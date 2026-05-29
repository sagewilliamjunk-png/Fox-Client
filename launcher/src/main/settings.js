// Persistent user settings. Stored as JSON in ~/.foxlauncher/settings.json.
//
// Atomic writes: write to .tmp then rename. Safe against partial writes
// during a crash. Inputs are validated/clamped on every load and patch so
// that a corrupted or hand-edited settings.json can never wedge the launcher.

const fs = require('fs');
const path = require('path');
const paths = require('./paths');

const DEFAULTS = {
  javaPath: '',
  minRam: 2,
  maxRam: 4,
  gameDir: '',
  resolution: { width: 1280, height: 720, fullscreen: false },
  selectedProfile: 'default',
  keepLauncherOpen: true,
  autoUpdate: true,
  githubRepo: 'sagewilliamjunk-png/Fox-Client',
  theme: 'fox',
  // NOTE: selectedVersion was removed — version selection lives in profile.mcVersion now.
  /** URL to a JSON document for the Home news feed. Empty = no fetch.
   *  See main/news.js for the expected shape. */
  newsUrl: 'https://gist.githubusercontent.com/sagewilliamjunk-png/5246a5dd2d5b6072fe41b3ef3bb914f0/raw/fox-news.json',
  /** Discord rich-presence integration. Disabled until the user provides an
   *  Application ID at developers.discord.com. */
  discordRpcEnabled: true,
  discordAppId: '1497814976193364029',
  /** Microsoft Azure Application (client) ID for the device-code sign-in
   *  flow. Microsoft retired the legacy Mojang client ID for new tenants,
   *  so every Fox Launcher build needs its own registration. The login
   *  screen surfaces a one-time setup panel when this is empty. Bake in
   *  your tenant's client ID once you have one to skip that for friends. */
  msaClientId: 'dfb3c8ba-220e-4332-88e3-0c69c208e403',
  /** Last guest username, pre-filled on the login screen so a returning
   *  guest user doesn't have to retype it (and gets the same offline UUID
   *  → same singleplayer worlds attached). */
  lastGuestName: '',
  /** Tracks whether Fox Launcher's auto-install of recommended performance
   *  mods has run successfully. Flips to true once the background pass on
   *  boot completes; we don't try again unless the user explicitly resets
   *  settings or a future schema migration clears it. */
  recommendedModsInstalled: false,
  /** v2 flag — set after the full pack (essentialOnly:false) has been installed.
   *  Supersedes recommendedModsInstalled so existing users get the full pack. */
  recommendedModsInstalledFull: false,
  /** One-shot: shows the first-run wizard once after sign-in for new users.
   *  Set to true the moment the user finishes the wizard or dismisses it. */
  firstRunComplete: false,
  /** Launch Fox Launcher automatically when the user logs into Windows / macOS.
   *  Implemented via Electron's app.setLoginItemSettings(). Defaults off so
   *  the launcher doesn't silently add itself to startup on fresh installs. */
  launchOnStartup: false,
  /** When true, clicking the window close button (X) hides to the system
   *  tray instead of quitting. Useful for users who keep the launcher open
   *  during long Minecraft sessions for Modrinth browsing / Discord status.
   *  False keeps the conventional "X = quit" behavior. */
  minimizeToTray: false,
};

// Hard floors/ceilings. RAM bounds in GB; window bounds in pixels. Anything
// outside these ranges is silently clamped — the user can re-set it from the
// Settings screen if the clamp surprised them.
const BOUNDS = {
  minRam: { min: 1, max: 64 },
  maxRam: { min: 1, max: 64 },
  width:  { min: 320, max: 7680 },
  height: { min: 240, max: 4320 },
};

const ALLOWED_THEMES = ['fox', 'fox-light'];

let cache = null;

function clamp(n, lo, hi) {
  if (typeof n !== 'number' || !Number.isFinite(n)) return lo;
  return Math.max(lo, Math.min(hi, n));
}

function asBool(v, fallback) {
  return typeof v === 'boolean' ? v : fallback;
}

function asString(v, fallback) {
  return typeof v === 'string' ? v : fallback;
}

