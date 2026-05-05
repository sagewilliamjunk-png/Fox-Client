// Discord presence state machine.
//
// Owns the singleton DiscordRpc instance, watches settings for the enabled
// flag + Application ID, and translates launcher-side events ("idle",
// "launching version X", "playing version X") into the Discord activity
// payload shape. ipc.js and index.js call into the small public surface
// here; nobody else touches DiscordRpc directly.

const { DiscordRpc } = require('./discordRpc');
const settings = require('./settings');

let rpc = null;
let appBootedAt = Date.now();
let lastClientId = '';
let lastEnabled = null;

const LARGE_ASSET = 'fox_large';   // upload key in the Discord app portal
const SMALL_ASSET = 'fox_small';   // optional secondary key

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

/** Call after settings + window are ready. */
function init() {
  appBootedAt = Date.now();
  setIdle();
}

/** Call from app.on('before-quit') — gracefully closes the IPC pipe. */
function dispose() {
  if (rpc) { rpc.dispose(); rpc = null; }
}

/** User has the launcher open but no game running. */
function setIdle() {
  const r = _ensureRpc();
  if (!r) return;
  r.setActivity({
    details: 'In the launcher',
    state: 'Picking a profile',
    startTimestamp: appBootedAt,
    largeImageKey: LARGE_ASSET,
    largeImageText: 'Fox Launcher',
  });
}

/** Pre-flight + spawn of the Java process. */
function setLaunching(versionId) {
  const r = _ensureRpc();
  if (!r) return;
  r.setActivity({
    details: 'Launching Minecraft',
    state: versionId ? `Version ${versionId}` : undefined,
    startTimestamp: Date.now(),
    largeImageKey: LARGE_ASSET,
    largeImageText: 'Fox Launcher',
    smallImageKey: SMALL_ASSET,
    smallImageText: 'Loading…',
  });
}

/** Game spawned successfully. `startedAt` should be the launch start so the
 *  Discord "elapsed" timer is accurate. */
function setPlaying(versionId, startedAt) {
  const r = _ensureRpc();
  if (!r) return;
  r.setActivity({
    details: 'Playing Minecraft',
    state: versionId ? `Version ${versionId}` : undefined,
    startTimestamp: startedAt || Date.now(),
    largeImageKey: LARGE_ASSET,
    largeImageText: 'Fox Client',
  });
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

module.exports = { init, dispose, setIdle, setLaunching, setPlaying, refresh, status };
