// Profiles 2.0 — card grid with one-click launch, templates, and isolation.
//
// Two-pane layout:
//   - Left: vertical list of profile *cards*. Each shows name, account
//           binding, isolation status, server, RAM, mod count, and a Play
//           button that atomically switches active and launches.
//   - Right: tabbed editor for the selected profile (Identity, Loadout,
//           Performance, Server & Display, Advanced).
//
// Two new concepts vs the old Profiles tab:
//   - **Isolated** profiles get their own gameDir + their own Microsoft
//     account vault under ~/.foxlauncher/instances/<id>/. This is the
//     "two different people, totally separate setups" mode.
//   - **Templates** seed a new profile with sensible defaults (Anarchy,
//     Ranked PvP, Casual, Modded, Vanilla-safe).
//
// All profile changes are saved on click, not on form blur — explicit Save
// button at the bottom of the editor pane.

let selectedId = null;
let mods = [];
let resourcePacksList = [];
let shaderPacksList = [];
let profilesCache = [];
let activeId = '';
let templatesCache = [];
let editorTab = 'identity';
let signedInUser = null; // { username, uuid } from auth status

// Survives tab switches — keyed by _profileId so stale draft is ignored on profile change.
let editorDraft = {};

/** Read whatever form fields are currently in the DOM and stash them in editorDraft. */
function flushCurrentTabToDraft() {
  const get = (id) => document.getElementById(id);
  // Identity
  if (get('ed-name'))    editorDraft.name    = get('ed-name').value;
  if (get('ed-notes'))   editorDraft.notes   = get('ed-notes').value;
  if (get('ed-locked'))  editorDraft.locked  = get('ed-locked').checked;
  // Performance
  if (get('ed-ramMin'))  editorDraft.ramMin  = get('ed-ramMin').value;
  if (get('ed-ramMax'))  editorDraft.ramMax  = get('ed-ramMax').value;
  if (get('ed-jvm'))     editorDraft.jvmArgs = get('ed-jvm').value;
  // Display
  if (get('ed-srvHost')) editorDraft.serverHost = get('ed-srvHost').value;
  if (get('ed-srvPort')) editorDraft.serverPort = get('ed-srvPort').value;
  if (get('ed-resW'))    editorDraft.resW    = get('ed-resW').value;
  if (get('ed-resH'))    editorDraft.resH    = get('ed-resH').value;
  if (get('ed-resFs'))   editorDraft.resFs   = get('ed-resFs').checked;
  // Advanced
  if (get('ed-gdir'))    editorDraft.gameDirOverride = get('ed-gdir').value;
}

// Same 8-color palette as the sidebar avatar dots so a profile keeps a
// stable visual identity across the whole UI.
const PROFILE_COLORS = [
  '#ff8c42', '#e05a5a', '#4ec27a', '#5b8cff',
  '#d97aed', '#f5b942', '#42d4c2', '#c2a852',
];
function profileColor(profile) {
  if (profile && profile.color) return profile.color;
  let hash = 0;
  for (const c of String((profile && profile.id) || '')) hash = ((hash * 31) + c.charCodeAt(0)) >>> 0;
  return PROFILE_COLORS[hash % PROFILE_COLORS.length];
}

export async function renderProfiles(mount) {
  const [doc, s, modsList, addonCatalog, templatesList, authStatus, rpList, spList] = await Promise.all([
    window.fox.listProfiles(),
    window.fox.getSettings(),
    window.fox.listMods().catch(() => []),
    window.fox.addonCatalog().catch(() => []),
    window.fox.profileTemplates().catch(() => []),
    window.fox.authStatus().catch(() => ({})),
    window.fox.listResourcePacks().catch(() => []),
    window.fox.listShaderPacks().catch(() => []),
  ]);
  profilesCache = doc.profiles;
  activeId = s.selectedProfile || '';
  mods = modsList;
  resourcePacksList = rpList;
  shaderPacksList = spList;
  templatesCache = templatesList;
  signedInUser = authStatus && authStatus.signedIn
    ? { username: authStatus.username, uuid: authStatus.uuid, guest: !!authStatus.guest }
    : null;
  renderProfiles._addons = addonCatalog;
  if (!selectedId || !profilesCache.find(p => p.id === selectedId)) {
    selectedId = activeId || (profilesCache[0] && profilesCache[0].id) || null;
  }

  mount.innerHTML = `
    <h1 class="screen-title">Profiles</h1>
    <p class="screen-sub">
      Each profile is a complete identity: mods, settings, server, and (for isolated profiles) its own
      worlds and Microsoft account. Click <strong>Play</strong> on any card to switch + launch in one go.
    </p>

    <div class="profile-layout">
      <div class="profile-list-pane">
        <div class="section">
          <div class="section-title">
            Profiles
            <span class="badge" style="margin-left:6px;font-size:10px;">${profilesCache.length}</span>
          </div>
          <div id="profile-cards"></div>
          <button class="btn btn-primary" id="btn-new-profile" style="margin-top:10px;width:100%;">
            + New profile
          </button>
          <button class="btn" id="btn-import-profile" style="margin-top:6px;width:100%;">
            Import from file…
          </button>
        </div>
      </div>

      <div class="profile-editor-pane">
        <div id="profile-editor"></div>
      </div>
    </div>

    <!-- Templates picker — shown when "New profile" is clicked -->
    <div class="modal-backdrop hidden" id="tpl-backdrop">
      <div class="modal" id="tpl-modal">
        <div class="modal-header">
          <div class="modal-title">Create a new profile</div>
          <button class="modal-close" id="tpl-close" aria-label="Close">&times;</button>
        </div>
        <div class="modal-sub">Pick a starting point. You can edit anything afterwards.</div>
        <div id="tpl-list"></div>
        <div class="modal-footer">
          <div class="field" style="flex:1">
            <label>Name</label>
            <input type="text" class="input" id="tpl-name" placeholder="e.g. 2b2t — Player1" maxlength="60" />
          </div>
          <button class="btn btn-primary" id="tpl-create" disabled>Create</button>
        </div>
      </div>
    </div>
  `;

  document.getElementById('btn-new-profile').addEventListener('click', openTemplatePicker);
  document.getElementById('btn-import-profile').addEventListener('click', async () => {
    const r = await window.fox.importProfile();
    if (r.cancelled) return;
    if (!r.ok) { alert('Import failed: ' + r.error); return; }
    const refreshed = await window.fox.listProfiles();
    profilesCache = refreshed.profiles;
    selectedId = r.profile.id;
    rerender();
  });

  rerenderList();
  rerenderEditor();
}

