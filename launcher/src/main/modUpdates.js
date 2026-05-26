// Mod update detection.
//
// On demand (or once at startup), hash every installed jar in a profile's
// mods folder and POST the hashes to Modrinth's /v2/version_files/update
// endpoint. The response tells us, per hash, what the latest matching
// version of that project is. If a hash differs from what's installed,
// we have an update.
//
// Modrinth's update endpoint is the right API to use here — it's batched,
// so 50 mods = one HTTP round-trip instead of 50.

const fs     = require('fs');
const path   = require('path');
const https  = require('https');
const crypto = require('crypto');

const MODRINTH_BASE = 'https://api.modrinth.com/v2';
const REQUEST_TIMEOUT_MS = 20_000;

/** POST helper that handles redirects + timeout + JSON body. Kept inline
 *  rather than threading through httpClient because httpClient is GET-only. */
function postJson(urlStr, body) {
  return new Promise((resolve, reject) => {
    const u = new URL(urlStr);
    const payload = Buffer.from(JSON.stringify(body), 'utf8');
    const req = https.request({
      method:   'POST',
      hostname: u.hostname,
      port:     u.port || 443,
      path:     u.pathname + u.search,
      headers: {
        'Content-Type':   'application/json',
        'Content-Length': payload.length,
        'User-Agent':     'Fox-Launcher (mod-updates)',
        'Accept':         'application/json',
      },
      timeout: REQUEST_TIMEOUT_MS,
    }, (res) => {
      if (res.statusCode < 200 || res.statusCode >= 300) {
        res.resume();
        return reject(new Error(`HTTP ${res.statusCode} for ${urlStr}`));
      }
      const chunks = [];
      res.on('data',  (c) => chunks.push(c));
      res.on('end',   () => {
        try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))); }
        catch (e) { reject(e); }
      });
      res.on('error', reject);
    });
    req.on('error',   reject);
    req.on('timeout', () => req.destroy(new Error(`Request timed out: ${urlStr}`)));
    req.write(payload);
    req.end();
  });
}

/** SHA-512 hex digest of a file. Returns null on read failure. */
function sha512OfFile(filePath) {
  try {
    const buf = fs.readFileSync(filePath);
    return crypto.createHash('sha512').update(buf).digest('hex');
  } catch (_) {
    return null;
  }
}

/** Enumerate every .jar (and .jar.disabled) under modsDir alongside its hash.
 *  Skips files we can't read. */
function hashInstalledJars(modsDir) {
  let entries;
  try { entries = fs.readdirSync(modsDir); }
  catch (_) { return []; }
  const out = [];
  for (const name of entries) {
    if (!/\.(jar|jar\.disabled)$/i.test(name)) continue;
    const full = path.join(modsDir, name);
    const hash = sha512OfFile(full);
    if (hash) out.push({ name, path: full, hash });
  }
  return out;
}

/**
 * Check every installed jar against Modrinth's latest for the given MC version.
 * Returns:
 *   {
 *     gameDir,
 *     mcVersion,
 *     scanned: number,           // jars hashed
 *     resolved: number,          // jars matched to a Modrinth project
 *     updates: [{
 *       filename, currentHash,
 *       project: { id, slug, title, icon },
 *       latest:  { id, versionNumber, datePublished, primaryFile: { filename, url, sha512 } },
 *     }, ...]
 *   }
 *
 * Jars that aren't on Modrinth (modpack-bundled internal jars, custom builds)
 * are silently skipped. Jars whose hash matches the latest are also silently
 * dropped — they're already up to date.
 */
