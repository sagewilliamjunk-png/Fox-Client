// OS desktop notifications for key launcher events.
//
// Uses Electron's built-in Notification API — zero external dependencies.
// All notifications are suppressed while the launcher window is focused
// (the UI itself already shows status inline) and on platforms that don't
// support the API (older macOS, some Linux desktops without libnotify).
//
// Pattern: every exported function is a named event, not a generic "send".
// Callers never build strings; they just call e.g. notifications.gameCrashed().

const { Notification, BrowserWindow } = require('electron');
const path = require('path');

const ICON_PATH = path.join(
  __dirname, '..', 'renderer', 'assets',
  process.platform === 'win32' ? 'fox.ico' : 'fox-256.png',
);

function canNotify() {
  return Notification.isSupported();
}

/** Returns true if any launcher window is currently focused. */
function launcherFocused() {
  try {
    return BrowserWindow.getAllWindows().some(w => !w.isDestroyed() && w.isFocused());
  } catch (_) {
    return false;
  }
}

/**
 * Show a desktop notification unless the launcher is focused or the
 * platform doesn't support it.
 *
 * @param {string} title
 * @param {string} body
 * @param {object} [opts]
 * @param {function} [opts.onClick]  Called when the user clicks the notification.
 * @param {boolean}  [opts.always]  Show even if the launcher window is focused.
 */
function send(title, body, { onClick, always = false } = {}) {
  if (!canNotify()) return;
  if (!always && launcherFocused()) return;
  try {
    const n = new Notification({
      title,
      body,
      icon: ICON_PATH,
      silent: false,
    });
    if (onClick) n.on('click', onClick);
    n.show();
  } catch (_) {
    // Notification API can throw on some Linux setups — swallow silently.
  }
}

// ---- named events ----

/**
 * Game process started successfully.
 * @param {string} [versionId]
 */
function gameStarted(versionId) {
  send(
    'Minecraft launched',
    versionId ? `Running ${versionId}` : 'Game is running',
  );
}

/**
 * Game exited with a non-zero code — crash detected.
 * `onClick` should bring the launcher window to focus so the user sees
 * the crash modal.
 * @param {function} [onClick]
 */
function gameCrashed(onClick) {
  send(
    'Minecraft crashed',
    'Click to open the crash report in Fox Launcher',
    { onClick, always: true },
  );
}

/**
 * Game closed cleanly (exit code 0).
 * Only shown when the window was hidden (user is playing with launcher in tray).
 */
function gameExited() {
  send('Minecraft closed', 'Back in Fox Launcher');
}

/**
 * A new Fox Client release is available and has been downloaded.
 * @param {string} [tag]  e.g. "v1.2.0"
 */
function updateAvailable(tag) {
  send(
    'Fox Client update ready',
    tag ? `Version ${tag} will install on next launch` : 'A new update is ready',
    { always: true },
  );
}

/**
 * Vanilla MC / JRE download completed in the background.
 * @param {string} what  Short label, e.g. "Minecraft 1.21.1" or "Java 21 JRE"
 */
function downloadComplete(what) {
  send('Download complete', `${what} is ready`, { always: true });
}

module.exports = { gameStarted, gameCrashed, gameExited, updateAvailable, downloadComplete };
