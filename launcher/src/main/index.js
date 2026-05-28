// Electron main process entry point.
//
// Responsibilities:
//   - Create a single frameless 960×640 browser window
//   - Wire up the IPC handlers defined in ipc.js
//   - On window close, terminate any running game process so the user isn't
//     left with an orphan Java process unless they chose "keep open"
//   - Apply a minimal OS-level title "Fox Launcher"

const { app, BrowserWindow, shell, dialog, Tray, Menu, nativeImage } = require('electron');

// ---- startup helper ----

/** Sync Electron's login-item (Windows startup / macOS login) with the
 *  user's launchOnStartup preference.  Safe to call at any time — on
 *  platforms that don't support login items this is a no-op. */
function applyLoginItem(s) {
  try {
    const enabled = !!(s && s.launchOnStartup);
    if (process.platform === 'win32') {
      app.setLoginItemSettings({ openAtLogin: enabled });
    } else if (process.platform === 'darwin') {
      // openAsHidden keeps the dock icon suppressed on login.
      app.setLoginItemSettings({ openAtLogin: enabled, openAsHidden: true });
    }
    // Linux: app.setLoginItemSettings is a no-op; not supported by Electron.
  } catch (_) { /* best effort — some sandboxed environments reject this */ }
}
const path = require('path');
const fs = require('fs');

const paths = require('./paths');
const settings = require('./settings');
const ipc = require('./ipc');
const launcher = require('./launcher');
const updater = require('./updater');
const presence = require('./presence');
const recommendedMods = require('./recommendedMods');
const notifications = require('./notifications');

let mainWindow = null;
let autoUpdateTimer = null;
let tray = null;
/** Set to true when the user picks Quit from the tray menu — bypasses the
 *  close handler's hide-to-tray interception so the app actually exits. */
let _quitting = false;

// ---- System tray ----

function showWindow() {
  if (!mainWindow) {
    createWindow();
    return;
  }
  mainWindow.show();
  mainWindow.focus();
}

function buildTrayMenu() {
  const gameRunning = launcher.isRunning();
  const items = [
    { label: 'Show Fox Launcher', click: showWindow },
  ];
  if (gameRunning) {
    items.push({ label: 'Kill Game', click: () => launcher.stop() });
  }
  items.push({ type: 'separator' });
  items.push({
    label: 'Quit Fox Launcher',
    click: () => { _quitting = true; app.quit(); },
  });
  return Menu.buildFromTemplate(items);
}

function createTray() {
  if (tray) { tray.setContextMenu(buildTrayMenu()); return; }

  let icon;
  try {
    icon = nativeImage.createFromPath(ICON_PATH);
    if (process.platform === 'darwin') {
      icon = icon.resize({ width: 16, height: 16 });
      icon.setTemplateImage(true);
    } else if (process.platform === 'win32') {
      // ICO is multi-resolution; Electron picks the right size automatically.
    }
  } catch (_) {
    icon = nativeImage.createEmpty();
  }

  tray = new Tray(icon);
  tray.setToolTip('Fox Launcher');
  tray.setContextMenu(buildTrayMenu());
  // Left-click on the tray icon shows the launcher window.
  tray.on('click', showWindow);
  // Rebuild context menu on right-click so "Kill Game" reflects live state.
  tray.on('right-click', () => tray && tray.setContextMenu(buildTrayMenu()));
}

function destroyTray() {
  if (!tray) return;
  try { tray.destroy(); } catch (_) {}
  tray = null;
}

/**
 * Background one-time install of the curated performance pack. Conditions:
 *   - flag in settings hasn't been flipped yet,
 *   - game directory exists,
 *   - a Fabric profile is installed for our pinned MC version.
 *
 * Stays silent on the no-op paths (already done, nothing to install,
 * skipped because already present) — the only user-facing surface is a
 * single toast at the end if something actually changed or errored.
 */
