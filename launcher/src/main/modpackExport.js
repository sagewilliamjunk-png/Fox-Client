// Modrinth modpack (.mrpack) exporter — the counterpart to modpackImport.js.
//
// Bundles a profile's mods + config (and optionally resource/shader packs)
// into a shareable .mrpack file. Everything is packed into overrides/ rather
// than referenced by Modrinth CDN URL, which makes the export:
//   • fully offline (no Modrinth hash-lookup round-trips)
//   • reliable (no "this mod isn't on Modrinth" gaps)
//   • round-trippable with our own importer (which handles overrides/)
//
// The .mrpack is a plain ZIP, so we write one with a tiny built-in writer
// (Node's zlib for DEFLATE) instead of pulling in a new dependency. yauzl —
// our only zip dep — is read-only, hence this.
//
// Schema: https://docs.modrinth.com/modpacks/format

const fs   = require('fs');
const path = require('path');
const zlib = require('zlib');

// ── minimal ZIP writer ──────────────────────────────────────────────────────

const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
    t[n] = c >>> 0;
  }
  return t;
})();

function crc32(buf) {
  let c = 0xFFFFFFFF;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xFF] ^ (c >>> 8);
  return (c ^ 0xFFFFFFFF) >>> 0;
}

/**
 * Build a ZIP buffer from [{ name, data }] entries. Per entry we pick the
 * smaller of DEFLATE vs STORE — jars are already compressed, so STORE avoids
 * wasting CPU (and bytes) re-deflating them, while text configs shrink nicely.
 */
function buildZip(entries) {
  const local = [];
  const central = [];
  let offset = 0;
  const DOS_DATE = 0x21; // 1980-01-01, time 0 — deterministic timestamp

  for (const e of entries) {
    const nameBuf = Buffer.from(e.name, 'utf8');
    const crc = crc32(e.data);
    const uncompSize = e.data.length;

    let method = 0;            // 0 = store
    let body = e.data;
    if (uncompSize > 0) {
      const deflated = zlib.deflateRawSync(e.data, { level: 6 });
      if (deflated.length < uncompSize) { method = 8; body = deflated; }
    }
    const compSize = body.length;

    const lh = Buffer.alloc(30);
    lh.writeUInt32LE(0x04034b50, 0);   // local file header sig
    lh.writeUInt16LE(20, 4);           // version needed
    lh.writeUInt16LE(0x0800, 6);       // general purpose flag: bit 11 = UTF-8 names
    lh.writeUInt16LE(method, 8);
    lh.writeUInt16LE(0, 10);           // mod time
    lh.writeUInt16LE(DOS_DATE, 12);    // mod date
    lh.writeUInt32LE(crc, 14);
    lh.writeUInt32LE(compSize, 18);
    lh.writeUInt32LE(uncompSize, 22);
    lh.writeUInt16LE(nameBuf.length, 26);
    lh.writeUInt16LE(0, 28);           // extra field len
    local.push(lh, nameBuf, body);

    const ch = Buffer.alloc(46);
    ch.writeUInt32LE(0x02014b50, 0);   // central dir header sig
    ch.writeUInt16LE(20, 4);           // version made by
    ch.writeUInt16LE(20, 6);           // version needed
    ch.writeUInt16LE(0x0800, 8);       // gp flag
    ch.writeUInt16LE(method, 10);
    ch.writeUInt16LE(0, 12);           // mod time
    ch.writeUInt16LE(DOS_DATE, 14);    // mod date
    ch.writeUInt32LE(crc, 16);
    ch.writeUInt32LE(compSize, 20);
    ch.writeUInt32LE(uncompSize, 24);
    ch.writeUInt16LE(nameBuf.length, 28);
    ch.writeUInt16LE(0, 30);           // extra
    ch.writeUInt16LE(0, 32);           // comment
    ch.writeUInt16LE(0, 34);           // disk number
    ch.writeUInt16LE(0, 36);           // internal attrs
    ch.writeUInt32LE(0, 38);           // external attrs
    ch.writeUInt32LE(offset, 42);      // offset of local header
    central.push(ch, nameBuf);

    offset += lh.length + nameBuf.length + body.length;
  }

  const localBuf   = Buffer.concat(local);
  const centralBuf = Buffer.concat(central);

  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);            // end of central dir sig
  eocd.writeUInt16LE(0, 4);                     // disk number
  eocd.writeUInt16LE(0, 6);                     // central dir start disk
  eocd.writeUInt16LE(entries.length, 8);        // entries on this disk
  eocd.writeUInt16LE(entries.length, 10);       // total entries
  eocd.writeUInt32LE(centralBuf.length, 12);    // central dir size
  eocd.writeUInt32LE(localBuf.length, 16);      // central dir offset
  eocd.writeUInt16LE(0, 20);                    // comment length

  return Buffer.concat([localBuf, centralBuf, eocd]);
}

// ── file gathering ────────────────────────────────────────────────────────────

/** Recursively collect files under `dir`, returning [{ abs, rel }] with rel
 *  using forward slashes. Returns [] if the directory doesn't exist. */
