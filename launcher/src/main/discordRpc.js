// Discord Rich Presence — minimal hand-rolled IPC client.
//
// Why not use the `discord-rpc` npm package? It's been unmaintained for
// years, pulls in transitive deps we don't need, and the protocol is small
// enough to implement directly. This file uses only Node's built-in `net`
// module.
//
// Protocol summary (Discord IPC v1):
//   Connect to a named pipe `discord-ipc-N` (N = 0..9, try in order).
//     Windows: \\?\pipe\discord-ipc-N
//     Linux:   $XDG_RUNTIME_DIR/discord-ipc-N (fallbacks to /tmp etc.)
//     macOS:   $TMPDIR/discord-ipc-N
//   Frame layout: [opcode (uint32 LE)] [length (uint32 LE)] [json bytes]
//   Opcodes: 0=Handshake  1=Frame  2=Close  3=Ping  4=Pong
//   Handshake payload: { v: 1, client_id }
//   Then send { cmd: "SET_ACTIVITY", args: { pid, activity }, nonce } as op 1.
//
// Behavioural guarantees:
//   - If Discord isn't running, every call is a silent no-op.
//   - If the pipe disconnects, we reconnect with exponential backoff.
//   - SET_ACTIVITY is rate-limited (Discord enforces ~5 per 20s); we throttle
//     to one update per 4 seconds and skip duplicates.
//   - Never throws to the caller. All errors are logged and swallowed.

const net = require('net');
const os = require('os');
const path = require('path');
const { randomUUID } = require('crypto');

const OP_HANDSHAKE = 0;
const OP_FRAME     = 1;
const OP_CLOSE     = 2;
const OP_PING      = 3;
const OP_PONG      = 4;

const RECONNECT_BASE_MS = 5_000;
const RECONNECT_MAX_MS  = 60_000;
const ACTIVITY_THROTTLE_MS = 4_000;

class DiscordRpc {
  constructor({ clientId, log = () => {} } = {}) {
    this.clientId = clientId || '';
    this.log = log;
    this.socket = null;
    this.connected = false;
    this.disposed = false;
    this.reconnectAttempts = 0;
    this.reconnectTimer = null;
    this.lastActivity = null;
    this.lastActivityAt = 0;
    this.pendingActivity = null;
    this.flushTimer = null;
    this.recvBuffer = Buffer.alloc(0);
  }

  // ---- public API ----

  /** Idempotent. Spins up the connect loop. Safe to call multiple times. */
  start() {
    if (this.disposed) return;
    if (!this.clientId) { this.log('discord: no clientId configured, skipping'); return; }
    if (this.socket || this.reconnectTimer) return;
    this._connect();
  }

  /** Set the displayed activity. `activity` is the Discord activity payload
   *  shape (see _normalize below) or null to clear. */
  setActivity(activity) {
    if (this.disposed) return;
    this.pendingActivity = activity;
    this._flushSoon();
  }

  /** Clear any displayed activity. */
  clear() { this.setActivity(null); }

  /** Permanent shutdown — stop reconnecting and close the socket. */
  dispose() {
    this.disposed = true;
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    if (this.flushTimer) clearTimeout(this.flushTimer);
    this.reconnectTimer = this.flushTimer = null;
    this._tearDownSocket();
  }

  isConnected() { return this.connected; }

  // ---- pipe path resolution ----

  _candidatePaths() {
    const out = [];
    if (process.platform === 'win32') {
      for (let i = 0; i < 10; i++) out.push(`\\\\?\\pipe\\discord-ipc-${i}`);
      return out;
    }
    // Unix-likes: try the well-known runtime dirs in order.
    const dirs = [
      process.env.XDG_RUNTIME_DIR,
      process.env.TMPDIR,
      process.env.TMP,
      process.env.TEMP,
      '/tmp',
    ].filter(Boolean);
    // Discord on macOS often installs into a snap/flatpak subdir under
    // $XDG_RUNTIME_DIR — those are normal directories so the bare path works
    // for the common case. We also probe the snap and flatpak sub-paths.
    const sub = ['', 'snap.discord/', 'app/com.discordapp.Discord/'];
    for (const d of dirs) {
      for (const s of sub) {
        for (let i = 0; i < 10; i++) out.push(path.join(d, s, `discord-ipc-${i}`));
      }
    }
    return out;
  }

  // ---- connect / handshake ----

  _connect() {
    if (this.disposed) return;
    const candidates = this._candidatePaths();
    this._tryNext(candidates, 0);
  }

  _tryNext(candidates, idx) {
    if (this.disposed) return;
    if (idx >= candidates.length) {
      // Nothing answered. Schedule a retry — Discord may launch later.
      this._scheduleReconnect();
      return;
    }
    const target = candidates[idx];
    const sock = net.createConnection(target);
    let settled = false;

    const giveUp = () => {
      if (settled) return;
      settled = true;
      try { sock.destroy(); } catch (_) {}
      this._tryNext(candidates, idx + 1);
    };

    sock.once('error', giveUp);
    sock.once('connect', () => {
      if (settled) return;
      settled = true;
      sock.removeListener('error', giveUp);
      this._onConnected(sock);
    });
  }

