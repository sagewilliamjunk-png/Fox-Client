// World Backups — Resources sub-tab.
//
// Two columns: the profile's worlds (with a "Back up now" button per world)
// and the existing backups (restore / delete). Backups are stored outside
// the game directory (~/.foxlauncher/backups/) so reinstalls can't eat them.

import { escapeHtml, formatRelative, formatBytes } from '../util.js';

let currentProfileId = null;

export async function renderWorldBackups(mount) {
  const [s, profilesDoc] = await Promise.all([
    window.fox.getSettings().catch(() => ({})),
    window.fox.listProfiles().catch(() => ({ profiles: [] })),
  ]);
  const profiles = profilesDoc.profiles || [];
  if (!currentProfileId) {
    currentProfileId = s.selectedProfile || (profiles[0] && profiles[0].id) || null;
  }

  mount.innerHTML = `
    <div class="wb-toolbar" style="display:flex;gap:8px;align-items:center;margin-bottom:12px;">
      ${profiles.length > 1 ? `
        <select id="wb-profile" class="select" aria-label="Profile">
          ${profiles.map(p => `
            <option value="${escapeHtml(p.id)}"${p.id === currentProfileId ? ' selected' : ''}>
              ${escapeHtml(p.name)}
            </option>
          `).join('')}
        </select>
      ` : ''}
      <button class="btn" id="wb-refresh" title="Refresh" aria-label="Refresh">↺</button>
      <span class="status muted" id="wb-status" style="margin-left:auto;" aria-live="polite"></span>
    </div>
    <div class="two-col" style="display:grid;grid-template-columns:1fr 1fr;gap:16px;align-items:start;">
      <div class="section">
        <div class="section-title">Worlds</div>
        <div class="section-sub">Singleplayer worlds in this profile's saves folder. Back up before risky experiments — restores are one click.</div>
        <div id="wb-worlds"><div class="muted">Loading…</div></div>
      </div>
      <div class="section">
        <div class="section-title">Backups</div>
        <div class="section-sub">Stored in ~/.foxlauncher/backups — they survive profile resets and reinstalls.</div>
        <div id="wb-backups"><div class="muted">Loading…</div></div>
      </div>
    </div>
  `;

  const profileSel = document.getElementById('wb-profile');
  if (profileSel) {
    profileSel.addEventListener('change', () => {
      currentProfileId = profileSel.value;
      refresh();
    });
  }
  document.getElementById('wb-refresh').addEventListener('click', refresh);

  await refresh();

  function flash(msg) {
    const el = document.getElementById('wb-status');
    if (!el) return;
    el.textContent = msg;
    clearTimeout(flash._t);
    flash._t = setTimeout(() => { el.textContent = ''; }, 3500);
  }

  async function refresh() {
    const worldsHost = document.getElementById('wb-worlds');
    const backupsHost = document.getElementById('wb-backups');
    if (!worldsHost || !backupsHost) return;

    const [worldsRes, backupsRes] = await Promise.all([
      window.fox.listWorlds(currentProfileId).catch(e => ({ ok: false, error: e.message })),
      window.fox.listWorldBackups(currentProfileId).catch(e => ({ ok: false, error: e.message })),
    ]);

    // ---- worlds column ----
    const worlds = (worldsRes && worldsRes.worlds) || [];
    worldsHost.innerHTML = worlds.length ? worlds.map(w => `
      <div class="card" style="display:flex;align-items:center;gap:10px;margin-bottom:8px;">
        <div style="flex:1;min-width:0;">
          <div class="card-title" style="overflow:hidden;text-overflow:ellipsis;">${escapeHtml(w.name)}</div>
          <div class="card-meta">${formatBytes(w.sizeBytes)} · played ${escapeHtml(formatRelative(w.lastPlayedMs))}</div>
        </div>
        <button class="btn" data-backup="${escapeHtml(w.name)}">Back up</button>
      </div>
    `).join('') : `<div class="muted">No worlds found${worldsRes.error ? ` (${escapeHtml(worldsRes.error)})` : ''}.</div>`;

    for (const btn of worldsHost.querySelectorAll('[data-backup]')) {
      btn.addEventListener('click', async () => {
        btn.disabled = true;
        flash(`Backing up "${btn.dataset.backup}"…`);
        const r = await window.fox.backupWorld({ profileId: currentProfileId, world: btn.dataset.backup });
        btn.disabled = false;
        if (r.ok) { flash(`Backed up (${formatBytes(r.sizeBytes)}, ${r.fileCount} files).`); refresh(); }
        else flash(`Backup failed: ${r.error}`);
      });
    }

    // ---- backups column ----
    const backups = (backupsRes && backupsRes.backups) || [];
    backupsHost.innerHTML = backups.length ? backups.map(b => `
      <div class="card" style="display:flex;align-items:center;gap:10px;margin-bottom:8px;">
        <div style="flex:1;min-width:0;">
          <div class="card-title" style="overflow:hidden;text-overflow:ellipsis;">${escapeHtml(b.world)}</div>
          <div class="card-meta">${formatBytes(b.sizeBytes)} · ${escapeHtml(formatRelative(b.createdMs))}</div>
        </div>
        <button class="btn" data-restore="${escapeHtml(b.file)}" data-world="${escapeHtml(b.world)}">Restore</button>
        <button class="btn" data-delete="${escapeHtml(b.file)}" title="Delete backup" aria-label="Delete backup">🗑</button>
      </div>
    `).join('') : `<div class="muted">No backups yet.</div>`;

    for (const btn of backupsHost.querySelectorAll('[data-restore]')) {
      btn.addEventListener('click', async () => {
        btn.disabled = true;
        flash('Restoring…');
        let r = await window.fox.restoreWorldBackup({ profileId: currentProfileId, file: btn.dataset.restore });
        if (!r.ok && r.error === 'exists') {
          const overwrite = confirm(
            `A world named "${r.world}" already exists.\n\nOK = overwrite it with the backup\nCancel = keep both (restore as "${r.world} (restored)")`);
          r = await window.fox.restoreWorldBackup({
            profileId: currentProfileId,
            file: btn.dataset.restore,
            overwrite,
            asName: overwrite ? undefined : `${r.world} (restored)`,
          });
        }
        btn.disabled = false;
        if (r.ok) { flash(`Restored "${r.world}" (${r.fileCount} files).`); refresh(); }
        else flash(`Restore failed: ${r.error}`);
      });
    }
    for (const btn of backupsHost.querySelectorAll('[data-delete]')) {
      btn.addEventListener('click', async () => {
        if (!confirm('Delete this backup? This cannot be undone.')) return;
        const r = await window.fox.deleteWorldBackup({ profileId: currentProfileId, file: btn.dataset.delete });
        if (r.ok) { flash('Backup deleted.'); refresh(); }
        else flash(`Delete failed: ${r.error}`);
      });
    }
  }
}
