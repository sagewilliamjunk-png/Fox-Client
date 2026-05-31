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
  // MemoryLeakFix: no Minecraft 26.x build on Modrinth yet — removed so it
  // stops failing on every auto-install. Re-add when a 26.x version ships.
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

  // ----------------------------------------------------------------
  // Universally-loved QoL mods that ship in nearly every modpack a
  // typical Modrinth user puts together. Adding them here means a
  // first-launch user gets the same experience without hunting for
  // each one individually on Modrinth.
  // ----------------------------------------------------------------
  // EMI (recipe viewer): no Minecraft 26.x build on Modrinth yet — removed so
  // it stops failing on every auto-install. Re-add when a 26.x version ships.
  { slug: 'mouse-tweaks',          displayName: 'Mouse Tweaks', essential: true,
    description: 'Drag-and-distribute mouse controls for inventories. The "wait, vanilla doesn\'t do this?" mod.' },
  { slug: 'no-chat-reports',       displayName: 'No Chat Reports', essential: false,
    description: 'Removes the Mojang chat-report system. Privacy-first chat for multiplayer.' },
  { slug: 'visuality',             displayName: 'Visuality',   essential: false,
    description: 'Extra particle effects (water ripples, leaf falls, ender block ambient particles).' },
  // World Host: no Minecraft 26.x build on Modrinth yet — removed so it stops
  // failing on every auto-install. Re-add when a 26.x version ships.
  { slug: '3dskinlayers',          displayName: '3D Skin Layers', essential: false,
    description: 'Renders the second skin layer in proper 3D — gives every player a bit more presence.' },
  { slug: 'continuity',            displayName: 'Continuity',  essential: false,
    description: 'OptiFine-style connected textures and emissive overlays for resource packs.' },
  { slug: 'visual-workbench',      displayName: 'Visual Workbench', essential: false,
    description: 'Crafting table keeps the items you place in it; lets you see them in the world.' },
  { slug: 'lambdynamiclights',     displayName: 'LambDynamicLights', essential: false,
    description: 'Dynamic lighting — torches in hand, glowstone in inventory, magma cubes etc all emit light.' },
  { slug: 'better-mount-hud',      displayName: 'Better Mount HUD', essential: false,
    description: 'When riding, shows your mount\'s health, jump strength, and saddle stats.' },
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

/** Transitive dependencies live under a sibling `deps:` map keyed by Modrinth
 *  project_id. Lazy-create so old manifests stay readable. */
function depsBucket(mf) {
  if (!mf.deps || typeof mf.deps !== 'object') mf.deps = {};
  return mf.deps;
}

/** True only when the dep's recorded jar exists on disk for this mcVersion. */
function isDepInstalled(projectId, modsDir, gameDir, mcVersion) {
  const rec = depsBucket(readManifest(gameDir))[projectId];
  if (!rec || rec.mcVersion !== mcVersion || !rec.filename) return false;
  try { return fs.existsSync(path.join(modsDir, rec.filename)); }
  catch (_) { return false; }
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
  const modsDir = path.join(gameDir, 'mods');
  fs.mkdirSync(modsDir, { recursive: true });

  // Build a per-call context unless one was threaded in by installAll. The ctx
  // carries the cycle-guard set, the deps-installed buffer, and overridable
  // network calls so tests can mock fetches without hitting Modrinth.
  const ctx = opts.ctx || {
    visited: new Set(),
    deps: [],
    modsDir,
    onProgress: typeof opts.onProgress === 'function' ? opts.onProgress : () => {},
    fetchJson: opts.fetchJson || fetchJson,
    fetchVersion: opts.fetchVersion || ((vid) => fetchJson(`${MODRINTH_BASE}/version/${vid}`)),
    fetchBuffer: opts.fetchBuffer || fetchWithRetry,
  };
  const onProgress = ctx.onProgress;

  // Already installed for this MC version? Even so, fetch the recorded
  // version's dependencies and walk them — that's how existing-but-broken
  // installs (Visuality without cloth-config etc.) self-heal next boot.
  if (isCurrentVersionInstalled(slug, modsDir, gameDir, mcVersion)) {
    try {
      const versions = await ctx.fetchJson(`${MODRINTH_BASE}/project/${encodeURIComponent(slug)}/version`);
      const mf = readManifest(gameDir);
      const rec = mf[slug] || {};
      const version =
        (rec.versionId && versions.find(v => v.id === rec.versionId)) ||
        pickVersion(versions, mcVersion);
      if (version) {
        // Back-fill projectId/versionId so future runs skip this lookup.
        mf[slug] = {
          mcVersion,
          filename: rec.filename || ((version.files.find(f => f.primary) || version.files[0]) || {}).filename,
          installedAt: rec.installedAt || Date.now(),
          projectId: version.project_id,
          versionId: version.id,
        };
        writeManifest(gameDir, mf);
        await resolveAndInstallDeps(version, slug, gameDir, mcVersion, ctx);
      }
    } catch (_) { /* best-effort dep heal */ }
    return { slug, status: 'skipped' };
  }

  // Jar exists but was built for a different MC version — clear before download.
  removeStaleJar(slug, modsDir);

  onProgress(`Looking up ${slug}…`);
  let versions;
  try {
    versions = await ctx.fetchJson(`${MODRINTH_BASE}/project/${encodeURIComponent(slug)}/version`);
  } catch (err) {
    return { slug, status: 'error', error: err.message };
  }

  const version = pickVersion(versions, mcVersion);
  if (!version || !Array.isArray(version.files) || !version.files.length) {
    return { slug, status: 'no-version' };
  }
  return await installResolved(slug, version, gameDir, mcVersion, ctx, /*isDep*/ false);
}

