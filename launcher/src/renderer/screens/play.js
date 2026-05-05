// Play screen.
//
// Pre-flight checks → big animated PLAY button → spawn the game.
// On exit, if the game crashed (non-zero) AND a fresh crash report exists, a
// modal surfaces with copy / open-folder actions. Listener bookkeeping is
// strict: every IPC subscription is owned by this render's lifetime and
// released on unmount.

let activeUnsubs = []; // module-scoped so a stale render's subs can't outlive us

export async function renderPlay(mount) {
  // Tear down any subscriptions the previous render established.
  for (const off of activeUnsubs) { try { off(); } catch (_) {} }
  activeUnsubs = [];

  const [s, java, vs, running, lastLaunch] = await Promise.all([
    window.fox.getSettings(),
    window.fox.detectJava(),
    window.fox.listVersions(),
    window.fox.isRunning(),
    window.fox.lastLaunch().catch(() => null),
  ]);
  const gameDir = (s.gameDir && s.gameDir.trim()) || (await window.fox.defaultGameDir());

  const checks = [
    { label: 'Minecraft directory', ok: vs.exists, detail: vs.exists ? gameDir : `${gameDir} not found — install Minecraft via the official launcher first` },
    { label: 'Java installation',   ok: java.ok,   detail: java.ok ? `${java.versionString}` : (java.reason || 'Not found') },
    { label: 'Installed versions',  ok: vs.versions.length > 0, detail: vs.versions.length ? `${vs.versions.length} available` : 'None — open the official launcher to download one' },
    { label: 'Selected version',    ok: !!(s.selectedVersion && vs.versions.includes(s.selectedVersion)) || vs.versions.length > 0, detail: s.selectedVersion || (vs.versions[0] || '(none)') },
    { label: 'RAM',                 ok: s.minRam <= s.maxRam, detail: `${s.minRam} GB / ${s.maxRam} GB` },
  ];
  const anyFail = checks.some((c) => !c.ok);
  const ready = !anyFail && !running;

  const lastLaunchLine = lastLaunch?.startedAt
    ? `Last launch: ${escapeHtml(lastLaunch.versionId || '?')} · ${formatRelative(lastLaunch.startedAt)}`
    : '';

  mount.innerHTML = `
    <h1 class="screen-title">Play</h1>
    <p class="screen-sub">Launch Minecraft with the Kitsune client.</p>

    <div class="play-hero">
      <div class="big-title">${running ? 'Game running' : 'Ready to play'}</div>
      <div class="sub">${escapeHtml(s.selectedVersion || vs.versions[0] || '(no version selected)')}</div>
      <button id="btn-launch" class="btn-play ${ready ? 'ready' : ''}" ${anyFail || running ? 'disabled' : ''}>
        ${running ? '● Running' : '▶ PLAY'}
      </button>
      <div class="play-footer">
        ${anyFail
          ? '<span style="color:var(--warn);">Fix the issues below to enable launch.</span>'
          : `Uses ${s.minRam}–${s.maxRam} GB RAM · ${s.resolution.width}×${s.resolution.height}${s.resolution.fullscreen ? ' fullscreen' : ''}`}
      </div>
      ${lastLaunchLine ? `<div class="play-footer">${lastLaunchLine}</div>` : ''}
    </div>

    <div class="section">
      <div class="section-title">Pre-flight checks</div>
      <div class="section-sub">All checks must pass before launching.</div>
      ${checks.map((c) => `
        <div class="stat-row">
          <span>${c.ok ? '<span style="color:var(--success);">●</span>' : '<span style="color:var(--danger);">●</span>'} ${escapeHtml(c.label)}</span>
          <span class="muted">${escapeHtml(c.detail)}</span>
        </div>
      `).join('')}
    </div>

    <div class="status muted" id="play-status"></div>
    <div class="progress hidden" id="play-progress"><div class="bar"></div></div>
  `;

  const btn  = document.getElementById('btn-launch');
  const stat = document.getElementById('play-status');
  const prog = document.getElementById('play-progress');

  if (btn) {
    btn.addEventListener('click', async () => {
      btn.disabled = true;
      btn.classList.remove('ready');
      btn.textContent = 'Starting…';
      stat.textContent = 'Launching…';
      prog.classList.remove('hidden');
      prog.classList.add('indeterminate');

      // Subscribe to crash + exit BEFORE launchGame returns so we never miss
      // the exit signal even on quick failures.
      const offCrash = window.fox.onGameCrash((info) => showCrashModal(info));
      const offExit = window.fox.onGameExit(({ code }) => {
        btn.disabled = false;
        btn.textContent = '▶ PLAY';
        if (ready) btn.classList.add('ready');
        prog.classList.add('hidden');
        prog.classList.remove('indeterminate');
        stat.textContent = code === 0
          ? `Game exited cleanly.`
          : `Game exited with code ${code}.`;
        offExit();
        // Keep crash listener alive — a crash report can land a moment after
        // the close event. We tear it down on unmount instead.
      });
      activeUnsubs.push(offCrash, offExit);

      try {
        const r = await window.fox.launchGame();
        if (!r.ok) {
          btn.disabled = false;
          btn.textContent = '▶ PLAY';
          if (ready) btn.classList.add('ready');
          stat.innerHTML = `<span style="color:var(--danger);">${escapeHtml(r.error)}</span>`;
          prog.classList.add('hidden');
          prog.classList.remove('indeterminate');
          offExit();
          return;
        }
        btn.textContent = '● Running';
        stat.textContent = `Game started (PID ${r.pid}). See the Logs tab for live output.`;
      } catch (err) {
        btn.disabled = false;
        btn.textContent = '▶ PLAY';
        if (ready) btn.classList.add('ready');
        stat.innerHTML = `<span style="color:var(--danger);">${escapeHtml(err.message)}</span>`;
        prog.classList.add('hidden');
        prog.classList.remove('indeterminate');
        offExit();
      }
    });
  }

  // Lifecycle: clean up listeners when this screen unmounts (route change,
  // app quit). The MutationObserver fires only for top-level removals of the
  // mount element so it's cheap.
  const observer = new MutationObserver(() => {
    if (!document.body.contains(mount) || mount.childElementCount === 0) {
      for (const off of activeUnsubs) { try { off(); } catch (_) {} }
      activeUnsubs = [];
      observer.disconnect();
    }
  });
  observer.observe(document.body, { childList: true, subtree: true });
}