async function checkForUpdates(gameDir, mcVersion) {
  const modsDir = path.join(gameDir, 'mods');
  const installed = hashInstalledJars(modsDir);
  if (installed.length === 0) {
    return { gameDir, mcVersion, scanned: 0, resolved: 0, updates: [] };
  }

  // Batched lookup: send all hashes in one POST.
  const hashes = installed.map(i => i.hash);
  let lookup;
  try {
    lookup = await postJson(`${MODRINTH_BASE}/version_files/update`, {
      hashes,
      algorithm: 'sha512',
      loaders:    ['fabric'],
      game_versions: [mcVersion],
    });
  } catch (err) {
    return { gameDir, mcVersion, scanned: installed.length, resolved: 0, updates: [], error: err.message };
  }

  // Response shape: { "<hash>": <Version object> } — present only for jars
  // Modrinth knows about. Missing keys = unknown jar (skip).
  const updates = [];
  let resolved = 0;
  for (const inst of installed) {
    const v = lookup[inst.hash];
    if (!v) continue;
    resolved++;
    const primary = (v.files || []).find(f => f.primary) || (v.files || [])[0];
    if (!primary || !primary.hashes || !primary.hashes.sha512) continue;
    // If the primary file's sha512 matches what's installed, no update.
    if (primary.hashes.sha512.toLowerCase() === inst.hash.toLowerCase()) continue;
    updates.push({
      filename:    inst.name,
      currentHash: inst.hash,
      project: {
        id:   v.project_id,
        slug: null, // resolved lazily by the renderer if it needs the title
      },
      latest: {
        id:            v.id,
        versionNumber: v.version_number,
        datePublished: v.date_published,
        primaryFile: {
          filename: primary.filename,
          url:      primary.url,
          sha512:   primary.hashes.sha512,
        },
      },
    });
  }
  return { gameDir, mcVersion, scanned: installed.length, resolved, updates };
}

/**
 * Apply a single update: download the new jar, hash-verify, delete the old
 * file, write the new one atomically. Returns { ok, error?, newFilename? }.
 */
async function applyUpdate(gameDir, update) {
  const modsDir = path.join(gameDir, 'mods');
  const oldPath = path.join(modsDir, update.filename);
  const newFilename = update.latest.primaryFile.filename;
  const newPath = path.join(modsDir, newFilename);
  const tmpPath = newPath + '.tmp';

  // Download.
  let buf;
  try {
    buf = await new Promise((resolve, reject) => {
      const u = new URL(update.latest.primaryFile.url);
      const req = https.request({
        method:   'GET',
        hostname: u.hostname,
        port:     u.port || 443,
        path:     u.pathname + u.search,
        headers:  { 'User-Agent': 'Fox-Launcher (mod-updates)' },
        timeout:  REQUEST_TIMEOUT_MS,
      }, (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
          res.resume();
          // Simple one-hop redirect; Modrinth CDN normally returns a 302 to cdn.
          https.get(res.headers.location, (r2) => {
            const chunks = [];
            r2.on('data',  (c) => chunks.push(c));
            r2.on('end',   () => resolve(Buffer.concat(chunks)));
            r2.on('error', reject);
          }).on('error', reject);
          return;
        }
        if (res.statusCode < 200 || res.statusCode >= 300) {
          return reject(new Error(`HTTP ${res.statusCode} for ${update.latest.primaryFile.url}`));
        }
        const chunks = [];
        res.on('data',  (c) => chunks.push(c));
        res.on('end',   () => resolve(Buffer.concat(chunks)));
        res.on('error', reject);
      });
      req.on('error',   reject);
      req.on('timeout', () => req.destroy(new Error('Request timed out')));
      req.end();
    });
  } catch (err) {
    return { ok: false, error: err.message };
  }

  // Hash verify.
  const actual = crypto.createHash('sha512').update(buf).digest('hex');
  if (actual.toLowerCase() !== update.latest.primaryFile.sha512.toLowerCase()) {
    return { ok: false, error: 'SHA-512 mismatch — refusing to install' };
  }

  // Write new file atomically, then drop the old one.
  try {
    fs.writeFileSync(tmpPath, buf);
    fs.renameSync(tmpPath, newPath);
  } catch (err) {
    return { ok: false, error: err.message };
  }
  // Skip the delete if old == new (filename unchanged, just newer content).
  if (oldPath !== newPath) {
    try { fs.unlinkSync(oldPath); } catch (_) { /* best-effort */ }
  }
  return { ok: true, newFilename };
}

module.exports = { checkForUpdates, applyUpdate };