/**
 * Download + install a fully-resolved Modrinth version. Reused by installOne
 * (top-level mods) and resolveAndInstallDeps (transitive required deps).
 *
 * For dep installs, idKey is the project_id and the manifest record lands in
 * the `deps:` bucket; for top-level installs idKey is the slug.
 */
async function installResolved(idKey, version, gameDir, mcVersion, ctx, isDep) {
  const modsDir = ctx.modsDir;
  const onProgress = ctx.onProgress;
  const file = version.files.find(f => f.primary) || version.files[0];
  const target = path.join(modsDir, file.filename);

  // Filename-renamed dedup (e.g. simple-voice-chat 2.6.17 → 2.6.18). The
  // manifest records the real filename, so use it directly. Skip when the
  // name is unchanged (writeAtomic overwrites in place).
  const mfPre = readManifest(gameDir);
  const prevEntry = isDep ? depsBucket(mfPre)[idKey] : mfPre[idKey];
  if (prevEntry && prevEntry.filename && prevEntry.filename !== file.filename) {
    try { fs.unlinkSync(path.join(modsDir, prevEntry.filename)); } catch (_) {}
  }

  onProgress(`Downloading ${file.filename}…`);
  let buf;
  try { buf = await ctx.fetchBuffer(file.url); }
  catch (err) { return { slug: idKey, status: 'error', error: err.message, dep: isDep }; }

  // Hash verify (prefer sha512, fall back to sha1).
  const hashes = file.hashes || {};
  if (hashes.sha512) {
    const actual = crypto.createHash('sha512').update(buf).digest('hex');
    if (actual !== hashes.sha512) {
      return { slug: idKey, status: 'error',
        error: `Hash mismatch for ${file.filename} (expected ${hashes.sha512.slice(0, 16)}…, got ${actual.slice(0, 16)}…)`,
        dep: isDep };
    }
  } else if (hashes.sha1) {
    const actual = crypto.createHash('sha1').update(buf).digest('hex');
    if (actual !== hashes.sha1) {
      return { slug: idKey, status: 'error', error: `Hash mismatch for ${file.filename}`, dep: isDep };
    }
  }

  try { await writeAtomic(target, buf); }
  catch (err) { return { slug: idKey, status: 'error', error: err.message, dep: isDep }; }

  // Record in the manifest. Top-level idKey lives at root; deps live under `deps:`.
  const mf = readManifest(gameDir);
  const record = {
    mcVersion,
    filename: file.filename,
    installedAt: Date.now(),
    projectId: version.project_id,
    versionId: version.id,
  };
  if (isDep) depsBucket(mf)[idKey] = record;
  else       mf[idKey] = record;
  writeManifest(gameDir, mf);

  // Now resolve this version's required dependencies, transitively.
  await resolveAndInstallDeps(version, idKey, gameDir, mcVersion, ctx);

  return { slug: idKey, status: 'installed', file: file.filename, dep: isDep };
}

/**
 * Walk `version.dependencies` and install every `required` entry the user
 * doesn't already have. Pushes a result row per dep into ctx.deps so the
 * caller can append them to its top-level results list.
 */
