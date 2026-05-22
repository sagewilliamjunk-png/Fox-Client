// Screenshots gallery screen.
//
// Shows a thumbnail grid of every .png/.jpg in the active profile's
// screenshots directory.  Per-profile: isolated profiles have their own
// <instances>/<id>/screenshots/; linked profiles use the global game dir.
//
// Features:
//   • Responsive thumbnail grid grouped by date
//   • Hover overlay with "reveal in Explorer" + "delete" actions
//   • Lightbox: click any thumbnail for full-res view with prev/next (← →)
//     and Delete shortcut
//   • Profile selector — switch between any profile's screenshots
//   • Live search filter by filename
//   • Refresh button

let activeUnsubs  = [];  // torn down on unmount
let allScreenshots = []; // full sorted list, mutated on delete
let lightboxIndex  = -1; // index into allScreenshots
let currentProfileId = null;

// ── main entry point ────────────────────────────────────────────────────────

export async function renderScreenshots(mount) {
  // Tear down listeners from any previous render.
  for (const off of activeUnsubs) { try { off(); } catch (_) {} }
  activeUnsubs = [];
  allScreenshots = [];
  lightboxIndex  = -1;

  const [s, profilesDoc] = await Promise.all([
    window.fox.getSettings().catch(() => ({})),
    window.fox.listProfiles().catch(() => ({ profiles: [] })),
  ]);

  const profiles = profilesDoc.profiles || [];
  currentProfileId = s.selectedProfile || (profiles[0] && profiles[0].id) || null;

  mount.innerHTML = `
    <div class="ss-header">
      <h1 class="screen-title" style="margin:0;">Screenshots</h1>
      <div class="ss-toolbar">
        ${profiles.length > 1 ? `
          <select id="ss-profile-select" class="select ss-select" aria-label="Profile">
            ${profiles.map(p => `
              <option value="${esc(p.id)}"${p.id === currentProfileId ? ' selected' : ''}>
                ${esc(p.name)}
              </option>
            `).join('')}
          </select>
        ` : ''}
        <input id="ss-search" class="input ss-search-input"
               placeholder="Filter by name…" autocomplete="off" spellcheck="false"
               aria-label="Filter screenshots" />
        <button class="btn ss-toolbar-btn" id="ss-open-folder" title="Open screenshots folder">
          Open folder
        </button>
        <button class="btn ss-toolbar-btn ss-refresh-btn" id="ss-refresh"
                title="Refresh" aria-label="Refresh">↺</button>
      </div>
    </div>

    <div id="ss-info" class="ss-info muted"></div>
    <div id="ss-gallery" class="ss-gallery-root"></div>

    <!-- Lightbox — fixed overlay, lives inside mount so it unmounts with the screen -->
    <div id="ss-lightbox" class="ss-lightbox hidden" role="dialog"
         aria-modal="true" aria-label="Screenshot viewer">
      <div class="ss-lb-backdrop" id="ss-lb-backdrop"></div>

      <button class="ss-lb-close" id="ss-lb-close"
              title="Close (Esc)" aria-label="Close">✕</button>

      <button class="ss-lb-nav ss-lb-prev" id="ss-lb-prev"
              title="Previous (←)" aria-label="Previous">‹</button>

      <div class="ss-lb-stage">
        <div class="ss-lb-img-wrap">
          <div class="ss-lb-spinner" id="ss-lb-spinner"></div>
          <img class="ss-lb-img" id="ss-lb-img" alt="" />
        </div>
        <div class="ss-lb-footer">
          <div id="ss-lb-name"  class="ss-lb-name"></div>
          <div id="ss-lb-meta"  class="ss-lb-meta muted"></div>
          <div class="ss-lb-actions">
            <button class="btn ss-toolbar-btn" id="ss-lb-reveal">Show in Explorer</button>
            <button class="btn btn-danger ss-toolbar-btn" id="ss-lb-delete">Delete</button>
          </div>
          <div id="ss-lb-counter" class="ss-lb-counter muted"></div>
        </div>
      </div>

      <button class="ss-lb-nav ss-lb-next" id="ss-lb-next"
              title="Next (→)" aria-label="Next">›</button>
    </div>
  `;

  wireControls();
  await loadGallery();

  // Unmount observer: tear down global listeners when navigate() replaces mount.
  const observer = new MutationObserver(() => {
    if (!document.body.contains(mount) || mount.childElementCount === 0) {
      for (const off of activeUnsubs) { try { off(); } catch (_) {} }
      activeUnsubs = [];
      observer.disconnect();
    }
  });
  observer.observe(document.body, { childList: true, subtree: true });
}

