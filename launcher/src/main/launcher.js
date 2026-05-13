// Game process orchestration. Spawns Java with the command built by
// mcVersion.js, streams stdout/stderr into the shared log buffer, and
// installs the Kitsune mod jar into the configured mods folder.

const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

const paths = require('./paths');
const settings = require('./settings');
const auth = require('./auth');
const java = require('./java');
const mcVersion = require('./mcVersion');
const updater = require('./updater');
const logs = require('./logs');
const addons = require('./addons');
const fabricInstaller = require('./fabricInstaller');
const profilesStore = require('./profiles');

/** The MC version the Fox Client jar is built against. Bump in lockstep with
 *  the mod's gradle.properties `minecraft_version`. When a profile targets a
 *  different version the Fox Client jar is skipped and the game launches with
 *  plain Fabric (or vanilla if Fabric isn't installed for that version). */
const TARGET_MC_VERSION = '26.1.2';

/** Return the MC version to use for a given profile.
 *  Falls back to TARGET_MC_VERSION when the profile has no override. */
function resolveTargetVersion(profile) {
  return (profile && profile.mcVersion) ? profile.mcVersion : TARGET_MC_VERSION;
}

/**
 * Locate a freshly-built kitsune-client jar under the project's `build/libs`
 * directory. Used so a developer running `npm start` picks up their last
 * `./gradlew build` without needing to push a GitHub release first.
 *
 * Walks two parents up from this file (src/main → launcher → project root)
 * and looks for `build/libs/kitsune-client-*.jar`. Returns the newest by
 * mtime, or null.
 */
function findLocalDevJar() {
  // src/main/launcher.js → src/main → launcher → project root
  const projectRoot = path.resolve(__dirname, '..', '..', '..');
  const libs = path.join(projectRoot, 'build', 'libs');
  let entries;
  try { entries = fs.readdirSync(libs); } catch (_) { return null; }
  const matches = entries
    .filter(name => /^kitsune-client.*\.jar$/i.test(name) && !/sources?\.jar$/i.test(name))
    .map(name => {
      const full = path.join(libs, name);
      let mtime = 0;
      try { mtime = fs.statSync(full).mtimeMs; } catch (_) {}
      return { full, mtime };
    });
  if (!matches.length) return null;
  matches.sort((a, b) => b.mtime - a.mtime);
  return matches[0].full;
}

let current = null; // { child, versionId, startedAt, gameDir, profileId }

function isRunning() { return current != null; }

// Launch-stage emitter — injected by ipc.js after register() so we can push
// structured progress messages to the renderer without a circular dependency.
let _stageEmitter = null;
function setStageEmitter(fn) { _stageEmitter = fn; }
function _stage(msg) {
  logs.push('info', msg);
  if (_stageEmitter) try { _stageEmitter(msg); } catch (_) {}
}

// Game-exit hook — injected by index.js so the tray can react when the
// game process exits, without creating a circular dependency.
let _exitHook = null;
function setExitHook(fn) { _exitHook = fn; }

/** Snapshot of the most recent launch — survives `current = null` after exit
 *  so the renderer can still ask "did the game crash?" after the fact. */
let lastLaunch = null;
function lastLaunchInfo() { return lastLaunch; }

/** Stamp lastPlayedAt + lastVersion on the active profile, via the shared
 *  profiles module so we don't reimplement atomic IO here. */
function _patchProfile(profileId, patch) {
  if (!profileId) return;
  try { profilesStore.patch(profileId, patch); } catch (_) { /* best-effort */ }
}

/** Field-wise merge of a profile's resolution override over the global one.
 *  Null fields on the profile mean "use the global value." */
function mergeResolution(global, profile) {
  if (!profile) return global;
  return {
    width:      profile.width  != null ? profile.width  : global.width,
    height:     profile.height != null ? profile.height : global.height,
    fullscreen: profile.fullscreen != null ? profile.fullscreen : global.fullscreen,
  };
}

/**
 * Install (copy) the Kitsune client jar into <gameDir>/mods/, replacing any
 * older kitsune-client-*.jar. The jar is stored at
 * ~/.foxlauncher/versions/<id>/kitsune-client.jar after a successful update
 * check (see updater.js).
 */
