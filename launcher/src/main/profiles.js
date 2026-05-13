// Profile storage + per-profile launch overrides.
//
// Each profile is a small JSON record:
//   {
//     id, name, notes,
//     disabledMods:  ["mod-a.jar", ...],   // matched against base file name
//     ramMin, ramMax,                       // per-profile RAM override (gb), null = use global
//     keepKitsuneEnabled,                   // when false, the Fox Client jar is also disabled
//                                           // for this profile (e.g. a "no-mods, won't-get-banned"
//                                           // profile for strict servers)
//     lastPlayedAt, lastVersion,            // stamped by launcher.js
//   }
//
// All disk I/O goes through atomic .tmp + rename so a crash mid-write can't
// corrupt the profiles file.

const fs = require('fs');
const path = require('path');
const paths = require('./paths');

const KITSUNE_JAR_PREFIX = /^kitsune-client/i;

/** Load + sanitize the entire profiles document. Always returns a usable
 *  shape — even if the file is missing or corrupted, the renderer will see
 *  a default "Default" profile rather than an error. */
function load() {
  let raw;
  try { raw = JSON.parse(fs.readFileSync(paths.profiles, 'utf8')); }
  catch (_) { raw = null; }
  const list = Array.isArray(raw && raw.profiles) ? raw.profiles : [];
  const cleaned = list.map(sanitize).filter(Boolean);
  if (!cleaned.length) {
    // New installs get an isolated default profile so the launcher's mods/
    // saves/config never pollute the user's global .minecraft folder.
    cleaned.push(sanitize({ id: 'default', name: 'Default', notes: 'Auto-created', isolated: true }));
  } else {
    // Migration: if the 'default' profile was created before isolation was the
    // standard and has never been played, silently flip it to isolated so it
    // stops sharing the global .minecraft. Once played we leave it alone —
    // the user may have saves there they want to keep.
    const def = cleaned.find(p => p.id === 'default');
    if (def && !def.isolated && !def.lastPlayedAt) {
      def.isolated = true;
    }
  }
  return { profiles: cleaned };
}

function save(doc) {
  paths.ensureAll();
  const tmp = paths.profiles + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(doc, null, 2));
  fs.renameSync(tmp, paths.profiles);
  return doc;
}

function find(id) {
  return load().profiles.find(p => p.id === id) || null;
}

function upsert(profile) {
  const cleaned = sanitize(profile);
  if (!cleaned) throw new Error('Profile must have an id and a name.');
  const doc = load();
  const idx = doc.profiles.findIndex(p => p.id === cleaned.id);
  if (idx >= 0) {
    // Merge — preserve fields the caller didn't set so partial PATCHes
    // (e.g. just renaming) don't wipe disabledMods / lastPlayedAt.
    doc.profiles[idx] = { ...doc.profiles[idx], ...cleaned };
  } else {
    doc.profiles.push(cleaned);
  }
  save(doc);
  return doc;
}

function remove(id) {
  const doc = load();
  doc.profiles = doc.profiles.filter(p => p.id !== id);
  if (!doc.profiles.length) {
    doc.profiles.push(sanitize({ id: 'default', name: 'Default' }));
  }
  save(doc);
  return doc;
}

/** Patch a single field set without rewriting the whole doc client-side.
 *  Used by launcher.js to stamp lastPlayedAt; also used by the IPC layer
 *  for "set active" toggles that only touch one field. */
function patch(id, partial) {
  const doc = load();
  const idx = doc.profiles.findIndex(p => p && p.id === id);
  if (idx < 0) return doc;
  doc.profiles[idx] = sanitize({ ...doc.profiles[idx], ...partial }) || doc.profiles[idx];
  save(doc);
  return doc;
}

// ---- mod toggling at launch ------------------------------------------

/**
 * Apply the named profile's `disabledMods` list to <gameDir>/mods/. Renames
 * `.jar` ↔ `.jar.disabled` until the actual on-disk state matches what the
 * profile says.
 *
 * The Kitsune client jar is treated specially: it's never auto-renamed by
 * `disabledMods` (the launcher just installed it); use the
 * `keepKitsuneEnabled` flag to turn it off explicitly.
 *
 * Returns: { enabled: [...], disabled: [...] } — the post-apply state, for
 * logging.
 */
