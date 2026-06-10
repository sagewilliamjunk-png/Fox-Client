// Reads a Minecraft version JSON from an existing install and produces the
// launch command.
//
// The launcher assumes the user has run the official Minecraft launcher at
// least once to download the target version's libraries, assets, and natives.
// This is the same assumption Prism Launcher and MultiMC make for their
// bootstrap flow — reimplementing vanilla's entire asset downloader is a
// multi-week project and out of scope for this MVP.
//
// Version JSONs live at <gameDir>/versions/<id>/<id>.json with metadata.
// Some versions use `inheritsFrom` to chain to a base version (Fabric, Forge
// profiles do this). We walk the chain and merge fields.

const fs = require('fs');
const path = require('path');

/** List all version IDs present in <gameDir>/versions. */
function listVersions(gameDir) {
  const dir = path.join(gameDir, 'versions');
  try {
    return fs.readdirSync(dir)
      .filter(name => {
        const jsonPath = path.join(dir, name, `${name}.json`);
        return fs.existsSync(jsonPath);
      })
      .sort();
  } catch (_) {
    return [];
  }
}

/**
 * Enriched version listing — for each install under <gameDir>/versions/,
 * read just enough of its JSON (or its inheritsFrom parent) to know:
 *
 *   - what major Java version it requires (via the version JSON's
 *     `javaVersion.majorVersion`, with a fallback by ID prefix);
 *   - whether it's a Fabric / Forge / Quilt loader profile;
 *   - whether the launcher can plausibly run it (Java 21 cap on the user's
 *     side means anything requiring Java <= 17 may still work, but anything
 *     newer than what they have installed won't).
 *
 * Returned objects: { id, type, requiredJava, family, loader, runnable }.
 * The list is sorted newest-first by parsed semver of the family field.
 */
function listVersionsEnriched(gameDir, hostJavaMajor) {
  const ids = listVersions(gameDir);
  const out = [];
  for (const id of ids) {
    const meta = describeVersion(gameDir, id);
    if (!meta) continue;
    meta.runnable = (hostJavaMajor || 0) >= (meta.requiredJava || 8);
    out.push(meta);
  }
  // Newest first (Mojang's own ordering); 'release' before 'snapshot' on ties.
  out.sort((a, b) => {
    const fa = familySort(a.family) - familySort(b.family);
    if (fa !== 0) return -fa;
    return a.id < b.id ? 1 : a.id > b.id ? -1 : 0;
  });
  return out;
}

function describeVersion(gameDir, id) {
  let v;
  try { v = readVersionJson(gameDir, id); } catch (_) { return null; }

  // Walk one inheritsFrom step (most loader profiles only chain once and
  // we don't need full library merging for filtering).
  let parentId = v.inheritsFrom || null;
  let parent = null;
  if (parentId) {
    try { parent = readVersionJson(gameDir, parentId); } catch (_) { parent = null; }
  }

  const requiredJava =
    (v.javaVersion && v.javaVersion.majorVersion) ||
    (parent && parent.javaVersion && parent.javaVersion.majorVersion) ||
    inferJavaFromId(parentId || id);

  // Detect loader by ID heuristics — version JSON `type` is "release" /
  // "snapshot" but doesn't say "fabric". Loader profile IDs are predictable.
  const loader = detectLoader(id, v);

  return {
    id,
    type: v.type || 'release',
    requiredJava,
    family: parentId || extractFamily(id),
    loader,
  };
}

function detectLoader(id, v) {
  const lower = id.toLowerCase();
  if (lower.startsWith('fabric-loader-')) return 'fabric';
  if (lower.includes('forge'))            return 'forge';
  if (lower.includes('quilt'))            return 'quilt';
  if (lower.includes('neoforge'))         return 'neoforge';
  if (v.libraries && v.libraries.some(l => /fabricloader/i.test(l.name || '')))  return 'fabric';
  if (v.libraries && v.libraries.some(l => /minecraftforge|net\.minecraftforge/i.test(l.name || ''))) return 'forge';
  return null;
}

/** Extract the underlying MC version family string from an ID, e.g.
 *  "fabric-loader-0.18.6-1.21.11" → "1.21.11"; "1.20.1" → "1.20.1". */
function extractFamily(id) {
  const m = id.match(/(\d+\.\d+(?:\.\d+)?)$/);
  return m ? m[1] : id;
}

/** Java requirement inferred from the MC family when the version JSON
 *  doesn't specify it (older installs from before the field existed). */
