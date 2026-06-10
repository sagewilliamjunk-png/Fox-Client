// Tests for worldBackups.js — backup/restore roundtrip in a temp dir, plus
// the name-validation guards.

const fs   = require('fs');
const os   = require('os');
const path = require('path');

let tmpDir;
let mockGameDir;
let mockBackupsRoot;

jest.mock('../src/main/gameDirs', () => ({
  gameDirForProfile: jest.fn(() => mockGameDir),
}));
jest.mock('../src/main/paths', () => ({
  get backups() { return mockBackupsRoot; },
}));

const worldBackups = require('../src/main/worldBackups');

beforeEach(() => {
  tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'fox-backups-test-'));
  mockGameDir = path.join(tmpDir, 'game');
  mockBackupsRoot = path.join(tmpDir, 'backups');
  fs.mkdirSync(mockGameDir, { recursive: true });
});

afterEach(() => {
  fs.rmSync(tmpDir, { recursive: true, force: true });
});

function seedWorld(name, files = { 'level.dat': 'nbt', 'region/r.0.0.mca': 'chunks' }) {
  const dir = path.join(mockGameDir, 'saves', name);
  for (const [rel, data] of Object.entries(files)) {
    const full = path.join(dir, rel);
    fs.mkdirSync(path.dirname(full), { recursive: true });
    fs.writeFileSync(full, data);
  }
  return dir;
}

describe('name guards', () => {
  it('isSafeChildName rejects traversal and separators', () => {
    for (const bad of ['..', '.', 'a/b', 'a\\b', '', null]) {
      expect(worldBackups.isSafeChildName(bad)).toBe(false);
    }
    expect(worldBackups.isSafeChildName('New World')).toBe(true);
  });
  it('sanitizeName keeps spaces and hyphens, strips separators', () => {
    expect(worldBackups.sanitizeName('My Cool-World')).toBe('My Cool-World');
    expect(worldBackups.sanitizeName('a/b\\c:d')).toBe('abcd');
  });
});

describe('listWorlds', () => {
  it('returns [] when there is no saves dir', () => {
    expect(worldBackups.listWorlds(null)).toEqual({ ok: true, worlds: [] });
  });
  it('lists only folders containing level.dat', () => {
    seedWorld('Real World');
    fs.mkdirSync(path.join(mockGameDir, 'saves', 'not-a-world'), { recursive: true });
    const { worlds } = worldBackups.listWorlds(null);
    expect(worlds.map(w => w.name)).toEqual(['Real World']);
    expect(worlds[0].sizeBytes).toBeGreaterThan(0);
  });
});

describe('backup → restore roundtrip', () => {
  it('zips a world, then restores it to a new name', async () => {
    seedWorld('New World');

    const b = worldBackups.createBackup(null, 'New World');
    expect(b.ok).toBe(true);
    expect(b.fileCount).toBe(2);

    const { backups } = worldBackups.listBackups(null);
    expect(backups).toHaveLength(1);
    expect(backups[0].world).toBe('New World');

    const r = await worldBackups.restoreBackup(null, backups[0].file, { asName: 'New World (restored)' });
    expect(r).toMatchObject({ ok: true, world: 'New World (restored)', fileCount: 2 });
    const restored = path.join(mockGameDir, 'saves', 'New World (restored)');
    expect(fs.readFileSync(path.join(restored, 'level.dat'), 'utf8')).toBe('nbt');
    expect(fs.readFileSync(path.join(restored, 'region', 'r.0.0.mca'), 'utf8')).toBe('chunks');
  });

  it('refuses to overwrite an existing world unless told to', async () => {
    seedWorld('New World');
    const b = worldBackups.createBackup(null, 'New World');
    const { backups } = worldBackups.listBackups(null);

    const refused = await worldBackups.restoreBackup(null, backups[0].file, {});
    expect(refused).toMatchObject({ ok: false, error: 'exists', world: 'New World' });

    const forced = await worldBackups.restoreBackup(null, backups[0].file, { overwrite: true });
    expect(forced.ok).toBe(true);
  });

  it('skips session.lock when backing up', () => {
    seedWorld('Locky', { 'level.dat': 'nbt', 'session.lock': 'lock' });
    const b = worldBackups.createBackup(null, 'Locky');
    expect(b.ok).toBe(true);
    expect(b.fileCount).toBe(1);
  });
});

describe('createBackup guards', () => {
  it('rejects traversal world names', () => {
    expect(worldBackups.createBackup(null, '../escape').ok).toBe(false);
  });
  it('rejects worlds without level.dat', () => {
    fs.mkdirSync(path.join(mockGameDir, 'saves', 'empty'), { recursive: true });
    expect(worldBackups.createBackup(null, 'empty').ok).toBe(false);
  });
});

describe('deleteBackup guards', () => {
  it('deletes only .zip basenames', () => {
    seedWorld('W');
    worldBackups.createBackup(null, 'W');
    const { backups } = worldBackups.listBackups(null);

    expect(worldBackups.deleteBackup(null, '../' + backups[0].file).ok).toBe(false);
    expect(worldBackups.deleteBackup(null, 'notes.txt').ok).toBe(false);
    expect(worldBackups.deleteBackup(null, backups[0].file).ok).toBe(true);
    expect(worldBackups.listBackups(null).backups).toHaveLength(0);
  });
});
