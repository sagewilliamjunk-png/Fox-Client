// Tests for logsUpload.js — payload preparation (scrub, truncate, format).
// The network call itself isn't exercised; uploadText is only checked for
// its no-network empty-input guard.

const {
  scrubHomeDir,
  truncateForUpload,
  formatEntries,
  preparePayload,
  uploadText,
  MAX_LINES,
} = require('../src/main/logsUpload');

describe('scrubHomeDir', () => {
  it('replaces the home dir with ~ (backslash form)', () => {
    const out = scrubHomeDir('jar at C:\\Users\\Sage\\mods\\fox.jar', 'C:\\Users\\Sage');
    expect(out).toBe('jar at ~\\mods\\fox.jar');
  });
  it('replaces the forward-slash form too', () => {
    const out = scrubHomeDir('path C:/Users/Sage/saves', 'C:\\Users\\Sage');
    expect(out).toBe('path ~/saves');
  });
  it('is case-insensitive (Windows paths)', () => {
    const out = scrubHomeDir('at c:\\users\\sage\\x', 'C:\\Users\\Sage');
    expect(out).toBe('at ~\\x');
  });
  it('leaves text alone when homedir is empty', () => {
    expect(scrubHomeDir('hello', '')).toBe('hello');
  });
});

describe('truncateForUpload', () => {
  it('passes small logs through untouched', () => {
    expect(truncateForUpload('a\nb\nc')).toBe('a\nb\nc');
  });
  it('keeps the TAIL when over the line limit and flags the cut', () => {
    const lines = [];
    for (let i = 0; i < MAX_LINES + 10; i++) lines.push(`line${i}`);
    const out = truncateForUpload(lines.join('\n'));
    expect(out.startsWith('[... truncated by Fox Launcher')).toBe(true);
    expect(out).toContain(`line${MAX_LINES + 9}`); // newest survives
    expect(out).not.toContain('line0\n');          // oldest dropped
  });
  it('enforces the byte cap', () => {
    const big = 'x'.repeat(1024) + '\n';
    const out = truncateForUpload(big.repeat(64), 1000, 16 * 1024);
    expect(Buffer.byteLength(out, 'utf8')).toBeLessThanOrEqual(16 * 1024 + 100);
  });
});

describe('formatEntries / preparePayload', () => {
  const entries = [
    { ts: Date.UTC(2026, 0, 2, 3, 4, 5), kind: 'stdout', text: 'Loading C:\\Users\\Sage\\.minecraft' },
    { ts: Date.UTC(2026, 0, 2, 3, 4, 6), kind: 'stderr', text: 'boom' },
  ];

  it('formats entries with ISO timestamp and kind', () => {
    const out = formatEntries(entries);
    expect(out).toBe(
        '[2026-01-02T03:04:05.000Z] [stdout] Loading C:\\Users\\Sage\\.minecraft\n'
      + '[2026-01-02T03:04:06.000Z] [stderr] boom');
  });

  it('preparePayload scrubs the home dir end-to-end', () => {
    const out = preparePayload(entries, 'C:\\Users\\Sage');
    expect(out).toContain('Loading ~\\.minecraft');
    expect(out).not.toContain('C:\\Users\\Sage');
  });
});

describe('uploadText', () => {
  it('refuses empty content without touching the network', async () => {
    await expect(uploadText('')).resolves.toEqual(
        { ok: false, error: 'Nothing to upload — the log is empty.' });
    await expect(uploadText('   \n  ')).resolves.toMatchObject({ ok: false });
  });
});
