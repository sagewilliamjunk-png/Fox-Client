// World backup manager — zip a profile's saves/<world> into
// ~/.foxlauncher/backups/<profile>/ and restore/delete on demand.
//
// Backups live OUTSIDE the game directories so wiping or re-isolating an
// instance can't destroy them. The zip writer is the dependency-free one
// from modpackExport.js; extraction uses yauzl (the read-only zip dep the
// modpack importer already uses).

const fs = require('fs');
const path = require('path');
const yauzl = require('yauzl');

const paths = require('./paths');
const { gameDirForProfile } = require('./gameDirs');
const { buildZip, walk } = require('./modpackExport');

/** Separator between world name and timestamp in backup filenames. */
const SEP = '__';

function backupsDirFor(profileId) {
  const key = sanitizeName(String(profileId || 'global')) || 'global';
  return path.join(paths.backups, key);
}

/** Strip path separators and anything else hostile from a name segment.
 *  Spaces and hyphens are legal in world names and must survive. */
function sanitizeName(name) {
  return String(name || '').replace(/[\\/:*?"<>|]/g, '').trim();
}

/** Reject names that try to traverse out of their directory. */
function isSafeChildName(name) {
  return typeof name === 'string'
      && name.length > 0
      && !name.includes('/') && !name.includes('\\')
      && name !== '.' && name !== '..';
}

function dirSizeBytes(dir) {
  let total = 0;
  for (const f of walk(dir, dir)) {
    try { total += fs.statSync(f.abs).size; } catch (_) {}
  }
  return total;
}

/** List the worlds in the profile's saves/ directory. */
function listWorlds(profileId) {
  const savesDir = path.join(gameDirForProfile(profileId), 'saves');
  let entries = [];
  try { entries = fs.readdirSync(savesDir, { withFileTypes: true }); }
  catch (_) { return { ok: true, worlds: [] }; }

  const worlds = [];
  for (const ent of entries) {
    if (!ent.isDirectory()) continue;
    const dir = path.join(savesDir, ent.name);
    // A world dir has a level.dat; skip stray folders.
    if (!fs.existsSync(path.join(dir, 'level.dat'))) continue;
    let mtime = 0;
    try { mtime = fs.statSync(path.join(dir, 'level.dat')).mtimeMs; } catch (_) {}
    worlds.push({ name: ent.name, lastPlayedMs: Math.round(mtime), sizeBytes: dirSizeBytes(dir) });
  }
  worlds.sort((a, b) => b.lastPlayedMs - a.lastPlayedMs);
  return { ok: true, worlds };
}

/** List existing backups for a profile, newest first. */
function listBackups(profileId) {
  const dir = backupsDirFor(profileId);
  let files = [];
  try { files = fs.readdirSync(dir).filter(f => f.endsWith('.zip')); }
  catch (_) { return { ok: true, backups: [] }; }

  const backups = [];
  for (const f of files) {
    const full = path.join(dir, f);
    let stat;
    try { stat = fs.statSync(full); } catch (_) { continue; }
    const stem = f.slice(0, -4);
    const sepIdx = stem.lastIndexOf(SEP);
    const world = sepIdx > 0 ? stem.slice(0, sepIdx) : stem;
    backups.push({
      file: f,
      world,
      createdMs: Math.round(stat.mtimeMs),
      sizeBytes: stat.size,
    });
  }
  backups.sort((a, b) => b.createdMs - a.createdMs);
  return { ok: true, backups };
}

/** Zip saves/<worldName> into the profile's backup dir. */
function createBackup(profileId, worldName) {
  if (!isSafeChildName(worldName)) return { ok: false, error: 'Invalid world name' };
  const savesDir = path.join(gameDirForProfile(profileId), 'saves');
  const worldDir = path.join(savesDir, worldName);
  if (!fs.existsSync(path.join(worldDir, 'level.dat'))) {
    return { ok: false, error: 'World not found (no level.dat)' };
  }

  const files = walk(worldDir, worldDir);
  if (!files.length) return { ok: false, error: 'World folder is empty' };

  const entries = [];
  for (const f of files) {
    // session.lock is held open while the world is loaded and isn't needed
    // for a restore.
    if (f.rel === 'session.lock') continue;
    try { entries.push({ name: f.rel, data: fs.readFileSync(f.abs) }); }
    catch (e) {
      if (/EBUSY|EPERM/.test(String(e.code))) {
        return { ok: false, error: `World file locked (${f.rel}) — close the world before backing up.` };
      }
      return { ok: false, error: `Read failed for ${f.rel}: ${e.message}` };
    }
  }

  let zipBuf;
  try { zipBuf = buildZip(entries); }
  catch (e) { return { ok: false, error: 'ZIP build failed: ' + e.message }; }

  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  const file = `${sanitizeName(worldName)}${SEP}${stamp}.zip`;
  const dir = backupsDirFor(profileId);
  try {
    fs.mkdirSync(dir, { recursive: true });
    const target = path.join(dir, file);
    fs.writeFileSync(target + '.tmp', zipBuf);
    fs.renameSync(target + '.tmp', target);
  } catch (e) {
    return { ok: false, error: 'Write failed: ' + e.message };
  }
  return { ok: true, file, sizeBytes: zipBuf.length, fileCount: entries.length };
}

/** Extract a backup zip back into saves/<world>. */
function restoreBackup(profileId, fileName, opts = {}) {
  if (!isSafeChildName(fileName) || !fileName.endsWith('.zip')) {
    return Promise.resolve({ ok: false, error: 'Invalid backup file name' });
  }
  const zipPath = path.join(backupsDirFor(profileId), fileName);
  if (!fs.existsSync(zipPath)) {
    return Promise.resolve({ ok: false, error: 'Backup not found' });
  }

  const stem = fileName.slice(0, -4);
  const sepIdx = stem.lastIndexOf(SEP);
  const world = sepIdx > 0 ? stem.slice(0, sepIdx) : stem;
  const destName = opts.asName ? sanitizeName(opts.asName) : world;
  if (!isSafeChildName(destName)) {
    return Promise.resolve({ ok: false, error: 'Invalid destination world name' });
  }
  const destDir = path.join(gameDirForProfile(profileId), 'saves', destName);
  if (fs.existsSync(destDir)) {
    if (!opts.overwrite) {
      return Promise.resolve({ ok: false, error: 'exists', world: destName });
    }
    // Overwrite means REPLACE, not merge — extracting over a live world
    // would leave stale region/entity files from the newer state mixed into
    // the restored snapshot, which corrupts the world subtly. Refuse while
    // the world is open (session.lock held) rather than half-delete it.
    try {
      fs.rmSync(destDir, { recursive: true, force: true });
    } catch (e) {
      return Promise.resolve({ ok: false, error: `Couldn't replace existing world (is it open?): ${e.message}` });
    }
  }

  return new Promise((resolve) => {
    yauzl.open(zipPath, { lazyEntries: true, autoClose: true }, (err, zip) => {
      if (err) return resolve({ ok: false, error: err.message });
      let count = 0;
      zip.on('entry', (entry) => {
        if (/\/$/.test(entry.fileName)) { zip.readEntry(); return; }
        // ZIP-slip guard: the resolved path must stay inside destDir.
        const target = path.resolve(destDir, entry.fileName);
        if (target !== path.resolve(destDir) && !target.startsWith(path.resolve(destDir) + path.sep)) {
          zip.close();
          return resolve({ ok: false, error: `Refusing to write outside the world folder: ${entry.fileName}` });
        }
        zip.openReadStream(entry, (err2, stream) => {
          if (err2) { zip.close(); return resolve({ ok: false, error: err2.message }); }
          const chunks = [];
          stream.on('data', c => chunks.push(c));
          stream.on('end', () => {
            try {
              fs.mkdirSync(path.dirname(target), { recursive: true });
              fs.writeFileSync(target, Buffer.concat(chunks));
              count++;
            } catch (e) {
              zip.close();
              return resolve({ ok: false, error: `Write failed for ${entry.fileName}: ${e.message}` });
            }
            zip.readEntry();
          });
          stream.on('error', e => { zip.close(); resolve({ ok: false, error: e.message }); });
        });
      });
      zip.on('end', () => resolve({ ok: true, world: destName, fileCount: count }));
      zip.on('error', e => resolve({ ok: false, error: e.message }));
      zip.readEntry();
    });
  });
}

/** Delete a backup zip. */
function deleteBackup(profileId, fileName) {
  if (!isSafeChildName(fileName) || !fileName.endsWith('.zip')) {
    return { ok: false, error: 'Invalid backup file name' };
  }
  const full = path.join(backupsDirFor(profileId), fileName);
  try {
    fs.unlinkSync(full);
    return { ok: true };
  } catch (e) {
    return { ok: false, error: e.message };
  }
}

module.exports = {
  listWorlds,
  listBackups,
  createBackup,
  restoreBackup,
  deleteBackup,
  // exported for tests
  sanitizeName,
  isSafeChildName,
  backupsDirFor,
};