function validate(raw) {
  const merged = { ...DEFAULTS, ...(raw || {}) };
  const r = (raw && raw.resolution) || {};

  let minRam = clamp(Math.floor(merged.minRam), BOUNDS.minRam.min, BOUNDS.minRam.max);
  let maxRam = clamp(Math.floor(merged.maxRam), BOUNDS.maxRam.min, BOUNDS.maxRam.max);
  if (maxRam < minRam) maxRam = minRam;

  // Migration: the repo moved from the old "Kitsune/Fox-Client" placeholder to
  // the real "sagewilliamjunk-png/Fox-Client". Users with a settings.json from
  // before the move kept the stale value, which 404s the client-jar download
  // and shows "Client jar: none". Rewrite any known-legacy value to the default.
  const LEGACY_REPOS = ['Kitsune/Fox-Client', 'kitsune/Fox-Client'];
  if (LEGACY_REPOS.includes(merged.githubRepo)) {
    merged.githubRepo = DEFAULTS.githubRepo;
  }

  return {
    javaPath:         asString(merged.javaPath, ''),
    minRam,
    maxRam,
    gameDir:          asString(merged.gameDir, ''),
    resolution: {
      width:      clamp(Math.floor(r.width  ?? DEFAULTS.resolution.width),  BOUNDS.width.min,  BOUNDS.width.max),
      height:     clamp(Math.floor(r.height ?? DEFAULTS.resolution.height), BOUNDS.height.min, BOUNDS.height.max),
      fullscreen: asBool(r.fullscreen, DEFAULTS.resolution.fullscreen),
    },
    selectedProfile:  asString(merged.selectedProfile, 'default'),
    keepLauncherOpen: asBool(merged.keepLauncherOpen, DEFAULTS.keepLauncherOpen),
    autoUpdate:       asBool(merged.autoUpdate, DEFAULTS.autoUpdate),
    githubRepo:       asString(merged.githubRepo, DEFAULTS.githubRepo),
    theme:            ALLOWED_THEMES.includes(merged.theme) ? merged.theme : DEFAULTS.theme,
    newsUrl:           asString(merged.newsUrl, DEFAULTS.newsUrl),
    discordRpcEnabled: asBool(merged.discordRpcEnabled, DEFAULTS.discordRpcEnabled),
    discordAppId:      asString(merged.discordAppId, DEFAULTS.discordAppId),
    // Empty strings fall through to DEFAULTS so users with stale settings.json
    // from before we bundled a public client ID auto-pick up the new default.
    msaClientId:       (asString(merged.msaClientId, '').trim() || DEFAULTS.msaClientId),
    lastGuestName:     asString(merged.lastGuestName, DEFAULTS.lastGuestName),
    recommendedModsInstalled:     asBool(merged.recommendedModsInstalled,     DEFAULTS.recommendedModsInstalled),
    recommendedModsInstalledFull: asBool(merged.recommendedModsInstalledFull, DEFAULTS.recommendedModsInstalledFull),
    firstRunComplete:             asBool(merged.firstRunComplete,             DEFAULTS.firstRunComplete),
    minimizeToTray:               asBool(merged.minimizeToTray,               DEFAULTS.minimizeToTray),
    launchOnStartup:   asBool(merged.launchOnStartup, DEFAULTS.launchOnStartup),
  };
}

function load() {
  if (cache) return cache;
  paths.ensureAll();
  let raw = null;
  try {
    raw = JSON.parse(fs.readFileSync(paths.settings, 'utf8'));
  } catch (_) {
    raw = null;
  }
  cache = validate(raw);
  if (!raw) save(cache);
  return cache;
}

function save(next) {
  paths.ensureAll();
  cache = validate(next);
  const tmp = paths.settings + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(cache, null, 2));
  fs.renameSync(tmp, paths.settings);
  return cache;
}

function patch(partial) {
  const current = load();
  const merged = { ...current, ...partial };
  if (partial && partial.resolution) {
    merged.resolution = { ...current.resolution, ...partial.resolution };
  }
  return save(merged);
}

module.exports = { load, save, patch, DEFAULTS, BOUNDS };