// ---- crash modal ----
// Exported so home.js (and any future launcher screen) can surface the same
// modal without depending on the whole Play renderer being mounted.
export function showCrashModal(info) {
  // Avoid stacking duplicates if the OS dispatches twice.
  if (document.getElementById('crash-modal')) return;

  const root = document.createElement('div');
  root.className = 'modal-backdrop';
  root.id = 'crash-modal';
  root.innerHTML = `
    <div class="modal" role="dialog" aria-labelledby="crash-title">
      <div class="modal-header">
        <span class="icon-warn">⚠</span>
        <div class="title" id="crash-title">Minecraft crashed</div>
        <span class="muted">exit ${info?.exitCode ?? '?'}</span>
      </div>
      <div class="modal-body">
        <div class="muted" style="margin-bottom:10px;">
          ${escapeHtml(info?.name || 'crash report')}
          · ${formatBytes(info?.sizeBytes || 0)}
          · ${formatRelative(info?.mtimeMs || Date.now())}
        </div>
        <pre class="crash" id="crash-content">Loading…</pre>
      </div>
      <div class="modal-footer">
        <button class="btn" id="crash-copy">Copy report</button>
        <button class="btn" id="crash-folder">Open crash folder</button>
        <button class="btn btn-primary" id="crash-close">Close</button>
      </div>
    </div>
  `;
  document.body.appendChild(root);

  // Wrap close so every dismiss path (Esc, click-outside, Close button)
  // also detaches the document-level keydown listener. Forgetting to
  // remove it leaks a listener per crash forever; opening a 2nd modal
  // would double-fire on Esc.
  const onEsc = (ev) => { if (ev.key === 'Escape') close(); };
  const close = () => {
    try { root.remove(); } catch (_) {}
    document.removeEventListener('keydown', onEsc);
  };
  root.addEventListener('click', (e) => { if (e.target === root) close(); });
  root.querySelector('#crash-close').addEventListener('click', close);
  root.querySelector('#crash-folder').addEventListener('click', () => window.fox.openCrashFolder());
  document.addEventListener('keydown', onEsc);

  (async () => {
    const r = await window.fox.readCrashReport(info.path).catch(() => ({ ok: false, error: 'unreadable' }));
    const pre = root.querySelector('#crash-content');
    if (!pre) return; // closed before fetch returned
    pre.textContent = r.ok ? r.content : `Failed to read report: ${r.error}`;
    root.querySelector('#crash-copy').addEventListener('click', () => {
      navigator.clipboard.writeText(r.ok ? r.content : '').catch(() => {});
    });
  })();
}

function formatBytes(b) {
  if (b < 1024) return `${b} B`;
  if (b < 1024 * 1024) return `${Math.round(b / 1024)} KB`;
  return `${(b / (1024 * 1024)).toFixed(1)} MB`;
}

function formatRelative(ts) {
  if (!ts) return '—';
  const diffSec = Math.max(0, Math.round((Date.now() - ts) / 1000));
  if (diffSec < 60)  return `${diffSec}s ago`;
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
