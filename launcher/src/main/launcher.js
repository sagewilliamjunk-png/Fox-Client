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
const recommendedMods = require('./recommendedMods');
const profilesStore = require('./profiles');
const javaDownloader = require('./javaDownloader');
const mcInstaller = require('./mcInstaller');
const notifications = require('./notifications');

/** The MC version the Fox Client jar is built against.
 *  Read directly from gradle.properties so bumping the version in one place
 *  (the mod's build config) automatically updates the launcher too.
 *  Falls back to a hardcoded value only if the file can't be read. */
function readGradleMcVersion() {
  try {
    // launcher/src/main/launcher.js → launcher/src/main → launcher → project root
    const propsPath = path.resolve(__dirname, '..', '..', '..', 'gradle.properties');
    const text = fs.readFileSync(propsPath, 'utf8');
    const match = text.match(/^\s*minecraft_version\s*=\s*(.+)$/m);
    if (match) return match[1].trim();
  } catch (_) {}
  return '26.1.2'; // fallback — keep in sync manually only if gradle.properties moves
}
const TARGET_MC_VERSION = readGradleMcVersion();

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

// Taskbar progress emitter — injected by index.js so we can push download
// progress into the Windows taskbar without a circular dependency.
// Call with a 0–100 percent value; call with -1 to clear the bar.
let _progressEmitter = null;
function setProgressEmitter(fn) { _progressEmitter = fn; }
function _progress(pct) {
  if (_progressEmitter) try { _progressEmitter(pct); } catch (_) {}
}

// Taskbar overlay-icon emitter — injected by index.js.
// Call with 'crashed' to show the red dot; null to clear it.
let _overlayEmitter = null;
function setOverlayEmitter(fn) { _overlayEmitter = fn; }
function _overlay(state) {
  if (_overlayEmitter) try { _overlayEmitter(state); } catch (_) {}
}

// Game-exit hook — injected by index.js so the tray can react when the
// game process exits, without creating a circular dependency.
let _exitHook = null;
function setExitHook(fn) { _exitHook = fn; }

/** The user-data game directory of the currently running game, or null. */
function currentGameDir() { return current ? current.gameDir : null; }

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
 * Remove mods whose filenames embed a Minecraft version that doesn't match
 * `targetVersion`. Only removes jars that have an obvious version tag
 * (e.g. `+1.21.11`, `mc1.21.11`, `-1.21.11`) — jars without a version tag
 * are left alone so manually-placed mods survive a version switch.
 *
 * Returns the list of removed filenames so the caller can log them.
 */
