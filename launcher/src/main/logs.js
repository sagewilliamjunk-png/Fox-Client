// In-memory ring buffer of the currently-running game's stdout/stderr,
// plus an optional rotating file sink under ~/.foxlauncher/logs/.
//
// The renderer subscribes via IPC to display the buffer in the log window;
// the file sink lets users hand off a full log to bug reports without the
// renderer having to scroll through 5000+ lines.

const fs = require('fs');
const path = require('path');
const paths = require('./paths');

const MAX_LINES = 5000;
const MAX_FILES = 10;
const MAX_FILE_BYTES = 5 * 1024 * 1024; // 5 MB

class LogBuffer {
  constructor() {
    this.lines = [];
    this.listeners = new Set();
    this.fileStream = null;
    this.fileBytes = 0;
    this.filePath = null;
  }

  push(kind, text) {
    const chunks = String(text).split(/\r?\n/);
    const ts = Date.now();
    for (const c of chunks) {
      if (c.length === 0) continue;
      this.lines.push({ ts, kind, text: c });
      if (this.lines.length > MAX_LINES) this.lines.shift();
      this._writeToFile(ts, kind, c);
    }
    for (const l of this.listeners) {
      try { l(kind, text); } catch (_) {}
    }
  }

  all() { return this.lines.slice(); }
  clear() { this.lines = []; }

  subscribe(fn) { this.listeners.add(fn); return () => this.listeners.delete(fn); }

  // ---- file sink ----

  /** Begin a new rotating log file for the current launch. Idempotent: closes any existing stream first. */
  beginFile() {
    this.endFile();
    paths.ensureAll();
    this._pruneOld();
    const stamp = new Date().toISOString().replace(/[:.]/g, '-');
    const file = path.join(paths.logs, `game-${stamp}.log`);
    try {
      this.fileStream = fs.createWriteStream(file, { flags: 'a' });
      this.filePath = file;
      this.fileBytes = 0;
    } catch (_) {
      this.fileStream = null;
      this.filePath = null;
    }
  }

  /** Flush + close the current log file (if any). Safe to call multiple times. */
  endFile() {
    if (!this.fileStream) return;
    try { this.fileStream.end(); } catch (_) {}
    this.fileStream = null;
    this.filePath = null;
    this.fileBytes = 0;
  }

  currentFilePath() { return this.filePath; }

  _writeToFile(ts, kind, line) {
    if (!this.fileStream) return;
    const stamp = new Date(ts).toISOString();
    const entry = `[${stamp}] [${kind}] ${line}\n`;
    try {
      this.fileStream.write(entry);
      this.fileBytes += Buffer.byteLength(entry);
      if (this.fileBytes >= MAX_FILE_BYTES) {
        const prev = this.filePath;
        this.endFile();
        this.beginFile();
        if (this.fileStream) this.fileStream.write(`[rotated from ${path.basename(prev)}]\n`);
      }
    } catch (_) {
      this.fileStream = null;
    }
  }

  _pruneOld() {
    let entries;
    try {
      entries = fs.readdirSync(paths.logs)
        .filter(f => /^game-.*\.log$/.test(f))
        .map(f => {
          const full = path.join(paths.logs, f);
          let mtime = 0;
          try { mtime = fs.statSync(full).mtimeMs; } catch (_) {}
          return { full, mtime };
        })
        .sort((a, b) => b.mtime - a.mtime);
    } catch (_) {
      return;
    }
    for (const stale of entries.slice(MAX_FILES - 1)) {
      try { fs.unlinkSync(stale.full); } catch (_) {}
    }
  }
}

module.exports = new LogBuffer();