async function autoInstallRecommended() {
  let s = settings.load();
  // v1.3+: we no longer short-circuit when the "installed-full" flag is set.
  // recommendedMods.installAll already short-circuits per-mod via the
  // version-aware manifest, so it's idempotent — running it every boot
  // costs ~50 ms when everything's present and naturally picks up any new
  // mods we add to the recommended list across releases. Users who want
  // to skip a particular mod manually still can (delete the jar; the
  // manifest entry stays so we don't re-fetch).
  // The flag is preserved as historical state but no longer gates the run.

  let readiness;
  try { readiness = launcher.clientReadiness(); }
  catch (_) { return; }
  if (!readiness || !readiness.gameDirExists || !readiness.fabricProfile) return;

  const send = (channel, payload) => {
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send(channel, payload);
    }
  };

  let results;
  try {
    results = await recommendedMods.installAll(readiness.gameDir, readiness.targetMcVersion, {
      essentialOnly: false,
      onProgress: (msg, percent) => send('recommended:progress', { message: msg, percent }),
    });
  } catch (err) {
    send('recommended:autoResult', { state: 'error', error: err.message || String(err) });
    return;
  }

  // Mark done unconditionally — even if some mods errored individually,
  // we don't want to retry the entire pack on every launcher boot.
  settings.patch({ recommendedModsInstalledFull: true });

  const installed = results.filter(r => r.status === 'installed').length;
  const errored   = results.filter(r => r.status === 'error' || r.status === 'no-version').length;
  send('recommended:autoResult', {
    state: errored ? 'partial' : (installed ? 'installed' : 'silent'),
    installed,
    errored,
  });
}

/** Background mod-update check for the active profile. Silent on "all up to
 *  date" (we never interrupt for a no-op); fires a single toast when updates
 *  are available. The renderer wires the toast to a "review updates" action
 *  that navigates to Profiles. */
async function checkActiveProfileModUpdates() {
  try {
    const s = settings.load();
    const profileId = s.selectedProfile;
    const readiness = launcher.clientReadiness();
    if (!readiness || !readiness.gameDirExists) return;
    // Lazy require avoids paying the modUpdates load cost at boot for users
    // whose first action isn't a profile launch.
    const modUpdates = require('./modUpdates');
    let gameDir = readiness.gameDir;
    // gameDirForProfile is in ipc.js so we duplicate the resolution here
    // rather than thread that helper out. Same logic as the IPC handler.
    const profiles = require('./profiles');
    const p = profileId ? profiles.find(profileId) : null;
    if (p && p.isolated) gameDir = paths.instanceDir(profileId);
    else if (p && p.gameDirOverride && p.gameDirOverride.trim()) gameDir = p.gameDirOverride.trim();

    const result = await modUpdates.checkForUpdates(gameDir, readiness.targetMcVersion);
    if (!result || !result.updates || result.updates.length === 0) return;

    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send('modUpdates:autoResult', {
        count:    result.updates.length,
        scanned:  result.scanned,
        resolved: result.resolved,
      });
    }
  } catch (_) { /* silent failure — re-check next boot */ }
}

/** Single fire of the update check. Sends progress events while running and
 *  a one-shot `updater:result` event when done so the renderer can show a
 *  single toast per outcome. Never throws — every failure path is surfaced
 *  via the result event with `ok: false`. */
let _updateInFlight = false;
function runUpdateCheck() {
  // Re-entry guard: if a previous check is still running (slow network, hung
  // CDN), don't kick off another one — the 30-min interval timer would
  // otherwise stack concurrent invocations on each fire.
  if (_updateInFlight) return;
  _updateInFlight = true;
  const send = (channel, payload) => {
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send(channel, payload);
    }
  };
  send('updater:result', { state: 'checking' });
  updater.checkAndDownload({
    onProgress: (msg) => send('updater:progress', { message: msg }),
  }).then((result) => {
    if (!result) {
      send('updater:result', { state: 'no-asset' });
      return;
    }
    send('updater:result', {
      state: result.updated ? 'updated' : 'up-to-date',
      tag: result.tag,
    });
  }).catch((err) => {
    send('updater:result', { state: 'error', error: err.message || String(err) });
  }).finally(() => {
    _updateInFlight = false;
  });
}

// Path to the launcher icon. Used both as the BrowserWindow icon (taskbar /
// alt-tab on Windows, dock on Linux) and as the dock image on macOS.
// On Windows we prefer the .ico — multi-resolution, sharper at small sizes.
const ICON_PATH = path.join(
  __dirname, '..', 'renderer', 'assets',
  process.platform === 'win32' ? 'fox.ico' : 'fox.png',
);

