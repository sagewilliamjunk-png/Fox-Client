// Tests for gameDirs.js — the consolidated game-directory resolution.
// settings / paths / profiles are mocked so each branch is exercised
// deterministically.

let mockSettingsState;
let mockProfilesById;

jest.mock('../src/main/settings', () => ({
  load: jest.fn(() => mockSettingsState),
}));
jest.mock('../src/main/paths', () => ({
  defaultMinecraft: jest.fn(() => 'C:\\default\\.minecraft'),
  instanceDir: jest.fn((id) => `C:\\foxlauncher\\instances\\${id}`),
}));
jest.mock('../src/main/profiles', () => ({
  find: jest.fn((id) => mockProfilesById[id] || null),
}));

const { resolveGameDir, gameDirForProfile } = require('../src/main/gameDirs');

beforeEach(() => {
  mockSettingsState = { gameDir: '', selectedProfile: 'default' };
  mockProfilesById = {};
});

describe('resolveGameDir', () => {
  it('falls back to the platform default when gameDir is empty', () => {
    expect(resolveGameDir()).toBe('C:\\default\\.minecraft');
  });
  it('falls back when gameDir is whitespace', () => {
    mockSettingsState.gameDir = '   ';
    expect(resolveGameDir()).toBe('C:\\default\\.minecraft');
  });
  it('returns the trimmed settings override', () => {
    mockSettingsState.gameDir = '  D:\\games\\mc  ';
    expect(resolveGameDir()).toBe('D:\\games\\mc');
  });
  it('accepts a pre-loaded settings object without re-reading', () => {
    const settings = require('../src/main/settings');
    settings.load.mockClear();
    expect(resolveGameDir({ gameDir: 'E:\\custom' })).toBe('E:\\custom');
    expect(settings.load).not.toHaveBeenCalled();
  });
});

describe('gameDirForProfile', () => {
  it('uses the instance dir for isolated profiles', () => {
    mockProfilesById.iso = { id: 'iso', isolated: true };
    expect(gameDirForProfile('iso')).toBe('C:\\foxlauncher\\instances\\iso');
  });
  it('uses a per-profile override when set', () => {
    mockProfilesById.ovr = { id: 'ovr', isolated: false, gameDirOverride: ' D:\\override ' };
    expect(gameDirForProfile('ovr')).toBe('D:\\override');
  });
  it('isolation beats gameDirOverride', () => {
    mockProfilesById.both = { id: 'both', isolated: true, gameDirOverride: 'D:\\override' };
    expect(gameDirForProfile('both')).toBe('C:\\foxlauncher\\instances\\both');
  });
  it('falls through to the global dir for linked profiles', () => {
    mockSettingsState.gameDir = 'D:\\global';
    mockProfilesById.linked = { id: 'linked', isolated: false, gameDirOverride: '' };
    expect(gameDirForProfile('linked')).toBe('D:\\global');
  });
  it('resolves the active profile when id is null', () => {
    mockSettingsState.selectedProfile = 'active';
    mockProfilesById.active = { id: 'active', isolated: true };
    expect(gameDirForProfile(null)).toBe('C:\\foxlauncher\\instances\\active');
  });
  it('handles unknown profile ids by falling back to the global dir', () => {
    expect(gameDirForProfile('ghost')).toBe('C:\\default\\.minecraft');
  });
});