// ── data loading ─────────────────────────────────────────────────────────────

async function loadGallery() {
  const gallery = document.getElementById('ss-gallery');
  const info    = document.getElementById('ss-info');
  if (!gallery) return;

  gallery.innerHTML = `
    <div class="ss-loading">
      <div class="ss-loading-spinner"></div>
      <span>Loading screenshots…</span>
    </div>
  `;

  let result;
  try {
    result = await window.fox.listScreenshots(currentProfileId);
  } catch (_) {
    result = { screenshots: [], exists: false };
  }

  allScreenshots = result.screenshots || [];

  // Update the info bar.
  if (info) {
    const count = allScreenshots.length;
    if (!result.exists) {
      info.textContent = 'Screenshots folder not found — play the game first to create it.';
    } else if (count === 0) {
      info.textContent = 'No screenshots yet — press F2 in-game to take one.';
    } else {
      const total = allScreenshots.reduce((s, ss) => s + (ss.size || 0), 0);
      info.textContent =
        `${count} screenshot${count !== 1 ? 's' : ''} · ${fmtBytes(total)}`;
      if (result.dir) {
        info.title = result.dir;
      }
    }
  }

  renderGallery();
}

// ── gallery rendering ─────────────────────────────────────────────────────────

function renderGallery() {
  const gallery  = document.getElementById('ss-gallery');
  const searchEl = document.getElementById('ss-search');
  if (!gallery) return;

  const query    = searchEl ? searchEl.value.trim().toLowerCase() : '';
  const filtered = query
    ? allScreenshots.filter(ss => ss.name.toLowerCase().includes(query))
    : allScreenshots;

  if (filtered.length === 0) {
    gallery.innerHTML = `
      <div class="ss-empty">
        <div class="ss-empty-icon">⊡</div>
        <div class="ss-empty-title">
          ${query ? 'No screenshots match your filter' : 'No screenshots yet'}
        </div>
        <div class="ss-empty-sub muted">
          ${query
            ? 'Try clearing the search box.'
            : 'Press <kbd>F2</kbd> in-game to capture a screenshot.'}
        </div>
      </div>
    `;
    return;
  }

  // Group by calendar date (local, newest first).
  const groups = new Map();
  for (const ss of filtered) {
    const d = new Date(ss.mtimeMs).toLocaleDateString(undefined, {
      weekday: 'long', year: 'numeric', month: 'long', day: 'numeric',
    });
    if (!groups.has(d)) groups.set(d, []);
    groups.get(d).push(ss);
  }

  gallery.innerHTML = [...groups.entries()].map(([dateLabel, shots]) => `
    <div class="ss-group">
      <div class="ss-group-label">
        ${esc(dateLabel)}
        <span class="ss-group-count">${shots.length}</span>
      </div>
      <div class="ss-grid">
        ${shots.map(ss => {
          const gIdx = allScreenshots.indexOf(ss);
          return `
            <div class="ss-card" data-idx="${gIdx}" tabindex="0"
                 role="button" aria-label="${esc(ss.name)}">
              <div class="ss-thumb-wrap">
                <img class="ss-thumb"
                     src="${esc(ss.fileUrl)}"
                     alt="${esc(ss.name)}"
                     loading="lazy"
                     draggable="false" />
                <div class="ss-card-overlay" aria-hidden="true">
                  <button class="ss-card-btn"
                          data-action="reveal" data-idx="${gIdx}"
                          title="Show in Explorer" tabindex="-1">⬒</button>
                  <button class="ss-card-btn ss-card-btn-del"
                          data-action="delete" data-idx="${gIdx}"
                          title="Delete" tabindex="-1">🗑</button>
                </div>
              </div>
              <div class="ss-card-info">
                <div class="ss-card-name" title="${esc(ss.name)}">${esc(ss.name)}</div>
                <div class="ss-card-meta muted">${fmtBytes(ss.size || 0)}</div>
              </div>
            </div>
          `;
        }).join('')}
      </div>
    </div>
  `).join('');

  // Wire per-image error handler (CSP forbids the inline onerror attribute,
  // so we attach the listener here instead).
  for (const img of gallery.querySelectorAll('img.ss-thumb')) {
    img.addEventListener('error', () => {
      const card = img.closest('.ss-card');
      if (card) card.classList.add('ss-card-broken');
    });
  }

  // Wire card clicks and overlay buttons.
  for (const card of gallery.querySelectorAll('.ss-card')) {
    const idx = parseInt(card.dataset.idx, 10);

    // Click on card body → open lightbox.
    card.addEventListener('click', (e) => {
      if (e.target.closest('.ss-card-btn')) return;
      openLightbox(idx);
    });
    card.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        openLightbox(idx);
      }
    });
  }

  for (const btn of gallery.querySelectorAll('.ss-card-btn')) {
    btn.addEventListener('click', async (e) => {
      e.stopPropagation();
      const idx = parseInt(btn.dataset.idx, 10);
      const ss  = allScreenshots[idx];
      if (!ss) return;

      if (btn.dataset.action === 'reveal') {
        window.fox.revealScreenshot(ss.path);
      } else if (btn.dataset.action === 'delete') {
        await confirmAndDelete(idx, /* fromLightbox */ false);
      }
    });
  }
}