function rerender() {
  rerenderList();
  rerenderEditor();
}

// ---- profile cards (left pane) ---------------------------------------

function rerenderList() {
  const host = document.getElementById('profile-cards');
  if (!host) return;
  host.innerHTML = profilesCache.map(p => renderCard(p)).join('');
  for (const card of host.querySelectorAll('.profile-card')) {
    const id = card.dataset.id;
    // Whole card click → select for editing
    card.addEventListener('click', (e) => {
      // Don't steal clicks meant for the Play button
      if (e.target.closest('.profile-card-play')) return;
      selectedId = id;
      rerender();
    });
  }
  for (const btn of host.querySelectorAll('.profile-card-play')) {
    btn.addEventListener('click', async (e) => {
      e.stopPropagation();
      const id = btn.dataset.id;
      // Guard: don't try to launch a second instance while one is already running.
      const alreadyRunning = await window.fox.isRunning().catch(() => false);
      if (alreadyRunning) {
        alert('A game is already running. Close it before launching another profile.');
        return;
      }
      btn.disabled = true;
      btn.textContent = '…';
      try {
        const r = await window.fox.launchProfile(id);
        if (!r.ok) {
          // If the failure is auth-related (likely an empty vault for an
          // isolated profile bound to a different account), bring up the
          // login flow by reloading. Otherwise show the error inline.
          const isAuthErr = /not signed in|sign in|authenticat/i.test(r.error || '');
          if (isAuthErr) {
            // Profile was already switched server-side in profiles:launch
            // before the auth check failed, so reloading boots into the
            // sign-in flow against the new profile's empty vault.
            location.reload();
            return;
          }
          alert('Launch failed: ' + r.error);
          btn.disabled = false;
          btn.textContent = '▶ Play';
          return;
        }
        // Game is starting — refresh active state and route to Home so the
        // user sees the launch progress.
        activeId = id;
        location.hash = 'home';
      } catch (err) {
        alert('Launch failed: ' + err.message);
        btn.disabled = false;
        btn.textContent = '▶ Play';
      }
    });
  }
}

function renderCard(p) {
  const isSelected = p.id === selectedId;
  const isActive   = p.id === activeId;
  const color      = profileColor(p);

  // Account binding indicator
  let accountChip = '';
  if (p.accountUsername) {
    const mismatch = signedInUser && !signedInUser.guest && signedInUser.uuid && p.accountUuid && signedInUser.uuid !== p.accountUuid;
    accountChip = `<span class="profile-chip ${mismatch ? 'profile-chip-warn' : ''}" title="${mismatch ? 'Signed in as a different account' : 'Bound account'}">
      👤 ${escapeHtml(p.accountUsername)}${mismatch ? ' ⚠' : ''}
    </span>`;
  } else {
    accountChip = `<span class="profile-chip profile-chip-muted" title="Not yet bound to an account">👤 not bound</span>`;
  }

  const isolationChip = p.isolated
    ? `<span class="profile-chip profile-chip-iso" title="Isolated: own worlds, mods, and account">🛡 ISOLATED</span>`
    : `<span class="profile-chip profile-chip-muted" title="Shares the global .minecraft">🔗 linked</span>`;

  const serverChip = p.serverHost
    ? `<span class="profile-chip" title="Auto-joins on launch">🌐 ${escapeHtml(p.serverHost)}${p.serverPort ? ':' + p.serverPort : ''}</span>`
    : '';

  const ramChip = (p.ramMin || p.ramMax)
    ? `<span class="profile-chip">💾 ${p.ramMin || '—'}–${p.ramMax || '—'}G</span>`
    : '';

  const modsOffChip = (p.disabledMods && p.disabledMods.length)
    ? `<span class="profile-chip profile-chip-warn">${p.disabledMods.length} mods off</span>`
    : '';

  const lockedChip = p.locked
    ? `<span class="profile-chip profile-chip-warn" title="Locked — unlock to edit">🔒 locked</span>`
    : '';

  const tplChip = p.templateId
    ? `<span class="profile-chip profile-chip-muted" title="Created from template">📋 ${escapeHtml(p.templateId)}</span>`
    : '';

  return `
    <div class="profile-card ${isSelected ? 'selected' : ''} ${isActive ? 'active' : ''}" data-id="${escapeHtml(p.id)}">
      <div class="profile-card-ribbon" style="background:${escapeHtml(color)};"></div>
      <div class="profile-card-body">
        <div class="profile-card-header">
          <div class="profile-card-name">${escapeHtml(p.name)}${isActive ? ' <span class="badge badge-ok" style="font-size:9px;">ACTIVE</span>' : ''}</div>
          <button class="btn btn-primary profile-card-play" data-id="${escapeHtml(p.id)}" title="Switch to this profile and launch">▶ Play</button>
        </div>
        <div class="profile-card-chips">
          ${accountChip}
          ${isolationChip}
          ${serverChip}
          ${ramChip}
          ${modsOffChip}
          ${lockedChip}
          ${tplChip}
        </div>
        <div class="profile-card-foot">
          ${p.lastPlayedAt ? `Last played ${formatRelative(p.lastPlayedAt)}` : 'Never played'}
        </div>
      </div>
    </div>
  `;
}

// ---- editor (right pane, tabbed) ------------------------------------

