// Minecraft Server List Ping (SLP) — query a server's status without joining.
//
// Modern (1.7+) handshake protocol:
//   1. TCP connect to host:port
//   2. Send Handshake  (id 0x00: protocolVersion, address, port, nextState=1)
//   3. Send StatusReq  (id 0x00, empty)
//   4. Read StatusResp (id 0x00 + length-prefixed JSON)
// Latency is the connect→response wall time.
//
// Every packet is length-prefixed with a VarInt. The VarInt + JSON-extraction
// helpers are pure and exported for unit tests; ping() wraps them in a socket.

const net = require('net');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const paths = require('./paths');

const DEFAULT_PORT = 25565;
const DEFAULT_TIMEOUT_MS = 5000;

// ── VarInt ────────────────────────────────────────────────────────────────

/** Encode a non-negative integer as a Minecraft VarInt (Buffer). */
function encodeVarInt(value) {
  const bytes = [];
  let v = value >>> 0;
  do {
    let b = v & 0x7f;
    v >>>= 7;
    if (v !== 0) b |= 0x80;
    bytes.push(b);
  } while (v !== 0);
  return Buffer.from(bytes);
}

/**
 * Read a VarInt from `buf` at `offset`. Returns { value, size } or null if the
 * buffer doesn't yet hold a complete VarInt (caller should wait for more data).
 */
function readVarInt(buf, offset = 0) {
  let value = 0;
  let size = 0;
  let b;
  do {
    if (offset + size >= buf.length) return null; // incomplete
    b = buf[offset + size];
    value |= (b & 0x7f) << (7 * size);
    size++;
    if (size > 5) throw new Error('VarInt too long');
  } while ((b & 0x80) !== 0);
  return { value, size };
}

// ── packet builders ─────────────────────────────────────────────────────────

/** Prefix a packet body with its VarInt length. */
function framed(body) {
  return Buffer.concat([encodeVarInt(body.length), body]);
}

function buildHandshake(host, port) {
  const hostBuf = Buffer.from(host, 'utf8');
  const body = Buffer.concat([
    encodeVarInt(0x00),          // packet id
    encodeVarInt(-1 >>> 0 & 0),  // protocol version (0 = "any"; servers tolerate it)
    encodeVarInt(hostBuf.length),
    hostBuf,
    Buffer.from([(port >> 8) & 0xff, port & 0xff]), // unsigned short, big-endian
    encodeVarInt(1),             // next state: status
  ]);
  return framed(body);
}

function buildStatusRequest() {
  return framed(encodeVarInt(0x00));
}

// ── response parsing ────────────────────────────────────────────────────────

/**
 * Given the full accumulated TCP buffer, try to extract the status JSON string.
 * Returns the parsed object, or null if the buffer is still incomplete.
 * Layout: VarInt packetLen, VarInt packetId(0x00), VarInt jsonLen, json bytes.
 */
function parseStatusResponse(buf) {
  const lenField = readVarInt(buf, 0);
  if (!lenField) return null;
  const total = lenField.size + lenField.value;
  if (buf.length < total) return null; // wait for more

  let off = lenField.size;
  const idField = readVarInt(buf, off);
  if (!idField) return null;
  off += idField.size;
  if (idField.value !== 0x00) throw new Error(`Unexpected packet id 0x${idField.value.toString(16)}`);

  const jsonLen = readVarInt(buf, off);
  if (!jsonLen) return null;
  off += jsonLen.size;
  if (buf.length < off + jsonLen.value) return null;

  const json = buf.toString('utf8', off, off + jsonLen.value);
  return JSON.parse(json);
}

/** Flatten a chat-component-or-string MOTD into plain text. */
function motdToText(desc) {
  if (desc == null) return '';
  if (typeof desc === 'string') return desc;
  let out = typeof desc.text === 'string' ? desc.text : '';
  if (Array.isArray(desc.extra)) for (const e of desc.extra) out += motdToText(e);
  // Strip legacy § colour codes for a clean one-line display.
  return out.replace(/§./g, '');
}