function purgeIncompatibleMods(modsDir, targetVersion) {
  if (!fs.existsSync(modsDir)) return [];
  // Use major.minor for comparison so "1.21" matches "1.21.11" target.
  const targetBase = targetVersion.split('.').slice(0, 2).join('.');
  const removed = [];
  for (const f of fs.readdirSync(modsDir)) {
    // Handle both active jars and profile-disabled jars (.jar.disabled).
    if (!/\.jar(\.disabled)?$/i.test(f)) continue;
    if (/^kitsune-client/i.test(f)) continue; // handled separately
    // Detect the embedded MC version using the naming patterns common on Modrinth:
    //   +1.21.11        ImmediatelyFast, FerriteCore  (+<version>, no mc prefix)
    //   +mc1.21.1       Sodium                        (+mc<version>)
    //   +mc-1.21.1      MemoryLeakFix                 (+mc-<version>)
    //   -mc1.21.11      Lithium, EntityCulling         (-mc<version>)
    // Prefer the '+' side of the filename (build metadata = MC version in semver),
    // then fall back to an explicit '-mc' prefix.
    const m = f.match(/\+m?c?[-_]?(\d+\.\d+)/) ||
              f.match(/[-_]mc(\d+\.\d+)/i);
    if (!m) continue; // no recognisable version tag — leave it
    const jarBase = m[1];
    if (jarBase !== targetBase) {
      try { fs.unlinkSync(path.join(modsDir, f)); removed.push(f); } catch (_) {}
    }
  }
  return removed;
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

  // 2. Java — detect first; if none found, auto-download a JRE from Adoptium
  //    (same approach as Modrinth App / Prism Launcher).
  _stage('Detecting Java…');
  let j = await java.detect(s.javaPath);
  if (!j.ok) {
    _stage('Downloading Java 21 JRE…');
    logs.push('info', 'No suitable Java found — downloading JRE from Adoptium…');
    try {
      const jrePath = await javaDownloader.ensureJre(({ message, percent }) => {
        _stage(message);
        if (percent != null) _progress(percent);
        logs.push('info', `[java] ${message}`);
      });
      java.invalidateCache();
      j = await java.detect(jrePath);
    } catch (jreErr) {
      throw new Error(
        `Java ${java.REQUIRED_MAJOR}+ is required and the automatic download failed: ${jreErr.message}. ` +
        `Please install Java manually from adoptium.net.`
      );
    }
    if (!j.ok) throw new Error(j.reason || `Java ${java.REQUIRED_MAJOR}+ is required.`);
  }
  logs.push('info', `Using Java ${j.versionString} at ${j.path}`);

  // 3. Library root — where vanilla MC, Fabric loader libs, and assets live.
  //    If the vanilla version isn't present we download it automatically from
  //    Mojang's CDN, the same way Modrinth App and Prism Launcher do.
  const gameDir = s.gameDir && s.gameDir.trim() ? s.gameDir : paths.defaultMinecraft();
  fs.mkdirSync(gameDir, { recursive: true });

  if (!mcInstaller.isInstalled(gameDir, targetVersion)) {
    _stage(`Downloading Minecraft ${targetVersion}…`);
    logs.push('info', `Minecraft ${targetVersion} not found — downloading from Mojang…`);
    try {
      const result = await mcInstaller.installVersion(gameDir, targetVersion, {
        onProgress: ({ message, percent }) => {
          _stage(message);
          if (percent != null) _progress(percent);
          logs.push('info', `[mc] ${message}`);
        },
      });
      logs.push('info',
        `Minecraft ${targetVersion} installed: ${result.downloaded} files downloaded, ${result.skipped} cached.`
      );
    } catch (mcErr) {
      throw new Error(`Failed to download Minecraft ${targetVersion}: ${mcErr.message}`);
    }
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
  // Purge any mods built for a different MC version before installing the
  // correct Fabric API. This handles the case where the user switches the
  // version picker — stale jars (Sodium, Lithium, etc.) would otherwise
  // cause an "Incompatible mods found!" crash on launch.
  const purged = purgeIncompatibleMods(path.join(launchGameDir, 'mods'), targetVersion);
  if (purged.length) {
    logs.push('info', `Removed ${purged.length} mod(s) incompatible with MC ${targetVersion}: ${purged.join(', ')}`);
    _stage(`Reinstalling mods for MC ${targetVersion}…`);
    try {
      await recommendedMods.installAll(launchGameDir, targetVersion, {
        essentialOnly: true,
        onProgress: (msg) => {
          logs.push('info', `[mods] ${msg}`);
          _stage(msg);
        },
      });
    } catch (err) {
      logs.push('warn', `Mod reinstall failed (will launch anyway): ${err.message}`);
    }
  }

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
  _progress(-1); // clear taskbar progress bar — game is launching
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
  _overlay(null); // clear any leftover crash badge from a previous session
  notifications.gameStarted(versionId);

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
    const crashed = code !== 0 && signal == null;
    if (crashed) {
      _overlay('crashed'); // red dot on taskbar icon until user opens the launcher
      notifications.gameCrashed(() => {
        try {
          const { BrowserWindow } = require('electron');
          const win = BrowserWindow.getAllWindows()[0];
          if (win) { win.show(); win.focus(); }
        } catch (_) {}
      });
    } else if (code === 0) {
      notifications.gameExited();
    }
    if (onExit) onExit({ code, signal, startedAt, gameDir });
    if (_exitHook) try { _exitHook({ code, signal }); } catch (_) {}
  });

  return { versionId, pid: child.pid, startedAt, gameDir: launchGameDir };
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

  // Also check whether the jar is already present in the mods folder from a
  // previous launch — this is the common case when neither the updater cache
  // nor build/libs is populated (e.g. first run after a clean install where
  // the user manually placed the jar, or after the launcher copied it once).
  const modsDir = gameDirExists ? path.join(gameDir, 'mods') : null;
  let installedModsJar = null;
  if (modsDir) {
    try {
      const entries = fs.readdirSync(modsDir);
      const match = entries.find(f => /^kitsune-client.*\.jar$/i.test(f) && !/sources?\.jar$/i.test(f));
      if (match) installedModsJar = path.join(modsDir, match);
    } catch (_) {}
  }

  const hasModJar = !!(cachedJar || devJar || installedModsJar);
  const modJarSource = cachedJar ? 'release' : devJar ? 'dev-build' : installedModsJar ? 'installed' : null;

  return {
    targetMcVersion:      TARGET_MC_VERSION,    // version the Fox Client jar targets
    selectedMcVersion:    targetVersion,         // version this profile will actually launch
    clientSupported,                             // false → launching Fabric/vanilla only
    gameDir,
    gameDirExists,
    vanillaInstalled,                            // if false, will auto-download on launch
    vanillaAutoInstallable: true,                // always true — mcInstaller handles it
    fabricProfile,                               // null if not installed for selectedMcVersion
    fabricAutoInstallable: true,                 // always true — fabricInstaller handles it
    hasModJar,
    modJarSource,
  };
}

module.exports = { launch, stop, stopAndWait, isRunning, lastLaunchInfo, clientReadiness, setStageEmitter, setExitHook, setProgressEmitter, setOverlayEmitter, TARGET_MC_VERSION };
