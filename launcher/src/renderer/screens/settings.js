// Settings — tabbed layout. One screen, five panels (Game, Java, Display,
// Shortcuts, Advanced). All inputs write into a single `state` object so
// partial saves can't desync; the Save button validates + flushes
// everything atomically.

import { applyTheme } from '../app.js';

/** Render one row of the keyboard-shortcuts reference table. */
function renderShortcut(action, keys, description) {
  return `
    <div class="shortcut-row">
      <div class="shortcut-action">${action}</div>
      <div class="shortcut-keys">${formatKeys(keys)}</div>
      <div class="shortcut-desc">${description}</div>
    </div>
  `;
}

/** Wrap each token (split on spaces / + / -) in a <kbd>, leaving connectors
 *  visible as plain text. So "Ctrl + R" becomes <kbd>Ctrl</kbd> + <kbd>R</kbd>. */
function formatKeys(keys) {
  if (!keys || keys.startsWith('(')) return `<span class="muted">${keys}</span>`;
  return keys.split(/(\s*\+\s*|\s*-\s*)/).map(part => {
    if (!part || /^\s*[+\-]\s*$/.test(part)) return part;
    return `<kbd>${part.trim()}</kbd>`;
  }).join('');
}

export async function renderSettings(mount) {
  const s = await window.fox.getSettings();
  const defaultGameDir = await window.fox.defaultGameDir();
  const ramInfo = await window.fox.ramInfo().catch(() => null);

  // Working state — clones the persisted settings so unsaved tweaks live here
  // until the user clicks Save. Refs into this object survive tab switches.
  const state = {
    javaPath:          s.javaPath || '',
    minRam:            s.minRam,
    maxRam:            s.maxRam,
    gameDir:           s.gameDir || '',
    resolution: {
      width:      s.resolution.width,
      height:     s.resolution.height,
      fullscreen: !!s.resolution.fullscreen,
    },
    keepLauncherOpen:  !!s.keepLauncherOpen,
    autoUpdate:        !!s.autoUpdate,
    launchOnStartup:   !!s.launchOnStartup,
    theme:             s.theme || 'fox',
    discordRpcEnabled: s.discordRpcEnabled !== false,
  };

  // Slider ceiling: respect both the OS recommendation AND settings.BOUNDS
  // (max 64 GB), so a 96 GB rig doesn't show a slider that silently clamps
  // on save. Floor 2 GB for usability on low-RAM machines.
  const ramRecommended = Math.round((ramInfo?.recommendedMaxMb || 16384) / 1024);
  const ramCeiling = Math.max(2, Math.min(64, ramRecommended));
  const ramLabel   = ramInfo && ramInfo.totalMb > 0
    ? `Detected ${Math.round(ramInfo.totalMb / 1024)} GB total · recommended max ${Math.round(ramInfo.recommendedMaxMb / 1024)} GB`
    : 'System RAM detection unavailable — using a 16 GB cap.';

  mount.innerHTML = `
    <h1 class="screen-title">Settings</h1>
    <p class="screen-sub">Configure Java, memory, and launcher behavior.</p>

    <div class="tabs" role="tablist">
      <button class="tab active" data-tab="game">Game</button>
      <button class="tab" data-tab="java">Java</button>
      <button class="tab" data-tab="display">Display</button>
      <button class="tab" data-tab="shortcuts">Shortcuts</button>
      <button class="tab" data-tab="advanced">Advanced</button>
    </div>

    <div class="tab-panel active" data-panel="game">
      <div class="section">
        <div class="section-title">Memory</div>
        <div class="section-sub">${escapeHtml(ramLabel)}</div>
        <div class="field">
          <label>Minimum RAM: <span class="slider-value" id="v-min">${state.minRam} GB</span></label>
          <div class="slider-wrap">
            <input type="range" id="f-min" min="1" max="${ramCeiling}" step="1" value="${state.minRam}" />
          </div>
        </div>
        <div class="field">
          <label>Maximum RAM: <span class="slider-value" id="v-max">${state.maxRam} GB</span></label>
          <div class="slider-wrap">
            <input type="range" id="f-max" min="1" max="${ramCeiling}" step="1" value="${state.maxRam}" />
          </div>
        </div>
      </div>

      <div class="section">
        <div class="section-title">Game Directory</div>
        <div class="section-sub">Where Minecraft stores worlds, packs, mods, and libraries.</div>
        <div class="field">
          <label>Game directory (empty = default)</label>
          <div class="input-row">
            <input type="text" class="input" id="f-gameDir" value="${escapeHtml(state.gameDir)}" placeholder="${escapeHtml(defaultGameDir)}" />
            <button class="btn" id="btn-browse-gamedir">Browse…</button>
            <button class="btn" id="btn-open-gamedir">Open</button>
          </div>
        </div>
      </div>

      <div class="section">
        <div class="section-title">Launcher behavior</div>
        <label class="checkbox">
          <input type="checkbox" id="f-keepOpen" ${state.keepLauncherOpen ? 'checked' : ''} />
          Keep launcher open after launching the game
        </label>
        <label class="checkbox" style="margin-top:8px;">
          <input type="checkbox" id="f-autoUpdate" ${state.autoUpdate ? 'checked' : ''} />
          Automatically check for client updates on launch
        </label>
        <label class="checkbox" style="margin-top:8px;">
          <input type="checkbox" id="f-launchOnStartup" ${state.launchOnStartup ? 'checked' : ''} />
          Launch Fox Launcher when you log in to Windows
        </label>
        <label class="checkbox" style="margin-top:8px;">
          <input type="checkbox" id="f-discord-on" ${state.discordRpcEnabled ? 'checked' : ''} />
          Show "Playing Minecraft" on Discord
          <span class="badge" id="discord-status-badge" style="margin-left:6px;font-size:10px;">…</span>
        </label>
      </div>

      <div class="section">
        <div class="section-title">Appearance</div>
        <div class="field">
          <label>Theme</label>
          <div class="theme-picker" id="theme-picker">
            <button type="button" class="theme-swatch ${(s.theme || 'fox') === 'fox' ? 'active' : ''}" data-theme="fox">
              <span class="theme-swatch-preview theme-swatch-dark"></span>
              <span>Dark (Fox)</span>
            </button>
            <button type="button" class="theme-swatch ${s.theme === 'fox-light' ? 'active' : ''}" data-theme="fox-light">
              <span class="theme-swatch-preview theme-swatch-light"></span>
              <span>Light (Fox)</span>
            </button>
          </div>
        </div>
      </div>
    </div>

    <div class="tab-panel" data-panel="java">
      <div class="section">
        <div class="section-title">Detected Java installations</div>
        <div class="section-sub">Click a card to use that Java. Anything below 21 is dim and disabled.</div>
        <div id="java-cards" class="card-grid"><div class="muted">Scanning…</div></div>
      </div>

      <div class="section">
        <div class="section-title">Custom path</div>
        <div class="section-sub">Override auto-detection with a specific java(.exe) binary.</div>
        <div class="field">
          <div class="input-row">
            <input type="text" class="input" id="f-javaPath" value="${escapeHtml(state.javaPath)}" placeholder="Auto-detect" />
            <button class="btn" id="btn-browse-java">Browse…</button>
            <button class="btn" id="btn-clear-java">Clear</button>
          </div>
        </div>
      </div>
    </div>

    <div class="tab-panel" data-panel="display">
      <div class="section">
        <div class="section-title">Window</div>
        <div class="two-col">
          <div class="field">
            <label>Width</label>
            <input type="number" class="input" id="f-width" value="${state.resolution.width}" min="320" />
          </div>
          <div class="field">
            <label>Height</label>
            <input type="number" class="input" id="f-height" value="${state.resolution.height}" min="240" />
          </div>
        </div>
        <label class="checkbox" style="margin-top:8px;">
          <input type="checkbox" id="f-fullscreen" ${state.resolution.fullscreen ? 'checked' : ''} />
          Start in fullscreen
        </label>
      </div>
    </div>

    <div class="tab-panel" data-panel="shortcuts">
      <div class="section">
        <div class="section-title">⌨ Keyboard shortcuts</div>
        <div class="section-sub">
          Every binding the launcher and the Fox Client mod ship with by default. In-game keys are configured under
          <em>Minecraft → Options → Controls → Fox Client</em> — change them there, not here.
        </div>

        <div class="shortcut-group-label">In the launcher</div>
        <div class="shortcut-table">
          ${renderShortcut('Switch nav tab',     '1 - 5',  'Jump straight to Home / Profiles / Screenshots / Logs / Settings.')}
          ${renderShortcut('Open DevTools',      'F12',    'Opens Chromium DevTools — useful for debugging.')}
          ${renderShortcut('Reload launcher',    'Ctrl + R', 'Reloads the renderer without restarting the Electron process.')}
          ${renderShortcut('Toggle fullscreen',  'F11',    'Standard fullscreen toggle.')}
        </div>

        <div class="shortcut-group-label">Fox Client (in-game)</div>
        <div class="shortcut-table">
          ${renderShortcut('Open Fox Menu',         ']',          'Opens the legacy Fox settings menu.')}
          ${renderShortcut('Open ClickGUI',         'Right Shift','Opens the module browser / on/off toggles.')}
          ${renderShortcut('Open HUD Editor',       'End',        'Drag-and-drop layout for every HUD widget.')}
          ${renderShortcut('Zoom (hold)',           'C',          'Smooth hold-to-zoom (Lunar-style).')}
          ${renderShortcut('Toggle Full Brightness','(unbound)', 'Defaults unbound to avoid colliding with vanilla\\'s G (Social Interactions). Rebind in Options → Controls.')}
          ${renderShortcut('Copy coords',           '(unbound)', 'Copies your XYZ to the clipboard via the Coords HUD module.')}
          ${renderShortcut('Tab-held minimap',      'Tab',        'When the Minimap module is on, holding tab swaps player dots for their actual skin\\'s head face.')}
        </div>

        <div class="shortcut-group-label">Vanilla MC defaults still apply</div>
        <div class="shortcut-table">
          ${renderShortcut('Take screenshot',  'F2',       'Saved into the active profile\\'s screenshots folder — browse them in the launcher\\'s Screenshots tab.')}
          ${renderShortcut('Debug overlay',    'F3',       'Vanilla F3 debug; toggle entity hitboxes with F3 + B.')}
          ${renderShortcut('Reload resources', 'F3 + T',   'Reloads resource packs — Fox cosmetics reload with them.')}
        </div>
      </div>
    </div>

    <div class="tab-panel" data-panel="advanced">
      <div class="section">
        <div class="section-title">About</div>
        <div id="about-card" class="muted">Loading…</div>
      </div>

      <div class="section">
        <div class="section-title">Reset</div>
        <div class="section-sub">Restores every setting to default. Profiles, auth, and the cached client jar are kept. Your previous settings.json is renamed (not deleted) so you can recover from disk if needed.</div>
        <button class="btn btn-danger" id="btn-reset">Reset all settings</button>
        <span class="status muted" id="reset-status" style="margin-left:10px;"></span>
      </div>
    </div>

    <div style="display:flex;gap:8px;align-items:center;margin-top:8px;">
      <button class="btn btn-primary" id="btn-save">Save settings</button>
      <span class="status muted" id="save-status"></span>
    </div>
  `;

  const $ = (id) => document.getElementById(id);

  // ---- tab switching ----
  for (const tab of mount.querySelectorAll('.tab')) {
    tab.addEventListener('click', () => {
      const target = tab.dataset.tab;
      for (const t of mount.querySelectorAll('.tab')) t.classList.toggle('active', t === tab);
      for (const p of mount.querySelectorAll('.tab-panel')) {
        p.classList.toggle('active', p.dataset.panel === target);
      }
    });
  }

  // ---- live slider feedback ----
  $('f-min').addEventListener('input', (e) => {
    state.minRam = Number(e.target.value);
    $('v-min').textContent = `${state.minRam} GB`;
    if (state.maxRam < state.minRam) {
      state.maxRam = state.minRam;
      $('f-max').value = state.maxRam;
      $('v-max').textContent = `${state.maxRam} GB`;
    }
  });
  $('f-max').addEventListener('input', (e) => {
    state.maxRam = Number(e.target.value);
    $('v-max').textContent = `${state.maxRam} GB`;
    if (state.maxRam < state.minRam) {
      state.minRam = state.maxRam;
      $('f-min').value = state.minRam;
      $('v-min').textContent = `${state.minRam} GB`;
    }
  });

  // ---- theme picker ----
  const themePicker = document.getElementById('theme-picker');
  if (themePicker) {
    for (const btn of themePicker.querySelectorAll('.theme-swatch')) {
      btn.addEventListener('click', () => {
        const picked = btn.dataset.theme;
        state.theme = picked;
        applyTheme(picked);
        for (const b of themePicker.querySelectorAll('.theme-swatch')) {
          b.classList.toggle('active', b.dataset.theme === picked);
        }
      });
    }
  }

  // ---- game dir browse / open ----
  $('btn-browse-gamedir').addEventListener('click', async () => {
    const p = await window.fox.browseGameDir();
    if (p) { state.gameDir = p; $('f-gameDir').value = p; }
  });
  $('btn-open-gamedir').addEventListener('click', async () => {
    const dir = state.gameDir.trim() || defaultGameDir;
    await window.fox.openPath(dir);
  });

  // ---- Discord status badge: poll while the Settings page is mounted ----
  const discordBadge = document.getElementById('discord-status-badge');
  const refreshDiscordBadge = async () => {
    if (!document.body.contains(discordBadge)) return;
    let s;
    try { s = await window.fox.discordStatus(); }
    catch (_) { s = { state: 'disabled' }; }
    const map = {
      'connected':  ['Connected',   'badge-ok'],
      'waiting':    ['Connecting…', 'badge-warn'],
      'starting':   ['Starting…',   'badge-warn'],
      'no-app-id':  ['No App ID',   'badge-warn'],
      'disabled':   ['Off',         'badge-error'],
    };
    const [label, cls] = map[s.state] || ['Unknown', 'badge-warn'];
    discordBadge.textContent = label;
    discordBadge.className = 'badge ' + cls;
  };
  refreshDiscordBadge();
  const discordPollTimer = setInterval(refreshDiscordBadge, 4000);
  // Stop polling when the user leaves the Settings screen.
  mount.addEventListener('fox:screen-unmount', () => clearInterval(discordPollTimer), { once: true });

  // ---- advanced tab: about + reset ----
  (async () => {
    const about = await window.fox.about().catch(() => null);
    const card = document.getElementById('about-card');
    if (!card || !about) return;
    card.innerHTML = `
      <div class="stat-row"><span>Fox Launcher</span><span class="v">v${escapeHtml(about.version)}</span></div>
      <div class="stat-row"><span>Electron</span><span class="v">${escapeHtml(about.electron)}</span></div>
      <div class="stat-row"><span>Chromium</span><span class="v">${escapeHtml(about.chrome)}</span></div>
      <div class="stat-row"><span>Node</span><span class="v">${escapeHtml(about.node)}</span></div>
      <div class="stat-row"><span>Platform</span><span class="v">${escapeHtml(about.platform)} · ${escapeHtml(about.arch)}</span></div>
      <div class="stat-row"><span>Data dir</span><span class="v" style="font-family:var(--mono);font-size:11px;text-align:right;max-width:60%;word-break:break-all;">${escapeHtml(about.paths.root)}</span></div>
    `;
  })();
  $('btn-reset').addEventListener('click', async () => {
    if (!confirm('Reset every Fox Launcher setting to default?\n\nProfiles, auth, and the cached client jar are kept.')) return;
    const r = await window.fox.resetSettings();
    const stat = $('reset-status');
    if (r.ok) {
      stat.innerHTML = '<span style="color:var(--success);">Reset. Reloading…</span>';
      setTimeout(() => location.reload(), 600);
    } else {
      stat.innerHTML = `<span style="color:var(--danger);">Reset failed: ${escapeHtml(r.error || '?')}</span>`;
    }
  });

  // ---- java tab: load all candidates lazily, render cards ----
  loadJavaCards();

  $('btn-browse-java').addEventListener('click', async () => {
    const r = await window.fox.browseJava();
    if (r && r.path) {
      state.javaPath = r.path;
      $('f-javaPath').value = r.path;
      loadJavaCards();
      if (!r.major || r.major < 21) {
        showStatus('warn', `Selected Java is version ${r.major || '?'}. Minecraft requires Java 21+.`);
      }
    }
  });
  $('btn-clear-java').addEventListener('click', () => {
    state.javaPath = '';
    $('f-javaPath').value = '';
    loadJavaCards();
  });
  $('f-javaPath').addEventListener('input', (e) => { state.javaPath = e.target.value; });

  // ---- save ----
  $('btn-save').addEventListener('click', async () => {
    state.gameDir         = $('f-gameDir').value.trim();
    state.discordRpcEnabled = $('f-discord-on').checked;
    state.resolution = {
      width:      Math.max(320, Number($('f-width').value)  || 1280),
      height:     Math.max(240, Number($('f-height').value) || 720),
      fullscreen: $('f-fullscreen').checked,
    };
    state.keepLauncherOpen  = $('f-keepOpen').checked;
    state.autoUpdate        = $('f-autoUpdate').checked;
    state.launchOnStartup   = $('f-launchOnStartup').checked;
    state.javaPath          = $('f-javaPath').value.trim();

    if (state.minRam > state.maxRam) {
      showStatus('error', 'Minimum RAM cannot exceed maximum.');
      return;
    }

    try {
      await window.fox.patchSettings(state);
      showStatus('success', 'Saved ✓');
    } catch (err) {
      showStatus('error', `Save failed: ${err.message}`);
    }
  });

  function showStatus(kind, msg) {
    const el = $('save-status');
    const color = kind === 'error' ? 'var(--danger)'
                : kind === 'warn'  ? 'var(--warn)'
                : 'var(--success)';
    el.innerHTML = `<span style="color:${color};">${escapeHtml(msg)}</span>`;
    clearTimeout(showStatus._t);
    showStatus._t = setTimeout(() => { el.textContent = ''; }, 2500);
  }

  async function loadJavaCards() {
    const host = $('java-cards');
    host.innerHTML = `<div class="muted">Scanning…</div>`;
    let payload;
    try { payload = await window.fox.detectAllJava(); }
    catch (_) { host.innerHTML = `<div class="muted">Detection failed.</div>`; return; }
    const required = payload.required || 21;
    const list = payload.results || [];
    if (!list.length) {
      host.innerHTML = `<div class="muted">No Java installations detected. Install JDK ${required}+ or set a custom path below.</div>`;
      return;
    }
    const selected = state.javaPath.trim();
    host.innerHTML = list.map((j) => {
      const ok = (j.major || 0) >= required;
      const isSelected = selected && j.path === selected;
      const badge = ok
        ? `<span class="badge badge-ok">Java ${j.major}</span>`
        : `<span class="badge badge-error">Java ${j.major}</span>`;
      const sel = isSelected ? `<span class="badge badge-warn">SELECTED</span>` : '';
      return `
        <div class="card ${ok ? 'clickable' : ''} ${isSelected ? 'selected' : ''}" data-path="${escapeHtml(j.path)}" ${ok ? '' : 'aria-disabled="true"'}>
          <div class="card-title">${escapeHtml(j.versionString || `Java ${j.major}`)} ${badge} ${sel}</div>
          <div class="card-meta">${escapeHtml(j.path)}</div>
        </div>
      `;
    }).join('');
    for (const card of host.querySelectorAll('.card.clickable')) {
      card.addEventListener('click', () => {
        const p = card.dataset.path;
        state.javaPath = p;
        $('f-javaPath').value = p;
        for (const c of host.querySelectorAll('.card')) c.classList.remove('selected');
        card.classList.add('selected');
      });
    }
  }
}

function escapeHtml(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}