function installModJar(gameDir, localJar) {
  if (!localJar || !fs.existsSync(localJar)) return null;
  const modsDir = path.join(gameDir, 'mods');
  fs.mkdirSync(modsDir, { recursive: true });
  // Remove any previously-installed kitsune-client jars
  for (const f of fs.readdirSync(modsDir)) {
    if (/^kitsune-client.*\.jar$/i.test(f)) {
      try { fs.unlinkSync(path.join(modsDir, f)); } catch (_) {}
    }
  }
  const target = path.join(modsDir, 'kitsune-client.jar');
  fs.copyFileSync(localJar, target);
  return target;
}

/**
 * Launch the game. Returns once the process is spawned; the game keeps running
 * in the background. Reject if prerequisites fail (no auth, no java, etc.).
 */
async function launch(onExit) {
  if (current) throw new Error('A game instance is already running.');

  const s = settings.load();
  logs.clear();
  logs.beginFile();
  logs.push('info', `Fox Launcher starting game…`);
  if (logs.currentFilePath()) logs.push('info', `Logging to ${logs.currentFilePath()}`);

  // 1. Auth — auth.js auto-resolves the right vault from the active profile
  //    (isolated profiles get their own auth.json under instances/<id>/).
  _stage('Checking authentication…');
  const authRecord = await auth.getValid();
  if (!authRecord) throw new Error('Not signed in. Please authenticate with your Microsoft account first.');
  logs.push('info', `Authenticated as ${authRecord.username}`);

  // 1b. Active profile lookup — needed early because isolation affects the
  //     gameDir resolution that follows.
  const activeProfile = profilesStore.find(s.selectedProfile) || null;

  // Resolve the MC version for this launch — profile override or the built-in target.
  const targetVersion = resolveTargetVersion(activeProfile);
  const clientSupported = (targetVersion === TARGET_MC_VERSION);

  // 1c. Stamp the resolved Microsoft account onto the active profile, so
  //     next time the user activates this profile we can warn if the
  //     signed-in account differs. Skipped for guest auth (no real UUID).
  if (activeProfile && authRecord.uuid && !authRecord.guest) {
    const cur = activeProfile.accountUuid || null;
    if (cur !== authRecord.uuid) {
      try {
        profilesStore.bindAccount(activeProfile.id, {
          username: authRecord.username,
          uuid: authRecord.uuid,
        });
        logs.push('info', `Bound profile "${activeProfile.name}" to account ${authRecord.username}`);
      } catch (e) { logs.push('warn', `Account binding failed: ${e.message}`); }
    }
  }

  // 2. Java
  _stage('Detecting Java…');
  const j = await java.detect(s.javaPath);
  if (!j.ok) throw new Error(j.reason || `Java ${java.REQUIRED_MAJOR}+ is required.`);
  logs.push('info', `Using Java ${j.versionString} at ${j.path}`);

  // 3. Library root (gameDir) — where vanilla MC, Fabric loader libs, and
  //    assets live. Always the global .minecraft (user installed it via the
  //    official launcher); shared across all Fox Launcher profiles so we
  //    don't duplicate gigabytes of asset/library data per identity.
  const gameDir = s.gameDir && s.gameDir.trim() ? s.gameDir : paths.defaultMinecraft();
  if (!fs.existsSync(gameDir)) {
    throw new Error(`Game directory not found: ${gameDir}. Install Minecraft with the official launcher first.`);
  }
  // Vanilla MC for our target version must be present (Fabric inheritsFrom
  // it). We can't bootstrap vanilla — the asset / library / natives downloader
  // is huge and out of scope. Surface a clear error if missing.
  const vanillaJsonPath = path.join(gameDir, 'versions', targetVersion, `${targetVersion}.json`);
  if (!fs.existsSync(vanillaJsonPath)) {
    throw new Error(
      `Vanilla Minecraft ${targetVersion} not installed. ` +
      `Run the official Minecraft launcher once, play vanilla ${targetVersion}, ` +
      `then return here. (Fox Launcher can install Fabric for you, but not vanilla MC itself.)`);
  }

  // Fabric profile: install on demand if it's not already present.
  let versionId = mcVersion.findFabricProfile(gameDir, targetVersion);
  if (!versionId) {
    _stage(`Installing Fabric for Minecraft ${targetVersion}…`);
    logs.push('info', `Fabric for ${targetVersion} not installed — fetching automatically…`);
    try {
      const result = await fabricInstaller.install(gameDir, targetVersion, {
        onProgress: (msg) => logs.push('info', msg),
      });
      versionId = result.profileId;
      logs.push('info', `Fabric installed: ${versionId} (${result.downloaded} libs downloaded, ${result.skipped} cached).`);
    } catch (err) {
      // Fabric install failed — fall back to launching plain vanilla if possible.
      const vanillaId = targetVersion;
      const vanillaLibs = path.join(gameDir, 'versions', vanillaId, `${vanillaId}.jar`);
      if (fs.existsSync(vanillaLibs)) {
        logs.push('warn', `Fabric install failed (${err.message}). Falling back to vanilla ${targetVersion}.`);
        versionId = vanillaId;
      } else {
        throw new Error(`Couldn't install Fabric for ${targetVersion}: ${err.message}`);
      }
    }
  }
  logs.push('info', `Library root: ${gameDir}`);
  logs.push('info', `Version: ${versionId} (MC ${targetVersion}${clientSupported ? ', client supported' : ', vanilla/Fabric only'})`);

  // 3b. Resolve the *user* game directory — where mods/, config/, saves/,
  //     and options.txt live for this profile. Three modes, in priority:
  //       1. Profile is isolated  → ~/.foxlauncher/instances/<id>/
  //       2. Profile has gameDirOverride → that absolute path
  //       3. Default → same as the library root (`gameDir`)
  //     The library root is always the global .minecraft so we don't
  //     duplicate gigabytes of vanilla MC data per profile.
  let launchGameDir;
  if (activeProfile && activeProfile.isolated) {
    launchGameDir = paths.ensureInstance(activeProfile.id);
    logs.push('info', `Isolated profile: using instance dir ${launchGameDir}`);
  } else if (activeProfile && activeProfile.gameDirOverride) {
    launchGameDir = activeProfile.gameDirOverride;
    if (!fs.existsSync(launchGameDir)) {
      throw new Error(`Profile game-dir override does not exist: ${launchGameDir}`);
    }
    logs.push('info', `Profile game directory: ${launchGameDir}`);
  } else {
    launchGameDir = gameDir;
  }

  // 3c. Ensure Fabric API is present in <launchGameDir>/mods/. Required by
  //     virtually every Fabric mod (EntityCulling, ImmediatelyFast, client
  //     itself). installFabricApi() is idempotent — skips if already there.
  //     Goes in launchGameDir, not gameDir, so isolated profiles each have
  //     their own copy.
  try {
    _stage('Checking Fabric API…');
    await fabricInstaller.installFabricApi(launchGameDir, targetVersion, (msg) => {
      logs.push('info', msg);
      _stage(msg);
    });
  } catch (err) {
    logs.push('warn', `Fabric API install failed (will attempt launch anyway): ${err.message}`);
  }

  // 4. Install client jar — only if this profile targets the version the
  //    Fox Client jar was built against. Any other version launches with
  //    plain Fabric (or vanilla) so the user doesn't get a crash from a
  //    jar built against different mappings.
  let installed = null;
  if (clientSupported) {
    try {
      if (s.autoUpdate) {
        _stage('Checking for client update…');
        logs.push('info', 'Checking for Kitsune client update…');
        const up = await updater.checkAndDownload({ onProgress: (msg) => logs.push('info', msg) });
        if (up && up.path) installed = installModJar(launchGameDir, up.path);
      }
      if (!installed) {
        const cached = updater.currentLocalJar();
        if (cached) installed = installModJar(launchGameDir, cached);
      }
      if (!installed) {
        const devJar = findLocalDevJar();
        if (devJar) {
          installed = installModJar(launchGameDir, devJar);
          if (installed) logs.push('info', `Using locally-built dev jar: ${devJar}`);
        }
      }
      if (installed) logs.push('info', `Installed client mod: ${installed}`);
      else logs.push('warn', 'No Kitsune client jar available — launching Fabric without the Fox client.');
    } catch (err) {
      logs.push('warn', `Mod install failed: ${err.message}`);
      const cached = updater.currentLocalJar();
      if (cached) installed = installModJar(launchGameDir, cached);
      else {
        const devJar = findLocalDevJar();
        if (devJar) installed = installModJar(launchGameDir, devJar);
      }
    }
  } else {
    logs.push('info', `Skipping Fox Client jar — not built for MC ${targetVersion} (built for ${TARGET_MC_VERSION}). Launching Fabric only.`);
    _stage(`Launching Fabric ${targetVersion} (Fox Client not compatible)…`);
  }

  // 4b. Apply per-profile mod toggles inside the user-data dir.
  if (activeProfile) {
    const toggleResult = profilesStore.applyModToggles(launchGameDir, activeProfile.id);
    if (toggleResult.disabled.length) {
      logs.push('info', `Profile "${activeProfile.name}" disabled mods: ${toggleResult.disabled.join(', ')}`);
    }
  }

  // 4c. Write addons.json so the mod sees this profile's addon flags on
  //     init. Always write, even with an empty list — that way switching
  //     from "PvP-safe" back to "default" actually re-enables addons.
  const disabledAddonIds = activeProfile && Array.isArray(activeProfile.disabledAddons)
    ? activeProfile.disabledAddons : [];
  if (addons.writeAddonsJson(launchGameDir, disabledAddonIds)) {
    if (disabledAddonIds.length) {
      logs.push('info', `Profile addons disabled: ${disabledAddonIds.join(', ')}`);
    }
  } else {
    logs.push('warn', 'Could not write addons.json — addon toggles may not apply.');
  }

  // Resolve RAM — profile override takes precedence over the global setting.
  const minRam = (activeProfile && activeProfile.ramMin != null) ? activeProfile.ramMin : s.minRam;
  const maxRam = (activeProfile && activeProfile.ramMax != null) ? activeProfile.ramMax : s.maxRam;
  if (activeProfile && (activeProfile.ramMin != null || activeProfile.ramMax != null)) {
    logs.push('info', `Profile RAM override: ${minRam}–${maxRam} GB`);
  }

  // Resolution — profile override uses null fields to fall back to global.
  const resolution = mergeResolution(s.resolution, activeProfile && activeProfile.resolution);

  // Extra JVM args from the profile (whitespace-split, simple word splitting).
  const extraJvmArgs = (activeProfile && activeProfile.jvmArgs)
    ? activeProfile.jvmArgs.split(/\s+/).filter(Boolean)
    : [];
  if (extraJvmArgs.length) {
    logs.push('info', `Profile JVM args: ${extraJvmArgs.join(' ')}`);
  }

  // Server auto-join.
  const serverHost = (activeProfile && activeProfile.serverHost) || '';
  const serverPort = (activeProfile && activeProfile.serverPort) || null;
  if (serverHost) {
    logs.push('info', `Profile auto-join: ${serverHost}${serverPort ? ':' + serverPort : ''}`);
  }

  // 5. Build command. Isolated profiles need a split: libs/versions/assets
  //    come from the global library root, user data (mods/, saves/, options
  //    .txt, etc.) comes from the per-profile instance dir. For linked or
  //    gameDirOverride'd profiles both are the same dir (current behavior).
  const libraryRoot = (activeProfile && activeProfile.isolated) ? gameDir : launchGameDir;
  const cmd = mcVersion.buildLaunchCommand({
    gameDir: libraryRoot,
    userGameDir: launchGameDir,
    versionId,
    auth: authRecord,
    javaPath: j.path,
    minRam,
    maxRam,
    resolution,
    extraJvmArgs,
    serverHost,
    serverPort,
  });

  _stage('Starting Minecraft…');
  logs.push('info', `Spawning: ${cmd.command} (${cmd.args.length} args)`);

  // 6. Spawn
  const child = spawn(cmd.command, cmd.args, {
    cwd: cmd.cwd,
    windowsHide: true,
    stdio: ['ignore', 'pipe', 'pipe'],
  });

  const startedAt = Date.now();
  // Track the user-data dir (launchGameDir) on `current`/`lastLaunch` so
  // crash-report scanning looks in the right place — crash logs live under
  // <userGameDir>/crash-reports/, not the library root.
  current = {
    child,
    versionId,
    startedAt,
    gameDir: launchGameDir,
    profileId: s.selectedProfile || null,
  };
  lastLaunch = { versionId, startedAt, gameDir: launchGameDir, profileId: s.selectedProfile || null, exitCode: null };

  // Stamp lastPlayedAt on the active profile right when the game starts —
  // we don't wait for a clean exit, because a player who alt-F4s out of a
  // crash still played that profile. The UI cares about "when did I last
  // open this," not "did it exit gracefully."
  _patchProfile(current.profileId, { lastPlayedAt: startedAt, lastVersion: versionId });

  _stage(`Game started · PID ${child.pid}`);
  child.stdout.on('data', (d) => logs.push('stdout', d.toString()));
  child.stderr.on('data', (d) => logs.push('stderr', d.toString()));
  child.on('error', (err) => {
    logs.push('error', `Process error: ${err.message}`);
  });
  child.on('close', (code, signal) => {
    logs.push('info', `Game exited (code=${code}${signal ? ', signal=' + signal : ''}).`);
    logs.endFile();
    if (lastLaunch) lastLaunch.exitCode = code;
    current = null;
    if (onExit) onExit({ code, signal, startedAt, gameDir });
    if (_exitHook) try { _exitHook({ code, signal }); } catch (_) {}
  });

  return { versionId, pid: child.pid, startedAt };
}

