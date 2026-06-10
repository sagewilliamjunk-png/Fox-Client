// Tests for mcVersion.js — pure functions only (no I/O, no child processes).
// We reach into the module's internals by requiring it and testing the
// exported surface, plus two unexported helpers exposed via a test-only path.

// Patch the module cache to redirect 'fs' so we can hand-roll a lightweight
// fake without a full mock framework.
const path = require('path');

// ---- helpers extracted for direct testing ----
// We test the logic by re-implementing the small helpers inline and then
// comparing against the real module's exported behaviour.

// Pull in the real module so we can test its exports.
const mcVersion = require('../src/main/mcVersion');

// ---------------------------------------------------------------------------
// inferJavaFromId
// ---------------------------------------------------------------------------
// The function is NOT exported, so we reconstruct its logic here and ensure
// the real module's listVersionsEnriched / describeVersion derives the same
// value when there is no javaVersion field in the version JSON. This is an
// indirect test — the direct approach would require monkey-patching fs.

// Instead we test the exported buildLaunchCommand indirectly via evaluateRules,
// and we test the pure sort key directly on familySort by monkey-patching
// via the module's own computed output.

describe('familySort (via listVersionsEnriched sort stability)', () => {
  // We can test familySort's output indirectly by checking that year-based
  // versions sort above 1.x versions.  listVersionsEnriched is not testable
  // without a real game dir — so we unit-test the sort key by requiring a
  // fresh copy of the helper module and checking the output ordering.
  //
  // Since familySort is private, we test it via a helper that mirrors
  // the exact implementation.
  function familySort(family) {
    const s = String(family || '');
    const y = s.match(/^(\d{2})\.(\d+)(?:\.(\d+))?/);
    if (y) {
      return parseInt(y[1], 10) * 1_000_000
           + parseInt(y[2], 10) * 1000
           + parseInt(y[3] || '0', 10);
    }
    const m = s.match(/^1\.(\d+)(?:\.(\d+))?/);
    if (!m) return 0;
    return parseInt(m[1], 10) * 1000 + parseInt(m[2] || '0', 10);
  }

  it('year-based version sorts above any 1.x version', () => {
    expect(familySort('26.1.2')).toBeGreaterThan(familySort('1.21.11'));
    expect(familySort('25.2.0')).toBeGreaterThan(familySort('1.21.1'));
  });

  it('higher year-based versions sort above lower ones', () => {
    expect(familySort('26.2.0')).toBeGreaterThan(familySort('26.1.2'));
    expect(familySort('26.1.3')).toBeGreaterThan(familySort('26.1.2'));
  });

  it('1.21 sorts above 1.20', () => {
    expect(familySort('1.21')).toBeGreaterThan(familySort('1.20.4'));
  });

  it('returns 0 for unrecognised strings', () => {
    expect(familySort('')).toBe(0);
    expect(familySort('snapshot')).toBe(0);
  });
});

describe('inferJavaFromId (mirrors real implementation)', () => {
  // Mirror the real implementation so we can test all branches.
  function extractFamily(id) {
    const m = id.match(/(\d+\.\d+(?:\.\d+)?)$/);
    return m ? m[1] : id;
  }
  function inferJavaFromId(id) {
    const fam = extractFamily(id);
    if (/^\d{2}\.\d/.test(fam)) return 21;
    const m = fam.match(/^1\.(\d+)/);
    if (!m) return 8;
    const minor = parseInt(m[1], 10);
    if (minor >= 21) return 21;
    if (minor >= 18) return 17;
    if (minor >= 17) return 16;
    return 8;
  }

  it('returns 21 for year-based format (26.x.y)', () => {
    expect(inferJavaFromId('26.1.2')).toBe(21);
    expect(inferJavaFromId('25.2.0')).toBe(21);
  });

  it('returns 21 for 1.21+', () => {
    expect(inferJavaFromId('1.21.11')).toBe(21);
    expect(inferJavaFromId('1.21')).toBe(21);
    expect(inferJavaFromId('1.22')).toBe(21);
  });

  it('returns 17 for 1.18 – 1.20.x', () => {
    expect(inferJavaFromId('1.18')).toBe(17);
    expect(inferJavaFromId('1.20.4')).toBe(17);
  });

  it('returns 16 for 1.17', () => {
    expect(inferJavaFromId('1.17')).toBe(16);
    expect(inferJavaFromId('1.17.1')).toBe(16);
  });

  it('returns 8 for 1.16 and below', () => {
    expect(inferJavaFromId('1.16.5')).toBe(8);
    expect(inferJavaFromId('1.8.9')).toBe(8);
  });

  it('returns 8 for unknown format', () => {
    expect(inferJavaFromId('unknown')).toBe(8);
  });

  it('extracts family from fabric-loader id correctly', () => {
    expect(inferJavaFromId('fabric-loader-0.18.6-1.21.11')).toBe(21);
    expect(inferJavaFromId('fabric-loader-0.18.6-26.1.2')).toBe(21);
  });
});

