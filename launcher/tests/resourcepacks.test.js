// Tests for resourcepacks.js — list/add/delete with a real temp directory,
// with special attention to the deletePack path-traversal guard (locking in
// the behaviour so a refactor can't silently drop it).

const fs   = require('fs');
const os   = require('os');
const path = require('path');

const resourcepacks = require('../src/main/resourcepacks');

let tmpDir;

beforeEach(() => {
  tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'fox-packs-test-'));
});

afterEach(() => {
  fs.rmSync(tmpDir, { recursive: true, force: true });
});

function seed(type, names) {
  const dir = path.join(tmpDir, type === 'shaders' ? 'shaderpacks' : 'resourcepacks');
  fs.mkdirSync(dir, { recursive: true });
  for (const n of names) fs.writeFileSync(path.join(dir, n), 'pack-bytes');
  return dir;
}

describe('packDir', () => {
  it('maps types to the right subfolders', () => {
    expect(resourcepacks.packDir(tmpDir, 'resourcepacks')).toBe(path.join(tmpDir, 'resourcepacks'));
    expect(resourcepacks.packDir(tmpDir, 'shaders')).toBe(path.join(tmpDir, 'shaderpacks'));
  });
  it('throws on unknown type', () => {
    expect(() => resourcepacks.packDir(tmpDir, 'mods')).toThrow(/Unknown pack type/);
  });
});

describe('listPacks', () => {
  it('returns [] when the directory does not exist', () => {
    expect(resourcepacks.listPacks(tmpDir, 'resourcepacks')).toEqual([]);
  });
  it('lists only .zip/.jar files, sorted, with sizes', () => {
    seed('resourcepacks', ['b.zip', 'a.zip', 'shader.jar', 'readme.txt']);
    const out = resourcepacks.listPacks(tmpDir, 'resourcepacks');
    expect(out.map(p => p.name)).toEqual(['a.zip', 'b.zip', 'shader.jar']);
    for (const p of out) expect(p.sizeBytes).toBeGreaterThan(0);
  });
});

describe('addPacks', () => {
  it('copies new files and skips ones already present', () => {
    const dir = seed('shaders', ['existing.zip']);
    const src = path.join(tmpDir, 'incoming.zip');
    const dup = path.join(tmpDir, 'existing.zip');
    fs.writeFileSync(src, 'new');
    fs.writeFileSync(dup, 'dup');

    const r = resourcepacks.addPacks(tmpDir, 'shaders', [src, dup]);
    expect(r.ok).toBe(true);
    expect(r.added).toEqual(['incoming.zip']);
    expect(r.skipped).toEqual([{ name: 'existing.zip', reason: 'already-present' }]);
    expect(fs.existsSync(path.join(dir, 'incoming.zip'))).toBe(true);
  });
});

describe('deletePack — traversal guard', () => {
  it('deletes a pack by basename', () => {
    const dir = seed('resourcepacks', ['doomed.zip']);
    const r = resourcepacks.deletePack(tmpDir, 'resourcepacks', 'doomed.zip');
    expect(r.ok).toBe(true);
    expect(fs.existsSync(path.join(dir, 'doomed.zip'))).toBe(false);
  });

  it.each([
    '../evil.zip',
    '..\\evil.zip',
    'sub/evil.zip',
    'sub\\evil.zip',
    '/abs/evil.zip',
    'C:\\Windows\\evil.zip',
  ])('rejects names containing separators: %s', (name) => {
    seed('resourcepacks', ['safe.zip']);
    // Plant a victim file one level up to prove nothing escapes the pack dir.
    const victim = path.join(tmpDir, 'evil.zip');
    fs.writeFileSync(victim, 'do not delete');

    const r = resourcepacks.deletePack(tmpDir, 'resourcepacks', name);
    expect(r.ok).toBe(false);
    expect(fs.existsSync(victim)).toBe(true);
  });

  it('rejects non-string names', () => {
    expect(resourcepacks.deletePack(tmpDir, 'resourcepacks', null).ok).toBe(false);
    expect(resourcepacks.deletePack(tmpDir, 'resourcepacks', 42).ok).toBe(false);
  });

  it('reports not-found for missing packs without throwing', () => {
    seed('resourcepacks', []);
    const r = resourcepacks.deletePack(tmpDir, 'resourcepacks', 'ghost.zip');
    expect(r).toEqual({ ok: false, error: 'Not found' });
  });
});
