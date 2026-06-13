// Servers screen — a saved server list with live SLP status (MOTD, player
// count, latency) and one-click quick-join.
//
// Quick-join writes the host into the active profile's serverHost (the field
// that already drives auto-join in launcher.js) and launches via the normal
// game:launch path — the launcher never speaks the join protocol itself.

import { escapeHtml } from '../util.js';

export async function renderServers(mount) {
  mount.innerHTML = `
    <div class="srv-header" style="display:flex;align-items:center;gap:8px;margin-bottom:12px;">
      <h1 class="screen-title" style="margin:0;">Servers</h1>
      <button class="btn" id="srv-refresh" title="Re-ping all" aria-label="Refresh">↺</button>
      <span class="status muted" id="srv-status" style="margin-left:auto;" aria-live="polite"></span>
    </div>

    <div class="section">
      <div class="section-title">Add a server</div>
      <div class="input-row" style="display:flex;gap:8px;flex-wrap:wrap;">
        <input class="input" id="srv-name" placeholder="Name (optional)" style="flex:1;min-width:120px;" />
        <input class="input" id="srv-host" placeholder="host (e.g. mcpvp.club)" style="flex:2;min-width:160px;" />
        <input class="input" id="srv-port" placeholder="25565" inputmode="numeric" style="width:90px;" />
        <button class="btn" id="srv-add">Add</button>
      </div>
    </div>

    <div id="srv-list"><div class="muted">Loading…</div></div>
  `;

  const statusEl = mount.querySelector('#srv-status');
  const flash = (msg) => {
    statusEl.textContent = msg;
    clearTimeout(flash._t);
    flash._t = setTimeout(() => { statusEl.textContent = ''; }, 3500);
  };

  mount.querySelector('#srv-add').addEventListener('click', addServer);
  mount.querySelector('#srv-host').addEventListener('keydown', (e) => { if (e.key === 'Enter') addServer(); });
  mount.querySelector('#srv-refresh').addEventListener('click', () => refresh());

  async function addServer() {
    const name = mount.querySelector('#srv-name').value.trim();
    const host = mount.querySelector('#srv-host').value.trim();
    const port = mount.querySelector('#srv-port').value.trim();
    if (!host) { flash('Enter a host first.'); return; }
    const r = await window.fox.addServer({ name, host, port: port ? Number(port) : undefined });
    if (!r.ok) { flash(`Add failed: ${r.error}`); return; }
    mount.querySelector('#srv-name').value = '';
    mount.querySelector('#srv-host').value = '';
    mount.querySelector('#srv-port').value = '';
    refresh();
  }

  await refresh();

  async function refresh() {
    const list = await window.fox.listServers().catch(() => []);
    const host = mount.querySelector('#srv-list');
    if (!host) return;
    if (!list.length) {
      host.innerHTML = `<div class="muted">No servers yet — add one above.</div>`;
      return;
    }
    host.innerHTML = list.map(s => `
      <div class="card srv-row" data-id="${escapeHtml(s.id)}" data-host="${escapeHtml(s.host)}" data-port="${s.port}"
           style="display:flex;align-items:center;gap:10px;margin-bottom:8px;">
        <div style="flex:1;min-width:0;">
          <div class="card-title">${escapeHtml(s.name)}</div>
          <div class="card-meta">${escapeHtml(s.host)}${s.port !== 25565 ? ':' + s.port : ''} · <span class="srv-stat">pinging…</span></div>
        </div>
        <button class="btn srv-join" data-id="${escapeHtml(s.id)}">Join</button>
        <button class="btn srv-del" data-id="${escapeHtml(s.id)}" title="Remove" aria-label="Remove">🗑</button>
      </div>
    `).join('');

    for (const btn of host.querySelectorAll('.srv-del')) {
      btn.addEventListener('click', async () => {
        await window.fox.removeServer(btn.dataset.id);
        refresh();
      });
    }
    for (const btn of host.querySelectorAll('.srv-join')) {
      btn.addEventListener('click', async () => {
        const row = btn.closest('.srv-row');
        btn.disabled = true;
        flash(`Joining ${row.dataset.host}…`);
        const r = await window.fox.quickJoin({ host: row.dataset.host, port: Number(row.dataset.port) })
          .catch(e => ({ ok: false, error: e.message }));
        btn.disabled = false;
        if (!r || !r.ok) flash(`Join failed: ${(r && r.error) || 'unknown'}`);
      });
    }

    // Ping each row independently so a slow/dead server doesn't block the rest.
    for (const row of host.querySelectorAll('.srv-row')) {
      const stat = row.querySelector('.srv-stat');
      window.fox.pingServer({ host: row.dataset.host, port: Number(row.dataset.port) })
        .then(r => {
          if (r.ok) {
            const players = (r.online != null && r.max != null) ? `${r.online}/${r.max}` : '?';
            const motd = r.motd ? ` · ${r.motd}` : '';
            stat.textContent = `${players} players · ${r.latencyMs}ms${motd}`;
            stat.style.color = 'var(--success)';
          } else {
            stat.textContent = `offline (${r.error})`;
            stat.style.color = 'var(--danger)';
          }
        })
        .catch(() => { stat.textContent = 'offline'; });
    }
  }
}
