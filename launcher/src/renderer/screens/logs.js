// Logs screen — live console output from the running game process.
//
// Live tail with: search-as-you-type filter (case-insensitive substring),
// kind filter (info / stdout / stderr / warn / error), Save-to-file, Copy-all,
// Clear, and Auto-scroll. The Mute toggle stops the live appender entirely
// so a chatty server doesn't blow past the 5000-line ring buffer if the
// user is reading.

let activeOff = null;

export async function renderLogs(mount) {
  if (activeOff) { try { activeOff(); } catch (_) {} activeOff = null; }

  mount.innerHTML = `
    <h1 class="screen-title">Logs</h1>
    <p class="screen-sub">Live output from the currently-running game.</p>

    <div class="log-toolbar">
      <input type="text" id="log-search" class="input" placeholder="Filter…" style="flex:1;max-width:320px;" />
      <select id="log-kind" class="select" style="margin-left:6px;">
        <option value="">All kinds</option>
        <option value="info">info</option>
        <option value="stdout">stdout</option>
        <option value="stderr">stderr</option>
        <option value="warn">warn</option>
        <option value="error">error</option>
      </select>
      <button class="btn" id="btn-save">Save to file</button>
      <button class="btn" id="btn-upload" title="Upload to mclo.gs and copy the share link">Upload</button>
      <button class="btn" id="btn-copy">Copy</button>
      <button class="btn" id="btn-clear">Clear</button>
      <label class="checkbox" style="margin-left:12px;">
        <input type="checkbox" id="autoscroll" checked /> Auto-scroll
      </label>
      <span class="status muted" id="log-status" style="margin-left:auto;" aria-live="polite"></span>
    </div>

    <div class="log-view" id="log-view"></div>
  `;

  const view = document.getElementById('log-view');
  const autoscroll = document.getElementById('autoscroll');
  const searchInput = document.getElementById('log-search');
  const kindInput = document.getElementById('log-kind');

  // Filter state — applied on every render and again on every new line.
  let needle = '';
  let kindFilter = '';

  const matches = (line) => {
    if (kindFilter && (line.kind || 'stdout') !== kindFilter) return false;
    if (needle && !line.text.toLowerCase().includes(needle)) return false;
    return true;
  };

  // Hold the unfiltered ring buffer locally so search/filter changes can
  // re-render without round-tripping to the main process.
  let buffer = await window.fox.getAllLogs();
  rerender();

  const off = window.fox.onLog((entry) => {
    for (const piece of String(entry.text).split(/\r?\n/)) {
      if (!piece) continue;
      const line = { kind: entry.kind, text: piece, ts: entry.ts };
      buffer.push(line);
      if (buffer.length > 5000) buffer.shift();
      if (matches(line)) appendLine(view, line);
    }
    scrollToEndIfNeeded();
  });
  activeOff = off;

  searchInput.addEventListener('input', () => {
    needle = searchInput.value.trim().toLowerCase();
    rerender();
  });
  kindInput.addEventListener('change', () => {
    kindFilter = kindInput.value;
    rerender();
  });

  document.getElementById('btn-clear').addEventListener('click', async () => {
    await window.fox.clearLogs();
    buffer = [];
    rerender();
  });

  document.getElementById('btn-copy').addEventListener('click', () => {
    const text = buffer.filter(matches).map(l => `[${formatTs(l.ts)}] [${l.kind}] ${l.text}`).join('\n');
    navigator.clipboard.writeText(text);
    flashStatus('Copied to clipboard.');
  });

  document.getElementById('btn-save').addEventListener('click', async () => {
    const r = await window.fox.saveLogs();
    if (r.cancelled) return;
    if (r.ok) flashStatus(`Saved ${r.path}`);
    else flashStatus(`Save failed: ${r.error}`);
  });

  document.getElementById('btn-upload').addEventListener('click', async () => {
    if (!buffer.length) { flashStatus('Nothing to upload.'); return; }
    // Uploading publishes the log at a public mclo.gs URL — confirm first.
    if (!confirm('Upload the current log to mclo.gs?\n\nThe log becomes visible to anyone with the link. Your home-directory paths are scrubbed and mclo.gs hides IP addresses.')) return;
    const btn = document.getElementById('btn-upload');
    btn.disabled = true;
    flashStatus('Uploading…');
    try {
      const r = await window.fox.uploadLogs();
      if (r.ok) {
        try { await navigator.clipboard.writeText(r.url); } catch (_) {}
        flashStatus(`Uploaded — link copied: ${r.url}`);
      } else {
        flashStatus(`Upload failed: ${r.error}`);
      }
    } finally {
      btn.disabled = false;
    }
  });

  // Lifecycle — drop subscription when the screen unmounts.
  const observer = new MutationObserver(() => {
    if (!document.body.contains(view)) {
      try { off(); } catch (_) {}
      activeOff = null;
      observer.disconnect();
    }
  });
  observer.observe(document.body, { childList: true, subtree: true });

  function rerender() {
    view.innerHTML = '';
    for (const line of buffer) {
      if (matches(line)) appendLine(view, line);
    }
    scrollToEndIfNeeded();
  }

  function scrollToEndIfNeeded() {
    if (autoscroll.checked) view.scrollTop = view.scrollHeight;
  }

  function flashStatus(msg) {
    const s = document.getElementById('log-status');
    s.textContent = msg;
    clearTimeout(flashStatus._t);
    flashStatus._t = setTimeout(() => { s.textContent = ''; }, 2200);
  }
}

function appendLine(view, line) {
  const div = document.createElement('div');
  div.className = 'log-line ' + (line.kind || 'stdout');
  const tsSpan = document.createElement('span');
  tsSpan.className = 'ts';
  tsSpan.textContent = `[${formatTs(line.ts)}]`;
  div.appendChild(tsSpan);
  div.appendChild(document.createTextNode(line.text));
  view.appendChild(div);
}

function formatTs(ts) {
  return new Date(ts || Date.now()).toLocaleTimeString();
}
