// Minecraft skin management via the official Mojang/Minecraft Services API.
//
// Endpoints used:
//   GET  https://sessionserver.mojang.com/session/minecraft/profile/<uuid>
//     → decodes the base64 "textures" property to get the current skin URL
//       and model variant ("CLASSIC" | "SLIM").
//
//   POST https://api.minecraftservices.com/minecraft/profile/skins
//     Content-Type: multipart/form-data
//     Fields: variant ("classic" | "slim"), file (PNG blob)
//     Authorization: Bearer <accessToken>
//     → uploads + activates a new skin in one call.
//
// The access token is provided by the caller (auth.getValid()) and never
// stored here.  Both functions are called from main-process IPC handlers only
// so the token never crosses the context bridge.

const https = require('https');
const fs    = require('fs');
const path  = require('path');

// ---- helpers ----

function httpsGet(urlStr, headers = {}) {
  return new Promise((resolve, reject) => {
    const req = https.get(urlStr, { headers, timeout: 10000 }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        const body = Buffer.concat(chunks).toString('utf8');
        if (res.statusCode >= 200 && res.statusCode < 300) {
          try { resolve(JSON.parse(body)); }
          catch (_) { resolve(body); }
        } else {
          reject(Object.assign(new Error(`HTTP ${res.statusCode}`), { status: res.statusCode, body }));
        }
      });
      res.on('error', reject);
    });
    req.on('error', reject);
    req.on('timeout', () => { req.destroy(); reject(new Error('Timeout fetching skin info')); });
  });
}

// ---- public API ----

/**
 * Fetch the current skin info for a Minecraft profile.
 *
 * Returns `{ skinUrl: string|null, variant: 'classic'|'slim' }`.
 * `skinUrl` is null when the profile has the default Steve/Alex skin.
 *
 * @param {string} uuid        Minecraft UUID (with or without hyphens)
 * @param {string} _accessToken  not required for session-server lookups, kept
 *                               for API symmetry so callers don't need to know.
 */
async function fetchSkinInfo(uuid, _accessToken) {
  if (!uuid || typeof uuid !== 'string') throw new Error('UUID required');
  const clean = uuid.replace(/-/g, '');
  const data = await httpsGet(`https://sessionserver.mojang.com/session/minecraft/profile/${clean}`);

  const texturesProp = (data.properties || []).find(p => p.name === 'textures');
  if (!texturesProp) return { skinUrl: null, variant: 'classic' };

  let textures;
  try {
    textures = JSON.parse(Buffer.from(texturesProp.value, 'base64').toString('utf8'));
  } catch (_) {
    return { skinUrl: null, variant: 'classic' };
  }

  const skin = textures && textures.textures && textures.textures.SKIN;
  if (!skin) return { skinUrl: null, variant: 'classic' };

  // The metadata field contains { model: "slim" } for Alex-model skins.
  const variant = (skin.metadata && skin.metadata.model === 'slim') ? 'slim' : 'classic';
  return { skinUrl: skin.url || null, variant };
}

/**
 * Upload a new skin PNG and make it active on the account.
 *
 * @param {string} accessToken  Minecraft JWT from auth.getValid()
 * @param {string} filePath     Absolute path to the local .png file
 * @param {'classic'|'slim'} variant  'classic' (Steve) or 'slim' (Alex)
 * @returns {{ ok: boolean, error?: string }}
 */
async function uploadSkin(accessToken, filePath, variant) {
  if (!filePath || !fs.existsSync(filePath)) throw new Error('Skin file not found');
  return uploadSkinBytes(accessToken, fs.readFileSync(filePath), variant, path.basename(filePath));
}

/** Upload an in-memory PNG buffer as the player's skin. Used by the in-app
 *  skin editor — same wire format as uploadSkin, no temp file needed. */
async function uploadSkinBytes(accessToken, fileData, variant, filename = 'skin.png') {
  if (!accessToken) throw new Error('Access token required');
  if (!Buffer.isBuffer(fileData) || fileData.length === 0) throw new Error('Empty skin buffer');
  if (variant !== 'classic' && variant !== 'slim') variant = 'classic';

  const boundary = `----FoxLauncherBoundary${Date.now().toString(16)}`;

  // Build multipart/form-data body manually — no external dependencies.
  const parts = [
    `--${boundary}\r\nContent-Disposition: form-data; name="variant"\r\n\r\n${variant}`,
    `--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="${filename}"\r\nContent-Type: image/png\r\n`,
  ];
  const body = Buffer.concat([
    Buffer.from(parts[0] + '\r\n'),
    Buffer.from(parts[1] + '\r\n'),
    fileData,
    Buffer.from(`\r\n--${boundary}--\r\n`),
  ]);

  return new Promise((resolve, reject) => {
    const req = https.request({
      hostname: 'api.minecraftservices.com',
      path:     '/minecraft/profile/skins',
      method:   'POST',
      headers:  {
        Authorization:  `Bearer ${accessToken}`,
        'Content-Type': `multipart/form-data; boundary=${boundary}`,
        'Content-Length': body.length,
      },
      timeout: 15000,
    }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        if (res.statusCode >= 200 && res.statusCode < 300) {
          resolve({ ok: true });
        } else {
          const msg = Buffer.concat(chunks).toString('utf8');
          resolve({ ok: false, error: `HTTP ${res.statusCode}: ${msg}` });
        }
      });
      res.on('error', (err) => resolve({ ok: false, error: err.message }));
    });
    req.on('error', (err) => resolve({ ok: false, error: err.message }));
    req.on('timeout', () => { req.destroy(); resolve({ ok: false, error: 'Request timed out' }); });
    req.write(body);
    req.end();
  });
}

/** Download a PNG over HTTPS into a Buffer. Used by the skin editor's
 *  "Load current skin" so the renderer doesn't need a wide CSP — main fetches,
 *  hands back base64. */
function fetchPngBuffer(url) {
  return new Promise((resolve, reject) => {
    https.get(url, { headers: { 'User-Agent': 'FoxLauncher/1.0 (skin editor)' }, timeout: 15000 }, (res) => {
      if (res.statusCode < 200 || res.statusCode >= 300) {
        res.resume();
        return reject(new Error(`HTTP ${res.statusCode} fetching skin PNG`));
      }
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end',  () => resolve(Buffer.concat(chunks)));
      res.on('error', reject);
    }).on('error', reject).on('timeout', function () { this.destroy(new Error('Skin PNG request timed out')); });
  });
}

module.exports = { fetchSkinInfo, uploadSkin, uploadSkinBytes, fetchPngBuffer };
