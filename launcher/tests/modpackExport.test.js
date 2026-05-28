// Tests for modpackExport.js — the built-in ZIP writer + the .mrpack exporter.
// Uses a real temp game directory and round-trips the output through yauzl
// (the same reader modpackImport.js uses) to prove the archive is valid.

const fs    = require('fs');
const os    = require('os');
const path  = require('path');
const zlib  = require('zlib');
const yauzl = require('yauzl');

const { exportMrpack, buildZip, crc32, detectLoaderVersion } =
  require('../src/main/modpackExport');

let tmpDir;

beforeEach(() => {
  tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'fox-export-'));
});
afterEach(() => {
  try { fs.rmSync(tmpDir, { recursive: true, force: true }); } catch (_) {}
});

/** Read a zip buffer into a { name: Buffer } map using yauzl. */
function readZip(buf) {
  // yauzl needs a file path or a random-access reader; write to a temp file.
  const p = path.join(tmpDir, '_read.zip');
  fs.writeFileSync(p, buf);
  return new Promise((resolve, reject) => {
    const out = {};
    yauzl.open(p, { lazyEntries: true }, (err, zip) => {
      if (err) return reject(err);
      zip.on('entry', (entry) => {
        if (/\/$/.test(entry.fileName)) { zip.readEntry(); return; }
        zip.openReadStream(entry, (e2, stream) => {
          if (e2) return reject(e2);
          const chunks = [];
          stream.on('data', c => chunks.push(c));
          stream.on('end', () => { out[entry.fileName] = Buffer.concat(chunks); zip.readEntry(); });
          stream.on('error', reject);
        });
      });
      zip.on('end', () => resolve(out));
      zip.on('error', reject);
      zip.readEntry();
    });
  });
}

describe('crc32', () => {
  test('matches a known reference value', () => {
    // CRC-32 of the ASCII string "123456789" is 0xCBF43926.
    expect(crc32(Buffer.from('123456789'))).toBe(0xCBF43926);
  });
});

describe('buildZip', () => {
  test('round-trips store + deflate + empty entries byte-for-byte', async () => {
    const compressible = Buffer.from('hello '.repeat(1000));        // deflates well
    const incompressible = zlib.gzipSync(Buffer.from('x'.repeat(4000))); // already compressed
    const entries = [
      { name: 'a.txt', data: compressible },
      { name: 'dir/b.bin', data: incompressible },
      { name: 'empty', data: Buffer.alloc(0) },
    ];
    const out = await readZip(buildZip(entries));
    expect(Object.keys(out).sort()).toEqual(['a.txt', 'dir/b.bin', 'empty']);
    expect(Buffer.compare(out['a.txt'], compressible)).toBe(0);
    expect(Buffer.compare(out['dir/b.bin'], incompressible)).toBe(0);
    expect(out['empty'].length).toBe(0);
  });
});

describe('detectLoaderVersion', () => {
  test('parses fabric-loader version from versions dir', () => {
    fs.mkdirSync(path.join(tmpDir, 'versions', 'fabric-loader-0.18.6-26.1.2'), { recursive: true });
    expect(detectLoaderVersion(tmpDir, '26.1.2')).toBe('0.18.6');
  });
  test('returns null when no fabric profile present', () => {
    fs.mkdirSync(path.join(tmpDir, 'versions'), { recursive: true });
    expect(detectLoaderVersion(tmpDir, '26.1.2')).toBeNull();
  });
});

describe('exportMrpack', () => {
  function seedGameDir() {
    fs.mkdirSync(path.join(tmpDir, 'mods'), { recursive: true });
    fs.mkdirSync(path.join(tmpDir, 'config', 'sub'), { recursive: true });
    fs.mkdirSync(path.join(tmpDir, 'resourcepacks'), { recursive: true });
    fs.writeFileSync(path.join(tmpDir, 'mods', 'sodium.jar'), Buffer.from('JAR1'));
    fs.writeFileSync(path.join(tmpDir, 'mods', 'xaero.jar'), Buffer.from('JAR2'));
    fs.writeFileSync(path.join(tmpDir, 'mods', 'disabled.jar.disabled'), Buffer.from('OFF'));
    fs.writeFileSync(path.join(tmpDir, 'config', 'sodium.json'), '{"quality":"high"}');
    fs.writeFileSync(path.join(tmpDir, 'config', 'sub', 'nested.txt'), 'nested');
    fs.writeFileSync(path.join(tmpDir, 'resourcepacks', 'pack.zip'), Buffer.from('RP'));
  }

  test('bundles mods + config into a valid .mrpack with a correct manifest', async () => {
    seedGameDir();
    const dest = path.join(tmpDir, 'out', 'MyPack.mrpack');
    const res = await exportMrpack({
      gameDir: tmpDir, destPath: dest, name: 'My Pack', versionId: '2.1.0', mcVersion: '26.1.2',
    });

    expect(res.ok).toBe(true);
    expect(res.modCount).toBe(2);        // .jar.disabled excluded
    expect(res.configCount).toBe(2);     // includes nested
    expect(res.packCount).toBe(0);       // includePacks not set
    expect(fs.existsSync(dest)).toBe(true);

    const out = await readZip(fs.readFileSync(dest));
    expect(out['modrinth.index.json']).toBeDefined();
    expect(out['overrides/mods/sodium.jar']).toBeDefined();
    expect(out['overrides/mods/xaero.jar']).toBeDefined();
    expect(out['overrides/config/sodium.json']).toBeDefined();
    expect(out['overrides/config/sub/nested.txt']).toBeDefined();
    // Disabled jar must not be bundled.
    expect(out['overrides/mods/disabled.jar.disabled']).toBeUndefined();

    const idx = JSON.parse(out['modrinth.index.json'].toString('utf8'));
    expect(idx.formatVersion).toBe(1);
    expect(idx.game).toBe('minecraft');
    expect(idx.name).toBe('My Pack');
    expect(idx.versionId).toBe('2.1.0');
    expect(idx.dependencies.minecraft).toBe('26.1.2');
    expect(Array.isArray(idx.files)).toBe(true);
    expect(idx.files.length).toBe(0); // everything ships as overrides
  });

  test('includePacks bundles resource/shader packs too', async () => {
    seedGameDir();
    const dest = path.join(tmpDir, 'out2.mrpack');
    const res = await exportMrpack({
      gameDir: tmpDir, destPath: dest, name: 'P', mcVersion: '26.1.2', includePacks: true,
    });
    expect(res.ok).toBe(true);
    expect(res.packCount).toBe(1);
    const out = await readZip(fs.readFileSync(dest));
    expect(out['overrides/resourcepacks/pack.zip']).toBeDefined();
  });

  test('fails cleanly when the game dir has nothing to export', async () => {
    fs.mkdirSync(path.join(tmpDir, 'mods'), { recursive: true }); // empty
    const res = await exportMrpack({
      gameDir: tmpDir, destPath: path.join(tmpDir, 'x.mrpack'), name: 'Empty', mcVersion: '26.1.2',
    });
    expect(res.ok).toBe(false);
    expect(res.error).toMatch(/nothing to export/i);
  });

  test('fails cleanly when the game dir does not exist', async () => {
    const res = await exportMrpack({
      gameDir: path.join(tmpDir, 'nope'), destPath: path.join(tmpDir, 'x.mrpack'),
      name: 'X', mcVersion: '26.1.2',
    });
    expect(res.ok).toBe(false);
    expect(res.error).toMatch(/does not exist/i);
  });
});
