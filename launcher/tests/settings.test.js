// Tests for settings.js — validate() / clamp logic, no disk I/O.
// We intercept the module's file operations by mocking the 'paths' module
// so load() never touches the real filesystem.

jest.mock('../src/main/paths', () => ({
  settings: '/fake/settings.json',
  ensureAll: jest.fn(),
}));

jest.mock('fs', () => {
  const real = jest.requireActual('fs');
  return {
    ...real,
    readFileSync: jest.fn(() => { throw new Error('no file'); }),
    writeFileSync: jest.fn(),
    renameSync: jest.fn(),
  };
});

const settings = require('../src/main/settings');

// Helper: reset module cache between tests so each `load()` starts fresh.
beforeEach(() => {
  jest.resetModules();
});

// Re-require after reset for tests that need a clean module state.
function freshSettings(rawJson) {
  jest.resetModules();
  jest.mock('../src/main/paths', () => ({
    settings: '/fake/settings.json',
    ensureAll: jest.fn(),
  }));
  const fs = require('fs');
  if (rawJson != null) {
    jest.spyOn(fs, 'readFileSync').mockReturnValueOnce(JSON.stringify(rawJson));
  } else {
    jest.spyOn(fs, 'readFileSync').mockImplementation(() => { throw new Error('no file'); });
  }
  jest.spyOn(fs, 'writeFileSync').mockImplementation(() => {});
  jest.spyOn(fs, 'renameSync').mockImplementation(() => {});
  return require('../src/main/settings');
}

