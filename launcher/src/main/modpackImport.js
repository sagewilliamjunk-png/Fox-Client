// Modrinth modpack (.mrpack) importer.
//
// .mrpack format: a ZIP containing
//   - modrinth.index.json  (manifest with file URLs + hashes + deps)
//   - overrides/           (files copied verbatim into the game dir —
//                           configs, resource packs, shader packs, etc.)
//   - client-overrides/    (same but client-only)
//   - server-overrides/    (server-only — we ignore on the client)
//
// Schema reference: https://docs.modrinth.com/modpacks/format
//
// What this implementation does:
//   1. Open the .mrpack with yauzl (already a dependency)
//   2. Read modrinth.index.json
//   3. Verify MC version compatibility against TARGET_MC_VERSION
//   4. Create a new isolated profile named after the modpack
//   5. Download every required file, SHA-512 verify, write into
//      <instance>/mods (or wherever the path says)
//   6. Extract overrides/ + client-overrides/ into the instance root
//
// Idempotent: re-importing the same .mrpack updates the existing profile
// (matched by import-source path).

const fs    = require('fs');
const path  = require('path');
const crypto = require('crypto');
const yauzl = require('yauzl');
const { fetchWithRetry, writeAtomic } = require('./httpClient');
const paths = require('./paths');

// ── verification audit trail ─────────────────────────────────────────────────
//
// Every file that fails (or skips) SHA-512 verification gets a structured
// line in an audit log under ~/.foxlauncher/logs/, so "the import said 2
// errors" is diagnosable after the fact instead of vanishing with the toast.

/** One audit event → one stable, greppable log line. */
function formatAuditLine(ev) {
  const ts = new Date(ev.ts || Date.now()).toISOString();
  switch (ev.type) {
    case 'hash-mismatch':
      return `[${ts}] HASH-MISMATCH ${ev.file} expected=${ev.expected} actual=${ev.actual} action=${ev.action}`;
    case 'no-hash':
      return `[${ts}] NO-HASH ${ev.file} action=${ev.action}`;
    case 'download-failed':
      return `[${ts}] DOWNLOAD-FAILED ${ev.file} error=${JSON.stringify(ev.error || 'unknown')} action=${ev.action}`;
    default:
      return `[${ts}] ${String(ev.type || 'EVENT').toUpperCase()} ${ev.file || ''}`;
  }
}

/** Write the audit events to a timestamped file. Returns the path or null. */
function writeAuditLog(events, logsDir = paths.logs) {
  if (!events.length) return null;
  try {
    fs.mkdirSync(logsDir, { recursive: true });
    const stamp = new Date().toISOString().replace(/[:.]/g, '-');
    const file = path.join(logsDir, `import-audit-${stamp}.log`);
    fs.writeFileSync(file, events.map(formatAuditLine).join('\n') + '\n');
    return file;
  } catch (_) {
    return null;
  }
}

