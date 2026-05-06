// Mojang service status — polls status.mojang.com/check and caches the result
// for 2 minutes so repeated IPC calls from the renderer are free.

const https = require('https');

const STATUS_URL = 'https://status.mojang.com/check';
const CACHE_TTL  = 2 * 60 * 1000;

// Subset we surface in the launcher UI, in display order.
const DISPLAY = [
  { key: 'minecraft.net',         label: 'Website'     },
  { key: 'session.minecraft.net', label: 'Multiplayer' },
  { key: 'authserver.mojang.com', label: 'Auth'        },
  { key: 'api.mojang.com',        label: 'API'         },
];

let _cache   = null;
let _cacheAt = 0;

async function getStatus(force = false) {
  if (!force && _cache && (Date.now() - _cacheAt) < CACHE_TTL) {
    return { ..._cache, fromCache: true };
  }
  try {
    const body = await _fetch(STATUS_URL);
    const arr  = JSON.parse(body);
    const map  = {};
    for (const entry of arr) {
      const [k, v] = Object.entries(entry)[0];
      map[k] = v; // 'green' | 'yellow' | 'red'
    }
    const services = DISPLAY.map(({ key, label }) => ({
      label,
      status: map[key] || 'unknown',
    }));
    _cache   = { services, fetchedAt: Date.now(), error: null };
    _cacheAt = Date.now();
    return _cache;
  } catch (err) {
    return {
      services: DISPLAY.map(({ label }) => ({ label, status: 'unknown' })),
      fetchedAt: Date.now(),
      error: err.message,
      fromCache: false,
    };
  }
}

function _fetch(url) {
  return new Promise((resolve, reject) => {
    const req = https.get(url, { timeout: 5000 }, (res) => {
      if (res.statusCode !== 200) return reject(new Error(`HTTP ${res.statusCode}`));
      const chunks = [];
      res.on('data',  (c) => chunks.push(c));
      res.on('end',   ()  => resolve(Buffer.concat(chunks).toString()));
      res.on('error', reject);
    });
    req.on('error',   reject);
    req.on('timeout', () => { req.destroy(); reject(new Error('Timeout')); });
  });
}

module.exports = { getStatus };
