// Automatic JRE download from Eclipse Temurin (Adoptium).
//
// Mirrors what Modrinth App and Prism Launcher do: if no suitable Java is
// found on the system, download a JRE into ~/.foxlauncher/java/ and use it
// automatically. The user never has to visit adoptium.net or java.com.
//
// Adoptium v3 API used:
//   GET https://api.adoptium.net/v3/assets/latest/21/hotspot
//       ?os={windows|linux|mac}&arch={x64|aarch64|x86}&image_type=jre
//
// Download pipeline:
//   1. Fetch JSON metadata → get download URL + semver tag
//   2. Stream .zip (Windows) or .tar.gz (Linux/macOS) to a .part file
//   3. Rename .part → archive on completion
//   4. Extract with `tar` (available on all supported platforms)
//   5. Write a sentinel file so the next launch skips steps 1-4
//   6. Walk the extracted tree to locate the `java` executable
//
// Zero external npm dependencies — only Node built-ins (https, fs, path,
// child_process, crypto).

const https = require('https');
const http  = require('http');
const fs    = require('fs');
const path  = require('path');
const crypto = require('crypto');
const { execFileSync } = require('child_process');
const { URL } = require('url');

const paths = require('./paths');

const REQUIRED_JAVA = 21;
const ADOPTIUM_API  = 'https://api.adoptium.net/v3/assets/latest';
const CONNECT_TIMEOUT_MS  = 15_000;
const DOWNLOAD_TIMEOUT_MS = 120_000;

// ---- platform helpers ----

function adoptiumOs() {
  switch (process.platform) {
    case 'win32':  return 'windows';
    case 'darwin': return 'mac';
    default:       return 'linux';
  }
}

function adoptiumArch() {
  switch (process.arch) {
    case 'x64':   return 'x64';
    case 'arm64': return 'aarch64';
    case 'ia32':  return 'x86';
    default:      return 'x64';
  }
}

function javaExeName() {
  return process.platform === 'win32' ? 'java.exe' : 'java';
}

function metadataUrl() {
  return `${ADOPTIUM_API}/${REQUIRED_JAVA}/hotspot?os=${adoptiumOs()}&arch=${adoptiumArch()}&image_type=jre`;
}

// ---- path helpers ----

function jreRoot() {
  return path.join(paths.root, 'java');
}

function sentinelPath(semver) {
  return path.join(jreRoot(), `.jre-${semver}.installed`);
}

// ---- find the java executable inside an extracted tree ----
// Adoptium archives contain a top-level directory like
//   jdk-21.0.3+9-jre/  (Windows) or  jdk-21.0.3+9-jre/  (Linux/mac)
// The actual binary is at  <top>/bin/java[.exe].
// We walk up to 4 levels deep to be robust against layout changes.

function findJavaExe(rootDir) {
  const target = javaExeName();

  function walk(dir, depth) {
    if (depth > 4) return null;
    let entries;
    try { entries = fs.readdirSync(dir); } catch (_) { return null; }

    // Direct hit in this dir (shouldn't happen but cover it)
    if (entries.includes(target)) {
      const p = path.join(dir, target);
      try { if (fs.statSync(p).isFile()) return p; } catch (_) {}
    }
    // bin/ subdir
    if (entries.includes('bin')) {
      const p = path.join(dir, 'bin', target);
      try { if (fs.statSync(p).isFile()) return p; } catch (_) {}
    }
    // Recurse into subdirectories
    for (const e of entries) {
      const sub = path.join(dir, e);
      try {
        if (fs.statSync(sub).isDirectory()) {
          const found = walk(sub, depth + 1);
          if (found) return found;
        }
      } catch (_) {}
    }
    return null;
  }
  return walk(rootDir, 0);
}

// ---- networking ----

function fetchJson(urlStr) {
  return new Promise((resolve, reject) => {
    const u = new URL(urlStr);
    const mod = u.protocol === 'https:' ? https : http;
    const req = mod.get(urlStr, { timeout: CONNECT_TIMEOUT_MS }, (res) => {
      const chunks = [];
      res.on('data', c => chunks.push(c));
      res.on('end', () => {
        try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))); }
        catch (e) { reject(new Error(`Non-JSON from Adoptium: ${e.message}`)); }
      });
      res.on('error', reject);
    });
    req.on('error', reject);
    req.on('timeout', () => req.destroy(new Error('Timeout fetching JRE metadata')));
  });
}

/**
 * Stream a file from `urlStr` → `destPath`, following up to 5 redirects.
 * `onProgress({ received, total, percent })` is called as data arrives.
 */
