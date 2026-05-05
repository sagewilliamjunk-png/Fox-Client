// Java detection and validation.
//
// Strategy:
//   1. If the user configured a specific `javaPath`, probe it.
//   2. Otherwise search JAVA_HOME, then PATH, then a short list of common
//      install locations for each OS.
//   3. Run `java -version` (Java prints this to stderr) and parse the version.
//
// We require Java 21+ for modern Minecraft (1.20.5+). The threshold is tunable.

const { spawn } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const REQUIRED_MAJOR = 21;

function javaExeName() {
  return process.platform === 'win32' ? 'java.exe' : 'java';
}

function commonCandidates() {
  const out = [];
  if (process.env.JAVA_HOME) out.push(path.join(process.env.JAVA_HOME, 'bin', javaExeName()));

  if (process.platform === 'win32') {
    const pf = process.env['ProgramFiles'] || 'C:\\Program Files';
    // Walk well-known vendor directories under Program Files
    for (const vendor of ['Java', 'Eclipse Adoptium', 'Zulu', 'Microsoft', 'Amazon Corretto']) {
      const vdir = path.join(pf, vendor);
      try {
        for (const entry of fs.readdirSync(vdir)) {
          const c = path.join(vdir, entry, 'bin', javaExeName());
          if (fs.existsSync(c)) out.push(c);
        }
      } catch (_) {}
    }
  } else if (process.platform === 'darwin') {
    try {
      const vers = fs.readdirSync('/Library/Java/JavaVirtualMachines');
      for (const v of vers) out.push(`/Library/Java/JavaVirtualMachines/${v}/Contents/Home/bin/java`);
    } catch (_) {}
    out.push('/usr/bin/java');
  } else {
    for (const p of ['/usr/lib/jvm', '/usr/java']) {
      try {
        for (const v of fs.readdirSync(p)) out.push(path.join(p, v, 'bin', 'java'));
      } catch (_) {}
    }
    out.push('/usr/bin/java', '/usr/local/bin/java');
  }
  // PATH fallback
  out.push(javaExeName());
  return [...new Set(out)];
}

/** Run `java -version` and parse. Returns {path, major, versionString} or null. */
function probe(javaPath) {
  return new Promise((resolve) => {
    let stderr = '';
    let stdout = '';
    let settled = false;
    const done = (v) => { if (!settled) { settled = true; resolve(v); } };

    let child;
    try {
      child = spawn(javaPath, ['-version'], { windowsHide: true });
    } catch (_) {
      return done(null);
    }
    child.stderr.on('data', (d) => (stderr += d.toString()));
    child.stdout.on('data', (d) => (stdout += d.toString()));
    child.on('error', () => done(null));
    child.on('close', () => {
      const combined = stderr + stdout;
      // Match "1.8.0_xxx" style or "21.0.1" style
      const m = combined.match(/version "([^"]+)"/);
      if (!m) return done(null);
      const versionString = m[1];
      // Parse major version: "21.0.1" → 21, "1.8.0_302" → 8
      let major;
      if (versionString.startsWith('1.')) {
        major = parseInt(versionString.split('.')[1], 10);
      } else {
        major = parseInt(versionString.split('.')[0], 10);
      }
      if (Number.isNaN(major)) return done(null);
      done({ path: javaPath, major, versionString });
    });
    // Safety timeout — java should respond in well under 5 s
    setTimeout(() => { try { child.kill(); } catch (_) {} done(null); }, 5000);
  });
}

// ---- detection cache ---------------------------------------------------
// `detect()` was being called from every screen render — Play, Home, Settings
// pre-flight — and each call spawns one `java -version` process per
// candidate plus the configured path. On a machine with several JDKs that's
// 5+ child-process starts taking 200–800 ms total, blocking the renderer.
//
// Cache the result keyed by configured path with a 60 s TTL. Settings:patch
// fires `invalidateCache()` when the user changes javaPath, so a freshly
// chosen JDK is reflected immediately.

let _detectCache = null;        // { configuredPath, value, expiresAt }

function invalidateCache() { _detectCache = null; }

async function detect(configuredPath) {
  const key = (configuredPath || '').trim();
  const now = Date.now();
  if (_detectCache && _detectCache.configuredPath === key && _detectCache.expiresAt > now) {
    return _detectCache.value;
  }
  const value = await _detectImpl(configuredPath);
  _detectCache = { configuredPath: key, value, expiresAt: now + 600_000 };
  return value;
}

/** Original detection logic, now wrapped by the cache. */
async function _detectImpl(configuredPath) {
  const candidates = [];
  if (configuredPath && configuredPath.trim()) candidates.push(configuredPath.trim());
  candidates.push(...commonCandidates());

  // Probe in parallel so a single slow/hanging candidate (e.g. the Windows
  // Store "java.exe" shim that redirects to the Store) can't block the others.
  const seen = new Set();
  const probes = await Promise.all(
    candidates
      .filter(c => { if (seen.has(c)) return false; seen.add(c); return true; })
      .map(c => probe(c))
  );
  const results = probes.filter(Boolean);

  if (results.length === 0) {
    return { path: null, major: 0, versionString: null, ok: false, reason: 'No Java installation found on PATH or in common locations.' };
  }
  // Prefer configured path if it probed successfully
  if (configuredPath && configuredPath.trim()) {
    const conf = results.find(r => r.path === configuredPath.trim());
    if (conf) return { ...conf, ok: conf.major >= REQUIRED_MAJOR };
  }
  // Otherwise pick the highest major version
  results.sort((a, b) => b.major - a.major);
  const best = results[0];
  return {
    ...best,
    ok: best.major >= REQUIRED_MAJOR,
    reason: best.major >= REQUIRED_MAJOR ? null :
      `Java ${best.major} found, but Minecraft 1.20.5+ requires Java ${REQUIRED_MAJOR}+.`,
    required: REQUIRED_MAJOR,
  };
}

/**
 * Probe every common candidate plus an optional configured path. Returns the
 * full list (de-duped, in scan order) so the Settings screen can show "all
 * detected Javas" as a click-to-pick card.
 *
 * Returned objects mirror {@link probe} but never include the `ok` field —
 * the renderer derives that from the major + REQUIRED_MAJOR.
 */
async function detectAll(configuredPath) {
  const candidates = [];
  if (configuredPath && configuredPath.trim()) candidates.push(configuredPath.trim());
  candidates.push(...commonCandidates());

  const seen = new Set();
  const out = [];
  // Probe in parallel — each probe spawns a 5 s-bounded child, so doing them
  // sequentially adds up fast on a machine with several JDKs installed.
  const probes = await Promise.all(candidates.map(async (c) => {
    if (seen.has(c)) return null;
    seen.add(c);
    const r = await probe(c);
    return r;
  }));
  for (const r of probes) if (r) out.push(r);
  // Highest-version-first matches what `detect()` would have picked.
  out.sort((a, b) => b.major - a.major);
  return { required: REQUIRED_MAJOR, results: out };
}

module.exports = { detect, detectAll, probe, invalidateCache, REQUIRED_MAJOR };
