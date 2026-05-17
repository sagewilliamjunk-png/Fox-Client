// Discord presence state machine.
//
// Owns the singleton DiscordRpc instance, watches settings for the enabled
// flag + Application ID, and translates launcher-side events ("idle",
// "launching version X", "playing version X") into the Discord activity
// payload shape. ipc.js and index.js call into the small public surface
// here; nobody else touches DiscordRpc directly.
//
// While the game is running, startPoller(gameDir) kicks off a 3-second poll
// loop that reads <gameDir>/config/kitsune/game-state.json (written by the
// Fabric mod's GameStateBridge) and dynamically updates the Discord state
// field to show the current server / dimension.

const fs       = require('fs');
const path     = require('path');
const { DiscordRpc } = require('./discordRpc');
const settings = require('./settings');

let rpc = null;
let appBootedAt = Date.now();
let lastClientId = '';
let lastEnabled = null;

const LARGE_ASSET = 'fox_large';   // upload key in the Discord app portal
const SMALL_ASSET = 'fox_small';   // optional secondary key

const DOWNLOAD_URL = 'https://github.com/sagewilliamjunk-png/Fox-Client/releases/latest';
const DOWNLOAD_BTN = { label: 'Get Fox Client', url: DOWNLOAD_URL };

// ---- game-state poller state -----------------------------------------------

/** Timer handle for the active poll loop; null when not playing. */
let _pollTimer   = null;
/** Absolute path to the game-state.json the mod writes. */
let _stateFile   = null;
/** The base activity payload set when the game spawned (no state field). */
let _baseActivity = null;
/** Clean MC version string e.g. "1.21.1" — used when building state text. */
let _mcVersion   = null;

// ---- helpers ----------------------------------------------------------------

/** Strip the Fabric loader prefix from a versionId so Discord shows a clean
 *  MC version. "fabric-loader-0.16.5-1.21.1" → "1.21.1".
 *  Falls back to the raw string if no MC version pattern is found. */
function _cleanVersion(versionId) {
  if (!versionId) return '';
  const m = String(versionId).match(/(\d+\.\d+(?:\.\d+)?)$/);
  return m ? m[1] : versionId;
}

/**
 * Map a Minecraft dimension key to a human-readable label.
 * The overworld is omitted — it's the default and not interesting.
 */
function _dimensionLabel(dim) {
  if (!dim) return null;
  const map = {
    'minecraft:overworld':   null,          // suppress — default dimension
    'minecraft:the_nether':  'The Nether',
    'minecraft:the_end':     'The End',
  };
  if (dim in map) return map[dim];
  // Custom dimension: "mynamespace:cool_world" → "Cool World"
  const part = dim.includes(':') ? dim.split(':')[1] : dim;
  return part.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
}

/**
 * Strip common server-address prefixes so "mc.hypixel.net" becomes
 * "hypixel.net" — cleaner in the Discord presence strip.
 */
function _cleanServer(addr) {
  if (!addr) return null;
  return addr.trim()
    .replace(/^(play|mc|server|join)\./i, '')
    .split(':')[0]   // strip port
    .toLowerCase();
}

function _ensureRpc() {
  const s = settings.load();
  // Settings change handling — if the user toggled off, dispose; if the
  // Application ID changed, reconnect with the new one.
  if (!s.discordRpcEnabled || !s.discordAppId) {
    if (rpc) { rpc.dispose(); rpc = null; }
    lastClientId = '';
    lastEnabled = false;
    return null;
  }
  if (!rpc || lastClientId !== s.discordAppId) {
    if (rpc) rpc.dispose();
    lastClientId = s.discordAppId;
    rpc = new DiscordRpc({
      clientId: s.discordAppId,
      log: (msg) => { /* swallow — surface via logs.js if useful */ },
    });
    rpc.start();
  }
  lastEnabled = true;
  return rpc;
}

// ---- game-state poller ------------------------------------------------------

/**
 * Build the Discord `state` string from a parsed game-state object.
 * Falls back to just "MC <version>" when no context is available.
 */
function _buildState(gameState) {
  const parts = [];

  if (gameState) {
    if (gameState.server) {
      parts.push(_cleanServer(gameState.server));
    } else if (gameState.singleplayer) {
      parts.push(`Singleplayer`);
    }
    const dim = _dimensionLabel(gameState.dimension);
    if (dim) parts.push(dim);
  }

  if (_mcVersion) parts.push(`MC ${_mcVersion}`);
  return parts.join(' · ') || undefined;
}