function downloadFile(urlStr, destPath, onProgress) {
  return new Promise((resolve, reject) => {
    function attempt(url, hops) {
      if (hops > 5) return reject(new Error('Too many redirects downloading JRE'));
      const u = new URL(url);
      const mod = u.protocol === 'https:' ? https : http;
      const req = mod.get(url, { timeout: DOWNLOAD_TIMEOUT_MS }, (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
          res.resume();
          return attempt(res.headers.location, hops + 1);
        }
        if (res.statusCode < 200 || res.statusCode >= 300) {
          res.resume();
          return reject(new Error(`HTTP ${res.statusCode} downloading JRE`));
        }
        const total = parseInt(res.headers['content-length'] || '0', 10);
        let received = 0;
        const out = fs.createWriteStream(destPath);
        out.on('error', reject);
        res.on('error', reject);
        res.on('data', chunk => {
          received += chunk.length;
          out.write(chunk);
          if (onProgress && total > 0) {
            onProgress({ received, total, percent: Math.floor(received / total * 100) });
          }
        });
        res.on('end', () => out.end(resolve));
      });
      req.on('error', reject);
      req.on('timeout', () => req.destroy(new Error('JRE download stalled')));
    }
    attempt(urlStr, 0);
  });
}

// ---- extraction ----
// `tar` is available on:
//   - Windows 10 1803+ (BSD tar bundled with the OS)
//   - All macOS versions
//   - All Linux distributions
// For .zip on Windows, modern `tar` also handles zip natively.

function extract(archivePath, destDir) {
  fs.mkdirSync(destDir, { recursive: true });
  execFileSync('tar', ['-xf', archivePath, '-C', destDir], {
    timeout: 120_000,
    windowsHide: true,
  });
}

// ---- public API ----

/**
 * Return the `java` executable path from the previously cached JRE, or
 * `null` if no JRE has been downloaded yet.
 */
function cachedJrePath() {
  try {
    const root = jreRoot();
    // Any .installed sentinel means extraction succeeded
    const sentinels = fs.readdirSync(root).filter(f => f.startsWith('.jre-') && f.endsWith('.installed'));
    if (!sentinels.length) return null;
    return findJavaExe(root);
  } catch (_) {
    return null;
  }
}

/**
 * Download and install a JRE from Adoptium if not already cached.
 * Resolves with the path to the `java` executable.
 *
 * @param {function} [onProgress]  Called with `{ stage, message, percent }`
 *   stage: 'checking' | 'fetching' | 'downloading' | 'extracting' | 'ready'
 */
async function ensureJre(onProgress) {
  const emit = (stage, message, percent = 0) => {
    if (onProgress) onProgress({ stage, message, percent });
  };

  emit('checking', 'Checking for bundled JRE…', 0);

  // Fast path: already installed
  const cached = cachedJrePath();
  if (cached) {
    emit('ready', 'JRE ready', 100);
    return cached;
  }

  // Fetch metadata
  emit('fetching', 'Fetching JRE download info…', 5);
  const assets = await fetchJson(metadataUrl());
  if (!Array.isArray(assets) || !assets.length) {
    throw new Error(`No Adoptium JRE found for ${adoptiumOs()}/${adoptiumArch()}`);
  }

  const asset   = assets[0];
  const binary  = asset.binary;
  const pkg     = binary.package;
  const semver  = (asset.version && asset.version.semver) ? asset.version.semver : `jre-${REQUIRED_JAVA}`;
  const dlUrl   = pkg.link;
  const filename = pkg.name;

  const root    = jreRoot();
  fs.mkdirSync(root, { recursive: true });

  const archivePath = path.join(root, filename);
  const partPath    = archivePath + '.part';

  // Download
  emit('downloading', `Downloading JRE ${semver}… 0%`, 10);
  await downloadFile(dlUrl, partPath, ({ percent }) => {
    emit('downloading', `Downloading JRE ${semver}… ${percent}%`, 10 + Math.floor(percent * 0.70));
  });

  // Verify size (Adoptium always includes content-length)
  if (pkg.size) {
    const actual = fs.statSync(partPath).size;
    if (actual !== pkg.size) {
      fs.unlinkSync(partPath);
      throw new Error(`JRE download size mismatch (got ${actual}, expected ${pkg.size})`);
    }
  }

  // Rename .part → archive
  fs.renameSync(partPath, archivePath);

  // Extract
  emit('extracting', 'Extracting JRE…', 82);
  extract(archivePath, root);

  // Clean up archive to save ~200 MB
  try { fs.unlinkSync(archivePath); } catch (_) {}

  // Write sentinel so future launches skip the download
  try { fs.writeFileSync(sentinelPath(semver), semver, 'utf8'); } catch (_) {}

  emit('ready', 'JRE installed', 100);

  const javaExe = findJavaExe(root);
  if (!javaExe) throw new Error('JRE extracted but java executable not found — please report this.');
  return javaExe;
}

module.exports = { ensureJre, cachedJrePath, REQUIRED_JAVA };