function inferJavaFromId(id) {
  const fam = extractFamily(id);
  const m = fam.match(/^1\.(\d+)/);
  if (!m) return 8;
  const minor = parseInt(m[1], 10);
  if (minor >= 21) return 21;     // 1.21+ requires Java 21
  if (minor >= 18) return 17;     // 1.18 - 1.20.4 require Java 17
  if (minor >= 17) return 16;     // 1.17 requires Java 16
  return 8;                       // 1.16 and below
}

/** Stable sort key for a family string: parses `1.A.B` → A*1000 + B. */
function familySort(family) {
  const m = String(family || '').match(/^1\.(\d+)(?:\.(\d+))?/);
  if (!m) return 0;
  return parseInt(m[1], 10) * 1000 + parseInt(m[2] || '0', 10);
}

function readVersionJson(gameDir, id) {
  const p = path.join(gameDir, 'versions', id, `${id}.json`);
  const raw = fs.readFileSync(p, 'utf8');
  return JSON.parse(raw);
}

/** Merge a version with its inheritsFrom chain. Returns the flattened version. */
function resolveVersion(gameDir, id) {
  const seen = new Set();
  function load(curId) {
    if (seen.has(curId)) throw new Error(`Circular inheritance at ${curId}`);
    seen.add(curId);
    const v = readVersionJson(gameDir, curId);
    if (v.inheritsFrom) {
      const parent = load(v.inheritsFrom);
      return mergeVersions(parent, v);
    }
    return v;
  }
  return load(id);
}

/** Merge child over parent (Fabric-style inheritance). */
function mergeVersions(parent, child) {
  const out = { ...parent, ...child };
  out.libraries = [...(parent.libraries || []), ...(child.libraries || [])];
  // Arguments: modern format is { game: [...], jvm: [...] }
  if (parent.arguments || child.arguments) {
    out.arguments = {
      game: [...((parent.arguments && parent.arguments.game) || []), ...((child.arguments && child.arguments.game) || [])],
      jvm:  [...((parent.arguments && parent.arguments.jvm)  || []), ...((child.arguments && child.arguments.jvm)  || [])],
    };
  }
  // Older `minecraftArguments` is a flat string; child overrides if present
  if (child.minecraftArguments) out.minecraftArguments = child.minecraftArguments;
  return out;
}

// ---- rule evaluation (os + features) ----

function currentOsName() {
  if (process.platform === 'win32') return 'windows';
  if (process.platform === 'darwin') return 'osx';
  return 'linux';
}

function currentOsArch() {
  return process.arch === 'ia32' ? 'x86' : process.arch;
}

function evaluateRules(rules, features = {}) {
  if (!rules || rules.length === 0) return true;
  let allow = false;
  for (const rule of rules) {
    const matches = matchRule(rule, features);
    if (rule.action === 'allow') { if (matches) allow = true; }
    else if (rule.action === 'disallow') { if (matches) allow = false; }
  }
  return allow;
}

function matchRule(rule, features) {
  if (rule.os) {
    if (rule.os.name && rule.os.name !== currentOsName()) return false;
    if (rule.os.arch && rule.os.arch !== currentOsArch()) return false;
    if (rule.os.version) {
      try {
        const re = new RegExp(rule.os.version);
        if (!re.test(require('os').release())) return false;
      } catch (_) { /* bad regex = no match */ return false; }
    }
  }
  if (rule.features) {
    for (const [k, v] of Object.entries(rule.features)) {
      if (features[k] !== v) return false;
    }
  }
  return true;
}

// ---- classpath + args ----

function libraryPath(gameDir, lib) {
  // Modern entries have `downloads.artifact.path`
  if (lib.downloads && lib.downloads.artifact && lib.downloads.artifact.path) {
    return path.join(gameDir, 'libraries', lib.downloads.artifact.path);
  }
  // Fallback: derive from Maven-style name "group:artifact:version[:classifier]"
  if (lib.name) {
    const parts = lib.name.split(':');
    const [group, artifact, version, classifier] = parts;
    const groupPath = group.replace(/\./g, '/');
    const suffix = classifier ? `-${classifier}` : '';
    const filename = `${artifact}-${version}${suffix}.jar`;
    return path.join(gameDir, 'libraries', groupPath, artifact, version, filename);
  }
  return null;
}

