// Tests for the modpack-import verification audit trail (modpackImport.js).

const fs   = require('fs');
const os   = require('os');
const path = require('path');

const { formatAuditLine, writeAuditLog } = require('../src/main/modpackImport');

describe('formatAuditLine', () => {
  const ts = Date.UTC(2026, 5, 9, 12, 0, 0);

  it('renders hash mismatches with expected/actual', () => {
    const line = formatAuditLine({
      type: 'hash-mismatch', file: 'mods/sodium.jar',
      expected: 'aaa', actual: 'bbb', action: 'skipped', ts,
    });
    expect(line).toBe('[2026-06-09T12:00:00.000Z] HASH-MISMATCH mods/sodium.jar expected=aaa actual=bbb action=skipped');
  });

  it('renders unverified installs', () => {
    const line = formatAuditLine({
      type: 'no-hash', file: 'mods/mystery.jar', action: 'installed-unverified', ts,
    });
    expect(line).toBe('[2026-06-09T12:00:00.000Z] NO-HASH mods/mystery.jar action=installed-unverified');
  });

  it('renders download failures with the error quoted', () => {
    const line = formatAuditLine({
      type: 'download-failed', file: 'mods/gone.jar', error: 'HTTP 404', action: 'skipped', ts,
    });
    expect(line).toBe('[2026-06-09T12:00:00.000Z] DOWNLOAD-FAILED mods/gone.jar error="HTTP 404" action=skipped');
  });
});

describe('writeAuditLog', () => {
  let tmpDir;
  beforeEach(() => { tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'fox-audit-test-')); });
  afterEach(() => { fs.rmSync(tmpDir, { recursive: true, force: true }); });

  it('returns null for an empty event list (no file written)', () => {
    expect(writeAuditLog([], tmpDir)).toBeNull();
    expect(fs.readdirSync(tmpDir)).toHaveLength(0);
  });

  it('writes one line per event to a timestamped file', () => {
    const file = writeAuditLog([
      { type: 'hash-mismatch', file: 'a.jar', expected: 'x', actual: 'y', action: 'skipped' },
      { type: 'no-hash', file: 'b.jar', action: 'installed-unverified' },
    ], tmpDir);
    expect(file).toMatch(/import-audit-.*\.log$/);
    const lines = fs.readFileSync(file, 'utf8').trim().split('\n');
    expect(lines).toHaveLength(2);
    expect(lines[0]).toContain('HASH-MISMATCH a.jar');
    expect(lines[1]).toContain('NO-HASH b.jar');
  });
});
