// Versions — manage the Minecraft installs Fox Launcher can launch.
//
// Two clear sections:
//   1. Compatible: versions runnable with the host's installed Java. The
//      green-bordered card highlights the current selection; clicking any
//      compatible version makes it the new default for Home → PLAY.
//   2. Legacy: versions present in <gameDir>/versions/ that need an older
//      Java than the user has installed. Shown so the user knows we *see*
//      them and isn't confused by a partial list.
//
// A "How to add a version" hint sits at the bottom — Fox Launcher doesn't
// download vanilla MC, so the path is "use the official launcher once."

export async function renderVersions(mount) {
  mount.innerHTML = `
    <h1 class="screen-title">Versions</h1>
    <p class="screen-sub">Pick which Minecraft install Fox Launcher uses.</p>
    <div id="versions-body"><div class="muted">Scanning…</div></div>
  `;
  await rebuild();

  async function rebuild() {
    const [s, vs] = await Promise.all([
      window.fox.getSettings(),
      window.fox.listVersionsEnriched(),
    ]);
    const body = document.getElementById('versions-body');

    if (!vs.exists) {
      body.innerHTML = `
        <div class="notice warn">
          Game directory not found: <code>${escapeHtml(vs.gameDir || '')}</code>.
          Install Minecraft via the <a href="#" id="link-mc">official launcher</a> first, then return here.
        </div>
        ${legacyHelpSection()}
      `;
      wireGetMcLink();
      return;
    }

    const compat = (vs.versions || []).filter(v => v.runnable);
    const legacy = (vs.versions || []).filter(v => !v.runnable);
    const selected = s.selectedVersion;

    body.innerHTML = `
      <div class="section">
        <div class="section-title">Compatible
          <span class="badge badge-ok" style="margin-left:8px;font-size:10px;">${compat.length}</span>
        </div>
        <div class="section-sub">
          Reading from <code>${escapeHtml(vs.gameDir)}</code>.
          Host Java: ${vs.hostJavaMajor ? `<span class="badge badge-ok">Java ${vs.hostJavaMajor}</span>` : '<span class="badge badge-error">missing</span>'}.
        </div>
        ${compat.length === 0 ? `
          <div class="muted">No installed version matches your Java. Install Minecraft 1.21+ via the official launcher and return here.</div>
        ` : `
          <div class="version-list">
            ${compat.map(v => versionRow(v, selected, true)).join('')}
          </div>
        `}
      </div>

      ${legacy.length ? `
        <div class="section">
          <div class="section-title">Legacy (needs older Java)
            <span class="badge badge-warn" style="margin-left:8px;font-size:10px;">${legacy.length}</span>
          </div>
          <div class="section-sub">These installs need a Java version older than what you've got. They're listed for reference — switching the host JDK is rarely worth it just to play 1.16.</div>
          <div class="version-list">
            ${legacy.map(v => versionRow(v, selected, false)).join('')}
          </div>
        </div>
      ` : ''}

      ${legacyHelpSection()}
    `;

    // ---- click-to-select on compatible rows ----
    for (const row of body.querySelectorAll('.version-row.compat')) {
      row.addEventListener('click', async () => {
        const id = row.dataset.id;
        await window.fox.patchSettings({ selectedVersion: id });
        await rebuild();
      });
    }

    wireGetMcLink();
  }

  function wireGetMcLink() {
    const lnk = document.getElementById('link-mc');
    if (lnk) lnk.addEventListener('click', (e) => {
      e.preventDefault();
      window.fox.openExternal('https://www.minecraft.net/en-us/download');
    });
    const fab = document.getElementById('link-fabric');
    if (fab) fab.addEventListener('click', (e) => {
      e.preventDefault();
      window.fox.openExternal('https://fabricmc.net/use/installer/');
    });
  }
}

function versionRow(v, selectedId, compat) {
  const isSelected = v.id === selectedId;
  const cls = `version-row ${compat ? 'compat clickable' : 'legacy'} ${isSelected ? 'selected' : ''}`;
  const loaderBadge = v.loader
    ? `<span class="badge badge-warn" style="font-size:10px;margin-left:6px;">${escapeHtml(v.loader)}</span>`
    : '';
  const javaBadge = compat
    ? `<span class="badge badge-ok" style="font-size:10px;">Java ${v.requiredJava}</span>`
    : `<span class="badge badge-error" style="font-size:10px;">needs Java ${v.requiredJava}</span>`;
  const typeBadge = v.type !== 'release'
    ? `<span class="badge" style="font-size:10px;background:rgba(255,255,255,0.06);color:var(--text-2);">${escapeHtml(v.type)}</span>`
    : '';
  return `
    <div class="${cls}" data-id="${escapeHtml(v.id)}">
      <div>
        <div class="version-id">${escapeHtml(v.id)}${loaderBadge}</div>
        <div class="muted" style="font-size:11px;margin-top:2px;">${typeBadge} ${javaBadge}</div>
      </div>
      <div>${isSelected ? '<span class="version-badge">SELECTED</span>' : ''}</div>
    </div>
  `;
}

function legacyHelpSection() {
  return `
    <div class="section">
      <div class="section-title">Adding a version</div>
      <div class="section-sub" style="line-height:1.6;">
        Fox Launcher reads versions from <code>&lt;game dir&gt;/versions</code> — it doesn't download vanilla MC itself (that subsystem is huge and well-handled by the official launcher). To add a version:
        <ol style="margin-top:8px;">
          <li>Open the <a href="#" id="link-mc">official Minecraft launcher</a> and run that version once. It downloads libraries, natives, and assets.</li>
          <li>For Fabric / Quilt support, run the <a href="#" id="link-fabric">Fabric installer</a> and pick the same MC version.</li>
          <li>Come back here — the version will appear under <strong>Compatible</strong> if your Java can run it.</li>
        </ol>
      </div>
    </div>
  `;
}

function escapeHtml(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}
