// Game-directory resolution, shared by every IPC handler that needs to know
// where mods / packs / screenshots live. Two flavours:
//
//   resolveGameDir()          → the global game dir (settings override or the
//                               platform default .minecraft)
//   gameDirForProfile(id)     → the directory a specific profile actually
//                               plays from (isolated instance dir, per-profile
//                               override, or the global dir)

const settings = require('./settings');
const paths = require('./paths');
const profiles = require('./profiles');

/**
 * The global game directory. Pass an already-loaded settings object to avoid
 * a redundant disk read when the caller has one in hand.
 */
function resolveGameDir(s = settings.load()) {
  const v = s.gameDir && s.gameDir.trim();
  return v || paths.defaultMinecraft();
}

/** Resolve game directory for a profile id, or the active profile when null. */
function gameDirForProfile(profileId) {
  const s = settings.load();
  const id = profileId || s.selectedProfile;
  const profile = id ? profiles.find(id) : null;
  if (profile && profile.isolated) return paths.instanceDir(id);
  if (profile && profile.gameDirOverride && profile.gameDirOverride.trim()) {
    return profile.gameDirOverride.trim();
  }
  return resolveGameDir(s);
}

module.exports = { resolveGameDir, gameDirForProfile };
