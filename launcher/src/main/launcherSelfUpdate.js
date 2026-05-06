// Launcher self-update — wraps electron-updater.
//
// Only active in packaged builds (app.isPackaged). In dev mode every call
// returns a 'dev-mode' state immediately so the renderer can show a clear
// no-op message rather than silently doing nothing.

const { app } = require('electron');

let autoUpdater = null;
try {
  ({ autoUpdater } = require('electron-updater'));
  autoUpdater.autoDownload = true;
  autoUpdater.autoInstallOnAppQuit = true;
  autoUpdater.logger = null; // suppress electron-log chatter
} catch (_) {
  // electron-updater not installed — feature gracefully disabled
}

let _emitter   = null;
let _state     = { state: 'idle' };
let _wired     = false;

function setEmitter(fn) { _emitter = fn; }

function _emit(payload) {
  _state = payload;
  if (_emitter) _emitter('launcher:update', payload);
}

function init() {
  if (_wired || !autoUpdater || !app.isPackaged) return;
  _wired = true;

  autoUpdater.on('checking-for-update',  ()     => _emit({ state: 'checking' }));
  autoUpdater.on('update-available',     (info) => _emit({ state: 'available',   version: info.version }));
  autoUpdater.on('update-not-available', ()     => _emit({ state: 'up-to-date' }));
  autoUpdater.on('download-progress',    (p)    => _emit({ state: 'downloading', percent: Math.round(p.percent) }));
  autoUpdater.on('update-downloaded',    (info) => _emit({ state: 'ready',       version: info.version }));
  autoUpdater.on('error',                (err)  => _emit({ state: 'error',       error: err.message }));
}

function check() {
  if (!autoUpdater || !app.isPackaged) {
    _emit({ state: 'dev-mode' });
    return;
  }
  autoUpdater.checkForUpdates().catch((err) => _emit({ state: 'error', error: err.message }));
}

function install() {
  if (!autoUpdater || !app.isPackaged) return;
  autoUpdater.quitAndInstall(false, true);
}

function currentState() { return _state; }

module.exports = { init, check, install, currentState, setEmitter };