// ── lightbox ──────────────────────────────────────────────────────────────────

function openLightbox(idx) {
  if (idx < 0 || idx >= allScreenshots.length) return;
  lightboxIndex = idx;

  const lb = document.getElementById('ss-lightbox');
  if (!lb) return;
  lb.classList.remove('hidden');
  updateLightboxContent();
  document.getElementById('ss-lb-close')?.focus();
}

function closeLightbox() {
  const lb = document.getElementById('ss-lightbox');
  if (lb) lb.classList.add('hidden');
}

function navigateLightbox(dir) {
  const next = lightboxIndex + dir;
  if (next < 0 || next >= allScreenshots.length) return;
  lightboxIndex = next;
  updateLightboxContent();
}

function updateLightboxContent() {
  const ss = allScreenshots[lightboxIndex];
  if (!ss) { closeLightbox(); return; }

  const img     = document.getElementById('ss-lb-img');
  const spinner = document.getElementById('ss-lb-spinner');
  const name    = document.getElementById('ss-lb-name');
  const meta    = document.getElementById('ss-lb-meta');
  const counter = document.getElementById('ss-lb-counter');
  const prev    = document.getElementById('ss-lb-prev');
  const next    = document.getElementById('ss-lb-next');

  if (img) {
    // Show spinner while new image loads.
    if (spinner) spinner.style.display = 'block';
    img.style.opacity = '0';
    img.alt = ss.name;
    img.onload  = () => {
      img.style.opacity = '1';
      if (spinner) spinner.style.display = 'none';
    };
    img.onerror = () => {
      img.style.opacity = '0.3';
      if (spinner) spinner.style.display = 'none';
    };
    img.src = ss.fileUrl;
  }
  if (name)    name.textContent    = ss.name;
  if (meta)    meta.textContent    = `${new Date(ss.mtimeMs).toLocaleString()} · ${fmtBytes(ss.size || 0)}`;
  if (counter) counter.textContent = `${lightboxIndex + 1} / ${allScreenshots.length}`;
  if (prev)    prev.disabled       = lightboxIndex === 0;
  if (next)    next.disabled       = lightboxIndex === allScreenshots.length - 1;
}

