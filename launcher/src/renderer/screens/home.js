// Home — the launch surface.
//
// Big PLAY button, inline version picker, compact pre-flight checks (only
// shown when something's wrong), small status sidebar with installed-client
// + last-launch + news. Replaces the previous Play tab — there's no separate
// "Play" screen anymore.
//
// Subscriptions are managed strictly: every onGameExit / onGameCrash listener
// is owned by the current render and torn down when the screen unmounts.

let activeUnsubs = [];

export async function renderHome(mount) {
  // Tear down subscriptions from any previous render of this screen.
  for (const off of activeUnsubs) { try { off(); } catch (_) {} }
  activeUnsubs = [];

  // `running` is `let` so the game-exit handler can clear it and allow
  // patchJava() to re-enable the PLAY button without a full re-render.
  let [s, summary, status, lastLaunch, readiness, profilesDoc, running] = await Promise.all([
    window.fox.getSettings(),
    window.fox.updateSummary().catch(() => ({})),
    window.fox.authStatus().catch(() => ({})),
    window.fox.lastLaunch().catch(() => null),
    window.fox.clientReadiness().catch(() => null),
    window.fox.listProfiles().catch(() => ({ profiles: [] })),
    window.fox.isRunning().catch(() => false),
  ]);
  // The user may have navigated away during the await above (slow IPC chain).
  // Writing to a detached mount produces invisible state + leaked subs.
  if (!document.body.contains(mount)) return;
  const profiles = profilesDoc.profiles || [];
  const activeProfileId = s.selectedProfile || (profiles[0] && profiles[0].id) || '';
  const activeProfile = profiles.find(p => p.id === activeProfileId);
  const installed = summary.installed;
  const latest    = summary.latestSeen;
  const upToDate  = installed && latest && installed.tag === latest.tag;
  // The "Currently installed" card pulls primarily from the GitHub-release
  // cache, but the launcher also installs a locally-built jar from
  // <projectRoot>/build/libs when present. The card should reflect *that*
  // when no GitHub release is configured / available.
  const usingDevJar = !installed && readiness?.modJarSource === 'dev-build';

  // Early failures — determinable without Java (no child-process spawn needed).
  // Vanilla MC and Java are downloaded automatically on first launch (same as
  // Modrinth App / Lunar Client), so those are no longer blocking errors here.
  // Auth and RAM issues are surfaced by patchJava() after detection resolves.
  const earlyFailures = [];
  // (vanilla missing & java missing are handled by the launcher automatically)

  // ready starts false; patchJava() sets it once Java detection resolves.
  let ready = false;

  // Update banner — shown when a newer release is available from GitHub.
  const showUpdateBanner = !upToDate && latest && !running;
  const updateBannerHtml = showUpdateBanner ? `
    <div class="update-banner" id="update-banner">
      <span class="update-banner-ico">🦊</span>
      <span>Update available: <span class="update-banner-tag">${escapeHtml(latest.tag)}</span></span>
      <div class="update-banner-actions">
        <button class="btn btn-primary" id="btn-do-update" style="padding:5px 12px;font-size:12px;">Update now</button>
        <button class="update-banner-dismiss" id="btn-dismiss-update" title="Dismiss">✕</button>
      </div>
    </div>` : '';

  mount.innerHTML = `
    <h1 class="screen-title">Welcome back${status.username ? ', ' + escapeHtml(status.username) : ''}.</h1>
    <p class="screen-sub" id="screen-sub">${earlyFailures.length ? 'Resolve the issue below to launch.' : 'Checking system requirements…'}</p>

    ${updateBannerHtml}

    <div class="play-hero">
      <div class="big-title">${running ? 'Game running' : 'Ready to play'}</div>

      ${activeProfile ? `
      <div class="hero-profile-label">Active profile</div>
      <div class="hero-active-profile">${escapeHtml(activeProfile.name)}</div>
      ` : ''}

      <div class="home-version-row">
        <label class="muted">Profile</label>
        <div class="fox-dropdown" id="home-profile-dropdown" data-active-id="${escapeHtml(activeProfileId || '')}">
          <button type="button" class="fox-dropdown-trigger" id="home-profile-trigger" aria-haspopup="listbox" aria-expanded="false">
            <span class="fox-dropdown-value">${escapeHtml(activeProfile?.name || 'Default')}</span>
            <span class="fox-dropdown-caret" aria-hidden="true">▾</span>
          </button>
          <div class="fox-dropdown-menu" role="listbox" hidden>
            ${profiles.map(p => `
              <button type="button" role="option" class="fox-dropdown-item ${p.id === activeProfileId ? 'is-selected' : ''}" data-value="${escapeHtml(p.id)}">
                ${escapeHtml(p.name)}${p.id === activeProfileId ? ' <span class="fox-dropdown-check">✓</span>' : ''}
              </button>
            `).join('')}
          </div>
        </div>
        ${activeProfile && activeProfile.disabledMods && activeProfile.disabledMods.length
          ? `<span class="badge badge-warn" style="font-size:10px;" title="${activeProfile.disabledMods.length} mod(s) disabled by this profile">${activeProfile.disabledMods.length} OFF</span>`
          : ''}
        ${activeProfile && activeProfile.keepKitsuneEnabled === false
          ? '<span class="badge badge-error" style="font-size:10px;" title="Profile launches without the Fox Client jar">VANILLA-SAFE</span>'
          : ''}
      </div>

      <div class="home-version-row" style="margin-top:6px;">
        <label class="muted">Version</label>
        <span class="home-version-static">${escapeHtml(readiness?.selectedMcVersion || readiness?.targetMcVersion || '—')}</span>
        <span id="version-compat-badge">
          ${readiness?.modJarSource === 'dev-build' && readiness?.clientSupported
            ? '<span class="badge badge-warn" style="font-size:10px;">DEV JAR</span>'
            : readiness?.clientSupported && readiness?.hasModJar
              ? '<span class="badge badge-ok" style="font-size:10px;">CLIENT</span>'
              : readiness?.clientSupported
                ? '<span class="badge badge-error" style="font-size:10px;">VANILLA ONLY</span>'
                : '<span class="badge" style="font-size:10px;">FABRIC ONLY</span>'}
        </span>
      </div>

      <button id="btn-launch" class="btn-play ${ready ? 'ready' : ''}" ${ready ? '' : 'disabled'}>
        ${running ? '● Running' : '▶ PLAY'}
      </button>

      <div class="play-footer" id="play-footer">
        ${lastLaunch?.startedAt ? `Last played ${formatRelative(lastLaunch.startedAt)}` : ''}
      </div>
    </div>

    <div id="failure-cards">${earlyFailures.map(renderFailureCard).join('')}</div>

    ${(readiness && !readiness.vanillaInstalled && !earlyFailures.length)
      ? `<div class="notice" style="margin-top:0;margin-bottom:14px;">
           <strong>Minecraft ${escapeHtml(readiness.selectedMcVersion || readiness.targetMcVersion)} will download automatically.</strong>
           <div style="margin-top:4px;">First PLAY click downloads vanilla MC (~400 MB), Fabric, and all assets from Mojang directly. No official launcher needed.</div>
         </div>`
      : (readiness && readiness.vanillaInstalled && !readiness.fabricProfile && !earlyFailures.length)
        ? `<div class="notice" style="margin-top:0;margin-bottom:14px;">
             <strong>Fabric will be installed automatically on first launch.</strong>
             <div style="margin-top:4px;">First PLAY click fetches the Fabric loader and ~10 MB of libraries from fabricmc.net.</div>
           </div>`
        : ''}

    <div class="home-grid">
      <div>
        <div class="section">
          <div class="section-title">News</div>
          <div class="section-sub" id="news-meta">Loading…</div>
          <div id="news-list"></div>
        </div>
      </div>

      <div>
        <div class="section">
          <div class="section-title">Currently installed</div>
          ${renderInstalledCard(installed, latest, upToDate, usingDevJar)}
        </div>

        <div class="section">
          <div class="section-title">Status</div>
          <div class="stat-row"><span>Signed in</span><span class="v">${escapeHtml(status.username || '—')}${status.guest ? ' <span class="badge badge-warn">GUEST</span>' : ''}</span></div>
          <div class="stat-row"><span>Java</span><span class="v" id="java-stat-value"><span class="badge">Checking…</span></span></div>
          <div class="stat-row"><span>Fabric ${readiness?.selectedMcVersion || readiness?.targetMcVersion || ''}</span><span class="v">${readiness?.fabricProfile ? '<span class="badge badge-ok">installed</span>' : '<span class="badge badge-error">missing</span>'}</span></div>
          <div class="stat-row"><span>Client jar</span><span class="v">${!readiness?.clientSupported ? '<span class="badge">n/a</span>' : readiness?.modJarSource === 'release' ? '<span class="badge badge-ok">release</span>' : readiness?.modJarSource === 'dev-build' ? '<span class="badge badge-warn">dev build</span>' : '<span class="badge badge-error">none</span>'}</span></div>
        </div>

        <div class="section">
          <div class="section-title">Quick actions</div>
          <div style="display:flex;gap:8px;flex-wrap:wrap;">
            <button class="btn" id="btn-open-gamedir">Open game directory</button>
            <button class="btn" id="btn-open-screenshots">Open screenshots</button>
            <button class="btn" id="btn-reinstall-mods" title="Delete every recommended-mod jar and reinstall the full pack with dependencies.">↻ Reinstall mods</button>
          </div>
          <div id="reinstall-status" class="status muted" style="margin-top:6px;font-size:11px;"></div>
        </div>
      </div>
    </div>

    <div class="status muted" id="play-status" style="margin-top:8px;"></div>
  `;

  loadNews();
  wireLaunch();
  wireQuickActions();
  wireFailureLinks();
  wireUpdateBanner();
  patchJava();

  // ---- functions ----

  function setStage(statEl, message, spinning = true) {
    if (!statEl) return;
    if (spinning) {
      statEl.innerHTML = `<span class="launch-stage"><span class="stage-spinner"></span>${escapeHtml(message)}</span>`;
    } else {
      statEl.innerHTML = `<span class="muted" style="font-size:12px;">${escapeHtml(message)}</span>`;
    }
  }

  function wireLaunch() {
    wireProfileDropdown();

    const btn = document.getElementById('btn-launch');
    const stat = document.getElementById('play-status');
    if (!btn) return;

    // Launch-stage progress messages.
    if (window.fox.onLaunchStage) {
      const offStage = window.fox.onLaunchStage(({ message }) => {
        setStage(stat, message, true);
      });
      activeUnsubs.push(offStage);
    }

    // Game-exit listener lives at screen scope, not inside the click handler.
    // This means it fires correctly even when:
    //   • the game was already running when the home screen loaded, OR
    //   • the user navigated away and back during a launch attempt.
    // `running` is mutable so patchJava() re-evaluates without the stale value.
    const offExit = window.fox.onGameExit(({ code }) => {
      running = false;
      btn.disabled = false;
      btn.textContent = '▶ PLAY';
      setStage(stat, code === 0 ? 'Game exited cleanly.' : `Game exited (code ${code}).`, false);
      if (!ready) {
        // ready was blocked because game was running at render time — re-run
        // the readiness check now that running = false.
        patchJava();
      } else {
        btn.classList.add('ready');
      }
    });
    activeUnsubs.push(offExit);

    // Crash modal (separate from exit — both events fire on a crash).
    const offCrash = window.fox.onGameCrash((info) => {
      import('./play.js').then(m => m.showCrashModal && m.showCrashModal(info));
    });
    activeUnsubs.push(offCrash);

    btn.addEventListener('click', async () => {
      if (!ready) return;
      btn.disabled = true;
      btn.classList.remove('ready');
      btn.textContent = 'Starting…';
      setStage(stat, 'Preparing launch…', true);

      try {
        const r = await window.fox.launchGame();
        if (!r.ok) {
          btn.disabled = false;
          btn.textContent = '▶ PLAY';
          if (ready) btn.classList.add('ready');
          stat.innerHTML = `<span style="color:var(--danger);font-size:12px;">${escapeHtml(r.error || 'unknown error')}</span>`;
          return;
        }
        btn.textContent = '● Running';
        setStage(stat, `Game started · PID ${r.pid}`, false);
      } catch (err) {
        btn.disabled = false;
        btn.textContent = '▶ PLAY';
        if (ready) btn.classList.add('ready');
        stat.innerHTML = `<span style="color:var(--danger);font-size:12px;">${escapeHtml(err.message)}</span>`;
      }
    });
  }

  function wireUpdateBanner() {
    const banner  = document.getElementById('update-banner');
    if (!banner) return;

    const dismissBtn = document.getElementById('btn-dismiss-update');
    if (dismissBtn) dismissBtn.addEventListener('click', () => banner.remove());

    const updateBtn = document.getElementById('btn-do-update');
    if (!updateBtn) return;
    updateBtn.addEventListener('click', async () => {
      updateBtn.disabled = true;
      updateBtn.textContent = 'Updating…';
      try {
        // checkUpdates both checks and downloads in the existing updater flow
        await window.fox.checkUpdates();
        banner.innerHTML = `<span class="update-banner-ico">✓</span><span>Updated! Restart the game to use the new client.</span>`;
      } catch (err) {
        updateBtn.disabled = false;
        updateBtn.textContent = 'Retry';
        banner.querySelector('span:nth-child(2)').textContent = `Update failed: ${err.message}`;
      }
    });
  }

  function wireProfileDropdown() {
    const root = document.getElementById('home-profile-dropdown');
    if (!root) return;
    const trigger = root.querySelector('.fox-dropdown-trigger');
    const menu = root.querySelector('.fox-dropdown-menu');
    if (!trigger || !menu) return;

    const open = () => {
      menu.hidden = false;
      trigger.setAttribute('aria-expanded', 'true');
      root.classList.add('is-open');
    };
    const close = () => {
      menu.hidden = true;
      trigger.setAttribute('aria-expanded', 'false');
      root.classList.remove('is-open');
    };
    const toggle = () => (menu.hidden ? open() : close());

    trigger.addEventListener('click', (e) => { e.stopPropagation(); toggle(); });

    // Per-item click: switch active profile and re-render Home (badges + the
    // sidebar profile line both depend on which profile is active).
    for (const item of menu.querySelectorAll('.fox-dropdown-item')) {
      item.addEventListener('click', async (e) => {
        e.stopPropagation();
        const id = item.dataset.value;
        close();
        try {
          await window.fox.setActiveProfile(id);
          renderHome(mount);
        } catch (_) {}
      });
    }

    // Click outside / ESC closes the menu. Listeners attach to document but
    // self-detach via the screen-unmount MutationObserver further down.
    const onDocClick = (e) => { if (!root.contains(e.target)) close(); };
    const onKey = (e) => { if (e.key === 'Escape') close(); };
    document.addEventListener('click', onDocClick);
    document.addEventListener('keydown', onKey);
    activeUnsubs.push(() => {
      document.removeEventListener('click', onDocClick);
      document.removeEventListener('keydown', onKey);
    });
  }

  function wireQuickActions() {
    const dirBtn = document.getElementById('btn-open-gamedir');
    if (dirBtn) dirBtn.addEventListener('click', async () => {
      const dir = (s.gameDir && s.gameDir.trim()) || (await window.fox.defaultGameDir());
      window.fox.openPath(dir);
    });
    const ssBtn = document.getElementById('btn-open-screenshots');
    if (ssBtn) ssBtn.addEventListener('click', async () => {
      const dir = (s.gameDir && s.gameDir.trim()) || (await window.fox.defaultGameDir());
      const sep = (dir.endsWith('/') || dir.endsWith('\\')) ? '' : '/';
      window.fox.openPath(dir + sep + 'screenshots');
    });

    const reBtn = document.getElementById('btn-reinstall-mods');
    const reStatus = document.getElementById('reinstall-status');
    if (reBtn) reBtn.addEventListener('click', async () => {
      if (!confirm(
        'Reinstall all recommended mods?\n\n' +
        'This will delete every recommended-mod jar in your mods folder ' +
        '(your Fox client mod and custom mods are kept), then download the ' +
        'full pack fresh with all required dependencies.\n\nContinue?'
      )) return;
      reBtn.disabled = true;
      const orig = reBtn.textContent;
      reBtn.textContent = 'Reinstalling…';
      reStatus.textContent = 'Starting…';
      const unsub = window.fox.onRecommendedProgress((data) => {
        if (data && data.message) reStatus.textContent = data.message;
      });
      try {
        const r = await window.fox.recommendedReinstallAll();
        if (r && r.ok) {
          const results = r.results || [];
          const tally = results.reduce((g, x) => { g[x.status] = (g[x.status] || 0) + 1; return g; }, {});
          const installed = tally.installed || 0;
          const errored   = (tally.error || 0) + (tally['no-version'] || 0);
          reStatus.textContent = `Done — removed ${r.removed || 0}, installed ${installed}` +
            (errored ? `, failed ${errored} (see Logs)` : '');
        } else {
          reStatus.textContent = 'Reinstall failed: ' + ((r && r.error) || 'unknown error');
        }
      } catch (err) {
        reStatus.textContent = 'Reinstall failed: ' + err.message;
      } finally {
        try { unsub && unsub(); } catch (_) {}
        reBtn.textContent = orig;
        reBtn.disabled = false;
      }
    });
  }

  function wireFailureLinks() {
    for (const a of mount.querySelectorAll('[data-fail-link]')) {
      a.addEventListener('click', (e) => {
        e.preventDefault();
        const url = a.dataset.failLink;
        if (url.startsWith('#')) location.hash = url.slice(1);
        else window.fox.openExternal(url);
      });
    }
  }

  function wireJavaDownloadCard() {
    const btn      = document.getElementById('btn-java-download');
    const progress = document.getElementById('java-dl-progress');
    const bar      = document.getElementById('java-dl-bar');
    const fill     = document.getElementById('java-dl-fill');
    if (!btn) return;

    // Subscribe to main-process progress events.
    const offProgress = window.fox.onJavaInstallProgress(({ message, percent }) => {
      if (progress) progress.textContent = message || '';
      if (fill) fill.style.width = `${percent || 0}%`;
    });
    activeUnsubs.push(offProgress);

    btn.addEventListener('click', async () => {
      btn.disabled = true;
      btn.textContent = 'Downloading…';
      if (bar) bar.style.display = 'block';
      if (progress) progress.textContent = 'Starting…';

      const result = await window.fox.installJava().catch(err => ({ ok: false, error: err.message }));

      if (!document.body.contains(mount)) return;

      if (result.ok) {
        // Java is now available — re-run patchJava to clear the card and enable PLAY.
        patchJava();
      } else {
        btn.disabled = false;
        btn.textContent = 'Retry';
        if (progress) progress.textContent = `Failed: ${result.error}`;
      }
    });
  }

  // Performance pack runs automatically in the background on first launch
  // — see main/index.js#autoInstallRecommended. The result is surfaced via
  // a toast in app.js, not in this screen.

  async function loadNews() {
    const meta = document.getElementById('news-meta');
    const list = document.getElementById('news-list');
    let payload;
    try { payload = await window.fox.fetchNews(); }
    catch (_) { payload = { items: [], source: 'empty' }; }
    if (!payload.items || payload.items.length === 0) {
      meta.textContent = payload.error
        ? `News feed unavailable: ${payload.error}.`
        : 'No news yet.';
      list.innerHTML = '';
      return;
    }
    meta.textContent = payload.source === 'cache'
      ? `Cached · ${formatRelative(payload.fetchedAt)}`
      : payload.source === 'fresh' ? 'Updated just now' : '';
    list.innerHTML = payload.items.slice(0, 3).map((it) => `
      <div class="news-item">
        <div class="news-title">${escapeHtml(it.title)}</div>
        <div class="news-meta">${escapeHtml(it.date || '')}</div>
        <div class="news-body">${escapeHtml(truncate(it.body || '', 360))}</div>
      </div>
    `).join('');
  }

  // Deferred: detectJava() spawns one child process per JDK candidate (200–800 ms
  // cold on Windows). Keeping it out of the initial Promise.all lets the home
  // screen paint immediately; the badge + PLAY button update once it resolves.
  async function patchJava() {
    const java = await window.fox.detectJava()
      .catch(() => ({ ok: false, reason: 'detection failed' }));

    if (!document.body.contains(mount)) return;

    const javaEl = document.getElementById('java-stat-value');
    if (javaEl) {
      javaEl.innerHTML = java.ok
        ? `<span class="badge badge-ok">${escapeHtml(java.versionString)}</span>`
        : '<span class="badge badge-error">missing</span>';
    }

    if (!earlyFailures.length) {
      const lateFailures = [];
      if (!java.ok) {
        // Java missing — offer auto-download instead of a link to adoptium.net.
        lateFailures.push({ _javaDownload: true });
      } else if (!status.signedIn) {
        lateFailures.push({
          title: 'Not signed in',
          detail: 'Sign in or pick a guest name on the welcome screen.',
        });
      } else if (s.minRam > s.maxRam) {
        lateFailures.push({
          title: 'RAM range invalid',
          detail: `Minimum (${s.minRam} GB) exceeds maximum (${s.maxRam} GB). Fix in Settings.`,
          action: { label: 'Open Settings', url: '#settings' },
        });
      }

      const cards = document.getElementById('failure-cards');
      if (cards) {
        cards.innerHTML = lateFailures.map(f => f._javaDownload ? renderJavaDownloadCard() : renderFailureCard(f)).join('');
        if (lateFailures.length) {
          wireFailureLinks();
          wireJavaDownloadCard();
        }
      }

      ready = !lateFailures.length && !running;

      const sub = document.getElementById('screen-sub');
      if (sub) sub.textContent = ready ? 'Ready to launch.' : 'Resolve the issue below to launch.';

      const btn = document.getElementById('btn-launch');
      if (btn) {
        btn.disabled = !ready;
        btn.className = `btn-play${ready ? ' ready' : ''}`;
      }

      if (ready) {
        const footer = document.getElementById('play-footer');
        if (footer) {
          const ramRes = `${s.minRam}–${s.maxRam} GB RAM · ${s.resolution.width}×${s.resolution.height}${s.resolution.fullscreen ? ' fullscreen' : ''}`;
          const lastStr = lastLaunch?.startedAt ? ` · Last played ${formatRelative(lastLaunch.startedAt)}` : '';
          footer.textContent = ramRes + lastStr;
        }
      }
    }
  }

  // Lifecycle: drop subs when this screen unmounts.
  // Listen once for the navigate()-fired unmount event. Replaces the old
  // MutationObserver which never actually fired because navigate() leaves
  // the loading placeholder behind (childElementCount=1, not 0).
  const onUnmount = () => {
    for (const off of activeUnsubs) { try { off(); } catch (_) {} }
    activeUnsubs = [];
    mount.removeEventListener('fox:screen-unmount', onUnmount);
  };
  mount.addEventListener('fox:screen-unmount', onUnmount, { once: true });
}

