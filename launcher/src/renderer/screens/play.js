// play.js — crash modal, shared across launcher screens.
//
// renderPlay() was removed: the launch flow now lives in home.js.
// showCrashModal() is kept here and imported dynamically by home.js
// so the full crash UI is only loaded when a crash actually occurs.

import { escapeHtml, formatRelative, formatBytes } from '../util.js';

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
  // also detaches the document-level keydown listener.
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

