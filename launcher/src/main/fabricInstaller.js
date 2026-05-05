// Headless Fabric installer.
//
// Replaces the "go run the official Fabric installer .jar" step with a direct
// fetch of the loader profile JSON + every Fabric library it lists from
// `meta.fabricmc.net` and `maven.fabricmc.net`. Vanilla MC libraries are NOT
// downloaded here — the Fabric profile inheritsFrom the vanilla profile,
// which the user still gets via the official Mojang launcher.
//
// Network behaviour:
//   - All HTTPS, with 15 s connect timeout and 5 retries on transient errors.
//   - Each library write is atomic (.tmp + rename) so a crash mid-download
//     can't leave a half-written jar that fools later runs.
//   - Existing libraries are skipped — install() can be called repeatedly
//     and only does work when something changed (loader version bumped, etc.).

const fs = require('fs');
const path = require('path');
const https = require('https');
const { URL } = require('url');

const META_BASE  = 'https://meta.fabricmc.net/v2';
const MAVEN_FALLBACK = 'https://maven.fabricmc.net/';
const MODRINTH_API = 'https://api.modrinth.com/v2';
const FABRIC_API_PROJECT = 'P7dR8mSH'; // Fabric API Modrinth project ID
const REQUEST_TIMEOUT_MS = 15_000;
const MAX_RETRIES = 5;

// ---- HTTP helpers ----

