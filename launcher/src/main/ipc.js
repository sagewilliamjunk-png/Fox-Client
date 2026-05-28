// IPC surface for the renderer. Every user-facing action goes through one of
// these handlers. The preload script mirrors this list into window.fox.*.

const { ipcMain, dialog, shell } = require('electron');
const fs = require('fs');
const path = require('path');

const settings = require('./settings');
const auth = require('./auth');
const javaDetect = require('./java');
const launcher = require('./launcher');
const updater = require('./updater');
const mcVersion = require('./mcVersion');
const logs = require('./logs');
const paths = require('./paths');
const system = require('./system');
const crashReports = require('./crashReports');
const news = require('./news');
const presence = require('./presence');
const addons = require('./addons');
const recommendedMods = require('./recommendedMods');
const modrinthBrowser = require('./modrinthBrowser');
const modUpdates      = require('./modUpdates');
const modpackImport   = require('./modpackImport');
const modpackExport   = require('./modpackExport');
const profiles = require('./profiles');
const resourcepacks = require('./resourcepacks');
const skins = require('./skins');
const javaDownloader = require('./javaDownloader');
const mcInstaller = require('./mcInstaller');

// Subscribers fired after every successful settings:patch. Lets other main-
// process modules (e.g. index.js's auto-update timer) react to user changes
// without ipc.js needing to know about them.
const _settingsSubs = new Set();
function onSettingsChanged(fn) {
  _settingsSubs.add(fn);
  return () => _settingsSubs.delete(fn);
}

