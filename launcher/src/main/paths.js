// Central source of truth for every path the launcher touches.
// Everything lives in ~/.foxlauncher/ per the spec.

const os = require('os');
const path = require('path');
const fs = require('fs');

const HOME = os.homedir();
const ROOT = path.join(HOME, '.foxlauncher');

const paths = {
  root: ROOT,
  settings: path.join(ROOT, 'settings.json'),
  auth: path.join(ROOT, 'auth.json'),      // global auth vault — used by linked profiles
  versions: path.join(ROOT, 'versions'),   // downloaded client jars
  profiles: path.join(ROOT, 'profiles.json'),
  logs: path.join(ROOT, 'logs'),           // rotated stdout/stderr of game
  cache: path.join(ROOT, 'cache'),
  /** Per-profile isolated game-directory roots. Each isolated profile owns
   *  <instances>/<profile.id>/ — its own mods/, saves/, config/, options.txt,
   *  servers.dat, AND auth.json. This is what makes "two different people"
   *  cleanly separable. Linked profiles ignore this entirely. */
  instances: path.join(ROOT, 'instances'),

  /** Resolve the per-profile instance directory. The directory is NOT
   *  created here — call ensureInstance() to materialize it. */
  instanceDir(profileId) {
    return path.join(ROOT, 'instances', String(profileId));
  },

  /** Materialize the instance directory for the given profile id and return
   *  its absolute path. mkdirp + the standard subfolders MC expects. */
  ensureInstance(profileId) {
    const dir = paths.instanceDir(profileId);
    try {
      fs.mkdirSync(dir, { recursive: true });
      for (const sub of ['mods', 'config', 'saves', 'screenshots', 'resourcepacks', 'shaderpacks']) {
        fs.mkdirSync(path.join(dir, sub), { recursive: true });
      }
    } catch (_) { /* best effort */ }
    return dir;
  },

  /** Auth vault path for a given profile object. Isolated profiles get their
   *  own vault inside their instance dir; linked profiles share the global. */
  authVaultForProfile(profile) {
    if (profile && profile.isolated && profile.id) {
      return path.join(ROOT, 'instances', profile.id, 'auth.json');
    }
    return paths.auth;
  },

  /** Default Minecraft install path (per OS) — used when settings.gameDir is empty. */
  defaultMinecraft() {
    switch (process.platform) {
      case 'win32':
        return path.join(process.env.APPDATA || path.join(HOME, 'AppData', 'Roaming'), '.minecraft');
      case 'darwin':
        return path.join(HOME, 'Library', 'Application Support', 'minecraft');
      default:
        return path.join(HOME, '.minecraft');
    }
  },

  /** Create all launcher subdirectories if they don't exist. Idempotent. */
  ensureAll() {
    for (const p of [ROOT, paths.versions, paths.logs, paths.cache, paths.instances]) {
      try { fs.mkdirSync(p, { recursive: true }); } catch (_) {}
    }
  },
};

module.exports = paths;
