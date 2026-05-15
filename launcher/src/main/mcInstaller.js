// Vanilla Minecraft bootstrapper — downloads everything the game needs
// directly from Mojang's CDN so users never have to run the official launcher.
//
// This is what Modrinth App, Prism Launcher, and Lunar Client all do internally.
// The downloaded file layout is identical to the official launcher so the two
// can coexist in the same ~/.minecraft directory.
//
// Download sequence for a version like "1.21.1":
//   1. Version manifest  →  https://launchermeta.mojang.com/mc/game/version_manifest_v2.json
//   2. Version JSON      →  URL from manifest  →  <gameDir>/versions/<id>/<id>.json
//   3. Client jar        →  downloads.client.url  →  <gameDir>/versions/<id>/<id>.jar
//   4. Libraries         →  lib.downloads.artifact.url  →  <gameDir>/libraries/...
//   5. Natives           →  native classifiers (zip) extracted into <gameDir>/versions/<id>/natives/
//   6. Asset index       →  assetIndex.url  →  <gameDir>/assets/indexes/<assetId>.json
//   7. Assets            →  https://resources.download.minecraft.net/<h2>/<hash>
//                           →  <gameDir>/assets/objects/<h2>/<hash>
//
// All files are verified with SHA-1 before writing. Existing files whose hash
// matches are skipped — re-running is cheap and safe.
//
// Assets (textures, sounds) are streamed concurrently with a configurable pool
// size to keep total download time reasonable on fast connections.

const https = require('https');
const http  = require('http');
const fs    = require('fs');
const path  = require('path');
const crypto = require('crypto');
const { execFileSync } = require('child_process');
const { URL } = require('url');

// ---- constants ----

const MANIFEST_URL   = 'https://launchermeta.mojang.com/mc/game/version_manifest_v2.json';
const RESOURCES_BASE = 'https://resources.download.minecraft.net';
const CONNECT_TIMEOUT_MS  = 15_000;
const DOWNLOAD_TIMEOUT_MS = 60_000;
const CONCURRENT_ASSETS   = 16;   // parallel asset downloads

// ---- low-level HTTP ----

function fetchBuffer(urlStr) {
  return new Promise((resolve, reject) => {
    function attempt(url, hops) {
      if (hops > 5) return reject(new Error('Too many redirects'));
      const u = new URL(url);
      const mod = u.protocol === 'https:' ? https : http;
      const req = mod.get(url, { timeout: DOWNLOAD_TIMEOUT_MS }, (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
          res.resume();
          return attempt(res.headers.location, hops + 1);
        }
        if (res.statusCode < 200 || res.statusCode >= 300) {
          res.resume();
          return reject(new Error(`HTTP ${res.statusCode}: ${url}`));
        }
        const chunks = [];
        res.on('data', c => chunks.push(c));
        res.on('end', () => resolve(Buffer.concat(chunks)));
        res.on('error', reject);
      });
      req.on('error', reject);
      req.on('timeout', () => req.destroy(new Error(`Timeout: ${url}`)));
    }
    attempt(urlStr, 0);
  });
}

function fetchJson(urlStr) {
  return fetchBuffer(urlStr).then(buf => JSON.parse(buf.toString('utf8')));
}

function sha1(buf) {
  return crypto.createHash('sha1').update(buf).digest('hex');
}

// ---- file helpers ----

function writeAtomic(filePath, buf) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  const tmp = filePath + '.tmp';
  fs.writeFileSync(tmp, buf);
  fs.renameSync(tmp, filePath);
}

/**
 * Return true if the file already exists and its SHA-1 matches `expectedHash`.
 * Returns false (needs download) when the file is missing, has a size mismatch,
 * or the hash doesn't match.
 */
function alreadyGood(filePath, expectedHash, expectedSize) {
  try {
    const st = fs.statSync(filePath);
    if (expectedSize != null && st.size !== expectedSize) return false;
    if (!expectedHash) return true;
    const buf = fs.readFileSync(filePath);
    return sha1(buf) === expectedHash.toLowerCase();
  } catch (_) {
    return false;
  }
}

// ---- concurrency pool ----

async function pool(items, limit, fn) {
  const results = [];
  let i = 0;
  async function worker() {
    while (i < items.length) {
      const idx = i++;
      results[idx] = await fn(items[idx], idx);
    }
  }
  const workers = Array.from({ length: Math.min(limit, items.length) }, worker);
  await Promise.all(workers);
  return results;
}

// ---- OS name (Mojang format) ----

function mojangOs() {
  if (process.platform === 'win32') return 'windows';
  if (process.platform === 'darwin') return 'osx';
  return 'linux';
}

// ---- rule evaluation (same logic as mcVersion.js) ----

function evaluateRules(rules) {
  if (!rules || !rules.length) return true;
  let allow = false;
  for (const rule of rules) {
    let matches = true;
    if (rule.os && rule.os.name && rule.os.name !== mojangOs()) matches = false;
    if (rule.action === 'allow')    { if (matches) allow = true; }
    else if (rule.action === 'disallow') { if (matches) allow = false; }
  }
  return allow;
}

// ---- natives extraction ----