async function resolveAndInstallDeps(version, parentKey, gameDir, mcVersion, ctx) {
  const modsDir = ctx.modsDir;
  const deps = Array.isArray(version.dependencies) ? version.dependencies : [];
  for (const dep of deps) {
    // Only `required` matters — optional/incompatible/embedded are not installed.
    if (dep.dependency_type !== 'required') continue;
    const pid = dep.project_id;
    if (!pid) continue;
    if (ctx.visited.has(pid)) continue;
    ctx.visited.add(pid);

    // Already recorded as installed for this MC version → skip without fetch.
    if (isDepInstalled(pid, modsDir, gameDir, mcVersion)) {
      ctx.deps.push({ slug: pid, displayName: '(dependency)', status: 'skipped', dep: true });
      continue;
    }

    // If the parent pinned a specific version, fetch it directly; otherwise
    // list project versions and pickVersion against our MC target.
    let depVersion;
    try {
      if (dep.version_id) depVersion = await ctx.fetchVersion(dep.version_id);
      else {
        const list = await ctx.fetchJson(`${MODRINTH_BASE}/project/${encodeURIComponent(pid)}/version`);
        depVersion = pickVersion(list, mcVersion);
      }
    } catch (err) {
      ctx.deps.push({ slug: pid, displayName: '(dependency)', status: 'error', error: err.message, dep: true });
      continue;
    }
    if (!depVersion || !Array.isArray(depVersion.files) || !depVersion.files.length) {
      ctx.deps.push({ slug: pid, displayName: '(dependency)', status: 'no-version', dep: true });
      continue;
    }

    // Shortcut: the file is already on disk (e.g. previously installed by hand
    // or carried over from before this code existed). Record it and move on.
    const file = depVersion.files.find(f => f.primary) || depVersion.files[0];
    if (fs.existsSync(path.join(modsDir, file.filename))) {
      const mf = readManifest(gameDir);
      depsBucket(mf)[pid] = {
        mcVersion, filename: file.filename, installedAt: Date.now(),
        projectId: pid, versionId: depVersion.id, parentSlug: parentKey,
      };
      writeManifest(gameDir, mf);
      ctx.deps.push({ slug: pid, displayName: '(dependency)', status: 'skipped', dep: true });
      continue;
    }

    const r = await installResolved(pid, depVersion, gameDir, mcVersion, ctx, /*isDep*/ true);
    if (r.status === 'installed') {
      const mf = readManifest(gameDir);
      const rec = depsBucket(mf)[pid];
      if (rec) { rec.parentSlug = parentKey; writeManifest(gameDir, mf); }
    }
    ctx.deps.push({ ...r, displayName: '(dependency)' });
  }
}

/** Install every recommended mod that isn't already present. `essentialOnly`
 *  defaults to true so the first-launch flow doesn't haul in optional ones
 *  like Iris without explicit user consent. */
async function installAll(gameDir, mcVersion, opts = {}) {
  const onProgress = typeof opts.onProgress === 'function' ? opts.onProgress : () => {};
  const essentialOnly = opts.essentialOnly !== false;

  const list = RECOMMENDED.filter(m => !essentialOnly || m.essential);
  const modsDir = path.join(gameDir, 'mods');
  // One shared `visited` across the whole pass so fabric-api only resolves once
  // even though half the recommended mods require it.
  const sharedVisited = new Set();
  const results = [];

  for (let i = 0; i < list.length; i++) {
    const { slug, displayName } = list[i];
    const stepBase = Math.round((i / list.length) * 100);
    const stepHalf = Math.round(((i + 0.5) / list.length) * 100);
    onProgress(`(${i + 1}/${list.length}) ${displayName}`, stepBase);

    const ctx = {
      visited: sharedVisited,           // shared Set instance
      deps: [],                         // fresh per call
      modsDir,
      onProgress: (msg) => onProgress(`(${i + 1}/${list.length}) ${msg}`, stepHalf),
      fetchJson,
      fetchVersion: (vid) => fetchJson(`${MODRINTH_BASE}/version/${vid}`),
      fetchBuffer: fetchWithRetry,
    };
    const r = await installOne(slug, gameDir, mcVersion, { ctx });
    results.push({ ...r, displayName });
    for (const d of ctx.deps) results.push(d);
  }
  onProgress('Done.', 100);
  return results;
}

function manifest() {
  return RECOMMENDED.map(m => ({ ...m }));
}

/** Back-compat: tests and external code may still want a simple "is the
 *  slug installed (any version)" check. Returns a boolean — the new
 *  internal logic uses findInstalledJar which returns a path. */
function isAlreadyInstalled(slug, modsDir) {
  return findInstalledJar(slug, modsDir) !== null;
}

module.exports = {
  installOne,
  installAll,
  manifest,
  RECOMMENDED,
  // helpers exposed for tests
  isAlreadyInstalled,
  findInstalledJar,
};
