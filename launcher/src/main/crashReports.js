// Detects and reads Minecraft crash reports under <gameDir>/crash-reports/.
//
// Used by the post-launch flow: when the game exits non-zero, we look for any
// crash report file whose mtime is *after* the launch start. If we find one,
// the renderer surfaces a modal with copy/open-folder actions. No upload —
// solo MIT means no telemetry endpoint to point at.

const fs = require('fs');
const path = require('path');

const FILE_RE = /^crash-.*\.txt$/i;
const MAX_BYTES_RETURNED = 256 * 1024; // 256 KB — full reports rarely exceed this

/**
 * Find the newest crash report file inside `<gameDir>/crash-reports/` whose
 * mtime is at or after `sinceMs` (epoch ms). Returns `null` if none.
 *
 * Returns: { path, name, mtimeMs, sizeBytes } or null.
 */
function findNewSince(gameDir, sinceMs) {
  if (!gameDir) return null;
  const dir = path.join(gameDir, 'crash-reports');
  let entries;
  try {
    entries = fs.readdirSync(dir);
  } catch (_) {
    return null;
  }
  let best = null;
  for (const name of entries) {
    if (!FILE_RE.test(name)) continue;
    const full = path.join(dir, name);
    let st;
    try { st = fs.statSync(full); } catch (_) { continue; }
    if (!st.isFile()) continue;
    if (st.mtimeMs < sinceMs) continue;
    if (!best || st.mtimeMs > best.mtimeMs) {
      best = { path: full, name, mtimeMs: st.mtimeMs, sizeBytes: st.size };
    }
  }
  return best;
}

/**
 * Read a crash report file. Validates the path is under `<gameDir>/crash-reports/`
 * to guard against the renderer asking us to read arbitrary files (defence in
 * depth — IPC inputs are not trusted).
 *
 * Returns: { ok, content?, error? }
 */
function readReport(gameDir, fullPath) {
  if (!gameDir || !fullPath) return { ok: false, error: 'missing arguments' };
  const reportsDir = path.resolve(gameDir, 'crash-reports') + path.sep;
  const resolved = path.resolve(fullPath);
  if (!resolved.startsWith(reportsDir)) {
    return { ok: false, error: 'path outside crash-reports/' };
  }
  try {
    const raw = fs.readFileSync(resolved, 'utf8');
    const content = raw.length > MAX_BYTES_RETURNED
      ? raw.slice(0, MAX_BYTES_RETURNED) + '\n…[truncated; open the file to see the rest]'
      : raw;
    return { ok: true, content };
  } catch (err) {
    return { ok: false, error: err.message };
  }
}

module.exports = { findNewSince, readReport };