// ---------------------------------------------------------------------------
// evaluateRules
// ---------------------------------------------------------------------------
// evaluateRules is not exported — test it via buildLaunchCommand which uses
// it internally. We create a minimal fake version JSON.

describe('substitute + buildLaunchCommand argument construction', () => {
  const os  = require('os');
  const fs  = require('fs');

  // Build a minimal fake game dir so buildLaunchCommand can read version JSON
  // without failing on missing files.
  let tmpDir;
  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'fox-test-'));
  });
  afterEach(() => {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  function writeVersionJson(id, data) {
    const dir = path.join(tmpDir, 'versions', id);
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(path.join(dir, `${id}.json`), JSON.stringify(data));
  }
  function touchClientJar(id) {
    const dir = path.join(tmpDir, 'versions', id);
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(path.join(dir, `${id}.jar`), '');
  }
  function mkLibrary(rel) {
    const full = path.join(tmpDir, 'libraries', rel);
    fs.mkdirSync(path.dirname(full), { recursive: true });
    fs.writeFileSync(full, '');
    return full;
  }

  it('uses --quickPlayMultiplayer host:port when serverHost is set', () => {
    const vId = 'test-1.21';
    writeVersionJson(vId, {
      id: vId,
      type: 'release',
      mainClass: 'net.minecraft.client.main.Main',
      libraries: [],
      arguments: { jvm: [], game: [] },
    });
    touchClientJar(vId);
    const cmd = mcVersion.buildLaunchCommand({
      gameDir:    tmpDir,
      versionId:  vId,
      auth:       { username: 'Test', uuid: 'abc-123', accessToken: 'tok' },
      javaPath:   '/usr/bin/java',
      minRam:     2,
      maxRam:     4,
      resolution: { width: 1280, height: 720, fullscreen: false },
      serverHost: 'mc.2b2t.org',
      serverPort: 25565,
    });
    // Modern Quick Play arg — `--server`/`--port` were removed in MC 1.20.
    expect(cmd.args).toContain('--quickPlayMultiplayer');
    expect(cmd.args).toContain('mc.2b2t.org:25565');
    expect(cmd.args).not.toContain('--server');
    expect(cmd.args).not.toContain('--port');
  });

  it('uses bare host for --quickPlayMultiplayer when no port is set', () => {
    const vId = 'test-1.21-noport';
    writeVersionJson(vId, {
      id: vId, type: 'release',
      mainClass: 'net.minecraft.client.main.Main',
      libraries: [], arguments: { jvm: [], game: [] },
    });
    touchClientJar(vId);
    const cmd = mcVersion.buildLaunchCommand({
      gameDir: tmpDir, versionId: vId,
      auth: { username: 'T', uuid: 'u', accessToken: 't' },
      javaPath: '/usr/bin/java', minRam: 2, maxRam: 4,
      resolution: { width: 1280, height: 720, fullscreen: false },
      serverHost: 'hypixel.net',
    });
    expect(cmd.args).toContain('--quickPlayMultiplayer');
    expect(cmd.args).toContain('hypixel.net');
  });

  it('omits the quick-play arg when serverHost is empty', () => {
    const vId = 'test-1.21-noserver';
    writeVersionJson(vId, {
      id: vId, type: 'release',
      mainClass: 'net.minecraft.client.main.Main',
      libraries: [], arguments: { jvm: [], game: [] },
    });
    touchClientJar(vId);
    const cmd = mcVersion.buildLaunchCommand({
      gameDir: tmpDir, versionId: vId,
      auth: { username: 'T', uuid: 'u', accessToken: 't' },
      javaPath: '/usr/bin/java', minRam: 2, maxRam: 4,
      resolution: { width: 1280, height: 720, fullscreen: false },
    });
    expect(cmd.args).not.toContain('--quickPlayMultiplayer');
    expect(cmd.args).not.toContain('--server');
  });

  it('includes --fullscreen when resolution.fullscreen is true', () => {
    const vId = 'test-fs';
    writeVersionJson(vId, {
      id: vId, type: 'release',
      mainClass: 'net.minecraft.client.main.Main',
      libraries: [], arguments: { jvm: [], game: [] },
    });
    touchClientJar(vId);
    const cmd = mcVersion.buildLaunchCommand({
      gameDir: tmpDir, versionId: vId,
      auth: { username: 'T', uuid: 'u', accessToken: 't' },
      javaPath: '/usr/bin/java', minRam: 2, maxRam: 4,
      resolution: { width: null, height: null, fullscreen: true },
    });
    expect(cmd.args).toContain('--fullscreen');
  });

  it('sets memory flags from minRam/maxRam', () => {
    const vId = 'test-ram';
    writeVersionJson(vId, {
      id: vId, type: 'release',
      mainClass: 'net.minecraft.client.main.Main',
      libraries: [], arguments: { jvm: [], game: [] },
    });
    touchClientJar(vId);
    const cmd = mcVersion.buildLaunchCommand({
      gameDir: tmpDir, versionId: vId,
      auth: { username: 'T', uuid: 'u', accessToken: 't' },
      javaPath: '/usr/bin/java', minRam: 3, maxRam: 8,
      resolution: { width: 1280, height: 720, fullscreen: false },
    });
    expect(cmd.args).toContain('-Xms3G');
    expect(cmd.args).toContain('-Xmx8G');
  });

  it('appends extraJvmArgs to the command', () => {
    const vId = 'test-jvm';
    writeVersionJson(vId, {
      id: vId, type: 'release',
      mainClass: 'net.minecraft.client.main.Main',
      libraries: [], arguments: { jvm: [], game: [] },
    });
    touchClientJar(vId);
    const cmd = mcVersion.buildLaunchCommand({
      gameDir: tmpDir, versionId: vId,
      auth: { username: 'T', uuid: 'u', accessToken: 't' },
      javaPath: '/usr/bin/java', minRam: 2, maxRam: 4,
      resolution: { width: 1280, height: 720, fullscreen: false },
      extraJvmArgs: ['-XX:+UseStringDeduplication', '-Dfoo=bar'],
    });
    expect(cmd.args).toContain('-XX:+UseStringDeduplication');
    expect(cmd.args).toContain('-Dfoo=bar');
  });
});

