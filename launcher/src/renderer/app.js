// Fox Launcher renderer — minimal single-page app with hash-based routing.
// No framework: each screen is a module that renders HTML into #main.

import { renderHome }        from './screens/home.js';
import { renderSettings }    from './screens/settings.js';
import { renderProfiles }    from './screens/profiles.js';
import { renderLogs }        from './screens/logs.js';
import { renderScreenshots } from './screens/screenshots.js';

const ROUTES = {
  home:        renderHome,
  // Old #play / #versions hashes redirect to Home (single-version client,
  // version picking lives in code, not in the UI).
  play:        renderHome,
  versions:    renderHome,
  settings:    renderSettings,
  profiles:    renderProfiles,
  logs:        renderLogs,
  screenshots: renderScreenshots,
};

const el = (id) => document.getElementById(id);

/** Minimal HTML escaper for any text we splice into innerHTML. Defense in
 *  depth — even strings we believe are trusted (Microsoft error responses,
 *  Mojang usernames) get run through this. */
function escapeForHtml(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

// ---- boot sequence ----

/** Apply the saved theme to the document root immediately. Called once on
 *  boot and again if the user changes it in Settings. */
function applyTheme(theme) {
  if (theme && theme !== 'fox') {
    document.documentElement.setAttribute('data-theme', theme);
  } else {
    document.documentElement.removeAttribute('data-theme');
  }
}

async function boot() {
  // Show splash, check auth, then either login or app
  const splash = el('splash');
  const app    = el('app');
  const login  = el('login');

  window.fox.about().then(a => { el('brand-version').textContent = 'v' + a.version; }).catch(() => {});

  // Apply saved theme before any render so the user never sees a flash of
  // the default dark palette when they have fox-light selected.
  window.fox.getSettings().then(s => applyTheme(s.theme)).catch(() => {});

  // Try silent sign-in. Race against a 15 s timeout so a hung IPC channel
  // surfaces as a readable error instead of an indefinite blank splash.
  const authPromise = window.fox.authStatus();
  const timeoutPromise = new Promise((_, reject) =>
    setTimeout(() => reject(new Error(
      'Launcher took too long to respond.\n\n' +
      'Try: close Fox Launcher, run  npm start  again.\n' +
      'If this keeps happening, open DevTools (npm run dev) and check the Console tab for errors.'
    )), 15_000)
  );
  const status = await Promise.race([authPromise, timeoutPromise]);
  splash.classList.add('hidden');

  if (status && status.signedIn) {
    window.fox.detectJava().catch(() => {}); // pre-warm cache before home renders
    app.classList.remove('hidden');
    showUser(status);
    initNav();
    navigate(window.location.hash.replace('#', '') || 'home');
  } else {
    login.classList.remove('hidden');
    initLogin();
  }
}

// ---- login flow ----

function initLogin() {
  const btn        = el('btn-signin');
  const waitPanel  = el('browser-waiting-panel');
  const errorEl    = el('login-error');

  const unsubs = [];
  unsubs.push(window.fox.onAuthBrowserOpened(() => {
    // Browser is open — show the spinner/waiting message
    waitPanel.classList.remove('hidden');
    btn.textContent = 'Waiting for sign-in…';
  }));
  unsubs.push(window.fox.onAuthError((info) => {
    if (info.childAccountRedirect) {
      // Child-account block: Microsoft gave us a redirect URL for the family
      // consent flow. Show it as a direct action button instead of the generic
      // "Change Microsoft setup" link, which would confuse the user here.
      errorEl.innerHTML = `${escapeForHtml(info.message)} ` +
        `<a href="#" id="link-child-fix" style="color:var(--fox-orange);text-decoration:underline;">` +
        `Set up family access →</a>`;
      errorEl.classList.remove('hidden');
      btn.disabled = false;
      btn.textContent = 'Sign in with Microsoft';
      waitPanel.classList.add('hidden');
      const fixLink = document.getElementById('link-child-fix');
      if (fixLink) fixLink.addEventListener('click', (e) => {
        e.preventDefault();
        window.fox.openExternal(info.childAccountRedirect);
      });
    } else {
      errorEl.innerHTML = `${escapeForHtml(info.message)} ` +
        `<a href="#" id="link-edit-msa" style="color:var(--fox-orange);text-decoration:underline;">` +
        `Change Microsoft setup</a>`;
      errorEl.classList.remove('hidden');
      btn.disabled = false;
      btn.textContent = 'Sign in with Microsoft';
      waitPanel.classList.add('hidden');
      const editLink = document.getElementById('link-edit-msa');
      if (editLink) editLink.addEventListener('click', (e) => {
        e.preventDefault();
        window.fox.patchSettings({ msaClientId: '' }).then(() => showMsaSetup());
      });
    }
  }));
  unsubs.push(window.fox.onAuthDone(async () => {
    for (const u of unsubs) u();
    const status = await window.fox.authStatus();
    el('login').classList.add('hidden');
    el('app').classList.remove('hidden');
    showUser(status);
    initNav();
    navigate('home');
  }));

  btn.addEventListener('click', async () => {
    const s = await window.fox.getSettings();
    if (!s.msaClientId || !s.msaClientId.trim()) {
      showMsaSetup();
      return;
    }
    btn.disabled = true;
    btn.textContent = 'Opening browser…';
    errorEl.classList.add('hidden');
    waitPanel.classList.add('hidden');
    const r = await window.fox.login();
    if (!r.ok) {
      btn.disabled = false;
      btn.textContent = 'Sign in with Microsoft';
    }
  });

  function showMsaSetup() {
    const setup = el('msa-setup-panel');
    setup.classList.remove('hidden');
    btn.classList.add('hidden');
    errorEl.classList.add('hidden');
    el('msa-setup-input').focus();
  }
  function hideMsaSetup() {
    el('msa-setup-panel').classList.add('hidden');
    btn.classList.remove('hidden');
  }
  el('msa-setup-save').addEventListener('click', async () => {
    const input = el('msa-setup-input');
    const id = input.value.trim();
    if (!/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(id)) {
      errorEl.textContent = 'That doesn\'t look like a Microsoft client ID. Expected format: 00000000-0000-0000-0000-000000000000';
      errorEl.classList.remove('hidden');
      return;
    }
    errorEl.classList.add('hidden');
    await window.fox.patchSettings({ msaClientId: id });
    hideMsaSetup();
    btn.disabled = true;
    btn.textContent = 'Opening browser…';
    const r = await window.fox.login();
    if (!r.ok) {
      btn.disabled = false;
      btn.textContent = 'Sign in with Microsoft';
    }
  });
  el('msa-setup-cancel').addEventListener('click', hideMsaSetup);
  el('msa-setup-help').addEventListener('click', (e) => {
    e.preventDefault();
    window.fox.openExternal('https://portal.azure.com/#view/Microsoft_AAD_RegisteredApps/ApplicationsListBlade');
  });

  // ---- Guest mode ----
  const guestInput = el('guest-name');
  const guestBtn   = el('btn-guest');
  // Pre-fill with the last guest name used on this machine — same name
  // produces the same offline UUID, so singleplayer worlds stay attached.
  window.fox.getSettings().then((s) => {
    if (s && s.lastGuestName && !guestInput.value) {
      guestInput.value = s.lastGuestName;
    }
  }).catch(() => {});
  const tryGuest = async () => {
    const name = guestInput.value.trim();
    if (!name) {
      errorEl.textContent = 'Pick a guest name first.';
      errorEl.classList.remove('hidden');
      return;
    }
    errorEl.classList.add('hidden');
    guestBtn.disabled = true;
    const r = await window.fox.loginGuest(name);
    guestBtn.disabled = false;
    if (!r.ok) {
      errorEl.textContent = r.error;
      errorEl.classList.remove('hidden');
      return;
    }
    // Same transition the MSA path uses.
    for (const u of unsubs) u();
    const status = await window.fox.authStatus();
    el('login').classList.add('hidden');
    el('app').classList.remove('hidden');
    showUser(status);
    initNav();
    navigate('home');
  };
  guestBtn.addEventListener('click', tryGuest);
  guestInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') tryGuest(); });
}

// ---- app shell ----

// 8 deterministic profile accent colors (fox palette)
const PROFILE_COLORS = [
  '#ff8c42', '#e05a5a', '#4ec27a', '#5b8cff',
  '#d97aed', '#f5b942', '#42d4c2', '#c2a852',
];
function profileColor(id) {
  let hash = 0;
  for (const c of String(id || '')) hash = ((hash * 31) + c.charCodeAt(0)) >>> 0;
  return PROFILE_COLORS[hash % PROFILE_COLORS.length];
}

function showUser(status) {
  const nameEl    = el('user-name');
  const avatarEl  = el('user-avatar');
  const statusDot = el('user-avatar-status');
  const wrapEl    = el('user-avatar-wrap');
  const profileLine = el('user-profile-line');

  if (!status || !status.signedIn) {
    nameEl.textContent = 'Not signed in';
    avatarEl.textContent = '?';
    if (statusDot) { statusDot.className = 'avatar-status'; }
    if (profileLine) profileLine.textContent = '';
    return;
  }

  const safeName = escapeForHtml(status.username || '?');
  const badge    = status.guest ? ` <span class="badge badge-warn">GUEST</span>` : '';
  nameEl.innerHTML = safeName + badge;
  avatarEl.textContent = (status.username || '?').charAt(0).toUpperCase();

  // Online / guest indicator dot
  if (statusDot) {
    statusDot.className = 'avatar-status ' + (status.guest ? 'guest' : 'online');
  }

  // Fetch Minecraft skin head from Crafatar (main process fetches → data URI)
  if (!status.guest && status.uuid && wrapEl) {
    window.fox.fetchAvatar(status.uuid).then((dataUri) => {
      if (!dataUri) return;
      // Only replace if the avatar element is still the letter-initial (not
      // already replaced by a concurrent call)
      const current = wrapEl.querySelector('#user-avatar');
      if (!current) return;
      const img = document.createElement('img');
      img.id        = 'user-avatar';
      img.className = 'avatar-img';
      img.alt       = '';
      // Set onerror BEFORE src so it is registered before any synchronous decode.
      img.onerror   = () => { if (img.parentNode) img.replaceWith(current); };
      img.src       = dataUri;
      current.replaceWith(img);
    }).catch(() => { /* silently keep letter initial */ });
  }

  // Show "Change skin" link only for real (non-guest) MSA accounts.
  const changeSkinBtn = el('btn-change-skin');
  if (changeSkinBtn) {
    changeSkinBtn.style.display = (!status.guest && status.uuid) ? '' : 'none';
  }

  // Populate the active-profile line + color dot
  refreshActiveProfileLine();
}

async function refreshActiveProfileLine() {
  const profileLine = el('user-profile-line');
  if (!profileLine) return;
  try {
    const [s, doc] = await Promise.all([
      window.fox.getSettings(),
      window.fox.listProfiles(),
    ]);
    const active = (doc.profiles || []).find(p => p.id === s.selectedProfile);
    if (active) {
      const color = profileColor(active.id);
      profileLine.innerHTML =
        `<span class="profile-dot" style="background:${escapeForHtml(color)}"></span>` +
        `<span class="v">${escapeForHtml(active.name)}</span>`;
    } else {
      profileLine.textContent = '';
    }
  } catch (_) { /* noop */ }
}

// Re-evaluate the sidebar profile line whenever the user navigates — handles
// the case where they switched profiles in the Profiles tab.
window.addEventListener('hashchange', refreshActiveProfileLine);

function getHomeNavBtn() {
  for (const btn of document.querySelectorAll('.nav-btn')) {
    if (btn.dataset.route === 'home') return btn;
  }
  return null;
}

function setRunningDot(running) {
  const btn = getHomeNavBtn();
  if (!btn) return;
  const existing = btn.querySelector('.nav-running-dot');
  if (running && !existing) {
    const dot = document.createElement('span');
    dot.className = 'nav-running-dot';
    dot.title = 'Game is running';
    btn.appendChild(dot);
  } else if (!running && existing) {
    existing.remove();
  }
}

function initNav() {
  for (const btn of document.querySelectorAll('.nav-btn')) {
    btn.addEventListener('click', () => navigate(btn.dataset.route));
  }
  el('sign-out').addEventListener('click', async () => {
    await window.fox.logout();
    // If other accounts remain, stay in-app and switch to the new active one.
    const status = await window.fox.authStatus().catch(() => null);
    if (status && status.signedIn) {
      showUser(status);
    } else {
      location.reload();
    }
  });

  initSkinManager();
  initAccountSwitcher();
  window.addEventListener('hashchange', () => {
    navigate(window.location.hash.replace('#', '') || 'home');
  });

  // Seed the running dot from the current process state, then keep it live.
  window.fox.isRunning().then(setRunningDot).catch(() => {});
  window.fox.onGameExit(() => setRunningDot(false));
  window.fox.onGameStart(() => setRunningDot(true));

  // Launcher self-update: show a persistent toast when a new version has been
  // downloaded and is waiting to be installed on the next quit.
  if (window.fox.onLauncherUpdateReady) {
    window.fox.onLauncherUpdateReady(() => {
      showToast('A launcher update is ready — restart to apply it.', 'info', 0 /* no auto-dismiss */);
    });
  }

  // When the active profile changes (sidebar dropdown, profile-card Play
  // button, or Settings switch), refresh the sidebar AND check whether
  // the new profile's auth vault is empty. Isolated profiles have their
  // own auth.json — switching to one whose vault is empty needs a
  // sign-in flow, so we reload to trigger the boot path.
  window.fox.onActiveProfileChanged(async () => {
    refreshActiveProfileLine();
    try {
      const status = await window.fox.authStatus();
      if (!status || !status.signedIn) {
        // Empty vault on the new profile → bring up the login screen.
        location.reload();
        return;
      }
      // Update the user pill (name, avatar) for the new account.
      showUser(status);
    } catch (_) { /* keep going — best effort */ }
  });
}

// ---- account switcher ----

function initAccountSwitcher() {
  const switcher    = el('account-switcher');
  const userCard    = el('user-card');
  const accountList = el('account-list');
  const addBtn      = el('btn-add-account');

  if (!switcher || !userCard) return;

  let switcherOpen = false;

  function closeSwitcher() {
    switcher.classList.add('hidden');
    switcherOpen = false;
  }

  async function renderAccountList() {
    if (!accountList) return;
    let accounts = [];
    try { accounts = await window.fox.listAccounts(); } catch (_) {}

    const status = await window.fox.authStatus().catch(() => null);
    const currentUuid = status && status.uuid;

    if (!accounts.length) {
      accountList.innerHTML = `<div class="account-row muted" style="font-size:12px;padding:8px 10px;">No other accounts</div>`;
      return;
    }

    accountList.innerHTML = accounts.map(acc => {
      const isActive = acc.uuid === currentUuid;
      const initial  = (acc.username || '?').charAt(0).toUpperCase();
      return `
        <div class="account-row ${isActive ? 'account-row-active' : ''}" data-id="${escapeForHtml(acc.id)}" data-uuid="${escapeForHtml(acc.uuid)}">
          <div class="account-row-avatar">${escapeForHtml(initial)}</div>
          <div class="account-row-info">
            <div class="account-row-name">${escapeForHtml(acc.username)}</div>
            ${isActive ? '<div class="account-row-badge">Active</div>' : ''}
          </div>
          ${!isActive ? `<button class="btn btn-sm account-switch-btn" data-id="${escapeForHtml(acc.id)}">Switch</button>` : ''}
          <button class="btn btn-sm account-remove-btn" data-id="${escapeForHtml(acc.id)}" title="Remove account" aria-label="Remove">×</button>
        </div>
      `;
    }).join('');

    // Wire switch buttons
    for (const btn of accountList.querySelectorAll('.account-switch-btn')) {
      btn.addEventListener('click', async (e) => {
        e.stopPropagation();
        const id = btn.dataset.id;
        const r = await window.fox.setActiveAccount(id);
        if (r && r.ok) {
          closeSwitcher();
          const newStatus = await window.fox.authStatus().catch(() => null);
          if (newStatus && newStatus.signedIn) {
            showUser(newStatus);
          } else {
            location.reload();
          }
        }
      });
    }

    // Wire remove buttons
    for (const btn of accountList.querySelectorAll('.account-remove-btn')) {
      btn.addEventListener('click', async (e) => {
        e.stopPropagation();
        const id = btn.dataset.id;
        const row = btn.closest('.account-row');
        const name = row ? row.querySelector('.account-row-name') : null;
        if (!confirm(`Remove account "${name ? name.textContent : id}"?`)) return;
        await window.fox.removeAccount(id);
        const newStatus = await window.fox.authStatus().catch(() => null);
        if (!newStatus || !newStatus.signedIn) {
          closeSwitcher();
          location.reload();
          return;
        }
        showUser(newStatus);
        await renderAccountList();
      });
    }
  }

  // Toggle switcher on user-card click
  userCard.style.cursor = 'pointer';
  userCard.addEventListener('click', async (e) => {
    // Don't intercept clicks on the sign-out / change-skin buttons
    if (e.target.closest('button')) return;
    if (switcherOpen) { closeSwitcher(); return; }
    await renderAccountList();
    switcher.classList.remove('hidden');
    switcherOpen = true;
  });

  // Close on outside click
  document.addEventListener('click', (e) => {
    if (!switcherOpen) return;
    if (!switcher.contains(e.target) && !userCard.contains(e.target)) closeSwitcher();
  });

  // "Add account" — triggers the normal login flow
  addBtn && addBtn.addEventListener('click', async () => {
    closeSwitcher();
    const s = await window.fox.getSettings();
    if (!s.msaClientId || !s.msaClientId.trim()) {
      showToast('Set up your Azure client ID in Settings → Updates first.', 'warn', 5000);
      return;
    }
    const r = await window.fox.login();
    if (r && r.ok) {
      const newStatus = await window.fox.authStatus().catch(() => null);
      if (newStatus && newStatus.signedIn) showUser(newStatus);
    }
  });

  // Keep switcher live when accounts change from another event
  window.fox.onAccountChanged(async () => {
    if (switcherOpen) await renderAccountList();
    const status = await window.fox.authStatus().catch(() => null);
    if (status && status.signedIn) showUser(status);
  });
}

// ---- skin manager ----

function initSkinManager() {
  const panel      = el('skin-panel');
  const openBtn    = el('btn-change-skin');
  const closeBtn   = el('btn-skin-close');
  const uploadBtn  = el('btn-skin-upload');
  const statusEl   = el('skin-status');
  const previewImg = el('skin-preview-img');
  const fallback   = el('skin-preview-fallback');
  const variantText = el('skin-variant-text');

  if (!panel || !openBtn) return;

  function setSkinStatus(msg, ok) {
    if (!statusEl) return;
    const color = ok === true ? 'var(--success)' : ok === false ? 'var(--danger)' : 'var(--muted)';
    statusEl.innerHTML = `<span style="color:${color};">${escapeForHtml(msg)}</span>`;
  }

  function closeSkinPanel() {
    panel.classList.add('hidden');
  }

  async function openSkinPanel() {
    // Reset state
    setSkinStatus('Loading current skin…', null);
    if (previewImg)  { previewImg.classList.add('hidden'); previewImg.src = ''; }
    if (fallback)    { fallback.classList.remove('hidden'); fallback.textContent = 'Loading…'; }
    if (variantText) variantText.textContent = 'Classic';

    panel.classList.remove('hidden');

    try {
      const info = await window.fox.fetchSkin();
      if (info && info.ok) {
        const v = info.variant === 'slim' ? 'slim' : 'classic';
        if (variantText) variantText.textContent = v === 'slim' ? 'Slim (Alex)' : 'Classic (Steve)';
        const radio = el(v === 'slim' ? 'skin-var-slim' : 'skin-var-classic');
        if (radio) radio.checked = true;

        if (info.skinUrl && previewImg) {
          // Skin URL is on Mojang's CDN — fetch via main process → data URI
          // so the renderer's CSP (img-src 'self' data:) is satisfied.
          window.fox.fetchAvatar /* re-use the base64 fetch */ ;
          // Use a direct img src — skin textures are public, no auth needed,
          // and sessionserver.mojang.com is on a different host than the
          // avatar proxy.  Mojang's texture CDN URLs are plain HTTPS so we
          // fetch via the existing _fetchBase64 helper through avatar:fetch.
          // The easiest path: just show the URL of the Steve/Alex face using
          // the avatar we already fetched.  Full body render would require a
          // third-party renderer; skip for now and just confirm the variant.
          previewImg.src = '';
          previewImg.classList.add('hidden');
          if (fallback) { fallback.textContent = 'Skin active ✓'; }
        } else {
          if (fallback) { fallback.textContent = 'Default skin'; }
        }
        setSkinStatus('');
      } else {
        if (fallback) { fallback.textContent = 'Could not load skin'; }
        setSkinStatus(info && info.error ? info.error : 'Failed to load skin info', false);
      }
    } catch (err) {
      if (fallback) { fallback.textContent = 'Error'; }
      setSkinStatus(err.message || 'Unknown error', false);
    }
  }

  openBtn.addEventListener('click', openSkinPanel);
  closeBtn && closeBtn.addEventListener('click', closeSkinPanel);

  // Close on backdrop click (clicking the darkened area outside the panel)
  panel.addEventListener('click', (e) => {
    if (e.target === panel) closeSkinPanel();
  });

  uploadBtn && uploadBtn.addEventListener('click', async () => {
    const variantRadio = document.querySelector('input[name="skin-variant"]:checked');
    const variant = variantRadio ? variantRadio.value : 'classic';
    uploadBtn.disabled = true;
    setSkinStatus('Uploading…', null);
    try {
      const r = await window.fox.uploadSkin(variant);
      if (r && r.cancelled) {
        setSkinStatus('');
      } else if (r && r.ok) {
        setSkinStatus('Skin uploaded! It may take a moment to appear in-game.', true);
        // Refresh the avatar in the sidebar
        const status = await window.fox.authStatus().catch(() => null);
        if (status && status.uuid) {
          window.fox.fetchAvatar(status.uuid).then((dataUri) => {
            if (!dataUri) return;
            const wrap = el('user-avatar-wrap');
            if (!wrap) return;
            let img = wrap.querySelector('#user-avatar');
            if (!img) return;
            const newImg = document.createElement('img');
            newImg.id = 'user-avatar'; newImg.className = 'avatar-img'; newImg.alt = '';
            newImg.src = dataUri;
            img.replaceWith(newImg);
          }).catch(() => {});
        }
      } else {
        setSkinStatus((r && r.error) ? r.error : 'Upload failed', false);
      }
    } catch (err) {
      setSkinStatus(err.message || 'Upload error', false);
    } finally {
      uploadBtn.disabled = false;
    }
  });
}

function navigate(route) {
  const renderer = ROUTES[route] || ROUTES.home;
  window.location.hash = route;
  for (const btn of document.querySelectorAll('.nav-btn')) {
    btn.classList.toggle('active', btn.dataset.route === route);
  }
  const main = el('main');
  // Show a loading placeholder *immediately* — every screen does async data
  // fetches before its first paint, and that gap was rendering as empty
  // space next to the sidebar. The screen's own renderer overwrites this
  // when its data is ready.
  main.innerHTML = `
    <div class="screen-loading" aria-busy="true" aria-live="polite">
      <img src="assets/fox.png" alt="" class="screen-loading-fox" />
      <div class="screen-loading-label">Loading…</div>
    </div>
  `;
  Promise.resolve(renderer(main)).catch(err => {
    main.innerHTML = `<div class="error">Failed to render ${route}: ${err.message}</div>`;
  });
}

// ---- toasts ----

export { applyTheme };

export function showToast(message, kind = 'info', ttlMs = 5000) {
  const host = el('toasts');
  if (!host) return;
  const t = document.createElement('div');
  t.className = `toast toast-${kind}`;
  t.textContent = message;
  host.appendChild(t);
  setTimeout(() => { t.classList.add('toast-leaving'); }, ttlMs);
  setTimeout(() => { if (t.parentNode) t.parentNode.removeChild(t); }, ttlMs + 400);
}

window.fox.onSessionExpired(({ reason }) => {
  showToast(`Your sign-in expired: ${reason || 'please sign in again'}.`, 'warn', 7000);
});

// Auto-update results — one toast per outcome. We deliberately *don't* toast
// on "checking", "up-to-date", or expected "no release exists" 404s. Only a
// real update landing or a non-trivial error is worth interrupting the user.
window.fox.onUpdateResult((r) => {
  if (!r) return;
  if (r.state === 'updated' && r.tag) {
    showToast(`Client updated to ${r.tag}.`, 'success', 6000);
  } else if (r.state === 'error') {
    // Suppress the common "GitHub repo not configured / repo missing /
    // no releases yet" failure — that's the default state for dev builds.
    const msg = String(r.error || '');
    if (/HTTP 404|ENOTFOUND|getaddrinfo/i.test(msg)) return;
    showToast(`Update check failed: ${msg}`, 'warn', 6000);
  }
  // 'checking', 'up-to-date', 'no-asset' → silent
});

// One-time auto-install of the recommended performance pack. The main side
// fires this once per fresh install; we only toast for meaningful outcomes
// (actually installed something, or hit an error). 'silent' means "nothing
// to do, everything was already there" — no need to interrupt.
window.fox.onRecommendedAutoResult((r) => {
  if (!r || r.state === 'silent') return;
  if (r.state === 'installed') {
    showToast(`Installed ${r.installed} performance mod${r.installed === 1 ? '' : 's'}.`, 'success', 6000);
  } else if (r.state === 'partial') {
    const ok = r.installed || 0;
    showToast(`Installed ${ok}, failed ${r.errored}. Check Logs for details.`, 'warn', 6000);
  } else if (r.state === 'error') {
    showToast(`Performance-mod install failed: ${r.error}`, 'warn', 6000);
  }
});

// ---- kick off ----

boot().catch(err => {
  document.body.innerHTML = `<pre style="color:#f59c9c;padding:20px;">Boot failed: ${err.stack || err}</pre>`;
});