function rerenderEditor() {
  const host = document.getElementById('profile-editor');
  if (!host) return;
  const profile = profilesCache.find(p => p.id === selectedId);
  if (!profile) {
    host.innerHTML = `
      <div class="muted" style="padding:24px;">
        Pick a profile from the list, or click <strong>+ New profile</strong>.
      </div>`;
    return;
  }

  // Clear the draft whenever we switch to a different profile so stale values
  // from a previous profile don't bleed into the new one.
  if (editorDraft._profileId !== profile.id) {
    editorDraft = { _profileId: profile.id };
  }

  const isActive = profile.id === activeId;
  const color = profileColor(profile);

  host.innerHTML = `
    <div class="section profile-editor-header">
      <div class="profile-editor-title-row">
        <div class="profile-editor-color-dot" style="background:${escapeHtml(color)};"></div>
        <div class="profile-editor-title">
          ${escapeHtml(profile.name)}
          ${isActive ? '<span class="badge badge-ok" style="font-size:10px;">ACTIVE</span>' : ''}
          ${profile.locked ? '<span class="badge badge-warn" style="font-size:10px;">LOCKED</span>' : ''}
        </div>
        <div class="profile-editor-actions">
          ${isActive ? '' : '<button class="btn btn-primary" id="btn-activate">Set as active</button>'}
          <button class="btn" id="btn-clone">Clone</button>
          <button class="btn" id="btn-export">Export…</button>
          ${profile.id === 'default' ? '' : '<button class="btn btn-danger" id="btn-delete">Delete</button>'}
        </div>
      </div>

      <div class="tabs">
        ${tabBtn('identity',    '👤 Identity')}
        ${tabBtn('loadout',     '📦 Mods & Addons')}
        ${tabBtn('performance', '⚡ Performance')}
        ${tabBtn('display',     '🌐 Server & Display')}
        ${tabBtn('advanced',    '⚙ Advanced')}
      </div>
    </div>

    <div id="profile-tab-content"></div>

    <div class="section" style="display:flex;gap:8px;align-items:center;">
      <button class="btn btn-primary" id="btn-save" ${profile.locked ? 'disabled' : ''}>Save</button>
      ${profile.locked ? '<span class="muted" style="font-size:11px;">Profile is locked. Unlock in the Identity tab to edit.</span>' : ''}
      <span class="status muted" id="ed-status"></span>
    </div>
  `;

  // Tab switching — flush current tab's fields to draft before re-rendering
  for (const t of host.querySelectorAll('.tab-btn')) {
    t.addEventListener('click', () => {
      flushCurrentTabToDraft();
      editorTab = t.dataset.tab;
      rerenderEditor();
    });
  }

  // ---- top-level button handlers ----
  const $ = (id) => document.getElementById(id);

  if (!isActive && $('btn-activate')) {
    $('btn-activate').addEventListener('click', async () => {
      await window.fox.setActiveProfile(profile.id);
      activeId = profile.id;
      rerender();
    });
  }

  $('btn-clone') && $('btn-clone').addEventListener('click', async () => {
    const newName = prompt('Name for the cloned profile:', `${profile.name} (copy)`);
    if (!newName) return;
    const baseId = newName.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || `${profile.id}-copy`;
    const c = await window.fox.cloneProfile(profile.id, { id: baseId, name: newName });
    const refreshed = await window.fox.listProfiles();
    profilesCache = refreshed.profiles;
    selectedId = c.id;
    rerender();
  });

  $('btn-export') && $('btn-export').addEventListener('click', async () => {
    const r = await window.fox.exportProfile(profile.id);
    if (r.cancelled) return;
    if (!r.ok) { alert('Export failed: ' + r.error); return; }
    showStatus(`Exported to ${r.path}`, 'success');
  });

  $('btn-delete') && $('btn-delete').addEventListener('click', async () => {
    if (profile.locked) { alert('Profile is locked. Unlock first.'); return; }
    if (!confirm(`Delete profile "${profile.name}"?\n\nIsolated profiles keep their instance directory on disk — you'll need to delete it manually if you want the worlds gone.`)) return;
    await window.fox.deleteProfile(profile.id);
    selectedId = null;
    const refreshed = await window.fox.listProfiles();
    profilesCache = refreshed.profiles;
    if (!selectedId) selectedId = profilesCache[0] && profilesCache[0].id;
    rerender();
  });

  // Render the active tab
  renderTabContent(profile);

  // Wire Save once tab content is in DOM
  $('btn-save') && $('btn-save').addEventListener('click', () => saveCurrentEditor(profile));
}

function tabBtn(id, label) {
  return `<button class="tab-btn ${editorTab === id ? 'active' : ''}" data-tab="${id}">${label}</button>`;
}

function renderTabContent(profile) {
  const host = document.getElementById('profile-tab-content');
  if (!host) return;
  switch (editorTab) {
    case 'loadout':     host.innerHTML = renderLoadoutTab(profile);     wireLoadoutTab(profile); break;
    case 'performance': host.innerHTML = renderPerfTab(profile);        break;
    case 'display':     host.innerHTML = renderDisplayTab(profile);     break;
    case 'advanced':    host.innerHTML = renderAdvancedTab(profile);    wireAdvancedTab(profile); break;
    case 'identity':
    default:            host.innerHTML = renderIdentityTab(profile);    wireIdentityTab(profile); break;
  }
}

// ---- Identity tab ----------------------------------------------------

function renderIdentityTab(profile) {
  const accountBound = !!profile.accountUsername;
  const mismatch = signedInUser && !signedInUser.guest && signedInUser.uuid && profile.accountUuid && signedInUser.uuid !== profile.accountUuid;

  return `
    <div class="section">
      <div class="section-title">Identity</div>
      <div class="two-col">
        <div class="field">
          <label>Name</label>
          <input type="text" class="input" id="ed-name" value="${escapeHtml(profile.name)}" maxlength="60" ${profile.locked ? 'disabled' : ''} />
        </div>
        <div class="field">
          <label>ID (read-only)</label>
          <input type="text" class="input" value="${escapeHtml(profile.id)}" disabled />
        </div>
      </div>
      <div class="field">
        <label>Notes</label>
        <input type="text" class="input" id="ed-notes" value="${escapeHtml(profile.notes || '')}" maxlength="240" placeholder="What's this profile for?" ${profile.locked ? 'disabled' : ''} />
      </div>
      <div class="field">
        <label class="checkbox">
          <input type="checkbox" id="ed-locked" ${profile.locked ? 'checked' : ''} />
          Lock this profile (Save / Delete disabled until unlocked)
        </label>
      </div>
    </div>

    <div class="section">
      <div class="section-title">🛡 Isolation
        ${profile.isolated ? '<span class="badge badge-ok" style="font-size:10px;">ON</span>' : '<span class="badge" style="font-size:10px;">off</span>'}
      </div>
      <div class="section-sub">
        When isolated, this profile gets its own <strong>game directory, mods folder, worlds, configs, and Microsoft account vault</strong>
        under <code>~/.foxlauncher/instances/${escapeHtml(profile.id)}/</code>. Use this for "totally different setup, totally different person" — switching to this profile signs you in to its bound account, and your other profiles keep their own logins.
      </div>
      <label class="checkbox" style="margin-top:8px;">
        <input type="checkbox" id="ed-isolated" ${profile.isolated ? 'checked' : ''} ${profile.locked ? 'disabled' : ''} />
        Isolated (separate everything)
      </label>
      ${profile.isolated ? `
      <div style="margin-top:8px;">
        <button class="btn" id="btn-open-instance">Open instance folder…</button>
      </div>` : ''}
    </div>

    <div class="section">
      <div class="section-title">👤 Bound Microsoft account</div>
      <div class="section-sub">${accountBound
        ? 'Stamped automatically the last time this profile launched successfully.'
        : 'No account bound yet. The first successful launch will stamp the signed-in account here.'}</div>

      ${accountBound ? `
        <div class="account-binding-row ${mismatch ? 'mismatch' : ''}">
          <div>
            <strong>${escapeHtml(profile.accountUsername)}</strong>
            <div class="muted" style="font-size:11px;">${escapeHtml(profile.accountUuid || '')}</div>
          </div>
          ${mismatch ? `
            <div class="account-warning">
              ⚠ You are signed in as <strong>${escapeHtml(signedInUser.username)}</strong>, not this profile's bound account.
              ${profile.isolated ? 'Activating this isolated profile will use its own auth vault — sign in again as the right user when prompted.' : 'Linked profiles share one account; consider isolating this profile if you want separate logins.'}
            </div>` : ''}
        </div>
        <div style="margin-top:8px;">
          <button class="btn btn-danger" id="btn-clear-binding" ${profile.locked ? 'disabled' : ''}>Clear binding</button>
        </div>
      ` : ''}
    </div>

    <div class="section">
      <div class="section-title">🎨 Color</div>
      <div class="section-sub">Used in the sidebar dot, profile card ribbon, and account badge so you can tell at a glance which profile is active.</div>
      <div class="color-swatches">
        ${PROFILE_COLORS.map(c => `
          <button class="color-swatch ${ (profile.color || profileColor(profile)) === c ? 'selected' : ''}" data-color="${c}" style="background:${c};" ${profile.locked ? 'disabled' : ''} title="${c}"></button>
        `).join('')}
        <button class="color-swatch ${!profile.color ? 'selected' : ''}" id="color-auto" data-color="" title="Auto (derived from id)" ${profile.locked ? 'disabled' : ''}>Auto</button>
      </div>
    </div>
  `;
}

