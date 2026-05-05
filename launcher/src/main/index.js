// Electron main process entry point.
//
// Responsibilities:
//   - Create a single frameless 960×640 browser window
//   - Wire up the IPC handlers defined in ipc.js
//   - On window close, terminate any running game process so the user isn't
//     left with an orphan Java process unless they chose "keep open"
//   - Apply a minimal OS-level title "Fox Launcher"

const { app, BrowserWindow, shell, dialog, Tray, Menu, nativeImage } = require('electron');
const path = require('path');
const fs = require('fs');

const paths = require('./paths');
const settings = require('./settings');
const ipc = require('./ipc');
const launcher = require('./launcher');
const updater = require('./updater');
const presence = require('./presence');
const recommendedMods = require('./recommendedMods');

let mainWindow = null;
let autoUpdateTimer = null;
let tray = null;

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
    label: 'Quit',
    click: () => app.quit(),
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
  if (s.recommendedModsInstalled) return;

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
      essentialOnly: true,
      onProgress: (msg, percent) => send('recommended:progress', { message: msg, percent }),
    });
  } catch (err) {
    send('recommended:autoResult', { state: 'error', error: err.message || String(err) });
    return;
  }

  // Mark done unconditionally — even if some mods errored individually,
  // we don't want to retry the entire pack on every launcher boot.
  settings.patch({ recommendedModsInstalled: true });

  const installed = results.filter(r => r.status === 'installed').length;
  const errored   = results.filter(r => r.status === 'error' || r.status === 'no-version').length;
  send('recommended:autoResult', {
    state: errored ? 'partial' : (installed ? 'installed' : 'silent'),
    installed,
    errored,
  });
}

/** Single fire of the update check. Sends progress events while running and
 *  a one-shot `updater:result` event when done so the renderer can show a
 *  single toast per outcome. Never throws — every failure path is surfaced
 *  via the result event with `ok: false`. */
function runUpdateCheck() {
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

  // Intercept the window-close button when a game is running — hide to tray
  // instead of destroying the window so the user can still reach the launcher
  // while they play. On a normal (no game) close we let it through.
  mainWindow.on('close', (event) => {
    if (launcher.isRunning()) {
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

  // If the user toggles autoUpdate in Settings, react immediately rather
  // than waiting until the next launch. ipc.js fires this after every
  // settings:patch.
  ipc.onSettingsChanged(() => {
    const s = settings.load();
    if (s.autoUpdate) startAutoUpdate();
    else if (autoUpdateTimer) {
      clearInterval(autoUpdateTimer);
      autoUpdateTimer = null;
    }
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
