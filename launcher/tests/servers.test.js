// Tests for the SLP ping parsing helpers (servers.js). The socket-driven
// ping() isn't exercised here — these cover the pure VarInt + response
// parsing + normalization that do the actual work.

const {
  encodeVarInt, readVarInt, parseStatusResponse, motdToText, normalizeStatus,
} = require('../src/main/servers');

describe('VarInt', () => {
  it('round-trips small and multi-byte values', () => {
    for (const n of [0, 1, 127, 128, 255, 300, 25565, 2097151, 123456789]) {
      const buf = encodeVarInt(n);
      const r = readVarInt(buf, 0);
      expect(r).not.toBeNull();
      expect(r.value).toBe(n);
      expect(r.size).toBe(buf.length);
    }
  });
  it('127 is one byte, 128 is two', () => {
    expect(encodeVarInt(127)).toHaveLength(1);
    expect(encodeVarInt(128)).toHaveLength(2);
  });
  it('returns null on an incomplete VarInt', () => {
    expect(readVarInt(Buffer.from([0x80]), 0)).toBeNull(); // continuation bit set, no next byte
  });
});

// Build a valid status response frame: len( id(0x00) + jsonLen + json ).
function buildStatusFrame(obj) {
  const json = Buffer.from(JSON.stringify(obj), 'utf8');
  const body = Buffer.concat([encodeVarInt(0x00), encodeVarInt(json.length), json]);
  return Buffer.concat([encodeVarInt(body.length), body]);
}

describe('parseStatusResponse', () => {
  const sample = {
    description: { text: 'Welcome to ', extra: [{ text: '§aMCPVP' }] },
    players: { online: 42, max: 100 },
    version: { name: '1.21.x', protocol: 767 },
    favicon: 'data:image/png;base64,AAAA',
  };

  it('parses a complete frame', () => {
    const out = parseStatusResponse(buildStatusFrame(sample));
    expect(out.players.online).toBe(42);
    expect(out.version.name).toBe('1.21.x');
  });

  it('returns null when the buffer is incomplete (waits for more TCP data)', () => {
    const full = buildStatusFrame(sample);
    expect(parseStatusResponse(full.subarray(0, full.length - 5))).toBeNull();
  });
});

describe('motdToText', () => {
  it('handles plain strings', () => {
    expect(motdToText('Hello')).toBe('Hello');
  });
  it('flattens chat components and strips § codes', () => {
    expect(motdToText({ text: 'A ', extra: [{ text: '§cB' }, 'C'] })).toBe('A BC');
  });
  it('tolerates null', () => {
    expect(motdToText(null)).toBe('');
  });
});

describe('normalizeStatus', () => {
  it('produces the UI shape with latency', () => {
    const out = normalizeStatus({
      description: 'Hi', players: { online: 3, max: 20 }, version: { name: '1.21' },
    }, 57);
    expect(out).toMatchObject({ ok: true, motd: 'Hi', online: 3, max: 20, version: '1.21', latencyMs: 57 });
  });
  it('tolerates missing fields', () => {
    const out = normalizeStatus({}, 10);
    expect(out).toMatchObject({ ok: true, online: null, max: null, version: null, favicon: null });
  });
});