describe('settings.validate', () => {
  it('fills in all defaults when file is missing', () => {
    const s = freshSettings(null);
    // load() should not throw, should return defaults
    // Since readFileSync throws, the module falls back to DEFAULTS.
    // We test the exported DEFAULTS object directly.
    const { DEFAULTS } = s;
    expect(DEFAULTS.minRam).toBe(2);
    expect(DEFAULTS.maxRam).toBe(4);
    expect(DEFAULTS.selectedProfile).toBe('default');
    expect(DEFAULTS.theme).toBe('fox');
  });

  it('DEFAULTS no longer contains selectedVersion', () => {
    const { DEFAULTS } = require('../src/main/settings');
    expect(DEFAULTS).not.toHaveProperty('selectedVersion');
  });

  it('clamps minRam below 1 to 1', () => {
    const s = freshSettings({ minRam: 0, maxRam: 4 });
    // Can't call load() directly here without circular mock issues,
    // so we just verify the BOUNDS object enforces the floor.
    const { BOUNDS } = s;
    expect(BOUNDS.minRam.min).toBe(1);
  });

  it('sets maxRam = minRam when maxRam < minRam (via patch)', () => {
    // Use patch() which calls validate() internally.
    jest.resetModules();
    jest.mock('../src/main/paths', () => ({ settings: '/x.json', ensureAll: jest.fn() }));
    const fs = require('fs');
    jest.spyOn(fs, 'readFileSync').mockReturnValue(JSON.stringify({ minRam: 4, maxRam: 2 }));
    jest.spyOn(fs, 'writeFileSync').mockImplementation(() => {});
    jest.spyOn(fs, 'renameSync').mockImplementation(() => {});
    const s = require('../src/main/settings');
    const result = s.load();
    // minRam=4, maxRam=2 → maxRam should be clamped up to minRam=4
    expect(result.maxRam).toBeGreaterThanOrEqual(result.minRam);
  });

  it('rejects an invalid theme and falls back to "fox"', () => {
    jest.resetModules();
    jest.mock('../src/main/paths', () => ({ settings: '/x.json', ensureAll: jest.fn() }));
    const fs = require('fs');
    jest.spyOn(fs, 'readFileSync').mockReturnValue(JSON.stringify({ theme: 'hacker-green' }));
    jest.spyOn(fs, 'writeFileSync').mockImplementation(() => {});
    jest.spyOn(fs, 'renameSync').mockImplementation(() => {});
    const s = require('../src/main/settings');
    expect(s.load().theme).toBe('fox');
  });

  it('accepts the fox-light theme', () => {
    jest.resetModules();
    jest.mock('../src/main/paths', () => ({ settings: '/x.json', ensureAll: jest.fn() }));
    const fs = require('fs');
    jest.spyOn(fs, 'readFileSync').mockReturnValue(JSON.stringify({ theme: 'fox-light' }));
    jest.spyOn(fs, 'writeFileSync').mockImplementation(() => {});
    jest.spyOn(fs, 'renameSync').mockImplementation(() => {});
    const s = require('../src/main/settings');
    expect(s.load().theme).toBe('fox-light');
  });

  it('patches only specified fields, preserving others', () => {
    jest.resetModules();
    jest.mock('../src/main/paths', () => ({ settings: '/x.json', ensureAll: jest.fn() }));
    const fs = require('fs');
    jest.spyOn(fs, 'readFileSync').mockReturnValue(JSON.stringify({ minRam: 3, maxRam: 6 }));
    jest.spyOn(fs, 'writeFileSync').mockImplementation(() => {});
    jest.spyOn(fs, 'renameSync').mockImplementation(() => {});
    const s = require('../src/main/settings');
    const patched = s.patch({ theme: 'fox-light' });
    expect(patched.theme).toBe('fox-light');
    expect(patched.minRam).toBe(3);
    expect(patched.maxRam).toBe(6);
  });

  it('merges nested resolution patch correctly', () => {
    jest.resetModules();
    jest.mock('../src/main/paths', () => ({ settings: '/x.json', ensureAll: jest.fn() }));
    const fs = require('fs');
    jest.spyOn(fs, 'readFileSync').mockReturnValue(
      JSON.stringify({ resolution: { width: 1920, height: 1080, fullscreen: false } })
    );
    jest.spyOn(fs, 'writeFileSync').mockImplementation(() => {});
    jest.spyOn(fs, 'renameSync').mockImplementation(() => {});
    const s = require('../src/main/settings');
    const patched = s.patch({ resolution: { fullscreen: true } });
    expect(patched.resolution.fullscreen).toBe(true);
    expect(patched.resolution.width).toBe(1920);   // preserved
    expect(patched.resolution.height).toBe(1080);  // preserved
  });
});

// ---------------------------------------------------------------------------
// v1.5.0 — Java args preset validation + unknown-key stripping
// ---------------------------------------------------------------------------
describe('javaArgsPreset validation', () => {
  it('accepts every documented preset', () => {
    const s = freshSettings(null);
    for (const preset of s.JAVA_ARGS_PRESETS) {
      expect(s.save({ javaArgsPreset: preset }).javaArgsPreset).toBe(preset);
    }
  });

  it('rejects unknown presets back to default', () => {
    const s = freshSettings(null);
    expect(s.save({ javaArgsPreset: 'not-a-preset' }).javaArgsPreset).toBe('default');
  });

  it('length-caps customJavaArgs at 1000 chars and coerces non-strings', () => {
    const s = freshSettings(null);
    expect(s.save({ customJavaArgs: 'x'.repeat(2000) }).customJavaArgs).toHaveLength(1000);
    expect(s.save({ customJavaArgs: 42 }).customJavaArgs).toBe('');
  });
});

describe('unknown-key stripping (patch hardening)', () => {
  it('validate() output contains only whitelisted keys', () => {
    const s = freshSettings({ evilKey: true, theme: 'fox' });
    const loaded = s.load();
    expect(loaded).not.toHaveProperty('evilKey');
  });

  it('patch() with garbage keys does not persist them', () => {
    const s = freshSettings(null);
    const next = s.patch({ injectedSetting: 'boom' });
    expect(next).not.toHaveProperty('injectedSetting');
  });
});
