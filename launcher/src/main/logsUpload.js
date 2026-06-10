// Upload game logs to mclo.gs so users can share a link in bug reports
// instead of pasting 5000 lines into Discord.
//
// API: POST https://api.mclo.gs/1/log with form-encoded `content`.
// Response: { success: true, url, id } or { success: false, error }.
// mclo.gs caps pastes at 25k lines / 10 MB and redacts IPs server-side;
// we additionally scrub the local user's home directory before upload.

const https = require('https');
const os = require('os');

const UPLOAD_HOST = 'api.mclo.gs';
const UPLOAD_PATH = '/1/log';
const MAX_LINES = 25000;
const MAX_BYTES = 10 * 1024 * 1024; // 10 MB
const REQUEST_TIMEOUT_MS = 20_000;

/** Escape a string for use inside a RegExp. */
function escapeRegExp(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * Replace the user's home directory (either slash flavour) with `~` so the
 * Windows username doesn't leak into a public paste.
 */
function scrubHomeDir(text, homedir = os.homedir()) {
  if (!homedir) return text;
  const fwd = homedir.replace(/\\/g, '/');
  let out = text.replace(new RegExp(escapeRegExp(homedir), 'gi'), '~');
  if (fwd !== homedir) {
    out = out.replace(new RegExp(escapeRegExp(fwd), 'gi'), '~');
  }
  return out;
}

/**
 * Enforce mclo.gs size limits, keeping the TAIL of the log (the crash is at
 * the end; the boot spam is not the interesting part).
 */
function truncateForUpload(text, maxLines = MAX_LINES, maxBytes = MAX_BYTES) {
  let lines = text.split('\n');
  let truncated = false;
  if (lines.length > maxLines) {
    lines = lines.slice(lines.length - maxLines);
    truncated = true;
  }
  let out = lines.join('\n');
  while (Buffer.byteLength(out, 'utf8') > maxBytes) {
    // Drop the oldest 10% until we fit — coarse but rarely more than one pass.
    lines = lines.slice(Math.ceil(lines.length * 0.1));
    out = lines.join('\n');
    truncated = true;
  }
  if (truncated) {
    out = '[... truncated by Fox Launcher — oldest lines removed ...]\n' + out;
  }
  return out;
}

/**
 * Render the log buffer's entries ({ts, kind, text}) into upload text.
 */
function formatEntries(entries) {
  return entries
    .map(e => `[${new Date(e.ts).toISOString()}] [${e.kind}] ${e.text}`)
    .join('\n');
}

/** Full pipeline used by the IPC handler: format → scrub → truncate. */
function preparePayload(entries, homedir = os.homedir()) {
  return truncateForUpload(scrubHomeDir(formatEntries(entries), homedir));
}

/**
 * POST the prepared content to mclo.gs. Resolves to
 * { ok: true, url } or { ok: false, error }.
 */
function uploadText(content) {
  return new Promise((resolve) => {
    if (!content || !content.trim()) {
      return resolve({ ok: false, error: 'Nothing to upload — the log is empty.' });
    }
    const body = 'content=' + encodeURIComponent(content);
    const req = https.request({
      method: 'POST',
      hostname: UPLOAD_HOST,
      path: UPLOAD_PATH,
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded',
        'Content-Length': Buffer.byteLength(body),
        'User-Agent': 'Fox-Launcher',
      },
      timeout: REQUEST_TIMEOUT_MS,
    }, (res) => {
      const chunks = [];
      res.on('data', c => chunks.push(c));
      res.on('end', () => {
        try {
          const json = JSON.parse(Buffer.concat(chunks).toString('utf8'));
          if (json.success && json.url) return resolve({ ok: true, url: json.url });
          resolve({ ok: false, error: json.error || `mclo.gs returned HTTP ${res.statusCode}` });
        } catch (e) {
          resolve({ ok: false, error: `Unexpected mclo.gs response (HTTP ${res.statusCode})` });
        }
      });
      res.on('error', (e) => resolve({ ok: false, error: e.message }));
    });
    req.on('error', (e) => resolve({ ok: false, error: e.message }));
    req.on('timeout', () => { req.destroy(); resolve({ ok: false, error: 'Upload timed out' }); });
    req.end(body);
  });
}

module.exports = {
  scrubHomeDir,
  truncateForUpload,
  formatEntries,
  preparePayload,
  uploadText,
  MAX_LINES,
  MAX_BYTES,
};