/** One poll tick — read the state file and update Discord if it changed. */
function _tick() {
  const r = _ensureRpc();
  if (!r || !_baseActivity || !_stateFile) return;

  let gameState = null;
  try {
    const raw = fs.readFileSync(_stateFile, 'utf8');
    const parsed = JSON.parse(raw);
    // Ignore files older than 10 s — mod probably disconnected.
    if (Date.now() - (parsed.timestamp || 0) < 10_000) {
      gameState = parsed;
    }
  } catch (_) { /* file absent or parse error — treat as no state */ }

  try {
    r.setActivity({ ..._baseActivity, state: _buildState(gameState) });
  } catch (_) {}
}

/**
 * Start the game-state poll loop.
 * Called from ipc.js immediately after the game process spawns.
 * @param {string} gameDir  The user-data game directory (where config/ lives).
 */
function startPoller(gameDir) {
  stopPoller();
  if (!gameDir) return;
  _stateFile = path.join(gameDir, 'config', 'kitsune', 'game-state.json');
  _pollTimer = setInterval(_tick, 3_000);
}

/** Stop the poll loop. Called when the game exits or presence goes idle. */
function stopPoller() {
  if (_pollTimer) { clearInterval(_pollTimer); _pollTimer = null; }
  _stateFile   = null;
  _baseActivity = null;
  _mcVersion   = null;
}

// ---- public API -------------------------------------------------------------

/** Call after settings + window are ready. */
function init() {
  appBootedAt = Date.now();
  setIdle();
}

/** Call from app.on('before-quit') — gracefully closes the IPC pipe. */
function dispose() {
  stopPoller();
  if (rpc) { rpc.dispose(); rpc = null; }
}

/** User has the launcher open but no game running. */
function setIdle() {
  stopPoller();
  const r = _ensureRpc();
  if (!r) return;
  r.setActivity({
    details: 'In the launcher',
    state: 'Picking a profile',
    startTimestamp: appBootedAt,
    largeImageKey: LARGE_ASSET,
    largeImageText: 'Fox Client',
    smallImageKey: SMALL_ASSET,
    smallImageText: 'Idle',
    buttons: [DOWNLOAD_BTN],
  });
}

/** Pre-flight + spawn of the Java process. */
function setLaunching(versionId) {
  const r = _ensureRpc();
  if (!r) return;
  const mc = _cleanVersion(versionId);
  r.setActivity({
    details: 'Starting up…',
    state: mc ? `MC ${mc}` : undefined,
    startTimestamp: Date.now(),
    largeImageKey: LARGE_ASSET,
    largeImageText: 'Fox Client',
    smallImageKey: SMALL_ASSET,
    smallImageText: 'Loading…',
    buttons: [DOWNLOAD_BTN],
  });
}

/**
 * Game spawned successfully.
 * @param {string}  versionId    Fabric version string e.g. "fabric-loader-0.16.5-1.21.1"
 * @param {number}  startedAt    ms timestamp of spawn (for Discord elapsed timer)
 * @param {string}  profileName  Optional — the active profile's display name
 * @param {string}  gameDir      Optional — path to the user-data game dir; starts the
 *                               game-state poller for dynamic server/dimension updates
 */
function setPlaying(versionId, startedAt, profileName, gameDir) {
  const r = _ensureRpc();
  if (!r) return;
  const mc = _cleanVersion(versionId);
  _mcVersion = mc || null;
  _baseActivity = {
    details:        profileName ? `Playing · ${profileName}` : 'Playing Minecraft',
    startTimestamp: startedAt || Date.now(),
    largeImageKey:  LARGE_ASSET,
    largeImageText: 'Fox Client',
    buttons:        [DOWNLOAD_BTN],
  };
  // Initial render before the first poll tick fires.
  r.setActivity({ ..._baseActivity, state: _buildState(null) });
  if (gameDir) startPoller(gameDir);
}

/** Settings change happened — re-evaluate enabled flag / AppID. Idempotent. */
function refresh() {
  _ensureRpc();
}

/** Snapshot for the Settings UI. Cheap; safe to poll. */
function status() {
  const s = settings.load();
  if (!s.discordRpcEnabled) return { state: 'disabled' };
  if (!s.discordAppId)      return { state: 'no-app-id' };
  if (!rpc)                 return { state: 'starting' };
  return { state: rpc.isConnected() ? 'connected' : 'waiting' };
}

module.exports = { init, dispose, setIdle, setLaunching, setPlaying, startPoller, stopPoller, refresh, status };