function buildClasspath(gameDir, version) {
  const seen = new Set();
  const paths = [];
  for (const lib of (version.libraries || [])) {
    if (!evaluateRules(lib.rules)) continue;
    // Skip natives-only entries handled separately
    if (lib.natives) continue;
    const p = libraryPath(gameDir, lib);
    if (!p || seen.has(p)) continue;
    seen.add(p);
    if (fs.existsSync(p)) paths.push(p);
  }
  // Main client jar — Fabric/Quilt/Forge profiles inherit from the vanilla
  // version and don't ship their own jar. `inheritsFrom` survives the merge
  // (child's field overrides parent's null), so use it when present.
  const clientVersionId = version.inheritsFrom || version.id;
  const clientJar = path.join(gameDir, 'versions', clientVersionId, `${clientVersionId}.jar`);
  if (fs.existsSync(clientJar)) paths.push(clientJar);
  return paths.join(process.platform === 'win32' ? ';' : ':');
}

function nativesDir(gameDir, version) {
  // Fabric/loader profiles inherit from vanilla; natives live under the
  // vanilla version directory, not the loader profile directory.
  const baseId = version.inheritsFrom || version.id;
  return path.join(gameDir, 'versions', baseId, 'natives');
}

/** Substitute ${variable} placeholders in a string. */
function substitute(str, vars) {
  return String(str).replace(/\$\{([^}]+)\}/g, (m, key) => vars[key] != null ? vars[key] : m);
}

/** Flatten the mixed-rule argument list into a plain string[] of allowed args. */
function flattenArgs(list, features) {
  const out = [];
  if (!list) return out;
  for (const item of list) {
    if (typeof item === 'string') {
      out.push(item);
    } else if (item && item.value != null) {
      if (evaluateRules(item.rules, features)) {
        if (Array.isArray(item.value)) out.push(...item.value);
        else out.push(item.value);
      }
    }
  }
  return out;
}

/**
 * Build the full command line to launch a given version.
 *
 * @param {Object} opts
 *   - gameDir       Path to .minecraft (or equivalent) — the "library root"
 *                   where versions/, libraries/, and assets/ live.
 *   - userGameDir   Optional. Where MC reads/writes per-user data (mods/,
 *                   config/, saves/, options.txt). Defaults to gameDir.
 *                   Different from gameDir when a profile is "isolated":
 *                   the launcher shares the global library root but gives
 *                   the game its own private user-data directory.
 *   - versionId     Version id under <gameDir>/versions/
 *   - auth          { accessToken, uuid, username }
 *   - javaPath      Absolute path to java executable
 *   - minRam, maxRam  GB (numbers)
 *   - resolution    { width, height, fullscreen }
 *   - extraJvmArgs  string[] (optional)
 *
 * Returns { command, args, cwd } ready for child_process.spawn.
 */