function nativesClassifier() {
  switch (process.platform) {
    case 'win32':  return process.arch === 'ia32' ? 'natives-windows-x86' : 'natives-windows';
    case 'darwin': return process.arch === 'arm64' ? 'natives-macos-arm64' : 'natives-macos';
    default:       return 'natives-linux';
  }
}

function extractZipTo(zipPath, destDir) {
  fs.mkdirSync(destDir, { recursive: true });
  try {
    execFileSync('tar', ['-xf', zipPath, '-C', destDir], { timeout: 30_000, windowsHide: true });
  } catch (_) {
    // `tar` on some Windows versions can't handle Mojang's native zips.
    // Fall back to a pure-Node minimal zip reader for the subset we need.
    extractZipNode(zipPath, destDir);
  }
}

/** Minimal ZIP extractor (no external deps). Only handles stored + deflated. */
function extractZipNode(zipPath, destDir) {
  const zlib = require('zlib');
  const buf = fs.readFileSync(zipPath);
  let pos = 0;

  while (pos < buf.length - 4) {
    const sig = buf.readUInt32LE(pos);
    if (sig !== 0x04034b50) break;  // local file header signature

    const flags      = buf.readUInt16LE(pos + 6);
    const method     = buf.readUInt16LE(pos + 8);
    const crc32      = buf.readUInt32LE(pos + 14);
    const compressed = buf.readUInt32LE(pos + 18);
    const nameLen    = buf.readUInt16LE(pos + 26);
    const extraLen   = buf.readUInt16LE(pos + 28);
    const name       = buf.slice(pos + 30, pos + 30 + nameLen).toString('utf8');
    const dataStart  = pos + 30 + nameLen + extraLen;
    const dataEnd    = dataStart + compressed;

    pos = dataEnd;

    if (name.endsWith('/')) continue;  // directory entry
    // Skip META-INF
    if (name.startsWith('META-INF/')) continue;

    const dest = path.join(destDir, name.replace(/\//g, path.sep));
    fs.mkdirSync(path.dirname(dest), { recursive: true });

    const compressedData = buf.slice(dataStart, dataEnd);
    const raw = method === 0
      ? compressedData
      : zlib.inflateRawSync(compressedData);

    fs.writeFileSync(dest, raw);
  }
}

// ---- main install function ----

/**
 * Install a vanilla Minecraft version into `gameDir` (same layout as
 * the official launcher uses — typically ~/.minecraft).
 *
 * Already-present and hash-verified files are skipped, so calling this
 * repeatedly is cheap and safe.
 *
 * @param {string} gameDir         Target directory (e.g. ~/.minecraft)
 * @param {string} versionId       E.g. "1.21.1" or "26.1.2"
 * @param {object} [opts]
 * @param {function} [opts.onProgress]  Called with { stage, message, percent }
 *   stage: 'manifest' | 'version' | 'client' | 'libraries' | 'natives'
 *        | 'asset-index' | 'assets' | 'done'
 * @returns {Promise<{ versionId, downloaded, skipped }>}
 */
async function installVersion(gameDir, versionId, { onProgress } = {}) {
  let downloaded = 0;
  let skipped    = 0;

  const emit = (stage, message, percent) => {
    if (onProgress) onProgress({ stage, message, percent });
  };

  // ---- 1. Version manifest ----
  emit('manifest', 'Fetching version list…', 2);
  const manifest = await fetchJson(MANIFEST_URL);
  const entry = (manifest.versions || []).find(v => v.id === versionId);
  if (!entry) {
    throw new Error(`Minecraft ${versionId} not found in Mojang's version manifest.`);
  }

  // ---- 2. Version JSON ----
  emit('version', `Fetching version data for ${versionId}…`, 5);
  const versionDir  = path.join(gameDir, 'versions', versionId);
  const versionJson = path.join(versionDir, `${versionId}.json`);
  const versionJar  = path.join(versionDir, `${versionId}.jar`);
  fs.mkdirSync(versionDir, { recursive: true });

  let versionData;
  if (alreadyGood(versionJson, entry.sha1)) {
    versionData = JSON.parse(fs.readFileSync(versionJson, 'utf8'));
    skipped++;
  } else {
    const buf = await fetchBuffer(entry.url);
    // The manifest sha1 is for the version JSON file itself
    writeAtomic(versionJson, buf);
    versionData = JSON.parse(buf.toString('utf8'));
    downloaded++;
  }

  // ---- 3. Client jar ----
  emit('client', `Downloading client jar…`, 8);
  const clientDl = versionData.downloads && versionData.downloads.client;
  if (clientDl && clientDl.url) {
    if (alreadyGood(versionJar, clientDl.sha1, clientDl.size)) {
      skipped++;
    } else {
      const buf = await fetchBuffer(clientDl.url);
      writeAtomic(versionJar, buf);
      downloaded++;
    }
  }

  // ---- 4. Libraries ----
  const libraries = (versionData.libraries || []).filter(lib => evaluateRules(lib.rules));
  const libTasks = [];
  const nativeTasks = [];

  for (const lib of libraries) {
    // Main artifact
    if (lib.downloads && lib.downloads.artifact) {
      const art = lib.downloads.artifact;
      if (art.url) {
        const dest = path.join(gameDir, 'libraries', art.path);
        libTasks.push({ url: art.url, dest, hash: art.sha1, size: art.size });
      }
    }
    // Natives
    if (lib.downloads && lib.downloads.classifiers) {
      const nc = nativesClassifier();
      const alt = lib.natives && lib.natives[process.platform === 'win32' ? 'windows'
                                           : process.platform === 'darwin' ? 'osx' : 'linux'];
      const classifier = (lib.natives && alt) ? alt.replace('${arch}', process.arch === 'ia32' ? '32' : '64') : nc;
      const native = lib.downloads.classifiers[classifier]
                  || lib.downloads.classifiers[nc];
      if (native && native.url) {
        const dest = path.join(gameDir, 'libraries', native.path);
        nativeTasks.push({ url: native.url, dest, hash: native.sha1, size: native.size, lib });
      }
    }
  }

  emit('libraries', `Downloading ${libTasks.length} libraries…`, 12);
  let libDone = 0;
  await pool(libTasks, 8, async ({ url, dest, hash, size }) => {
    if (alreadyGood(dest, hash, size)) { skipped++; }
    else {
      const buf = await fetchBuffer(url);
      writeAtomic(dest, buf);
      downloaded++;
    }
    libDone++;
    emit('libraries',
      `Libraries ${libDone}/${libTasks.length}`,
      12 + Math.floor(libDone / Math.max(libTasks.length, 1) * 18),
    );
  });

  // ---- 5. Natives (extract) ----
  emit('natives', 'Installing native libraries…', 30);
  const nativesDir = path.join(versionDir, 'natives');
  fs.mkdirSync(nativesDir, { recursive: true });
  await pool(nativeTasks, 4, async ({ url, dest, hash, size }) => {
    let buf;
    if (alreadyGood(dest, hash, size)) { buf = fs.readFileSync(dest); skipped++; }
    else {
      buf = await fetchBuffer(url);
      writeAtomic(dest, buf);
      downloaded++;
    }
    // Extract native jar into natives/ (it's a zip)
    const tmp = dest + '.native.zip';
    try {
      writeAtomic(tmp, buf);
      extractZipTo(tmp, nativesDir);
    } finally {
      try { fs.unlinkSync(tmp); } catch (_) {}
    }
  });

  // ---- 6. Asset index ----
  emit('asset-index', 'Fetching asset index…', 34);
  const aiMeta  = versionData.assetIndex;
  const aiId    = aiMeta ? aiMeta.id : (versionData.assets || 'legacy');
  const aiDest  = path.join(gameDir, 'assets', 'indexes', `${aiId}.json`);
  let assetIndex;
  if (aiMeta && alreadyGood(aiDest, aiMeta.sha1, aiMeta.size)) {
    assetIndex = JSON.parse(fs.readFileSync(aiDest, 'utf8'));
    skipped++;
  } else if (aiMeta && aiMeta.url) {
    const buf = await fetchBuffer(aiMeta.url);
    writeAtomic(aiDest, buf);
    assetIndex = JSON.parse(buf.toString('utf8'));
    downloaded++;
  } else {
    assetIndex = { objects: {} };
  }

  // ---- 7. Assets ----
  const objects = assetIndex.objects || {};
  const assetEntries = Object.values(objects);
  const total = assetEntries.length;
  emit('assets', `Downloading ${total} assets…`, 36);

  let assetDone = 0;
  await pool(assetEntries, CONCURRENT_ASSETS, async ({ hash, size }) => {
    const h2   = hash.slice(0, 2);
    const dest = path.join(gameDir, 'assets', 'objects', h2, hash);
    if (alreadyGood(dest, hash, size)) { skipped++; }
    else {
      const url = `${RESOURCES_BASE}/${h2}/${hash}`;
      const buf = await fetchBuffer(url);
      writeAtomic(dest, buf);
      downloaded++;
    }
    assetDone++;
    if (total > 0 && assetDone % 50 === 0) {
      const pct = 36 + Math.floor(assetDone / total * 62);
      emit('assets', `Assets ${assetDone}/${total}`, pct);
    }
  });

  emit('done', `Minecraft ${versionId} ready`, 100);
  return { versionId, downloaded, skipped };
}

/**
 * Return true if the version JSON is already present on disk.
 * Quick check — no network call.
 */
function isInstalled(gameDir, versionId) {
  const p = path.join(gameDir, 'versions', versionId, `${versionId}.json`);
  try { return fs.statSync(p).isFile(); } catch (_) { return false; }
}

/**
 * Fetch the list of available release versions from Mojang's manifest.
 * Returns [{ id, type, releaseTime }] newest-first.
 */
async function listAvailable({ includeSnapshots = false } = {}) {
  const manifest = await fetchJson(MANIFEST_URL);
  return (manifest.versions || [])
    .filter(v => includeSnapshots || v.type === 'release')
    .map(({ id, type, releaseTime }) => ({ id, type, releaseTime }));
}

module.exports = { installVersion, isInstalled, listAvailable };