function wireIdentityTab(profile) {
  const $ = (id) => document.getElementById(id);

  $('ed-isolated') && $('ed-isolated').addEventListener('change', async (e) => {
    const want = e.target.checked;
    if (want && !confirm(`Isolate "${profile.name}"?\n\nThis creates a private game directory at instances/${profile.id}/ and copies your current mods/ folder into it. Your worlds, configs, and Microsoft account become separate from your other profiles.`)) {
      e.target.checked = false;
      return;
    }
    e.target.disabled = true;
    const r = await window.fox.setProfileIsolation(profile.id, want);
    e.target.disabled = false;
    if (!r.ok) {
      alert('Failed: ' + r.error);
      e.target.checked = !want;
      return;
    }
    const refreshed = await window.fox.listProfiles();
    profilesCache = refreshed.profiles;
    rerender();
  });

  $('btn-open-instance') && $('btn-open-instance').addEventListener('click', async () => {
    await window.fox.openInstanceDir(profile.id);
  });

  $('btn-clear-binding') && $('btn-clear-binding').addEventListener('click', async () => {
    if (!confirm('Clear the bound Microsoft account? The next launch will rebind to whoever is signed in.')) return;
    await window.fox.saveProfile({ ...profile, accountUsername: null, accountUuid: null });
    const refreshed = await window.fox.listProfiles();
    profilesCache = refreshed.profiles;
    rerender();
  });

  for (const sw of document.querySelectorAll('.color-swatch')) {
    sw.addEventListener('click', () => {
      for (const s of document.querySelectorAll('.color-swatch')) s.classList.remove('selected');
      sw.classList.add('selected');
      // Stash on closure for save handler
      wireIdentityTab._pendingColor = sw.dataset.color || null;
    });
  }
}

// ---- Loadout tab (mods + addons) ------------------------------------

function renderLoadoutTab(profile) {
  const disabledSet = new Set(profile.disabledMods || []);
  const disabledAddons = new Set(profile.disabledAddons || []);
  return `
    <div class="section">
      <div class="section-title">Mods (<span id="ed-mod-count">${mods.length}</span>)</div>
      <div class="section-sub">
        ${profile.isolated
          ? 'These are the mods in this isolated profile\'s instance dir. Untick to disable for this profile only.'
          : 'These are the mods in your global .minecraft. Untick to disable for this profile only — the file gets renamed to <code>.jar.disabled</code> at launch.'}
      </div>
      <div id="ed-mod-list" class="mod-list">
        ${renderModList(mods, disabledSet, profile)}
      </div>
      <div style="display:flex;gap:8px;margin-top:10px;flex-wrap:wrap;">
        <button class="btn btn-primary" id="btn-mods-add" ${profile.locked ? 'disabled' : ''}>+ Add mod…</button>
        <button class="btn" id="btn-mods-folder">Open mods folder</button>
        <button class="btn" id="btn-mods-refresh">Refresh list</button>
        <button class="btn" id="btn-mods-rec-pack" title="Downloads Sodium, Lithium, Iris, EMF, ETF, AppleSkin and more from Modrinth">⬇ Recommended pack</button>
        <div style="flex:1"></div>
        <button class="btn" id="btn-mods-all-on" ${profile.locked ? 'disabled' : ''}>Enable all</button>
        <button class="btn" id="btn-mods-all-off" ${profile.locked ? 'disabled' : ''}>Disable all (vanilla-safe)</button>
      </div>
      <div id="rec-pack-progress" style="display:none;margin-top:6px;font-size:12px;color:var(--text-muted)"></div>
    </div>

    <div class="section">
      <div class="section-title">Optional Fox Client features
        <span class="badge badge-warn" style="margin-left:6px;font-size:10px;">PROFILE</span>
      </div>
      <div class="section-sub">Disable individual gray-zone modules. The mod skips registering them entirely on launch — they don't tick, render, or show up in the in-game ClickGUI.</div>
      ${renderAddonList(renderProfiles._addons || [], disabledAddons, profile.locked)}
    </div>

    <div class="section">
      <div class="section-title">Resource Packs (<span id="ed-rp-count">${resourcePacksList.length}</span>)</div>
      <div class="section-sub">Files in <code>resourcepacks/</code>. Minecraft manages the active pack stack in-game via Options → Resource Packs.</div>
      <div id="ed-rp-list">
        ${renderPackList(resourcePacksList)}
      </div>
      <div style="display:flex;gap:8px;margin-top:10px;flex-wrap:wrap;">
        <button class="btn btn-primary" id="btn-rp-add" ${profile.locked ? 'disabled' : ''}>+ Add resource pack…</button>
        <button class="btn" id="btn-rp-folder">Open folder</button>
        <button class="btn" id="btn-rp-refresh">Refresh</button>
      </div>
    </div>

    <div class="section">
      <div class="section-title">Shader Packs (<span id="ed-sp-count">${shaderPacksList.length}</span>)</div>
      <div class="section-sub">Files in <code>shaderpacks/</code>. Requires Iris Shaders mod. Select the active shader in-game via Options → Video Settings → Shader Packs.</div>
      <div id="ed-sp-list">
        ${renderPackList(shaderPacksList)}
      </div>
      <div style="display:flex;gap:8px;margin-top:10px;flex-wrap:wrap;">
        <button class="btn btn-primary" id="btn-sp-add" ${profile.locked ? 'disabled' : ''}>+ Add shader pack…</button>
        <button class="btn" id="btn-sp-folder">Open folder</button>
        <button class="btn" id="btn-sp-refresh">Refresh</button>
      </div>
    </div>
  `;
}