// Disable GPU compositing. On some Windows setups (certain drivers, sandboxed
// environments, first-run antivirus scans) Electron's GPU process hangs for
// minutes before the renderer can start — keeping the splash frozen even
// though the main process is ready. Software rendering is indistinguishable
// for a launcher-style UI and starts instantly.
app.disableHardwareAcceleration();

// Windows groups taskbar buttons by AppUserModelID. Without an explicit one
// we'd be lumped under "electron.exe" and pick up Electron's default icon
// in dev mode. Setting our own ID is also a no-op on macOS / Linux.
if (process.platform === 'win32') {
  app.setAppUserModelId('dev.kitsune.foxlauncher');
}

function createWindow() {
  paths.ensureAll();
  settings.load();

  mainWindow = new BrowserWindow({
    width: 960,
    height: 640,
    minWidth: 800,
    minHeight: 560,
    title: 'Fox Launcher',
    backgroundColor: '#1a1a1e',
    autoHideMenuBar: true,
    icon: ICON_PATH,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      preload: path.join(__dirname, '..', 'preload', 'index.js'),
      sandbox: false,
    },
  });

  // macOS: explicit dock icon — BrowserWindow.icon is ignored on darwin.
  if (process.platform === 'darwin' && app.dock) {
    try { app.dock.setIcon(ICON_PATH); } catch (_) {}
  }

  // External links open in the default browser, not the launcher
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url);
    return { action: 'deny' };
  });

  mainWindow.loadFile(path.join(__dirname, '..', 'renderer', 'index.html'));

  if (process.argv.includes('--dev')) {
    mainWindow.webContents.openDevTools({ mode: 'detach' });
  }

  // Intercept the window close button:
  //   - When a game is running, always hide to tray (preserves the launcher
  //     so the user can reach Modrinth / settings / Discord status mid-play).
  //   - When the user has enabled "Minimize to tray" in settings, always
  //     hide instead of quitting — they have to use Quit from the tray.
  //   - Otherwise vanilla "X = quit" behaviour.
  mainWindow.on('close', (event) => {
    if (_quitting) return; // explicit app.quit() — let it through
    const s = settings.load();
    if (launcher.isRunning() || s.minimizeToTray) {
      event.preventDefault();
      mainWindow.hide();
      createTray();
    }
  });

  mainWindow.on('closed', () => {
    mainWindow = null;
    destroyTray();
  });
}

// Single-instance lock — opening Fox Launcher a second time focuses the
// existing window instead of spawning a duplicate (which would also
// duplicate the Discord RPC connection, the auto-update timer, etc.).
const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.show();
      mainWindow.focus();
    }
  });
}

