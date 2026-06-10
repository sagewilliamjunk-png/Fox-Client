// Context bridge — the ONLY surface the renderer can use to talk to Node.
// Keep this list small and intentional. Anything here is exposed to web code.

const { contextBridge, ipcRenderer } = require('electron');

const invoke = (channel, ...args) => ipcRenderer.invoke(channel, ...args);
const on = (channel, handler) => {
  const wrapped = (_e, payload) => handler(payload);
  ipcRenderer.on(channel, wrapped);
  return () => ipcRenderer.removeListener(channel, wrapped);
};

contextBridge.exposeInMainWorld('fox', {
  // Settings
  getSettings:   () => invoke('settings:get'),
  patchSettings: (p) => invoke('settings:patch', p),

  // Auth
  authStatus:       () => invoke('auth:status'),
  login:            () => invoke('auth:login'),
  loginGuest:       (username) => invoke('auth:guest', username),
  logout:           () => invoke('auth:logout'),
  onAuthBrowserOpened: (fn) => on('auth:browser-opened', fn),
  onAuthDone:          (fn) => on('auth:done',            fn),
  onAuthError:         (fn) => on('auth:error',           fn),
  onSessionExpired:    (fn) => on('auth:session-expired', fn),

  // Multi-account
  listAccounts:      ()    => invoke('auth:listAccounts'),
  setActiveAccount:  (id)  => invoke('auth:setActiveAccount', id),
  removeAccount:     (id)  => invoke('auth:removeAccount', id),
  onAccountChanged:  (fn)  => on('auth:accountChanged', fn),

  // Java
  detectJava:    () => invoke('java:detect'),
  detectAllJava: () => invoke('java:detectAll'),
  browseJava:    () => invoke('java:browse'),
  installJava:   () => invoke('java:install'),
  onJavaInstallProgress: (fn) => on('java:install:progress', fn),

  // Minecraft installer (vanilla bootstrap — no official launcher required)
  mcIsInstalled:    (versionId) => invoke('mc:isInstalled', versionId),
  mcListAvailable:  (snapshots) => invoke('mc:listAvailable', snapshots || false),
  mcInstall:        (versionId) => invoke('mc:install', versionId),
  onMcInstallProgress: (fn) => on('mc:install:progress', fn),

  // Game dir
  browseGameDir:  () => invoke('gamedir:browse'),
  defaultGameDir: () => invoke('gamedir:default'),

  // Versions
  listVersions:        () => invoke('versions:list'),
  listVersionsEnriched: () => invoke('versions:listEnriched'),

  // Profiles
  listProfiles:     () => invoke('profiles:list'),
  saveProfile:      (p) => invoke('profiles:save', p),
  patchProfile:     (id, partial) => invoke('profiles:patch', id, partial),
  deleteProfile:    (id) => invoke('profiles:delete', id),
  setActiveProfile: (id) => invoke('profiles:setActive', id),
  cloneProfile:     (sourceId, opts) => invoke('profiles:clone', sourceId, opts || {}),
  exportProfile:    (id) => invoke('profiles:export', id),
  importProfile:    () => invoke('profiles:import'),
  // Profiles 2.0
  profileTemplates:    ()                  => invoke('profiles:templates'),
  setProfileIsolation: (id, isolated)       => invoke('profiles:setIsolation', id, isolated),
  applyProfileTemplate:(id, templateId)     => invoke('profiles:applyTemplate', id, templateId),
  launchProfile:       (id)                 => invoke('profiles:launch', id),
  openInstanceDir:     (id)                 => invoke('profiles:openInstanceDir', id),
  onActiveProfileChanged: (fn)              => on('profiles:activeChanged', fn),

  // Addon catalog (gray-zone feature flags)
  addonCatalog: () => invoke('addons:catalog'),

  // Recommended mods (Modrinth fetcher)
  recommendedList:    () => invoke('recommended:list'),
  recommendedInstall: (opts) => invoke('recommended:install', opts || {}),
  recommendedInstallOne: (slug) => invoke('recommended:installOne', slug),
  recommendedReinstallAll: () => invoke('recommended:reinstallAll'),
  onRecommendedProgress: (fn) => on('recommended:progress', fn),
  onRecommendedAutoResult: (fn) => on('recommended:autoResult', fn),

  // Modrinth marketplace — per-profile search + install
  modrinthSearch:  (payload) => invoke('modrinth:search',  payload || {}),
  modrinthProject: (payload) => invoke('modrinth:project', payload || {}),
  modrinthInstall: (payload) => invoke('modrinth:install', payload || {}),

  // Mod update detection
  modUpdatesCheck: (payload) => invoke('modUpdates:check', payload || {}),
  modUpdatesApply: (payload) => invoke('modUpdates:apply', payload || {}),
  onModUpdatesAutoResult: (fn) => on('modUpdates:autoResult', fn),

  // Modpack (.mrpack) import — opens a file picker then runs the full
  // download + extract pipeline. Subscribe via onModpackProgress for the
  // live log lines (one per file).
  modpackImport: () => invoke('modpack:import'),
  // Export a profile's mods + config to a shareable .mrpack. Pass
  // { profileId?, name?, versionId?, summary?, includePacks? }.
  modpackExport: (opts) => invoke('modpack:export', opts || {}),
  onModpackProgress: (fn) => on('modpack:progress', fn),

  // Mods (jars in <gameDir>/mods)
  listMods:       () => invoke('mods:list'),
  addMods:        () => invoke('mods:add'),
  openModsFolder: () => invoke('mods:openFolder'),
  deleteMod:      (baseName) => invoke('mods:delete', baseName),

  // Resource packs (<gameDir>/resourcepacks)
  listResourcePacks:       () => invoke('resourcepacks:list'),
  addResourcePacks:        () => invoke('resourcepacks:add'),
  deleteResourcePack: (n) => invoke('resourcepacks:delete', n),
  openResourcePacksFolder: () => invoke('resourcepacks:openFolder'),

  // Shader packs (<gameDir>/shaderpacks)
  listShaderPacks:         () => invoke('shaders:list'),
  addShaderPacks:          () => invoke('shaders:add'),
  deleteShaderPack:   (n) => invoke('shaders:delete', n),
  openShadersFolder:       () => invoke('shaders:openFolder'),

  // Game
  launchGame:   () => invoke('game:launch'),
  stopGame:     () => invoke('game:stop'),
  isRunning:    () => invoke('game:running'),
  lastLaunch:   () => invoke('launch:lastInfo'),
  clientReadiness: () => invoke('launch:readiness'),
  onGameExit:   (fn) => on('game:exited',  fn),
  onGameCrash:  (fn) => on('game:crash',   fn),
  onGameStart:  (fn) => on('game:started', fn),

  // Logs
  getAllLogs: () => invoke('logs:all'),
  clearLogs:  () => invoke('logs:clear'),
  onLog:      (fn) => on('logs:append', fn),

  // Updates
  checkUpdates:    () => invoke('updater:check'),
  updateSummary:   () => invoke('updater:summary'),
  onUpdateProgress: (fn) => on('updater:progress', fn),
  onUpdateResult:   (fn) => on('updater:result',   fn),

  // Shell
  openExternal: (url) => invoke('shell:openExternal', url),
  openPath:     (p)   => invoke('shell:openPath', p),

  // System probes
  ramInfo: () => invoke('system:ramInfo'),

  // Crash reports
  findCrashSince:   (sinceMs) => invoke('crash:findNewSince', sinceMs),
  readCrashReport:  (path)    => invoke('crash:read', path),
  openCrashFolder:  ()        => invoke('crash:openFolder'),

  // Minecraft skin head (Crafatar, fetched in main process → data URI)
  fetchAvatar: (uuid) => invoke('avatar:fetch', uuid),

  // Skin manager (Mojang API — main process only, token never crosses IPC)
  fetchSkin:  ()            => invoke('skins:fetch'),
  fetchSkinPng: ()          => invoke('skins:fetchPng'),
  uploadSkin: (variant)     => invoke('skins:upload', variant),
  uploadSkinBytes: (payload) => invoke('skins:uploadBytes', payload || {}),

  // Launch stage progress events
  onLaunchStage: (fn) => on('launch:stage', fn),

  // News feed
  fetchNews: () => invoke('news:fetch'),

  // Discord RPC live status (for the Settings indicator)
  discordStatus: () => invoke('discord:status'),

  // Settings reset + about + log save/upload
  resetSettings: () => invoke('settings:reset'),
  saveLogs:      () => invoke('logs:save'),
  uploadLogs:    () => invoke('logs:upload'),
  about:         () => invoke('app:about'),

  // World backups
  listWorlds:         (profileId)        => invoke('worlds:list',          profileId || null),
  listWorldBackups:   (profileId)        => invoke('worlds:listBackups',   profileId || null),
  backupWorld:        (payload)          => invoke('worlds:backup',        payload || {}),
  restoreWorldBackup: (payload)          => invoke('worlds:restoreBackup', payload || {}),
  deleteWorldBackup:  (payload)          => invoke('worlds:deleteBackup',  payload || {}),

  // Screenshots gallery
  listScreenshots:       (profileId) => invoke('screenshots:list',       profileId || null),
  deleteScreenshot:      (filePath)  => invoke('screenshots:delete',     filePath),
  revealScreenshot:      (filePath)  => invoke('screenshots:reveal',     filePath),
  openScreenshotsFolder: (profileId) => invoke('screenshots:openFolder', profileId || null),

  // Launcher self-update (electron-updater)
  onLauncherUpdateReady: (fn) => on('launcher:update-ready', fn),
  onLauncherUpdateError: (fn) => on('launcher:update-error', fn),
  installLauncherUpdate: () => invoke('updater:install'),
});
