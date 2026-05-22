// Cached news feed.
//
// Fetches a JSON document from raw.githubusercontent.com (or any URL the user
// configures), caches the parsed result on disk, and serves the cache on
// subsequent calls. The renderer never sees a network error directly — it
// gets either a fresh result, the cached result, or an empty list.
//
// Expected JSON shape (kept deliberately simple so a non-developer can edit
// the news.json in the repo):
// {
//   "items": [
//     { "title": "...", "date": "2026-04-01", "body": "...", "url": "https://..." },
//     ...
//   ]
// }

const fs = require('fs');
const path = require('path');
const https = require('https');
const { URL } = require('url');

const paths = require('./paths');

const CACHE_TTL_MS = 30 * 60 * 1000; // 30 min — cheap retries from the renderer become free
const REQUEST_TIMEOUT_MS = 5000;
const MAX_BYTES = 64 * 1024;
const MAX_ITEMS = 20;

function cachePath() {
  return path.join(paths.cache, 'news.json');
}

function readCache() {
  try {
    return JSON.parse(fs.readFileSync(cachePath(), 'utf8'));
  } catch (_) {
    return null;
  }
}

function writeCache(doc) {
  paths.ensureAll();
  const tmp = cachePath() + '.tmp';
  try {
    fs.writeFileSync(tmp, JSON.stringify(doc));
    fs.renameSync(tmp, cachePath());
  } catch (_) { /* best-effort */ }
}

function fetchJson(urlStr) {
  return new Promise((resolve, reject) => {
    let u;
    try { u = new URL(urlStr); } catch (e) { return reject(e); }
    if (u.protocol !== 'https:' && u.protocol !== 'http:') {
      return reject(new Error(`Refusing non-http(s) news URL: ${u.protocol}`));
    }
    const req = (u.protocol === 'https:' ? https : require('http')).request({
      method: 'GET',
      hostname: u.hostname,
      port: u.port || (u.protocol === 'https:' ? 443 : 80),
      path: u.pathname + u.search,
      headers: {
        'Accept': 'application/json',
        'User-Agent': 'Fox-Launcher',
      },
      timeout: REQUEST_TIMEOUT_MS,
    }, (res) => {
      if (res.statusCode < 200 || res.statusCode >= 300) {
        res.resume();
        return reject(new Error(`HTTP ${res.statusCode}`));
      }
      let bytes = 0;
      let tooLarge = false;
      const chunks = [];
      res.on('data', (c) => {
        bytes += c.length;
        if (bytes > MAX_BYTES) {
          tooLarge = true;
          req.destroy();
          reject(new Error('news payload too large'));
          return;
        }
        chunks.push(c);
      });
      res.on('end', () => {
        if (tooLarge) return;
        try {
          const txt = Buffer.concat(chunks).toString('utf8');
          resolve(JSON.parse(txt));
        } catch (e) { reject(e); }
      });
    });
    req.on('error', reject);
    req.on('timeout', () => req.destroy(new Error('news request timed out')));
    req.end();
  });
}

function normalize(doc) {
  if (!doc || !Array.isArray(doc.items)) return { items: [] };
  const items = doc.items.slice(0, MAX_ITEMS).map((it) => ({
    title: typeof it.title === 'string' ? it.title : 'Untitled',
    date:  typeof it.date  === 'string' ? it.date  : '',
    body:  typeof it.body  === 'string' ? it.body  : '',
    url:   typeof it.url   === 'string' ? it.url   : '',
  }));
  return { items };
}

/**
 * Fetch the news document. Always resolves; never throws to the renderer.
 *
 * Returns: { items, source: 'fresh' | 'cache' | 'empty', fetchedAt, error? }
 */
async function getNews(url) {
  const cached = readCache();
  const fresh = cached && (Date.now() - cached.fetchedAt) < CACHE_TTL_MS;
  if (fresh) return { ...cached, source: 'cache' };

  if (!url || typeof url !== 'string' || !url.trim()) {
    return cached
      ? { ...cached, source: 'cache' }
      : { items: [], source: 'empty', fetchedAt: 0 };
  }

  try {
    const raw = await fetchJson(url);
    const doc = normalize(raw);
    const out = { ...doc, fetchedAt: Date.now() };
    writeCache(out);
    return { ...out, source: 'fresh' };
  } catch (err) {
    if (cached) return { ...cached, source: 'cache', error: err.message };
    return { items: [], source: 'empty', fetchedAt: 0, error: err.message };
  }
}

module.exports = { getNews };