function wireLoadoutTab(profile) {
  const host = document.getElementById('profile-editor');
  if (!host) return;
  const disabledSet = new Set(profile.disabledMods || []);
  const disabledAddonsSet = new Set(profile.disabledAddons || []);

  // Stash for save
  wireLoadoutTab._disabledSet = disabledSet;
  wireLoadoutTab._disabledAddonsSet = disabledAddonsSet;

  for (const cb of host.querySelectorAll('.mod-toggle')) {
    cb.addEventListener('change', (e) => {
      const baseName = e.target.dataset.mod;
      if (e.target.checked) disabledSet.delete(baseName);
      else disabledSet.add(baseName);
    });
  }
  for (const cb of host.querySelectorAll('.addon-toggle')) {
    cb.addEventListener('change', (e) => {
      const id = e.target.dataset.addon;
      if (e.target.checked) disabledAddonsSet.delete(id);
      else disabledAddonsSet.add(id);
    });
  }

  const $ = (id) => document.getElementById(id);

  // Special Kitsune-client toggle
  const kitsuneCb = host.querySelector('#ed-keep-kitsune');
  if (kitsuneCb) kitsuneCb.addEventListener('change', (e) => {
    profile.keepKitsuneEnabled = e.target.checked;
  });

  $('btn-mods-all-on') && $('btn-mods-all-on').addEventListener('click', () => {
    disabledSet.clear();
    $('ed-mod-list').innerHTML = renderModList(mods, disabledSet, profile);
    wireLoadoutTab(profile);
  });
  $('btn-mods-all-off') && $('btn-mods-all-off').addEventListener('click', () => {
    for (const m of mods) if (!m.isKitsune) disabledSet.add(m.name);
    $('ed-mod-list').innerHTML = renderModList(mods, disabledSet, profile);
    wireLoadoutTab(profile);
  });
  $('btn-mods-refresh').addEventListener('click', refreshMods);
  $('btn-mods-folder').addEventListener('click', () => window.fox.openModsFolder());

  $('btn-mods-rec-pack') && $('btn-mods-rec-pack').addEventListener('click', async () => {
    const btn      = $('btn-mods-rec-pack');
    const progress = $('rec-pack-progress');
    btn.disabled = true;
    btn.textContent = '⬇ Installing…';
    progress.style.display = '';
    progress.textContent   = 'Starting…';

    const unsub = window.fox.onRecommendedProgress((data) => {
      progress.textContent = data.message || '';
    });

    let results;
    try {
      results = await window.fox.recommendedInstall({ essentialOnly: false });
    } catch (e) {
      progress.textContent = 'Error: ' + (e.message || e);
      btn.disabled = false;
      btn.textContent = '⬇ Recommended pack';
      if (typeof unsub === 'function') unsub();
      return;
    }
    if (typeof unsub === 'function') unsub();

    if (!results || !results.ok) {
      progress.textContent = 'Failed: ' + (results && results.error ? results.error : 'unknown error');
      btn.disabled = false;
      btn.textContent = '⬇ Recommended pack';
      return;
    }

    const installed = (results.results || []).filter(r => r.status === 'installed').map(r => r.displayName || r.slug);
    const skipped   = (results.results || []).filter(r => r.status === 'skipped').length;
    const errors    = (results.results || []).filter(r => r.status === 'error');

    if (installed.length) {
      progress.textContent = `Installed: ${installed.join(', ')}` + (errors.length ? ` · ${errors.length} failed` : '');
    } else if (errors.length) {
      progress.textContent = `${errors.length} mod(s) failed to download.`;
    } else {
      progress.textContent = `All ${skipped} mods already present — nothing to do.`;
    }

    btn.disabled = false;
    btn.textContent = '⬇ Recommended pack';
    await refreshMods();
  });
  $('btn-mods-add') && $('btn-mods-add').addEventListener('click', async () => {
    const r = await window.fox.addMods();
    if (r.cancelled) return;
    if (!r.ok) { alert('Couldn\'t add mods: ' + (r.error || 'unknown error')); return; }
    await refreshMods();
  });

  for (const btn of host.querySelectorAll('.mod-delete')) {
    btn.addEventListener('click', async (e) => {
      e.preventDefault(); e.stopPropagation();
      const name = btn.dataset.mod;
      if (!name) return;
      if (!confirm(`Permanently delete ${name}?`)) return;
      const r = await window.fox.deleteMod(name);
      if (!r.ok) { alert('Delete failed: ' + (r.error || 'unknown')); return; }
      disabledSet.delete(name);
      await refreshMods();
    });
  }

  async function refreshMods() {
    mods = await window.fox.listMods().catch(() => []);
    $('ed-mod-count').textContent = mods.length;
    $('ed-mod-list').innerHTML = renderModList(mods, disabledSet, profile);
    wireLoadoutTab(profile);
  }

  // ---- resource packs ----

  async function refreshResourcePacks() {
    resourcePacksList = await window.fox.listResourcePacks().catch(() => []);
    $('ed-rp-count').textContent = resourcePacksList.length;
    $('ed-rp-list').innerHTML = renderPackList(resourcePacksList);
    wirePackDeleteButtons('ed-rp-list', window.fox.deleteResourcePack, refreshResourcePacks);
  }

  $('btn-rp-folder') && $('btn-rp-folder').addEventListener('click', () => window.fox.openResourcePacksFolder());
  $('btn-rp-refresh') && $('btn-rp-refresh').addEventListener('click', refreshResourcePacks);
  $('btn-rp-add') && $('btn-rp-add').addEventListener('click', async () => {
    const r = await window.fox.addResourcePacks();
    if (r && !r.cancelled) await refreshResourcePacks();
  });
  wirePackDeleteButtons('ed-rp-list', window.fox.deleteResourcePack, refreshResourcePacks);

  // ---- shader packs ----

  async function refreshShaderPacks() {
    shaderPacksList = await window.fox.listShaderPacks().catch(() => []);
    $('ed-sp-count').textContent = shaderPacksList.length;
    $('ed-sp-list').innerHTML = renderPackList(shaderPacksList);
    wirePackDeleteButtons('ed-sp-list', window.fox.deleteShaderPack, refreshShaderPacks);
  }

  $('btn-sp-folder') && $('btn-sp-folder').addEventListener('click', () => window.fox.openShadersFolder());
  $('btn-sp-refresh') && $('btn-sp-refresh').addEventListener('click', refreshShaderPacks);
  $('btn-sp-add') && $('btn-sp-add').addEventListener('click', async () => {
    const r = await window.fox.addShaderPacks();
    if (r && !r.cancelled) await refreshShaderPacks();
  });
  wirePackDeleteButtons('ed-sp-list', window.fox.deleteShaderPack, refreshShaderPacks);
}