/** Normalize a parsed status JSON into the shape the UI consumes. */
function normalizeStatus(status, latencyMs) {
  const players = status.players || {};
  const version = status.version || {};
  return {
    ok: true,
    motd: motdToText(status.description).trim(),
    online: typeof players.online === 'number' ? players.online : null,
    max: typeof players.max === 'number' ? players.max : null,
    version: typeof version.name === 'string' ? version.name.replace(/§./g, '') : null,
    favicon: typeof status.favicon === 'string' ? status.favicon : null, // data: URI
    latencyMs,
  };
}

// ── public ping ───────────────────────────────────────────────────────────

/**
 * Ping a server. Resolves to a normalized status object (never rejects) so the
 * UI can render a row's error inline.
 *
 * @param {string} host
 * @param {number} [port=25565]
 * @param {object} [opts] { timeoutMs }
 */
function ping(host, port = DEFAULT_PORT, opts = {}) {
  const timeoutMs = opts.timeoutMs || DEFAULT_TIMEOUT_MS;
  return new Promise((resolve) => {
    const started = Date.now();
    let chunks = Buffer.alloc(0);
    let settled = false;
    const finish = (result) => { if (settled) return; settled = true; try { socket.destroy(); } catch (_) {} resolve(result); };

    const socket = net.createConnection({ host, port }, () => {
      socket.write(buildHandshake(host, port));
      socket.write(buildStatusRequest());
    });
    socket.setTimeout(timeoutMs);
    socket.on('timeout', () => finish({ ok: false, error: 'Timed out' }));
    socket.on('error', (e) => finish({ ok: false, error: e.code || e.message }));
    socket.on('data', (data) => {
      chunks = Buffer.concat([chunks, data]);
      try {
        const status = parseStatusResponse(chunks);
        if (status) finish(normalizeStatus(status, Date.now() - started));
      } catch (e) {
        finish({ ok: false, error: 'Bad status response' });
      }
    });
    socket.on('close', () => finish({ ok: false, error: 'Connection closed' }));
  });
}

// ── persisted server list ───────────────────────────────────────────────────

function serversFile() { return path.join(paths.root, 'servers.json'); }

function listServers() {
  try {
    const doc = JSON.parse(fs.readFileSync(serversFile(), 'utf8'));
    return Array.isArray(doc.servers) ? doc.servers : [];
  } catch (_) { return []; }
}

function saveServers(servers) {
  paths.ensureAll();
  const tmp = serversFile() + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify({ servers }, null, 2));
  fs.renameSync(tmp, serversFile());
}

/** Validate + normalize an incoming server entry. Returns null if invalid. */
function sanitizeServer(input) {
  if (!input || typeof input !== 'object') return null;
  const host = String(input.host || '').trim().slice(0, 253);
  if (!host || /\s/.test(host)) return null; // hostnames have no spaces
  let port = Math.floor(Number(input.port));
  if (!Number.isFinite(port) || port < 1 || port > 65535) port = DEFAULT_PORT;
  const name = String(input.name || host).trim().slice(0, 64) || host;
  return { id: crypto.randomUUID(), name, host, port };
}

function addServer(input) {
  const entry = sanitizeServer(input);
  if (!entry) return { ok: false, error: 'Invalid host' };
  const servers = listServers();
  servers.push(entry);
  saveServers(servers);
  return { ok: true, servers };
}

function removeServer(id) {
  const servers = listServers().filter(s => s.id !== id);
  saveServers(servers);
  return { ok: true, servers };
}

module.exports = {
  ping,
  DEFAULT_PORT,
  // persistence
  listServers,
  addServer,
  removeServer,
  sanitizeServer,
  // pure helpers exported for tests
  encodeVarInt,
  readVarInt,
  parseStatusResponse,
  motdToText,
  normalizeStatus,
};