function applyModToggles(gameDir, profileId) {
  const profile = find(profileId);
  if (!profile) return { enabled: [], disabled: [] };

  const modsDir = path.join(gameDir, 'mods');
  let entries;
  try { entries = fs.readdirSync(modsDir); }
  catch (_) { return { enabled: [], disabled: [] }; }

  const disabledList = new Set(profile.disabledMods || []);
  const wantsKitsune = profile.keepKitsuneEnabled !== false; // default true

  const result = { enabled: [], disabled: [] };
  for (const fname of entries) {
    const baseName = stripDisabled(fname);
    const isJar = /\.jar$/i.test(fname);
    const isDisabledJar = /\.jar\.disabled$/i.test(fname);
    if (!isJar && !isDisabledJar) continue;

    // Decide whether this mod should be enabled in this profile.
    let shouldBeEnabled;
    if (KITSUNE_JAR_PREFIX.test(baseName)) {
      shouldBeEnabled = wantsKitsune;
    } else {
      shouldBeEnabled = !disabledList.has(baseName);
    }

    const fullPath = path.join(modsDir, fname);
    const enabledPath  = path.join(modsDir, baseName);
    const disabledPath = path.join(modsDir, baseName + '.disabled');

    try {
      if (shouldBeEnabled && isDisabledJar) {
        fs.renameSync(fullPath, enabledPath);
        result.enabled.push(baseName);
      } else if (!shouldBeEnabled && isJar) {
        fs.renameSync(fullPath, disabledPath);
        result.disabled.push(baseName);
      } else if (shouldBeEnabled) {
        result.enabled.push(baseName);
      } else {
        result.disabled.push(baseName);
      }
    } catch (_) {
      // File in use or permissions — skip silently. The launch will still
      // proceed; the user can resolve manually.
    }
  }
  return result;
}

/** Scan <gameDir>/mods and return one entry per mod (each .jar or .jar.disabled).
 *  Pairs `.jar` and `.jar.disabled` referring to the same baseName so the UI
 *  shows one row, not two. */
function listMods(gameDir) {
  const modsDir = path.join(gameDir, 'mods');
  let entries;
  try { entries = fs.readdirSync(modsDir); }
  catch (_) { return []; }

  const byBase = new Map();
  for (const fname of entries) {
    const isJar = /\.jar$/i.test(fname);
    const isDisabledJar = /\.jar\.disabled$/i.test(fname);
    if (!isJar && !isDisabledJar) continue;
    const baseName = stripDisabled(fname);
    const fullPath = path.join(modsDir, fname);
    let st;
    try { st = fs.statSync(fullPath); } catch (_) { continue; }
    const cur = byBase.get(baseName) || { name: baseName, sizeBytes: 0, currentlyEnabled: false, isKitsune: false };
    cur.sizeBytes = Math.max(cur.sizeBytes, st.size);
    if (isJar) cur.currentlyEnabled = true;
    cur.isKitsune = KITSUNE_JAR_PREFIX.test(baseName);
    byBase.set(baseName, cur);
  }
  return [...byBase.values()].sort((a, b) => a.name.localeCompare(b.name));
}

// ---- internal --------------------------------------------------------

