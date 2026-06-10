// Tests for profiles.js (main) — sanitize, CRUD, mod toggles.
// Uses a real temp directory so the file I/O logic runs for real.
// We use jest.doMock() (not jest.mock()) because the paths mock must reference
// variables that are only known after beforeEach sets up the temp dir.

const fs   = require('fs');
const os   = require('os');
const path = require('path');

let tmpDir;
let profilesFile;

// Helper: require profiles.js with the paths mock pointing at our tmpDir.
// Must be called AFTER tmpDir / profilesFile are set in beforeEach.
function getProfiles() {
  // jest.doMock doesn't get hoisted — safe to use variables from outer scope.
  jest.doMock('../src/main/paths', () => ({
    profiles:       profilesFile,
    instances:      path.join(tmpDir, 'instances'),
    ensureAll:      jest.fn(),
    ensureInstance: (id) => {
      const dir = path.join(tmpDir, 'instances', id);
      fs.mkdirSync(dir, { recursive: true });
      return dir;
    },
    defaultMinecraft: () => path.join(tmpDir, 'minecraft'),
  }));
  return require('../src/main/profiles');
}

beforeEach(() => {
  jest.resetModules();
  tmpDir       = fs.mkdtempSync(path.join(os.tmpdir(), 'fox-profiles-test-'));
  profilesFile = path.join(tmpDir, 'profiles.json');
});

afterEach(() => {
  jest.resetModules();
  fs.rmSync(tmpDir, { recursive: true, force: true });
});