function register(getWindow) {
  // ---- settings ----
  ipcMain.handle('settings:get', () => settings.load());
  ipcMain.handle('settings:patch', (_e, partial) => {
    const prev = settings.load();
    const next = settings.patch(partial);
    // Java detection is cached for performance — invalidate when javaPath
    // changes so a freshly browsed JDK takes effect immediately.
    if (prev.javaPath !== next.javaPath) {
      try { javaDetect.invalidateCache(); } catch (_) {}
    }
    // Re-evaluate Discord presence in case the user toggled it / changed
    // the App ID. Cheap — refresh() returns immediately when nothing changed.
    try { presence.refresh(); } catch (_) {}
    // Notify any local subscribers (auto-update timer, etc.).
    for (const fn of _settingsSubs) { try { fn(next); } catch (_) {} }
    return next;
  });

  // ---- auth ----
  ipcMain.handle('auth:status', async () => {
    const cached = auth.loadCached();
    if (!cached) return { signedIn: false };
    const allAccounts = auth.listAccounts();
    return {
      signedIn: true,
      username: cached.username,
      uuid: cached.uuid,
      expiresAt: cached.expiresAt,
      guest: !!cached.guest,
      accountCount: allAccounts.length,
    };
  });

  ipcMain.handle('auth:login', async () => {
    const w = getWindow();
    const send = (ch, payload) => { if (w) w.webContents.send(ch, payload); };
    try {
      const record = await auth.login({
        // Auth now runs in an embedded BrowserWindow — no external browser.
        // We still fire auth:browser-opened so the renderer shows its spinner.
        onBrowserOpen: () => {
          send('auth:browser-opened', {});
        },
      });
      send('auth:done', { username: record.username, uuid: record.uuid });
      return { ok: true, username: record.username, uuid: record.uuid };
    } catch (err) {
      send('auth:error', { message: err.message, childAccountRedirect: err.childAccountRedirect || null });
      return { ok: false, error: err.message };
    }
  });

  ipcMain.handle('auth:logout', () => { auth.logout(); return { ok: true }; });

  // ---- multi-account helpers ----

  /** List all stored accounts (display fields only). */
  ipcMain.handle('auth:listAccounts', () => {
    try { return auth.listAccounts(); }
    catch (_) { return []; }
  });

  /** Switch the active account. Returns the new active record or null. */
  ipcMain.handle('auth:setActiveAccount', (_e, id) => {
    try {
      const result = auth.setActiveAccount(id);
      // Notify the renderer so the sidebar refreshes.
      const w = getWindow();
      if (w && !w.isDestroyed()) w.webContents.send('auth:accountChanged', { id });
      return { ok: !!result, account: result };
    } catch (err) {
      return { ok: false, error: err.message };
    }
  });

  /** Remove an account from the store. Fires auth:accountChanged. */
  ipcMain.handle('auth:removeAccount', (_e, id) => {
    try {
      const newActiveId = auth.removeAccount(id);
      const w = getWindow();
      if (w && !w.isDestroyed()) w.webContents.send('auth:accountChanged', { id: newActiveId });
      return { ok: true, newActiveId };
    } catch (err) {
      return { ok: false, error: err.message };
    }
  });

  ipcMain.handle('auth:guest', (_e, username) => {
    try {
      const record = auth.loginAsGuest(username);
      // Stash the cleaned name so the login field pre-fills next launch.
      try { settings.patch({ lastGuestName: record.username }); } catch (_) {}
      return { ok: true, username: record.username, uuid: record.uuid };
    } catch (err) {
      return { ok: false, error: err.message };
    }
  });

  // Forward silent-refresh failures so the renderer can show a toast
  // explaining why the user is being prompted to sign in again.
  auth.onSessionExpired((reason) => {
    const w = getWindow();
    if (w && !w.isDestroyed()) w.webContents.send('auth:session-expired', { reason });
  });

  // ---- java ----
  ipcMain.handle('java:detect', async () => {
    try {
      const s = settings.load();
      return await javaDetect.detect(s.javaPath);
    } catch (err) {
      return { ok: false, reason: err.message, path: null, major: 0, versionString: null };
    }
  });

  ipcMain.handle('java:detectAll', async () => {
    try {
      const s = settings.load();
      return await javaDetect.detectAll(s.javaPath);
    } catch (err) {
      return { required: javaDetect.REQUIRED_MAJOR, results: [], error: err.message };
    }
  });

  ipcMain.handle('java:browse', async () => {
    const w = getWindow();
    const r = await dialog.showOpenDialog(w, {
      title: 'Select Java executable',
      properties: ['openFile'],
      filters: process.platform === 'win32'
        ? [{ name: 'Java', extensions: ['exe'] }]
        : [{ name: 'All files', extensions: ['*'] }],
    });
    if (r.canceled || !r.filePaths[0]) return null;
    const picked = r.filePaths[0];
    const probed = await javaDetect.probe(picked);
    return probed;
  });

  // ---- java install ----
  // Triggered by the renderer when Java is missing and the user clicks
  // "Download Java" instead of waiting for the auto-download on launch.
  ipcMain.handle('java:install', async () => {
    const w = getWindow();
    const send = (payload) => {
      if (w && !w.isDestroyed()) w.webContents.send('java:install:progress', payload);
    };
    try {
      const jrePath = await javaDownloader.ensureJre(({ stage, message, percent }) => {
        send({ stage, message, percent });
      });
      javaDetect.invalidateCache();
      const probed = await javaDetect.probe(jrePath);
      return { ok: true, javaPath: jrePath, probed };
    } catch (err) {
      return { ok: false, error: err.message };
    }
  });

  // ---- mc install ----
  // On-demand download of a vanilla MC version. Also called automatically
  // by launcher.js during game:launch when the version is missing.
  ipcMain.handle('mc:isInstalled', (_e, versionId) => {
    try {
      const s = settings.load();
      const gameDir = s.gameDir && s.gameDir.trim() ? s.gameDir : paths.defaultMinecraft();
      return mcInstaller.isInstalled(gameDir, versionId);
    } catch (_) { return false; }
  });

  ipcMain.handle('mc:listAvailable', async (_e, includeSnapshots) => {
    try {
      return await mcInstaller.listAvailable({ includeSnapshots: !!includeSnapshots });
    } catch (err) {
      return { error: err.message, versions: [] };
    }
  });

  ipcMain.handle('mc:install', async (_e, versionId) => {
    const w = getWindow();
    const send = (payload) => {
      if (w && !w.isDestroyed()) w.webContents.send('mc:install:progress', payload);
    };
    try {
      const s = settings.load();
      const gameDir = s.gameDir && s.gameDir.trim() ? s.gameDir : paths.defaultMinecraft();
      const result = await mcInstaller.installVersion(gameDir, versionId, {
        onProgress: ({ stage, message, percent }) => send({ stage, message, percent }),
      });
      return { ok: true, ...result };
    } catch (err) {
      return { ok: false, error: err.message };
    }
  });

  // ---- game directory ----
  ipcMain.handle('gamedir:browse', async () => {
    const w = getWindow();
    const r = await dialog.showOpenDialog(w, {
      title: 'Select Minecraft game directory',
      properties: ['openDirectory'],
    });
    if (r.canceled || !r.filePaths[0]) return null;
    return r.filePaths[0];
  });
  ipcMain.handle('gamedir:default', () => paths.defaultMinecraft());

  // ---- versions ----
  ipcMain.handle('versions:list', () => {
    const s = settings.load();
    const dir = s.gameDir && s.gameDir.trim() ? s.gameDir : paths.defaultMinecraft();
    if (!fs.existsSync(dir)) return { gameDir: dir, exists: false, versions: [] };
    return { gameDir: dir, exists: true, versions: mcVersion.listVersions(dir) };
  });

  // Enriched listing — every install with the Java requirement, loader
  // family, and runnable flag. Used by Home (filter to runnable, default
  // to newest) and by the Versions tab (show legacy/modern split).
  ipcMain.handle('versions:listEnriched', async () => {
    const s = settings.load();
    const dir = s.gameDir && s.gameDir.trim() ? s.gameDir : paths.defaultMinecraft();
    if (!fs.existsSync(dir)) return { gameDir: dir, exists: false, versions: [], hostJavaMajor: 0 };
    let hostJavaMajor = 0;
    try {
      const j = await javaDetect.detect(s.javaPath);
      hostJavaMajor = j.major || 0;
    } catch (_) {}
    return {
      gameDir: dir,
      exists: true,
      hostJavaMajor,
      versions: mcVersion.listVersionsEnriched(dir, hostJavaMajor),
    };
  });

  // ---- profiles (stored in profiles.json) ----
  ipcMain.handle('profiles:list',       () => profiles.load());
  ipcMain.handle('profiles:save',       (_e, profile) => profiles.upsert(profile));
  ipcMain.handle('profiles:patch',      (_e, id, partial) => profiles.patch(id, partial));
  ipcMain.handle('profiles:delete', (_e, id) => {
    const doc = profiles.remove(id);
    // If the deleted profile was the active one, switch to whichever profile
    // survived. Leaving selectedProfile pointing at a nonexistent id causes
    // launcher.js to silently drop all profile overrides (mods, RAM, isolation).
    const s = settings.load();
    if (s.selectedProfile === id) {
      const fallback = (doc.profiles[0] && doc.profiles[0].id) || '';
      settings.patch({ selectedProfile: fallback });
      const w = getWindow();
      if (w && !w.isDestroyed()) w.webContents.send('profiles:activeChanged', { id: fallback });
    }
    return doc;
  });
  ipcMain.handle('profiles:setActive',  (_e, id) => {
    const result = settings.patch({ selectedProfile: id });
    // Notify the renderer so the sidebar dot, name, and avatar refresh —
    // and so login-flow checks can re-run if the new profile is isolated
    // with an empty auth vault.
    const w = getWindow();
    if (w && !w.isDestroyed()) w.webContents.send('profiles:activeChanged', { id });
    return result;
  });
  ipcMain.handle('profiles:clone',      (_e, sourceId, opts) => profiles.clone(sourceId, opts || {}));
  ipcMain.handle('profiles:export',     async (_e, id) => {
    const payload = profiles.exportOne(id);
    if (!payload) return { ok: false, error: 'Profile not found' };
    const w = getWindow();
    const r = await dialog.showSaveDialog(w, {
      title: 'Export profile',
      defaultPath: `${id}.foxprofile.json`,
      filters: [{ name: 'Fox profile', extensions: ['json'] }],
    });
    if (r.canceled || !r.filePath) return { ok: false, cancelled: true };
    try {
      fs.writeFileSync(r.filePath, JSON.stringify(payload, null, 2));
      return { ok: true, path: r.filePath };
    } catch (err) {
      return { ok: false, error: err.message };
    }
  });
  ipcMain.handle('profiles:import',     async () => {
    const w = getWindow();
    const r = await dialog.showOpenDialog(w, {
      title: 'Import profile',
      properties: ['openFile'],
      filters: [{ name: 'Fox profile', extensions: ['json'] }],
    });
    if (r.canceled || !r.filePaths[0]) return { ok: false, cancelled: true };
    try {
      const payload = JSON.parse(fs.readFileSync(r.filePaths[0], 'utf8'));
      const result = profiles.importOne(payload);
      return { ok: true, profile: result };
    } catch (err) {
      return { ok: false, error: err.message };
    }
  });

  // ---- Profiles 2.0 — isolation, templates, account binding ----

  /** Flip a profile's isolation mode. Returns the updated profile or {error}. */
  ipcMain.handle('profiles:setIsolation', (_e, id, isolated) => {
    try {
      const updated = profiles.setIsolation(id, !!isolated);
      return { ok: true, profile: updated };
    } catch (err) {
      return { ok: false, error: err.message };
    }
  });

  /** List available profile templates (id + label + description). */
  ipcMain.handle('profiles:templates', () => profiles.templates());

  /** Apply a template's defaults to an existing profile. */
  ipcMain.handle('profiles:applyTemplate', (_e, id, templateId) => {
    try {
      const doc = profiles.applyTemplate(id, templateId);
      return { ok: true, doc };
    } catch (err) {
      return { ok: false, error: err.message };
    }
  });

  /**
   * Atomic switch + launch — sets the given profile active and immediately
   * launches the game. Used by the per-card "Play" button so the user can
   * jump from "browsing profiles" to "playing as X" in a single click.
   */
  ipcMain.handle('profiles:launch', async (_e, id) => {
    try {
      // 1. Set active so all subsequent settings/auth lookups resolve to it.
      //    NOTE: we deliberately do NOT emit profiles:activeChanged here.
      //    The renderer's onActiveProfileChanged handler reloads the page
      //    if the new profile's auth vault is empty, which would interrupt
      //    the in-flight launch. The renderer will handle UI refresh from
      //    the launch result + navigation instead.
      settings.patch({ selectedProfile: id });
      const _launchProfile = profiles.find(id);
      const _launchProfileName = (_launchProfile && _launchProfile.name) || '';
      // 2. Reuse the regular launch path. Errors propagate as a non-OK
      //    result so the renderer can surface them.
      const info = await launcher.launch(({ code, startedAt, gameDir }) => {
        const w2 = getWindow();
        if (w2 && !w2.isDestroyed()) w2.webContents.send('game:exited', { code });
        try { presence.setIdle(); } catch (_) {}
        if (typeof code === 'number' && code !== 0) {
          const report = crashReports.findNewSince(gameDir, startedAt);
          if (report && w2 && !w2.isDestroyed()) {
            w2.webContents.send('game:crash', {
              path: report.path, name: report.name,
              mtimeMs: report.mtimeMs, sizeBytes: report.sizeBytes,
              exitCode: code,
            });
          }
        }
      });
      try { presence.setPlaying(info.versionId, info.startedAt, _launchProfileName, info.gameDir); } catch (_) {}
      const w3 = getWindow();
      if (w3 && !w3.isDestroyed()) w3.webContents.send('game:started', { pid: info.pid });
      return { ok: true, ...info };
    } catch (err) {
      try { presence.setIdle(); } catch (_) {}
      return { ok: false, error: err.message };
    }
  });

  /** Open the per-profile instance directory in the OS file explorer.
   *  Useful for power users who want to nuke saves / drop a resource pack
   *  into a specific isolated profile without going through the launcher. */
  ipcMain.handle('profiles:openInstanceDir', (_e, id) => {
    const profile = profiles.find(id);
    if (!profile) return { ok: false, error: 'Profile not found' };
    if (!profile.isolated) {
      return { ok: false, error: 'Profile is not isolated — it shares the global game directory.' };
    }
    const dir = paths.ensureInstance(id);
    shell.openPath(dir);
    return { ok: true, path: dir };
  });

  // List the mods folder (paired .jar / .jar.disabled rows). Used by the
  // profile editor to show toggle rows for everything currently installed.
  ipcMain.handle('mods:list', () => {
    const s = settings.load();
    const dir = s.gameDir && s.gameDir.trim() ? s.gameDir : paths.defaultMinecraft();
    return profiles.listMods(dir);
  });

  // Pick one or more .jar files via the OS file picker and copy them into
  // <gameDir>/mods/. Used by the profile editor "Add mod…" button so the
  // user doesn't have to leave the launcher to drop a jar in.
  ipcMain.handle('mods:add', async () => {
    const w = getWindow();
    const r = await dialog.showOpenDialog(w, {
      title: 'Add mods',
      properties: ['openFile', 'multiSelections'],
      filters: [{ name: 'Mod jar', extensions: ['jar'] }],
    });
    if (r.canceled || !r.filePaths.length) return { ok: false, cancelled: true };

    const s = settings.load();
    const gameDir = s.gameDir && s.gameDir.trim() ? s.gameDir : paths.defaultMinecraft();
    const modsDir = path.join(gameDir, 'mods');
    try { fs.mkdirSync(modsDir, { recursive: true }); }
    catch (err) { return { ok: false, error: `Couldn't create mods directory: ${err.message}` }; }

    const added = [];
    const skipped = [];
    for (const src of r.filePaths) {
      const baseName = path.basename(src);
      const target = path.join(modsDir, baseName);
      if (fs.existsSync(target) || fs.existsSync(target + '.disabled')) {
        skipped.push({ name: baseName, reason: 'already-present' });
        continue;
      }
      try {
        fs.copyFileSync(src, target);
        added.push(baseName);
      } catch (err) {
        skipped.push({ name: baseName, reason: err.message });
      }
    }
    return { ok: true, added, skipped };
  });

  // Reveal <gameDir>/mods in the OS file explorer.
  ipcMain.handle('mods:openFolder', () => {
    const s = settings.load();
    const gameDir = s.gameDir && s.gameDir.trim() ? s.gameDir : paths.defaultMinecraft();
    const modsDir = path.join(gameDir, 'mods');
    try { fs.mkdirSync(modsDir, { recursive: true }); } catch (_) {}
    return shell.openPath(modsDir);
  });

  // Permanently delete a mod jar (handles both .jar and .jar.disabled forms).
  ipcMain.handle('mods:delete', (_e, baseName) => {
    if (typeof baseName !== 'string' || baseName.includes('/') || baseName.includes('\\')) {
      return { ok: false, error: 'Invalid mod name' };
    }
    const s = settings.load();
    const gameDir = s.gameDir && s.gameDir.trim() ? s.gameDir : paths.defaultMinecraft();
    const modsDir = path.resolve(path.join(gameDir, 'mods'));
    const candidates = [
      path.join(modsDir, baseName),
      path.join(modsDir, baseName + '.disabled'),
    ];
    let removed = false;
    for (const p of candidates) {
      const resolved = path.resolve(p);
      if (!resolved.startsWith(modsDir + path.sep)) continue; // path-traversal guard
      if (fs.existsSync(resolved)) {
        try { fs.unlinkSync(resolved); removed = true; }
        catch (err) { return { ok: false, error: err.message }; }
      }
    }
    return { ok: removed, error: removed ? null : 'Not found' };
  });

  // ---- resource packs & shader packs ----
  //
  // Both types share the same four operations (list / add / delete /
  // open-folder).  The `type` parameter is either 'resourcepacks' or
  // 'shaders' and maps to the correct sub-directory inside gameDir.

  function resolveGameDir() {
    const s = settings.load();
    return (s.gameDir && s.gameDir.trim()) ? s.gameDir : paths.defaultMinecraft();
  }

  ipcMain.handle('resourcepacks:list', () => {
    return resourcepacks.listPacks(resolveGameDir(), 'resourcepacks');
  });

  ipcMain.handle('resourcepacks:add', async () => {
    const w = getWindow();
    const r = await dialog.showOpenDialog(w, {
      title: 'Add resource packs',
      properties: ['openFile', 'multiSelections'],
      filters: [{ name: 'Resource pack', extensions: ['zip'] }],
    });
    if (r.canceled || !r.filePaths.length) return { ok: false, cancelled: true };
    return resourcepacks.addPacks(resolveGameDir(), 'resourcepacks', r.filePaths);
  });

  ipcMain.handle('resourcepacks:delete', (_e, baseName) => {
    return resourcepacks.deletePack(resolveGameDir(), 'resourcepacks', baseName);
  });

  ipcMain.handle('resourcepacks:openFolder', () => {
    const dir = resourcepacks.packDir(resolveGameDir(), 'resourcepacks');
    try { fs.mkdirSync(dir, { recursive: true }); } catch (_) {}
    return shell.openPath(dir);
  });

  ipcMain.handle('shaders:list', () => {
    return resourcepacks.listPacks(resolveGameDir(), 'shaders');
  });

  ipcMain.handle('shaders:add', async () => {
    const w = getWindow();
    const r = await dialog.showOpenDialog(w, {
      title: 'Add shader packs',
      properties: ['openFile', 'multiSelections'],
      // Shader packs are usually .zip; some Iris-compatible ones are .jar.
      filters: [{ name: 'Shader pack', extensions: ['zip', 'jar'] }],
    });
    if (r.canceled || !r.filePaths.length) return { ok: false, cancelled: true };
    return resourcepacks.addPacks(resolveGameDir(), 'shaders', r.filePaths);
  });

  ipcMain.handle('shaders:delete', (_e, baseName) => {
    return resourcepacks.deletePack(resolveGameDir(), 'shaders', baseName);
  });

  ipcMain.handle('shaders:openFolder', () => {
    const dir = resourcepacks.packDir(resolveGameDir(), 'shaders');
    try { fs.mkdirSync(dir, { recursive: true }); } catch (_) {}
    return shell.openPath(dir);
  });

  // ---- launch ----
  ipcMain.handle('game:launch', async () => {
    try {
      const sNow = settings.load();
      const _activeProfile = profiles.find(sNow.selectedProfile);
      const _activeProfileName = (_activeProfile && _activeProfile.name) || '';
      try { presence.setLaunching(sNow.selectedVersion || ''); } catch (_) {}

      const info = await launcher.launch(({ code, startedAt, gameDir }) => {
        const w = getWindow();
        if (w && !w.isDestroyed()) w.webContents.send('game:exited', { code });
        // Game ended → presence flips back to "in launcher".
        try { presence.setIdle(); } catch (_) {}
        // Crash detection: only signal when the exit was non-zero AND a crash
        // report file mtime≥startedAt exists. Edge-only — won't false-positive
        // on a graceful Quit-To-Title that hits a 0 exit.
        if (typeof code === 'number' && code !== 0) {
          const report = crashReports.findNewSince(gameDir, startedAt);
          if (report && w && !w.isDestroyed()) {
            w.webContents.send('game:crash', {
              path: report.path,
              name: report.name,
              mtimeMs: report.mtimeMs,
              sizeBytes: report.sizeBytes,
              exitCode: code,
            });
          }
        }
      });
      // Spawn succeeded → flip to "playing" with accurate elapsed-since.
      try { presence.setPlaying(info.versionId, info.startedAt, _activeProfileName, info.gameDir); } catch (_) {}
      // Notify the renderer immediately so the sidebar running-dot appears
      // without waiting for a Home screen re-render.
      { const w = getWindow(); if (w && !w.isDestroyed()) w.webContents.send('game:started', { pid: info.pid }); }
      // Honor keepLauncherOpen preference: if false, close the window shortly
      // after the game has spawned successfully (give the user a beat to see
      // the success state).
      const s = settings.load();
      if (!s.keepLauncherOpen) {
        setTimeout(() => {
          const w = getWindow();
          if (w && !w.isDestroyed()) w.close();
        }, 1500);
      }
      return { ok: true, ...info };
    } catch (err) {
      // Pre-flight failed (no auth, no java, version missing, etc.) — flip
      // presence back to idle so it doesn't stick on "Launching Minecraft".
      try { presence.setIdle(); } catch (_) {}
      return { ok: false, error: err.message };
    }
  });
  ipcMain.handle('game:stop', () => ({ ok: launcher.stop() }));
  ipcMain.handle('game:running', () => launcher.isRunning());

  // ---- logs ----
  ipcMain.handle('logs:all', () => logs.all());
  ipcMain.handle('logs:clear', () => { logs.clear(); return true; });
  // Push log events live
  logs.subscribe((kind, text) => {
    const w = getWindow();
    if (w) w.webContents.send('logs:append', { kind, text, ts: Date.now() });
  });

  // ---- updater ----
  ipcMain.handle('updater:check', async () => {
    try {
      const r = await updater.checkAndDownload({
        onProgress: (msg) => {
          const w = getWindow();
          if (w) w.webContents.send('updater:progress', { message: msg });
        },
      });
      return { ok: true, result: r };
    } catch (err) {
      return { ok: false, error: err.message };
    }
  });
  ipcMain.handle('updater:summary', () => updater.summary());

  // ---- screenshots ----

  /** Resolve the screenshots directory for a given profile (or the active one). */
  /** True when `p` resolves to a location inside a known game/launcher dir.
   *  Used by shell-open IPC handlers to refuse arbitrary paths from the renderer. */
  function isInsideKnownGameLocation(p) {
    let target;
    try { target = path.resolve(p); }
    catch (_) { return false; }
    const s = settings.load();
    const allowed = [
      paths.root,
      paths.defaultMinecraft(),
      (s.gameDir && s.gameDir.trim()) || paths.defaultMinecraft(),
    ];
    try {
      const doc = profiles.load();
      for (const pr of (doc.profiles || [])) {
        if (pr.isolated) allowed.push(paths.instanceDir(pr.id));
        if (pr.gameDirOverride && pr.gameDirOverride.trim()) allowed.push(pr.gameDirOverride.trim());
      }
    } catch (_) {}
    return allowed.some(a => {
      try {
        const root = path.resolve(a);
        return target === root || target.startsWith(root + path.sep);
      } catch (_) { return false; }
    });
  }

  function resolveScreenshotDir(profileId) {
    const s = settings.load();
    const id = profileId || s.selectedProfile;
    const profile = id ? profiles.find(id) : null;
    let gameDir;
    if (profile && profile.isolated) {
      gameDir = paths.instanceDir(id);
    } else if (profile && profile.gameDirOverride && profile.gameDirOverride.trim()) {
      gameDir = profile.gameDirOverride.trim();
    } else {
      gameDir = (s.gameDir && s.gameDir.trim()) || paths.defaultMinecraft();
    }
    return path.join(gameDir, 'screenshots');
  }

  /** List screenshots for a profile. Returns sorted newest-first. */
  ipcMain.handle('screenshots:list', (_e, profileId) => {
    const { pathToFileURL } = require('url');
    const ssDir = resolveScreenshotDir(profileId || null);
    let entries;
    try {
      entries = fs.readdirSync(ssDir);
    } catch (_) {
      return { dir: ssDir, screenshots: [], exists: false };
    }
    const screenshots = entries
      .filter(f => /\.(png|jpg|jpeg|gif|bmp|webp)$/i.test(f))
      .map(f => {
        const fullPath = path.join(ssDir, f);
        try {
          const stat = fs.statSync(fullPath);
          return {
            name: f,
            path: fullPath,
            fileUrl: pathToFileURL(fullPath).href,
            size: stat.size,
            mtimeMs: stat.mtimeMs,
          };
        } catch (_) { return null; }
      })
      .filter(Boolean)
      .sort((a, b) => b.mtimeMs - a.mtimeMs);
    return { dir: ssDir, screenshots, exists: true };
  });

  /** Delete a screenshot. Validates the path is inside a known screenshots dir. */
  ipcMain.handle('screenshots:delete', (_e, filePath) => {
    if (typeof filePath !== 'string') return { ok: false, error: 'Invalid path' };
    if (!/\.(png|jpg|jpeg|gif|bmp|webp)$/i.test(path.basename(filePath))) {
      return { ok: false, error: 'Not an image file' };
    }
    const resolved = path.resolve(filePath);
    // Build the set of allowed screenshot directories from every known profile.
    const s = settings.load();
    const globalGameDir = (s.gameDir && s.gameDir.trim()) || paths.defaultMinecraft();
    const allowedDirs = [ path.resolve(path.join(globalGameDir, 'screenshots')) ];
    try {
      const doc = profiles.load();
      for (const p of doc.profiles) {
        if (p.isolated) allowedDirs.push(path.resolve(path.join(paths.instanceDir(p.id), 'screenshots')));
        if (p.gameDirOverride && p.gameDirOverride.trim()) {
          allowedDirs.push(path.resolve(path.join(p.gameDirOverride.trim(), 'screenshots')));
        }
      }
    } catch (_) {}
    const allowed = allowedDirs.some(d => resolved.startsWith(d + path.sep));
    if (!allowed) return { ok: false, error: 'Path outside allowed directories' };
    try {
      fs.unlinkSync(resolved);
      return { ok: true };
    } catch (err) {
      return { ok: false, error: err.message };
    }
  });

  /** Open the OS file manager with the screenshot file selected/highlighted.
   *  Restricted to paths inside known game directories (defense-in-depth
   *  against a compromised renderer asking to reveal arbitrary OS paths). */
  ipcMain.handle('screenshots:reveal', (_e, filePath) => {
    if (typeof filePath !== 'string' || !isInsideKnownGameLocation(filePath)) return;
    shell.showItemInFolder(filePath);
  });

  /** Open the screenshots folder for a given profile in the OS file manager. */
  ipcMain.handle('screenshots:openFolder', (_e, profileId) => {
    const ssDir = resolveScreenshotDir(profileId || null);
    try { fs.mkdirSync(ssDir, { recursive: true }); } catch (_) {}
    shell.openPath(ssDir);
  });

  // ---- shell helpers ----
  ipcMain.handle('shell:openExternal', (_e, url) => {
    // Only allow http(s) URLs — block file://, javascript:, and other schemes
    // that could be used to execute code or expose local files.
    if (typeof url !== 'string' || !/^https?:\/\//i.test(url)) return;
    shell.openExternal(url);
  });
  /** Open a directory in the OS file manager.
   *  Only paths inside known game/launcher locations are accepted — a renderer
   *  XSS cannot use this to open C:\Windows\System32 etc. */
  ipcMain.handle('shell:openPath', (_e, p) => {
    if (typeof p !== 'string' || !isInsideKnownGameLocation(p)) return;
    return shell.openPath(p);
  });

  // ---- system probes (RAM ceiling for the Settings sliders, etc.) ----
  ipcMain.handle('system:ramInfo', () => {
    try { return system.ramInfo(); }
    catch (err) { return { totalMb: 0, freeMb: 0, recommendedMaxMb: 0, error: err.message }; }
  });

  // ---- crash reports ----
  ipcMain.handle('crash:findNewSince', (_e, sinceMs) => {
    try {
      const s = settings.load();
      const dir = s.gameDir && s.gameDir.trim() ? s.gameDir : paths.defaultMinecraft();
      return crashReports.findNewSince(dir, Number(sinceMs) || 0);
    } catch (_) { return null; }
  });
  ipcMain.handle('crash:read', (_e, fullPath) => {
    const s = settings.load();
    const dir = s.gameDir && s.gameDir.trim() ? s.gameDir : paths.defaultMinecraft();
    return crashReports.readReport(dir, fullPath);
  });
  ipcMain.handle('crash:openFolder', () => {
    const s = settings.load();
    const dir = s.gameDir && s.gameDir.trim() ? s.gameDir : paths.defaultMinecraft();
    return shell.openPath(path.join(dir, 'crash-reports'));
  });

  // ---- last launch (for "Last played" UI in Profiles / Play) ----
  ipcMain.handle('launch:lastInfo', () => launcher.lastLaunchInfo());

  // Pinned-version readiness for the Home screen banner.
  ipcMain.handle('launch:readiness', () => {
    try { return launcher.clientReadiness(); }
    catch (err) { return { error: err.message }; }
  });

  // Addon catalog — used by the profile editor to render the "Optional
  // addons" checklist. Renderer never needs to know the file format,
  // just the list of (id, displayName, risk) tuples.
  ipcMain.handle('addons:catalog', () => addons.catalog());

  // ---- Recommended mods (Sodium / Lithium / etc., fetched from Modrinth) ----

  ipcMain.handle('recommended:list', () => recommendedMods.manifest());

  ipcMain.handle('recommended:install', async (_e, opts) => {
    const s = settings.load();
    const dir = s.gameDir && s.gameDir.trim() ? s.gameDir : paths.defaultMinecraft();
    if (!fs.existsSync(dir)) return { ok: false, error: 'Game directory does not exist.' };
    const mc = launcher.TARGET_MC_VERSION;
    try {
      const results = await recommendedMods.installAll(dir, mc, {
        essentialOnly: !!(opts && opts.essentialOnly),
        onProgress: (msg, pct) => {
          const w = getWindow();
          if (w && !w.isDestroyed()) {
            w.webContents.send('recommended:progress', { message: msg, percent: pct });
          }
        },
      });
      return { ok: true, results };
    } catch (err) {
      return { ok: false, error: err.message };
    }
  });

  // Single-mod install — used by the per-row "Install" button on the
  // recommended-mods list when the user wants Iris (non-essential) but not
  // the rest. Returns the same per-mod status shape installAll uses.
  ipcMain.handle('recommended:installOne', async (_e, slug) => {
    const s = settings.load();
    const dir = s.gameDir && s.gameDir.trim() ? s.gameDir : paths.defaultMinecraft();
    if (!fs.existsSync(dir)) return { slug, status: 'error', error: 'Game directory does not exist.' };
    const mc = launcher.TARGET_MC_VERSION;
    try {
      return await recommendedMods.installOne(slug, dir, mc);
    } catch (err) {
      return { slug, status: 'error', error: err.message };
    }
  });

  // ---- Modrinth marketplace ----
  //
  // Each handler resolves the active-OR-specified profile's game directory so
  // installs land in the right mods folder for isolated/overridden profiles.

  /** Resolve game directory for a profile id, or current active when null. */
  function gameDirForProfile(profileId) {
    const s = settings.load();
    const id = profileId || s.selectedProfile;
    const profile = id ? profiles.find(id) : null;
    if (profile && profile.isolated) return paths.instanceDir(id);
    if (profile && profile.gameDirOverride && profile.gameDirOverride.trim()) return profile.gameDirOverride.trim();
    return (s.gameDir && s.gameDir.trim()) || paths.defaultMinecraft();
  }

  ipcMain.handle('modrinth:search', async (_e, payload) => {
    const { query, mcVersion, sort, limit, offset } = payload || {};
    return modrinthBrowser.search(query || '', mcVersion || launcher.TARGET_MC_VERSION, {
      sort, limit, offset,
    });
  });

  ipcMain.handle('modrinth:project', async (_e, payload) => {
    const { slug, mcVersion } = payload || {};
    if (!slug) return { error: 'slug is required' };
    return modrinthBrowser.project(slug, mcVersion || launcher.TARGET_MC_VERSION);
  });

  ipcMain.handle('modrinth:install', async (_e, payload) => {
    const { slug, profileId, installDependencies } = payload || {};
    if (!slug) return { slug: '', status: 'error', error: 'slug is required' };
    const dir = gameDirForProfile(profileId);
    if (!fs.existsSync(dir)) {
      try { fs.mkdirSync(dir, { recursive: true }); } catch (_) {}
    }
    const mc = launcher.TARGET_MC_VERSION;
    try {
      return await modrinthBrowser.install(slug, dir, mc, {
        installDependencies: installDependencies !== false,
      });
    } catch (err) {
      return { slug, status: 'error', error: err.message || String(err) };
    }
  });

  // ---- Mod update detection ----
  //
  // checkForUpdates hashes every installed jar and batch-queries Modrinth for
  // newer versions. applyUpdate downloads + hash-verifies + atomically writes
  // the new jar, deleting the old one. Both are per-profile.

  ipcMain.handle('modUpdates:check', async (_e, payload) => {
    const profileId = payload && payload.profileId;
    const dir = gameDirForProfile(profileId);
    if (!fs.existsSync(dir)) {
      return { scanned: 0, resolved: 0, updates: [], error: 'Game directory does not exist.' };
    }
    return modUpdates.checkForUpdates(dir, launcher.TARGET_MC_VERSION);
  });

  ipcMain.handle('modUpdates:apply', async (_e, payload) => {
    const profileId = payload && payload.profileId;
    const update    = payload && payload.update;
    if (!update || !update.filename || !update.latest) {
      return { ok: false, error: 'malformed update payload' };
    }
    const dir = gameDirForProfile(profileId);
    if (!fs.existsSync(dir)) return { ok: false, error: 'Game directory does not exist.' };
    return modUpdates.applyUpdate(dir, update);
  });

  // ---- Modpack (.mrpack) import ----
  //
  // Picks a .mrpack file, parses its modrinth.index.json, creates a fresh
  // isolated profile named after the pack, downloads every required mod
  // (SHA-512 verified), extracts overrides into the instance dir.
  ipcMain.handle('modpack:import', async (_e) => {
    const { dialog } = require('electron');
    const win = getWindow();
    const picked = await dialog.showOpenDialog(win || undefined, {
      title: 'Pick a .mrpack file',
      filters: [{ name: 'Modrinth modpack', extensions: ['mrpack'] }],
      properties: ['openFile'],
    });
    if (picked.canceled || !picked.filePaths.length) return { ok: false, error: 'Cancelled' };
    const mrpackPath = picked.filePaths[0];

    // Notify the renderer of per-file progress so the UI can show a live log.
    const send = (msg) => {
      const w = getWindow();
      if (w && !w.isDestroyed()) w.webContents.send('modpack:progress', { message: msg });
    };

    return modpackImport.importMrpack(mrpackPath, {
      mcVersion: launcher.TARGET_MC_VERSION,
      instanceDir: (id) => paths.instanceDir(id),
      mkProfile: (name) => {
        // Sanitize name → unique id. Re-uses the existing profile-create
        // path so the new modpack profile shows up in the Profiles tab.
        const id = name.toLowerCase().replace(/[^a-z0-9]+/g, '-').slice(0, 40)
                + '-' + Date.now().toString(36);
        try {
          profiles.save({
            id, name,
            notes: 'Imported from .mrpack on ' + new Date().toISOString().slice(0, 10),
            isolated: true,
            keepKitsuneEnabled: true,
          });
          return id;
        } catch (err) {
          send('  → profile create failed: ' + err.message);
          return null;
        }
      },
      onProgress: send,
    });
  });

  // ---- Modpack (.mrpack) export ----
  //
  // Bundles a profile's mods + config (and optionally resource/shader packs)
  // into a shareable .mrpack. Everything ships inside overrides/, so the
  // result round-trips with our own importer and any Modrinth-format client.
  ipcMain.handle('modpack:export', async (_e, payload) => {
    const { dialog } = require('electron');
    const win = getWindow();
    const opts = payload || {};

    const profileId = opts.profileId || settings.load().selectedProfile;
    const profile   = profileId ? profiles.find(profileId) : null;
    const gameDir   = gameDirForProfile(profileId);
    const packName  = (opts.name && opts.name.trim())
      || (profile && profile.name)
      || 'Fox Modpack';

    const safeFile = packName.replace(/[^a-z0-9._-]+/gi, '_').replace(/^_+|_+$/g, '') || 'modpack';
    const picked = await dialog.showSaveDialog(win || undefined, {
      title: 'Export modpack as .mrpack',
      defaultPath: `${safeFile}.mrpack`,
      filters: [{ name: 'Modrinth modpack', extensions: ['mrpack'] }],
    });
    if (picked.canceled || !picked.filePath) return { ok: false, error: 'Cancelled' };

    const send = (msg) => {
      const w = getWindow();
      if (w && !w.isDestroyed()) w.webContents.send('modpack:progress', { message: msg });
    };

    return modpackExport.exportMrpack({
      gameDir,
      destPath: picked.filePath,
      name: packName,
      versionId: (opts.versionId && String(opts.versionId).trim()) || '1.0.0',
      summary: opts.summary,
      mcVersion: launcher.TARGET_MC_VERSION,
      includePacks: !!opts.includePacks,
      onProgress: send,
    });
  });

  // ---- news feed (from a configurable URL; cached + offline-tolerant) ----
  ipcMain.handle('news:fetch', async () => {
    const s = settings.load();
    return news.getNews(s.newsUrl);
  });

  // ---- Discord RPC live status (for the Settings UI) ----
  ipcMain.handle('discord:status', () => {
    try { return presence.status(); }
    catch (_) { return { state: 'disabled' }; }
  });

  // ---- Settings: factory reset (renames the existing settings.json so the
  //      user can recover if they fat-fingered the button). ----
  ipcMain.handle('settings:reset', () => {
    try {
      if (fs.existsSync(paths.settings)) {
        const backup = paths.settings + '.bak.' + Date.now();
        fs.renameSync(paths.settings, backup);
      }
      // Force a fresh read with all defaults.
      const fresh = settings.load();
      try { javaDetect.invalidateCache(); } catch (_) {}
      try { presence.refresh(); } catch (_) {}
      return { ok: true, settings: fresh };
    } catch (err) {
      return { ok: false, error: err.message };
    }
  });

  // ---- Logs: save the current ring buffer to a chosen file. ----
  ipcMain.handle('logs:save', async () => {
    const w = getWindow();
    const r = await dialog.showSaveDialog(w, {
      title: 'Save current log',
      defaultPath: `fox-launcher-${new Date().toISOString().replace(/[:.]/g, '-')}.log`,
      filters: [{ name: 'Log file', extensions: ['log', 'txt'] }],
    });
    if (r.canceled || !r.filePath) return { ok: false, cancelled: true };
    try {
      const lines = logs.all().map(l => `[${new Date(l.ts).toISOString()}] [${l.kind}] ${l.text}`).join('\n');
      fs.writeFileSync(r.filePath, lines);
      return { ok: true, path: r.filePath };
    } catch (err) {
      return { ok: false, error: err.message };
    }
  });

  // ---- About info for the Settings UI ----
  ipcMain.handle('app:about', () => {
    const pkgPath = path.join(__dirname, '..', '..', 'package.json');
    let version = '0.0.0';
    try { version = JSON.parse(fs.readFileSync(pkgPath, 'utf8')).version || version; } catch (_) {}
    // Also surface the mod version from gradle.properties so the About card can
    // show whether the launcher and mod jar are on the same version.
    let modVersion = version; // default: assume they match
    try {
      const propsPath = path.resolve(__dirname, '..', '..', '..', 'gradle.properties');
      const props = fs.readFileSync(propsPath, 'utf8');
      const m = props.match(/^\s*mod_version\s*=\s*(.+)$/m);
      if (m) modVersion = m[1].trim();
    } catch (_) {}
    return {
      version,
      modVersion,
      electron: process.versions.electron,
      node:     process.versions.node,
      chrome:   process.versions.chrome,
      platform: process.platform,
      arch:     process.arch,
      paths: {
        root:    paths.root,
        logs:    paths.logs,
        cache:   paths.cache,
        versions: paths.versions,
      },
    };
  });

  // ---- Minecraft skin avatar (Crafatar) ----
  // Fetched in the main process so the renderer's strict CSP (img-src 'self' data:)
  // is satisfied — we return a base64 data URI the renderer can use directly.
  ipcMain.handle('avatar:fetch', async (_e, uuid) => {
    if (!uuid || typeof uuid !== 'string') return null;
    // Sanitise — Minecraft UUIDs are 32 hex chars optionally with hyphens.
    if (!/^[0-9a-f-]{32,36}$/i.test(uuid)) return null;
    const url = `https://crafatar.com/avatars/${uuid}?size=32&overlay=true`;
    // Crafatar requires a User-Agent header; requests without one are blocked.
    const ua = 'FoxLauncher/1.0 (Electron; Minecraft launcher; crafatar contact: https://crafatar.com)';
    try {
      return await _fetchBase64(url, 3, { 'User-Agent': ua });
    } catch (_) {
      return null; // renderer falls back to letter-initial
    }
  });

  // ---- skin manager ----
  //
  // Both calls resolve auth internally (main-process only) so the access token
  // is never exposed to the renderer.

  /** Fetch current skin URL + model variant for the signed-in account. */
  ipcMain.handle('skins:fetch', async () => {
    try {
      const record = await auth.getValid();
      if (!record || record.guest || !record.uuid) return { ok: false, error: 'Not signed in' };
      const info = await skins.fetchSkinInfo(record.uuid, record.accessToken);
      return { ok: true, ...info };
    } catch (err) {
      return { ok: false, error: err.message };
    }
  });

  /** Open file picker for a PNG, then upload + activate the chosen skin. */
  ipcMain.handle('skins:upload', async (_e, variant) => {
    const w = getWindow();
    const r = await dialog.showOpenDialog(w, {
      title: 'Choose skin PNG',
      properties: ['openFile'],
      filters: [{ name: 'PNG image', extensions: ['png'] }],
    });
    if (r.canceled || !r.filePaths[0]) return { ok: false, cancelled: true };
    try {
      const record = await auth.getValid();
      if (!record || record.guest || !record.accessToken) {
        return { ok: false, error: 'Not signed in with a Microsoft account' };
      }
      const safeVariant = (variant === 'slim') ? 'slim' : 'classic';
      return await skins.uploadSkin(record.accessToken, r.filePaths[0], safeVariant);
    } catch (err) {
      return { ok: false, error: err.message };
    }
  });

  // Wire launch-stage events. launcher.js accepts a callback so it can push
  // structured progress messages (auth, Fabric install, jar copy, spawn) to
  // the renderer without introducing a circular dependency.
  launcher.setStageEmitter((message) => {
    const w = getWindow();
    if (w && !w.isDestroyed()) w.webContents.send('launch:stage', { message });
  });
}

/**
 * Fetch a URL via Node's built-in https and return the body as a base64
 * data URI string. Follows up to `maxRedirects` HTTP 3xx redirects.
 * Rejects on non-200 status or network error.
 */
function _fetchBase64(url, maxRedirects = 3, reqHeaders = {}) {
  return new Promise((resolve, reject) => {
    const https = require('https');
    const http  = require('http');

    const req = (url.startsWith('https') ? https : http).get(url, {
      timeout: 5000,
      headers: reqHeaders,
    }, (res) => {
      // Follow common redirects (301/302/307/308), preserving headers.
      if ((res.statusCode === 301 || res.statusCode === 302 ||
           res.statusCode === 307 || res.statusCode === 308) &&
          res.headers.location && maxRedirects > 0) {
        return _fetchBase64(res.headers.location, maxRedirects - 1, reqHeaders).then(resolve).catch(reject);
      }
      if (res.statusCode !== 200) return reject(new Error(`HTTP ${res.statusCode}`));
      const ct = (res.headers['content-type'] || 'image/png').split(';')[0].trim();
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve(`data:${ct};base64,${Buffer.concat(chunks).toString('base64')}`));
      res.on('error', reject);
    });
    req.on('error', reject);
    req.on('timeout', () => { req.destroy(); reject(new Error('Timeout')); });
  });
}

module.exports = { register, onSettingsChanged };
