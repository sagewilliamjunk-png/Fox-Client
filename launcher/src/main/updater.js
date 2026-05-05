// Client update checker + downloader.
//
// Polls GitHub Releases for the configured repo and downloads the latest
// asset matching `kitsune-client-*.jar`. Cached at
// ~/.foxlauncher/versions/<tag>/kitsune-client.jar.
//
// Uses HTTPS (no external deps). Retries transient failures with exponential
// backoff. Honors the 302 redirect chain for GitHub's CDN asset URLs.

const fs = require('fs');
const path = require('path');
const https = require('https');
const { URL } = require('url');
const crypto = require('crypto');
const paths = require('./paths');
const settings = require('./settings');

const USER_AGENT = 'FoxLauncher/0.1.0';
const MAX_RETRIES = 3;

function httpRequestFollow(urlStr, opts = {}) {
  return new Promise((resolve, reject) => {
    const doReq = (cur, redirects) => {
      if (redirects > 5) return reject(new Error('Too many redirects'));
      const u = new URL(cur);
      const req = https.request({
        method: opts.method || 'GET',
        hostname: u.hostname,
        path: u.pathname + u.search,
        headers: { 'User-Agent': USER_AGENT, ...(opts.headers || {}) },
      }, (res) => {
        if ([301, 302, 303, 307, 308].includes(res.statusCode) && res.headers.location) {
          res.resume();
          const next = new URL(res.headers.location, cur).toString();
          return doReq(next, redirects + 1);
        }
        resolve(res);
      });
      req.on('error', reject);
      req.end();
    };
    doReq(urlStr, 0);
  });
}

async function fetchJson(url) {
  const res = await httpRequestFollow(url, {
    headers: { Accept: 'application/vnd.github+json' },
  });
  if (res.statusCode < 200 || res.statusCode >= 300) {
    res.resume();
    throw new Error(`HTTP ${res.statusCode} from ${url}`);
  }
  let data = '';
  for await (const chunk of res) data += chunk;
  return JSON.parse(data);
}

async function downloadToFile(url, dest) {
  const res = await httpRequestFollow(url);
  if (res.statusCode < 200 || res.statusCode >= 300) {
    res.resume();
    throw new Error(`HTTP ${res.statusCode} downloading ${url}`);
  }
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  const tmp = dest + '.part';
  await new Promise((resolve, reject) => {
    const ws = fs.createWriteStream(tmp);
    res.pipe(ws);
    ws.on('finish', resolve);
    ws.on('error', reject);
    res.on('error', reject);
  });
  fs.renameSync(tmp, dest);
}

function sha256File(p) {
  const h = crypto.createHash('sha256');
  const data = fs.readFileSync(p);
  h.update(data);
  return h.digest('hex');
}

/** Read the manifest of what's currently installed. */
function readManifest() {
  const p = path.join(paths.versions, 'manifest.json');
  try { return JSON.parse(fs.readFileSync(p, 'utf8')); } catch (_) { return { installed: null }; }
}

function writeManifest(m) {
  paths.ensureAll();
  const p = path.join(paths.versions, 'manifest.json');
  fs.writeFileSync(p, JSON.stringify(m, null, 2));
}

function currentLocalJar() {
  const m = readManifest();
  if (m.installed && m.installed.path && fs.existsSync(m.installed.path)) return m.installed.path;
  return null;
}

/**
 * Poll the configured GitHub repo for a newer release. If found, download the
 * client jar. Returns { tag, path, updated: bool } or null if no asset found.
 */
async function checkAndDownload({ onProgress } = {}) {
  const s = settings.load();
  const repo = s.githubRepo;
  if (!repo) return null;

  const apiUrl = `https://api.github.com/repos/${repo}/releases/latest`;
  if (onProgress) onProgress(`Checking ${repo} for updates…`);

  let release;
  let lastErr;
  for (let attempt = 1; attempt <= MAX_RETRIES; attempt++) {
    try {
      release = await fetchJson(apiUrl);
      break;
    } catch (err) {
      lastErr = err;
      if (attempt < MAX_RETRIES) {
        await new Promise(r => setTimeout(r, 500 * 2 ** attempt));
      }
    }
  }
  if (!release) throw lastErr || new Error('Failed to fetch release info');

  const asset = (release.assets || []).find(a => /kitsune-client.*\.jar$/i.test(a.name))
             || (release.assets || []).find(a => /\.jar$/i.test(a.name));
  if (!asset) return null;

  const manifest = readManifest();
  const tag = release.tag_name;
  if (manifest.installed && manifest.installed.tag === tag && manifest.installed.assetId === asset.id) {
    if (onProgress) onProgress(`Client up to date (${tag}).`);
    const p = manifest.installed.path;
    if (fs.existsSync(p)) return { tag, path: p, updated: false };
  }

  const dest = path.join(paths.versions, tag, asset.name);
  if (onProgress) onProgress(`Downloading ${asset.name} (${Math.round(asset.size / 1024 / 1024)} MB)…`);

  let downloaded = false;
  lastErr = null;
  for (let attempt = 1; attempt <= MAX_RETRIES; attempt++) {
    try {
      await downloadToFile(asset.browser_download_url, dest);
      downloaded = true;
      break;
    } catch (err) {
      lastErr = err;
      if (onProgress) onProgress(`Download attempt ${attempt} failed: ${err.message}`);
      if (attempt < MAX_RETRIES) {
        await new Promise(r => setTimeout(r, 1000 * 2 ** attempt));
      }
    }
  }
  if (!downloaded) throw lastErr || new Error('Download failed after retries');

  writeManifest({
    installed: {
      tag,
      assetId: asset.id,
      assetName: asset.name,
      path: dest,
      sha256: sha256File(dest),
      downloadedAt: Date.now(),
    },
    latestSeen: { tag, name: release.name, publishedAt: release.published_at, body: release.body },
  });

  if (onProgress) onProgress(`Installed ${tag}.`);
  return { tag, path: dest, updated: true };
}

/** Summary for the home screen — what we last downloaded and what release notes say. */
function summary() {
  const m = readManifest();
  return {
    installed: m.installed || null,
    latestSeen: m.latestSeen || null,
  };
}

module.exports = { checkAndDownload, currentLocalJar, summary };