/** Attach click handlers to every .pack-delete button inside containerElId. */
function wirePackDeleteButtons(containerElId, deleteFn, refreshFn) {
  const container = document.getElementById(containerElId);
  if (!container) return;
  for (const btn of container.querySelectorAll('.pack-delete')) {
    btn.addEventListener('click', async (e) => {
      e.preventDefault(); e.stopPropagation();
      const name = btn.dataset.pack;
      if (!name) return;
      if (!confirm(`Permanently delete ${name}?`)) return;
      const r = await deleteFn(name);
      if (r && !r.ok) { alert('Delete failed: ' + (r.error || 'unknown')); return; }
      await refreshFn();
    });
  }
}

// ---- Performance tab -------------------------------------------------

function renderPerfTab(profile) {
  return `
    <div class="section">
      <div class="section-title">RAM allocation</div>
      <div class="section-sub">Leave blank to use the global Settings → Memory values.</div>
      <div class="two-col">
        <div class="field">
          <label>Min RAM (GB)</label>
          <input type="number" class="input" id="ed-ramMin" value="${profile.ramMin ?? ''}" min="1" max="64" placeholder="(global)" ${profile.locked ? 'disabled' : ''} />
        </div>
        <div class="field">
          <label>Max RAM (GB)</label>
          <input type="number" class="input" id="ed-ramMax" value="${profile.ramMax ?? ''}" min="1" max="64" placeholder="(global)" ${profile.locked ? 'disabled' : ''} />
        </div>
      </div>
    </div>

    <div class="section">
      <div class="section-title">JVM args</div>
      <div class="section-sub">Power-user JVM flags appended after Fox Launcher's defaults. Whitespace-separated.</div>
      <div class="field">
        <input type="text" class="input" id="ed-jvm" value="${escapeHtml(profile.jvmArgs || '')}" placeholder="-XX:+UseStringDeduplication -Dfoo=bar" ${profile.locked ? 'disabled' : ''} />
      </div>
    </div>
  `;
}

// ---- Server & Display tab -------------------------------------------

function renderDisplayTab(profile) {
  return `
    <div class="section">
      <div class="section-title">Auto-join server</div>
      <div class="section-sub">If set, Minecraft connects directly to this server on launch.</div>
      <div class="two-col">
        <div class="field">
          <label>Host</label>
          <input type="text" class="input" id="ed-srvHost" value="${escapeHtml(profile.serverHost || '')}" placeholder="e.g. mc.hypixel.net" ${profile.locked ? 'disabled' : ''} />
        </div>
        <div class="field">
          <label>Port (optional)</label>
          <input type="number" class="input" id="ed-srvPort" value="${profile.serverPort ?? ''}" min="1" max="65535" placeholder="25565" ${profile.locked ? 'disabled' : ''} />
        </div>
      </div>
    </div>

    <div class="section">
      <div class="section-title">Resolution override</div>
      <div class="section-sub">Useful when one profile is fullscreen PvP and another is windowed casual.</div>
      <div class="two-col">
        <div class="field">
          <label>Width</label>
          <input type="number" class="input" id="ed-resW" value="${profile.resolution?.width ?? ''}" min="320" placeholder="(global)" ${profile.locked ? 'disabled' : ''} />
        </div>
        <div class="field">
          <label>Height</label>
          <input type="number" class="input" id="ed-resH" value="${profile.resolution?.height ?? ''}" min="240" placeholder="(global)" ${profile.locked ? 'disabled' : ''} />
        </div>
      </div>
      <label class="checkbox" style="margin-top:8px;">
        <input type="checkbox" id="ed-resFs" ${profile.resolution?.fullscreen ? 'checked' : ''} ${profile.locked ? 'disabled' : ''} />
        Force fullscreen for this profile
      </label>
    </div>
  `;
}

// ---- Advanced tab ---------------------------------------------------

function renderAdvancedTab(profile) {
  return `
    <div class="section">
      <div class="section-title">Game directory override</div>
      <div class="section-sub">
        ${profile.isolated
          ? 'This profile is <strong>isolated</strong>, so the override is ignored — its instance directory is used instead.'
          : 'Use a different .minecraft for this profile (separate worlds, configs, mods/). Leave blank to use the global game directory.'}
      </div>
      <div class="field">
        <div class="input-row">
          <input type="text" class="input" id="ed-gdir" value="${escapeHtml(profile.gameDirOverride || '')}" placeholder="(global game directory)" ${(profile.locked || profile.isolated) ? 'disabled' : ''} />
          <button class="btn" id="ed-gdir-browse" ${(profile.locked || profile.isolated) ? 'disabled' : ''}>Browse…</button>
          <button class="btn" id="ed-gdir-clear" ${(profile.locked || profile.isolated) ? 'disabled' : ''}>Clear</button>
        </div>
      </div>
    </div>

    <div class="section">
      <div class="section-title">Apply template</div>
      <div class="section-sub">Reset profile fields to a curated preset. Existing name + notes are kept.</div>
      <div class="field">
        <select class="input" id="ed-tpl-select" ${profile.locked ? 'disabled' : ''}>
          <option value="">— pick a template —</option>
          ${templatesCache.map(t => `<option value="${escapeHtml(t.id)}" title="${escapeHtml(t.description)}">${escapeHtml(t.label)}</option>`).join('')}
        </select>
      </div>
      <button class="btn" id="btn-apply-template" ${profile.locked ? 'disabled' : ''}>Apply template</button>
    </div>
  `;
}

