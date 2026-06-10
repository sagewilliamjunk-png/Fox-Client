// Tests for the shared renderer helpers (src/renderer/util.js).
//
// util.js is a browser ES module and this Jest config is CommonJS, so we
// load the source, strip the `export ` keywords, and evaluate it. The file
// is deliberately dependency-free (no DOM, no window.fox) to keep this safe.

const fs = require('fs');
const path = require('path');

function loadUtil() {
  const src = fs.readFileSync(
      path.join(__dirname, '..', 'src', 'renderer', 'util.js'), 'utf8')
      .replace(/^export /gm, '');
  // eslint-disable-next-line no-new-func
  return new Function(`${src}; return { escapeHtml, formatRelative, formatBytes };`)();
}

const { escapeHtml, formatRelative, formatBytes } = loadUtil();

describe('escapeHtml', () => {
  it('escapes the five HTML special characters', () => {
    expect(escapeHtml(`<img src="x" onerror='a&b'>`))
        .toBe('&lt;img src=&quot;x&quot; onerror=&#39;a&amp;b&#39;&gt;');
  });
  it('stringifies null/undefined to empty', () => {
    expect(escapeHtml(null)).toBe('');
    expect(escapeHtml(undefined)).toBe('');
  });
  it('passes plain text through', () => {
    expect(escapeHtml('Fox Launcher 1.5')).toBe('Fox Launcher 1.5');
  });
  it('stringifies non-strings', () => {
    expect(escapeHtml(42)).toBe('42');
  });
});

describe('formatRelative', () => {
  it('renders a dash for falsy timestamps', () => {
    expect(formatRelative(0)).toBe('—');
    expect(formatRelative(null)).toBe('—');
  });
  it('renders seconds / minutes / hours / days ago', () => {
    const now = Date.now();
    expect(formatRelative(now - 5_000)).toBe('5s ago');
    expect(formatRelative(now - 120_000)).toBe('2m ago');
    expect(formatRelative(now - 2 * 3600_000)).toBe('2h ago');
    expect(formatRelative(now - 3 * 86400_000)).toBe('3d ago');
  });
  it('falls back to a locale date beyond a week', () => {
    const old = Date.now() - 30 * 86400_000;
    expect(formatRelative(old)).toBe(new Date(old).toLocaleDateString());
  });
  it('clamps future timestamps to "now" instead of negative', () => {
    expect(formatRelative(Date.now() + 60_000)).toBe('0s ago');
  });
});

describe('formatBytes', () => {
  it('formats B / KB / MB', () => {
    expect(formatBytes(512)).toBe('512 B');
    expect(formatBytes(2048)).toBe('2 KB');
    expect(formatBytes(3 * 1024 * 1024)).toBe('3.0 MB');
  });
});
