// Resource pack and shader pack management.
//
// Minecraft reads resource packs from <gameDir>/resourcepacks/ and shader
// packs (Iris / Optifine) from <gameDir>/shaderpacks/.  This module provides
// the same list / add / delete / open-folder operations the mod manager uses
// in profiles.js, so the UI can treat all three pack types uniformly.
//
// Unlike mods, packs are not toggled at launch-time — Minecraft manages the
// active pack stack inside options.txt itself.  The launcher is only
// responsible for getting the .zip files into the right folder.

const fs   = require('fs');
const path = require('path');

const PACK_DIRS = {
  resourcepacks: 'resourcepacks',
  shaders:       'shaderpacks',
};

/**
 * Return the absolute path to the pack sub-directory for the given type.
 * @param {string} gameDir  resolved game directory (never empty here)
 * @param {'resourcepacks'|'shaders'} type
 */
function packDir(gameDir, type) {
  const sub = PACK_DIRS[type];
  if (!sub) throw new Error(`Unknown pack type: ${type}`);
  return path.join(gameDir, sub);
}

/**
 * List all packs in the given directory.
 * Returns an array of  { name, sizeBytes }  objects sorted alphabetically.
 * Silently returns [] if the directory doesn't exist yet.
 *
 * @param {string} gameDir
 * @param {'resourcepacks'|'shaders'} type
 * @returns {{ name: string, sizeBytes: number }[]}
 */
function listPacks(gameDir, type) {
  const dir = packDir(gameDir, type);
  if (!fs.existsSync(dir)) return [];
  let entries;
  try { entries = fs.readdirSync(dir); }
  catch (_) { return []; }

  const result = [];
  for (const entry of entries) {
    // Accept .zip (resource packs, most shader packs) and .jar (some shaders).
    if (!/\.(zip|jar)$/i.test(entry)) continue;
    const full = path.join(dir, entry);
    let sizeBytes = 0;
    try { sizeBytes = fs.statSync(full).size; } catch (_) {}
    result.push({ name: entry, sizeBytes });
  }
  result.sort((a, b) => a.name.localeCompare(b.name));
  return result;
}

/**
 * Copy one or more source files into the pack folder.
 * Skips files whose basenames already exist.
 *
 * @param {string}   gameDir
 * @param {'resourcepacks'|'shaders'} type
 * @param {string[]} srcPaths  absolute source paths (from the OS file picker)
 * @returns {{ ok: boolean, added: string[], skipped: { name: string, reason: string }[] }}
 */
function addPacks(gameDir, type, srcPaths) {
  const dir = packDir(gameDir, type);
  try { fs.mkdirSync(dir, { recursive: true }); }
  catch (err) { return { ok: false, added: [], skipped: [], error: `Couldn't create ${type} directory: ${err.message}` }; }

  const added   = [];
  const skipped = [];
  for (const src of srcPaths) {
    const baseName = path.basename(src);
    const target   = path.join(dir, baseName);
    if (fs.existsSync(target)) {
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
}

/**
 * Permanently delete a pack file.  Path-traversal safe — resolves the target
 * and checks it's inside the pack directory before touching the filesystem.
 *
 * @param {string} gameDir
 * @param {'resourcepacks'|'shaders'} type
 * @param {string} baseName  filename only (no slashes)
 * @returns {{ ok: boolean, error?: string }}
 */
function deletePack(gameDir, type, baseName) {
  if (typeof baseName !== 'string' || baseName.includes('/') || baseName.includes('\\')) {
    return { ok: false, error: 'Invalid pack name' };
  }
  const dir      = path.resolve(packDir(gameDir, type));
  const target   = path.resolve(path.join(dir, baseName));
  if (!target.startsWith(dir + path.sep)) {
    return { ok: false, error: 'Path traversal detected' };
  }
  if (!fs.existsSync(target)) return { ok: false, error: 'Not found' };
  try {
    fs.unlinkSync(target);
    return { ok: true };
  } catch (err) {
    return { ok: false, error: err.message };
  }
}

module.exports = { listPacks, addPacks, deletePack, packDir };