function sanitize(p) {
  if (!p || typeof p !== 'object') return null;
  const id   = String(p.id || '').toLowerCase().replace(/[^a-z0-9-]/g, '').slice(0, 48);
  if (!id) return null;
  const name = String(p.name || '').slice(0, 60).trim() || id;
  return {
    id,
    name,
    notes:              typeof p.notes === 'string' ? p.notes.slice(0, 240) : '',
    disabledMods:       Array.isArray(p.disabledMods)
                          ? p.disabledMods.filter(m => typeof m === 'string').slice(0, 200)
                          : [],
    /** Per-profile addon flags. Each id corresponds to addons.js CATALOG.
     *  When listed here, the mod sees the id in its addons.json and skips
     *  registering that module — fully inert, no tick / render / events. */
    disabledAddons:     Array.isArray(p.disabledAddons)
                          ? p.disabledAddons.filter(m => typeof m === 'string').slice(0, 50)
                          : [],
    ramMin:             intOrNull(p.ramMin, 1, 64),
    ramMax:             intOrNull(p.ramMax, 1, 64),
    keepKitsuneEnabled: typeof p.keepKitsuneEnabled === 'boolean' ? p.keepKitsuneEnabled : true,
    /** Per-profile resolution override. Null fields fall back to the global
     *  Settings → Display values at launch. */
    resolution:         sanitizeResolution(p.resolution),
    /** Extra JVM args, space-separated when persisted, parsed into an array
     *  on apply. Sized to keep accidental command-line overruns at bay. */
    jvmArgs:            typeof p.jvmArgs === 'string' ? p.jvmArgs.slice(0, 1024) : '',
    /** Optional server auto-join. Empty host = no auto-join. */
    serverHost:         typeof p.serverHost === 'string' ? p.serverHost.slice(0, 128).trim() : '',
    serverPort:         intOrNull(p.serverPort, 1, 65535),
    /** Override game directory for this profile. Worlds + configs stay
     *  separate from the user's global game dir. Empty = inherit.
     *  IGNORED when {@code isolated} is true. */
    gameDirOverride:    typeof p.gameDirOverride === 'string' ? p.gameDirOverride.slice(0, 1024).trim() : '',
    /** Isolation mode. When true, the launcher uses
     *  ~/.foxlauncher/instances/<id>/ as the gameDir AND the auth vault. This
     *  is the "totally separate identity" mode — different worlds, different
     *  mods, different Microsoft account, all without contamination. */
    isolated:           typeof p.isolated === 'boolean' ? p.isolated : false,
    /** UI accent colour override (hex) — null falls back to id-derived hash. */
    color:              typeof p.color === 'string' && /^#[0-9a-fA-F]{6}$/.test(p.color) ? p.color : null,
    /** Last Microsoft account that successfully launched this profile. Set by
     *  launcher.js after a successful auth. Used to warn about account
     *  mismatches when the user activates a profile. */
    accountUsername:    typeof p.accountUsername === 'string' ? p.accountUsername.slice(0, 64) : null,
    accountUuid:        typeof p.accountUuid === 'string' ? p.accountUuid.slice(0, 36) : null,
    /** Locked profiles can't be edited or deleted from the UI without first
     *  unlocking. Prevents fat-finger nukes of a finely tuned setup. */
    locked:             typeof p.locked === 'boolean' ? p.locked : false,
    /** Override the Minecraft version for this profile. null = use the
     *  launcher's built-in target version (TARGET_MC_VERSION). Setting this
     *  to e.g. "1.20.4" lets the user launch any installed vanilla version;
     *  the Fox Client jar is skipped automatically if it was built for a
     *  different version. */
    mcVersion:          typeof p.mcVersion === 'string' && /^\d+\.\d+/.test(p.mcVersion)
                          ? p.mcVersion.slice(0, 16) : null,
    /** Template id used at creation. Informational — never read by the
     *  launcher; lets the UI show "Created from: Ranked PvP" etc. */
    templateId:         typeof p.templateId === 'string' ? p.templateId.slice(0, 32) : null,
    createdAt:          typeof p.createdAt === 'number' ? p.createdAt : Date.now(),
    lastPlayedAt:       typeof p.lastPlayedAt === 'number' ? p.lastPlayedAt : 0,
    lastVersion:        typeof p.lastVersion === 'string' ? p.lastVersion : '',
  };
}

function sanitizeResolution(r) {
  if (!r || typeof r !== 'object') return null;
  const w = intOrNull(r.width, 320, 7680);
  const h = intOrNull(r.height, 240, 4320);
  if (w == null && h == null && typeof r.fullscreen !== 'boolean') return null;
  return {
    width: w,
    height: h,
    fullscreen: typeof r.fullscreen === 'boolean' ? r.fullscreen : null,
  };
}

function intOrNull(v, lo, hi) {
  if (v == null || v === '') return null;
  const n = Math.floor(Number(v));
  if (!Number.isFinite(n)) return null;
  return Math.max(lo, Math.min(hi, n));
}

function stripDisabled(fname) {
  return fname.replace(/\.disabled$/i, '');
}

/** Clone an existing profile under a new id + name. Useful for "duplicate
 *  this and tweak one thing." Skips lastPlayedAt / lastVersion so the clone
 *  starts as if newly created. */
