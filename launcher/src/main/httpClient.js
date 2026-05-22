// Shared HTTPS helpers used by fabricInstaller.js, recommendedMods.js, and
// any other module that needs authenticated-free JSON/binary fetches.
//
// Features:
//   - 15 s connect timeout per request
//   - Follows up to 5 redirects
//   - Exponential backoff retry (up to MAX_RETRIES attempts)
//   - Atomic file writes for downloads (.tmp + rename)

const fs    = require('fs');
const https = require('https');
const path  = require('path');
const { URL } = require('url');

const REQUEST_TIMEOUT_MS = 15_000;
const MAX_RETRIES        = 5;

/**
 * Fetch a URL into a Buffer, following redirects. Does NOT retry — use
 * fetchWithRetry for that.
 *
 * @param {string} urlStr
 * @param {number} [attempt=0]   internal redirect counter
 * @param {string} [userAgent]   value for User-Agent header
 */
function fetchBuffer(urlStr, attempt = 0, userAgent = 'Fox-Launcher') {
  return new Promise((resolve, reject) => {
    let u;
    try { u = new URL(urlStr); } catch (e) { return reject(e); }
    const req = https.request({
      method:   'GET',
      hostname: u.hostname,
      port:     u.port || 443,
      path:     u.pathname + u.search,
      headers:  { 'User-Agent': userAgent, Accept: 'application/json,*/*' },
      timeout:  REQUEST_TIMEOUT_MS,
    }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location && attempt < 5) {
        res.resume();
        const next = new URL(res.headers.location, urlStr).toString();
        return fetchBuffer(next, attempt + 1, userAgent).then(resolve, reject);
      }
      if (res.statusCode < 200 || res.statusCode >= 300) {
        res.resume();
        return reject(new Error(`HTTP ${res.statusCode} for ${urlStr}`));
      }
      const chunks = [];
      res.on('data',  (c) => chunks.push(c));
      res.on('end',   ()  => resolve(Buffer.concat(chunks)));
      res.on('error', reject);
    });
    req.on('error',   reject);
    req.on('timeout', () => req.destroy(new Error(`Request timed out: ${urlStr}`)));
    req.end();
  });
}

/**
 * Fetch with exponential-backoff retries.
 *
 * @param {string} urlStr
 * @param {object} [opts]
 * @param {string} [opts.userAgent]
 * @param {number} [opts.maxRetries]
 */
async function fetchWithRetry(urlStr, opts = {}) {
  const retries   = opts.maxRetries !== undefined ? opts.maxRetries : MAX_RETRIES;
  const userAgent = opts.userAgent || 'Fox-Launcher';
  let lastErr;
  for (let i = 0; i < retries; i++) {
    try { return await fetchBuffer(urlStr, 0, userAgent); }
    catch (err) {
      lastErr = err;
      await new Promise(r => setTimeout(r, Math.min(5000, 250 * Math.pow(2, i))));
    }
  }
  throw lastErr;
}

/**
 * Fetch JSON from a URL with retries.
 */
async function fetchJson(urlStr, opts = {}) {
  const buf = await fetchWithRetry(urlStr, opts);
  return JSON.parse(buf.toString('utf8'));
}

/**
 * Write `contents` to `target` atomically (.tmp → rename).
 *
 * @param {string}          target
 * @param {Buffer|string}   contents
 */
function writeAtomic(target, contents) {
  return new Promise((resolve, reject) => {
    const tmp = target + '.tmp';
    fs.writeFile(tmp, contents, (err) => {
      if (err) return reject(err);
      fs.rename(tmp, target, (e2) => e2 ? reject(e2) : resolve());
    });
  });
}

module.exports = { fetchBuffer, fetchWithRetry, fetchJson, writeAtomic, REQUEST_TIMEOUT_MS, MAX_RETRIES };