// ---------------------------------------------------------------------------
// load() — defaults + migration
// ---------------------------------------------------------------------------
describe('load()', () => {
  it('returns a default isolated profile when file is missing', () => {
    const p = getProfiles();
    const doc = p.load();
    expect(doc.profiles).toHaveLength(1);
    expect(doc.profiles[0].id).toBe('default');
    expect(doc.profiles[0].isolated).toBe(true);
  });

  it('returns a default profile when file is corrupted JSON', () => {
    fs.writeFileSync(profilesFile, '{ not valid json ]]]');
    const p = getProfiles();
    const doc = p.load();
    expect(doc.profiles).toHaveLength(1);
    expect(doc.profiles[0].id).toBe('default');
  });

  it('migrates an unplayed non-isolated default profile to isolated', () => {
    fs.writeFileSync(profilesFile, JSON.stringify({
      profiles: [{ id: 'default', name: 'Default', isolated: false, lastPlayedAt: 0 }],
    }));
    const p = getProfiles();
    const doc = p.load();
    expect(doc.profiles[0].isolated).toBe(true);
  });

  it('does NOT migrate a played non-isolated default profile', () => {
    fs.writeFileSync(profilesFile, JSON.stringify({
      profiles: [{ id: 'default', name: 'Default', isolated: false, lastPlayedAt: Date.now() }],
    }));
    const p = getProfiles();
    const doc = p.load();
    expect(doc.profiles[0].isolated).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// sanitize via upsert
// ---------------------------------------------------------------------------
describe('sanitize', () => {
  it('strips disallowed characters from id', () => {
    const p = getProfiles();
    const doc = p.upsert({ id: 'My Cool Profile!', name: 'Test' });
    const saved = doc.profiles.find(pr => pr.name === 'Test');
    expect(saved).toBeDefined();
    expect(saved.id).toMatch(/^[a-z0-9-]+$/);
  });

  it('clamps ramMin and ramMax to [1, 64]', () => {
    const p = getProfiles();
    p.upsert({ id: 'test-ram', name: 'RAM', ramMin: 0, ramMax: 100 });
    const pr = p.find('test-ram');
    expect(pr.ramMin).toBe(1);
    expect(pr.ramMax).toBe(64);
  });

  it('clamps serverPort to [1, 65535]', () => {
    const p = getProfiles();
    p.upsert({ id: 'test-port', name: 'Port', serverPort: 99999 });
    const pr = p.find('test-port');
    expect(pr.serverPort).toBe(65535);
  });

  it('rejects an invalid mcVersion string', () => {
    const p = getProfiles();
    p.upsert({ id: 'test-mc', name: 'MC', mcVersion: 'not-a-version' });
    const pr = p.find('test-mc');
    expect(pr.mcVersion).toBeNull();
  });

  it('accepts a valid mcVersion string', () => {
    const p = getProfiles();
    p.upsert({ id: 'test-mc2', name: 'MC2', mcVersion: '26.1.2' });
    const pr = p.find('test-mc2');
    expect(pr.mcVersion).toBe('26.1.2');
  });

  it('rejects invalid hex color, preserves valid one', () => {
    const p = getProfiles();
    p.upsert({ id: 'bad-color', name: 'BC', color: 'red' });
    expect(p.find('bad-color').color).toBeNull();

    p.upsert({ id: 'good-color', name: 'GC', color: '#ff8c42' });
    expect(p.find('good-color').color).toBe('#ff8c42');
  });

  it('truncates jvmArgs at 1024 characters', () => {
    const p = getProfiles();
    const long = '-Xfoo=bar '.repeat(150); // > 1024 chars
    p.upsert({ id: 'test-jvm', name: 'JVM', jvmArgs: long });
    expect(p.find('test-jvm').jvmArgs.length).toBeLessThanOrEqual(1024);
  });
});

// ---------------------------------------------------------------------------
// upsert / find / remove
// ---------------------------------------------------------------------------
describe('upsert / find / remove', () => {
  it('creates a new profile', () => {
    const p = getProfiles();
    p.upsert({ id: 'new-prof', name: 'New' });
    expect(p.find('new-prof')).not.toBeNull();
  });

  it('merges into an existing profile, preserving untouched fields', () => {
    const p = getProfiles();
    p.upsert({ id: 'merge-test', name: 'Original', ramMin: 4 });
    p.upsert({ id: 'merge-test', name: 'Renamed' });
    const pr = p.find('merge-test');
    expect(pr.name).toBe('Renamed');
    expect(pr.ramMin).toBe(4); // preserved
  });

  it('remove() deletes the profile', () => {
    const p = getProfiles();
    p.upsert({ id: 'del-me', name: 'Delete' });
    p.remove('del-me');
    expect(p.find('del-me')).toBeNull();
  });

  it('remove() falls back to a default profile when list would be empty', () => {
    const p = getProfiles();
    p.remove('default');
    const doc = p.load();
    expect(doc.profiles.length).toBeGreaterThan(0);
  });
});

// ---------------------------------------------------------------------------
// patch
// ---------------------------------------------------------------------------
describe('patch()', () => {
  it('updates only the specified fields', () => {
    const p = getProfiles();
    p.upsert({ id: 'patch-me', name: 'Patch', ramMin: 2, ramMax: 4 });
    p.patch('patch-me', { ramMax: 8 });
    const pr = p.find('patch-me');
    expect(pr.ramMax).toBe(8);
    expect(pr.ramMin).toBe(2); // untouched
  });

  it('is a no-op for unknown id', () => {
    const p = getProfiles();
    expect(() => p.patch('no-such-id', { name: 'X' })).not.toThrow();
  });
});

// ---------------------------------------------------------------------------
// clone
// ---------------------------------------------------------------------------
describe('clone()', () => {
  it('creates a new profile with a different id', () => {
    const p = getProfiles();
    p.upsert({ id: 'src', name: 'Source', ramMin: 4 });
    const copy = p.clone('src', { id: 'copy', name: 'Copy' });
    expect(copy.id).toBe('copy');
    expect(copy.ramMin).toBe(4);
    expect(copy.lastPlayedAt).toBe(0);
  });

  it('generates a unique id when there is a collision', () => {
    const p = getProfiles();
    p.upsert({ id: 'x', name: 'X' });
    p.upsert({ id: 'x-copy', name: 'X Copy' });
    const copy = p.clone('x', { id: 'x-copy', name: 'Copy' });
    expect(copy.id).not.toBe('x-copy');
    expect(copy.id).toMatch(/^x-copy-\d+$/);
  });
});

// ---------------------------------------------------------------------------
// applyModToggles
// ---------------------------------------------------------------------------
describe('applyModToggles()', () => {
  it('renames .jar → .jar.disabled for disabled mods', () => {
    const p = getProfiles();
    p.upsert({ id: 'toggle-test', name: 'T', disabledMods: ['sodium.jar'] });

    const modsDir = path.join(tmpDir, 'mods');
    fs.mkdirSync(modsDir, { recursive: true });
    fs.writeFileSync(path.join(modsDir, 'sodium.jar'), '');

    p.applyModToggles(tmpDir, 'toggle-test');
    expect(fs.existsSync(path.join(modsDir, 'sodium.jar.disabled'))).toBe(true);
    expect(fs.existsSync(path.join(modsDir, 'sodium.jar'))).toBe(false);
  });

  it('restores .jar.disabled → .jar for enabled mods', () => {
    const p = getProfiles();
    p.upsert({ id: 'enable-test', name: 'E', disabledMods: [] });

    const modsDir = path.join(tmpDir, 'mods');
    fs.mkdirSync(modsDir, { recursive: true });
    fs.writeFileSync(path.join(modsDir, 'lithium.jar.disabled'), '');

    p.applyModToggles(tmpDir, 'enable-test');
    expect(fs.existsSync(path.join(modsDir, 'lithium.jar'))).toBe(true);
    expect(fs.existsSync(path.join(modsDir, 'lithium.jar.disabled'))).toBe(false);
  });

  it('disables kitsune-client when keepKitsuneEnabled is false', () => {
    const p = getProfiles();
    p.upsert({ id: 'no-kitsune', name: 'NK', keepKitsuneEnabled: false });

    const modsDir = path.join(tmpDir, 'mods');
    fs.mkdirSync(modsDir, { recursive: true });
    fs.writeFileSync(path.join(modsDir, 'kitsune-client.jar'), '');

    p.applyModToggles(tmpDir, 'no-kitsune');
    expect(fs.existsSync(path.join(modsDir, 'kitsune-client.jar.disabled'))).toBe(true);
  });

  it('returns enabled/disabled lists', () => {
    const p = getProfiles();
    p.upsert({ id: 'list-test', name: 'L', disabledMods: ['sodium.jar'] });

    const modsDir = path.join(tmpDir, 'mods');
    fs.mkdirSync(modsDir, { recursive: true });
    fs.writeFileSync(path.join(modsDir, 'sodium.jar'), '');
    fs.writeFileSync(path.join(modsDir, 'lithium.jar'), '');

    const result = p.applyModToggles(tmpDir, 'list-test');
    expect(result.disabled).toContain('sodium.jar');
    expect(result.enabled).toContain('lithium.jar');
  });
});

// ---------------------------------------------------------------------------
// export / import round-trip
// ---------------------------------------------------------------------------
describe('exportOne / importOne', () => {
  it('round-trips a profile through export+import', () => {
    const p = getProfiles();
    p.upsert({ id: 'export-me', name: 'Exported', ramMax: 8, notes: 'hi' });
    const payload = p.exportOne('export-me');
    expect(payload.schema).toBe('fox-launcher-profile');
    expect(payload.profile.name).toBe('Exported');
    expect(payload.profile.ramMax).toBe(8);
    // lastPlayedAt is stripped by exportOne
    expect(payload.profile.lastPlayedAt).toBeUndefined();
  });

  it('importOne loads the profile into the store', () => {
    const p = getProfiles();
    p.upsert({ id: 'export-me2', name: 'Exported2', ramMax: 6 });
    const payload = p.exportOne('export-me2');
    p.remove('export-me2');
    const imported = p.importOne(payload);
    expect(imported.name).toBe('Exported2');
    expect(imported.ramMax).toBe(6);
  });

  it('renames the id on import when there is a collision', () => {
    const p = getProfiles();
    p.upsert({ id: 'dup', name: 'Dup' });
    const payload = p.exportOne('dup');
    const imported = p.importOne(payload);
    expect(imported.id).not.toBe('dup');
    expect(imported.id).toMatch(/^dup-\d+$/);
  });

  it('throws for an invalid import payload', () => {
    const p = getProfiles();
    expect(() => p.importOne({ schema: 'bad-schema' })).toThrow();
    expect(() => p.importOne(null)).toThrow();
  });
});

// ---------------------------------------------------------------------------
// patch hardening — unknown keys are stripped, wrong types coerced (v1.5.0)
// ---------------------------------------------------------------------------
describe('sanitize whitelist (patch hardening)', () => {
  it('upsert drops unknown keys entirely', () => {
    const p = getProfiles();
    p.upsert({ id: 'evil', name: 'Evil', hackerFlag: true, polluted: { a: 1 } });
    const saved = p.load().profiles.find(x => x.id === 'evil');
    expect(saved).toBeDefined();
    expect(saved).not.toHaveProperty('hackerFlag');
    expect(saved).not.toHaveProperty('polluted');
  });

  it('patch with garbage keys/types leaves only whitelisted, coerced fields', () => {
    const p = getProfiles();
    p.upsert({ id: 'victim', name: 'Victim' });
    p.patch('victim', {
      injected: 'nope',
      jvmArgs: 12345,            // wrong type → default ''
      ramMin: 99999,             // out of range → clamped to 64
      serverPort: 'not-a-port',  // unparseable → null
      isolated: 'yes',           // wrong type → false
    });
    const saved = p.load().profiles.find(x => x.id === 'victim');
    expect(saved).not.toHaveProperty('injected');
    expect(saved.jvmArgs).toBe('');
    expect(saved.ramMin).toBe(64);
    expect(saved.serverPort).toBeNull();
    expect(saved.isolated).toBe(false);
  });

  it('gameDirOverride rejects system paths', () => {
    const p = getProfiles();
    p.upsert({ id: 'sys', name: 'Sys', gameDirOverride: 'C:\\Windows\\System32' });
    const saved = p.load().profiles.find(x => x.id === 'sys');
    expect(saved.gameDirOverride).toBe('');
  });
});