function clone(sourceId, opts = {}) {
  const src = find(sourceId);
  if (!src) throw new Error(`No profile to clone: ${sourceId}`);
  const baseId = (opts.id || `${sourceId}-copy`).toLowerCase().replace(/[^a-z0-9-]/g, '');
  // Make sure id is unique.
  let id = baseId;
  let n = 2;
  const existingIds = new Set(load().profiles.map(p => p.id));
  while (existingIds.has(id)) { id = `${baseId}-${n++}`; }
  const copy = {
    ...src,
    id,
    name: opts.name || `${src.name} (copy)`,
    lastPlayedAt: 0,
    lastVersion:  '',
  };
  upsert(copy);
  return copy;
}

/** Build a portable JSON for one profile. Strips runtime fields so the
 *  imported copy on another machine starts fresh. */
function exportOne(id) {
  const p = find(id);
  if (!p) return null;
  const { lastPlayedAt, lastVersion, ...portable } = p;
  return {
    schema: 'fox-launcher-profile',
    version: 1,
    profile: portable,
    exportedAt: Date.now(),
  };
}

/** Import a profile JSON produced by exportOne. Renames if id collides. */
function importOne(payload) {
  if (!payload || payload.schema !== 'fox-launcher-profile' || !payload.profile) {
    throw new Error('Not a Fox Launcher profile export.');
  }
  const incoming = payload.profile;
  const existingIds = new Set(load().profiles.map(p => p.id));
  let id = String(incoming.id || '').toLowerCase().replace(/[^a-z0-9-]/g, '') || `imported-${Date.now().toString(36)}`;
  if (existingIds.has(id)) {
    let n = 2;
    while (existingIds.has(`${id}-${n}`)) n++;
    id = `${id}-${n}`;
  }
  const cleaned = sanitize({ ...incoming, id });
  if (!cleaned) throw new Error('Imported profile failed sanitization.');
  upsert(cleaned);
  return cleaned;
}

// ---- isolation + account binding ------------------------------------

/**
 * Flip a profile's isolation mode.
 *
 * Turning ON: creates ~/.foxlauncher/instances/<id>/ (with mods/, config/,
 * saves/, etc.) and copies the current global mods/ into it as a starter
 * set. The profile from now on uses this dir as its gameDir AND its auth
 * vault — i.e. it gets its own Microsoft account, worlds, configs, options.
 *
 * Turning OFF: just flips the flag. The instance dir is intentionally left
 * on disk so the user doesn't lose worlds/configs by accident; deletion is
 * a separate explicit action.
 */
function setIsolation(id, isolated) {
  const profile = find(id);
  if (!profile) throw new Error(`No profile: ${id}`);
  if (profile.isolated === !!isolated) return profile;

  if (isolated) {
    const instanceDir = paths.ensureInstance(id);
    const fromMods = path.join(paths.defaultMinecraft(), 'mods');
    const toMods = path.join(instanceDir, 'mods');
    // Best-effort one-time mod copy so the new instance has a working set
    // out of the gate. Skip if the source doesn't exist or destination is
    // already populated (idempotent — re-flipping won't double-copy).
    try {
      const dstEntries = fs.readdirSync(toMods).filter(f => /\.jar(\.disabled)?$/i.test(f));
      if (dstEntries.length === 0 && fs.existsSync(fromMods)) {
        for (const fname of fs.readdirSync(fromMods)) {
          if (!/\.jar(\.disabled)?$/i.test(fname)) continue;
          const src = path.join(fromMods, fname);
          const dst = path.join(toMods, fname);
          try { fs.copyFileSync(src, dst); } catch (_) {}
        }
      }
    } catch (_) { /* best effort */ }
  }
  return patch(id, { isolated: !!isolated }).profiles.find(p => p.id === id);
}

/**
 * Stamp the Microsoft account that just successfully launched this profile.
 * Called by launcher.js after auth resolves; non-destructive (only updates
 * the binding fields). The launcher's renderer uses this to show a warning
 * when the active profile expects a different account than what's signed in.
 */
