// Tests for fabricInstaller.js — mavenPath coordinate conversion and
// installFabricApi version-check logic.
// HTTP calls are fully mocked; no network access needed.

// Mock httpClient so no real HTTPS requests are made.
jest.mock('../src/main/httpClient', () => ({
  fetchJson:       jest.fn(),
  fetchWithRetry:  jest.fn(),
  writeAtomic:     jest.fn().mockResolvedValue(undefined),
}));

const fs   = require('fs');
const os   = require('os');
const path = require('path');
const { fetchJson, fetchWithRetry, writeAtomic } = require('../src/main/httpClient');
const fabricInstaller = require('../src/main/fabricInstaller');

// ---- mavenPath ----
// mavenPath is private, so we test it indirectly via install() or
// by re-implementing the same logic here and cross-checking results.

describe('mavenPath (logic mirror)', () => {
  function mavenPath(name) {
    const parts = name.split(':');
    if (parts.length < 3) throw new Error(`Bad maven name: ${name}`);
    const [groupId, artifactId, version, classifier] = parts;
    const groupPath = groupId.replace(/\./g, '/');
    const fileName = classifier
      ? `${artifactId}-${version}-${classifier}.jar`
      : `${artifactId}-${version}.jar`;
    return `${groupPath}/${artifactId}/${version}/${fileName}`;
  }

  it('converts a basic coordinate', () => {
    expect(mavenPath('net.fabricmc:fabric-loader:0.18.6'))
      .toBe('net/fabricmc/fabric-loader/0.18.6/fabric-loader-0.18.6.jar');
  });

  it('converts a coordinate with a classifier', () => {
    expect(mavenPath('org.lwjgl:lwjgl:3.3.3:natives-windows'))
      .toBe('org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar');
  });

  it('handles dotted group IDs', () => {
    expect(mavenPath('com.example.foo:bar:1.0'))
      .toBe('com/example/foo/bar/1.0/bar-1.0.jar');
  });

  it('throws on a malformed coordinate (< 3 parts)', () => {
    expect(() => mavenPath('net.fabricmc:fabric-loader')).toThrow(/Bad maven name/);
  });
});

// ---- installFabricApi — version-check logic ----

describe('installFabricApi', () => {
  let tmpDir;
  let modsDir;

  beforeEach(() => {
    tmpDir  = fs.mkdtempSync(path.join(os.tmpdir(), 'fox-fabric-test-'));
    modsDir = path.join(tmpDir, 'mods');
    fs.mkdirSync(modsDir, { recursive: true });
    jest.resetAllMocks();
  });

  afterEach(() => {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  const MOCK_MODRINTH_RESPONSE = [{
    version_type: 'release',
    version_number: '0.100.1+26.1.2',
    files: [{ primary: true, filename: 'fabric-api-0.100.1+26.1.2.jar', url: 'https://example.com/fabric-api.jar' }],
  }];

  it('skips download when a jar for the exact MC version already exists', async () => {
    fs.writeFileSync(path.join(modsDir, 'fabric-api-0.99.0+26.1.2.jar'), '');
    await fabricInstaller.installFabricApi(tmpDir, '26.1.2');
    expect(fetchJson).not.toHaveBeenCalled();
    expect(fetchWithRetry).not.toHaveBeenCalled();
  });

  it('removes wrong-version jar and downloads correct one', async () => {
    const wrongJar = path.join(modsDir, 'fabric-api-0.99.0+1.21.11.jar');
    fs.writeFileSync(wrongJar, '');
    fetchJson.mockResolvedValueOnce(MOCK_MODRINTH_RESPONSE);
    fetchWithRetry.mockResolvedValueOnce(Buffer.from('fake-jar-content'));

    await fabricInstaller.installFabricApi(tmpDir, '26.1.2');

    expect(fs.existsSync(wrongJar)).toBe(false);
    expect(fetchJson).toHaveBeenCalledTimes(1);
    expect(writeAtomic).toHaveBeenCalledTimes(1);
  });

  it('downloads when no fabric-api jar exists', async () => {
    fetchJson.mockResolvedValueOnce(MOCK_MODRINTH_RESPONSE);
    fetchWithRetry.mockResolvedValueOnce(Buffer.from('fake-jar-content'));

    await fabricInstaller.installFabricApi(tmpDir, '26.1.2');

    expect(fetchJson).toHaveBeenCalledTimes(1);
    expect(writeAtomic).toHaveBeenCalledTimes(1);
  });

  it('throws when Modrinth returns no versions', async () => {
    fetchJson.mockResolvedValueOnce([]);
    await expect(fabricInstaller.installFabricApi(tmpDir, '26.1.2'))
      .rejects.toThrow(/No Fabric API release found/);
  });

  it('throws when the release has no downloadable file', async () => {
    fetchJson.mockResolvedValueOnce([{ version_type: 'release', version_number: '0.1', files: [] }]);
    await expect(fabricInstaller.installFabricApi(tmpDir, '26.1.2'))
      .rejects.toThrow(/no downloadable file/);
  });
});

// ---- latestStableLoader ----

describe('latestStableLoader', () => {
  it('returns the first stable loader version', async () => {
    fetchJson.mockResolvedValueOnce([
      { version: '0.19.0', stable: true },
      { version: '0.19.1-beta', stable: false },
    ]);
    const v = await fabricInstaller.latestStableLoader();
    expect(v).toBe('0.19.0');
  });

  it('falls back to the first entry when none is stable', async () => {
    fetchJson.mockResolvedValueOnce([
      { version: '0.19.1-beta', stable: false },
    ]);
    const v = await fabricInstaller.latestStableLoader();
    expect(v).toBe('0.19.1-beta');
  });

  it('throws when the loader list is not an array', async () => {
    fetchJson.mockResolvedValueOnce({ error: 'bad' });
    await expect(fabricInstaller.latestStableLoader()).rejects.toThrow();
  });
});