function walk(dir, baseForRel) {
  const out = [];
  if (!fs.existsSync(dir)) return out;
  const stack = [dir];
  while (stack.length) {
    const cur = stack.pop();
    let entries;
    try { entries = fs.readdirSync(cur, { withFileTypes: true }); }
    catch (_) { continue; }
    for (const ent of entries) {
      const abs = path.join(cur, ent.name);
      if (ent.isDirectory()) {
        stack.push(abs);
      } else if (ent.isFile()) {
        const rel = path.relative(baseForRel, abs).split(path.sep).join('/');
        out.push({ abs, rel });
      }
    }
  }
  return out;
}

/** Detect the Fabric loader version installed in `gameDir` by scanning for a
 *  `versions/fabric-loader-<loader>-<mc>` directory. Returns null if none. */
function detectLoaderVersion(gameDir, mcVersion) {
  try {
    const versionsDir = path.join(gameDir, 'versions');
    if (!fs.existsSync(versionsDir)) return null;
    for (const name of fs.readdirSync(versionsDir)) {
      const m = /^fabric-loader-([0-9][^-]*(?:-[^-]+)?)-(.+)$/.exec(name);
      if (m && (!mcVersion || m[2] === mcVersion)) return m[1];
    }
  } catch (_) { /* best-effort */ }
  return null;
}

// ── public API ────────────────────────────────────────────────────────────────

/**
 * Export a profile's contents to a .mrpack file.
 *
 * @param {object} opts
 * @param {string} opts.gameDir      The profile's game/instance directory.
 * @param {string} opts.destPath     Where to write the .mrpack.
 * @param {string} opts.name         Pack name (shows in modrinth.index.json).
 * @param {string} opts.versionId    Pack version label, e.g. "1.0.0".
 * @param {string} opts.mcVersion    Target Minecraft version.
 * @param {string} [opts.summary]    Optional description.
 * @param {boolean} [opts.includePacks] Also bundle resourcepacks/ + shaderpacks/.
 * @param {function} [opts.onProgress] (msg) => void
 * @returns {Promise<{ok, path?, modCount?, configCount?, packCount?, totalBytes?, loader?, error?}>}
 */
async function exportMrpack(opts) {
  const onProgress = typeof opts.onProgress === 'function' ? opts.onProgress : () => {};
  const gameDir = opts.gameDir;
  if (!gameDir || !fs.existsSync(gameDir)) {
    return { ok: false, error: 'Game directory does not exist — launch the profile once first.' };
  }

  onProgress('Collecting mods…');
  // Only real, enabled jars. Skip `.disabled` jars and non-jar clutter.
  const modsDir = path.join(gameDir, 'mods');
  const modFiles = walk(modsDir, gameDir).filter(f => /\.jar$/i.test(f.rel));

  onProgress('Collecting config…');
  const configFiles = walk(path.join(gameDir, 'config'), gameDir);

  let packFiles = [];
  if (opts.includePacks) {
    onProgress('Collecting resource & shader packs…');
    packFiles = [
      ...walk(path.join(gameDir, 'resourcepacks'), gameDir),
      ...walk(path.join(gameDir, 'shaderpacks'), gameDir),
    ];
  }

  const allSource = [...modFiles, ...configFiles, ...packFiles];
  if (!allSource.length) {
    return { ok: false, error: 'Nothing to export — no mods or config found in this profile.' };
  }

  // Build ZIP entries. Every gathered file goes under overrides/<relpath> so a
  // Modrinth-format importer drops them straight into the instance root.
  const entries = [];
  let totalBytes = 0;
  for (const f of allSource) {
    let data;
    try { data = fs.readFileSync(f.abs); }
    catch (e) { onProgress(`  → skipped ${f.rel}: ${e.message}`); continue; }
    totalBytes += data.length;
    entries.push({ name: 'overrides/' + f.rel, data });
  }

  // modrinth.index.json — files[] is empty; everything ships as overrides.
  const loader = detectLoaderVersion(gameDir, opts.mcVersion);
  const dependencies = { minecraft: opts.mcVersion };
  if (loader) dependencies['fabric-loader'] = loader;

  const index = {
    formatVersion: 1,
    game: 'minecraft',
    versionId: opts.versionId || '1.0.0',
    name: opts.name || 'Fox Modpack',
    summary: opts.summary || `Exported from Fox Launcher on ${new Date().toISOString().slice(0, 10)}`,
    files: [],
    dependencies,
  };
  entries.unshift({
    name: 'modrinth.index.json',
    data: Buffer.from(JSON.stringify(index, null, 2), 'utf8'),
  });

  onProgress(`Packing ${entries.length - 1} file(s)…`);
  let zipBuf;
  try { zipBuf = buildZip(entries); }
  catch (e) { return { ok: false, error: 'ZIP build failed: ' + e.message }; }

  try {
    fs.mkdirSync(path.dirname(opts.destPath), { recursive: true });
    fs.writeFileSync(opts.destPath, zipBuf);
  } catch (e) {
    return { ok: false, error: 'Write failed: ' + e.message };
  }

  onProgress(`Done — ${path.basename(opts.destPath)} (${(zipBuf.length / 1048576).toFixed(1)} MB)`);
  return {
    ok: true,
    path: opts.destPath,
    modCount: modFiles.length,
    configCount: configFiles.length,
    packCount: packFiles.length,
    totalBytes,
    zipBytes: zipBuf.length,
    loader,
  };
}

module.exports = { exportMrpack, buildZip, crc32, detectLoaderVersion };