function fetchBuffer(urlStr, attempt = 0) {
  return new Promise((resolve, reject) => {
    let u;
    try { u = new URL(urlStr); } catch (e) { return reject(e); }
    const req = https.request({
      method: 'GET',
      hostname: u.hostname,
      port: u.port || 443,
      path: u.pathname + u.search,
      headers: { 'User-Agent': 'Fox-Launcher', Accept: '*/*' },
      timeout: REQUEST_TIMEOUT_MS,
    }, (res) => {
      // Follow up to 5 redirects.
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
      // Exponential backoff capped at 5 s — keeps the install responsive.
      await new Promise(r => setTimeout(r, Math.min(5000, 250 * Math.pow(2, i))));
    }
  }
  throw lastErr;
}

async function fetchJson(urlStr) {
  const buf = await fetchWithRetry(urlStr);
  return JSON.parse(buf.toString('utf8'));
}

// ---- Maven coordinate parsing ----

/**
 * Convert a maven name like `net.fabricmc:fabric-loader:0.18.6` (optional
 * `:classifier`) into the relative path used inside <gameDir>/libraries.
 */
function mavenPath(name) {
  // groupId:artifactId:version[:classifier]
  const parts = name.split(':');
  if (parts.length < 3) throw new Error(`Bad maven name: ${name}`);
  const [groupId, artifactId, version, classifier] = parts;
  const groupPath = groupId.replace(/\./g, '/');
  const fileName = classifier
    ? `${artifactId}-${version}-${classifier}.jar`
    : `${artifactId}-${version}.jar`;
  return `${groupPath}/${artifactId}/${version}/${fileName}`;
}

// ---- public API ----

/**
 * @returns {Promise<string>} loader version string, e.g. "0.18.6"
 */
async function latestStableLoader() {
  const list = await fetchJson(`${META_BASE}/versions/loader`);
  if (!Array.isArray(list)) throw new Error('Unexpected loader-list shape');
  const stable = list.find(l => l && l.stable);
  return (stable || list[0]).version;
}

async function profileForVersion(mcVersion, loaderVersion) {
  return fetchJson(`${META_BASE}/versions/loader/${encodeURIComponent(mcVersion)}/${encodeURIComponent(loaderVersion)}/profile/json`);
}

/**
 * Install a Fabric loader profile + all its libraries into `<gameDir>`.
 *
 * - Downloads the profile JSON from meta.fabricmc.net.
 * - Writes it to <gameDir>/versions/<profileId>/<profileId>.json.
 * - For every library in the profile, downloads to
 *   <gameDir>/libraries/<mavenPath> if not already present.
 *
 * Idempotent — skips work that's already done.
 *
 * @param {string} gameDir
 * @param {string} mcVersion
 * @param {object} [opts]
 * @param {(msg: string, pct: number) => void} [opts.onProgress]
 * @param {string} [opts.loaderVersion]   override; default = latest stable
 *
 * @returns {Promise<{profileId, downloaded, skipped}>}
 */
async function install(gameDir, mcVersion, opts = {}) {
  const onProgress = typeof opts.onProgress === 'function' ? opts.onProgress : () => {};

  onProgress('Looking up latest Fabric loader…', 0);
  const loaderVersion = opts.loaderVersion || await latestStableLoader();

  onProgress(`Fetching Fabric ${loaderVersion} profile for Minecraft ${mcVersion}…`, 5);
  const profile = await profileForVersion(mcVersion, loaderVersion);
  const profileId = profile.id;
  if (!profileId || typeof profileId !== 'string') {
    throw new Error('Fabric profile missing id field');
  }

  // Write profile JSON atomically.
  const versionDir = path.join(gameDir, 'versions', profileId);
  fs.mkdirSync(versionDir, { recursive: true });
  const profilePath = path.join(versionDir, `${profileId}.json`);
  await writeAtomic(profilePath, JSON.stringify(profile, null, 2));
  onProgress(`Profile written: ${profileId}`, 10);

  // Collect every library that needs a download. Skip ones where the file
  // already exists locally (lets the user re-run install() safely).
  const libs = Array.isArray(profile.libraries) ? profile.libraries : [];
  const todo = [];
  for (const lib of libs) {
    if (!lib || !lib.name) continue;
    const rel = mavenPath(lib.name);
    const local = path.join(gameDir, 'libraries', rel);
    if (fs.existsSync(local)) continue;
    const repo = (lib.url && /^https?:/.test(lib.url)) ? lib.url : MAVEN_FALLBACK;
    const url = repo.endsWith('/') ? repo + rel : repo + '/' + rel;
    todo.push({ url, local, name: lib.name });
  }

  let downloaded = 0;
  let skipped   = libs.length - todo.length;
  for (let i = 0; i < todo.length; i++) {
    const { url, local, name } = todo[i];
    const pct = 10 + Math.round(((i + 1) / Math.max(1, todo.length)) * 88);
    onProgress(`Downloading ${i + 1}/${todo.length}: ${name}`, pct);
    fs.mkdirSync(path.dirname(local), { recursive: true });
    const buf = await fetchWithRetry(url);
    await writeAtomic(local, buf);
    downloaded++;
  }

  onProgress(`Fabric ${loaderVersion} ready (${downloaded} downloaded, ${skipped} cached).`, 100);

  // Fabric API — required by virtually every Fabric mod. Download it silently
  // into <gameDir>/mods/ alongside the loader. This is a dependency, not a
  // user-managed mod, so it intentionally bypasses the mod manager catalog.
  try {
    await installFabricApi(gameDir, mcVersion, onProgress);
  } catch (err) {
    // Non-fatal — the game will still launch, just without Fabric API.
    onProgress(`Warning: Fabric API download failed: ${err.message}`, 100);
  }

  return { profileId, downloaded, skipped };
}

/**
 * Download the latest Fabric API release for `mcVersion` from Modrinth and
 * place it in `<gameDir>/mods/`. Idempotent — skips if any fabric-api jar is
 * already present.
 *
 * The jar is treated as a silent runtime dependency, not a managed mod. It
 * will not appear in the launcher's mod catalog.
 */
async function installFabricApi(gameDir, mcVersion, onProgress = () => {}) {
  const modsDir = path.join(gameDir, 'mods');

  // Check whether any fabric-api jar already exists.
  fs.mkdirSync(modsDir, { recursive: true });
  const existing = fs.readdirSync(modsDir).find(f => /^fabric-api[-_]/i.test(f));
  if (existing) return; // already installed

  onProgress('Fetching Fabric API from Modrinth…', 100);

  // Query Modrinth for the latest release matching this MC version + fabric loader.
  const versionsUrl =
    `${MODRINTH_API}/project/${FABRIC_API_PROJECT}/version` +
    `?game_versions=${encodeURIComponent(JSON.stringify([mcVersion]))}` +
    `&loaders=${encodeURIComponent(JSON.stringify(['fabric']))}`;

  const versions = await fetchJson(versionsUrl);
  if (!Array.isArray(versions) || versions.length === 0) {
    throw new Error(`No Fabric API release found for MC ${mcVersion}`);
  }

  // Modrinth returns newest first; pick the first release-channel entry.
  const release = versions.find(v => v.version_type === 'release') || versions[0];
  const primaryFile = (release.files || []).find(f => f.primary) || release.files?.[0];
  if (!primaryFile || !primaryFile.url) {
    throw new Error('Fabric API release has no downloadable file');
  }

  const fileName = primaryFile.filename || `fabric-api-${release.version_number}.jar`;
  const dest = path.join(modsDir, fileName);

  onProgress(`Downloading Fabric API ${release.version_number}…`, 100);
  const buf = await fetchWithRetry(primaryFile.url);
  await writeAtomic(dest, buf);
  onProgress(`Fabric API ${release.version_number} installed.`, 100);
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

module.exports = { install, installFabricApi, latestStableLoader, profileForVersion };