function wireAdvancedTab(profile) {
  const $ = (id) => document.getElementById(id);
  const gdirBrowse = $('ed-gdir-browse');
  const gdirClear  = $('ed-gdir-clear');
  if (gdirBrowse) gdirBrowse.addEventListener('click', async () => {
    const p = await window.fox.browseGameDir();
    if (p) $('ed-gdir').value = p;
  });
  if (gdirClear) gdirClear.addEventListener('click', () => { $('ed-gdir').value = ''; });

  $('btn-apply-template') && $('btn-apply-template').addEventListener('click', async () => {
    const tpl = $('ed-tpl-select').value;
    if (!tpl) return;
    if (!confirm(`Apply template "${tpl}" to "${profile.name}"?\n\nFields the template defines will be overwritten.`)) return;
    const r = await window.fox.applyProfileTemplate(profile.id, tpl);
    if (!r.ok) { alert('Failed: ' + r.error); return; }
    const refreshed = await window.fox.listProfiles();
    profilesCache = refreshed.profiles;
    rerender();
  });
}

// ---- Save handler ---------------------------------------------------

async function saveCurrentEditor(profile) {
  if (profile.locked) return;

  // Flush whatever tab is currently visible so its values are in the draft too.
  flushCurrentTabToDraft();

  const $ = (id) => document.getElementById(id);
  const d = editorDraft._profileId === profile.id ? editorDraft : {};
  const updated = { ...profile };

  // Helper: prefer live DOM element, fall back to draft, fall back to profile value.
  const field     = (id, dk, fallback) => { const el = $(id); return el ? el.value : (dk in d ? d[dk] : fallback); };
  const fieldBool = (id, dk, fallback) => { const el = $(id); return el ? el.checked : (dk in d ? d[dk] : fallback); };

  // Identity tab
  updated.name   = (field('ed-name',   'name',   profile.name)   || '').trim() || profile.id;
  updated.notes  = field('ed-notes',  'notes',  profile.notes  || '');
  updated.locked = fieldBool('ed-locked', 'locked', profile.locked || false);
  if ('_pendingColor' in wireIdentityTab) {
    updated.color = wireIdentityTab._pendingColor || null;
    delete wireIdentityTab._pendingColor;
  }

  // Loadout tab
  if (wireLoadoutTab._disabledSet) {
    updated.disabledMods = [...wireLoadoutTab._disabledSet];
  }
  if (wireLoadoutTab._disabledAddonsSet) {
    updated.disabledAddons = [...wireLoadoutTab._disabledAddonsSet];
  }
  // keepKitsuneEnabled is mutated in-place on `profile` by its checkbox
  updated.keepKitsuneEnabled = profile.keepKitsuneEnabled !== false;

  // Performance tab
  const ramMinStr = field('ed-ramMin', 'ramMin', profile.ramMin != null ? String(profile.ramMin) : '');
  const ramMaxStr = field('ed-ramMax', 'ramMax', profile.ramMax != null ? String(profile.ramMax) : '');
  updated.ramMin  = ramMinStr === '' ? null : Number(ramMinStr);
  updated.ramMax  = ramMaxStr === '' ? null : Number(ramMaxStr);
  updated.jvmArgs = field('ed-jvm', 'jvmArgs', profile.jvmArgs || '');

  // Display tab
  updated.serverHost = field('ed-srvHost', 'serverHost', profile.serverHost || '').trim();
  const srvPortStr   = field('ed-srvPort', 'serverPort', profile.serverPort != null ? String(profile.serverPort) : '');
  updated.serverPort = srvPortStr === '' ? null : Number(srvPortStr);
  const resWStr  = field('ed-resW',  'resW',  profile.resolution?.width  != null ? String(profile.resolution.width)  : '');
  const resHStr  = field('ed-resH',  'resH',  profile.resolution?.height != null ? String(profile.resolution.height) : '');
  const resFs    = fieldBool('ed-resFs', 'resFs', profile.resolution?.fullscreen || false);
  const resW     = resWStr !== '' ? Number(resWStr) : null;
  const resH     = resHStr !== '' ? Number(resHStr) : null;
  updated.resolution = (resW != null || resH != null || resFs) ? { width: resW, height: resH, fullscreen: resFs } : null;

  // Advanced
  updated.gameDirOverride = field('ed-gdir', 'gameDirOverride', profile.gameDirOverride || '').trim();

  if (updated.ramMin && updated.ramMax && updated.ramMin > updated.ramMax) {
    showStatus('Min RAM cannot exceed max.', 'error');
    return;
  }

  const doc = await window.fox.saveProfile(updated);
  profilesCache = doc.profiles;
  showStatus('Saved ✓', 'success');
  rerenderList();
}

function showStatus(text, kind) {
  const el = document.getElementById('ed-status');
  if (!el) return;
  const color = kind === 'success' ? 'var(--success)' : kind === 'error' ? 'var(--danger)' : 'var(--muted)';
  el.innerHTML = `<span style="color:${color};">${escapeHtml(text)}</span>`;
  setTimeout(() => { if (el.isConnected) el.textContent = ''; }, 2400);
}

// ---- Template picker modal ------------------------------------------

function openTemplatePicker() {
  const backdrop = document.getElementById('tpl-backdrop');
  const list = document.getElementById('tpl-list');
  const nameInput = document.getElementById('tpl-name');
  const createBtn = document.getElementById('tpl-create');
  let pickedTpl = 'blank';

  list.innerHTML = templatesCache.map(t => `
    <button class="tpl-card ${t.id === 'blank' ? 'selected' : ''}" data-id="${escapeHtml(t.id)}">
      <div class="tpl-card-label">${escapeHtml(t.label)}</div>
      <div class="tpl-card-desc">${escapeHtml(t.description)}</div>
    </button>
  `).join('');

  for (const card of list.querySelectorAll('.tpl-card')) {
    card.addEventListener('click', () => {
      for (const c of list.querySelectorAll('.tpl-card')) c.classList.remove('selected');
      card.classList.add('selected');
      pickedTpl = card.dataset.id;
    });
  }

  nameInput.value = '';
  createBtn.disabled = true;
  nameInput.addEventListener('input', () => {
    createBtn.disabled = !nameInput.value.trim();
  });

  const close = () => backdrop.classList.add('hidden');
  document.getElementById('tpl-close').onclick = close;
  backdrop.onclick = (e) => { if (e.target === backdrop) close(); };

  createBtn.onclick = async () => {
    const name = nameInput.value.trim();
    if (!name) return;
    const id = `${name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'profile'}-${Date.now().toString(36)}`;
    // Create the bare profile, then apply the template if not blank.
    await window.fox.saveProfile({ id, name, notes: '', templateId: pickedTpl });
    if (pickedTpl && pickedTpl !== 'blank') {
      await window.fox.applyProfileTemplate(id, pickedTpl);
    }
    selectedId = id;
    const refreshed = await window.fox.listProfiles();
    profilesCache = refreshed.profiles;
    close();
    rerender();
  };

  backdrop.classList.remove('hidden');
  setTimeout(() => nameInput.focus(), 50);
}

