// Curated set of mods Fox Launcher pre-installs for the user.
//
// Each entry references a Modrinth project by slug. At install time we hit
// `api.modrinth.com/v2/project/<slug>/version` filtered to `loader=fabric` +
// the targeted MC version, pick the newest matching primary file, and
// download it into <gameDir>/mods/<filename>.jar.
//
// Network behaviour mirrors fabricInstaller.js — 15 s timeout, atomic
// writes, follows up to 5 redirects, retries with exponential backoff up
// to 5 attempts. Idempotent: existing mods (by slug-prefix match against
// filenames in mods/) are skipped without a network call.

const fs = require('fs');
const path = require('path');
const https = require('https');
const { URL } = require('url');
const crypto = require('crypto');

const MODRINTH_BASE = 'https://api.modrinth.com/v2';
const REQUEST_TIMEOUT_MS = 15_000;
const MAX_RETRIES = 5;

/**
 * The default pack. Order matters — installs run sequentially so progress
 * messages stay readable. `essential: true` items get installed in the
 * "first launch" auto-flow; `essential: false` ones only on explicit user
 * action ("Install full recommended pack").
 */
const RECOMMENDED = [
  { slug: 'sodium',          displayName: 'Sodium',           essential: true,
    description: 'Modern rendering engine — huge FPS gains.' },
  { slug: 'lithium',         displayName: 'Lithium',          essential: true,
    description: 'Game-logic optimization without behaviour changes.' },
  { slug: 'ferrite-core',    displayName: 'FerriteCore',      essential: true,
    description: 'Cuts memory use of block models (~20–40% less RAM).' },
  { slug: 'memoryleakfix',   displayName: 'MemoryLeakFix',    essential: true,
    description: 'Patches several long-running JVM memory leaks.' },
  { slug: 'immediatelyfast', displayName: 'ImmediatelyFast',  essential: true,
    description: 'Optimizes immediate-mode rendering (GUI, particles).' },
  { slug: 'entityculling',   displayName: 'EntityCulling',    essential: true,
    description: 'Skips rendering entities you can\'t actually see.' },
  { slug: 'iris',            displayName: 'Iris Shaders',     essential: true,
    description: 'Shader-pack support — comes with Sodium.' },
  { slug: 'axiom',                 displayName: 'Axiom',       essential: false,
    description: 'In-game world editor — selections, brushes, blueprints, and undo history for creative building.' },
  { slug: 'modmenu',               displayName: 'Mod Menu',    essential: false,
    description: 'Adds a mods list to the main/pause menu — browse installed mods and open their config screens.' },
  { slug: 'jade',                  displayName: 'Jade',        essential: false,
    description: 'Shows block and entity info (name, data, mod source) when looking at them.' },
  { slug: 'chat-heads',            displayName: 'Chat Heads',  essential: false,
    description: 'Shows player head icons next to their chat messages.' },
  { slug: 'appleskin',             displayName: 'AppleSkin',   essential: false,
    description: 'Shows saturation/exhaustion on the hunger bar and food preview when holding food.' },
  { slug: 'entity-model-features', displayName: 'EMF',        essential: false,
    description: 'OptiFine-style custom entity models for resource packs.' },
  { slug: 'entitytexturefeatures', displayName: 'ETF',        essential: false,
    description: 'OptiFine-style random/emissive entity textures for resource packs.' },
  { slug: 'simple-voice-chat',     displayName: 'Simple Voice Chat', essential: false,
    description: 'Proximity voice chat — hear nearby players in-game (requires server-side mod).' },
];

// ---- HTTP helpers (same shape as fabricInstaller) ----