function renderJavaDownloadCard() {
  return `
    <div class="notice warn" style="margin-top:0;margin-bottom:14px;" id="java-dl-card">
      <strong>Java 21 not found</strong>
      <div style="margin-top:4px;">Fox Launcher can download a JRE automatically (~200 MB) — no system install needed.</div>
      <div style="margin-top:8px;display:flex;align-items:center;gap:10px;">
        <button class="btn btn-primary" id="btn-java-download">Download Java 21</button>
        <span id="java-dl-progress" style="font-size:12px;color:var(--muted);"></span>
      </div>
      <div id="java-dl-bar" style="display:none;margin-top:8px;height:4px;background:var(--border);border-radius:2px;">
        <div id="java-dl-fill" style="height:100%;width:0%;background:var(--fox-orange);border-radius:2px;transition:width 0.2s;"></div>
      </div>
    </div>
  `;
}

function renderFailureCard(f) {
  const action = f.action
    ? `<a href="#" data-fail-link="${escapeHtml(f.action.url)}" style="color:var(--fox-orange);">${escapeHtml(f.action.label)} →</a>`
    : '';
  return `
    <div class="notice warn" style="margin-top:0;margin-bottom:14px;">
      <strong>${escapeHtml(f.title)}</strong>
      <div style="margin-top:4px;">${escapeHtml(f.detail)}</div>
      ${action ? `<div style="margin-top:6px;">${action}</div>` : ''}
    </div>
  `;
}