function bindAccount(id, accountInfo) {
  if (!accountInfo) return null;
  return patch(id, {
    accountUsername: accountInfo.username || null,
    accountUuid:     accountInfo.uuid || null,
  });
}

// ---- templates ------------------------------------------------------

/**
 * Curated starting points for a new profile. Each template is a partial
 * Profile object; sanitize() fills in the rest. Choosing a template at
 * creation does NOT lock the profile — the user can still edit anything.
 *
 * Server hostnames are deliberately omitted to keep the templates server-
 * agnostic; users wire their own. The "vibes" each template captures:
 *   - Anarchy:    long-haul anarchy (2b2t-style). Isolated, modest RAM,
 *                 every gray-zone safety setting on, vanilla-respecting.
 *   - Ranked PvP: tournament-style. Isolated, fullscreen, gray-zone OFF,
 *                 keystrokes/CPS visible, low-latency JVM args.
 *   - Casual:    your normal play. Linked to global, conservative defaults.
 *   - Modded:    big modded packs. Isolated, lots of RAM.
 *   - Blank:     empty (current behavior).
 */
const TEMPLATES = {
  anarchy: {
    label: 'Anarchy / 2b2t',
    description: 'Long-haul anarchy: isolated dir, low RAM, gray-zone modules ENABLED so you can survive.',
    apply: () => ({
      isolated: true,
      ramMin: 4, ramMax: 6,
      disabledAddons: [],
      keepKitsuneEnabled: true,
      jvmArgs: '-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=100',
    }),
  },
  ranked: {
    label: 'Ranked PvP',
    description: 'Tournament-style: isolated, fullscreen, vanilla-safe addons, low-latency tuning.',
    apply: () => ({
      isolated: true,
      ramMin: 6, ramMax: 8,
      // Disable every gray-zone addon for tournament safety. Ids match
      // addons.js CATALOG.GRAYZONE_* constants — anything else is harmless.
      disabledAddons: ['grayzone.reach_hud', 'grayzone.hitboxes', 'grayzone.anti_afk', 'grayzone.free_look'],
      keepKitsuneEnabled: true,
      resolution: { width: null, height: null, fullscreen: true },
      jvmArgs: '-XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -XX:+UseStringDeduplication',
    }),
  },
  casual: {
    label: 'Casual',
    description: 'Shares your global .minecraft. Default settings. Good for survival worlds.',
    apply: () => ({
      isolated: false,
      ramMin: null, ramMax: null,
      disabledAddons: [],
      keepKitsuneEnabled: true,
    }),
  },
  modded: {
    label: 'Modded',
    description: 'Isolated dir, large RAM allocation. For heavy modded packs that would crush your other profiles.',
    apply: () => ({
      isolated: true,
      ramMin: 8, ramMax: 12,
      keepKitsuneEnabled: false, // Most modded packs don't expect a client-mod
    }),
  },
  vanilla_safe: {
    label: 'Vanilla-safe',
    description: 'Linked dir, Fox Client jar disabled, every addon off. For strict servers that scan for clients.',
    apply: () => ({
      isolated: false,
      keepKitsuneEnabled: false,
      disabledAddons: ['grayzone.reach_hud', 'grayzone.hitboxes', 'grayzone.anti_afk', 'grayzone.free_look'],
    }),
  },
  blank: {
    label: 'Blank',
    description: 'Empty profile, configure everything yourself.',
    apply: () => ({}),
  },
};

function templates() {
  return Object.entries(TEMPLATES).map(([id, t]) => ({
    id, label: t.label, description: t.description,
  }));
}

/**
 * Apply a template's defaults to an existing profile. Only fields the
 * template actually sets are touched — others are left alone, so an existing
 * name / notes / disabledMods list survive the apply.
 */
function applyTemplate(id, templateId) {
  const tpl = TEMPLATES[templateId];
  if (!tpl) throw new Error(`No template: ${templateId}`);
  const partial = { ...tpl.apply(), templateId };
  // If the template wants isolation, materialize the instance dir + copy mods.
  if (partial.isolated) setIsolation(id, true);
  return patch(id, partial);
}

module.exports = {
  load, save, find, upsert, remove, patch,
  applyModToggles, listMods,
  clone, exportOne, importOne,
  setIsolation, bindAccount, applyTemplate, templates,
};