  _onConnected(sock) {
    this.socket = sock;
    this.recvBuffer = Buffer.alloc(0);
    this.reconnectAttempts = 0;

    sock.on('data', (chunk) => this._onData(chunk));
    sock.on('error', (err) => {
      this.log(`discord: socket error ${err.message}`);
      this._handleDrop();
    });
    sock.on('close', () => this._handleDrop());

    // Send handshake; READY arrives back via _onData.
    this._sendFrame(OP_HANDSHAKE, { v: 1, client_id: String(this.clientId) });
  }

  _handleDrop() {
    if (this.disposed) return;
    this._tearDownSocket();
    this._scheduleReconnect();
  }

  _scheduleReconnect() {
    if (this.disposed || this.reconnectTimer) return;
    const delay = Math.min(
      RECONNECT_MAX_MS,
      RECONNECT_BASE_MS * Math.pow(2, Math.min(6, this.reconnectAttempts++)),
    );
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this._connect();
    }, delay);
  }

  _tearDownSocket() {
    this.connected = false;
    if (!this.socket) return;
    try { this.socket.removeAllListeners(); } catch (_) {}
    try { this.socket.destroy(); } catch (_) {}
    this.socket = null;
  }

  // ---- frame I/O ----

  _sendFrame(op, payload) {
    if (!this.socket) return;
    const json  = Buffer.from(JSON.stringify(payload), 'utf8');
    const head  = Buffer.alloc(8);
    head.writeUInt32LE(op, 0);
    head.writeUInt32LE(json.length, 4);
    try {
      this.socket.write(Buffer.concat([head, json]));
    } catch (err) {
      this.log(`discord: write failed ${err.message}`);
      this._handleDrop();
    }
  }

  _onData(chunk) {
    this.recvBuffer = Buffer.concat([this.recvBuffer, chunk]);
    // Frames arrive packed and possibly split — drain in a loop.
    while (this.recvBuffer.length >= 8) {
      const op  = this.recvBuffer.readUInt32LE(0);
      const len = this.recvBuffer.readUInt32LE(4);
      if (this.recvBuffer.length < 8 + len) return;
      const body = this.recvBuffer.slice(8, 8 + len);
      this.recvBuffer = this.recvBuffer.slice(8 + len);
      let json = null;
      try { json = JSON.parse(body.toString('utf8')); } catch (_) { /* ignore */ }
      this._handleFrame(op, json);
    }
  }

  _handleFrame(op, payload) {
    if (op === OP_PING) {
      this._sendFrame(OP_PONG, payload || {});
      return;
    }
    if (op === OP_CLOSE) {
      this.log('discord: server closed connection');
      this._handleDrop();
      return;
    }
    if (op === OP_FRAME && payload && payload.evt === 'READY') {
      this.connected = true;
      this.log('discord: connected');
      // Push whatever activity we'd queued before READY.
      this._flushSoon(true);
    }
  }

  // ---- activity throttling ----

  _flushSoon(force = false) {
    if (this.disposed) return;
    if (!this.connected) return;
    if (this.flushTimer) return;

    const since = Date.now() - this.lastActivityAt;
    const wait = force ? 0 : Math.max(0, ACTIVITY_THROTTLE_MS - since);
    this.flushTimer = setTimeout(() => {
      this.flushTimer = null;
      this._flushNow();
    }, wait);
  }

  _flushNow() {
    if (!this.connected) return;
    const next = this._normalize(this.pendingActivity);
    const sig  = JSON.stringify(next);
    if (sig === this.lastActivity) return;
    this.lastActivity = sig;
    this.lastActivityAt = Date.now();

    this._sendFrame(OP_FRAME, {
      cmd: 'SET_ACTIVITY',
      nonce: randomUUID(),
      args: {
        pid: process.pid,
        activity: next || null,
      },
    });
  }

  /** Strip undefined fields and clamp string lengths to Discord's limits.
   *  Returns null when caller asked to clear. */
  _normalize(a) {
    if (!a) return null;
    const trim = (s, n) => (typeof s === 'string' ? s.slice(0, n) : undefined);
    const out = {
      details: trim(a.details, 128),
      state:   trim(a.state,   128),
    };
    if (a.startTimestamp) out.timestamps = { start: Math.floor(a.startTimestamp / 1000) };
    if (a.largeImageKey || a.smallImageKey) {
      out.assets = {};
      if (a.largeImageKey) out.assets.large_image = trim(a.largeImageKey, 256);
      if (a.largeImageText) out.assets.large_text = trim(a.largeImageText, 128);
      if (a.smallImageKey) out.assets.small_image = trim(a.smallImageKey, 256);
      if (a.smallImageText) out.assets.small_text = trim(a.smallImageText, 128);
    }
    // Buttons — up to 2, each { label (max 32), url }. Visible to anyone
    // viewing the presence in Discord; clicking opens the URL in a browser.
    if (Array.isArray(a.buttons) && a.buttons.length) {
      const btns = a.buttons
        .slice(0, 2)
        .map(b => ({ label: String(b.label || '').slice(0, 32), url: String(b.url || '') }))
        .filter(b => b.label && b.url);
      if (btns.length) out.buttons = btns;
    }
    // Drop empties — Discord rejects activities where required fields are
    // present but blank.
    if (!out.details) delete out.details;
    if (!out.state)   delete out.state;
    return out;
  }
}

module.exports = { DiscordRpc };