// ---- mod / addon list helpers (mostly unchanged from the old screen) ----

function renderAddonList(catalog, disabledSet, locked) {
  if (!catalog.length) return `<div class="muted">No optional addons in this client build.</div>`;
  const groups = new Map();
  for (const a of catalog) {
    if (!groups.has(a.group)) groups.set(a.group, []);
    groups.get(a.group).push(a);
  }
  let out = '';
  for (const [group, items] of groups.entries()) {
    out += `<div class="addon-group-label">${escapeHtml(prettyGroup(group))}</div>`;
    for (const a of items) {
      const enabled = !disabledSet.has(a.id);
      const riskBadge = a.risk === 'high'
        ? '<span class="badge badge-error" style="font-size:10px;margin-left:6px;">HIGH RISK</span>'
        : a.risk === 'medium'
          ? '<span class="badge badge-warn" style="font-size:10px;margin-left:6px;">MEDIUM</span>'
          : '<span class="badge badge-ok" style="font-size:10px;margin-left:6px;">LOW</span>';
      out += `
        <label class="mod-row addon-row">
          <input type="checkbox" class="addon-toggle" data-addon="${escapeHtml(a.id)}" ${enabled ? 'checked' : ''} ${locked ? 'disabled' : ''} />
          <div>
            <div class="mod-name">${escapeHtml(a.displayName)}${riskBadge}</div>
            <div class="muted" style="font-size:11px;">${escapeHtml(a.description)}</div>
            <div class="muted" style="font-size:10px;font-style:italic;margin-top:2px;">${escapeHtml(a.riskNote)}</div>
          </div>
        </label>
      `;
    }
  }
  return out;
}
function prettyGroup(g) {
  if (g === 'grayzone') return 'Gray-zone modules (some servers ban these)';
  return g.replace(/^./, (c) => c.toUpperCase());
}

function renderModList(mods, disabledSet, profile) {
  if (!mods.length) {
    return `
      <div class="mod-empty">
        <div style="font-weight:600;margin-bottom:6px;">No mods installed yet.</div>
        <div class="muted" style="font-size:12px;">
          Click <strong>+ Add mod…</strong> below or hit PLAY once — the launcher installs Fabric API and the Fox Client jar automatically.
        </div>
      </div>
    `;
  }
  const kitsune = mods.find(m => m.isKitsune);
  const others  = mods.filter(m => !m.isKitsune);
  const keepKitsune = profile.keepKitsuneEnabled !== false;
  let out = '';
  if (kitsune) {
    out += `
      <label class="mod-row mod-row-special">
        <input type="checkbox" id="ed-keep-kitsune" ${keepKitsune ? 'checked' : ''} ${profile.locked ? 'disabled' : ''} />
        <div>
          <div class="mod-name">${escapeHtml(kitsune.name)} <span class="badge badge-warn">FOX CLIENT</span></div>
          <div class="muted" style="font-size:11px;">Disable to launch a "vanilla-safe" profile (no Fox Client features).</div>
        </div>
        <div class="mod-size">${formatBytes(kitsune.sizeBytes)}</div>
      </label>
    `;
  }
  for (const m of others) {
    const enabled = !disabledSet.has(m.name);
    const stateBadge = m.currentlyEnabled
      ? ''
      : ' <span class="muted" style="font-size:10px;">(currently disabled on disk)</span>';
    out += `
      <label class="mod-row">
        <input type="checkbox" class="mod-toggle" data-mod="${escapeHtml(m.name)}" ${enabled ? 'checked' : ''} ${profile.locked ? 'disabled' : ''} />
        <div>
          <div class="mod-name">${escapeHtml(m.name)}${stateBadge}</div>
        </div>
        <div class="mod-size">${formatBytes(m.sizeBytes)}</div>
        <button class="mod-delete" data-mod="${escapeHtml(m.name)}" title="Delete this mod from disk" aria-label="Delete" ${profile.locked ? 'disabled' : ''}>×</button>
      </label>
    `;
  }
  return out;
}

/** Shared pack-list renderer for resource packs and shader packs.
 *  No enable/disable toggle — Minecraft manages that in-game. */
function renderPackList(packs) {
  if (!packs.length) {
    return `<div class="mod-empty muted" style="font-size:12px;padding:8px 0;">No packs installed yet. Click <strong>+ Add…</strong> below.</div>`;
  }
  return packs.map(p => `
    <div class="mod-row" style="justify-content:space-between;">
      <div class="mod-name" style="flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">${escapeHtml(p.name)}</div>
      <div class="mod-size">${formatBytes(p.sizeBytes)}</div>
      <button class="mod-delete pack-delete" data-pack="${escapeHtml(p.name)}" title="Delete this pack from disk" aria-label="Delete">×</button>
    </div>
  `).join('');
}

function formatBytes(b) {
  if (b < 1024) return `${b} B`;
  if (b < 1024 * 1024) return `${Math.round(b / 1024)} KB`;
  return `${(b / (1024 * 1024)).toFixed(1)} MB`;
}

function formatRelative(ts) {
  if (!ts) return '—';
  const diffSec = Math.max(0, Math.round((Date.now() - ts) / 1000));
  if (diffSec < 60)   return `${diffSec}s ago`;
  if (diffSec < 3600) return `${Math.round(diffSec / 60)}m ago`;
  if (diffSec < 86400) return `${Math.round(diffSec / 3600)}h ago`;
  if (diffSec < 7 * 86400) return `${Math.round(diffSec / 86400)}d ago`;
  return new Date(ts).toLocaleDateString();
}

function escapeHtml(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}