function fetchBuffer(urlStr, attempt = 0) {
  return new Promise((resolve, reject) => {
    let u;
    try { u = new URL(urlStr); } catch (e) { return reject(e); }
    const req = https.request({
      method: 'GET',
      hostname: u.hostname,
      port: u.port || 443,
      path: u.pathname + u.search,
      headers: { 'User-Agent': 'Fox-Launcher (recommended-mods)', Accept: 'application/json,*/*' },
      timeout: REQUEST_TIMEOUT_MS,
    }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location && attempt < 5) {
        res.resume();
        const next = new URL(res.headers.location, urlStr).toString();
        return fetchBuffer(next, attempt + 1).then(resolve, reject);
      }
      if (res.statusCode < 200 || res.statusCode >= 300) {
        res.resume();
        return reject(new Error(`HTTP ${res.statusCode} for ${urlStr}`));
      }
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve(Buffer.concat(chunks)));
      res.on('error', reject);
    });
    req.on('error', reject);
    req.on('timeout', () => req.destroy(new Error(`Request timed out: ${urlStr}`)));
    req.end();
  });
}

async function fetchWithRetry(urlStr) {
  let lastErr;
  for (let i = 0; i < MAX_RETRIES; i++) {
    try { return await fetchBuffer(urlStr); }
    catch (err) {
      lastErr = err;
      await new Promise(r => setTimeout(r, Math.min(5000, 250 * Math.pow(2, i))));
    }
  }
  throw lastErr;
}

async function fetchJson(urlStr) {
  const buf = await fetchWithRetry(urlStr);
  return JSON.parse(buf.toString('utf8'));
}

// ---- core ----

/** Pick the newest fabric-loader version that supports the given MC version.
 *  Modrinth returns versions newest-first by default. */
function pickVersion(versions, mcVersion) {
  if (!Array.isArray(versions)) return null;
  for (const v of versions) {
    const loaders = v.loaders || [];
    const games   = v.game_versions || [];
    if (loaders.includes('fabric') && games.includes(mcVersion)) return v;
  }
  return null;
}

// ---- version-aware install manifest ----
//
// Stored at <gameDir>/config/fox-launcher/recommended-mods.json.
// Shape: { slug → { mcVersion, filename } }
//
// On each install run we skip a mod only when BOTH:
//   1. A jar with the slug prefix exists on disk, AND
//   2. The manifest records it was installed for the CURRENT mcVersion.
//
// If the MC version changed (e.g. 26.1.1 → 26.1.2), the manifest entry
// is stale → we delete the old jar and download the new one.

function manifestPath(gameDir) {
  return path.join(gameDir, 'config', 'fox-launcher', 'recommended-mods.json');
}

function readManifest(gameDir) {
  try { return JSON.parse(fs.readFileSync(manifestPath(gameDir), 'utf8')); }
  catch (_) { return {}; }
}

function writeManifest(gameDir, data) {
  const p = manifestPath(gameDir);
  try { fs.mkdirSync(path.dirname(p), { recursive: true }); } catch (_) {}
  try { fs.writeFileSync(p + '.tmp', JSON.stringify(data, null, 2)); fs.renameSync(p + '.tmp', p); } catch (_) {}
}

/** Find any on-disk jar whose name starts with the slug (normalised).
 *  Returns the full path, or null. */
function findInstalledJar(slug, modsDir) {
  let entries;
  try { entries = fs.readdirSync(modsDir); } catch (_) { return null; }
  const needle = slug.toLowerCase().replace(/-/g, '');
  for (const f of entries) {
    if (!/\.(jar|jar\.disabled)$/i.test(f)) continue;
    if (f.toLowerCase().replace(/[-_.]/g, '').startsWith(needle)) return path.join(modsDir, f);
  }
  return null;
}

/** Returns true only when the jar exists AND was recorded as installed for
 *  the exact mcVersion we're targeting. */
function isCurrentVersionInstalled(slug, modsDir, gameDir, mcVersion) {
  const jarPath = findInstalledJar(slug, modsDir);
  if (!jarPath) return false;
  const m = readManifest(gameDir);
  return m[slug] && m[slug].mcVersion === mcVersion;
}

/** Remove any on-disk jar matching this slug (old MC-version build). */
function removeStaleJar(slug, modsDir) {
  const jarPath = findInstalledJar(slug, modsDir);
  if (jarPath) try { fs.unlinkSync(jarPath); } catch (_) {}
}

