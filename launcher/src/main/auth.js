// Microsoft + Minecraft authentication.
//
// Uses OAuth 2.0 Authorization Code + PKCE flow with an embedded BrowserWindow
// so Microsoft's sign-in UI (including child-account family consent) appears
// inside the launcher rather than opening the user's default browser.
//
// Redirect URI: https://login.live.com/oauth20_desktop.srf
//   — Microsoft's official "desktop app" redirect; no local HTTP server needed.
//   Add this URI to your Azure app registration's Redirect URIs list.
//
// Steps:
//   1. Generate code_verifier + code_challenge (SHA-256 / PKCE)
//   2. Open a child BrowserWindow with the Microsoft sign-in URL
//   3. Intercept the redirect to login.live.com/oauth20_desktop.srf
//   4. Exchange code + verifier for MS access/refresh tokens
//   5. Exchange MS token → XBL → XSTS → Minecraft tokens
//   6. Fetch Minecraft profile (UUID + username)
//   7. Cache result in ~/.foxlauncher/auth.json

const fs     = require('fs');
const https  = require('https');
const crypto = require('crypto');
const path   = require('path');
const { URL, URLSearchParams } = require('url');
const { BrowserWindow }        = require('electron');

const paths    = require('./paths');
const settings = require('./settings');

const REDIRECT_URI = 'https://login.live.com/oauth20_desktop.srf';
const SCOPES       = 'XboxLive.signin offline_access';

// ---- client ID ----

function clientId() {
  const id = (settings.load().msaClientId || '').trim();
  if (!id) {
    const e = new Error('No Microsoft Application ID configured. Open Settings and paste a client ID from portal.azure.com.');
    e.fatal = true;
    throw e;
  }
  return id;
}

// ---- PKCE helpers ----

function generateCodeVerifier()      { return crypto.randomBytes(32).toString('base64url'); }
function generateCodeChallenge(v)    { return crypto.createHash('sha256').update(v).digest('base64url'); }

// ---- low-level HTTP helpers ----

function httpJson(urlStr, opts = {}, body = null) {
  return new Promise((resolve, reject) => {
    const u = new URL(urlStr);
    const req = https.request({
      method:   opts.method || 'GET',
      hostname: u.hostname,
      path:     u.pathname + u.search,
      port:     443,
      headers:  { Accept: 'application/json', ...(opts.headers || {}) },
    }, (res) => {
      let data = '';
      res.on('data', (c) => (data += c));
      res.on('end', () => {
        let parsed = null;
        try { parsed = data ? JSON.parse(data) : null; } catch (_) {}
        if (res.statusCode >= 200 && res.statusCode < 300) resolve(parsed);
        else reject(Object.assign(new Error(`HTTP ${res.statusCode}`), { status: res.statusCode, body: parsed, raw: data }));
      });
    });
    req.on('error', reject);
    if (body) req.write(typeof body === 'string' ? body : JSON.stringify(body));
    req.end();
  });
}