function stop() {
  if (!current) return false;
  try { current.child.kill(); } catch (_) {}
  return true;
}

/**
 * Stop the game and resolve once the child process has actually exited.
 * Falls back to SIGKILL after 4s in case the process ignores the initial signal.
 */
function stopAndWait(timeoutMs = 4000) {
  if (!current) return Promise.resolve(false);
  const c = current.child;
  return new Promise((resolve) => {
    let done = false;
    const finish = () => { if (!done) { done = true; resolve(true); } };
    c.once('close', finish);
    try { c.kill(); } catch (_) {}
    setTimeout(() => {
      if (done) return;
      try { c.kill('SIGKILL'); } catch (_) {}
      setTimeout(finish, 500);
    }, timeoutMs);
  });
}

/** Snapshot of "is Fox Client launchable right now?" for the Home screen.
 *  Cheap — just a directory scan + jar lookup, no java probe or auth check. */
function clientReadiness() {
  const s = settings.load();
  const gameDir = s.gameDir && s.gameDir.trim() ? s.gameDir : paths.defaultMinecraft();
  const gameDirExists = fs.existsSync(gameDir);

  // Use the active profile's version override if set.
  const activeProfile = profilesStore.find(s.selectedProfile) || null;
  const targetVersion  = resolveTargetVersion(activeProfile);
  const clientSupported = (targetVersion === TARGET_MC_VERSION);

  const vanillaJsonPath = gameDirExists
    ? path.join(gameDir, 'versions', targetVersion, `${targetVersion}.json`)
    : null;
  const vanillaInstalled = !!vanillaJsonPath && fs.existsSync(vanillaJsonPath);
  const fabricProfile = gameDirExists ? mcVersion.findFabricProfile(gameDir, targetVersion) : null;
  const cachedJar = updater.currentLocalJar();
  const devJar = findLocalDevJar();
  return {
    targetMcVersion:      TARGET_MC_VERSION,    // version the Fox Client jar targets
    selectedMcVersion:    targetVersion,         // version this profile will actually launch
    clientSupported,                             // false → launching Fabric/vanilla only
    gameDir,
    gameDirExists,
    vanillaInstalled,                            // user must install via official launcher
    fabricProfile,                               // null if not installed for selectedMcVersion
    fabricAutoInstallable: vanillaInstalled,     // true → next PLAY auto-fetches Fabric
    hasModJar: !!(cachedJar || devJar),
    modJarSource: cachedJar ? 'release' : (devJar ? 'dev-build' : null),
  };
}

module.exports = { launch, stop, stopAndWait, isRunning, lastLaunchInfo, clientReadiness, setStageEmitter, setExitHook, TARGET_MC_VERSION };