function buildLaunchCommand(opts) {
  const { gameDir, versionId, auth, javaPath, minRam, maxRam, resolution, extraJvmArgs } = opts;
  // userGameDir (where user data goes) defaults to gameDir for backwards
  // compat with linked profiles; isolated profiles override it.
  const userGameDir = opts.userGameDir || gameDir;
  const version = resolveVersion(gameDir, versionId);

  const classpath = buildClasspath(gameDir, version);
  const natives = nativesDir(gameDir, version);

  const assetsDir = path.join(gameDir, 'assets');
  const assetIndex = version.assetIndex ? version.assetIndex.id : (version.assets || 'legacy');

  // A resolution override is only active when both width AND height are valid
  // positive integers. A profile that only sets fullscreen (width/height null)
  // must not emit --width null --height null to the game process.
  const resW = resolution && typeof resolution.width  === 'number' && resolution.width  > 0 ? resolution.width  : null;
  const resH = resolution && typeof resolution.height === 'number' && resolution.height > 0 ? resolution.height : null;
  const hasCustomRes = resW != null && resH != null;

  const features = {
    has_custom_resolution: hasCustomRes,
    is_demo_user: false,
  };

  const vars = {
    auth_player_name: auth.username,
    version_name: version.id,
    // game_directory is what MC uses for mods/, config/, saves/, options.txt.
    // For isolated profiles this is the per-instance dir, NOT the global root.
    game_directory: userGameDir,
    assets_root: assetsDir,
    assets_index_name: assetIndex,
    auth_uuid: auth.uuid,
    auth_access_token: auth.accessToken,
    auth_session: `token:${auth.accessToken}:${auth.uuid}`,
    clientid: '0',
    auth_xuid: '0',
    user_type: 'msa',
    version_type: version.type || 'release',
    user_properties: '{}',
    natives_directory: natives,
    launcher_name: 'FoxLauncher',
    launcher_version: (() => { try { return require('../../package.json').version || '0.0.0'; } catch (_) { return '0.0.0'; } })(),
    classpath,
    resolution_width:  resW != null ? String(resW) : '854',
    resolution_height: resH != null ? String(resH) : '480',
    library_directory: path.join(gameDir, 'libraries'),
    classpath_separator: process.platform === 'win32' ? ';' : ':',
  };

  // JVM args
  let jvmArgs;
  if (version.arguments && version.arguments.jvm) {
    jvmArgs = flattenArgs(version.arguments.jvm, features).map(a => substitute(a, vars));
  } else {
    // Legacy fallback (pre-1.13 format)
    jvmArgs = [
      `-Djava.library.path=${natives}`,
      '-cp', classpath,
    ];
  }

  // Game args
  let gameArgs;
  if (version.arguments && version.arguments.game) {
    gameArgs = flattenArgs(version.arguments.game, features).map(a => substitute(a, vars));
  } else if (version.minecraftArguments) {
    gameArgs = version.minecraftArguments.split(/\s+/).map(a => substitute(a, vars));
  } else {
    gameArgs = [];
  }

  // Add resolution if requested and not already present
  if (hasCustomRes && !gameArgs.includes('--width')) {
    gameArgs.push('--width', String(resW), '--height', String(resH));
  }
  if (resolution && resolution.fullscreen && !gameArgs.includes('--fullscreen')) {
    gameArgs.push('--fullscreen');
  }

  // Auto-join a server on launch. The old `--server <host>` / `--port <n>`
  // flags were REMOVED in Minecraft 1.20 (snapshot 23w14a) and are silently
  // ignored on modern versions — which is why profile auto-join did nothing.
  // The replacement is Quick Play: `--quickPlayMultiplayer <host[:port]>`.
  // (Confirmed against 26.1.2's net.minecraft.client.main.Main option parser,
  // which exposes quickPlayMultiplayer/Singleplayer/Realms and no server/port.)
  if (opts.serverHost && !gameArgs.includes('--quickPlayMultiplayer')) {
    const addr = opts.serverPort
      ? `${opts.serverHost}:${opts.serverPort}`
      : String(opts.serverHost);
    gameArgs.push('--quickPlayMultiplayer', addr);
  }

  // Memory
  const memArgs = [
    `-Xms${Math.max(1, minRam)}G`,
    `-Xmx${Math.max(1, maxRam)}G`,
  ];

  const allJvm = [
    ...memArgs,
    '-XX:+UnlockExperimentalVMOptions',
    '-XX:+UseG1GC',
    '-XX:G1NewSizePercent=20',
    '-XX:G1ReservePercent=20',
    '-XX:MaxGCPauseMillis=50',
    '-XX:G1HeapRegionSize=32M',
    ...(extraJvmArgs || []),
    ...jvmArgs,
  ];

  const mainClass = version.mainClass || 'net.minecraft.client.main.Main';
  const args = [...allJvm, mainClass, ...gameArgs];

  return { command: javaPath, args, cwd: userGameDir };
}

/**
 * Locate the Fabric loader profile for a given MC version, e.g. for
 * `1.21.11` returns the newest `fabric-loader-X.Y.Z-1.21.11` install in
 * <gameDir>/versions/. Returns null if none. The launcher uses this to
 * pin which profile actually gets launched (the Kitsune client only runs
 * under Fabric on the targeted MC version).
 */
function findFabricProfile(gameDir, mcVersion) {
  const ids = listVersions(gameDir);
  const suffix = '-' + mcVersion;
  const candidates = ids.filter(id => id.startsWith('fabric-loader-') && id.endsWith(suffix));
  if (!candidates.length) return null;
  // Sort by embedded loader version descending; fabric-loader-0.18.6-1.21.11
  // comes before fabric-loader-0.16.0-1.21.11.
  candidates.sort((a, b) => {
    const va = (a.match(/fabric-loader-([^-]+)-/) || [, ''])[1];
    const vb = (b.match(/fabric-loader-([^-]+)-/) || [, ''])[1];
    return semverCompare(vb, va);
  });
  return candidates[0];
}

function semverCompare(a, b) {
  const pa = String(a).split('.').map(n => parseInt(n, 10) || 0);
  const pb = String(b).split('.').map(n => parseInt(n, 10) || 0);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] || 0) - (pb[i] || 0);
    if (d !== 0) return d;
  }
  return 0;
}

module.exports = { listVersions, listVersionsEnriched, resolveVersion, buildLaunchCommand, findFabricProfile };