async function confirmAndDelete(idx, fromLightbox) {
  const ss = allScreenshots[idx];
  if (!ss) return;
  if (!confirm(`Delete "${ss.name}"?\nThis cannot be undone.`)) return;

  const r = await window.fox.deleteScreenshot(ss.path).catch(() => ({ ok: false }));
  if (!r || !r.ok) {
    alert(`Failed to delete: ${r && r.error ? r.error : 'unknown error'}`);
    return;
  }

  allScreenshots.splice(idx, 1);

  // Update info bar count.
  const info  = document.getElementById('ss-info');
  const count = allScreenshots.length;
  if (info && count === 0) {
    info.textContent = 'No screenshots yet — press F2 in-game to take one.';
  } else if (info) {
    const total = allScreenshots.reduce((s, ss) => s + (ss.size || 0), 0);
    info.textContent =
      `${count} screenshot${count !== 1 ? 's' : ''} · ${fmtBytes(total)}`;
  }

  if (fromLightbox) {
    if (allScreenshots.length === 0) {
      closeLightbox();
    } else {
      lightboxIndex = Math.min(lightboxIndex, allScreenshots.length - 1);
      updateLightboxContent();
    }
  }

  renderGallery();
}

// ── controls wiring ───────────────────────────────────────────────────────────

function wireControls() {
  // Profile selector.
  const profileSel = document.getElementById('ss-profile-select');
  if (profileSel) {
    profileSel.addEventListener('change', async () => {
      currentProfileId = profileSel.value;
      await loadGallery();
    });
  }

  // Search / filter (debounced 150 ms).
  const searchInput = document.getElementById('ss-search');
  if (searchInput) {
    let t;
    searchInput.addEventListener('input', () => {
      clearTimeout(t);
      t = setTimeout(() => renderGallery(), 150);
    });
    // ESC clears the search.
    searchInput.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') {
        if (searchInput.value) { e.stopPropagation(); searchInput.value = ''; renderGallery(); }
      }
    });
  }

  // Open folder button.
  document.getElementById('ss-open-folder')?.addEventListener('click', () => {
    window.fox.openScreenshotsFolder(currentProfileId);
  });

  // Refresh button.
  document.getElementById('ss-refresh')?.addEventListener('click', () => loadGallery());

  // Lightbox: close + backdrop.
  document.getElementById('ss-lb-close')?.addEventListener('click', closeLightbox);
  document.getElementById('ss-lb-backdrop')?.addEventListener('click', closeLightbox);

  // Lightbox: navigation.
  document.getElementById('ss-lb-prev')?.addEventListener('click', () => navigateLightbox(-1));
  document.getElementById('ss-lb-next')?.addEventListener('click', () => navigateLightbox(1));

  // Lightbox: reveal / delete.
  document.getElementById('ss-lb-reveal')?.addEventListener('click', () => {
    const ss = allScreenshots[lightboxIndex];
    if (ss) window.fox.revealScreenshot(ss.path);
  });
  document.getElementById('ss-lb-delete')?.addEventListener('click', () => {
    confirmAndDelete(lightboxIndex, /* fromLightbox */ true);
  });

  // Global keyboard: lightbox shortcuts.
  const onKey = (e) => {
    const lb = document.getElementById('ss-lightbox');
    if (!lb || lb.classList.contains('hidden')) return;
    if (e.key === 'Escape')      { e.preventDefault(); closeLightbox(); }
    else if (e.key === 'ArrowLeft')  { e.preventDefault(); navigateLightbox(-1); }
    else if (e.key === 'ArrowRight') { e.preventDefault(); navigateLightbox(1); }
    else if (e.key === 'Delete')     { e.preventDefault(); confirmAndDelete(lightboxIndex, true); }
  };
  document.addEventListener('keydown', onKey);
  activeUnsubs.push(() => document.removeEventListener('keydown', onKey));
}

// ── helpers ───────────────────────────────────────────────────────────────────

function esc(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function fmtBytes(bytes) {
  if (bytes < 1024)          return `${bytes} B`;
  if (bytes < 1024 * 1024)   return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