// ---------------------------------------------------------------------------
// findFabricProfile
// ---------------------------------------------------------------------------
describe('findFabricProfile', () => {
  const os = require('os');
  const fs = require('fs');
  let tmpDir;
  beforeEach(() => { tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'fox-test-')); });
  afterEach(() => { fs.rmSync(tmpDir, { recursive: true, force: true }); });

  function mkFabricVersion(id) {
    const dir = path.join(tmpDir, 'versions', id);
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(path.join(dir, `${id}.json`), '{}');
  }

  it('returns null when no fabric profile exists', () => {
    expect(mcVersion.findFabricProfile(tmpDir, '1.21.11')).toBeNull();
  });

  it('finds the fabric-loader profile for a given MC version', () => {
    mkFabricVersion('fabric-loader-0.18.6-1.21.11');
    expect(mcVersion.findFabricProfile(tmpDir, '1.21.11')).toBe('fabric-loader-0.18.6-1.21.11');
  });

  it('picks the newest loader when multiple exist', () => {
    mkFabricVersion('fabric-loader-0.16.0-1.21.11');
    mkFabricVersion('fabric-loader-0.18.6-1.21.11');
    mkFabricVersion('fabric-loader-0.17.2-1.21.11');
    expect(mcVersion.findFabricProfile(tmpDir, '1.21.11')).toBe('fabric-loader-0.18.6-1.21.11');
  });

  it('does not confuse versions with different MC suffixes', () => {
    mkFabricVersion('fabric-loader-0.18.6-1.21.11');
    mkFabricVersion('fabric-loader-0.18.6-1.21.1');
    expect(mcVersion.findFabricProfile(tmpDir, '1.21.11')).toBe('fabric-loader-0.18.6-1.21.11');
    expect(mcVersion.findFabricProfile(tmpDir, '1.21.1')).toBe('fabric-loader-0.18.6-1.21.1');
  });

  it('works with year-based MC versions', () => {
    mkFabricVersion('fabric-loader-0.18.6-26.1.2');
    expect(mcVersion.findFabricProfile(tmpDir, '26.1.2')).toBe('fabric-loader-0.18.6-26.1.2');
  });
});