function postForm(urlStr, form) {
  return httpJson(urlStr, {
    method:  'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  }, new URLSearchParams(form).toString());
}

function postJson(urlStr, body, headers = {}) {
  return httpJson(urlStr, {
    method:  'POST',
    headers: { 'Content-Type': 'application/json', ...headers },
  }, body);
}

// ---- auth persistence ----

function vaultPath() {
  try {
    const s = settings.load();
    const profilesStore = require('./profiles');
    const profile = profilesStore.find(s.selectedProfile);
    return paths.authVaultForProfile(profile);
  } catch (_) {
    return paths.auth;
  }
}

function loadCached() {
  try { return JSON.parse(fs.readFileSync(vaultPath(), 'utf8')); }
  catch (_) { return null; }
}

function saveCached(data) {
  paths.ensureAll();
  const dst = vaultPath();
  try { fs.mkdirSync(path.dirname(dst), { recursive: true }); } catch (_) {}
  const tmp = dst + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(data, null, 2), { mode: 0o600 });
  fs.renameSync(tmp, dst);
}

function clearCached() {
  try { fs.unlinkSync(vaultPath()); } catch (_) {}
}

// ---- step 1: embedded-browser OAuth + PKCE ----
//
// Opens a child BrowserWindow with the Microsoft sign-in page and intercepts
// the redirect to login.live.com/oauth20_desktop.srf to extract the auth code.
// Showing auth inside the launcher (rather than the system browser) lets
// Microsoft handle child-account family consent inline — no external redirect
// needed.

function requestBrowserAuth({ onBrowserOpen } = {}) {
  const codeVerifier  = generateCodeVerifier();
  const codeChallenge = generateCodeChallenge(codeVerifier);

  const authUrl = new URL('https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize');
  authUrl.searchParams.set('client_id',             clientId());
  authUrl.searchParams.set('response_type',         'code');
  authUrl.searchParams.set('redirect_uri',          REDIRECT_URI);
  authUrl.searchParams.set('scope',                 SCOPES);
  authUrl.searchParams.set('code_challenge',        codeChallenge);
  authUrl.searchParams.set('code_challenge_method', 'S256');
  authUrl.searchParams.set('prompt',                'select_account');

  return new Promise((resolve, reject) => {
    let settled = false;
    const done = (err, val) => {
      if (settled) return;
      settled = true;
      if (err) reject(err); else resolve(val);
    };

    const win = new BrowserWindow({
      width:           500,
      height:          650,
      title:           'Sign in with Microsoft',
      autoHideMenuBar: true,
      webPreferences:  { nodeIntegration: false, contextIsolation: true },
    });

    if (onBrowserOpen) onBrowserOpen();

    win.webContents.on('will-redirect', (_e, url) => {
      if (!url.startsWith(REDIRECT_URI)) return;
      const parsed = new URL(url);
      const code   = parsed.searchParams.get('code');
      const error  = parsed.searchParams.get('error');
      const desc   = parsed.searchParams.get('error_description');
      try { win.destroy(); } catch (_) {}
      if (code) {
        done(null, { codeVerifier, authCode: code });
      } else {
        done(new Error(desc || error || 'Sign-in was cancelled or failed.'));
      }
    });

    // Also intercept will-navigate for older Electron versions
    win.webContents.on('will-navigate', (_e, url) => {
      if (!url.startsWith(REDIRECT_URI)) return;
      const parsed = new URL(url);
      const code   = parsed.searchParams.get('code');
      const error  = parsed.searchParams.get('error');
      const desc   = parsed.searchParams.get('error_description');
      try { win.destroy(); } catch (_) {}
      if (code) {
        done(null, { codeVerifier, authCode: code });
      } else {
        done(new Error(desc || error || 'Sign-in was cancelled or failed.'));
      }
    });

    win.on('closed', () => done(new Error('Sign-in window was closed.')));
    win.loadURL(authUrl.toString());
  });
}

// ---- step 2: exchange code for MS token ----

async function exchangeCodeForToken({ authCode, codeVerifier }) {
  try {
    return await postForm('https://login.microsoftonline.com/consumers/oauth2/v2.0/token', {
      grant_type:    'authorization_code',
      client_id:     clientId(),
      code:          authCode,
      redirect_uri:  REDIRECT_URI,
      code_verifier: codeVerifier,
      scope:         SCOPES,
    });
  } catch (err) {
    const body = err && err.body ? err.body : null;
    const desc = body && (body.error_description || body.error);
    if (desc) throw new Error(`Microsoft sign-in failed: ${desc}`);
    throw err;
  }
}

// ---- step 3: exchange MS token for Minecraft ----

async function msToMinecraftToken(msAccessToken) {
  // 3a. XBL
  const xbl = await postJson('https://user.auth.xboxlive.com/user/authenticate', {
    Properties:   { AuthMethod: 'RPS', SiteName: 'user.auth.xboxlive.com', RpsTicket: 'd=' + msAccessToken },
    RelyingParty: 'http://auth.xboxlive.com',
    TokenType:    'JWT',
  });
  const xblToken = xbl.Token;
  const userHash = xbl.DisplayClaims.xui[0].uhs;

  // 3b. XSTS
  let xsts;
  try {
    xsts = await postJson('https://xsts.auth.xboxlive.com/xsts/authorize', {
      Properties:   { SandboxId: 'RETAIL', UserTokens: [xblToken] },
      RelyingParty: 'rp://api.minecraftservices.com/',
      TokenType:    'JWT',
    });
  } catch (err) {
    const xerr = err.body && err.body.XErr;
    if (xerr === 2148916227) throw new Error('This Xbox account has been banned.');
    if (xerr === 2148916233) throw new Error('This Microsoft account does not have an Xbox profile. Create one at xbox.com, then try again.');
    if (xerr === 2148916235) throw new Error('Xbox Live is not available in your country or region.');
    if (xerr === 2148916236 || xerr === 2148916237) throw new Error('This account requires adult verification (South Korea). Complete verification at xbox.com.');
    if (xerr === 2148916238) {
      // Microsoft includes a Redirect URL in the error body pointing to their
      // family-consent flow. start.ui.xboxlive.com has an expired cert (as of
      // 2025) so fall back to the account.microsoft.com family page instead.
      const redirect = (err.body && err.body.Redirect &&
        !err.body.Redirect.includes('start.ui.xboxlive.com'))
        ? err.body.Redirect
        : 'https://account.microsoft.com/family/';
      const e = new Error('This Microsoft account requires parental approval to use third-party apps. Click below to set up family access, then sign in again.');
      e.childAccountRedirect = redirect;
      throw e;
    }
    throw err;
  }

  // 3c. Minecraft
  const mc = await postJson('https://api.minecraftservices.com/authentication/login_with_xbox', {
    identityToken: `XBL3.0 x=${userHash};${xsts.Token}`,
  });

  // 3d. Profile
  const profile = await httpJson('https://api.minecraftservices.com/minecraft/profile', {
    headers: { Authorization: `Bearer ${mc.access_token}` },
  });
  if (!profile || !profile.id) {
    throw new Error('This Microsoft account does not own Minecraft Java Edition.');
  }

  return {
    accessToken: mc.access_token,
    expiresAt:   Date.now() + (mc.expires_in * 1000) - 30000,
    uuid:        profile.id,
    username:    profile.name,
  };
}

// ---- refresh flow ----

async function refreshMsToken(refreshToken) {
  return postForm('https://login.microsoftonline.com/consumers/oauth2/v2.0/token', {
    grant_type:    'refresh_token',
    client_id:     clientId(),
    refresh_token: refreshToken,
    scope:         SCOPES,
  });
}

// ---- public API ----

/**
 * Start an interactive sign-in via an embedded BrowserWindow.
 * `onBrowserOpen()` is called when the window opens (no URL — window is embedded).
 */
async function login({ onBrowserOpen } = {}) {
  const result = await requestBrowserAuth({ onBrowserOpen });
  const msTok  = await exchangeCodeForToken(result);
  const mc     = await msToMinecraftToken(msTok.access_token);
  const record = { ...mc, msRefreshToken: msTok.refresh_token, savedAt: Date.now() };
  saveCached(record);
  return record;
}

/**
 * Return a valid Minecraft auth record, refreshing silently if needed.
 * Returns null if there is no cached auth (user must sign in interactively).
 */
async function getValid() {
  const cached = loadCached();
  if (!cached) return null;
  if (cached.guest) return cached;
  if (cached.expiresAt && cached.expiresAt > Date.now()) return cached;
  if (!cached.msRefreshToken) return null;
  try {
    const msTok  = await refreshMsToken(cached.msRefreshToken);
    const mc     = await msToMinecraftToken(msTok.access_token);
    const record = { ...mc, msRefreshToken: msTok.refresh_token || cached.msRefreshToken, savedAt: Date.now() };
    saveCached(record);
    return record;
  } catch (err) {
    clearCached();
    _notifyExpired(err && err.message ? err.message : 'Refresh token expired');
    return null;
  }
}

function logout() { clearCached(); }

// ---- guest / offline mode ----

/** Deterministic offline UUID — matches vanilla's OfflinePlayer: algorithm. */
function offlineUuid(username) {
  const md5 = crypto.createHash('md5').update('OfflinePlayer:' + username, 'utf8').digest();
  md5[6] = (md5[6] & 0x0f) | 0x30;
  md5[8] = (md5[8] & 0x3f) | 0x80;
  const hex = md5.toString('hex');
  return `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`;
}

function sanitizeGuestName(input) {
  const cleaned = String(input || '').replace(/[^A-Za-z0-9_]/g, '').slice(0, 16);
  if (cleaned.length < 3) return null;
  return cleaned;
}

function loginAsGuest(rawName) {
  const name = sanitizeGuestName(rawName);
  if (!name) throw new Error('Guest names must be 3-16 characters: letters, digits, or underscore.');
  const record = {
    guest:          true,
    username:       name,
    uuid:           offlineUuid(name).replace(/-/g, ''),
    accessToken:    '0',
    expiresAt:      Date.now() + 365 * 24 * 60 * 60 * 1000,
    msRefreshToken: null,
    savedAt:        Date.now(),
  };
  saveCached(record);
  return record;
}

// ---- session expiry notifications ----

const expiryListeners = new Set();
function onSessionExpired(fn) { expiryListeners.add(fn); return () => expiryListeners.delete(fn); }
function _notifyExpired(reason) {
  for (const fn of expiryListeners) { try { fn(reason); } catch (_) {} }
}

module.exports = { login, getValid, logout, loadCached, onSessionExpired, loginAsGuest, sanitizeGuestName };