app.whenReady().then(() => {
  ipc.register(() => mainWindow);
  createWindow();
  presence.init();

  // ---- Taskbar progress bar (Windows/Linux) --------------------------------
  // launcher.js calls _progress(0–100) during downloads and _progress(-1) to
  // clear. We just forward those into Electron's native taskbar progress API.
  launcher.setProgressEmitter((pct) => {
    if (!mainWindow || mainWindow.isDestroyed()) return;
    mainWindow.setProgressBar(pct < 0 ? -1 : pct / 100);
  });

  // ---- Taskbar overlay icon — red dot on crash ----------------------------
  // crash-dot.png is a small 16×16 red circle. Shown until the user opens
  // the launcher window (at which point the overlay is cleared automatically
  // because the window becomes focused / the user has seen the state).
  const CRASH_DOT = path.join(__dirname, '..', 'renderer', 'assets', 'crash-dot.png');
  let crashDotIcon = null;
  try { crashDotIcon = nativeImage.createFromPath(CRASH_DOT); } catch (_) {}

  launcher.setOverlayEmitter((state) => {
    if (!mainWindow || mainWindow.isDestroyed()) return;
    if (state === 'crashed' && crashDotIcon && !crashDotIcon.isEmpty()) {
      mainWindow.setOverlayIcon(crashDotIcon, 'Game crashed');
    } else {
      mainWindow.setOverlayIcon(null, '');
    }
  });

  // Clear the overlay whenever the user focuses the launcher window so they
  // aren't left with a permanent red dot after they've seen the crash screen.
  mainWindow.on('focus', () => {
    if (!mainWindow.isDestroyed()) mainWindow.setOverlayIcon(null, '');
  });

  // ---- Launcher self-update (electron-updater + GitHub Releases) ----
  // Only active in a packaged build — dev mode skips this entirely.
  if (app.isPackaged) {
    try {
      const { autoUpdater } = require('electron-updater');
      autoUpdater.autoDownload    = true;   // download in background
      autoUpdater.autoInstallOnAppQuit = true; // install when user quits

      autoUpdater.on('update-downloaded', (info) => {
        // OS notification so the user knows even if the launcher is minimised.
        notifications.updateAvailable(info && info.version ? `v${info.version}` : null);
        // Notify the renderer so it can show a "Restart to update" banner.
        if (mainWindow && !mainWindow.isDestroyed()) {
          mainWindow.webContents.send('launcher:update-ready');
        }
      });

      // Check on boot (5 s delay so the window is ready) and every 4 hours.
      setTimeout(() => autoUpdater.checkForUpdates().catch(() => {}), 5_000);
      setInterval(() => autoUpdater.checkForUpdates().catch(() => {}), 4 * 60 * 60 * 1000);
    } catch (_) {
      // electron-updater not installed or not configured — silently skip.
    }
  }

  // Apply launch-on-startup preference immediately on boot.
  applyLoginItem(settings.load());

  // When the game exits and the window is hidden in the tray, bring it
  // back so the user doesn't get stranded without a visible launcher.
  launcher.setExitHook(() => {
    destroyTray();
    if (mainWindow && !mainWindow.isVisible()) {
      mainWindow.show();
      mainWindow.focus();
    }
  });

  // Background updater. Runs once on boot (1.5 s delay so the renderer is
  // listening) and again every 30 minutes for long-running sessions. The
  // renderer surfaces results via the unified updater event channel so we
  // can show a single toast per outcome instead of needing a manual button.
  const startAutoUpdate = () => {
    const s = settings.load();
    if (!s.autoUpdate) return;
    runUpdateCheck();
    if (autoUpdateTimer) clearInterval(autoUpdateTimer);
    autoUpdateTimer = setInterval(runUpdateCheck, 30 * 60 * 1000);
  };
  setTimeout(startAutoUpdate, 1500);

  // Background, one-time install of the curated performance pack. Quiet
  // by default — only toasts when something actually landed or when the
  // install errored. Skips if Fabric isn't installed yet (mods would do
  // nothing) or the user has already had it run successfully.
  setTimeout(autoInstallRecommended, 4000);

  // Background mod-update check (Modrinth). Runs once at boot for the
  // active profile, then surfaces a single toast if anything's stale.
  // Silent on "all up to date" so we never interrupt the user for a no-op.
  setTimeout(checkActiveProfileModUpdates, 12_000);

  // If the user toggles autoUpdate in Settings, react immediately rather
  // than waiting until the next launch. ipc.js fires this after every
  // settings:patch.
  ipc.onSettingsChanged((s) => {
    const loaded = s || settings.load();
    if (loaded.autoUpdate) startAutoUpdate();
    else if (autoUpdateTimer) {
      clearInterval(autoUpdateTimer);
      autoUpdateTimer = null;
    }
    // Re-sync login-item whenever any setting changes — cheap and safe.
    applyLoginItem(loaded);
  });

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});

let quitConfirmed = false;
app.on('before-quit', (event) => {
  if (quitConfirmed) return;
  if (!launcher.isRunning()) return;

  const resp = dialog.showMessageBoxSync({
    type: 'warning',
    buttons: ['Quit and stop game', 'Cancel'],
    defaultId: 1,
    cancelId: 1,
    message: 'A game is still running. Quitting will terminate it.',
  });

  // Always intercept this first quit — we either cancel, or shut the game
  // down asynchronously and re-issue the quit after the child has exited.
  event.preventDefault();
  if (resp === 1) return;

  launcher.stopAndWait().then(() => {
    quitConfirmed = true;
    app.quit();
  });
});

app.on('will-quit', () => {
  // Final tear-down — IPC pipe gets a clean Close frame.
  try { presence.dispose(); } catch (_) {}
  if (autoUpdateTimer) { clearInterval(autoUpdateTimer); autoUpdateTimer = null; }
  destroyTray();
});