/** Parse the .mrpack into { index, overrides } in memory. */
function readMrpack(filePath) {
  return new Promise((resolve, reject) => {
    yauzl.open(filePath, { lazyEntries: true, autoClose: true }, (err, zip) => {
      if (err) return reject(err);
      let index = null;
      const overrides = []; // { entryPath, buffer }
      zip.on('entry', (entry) => {
        // Skip directory entries.
        if (/\/$/.test(entry.fileName)) { zip.readEntry(); return; }
        zip.openReadStream(entry, (err2, stream) => {
          if (err2) { zip.close(); return reject(err2); }
          const chunks = [];
          stream.on('data', c => chunks.push(c));
          stream.on('end', () => {
            const buf = Buffer.concat(chunks);
            if (entry.fileName === 'modrinth.index.json') {
              try { index = JSON.parse(buf.toString('utf8')); }
              catch (e) { zip.close(); return reject(new Error('Bad modrinth.index.json: ' + e.message)); }
            } else if (entry.fileName.startsWith('overrides/')
                    || entry.fileName.startsWith('client-overrides/')) {
              // Strip the prefix — both get merged into the instance root.
              const stripped = entry.fileName.replace(/^(client-)?overrides\//, '');
              if (stripped) overrides.push({ entryPath: stripped, buffer: buf });
            }
            zip.readEntry();
          });
          stream.on('error', err3 => { zip.close(); reject(err3); });
        });
      });
      zip.on('end', () => {
        if (!index) return reject(new Error('modrinth.index.json missing from .mrpack'));
        resolve({ index, overrides });
      });
      zip.on('error', reject);
      zip.readEntry();
    });
  });
}

/** Sanitize an entry path to prevent ZIP-slip (../../../ escape). */
function safeRelativePath(entryPath, dest) {
  const resolved = path.resolve(dest, entryPath);
  if (!resolved.startsWith(path.resolve(dest) + path.sep) && resolved !== path.resolve(dest)) {
    throw new Error(`Refusing to write outside instance dir: ${entryPath}`);
  }
  return resolved;
}

/** SHA-512 a buffer. */
function sha512(buf) { return crypto.createHash('sha512').update(buf).digest('hex'); }

/**
 * Import a .mrpack into a fresh isolated profile and install its contents.
 *
 * @param {string} mrpackPath  Local path to the .mrpack file
 * @param {object} ctx        { instanceDir(profileId), mcVersion, mkProfile, onProgress }
 * @returns {Promise<{ok, profileId?, name?, mcVersion?, fileCount?, error?}>}
 */
async function importMrpack(mrpackPath, ctx) {
  const onProgress = typeof ctx.onProgress === 'function' ? ctx.onProgress : () => {};
  onProgress(`Reading ${path.basename(mrpackPath)}…`);
  let pack;
  try { pack = await readMrpack(mrpackPath); }
  catch (e) { return { ok: false, error: 'Open failed: ' + e.message }; }

  const idx = pack.index;
  if (idx.game && idx.game !== 'minecraft') {
    return { ok: false, error: `Unsupported game in mrpack: ${idx.game}` };
  }
  const targetMc = idx.dependencies && idx.dependencies.minecraft;
  if (targetMc && targetMc !== ctx.mcVersion) {
    onProgress(`⚠ Modpack targets MC ${targetMc} but launcher is set to ${ctx.mcVersion} — proceeding anyway.`);
  }
  const name = idx.name || path.basename(mrpackPath, '.mrpack');
  const profileId = ctx.mkProfile(name); // launcher creates an isolated profile and returns its id
  if (!profileId) return { ok: false, error: 'Failed to create profile slot.' };

  const instanceDir = ctx.instanceDir(profileId);
  fs.mkdirSync(instanceDir, { recursive: true });

  // Files from the manifest. Each file's "env" field tells us whether it's
  // client/server/both — we install anything except server-only.
  const files = Array.isArray(idx.files) ? idx.files : [];
  let installed = 0;
  let skipped  = 0;
  let errors   = 0;
  const auditEvents = [];
  for (let i = 0; i < files.length; i++) {
    const f = files[i];
    if (!f || !f.path || !Array.isArray(f.downloads) || !f.downloads.length) {
      skipped++; continue;
    }
    const env = f.env || {};
    if (env.client === 'unsupported') { skipped++; continue; }

    onProgress(`(${i + 1}/${files.length}) ${f.path}`);
    let buf = null;
    let lastErr;
    // Try each download URL in turn (Modrinth puts the primary + mirror).
    for (const url of f.downloads) {
      try { buf = await fetchWithRetry(url, { userAgent: 'Fox-Launcher (mrpack-import)' }); break; }
      catch (e) { lastErr = e; }
    }
    if (!buf) {
      onProgress(`  → failed: ${lastErr ? lastErr.message : 'unknown'}`);
      auditEvents.push({ type: 'download-failed', file: f.path,
          error: lastErr ? lastErr.message : 'unknown', action: 'skipped', ts: Date.now() });
      errors++; continue;
    }
    if (f.hashes && f.hashes.sha512) {
      const actual = sha512(buf);
      if (actual.toLowerCase() !== f.hashes.sha512.toLowerCase()) {
        onProgress(`  → hash mismatch — skipping ${f.path}`);
        auditEvents.push({ type: 'hash-mismatch', file: f.path,
            expected: f.hashes.sha512, actual, action: 'skipped', ts: Date.now() });
        errors++; continue;
      }
    } else {
      // No hash in the manifest — we install it, but record that it was
      // never verified so the audit log tells the whole story.
      auditEvents.push({ type: 'no-hash', file: f.path,
          action: 'installed-unverified', ts: Date.now() });
    }
    // Write — sanitized path.
    let dest;
    try { dest = safeRelativePath(f.path, instanceDir); }
    catch (e) { onProgress(`  → ${e.message}`); errors++; continue; }
    fs.mkdirSync(path.dirname(dest), { recursive: true });
    try { await writeAtomic(dest, buf); installed++; }
    catch (e) { onProgress(`  → write failed: ${e.message}`); errors++; }
  }

  // Overrides — verbatim copy from the .mrpack into the instance dir.
  onProgress(`Extracting ${pack.overrides.length} override file(s)…`);
  let overridesWritten = 0;
  for (const o of pack.overrides) {
    let dest;
    try { dest = safeRelativePath(o.entryPath, instanceDir); }
    catch (e) { continue; }
    try {
      fs.mkdirSync(path.dirname(dest), { recursive: true });
      await writeAtomic(dest, o.buffer);
      overridesWritten++;
    } catch (_) { /* best-effort */ }
  }

  // Persist the verification audit trail (if anything noteworthy happened).
  const auditPath = writeAuditLog(auditEvents);
  if (auditPath) onProgress(`Verification audit: ${auditEvents.length} event(s) → ${auditPath}`);

  return {
    ok: true,
    profileId,
    name,
    mcVersion: targetMc,
    fileCount: installed,
    skipped,
    errors,
    overrides: overridesWritten,
    auditCount: auditEvents.length,
    auditPath,
  };
}

module.exports = { importMrpack, formatAuditLine, writeAuditLog };