function writeAtomic(target, contents) {
  return new Promise((resolve, reject) => {
    const tmp = target + '.tmp';
    fs.writeFile(tmp, contents, (err) => {
      if (err) return reject(err);
      fs.rename(tmp, target, (e2) => e2 ? reject(e2) : resolve());
    });
  });
}

/**
 * Install one project from the recommended list. Returns a small status
 * object the renderer can render row-by-row.
 *
 * @returns {Promise<{slug, status: 'installed'|'skipped'|'no-version'|'error', file?, error?}>}
 */
async function installOne(slug, gameDir, mcVersion, opts = {}) {
  const onProgress = typeof opts.onProgress === 'function' ? opts.onProgress : () => {};
  const modsDir = path.join(gameDir, 'mods');
  fs.mkdirSync(modsDir, { recursive: true });

  if (isCurrentVersionInstalled(slug, modsDir, gameDir, mcVersion)) {
    return { slug, status: 'skipped' };
  }
  // Jar exists but was built for a different MC version — remove it before
  // downloading the correct one so we don't end up with two copies.
  removeStaleJar(slug, modsDir);

  onProgress(`Looking up ${slug}…`);
  let versions;
  try {
    versions = await fetchJson(`${MODRINTH_BASE}/project/${encodeURIComponent(slug)}/version`);
  } catch (err) {
    return { slug, status: 'error', error: err.message };
  }

  const version = pickVersion(versions, mcVersion);
  if (!version || !Array.isArray(version.files) || !version.files.length) {
    return { slug, status: 'no-version' };
  }
  // Prefer the file flagged primary; fall back to the first.
  const file = version.files.find(f => f.primary) || version.files[0];
  const target = path.join(modsDir, file.filename);

  onProgress(`Downloading ${file.filename}…`);
  let buf;
  try { buf = await fetchWithRetry(file.url); }
  catch (err) { return { slug, status: 'error', error: err.message }; }

  // Verify integrity using the hash Modrinth provides (prefer sha512, fall back to sha1).
  const hashes = file.hashes || {};
  if (hashes.sha512) {
    const actual = crypto.createHash('sha512').update(buf).digest('hex');
    if (actual !== hashes.sha512) {
      return { slug, status: 'error', error: `Hash mismatch for ${file.filename} (expected ${hashes.sha512.slice(0, 16)}…, got ${actual.slice(0, 16)}…)` };
    }
  } else if (hashes.sha1) {
    const actual = crypto.createHash('sha1').update(buf).digest('hex');
    if (actual !== hashes.sha1) {
      return { slug, status: 'error', error: `Hash mismatch for ${file.filename}` };
    }
  }

  try { await writeAtomic(target, buf); }
  catch (err) { return { slug, status: 'error', error: err.message }; }

  // Record what MC version this jar was built for so future runs can detect stale installs.
  const mf = readManifest(gameDir);
  mf[slug] = { mcVersion, filename: file.filename, installedAt: Date.now() };
  writeManifest(gameDir, mf);

  return { slug, status: 'installed', file: file.filename };
}

/** Install every recommended mod that isn't already present. `essentialOnly`
 *  defaults to true so the first-launch flow doesn't haul in optional ones
 *  like Iris without explicit user consent. */
async function installAll(gameDir, mcVersion, opts = {}) {
  const onProgress = typeof opts.onProgress === 'function' ? opts.onProgress : () => {};
  const essentialOnly = opts.essentialOnly !== false;

  const list = RECOMMENDED.filter(m => !essentialOnly || m.essential);
  const results = [];
  for (let i = 0; i < list.length; i++) {
    const { slug, displayName } = list[i];
    onProgress(`(${i + 1}/${list.length}) ${displayName}`, Math.round(((i) / list.length) * 100));
    const r = await installOne(slug, gameDir, mcVersion, {
      onProgress: (msg) => onProgress(`(${i + 1}/${list.length}) ${msg}`, Math.round(((i + 0.5) / list.length) * 100)),
    });
    results.push({ ...r, displayName });
  }
  onProgress('Done.', 100);
  return results;
}

function manifest() {
  return RECOMMENDED.map(m => ({ ...m }));
}

module.exports = { installOne, installAll, manifest, RECOMMENDED };