function renderInstalledCard(installed, latest, upToDate, usingDevJar) {
  // Dev-build path — most likely case for someone running `npm start` from
  // the project repo without a GitHub release configured. We show this
  // first because it's the actual jar being used for launches.
  if (!installed && usingDevJar) {
    return `
      <div class="stat-row">
        <span>Source</span>
        <span class="v"><span class="badge badge-warn">DEV BUILD</span></span>
      </div>
      <div class="muted" style="margin-top:6px;font-size:11px;">
        Using <code>build/libs/kitsune-client-*.jar</code> from the local project.
        Set <code>githubRepo</code> in Settings → Updates to enable release-tracked builds.
      </div>
    `;
  }
  if (!installed) {
    return `<div class="muted">No client jar installed yet. Run <code>./gradlew build</code> in the project root, or configure a GitHub repo in Settings → Updates.</div>`;
  }
  const newerBadge = (latest && !upToDate)
    ? `<span class="badge badge-warn">UPDATE: ${escapeHtml(latest.tag)}</span>`
    : `<span class="badge badge-ok">UP TO DATE</span>`;
  return `
    <div class="stat-row">
      <span>Tag</span>
      <span class="v">${escapeHtml(installed.tag)} ${newerBadge}</span>
    </div>
    ${installed.installedAt ? `
      <div class="stat-row">
        <span>Installed</span>
        <span class="v">${escapeHtml(formatRelative(installed.installedAt))}</span>
      </div>
    ` : ''}
  `;
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
function truncate(s, n) { return s.length > n ? s.slice(0, n) + '…' : s; }
