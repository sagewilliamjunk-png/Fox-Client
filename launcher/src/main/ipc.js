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
const profiles = require('./profiles');
const launcherSelfUpdate = require('./launcherSelfUpdate');
const mojangStatus = require('./mojangStatus');

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
    return {
      signedIn: true,
      username: cached.username,
      uuid: cached.uuid,
      expiresAt: cached.expiresAt,
      guest: !!cached.guest,
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
  ipcMain.handle('profiles:delete',     (_e, id) => profiles.remove(id));
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
      try { presence.setPlaying(info.versionId, info.startedAt); } catch (_) {}
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

  // ---- launch ----
  ipcMain.handle('game:launch', async () => {
    try {
      const sNow = settings.load();
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
      try { presence.setPlaying(info.versionId, info.startedAt); } catch (_) {}
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

  // ---- shell helpers ----
  ipcMain.handle('shell:openExternal', (_e, url) => shell.openExternal(url));
  ipcMain.handle('shell:openPath', (_e, p) => shell.openPath(p));

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
    return {
      version,
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
    try {
      return await _fetchBase64(url, 3);
    } catch (_) {
      return null; // renderer falls back to letter-initial
    }
  });

  // Wire launch-stage events. launcher.js accepts a callback so it can push
  // structured progress messages (auth, Fabric install, jar copy, spawn) to
  // the renderer without introducing a circular dependency.
  launcher.setStageEmitter((message) => {
    const w = getWindow();
    if (w && !w.isDestroyed()) w.webContents.send('launch:stage', { message });
  });

  // ---- Launcher self-update ----
  launcherSelfUpdate.setEmitter((channel, payload) => {
    const w = getWindow();
    if (w && !w.isDestroyed()) w.webContents.send(channel, payload);
  });
  ipcMain.handle('launcher:updateState', () => launcherSelfUpdate.currentState());
  ipcMain.handle('launcher:check',       () => { launcherSelfUpdate.check(); return true; });
  ipcMain.handle('launcher:install',     () => { launcherSelfUpdate.install(); return true; });

  // ---- Mojang service status ----
  ipcMain.handle('mojang:status', (_e, force) => mojangStatus.getStatus(!!force));
}

/**
 * Fetch a URL via Node's built-in https and return the body as a base64
 * data URI string. Follows up to `maxRedirects` HTTP 3xx redirects.
 * Rejects on non-200 status or network error.
 */
function _fetchBase64(url, maxRedirects = 3) {
  return new Promise((resolve, reject) => {
    const https = require('https');
    const http  = require('http');

    const req = (url.startsWith('https') ? https : http).get(url, { timeout: 5000 }, (res) => {
      if ((res.statusCode === 301 || res.statusCode === 302 || res.statusCode === 307) &&
          res.headers.location && maxRedirects > 0) {
        return _fetchBase64(res.headers.location, maxRedirects - 1).then(resolve).catch(reject);
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
