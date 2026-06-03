// Pixel-art skin editor — 64×64 backing canvas, zoomed view, tools, undo,
// UV overlay, and one-click "apply as my Minecraft skin".
//
// Architecture
//   • One 64×64 HTMLCanvasElement (`canvas`/`ctx`) holds the true pixels.
//   • A larger zoomed canvas (`view`/`vctx`) is what the user sees. Every
//     mutation calls `redrawAll()` to copy the backing to the view (nearest-
//     neighbour) and re-draws the UV overlay on top.
//   • Undo/redo are ImageData snapshots stacked in `undoStack`/`redoStack`.
//   • All Minecraft-protocol calls (fetch current skin / upload edited skin)
//     go through the main process IPC — the renderer never touches Mojang.

// ── constants ──────────────────────────────────────────────────────────────
const W = 64, H = 64;
const ZOOM = 10;
const UNDO_MAX = 50;

// Standard 64×64 skin layout — the *front* face of each body region. Drawn as
// outlines when "UV overlay" is on so it's obvious which 8×8 block paints
// which body part. Keeping it to fronts only avoids cluttering the canvas.
const UV_FACES = [
  { x: 8,  y: 8,  w: 8,  h: 8,  label: 'head' },
  { x: 40, y: 8,  w: 8,  h: 8,  label: 'hat' },
  { x: 20, y: 20, w: 8,  h: 12, label: 'body' },
  { x: 20, y: 36, w: 8,  h: 12, label: 'jacket' },
  { x: 44, y: 20, w: 4,  h: 12, label: 'R-arm' },
  { x: 44, y: 36, w: 4,  h: 12, label: 'R-sleeve' },
  { x: 36, y: 52, w: 4,  h: 12, label: 'L-arm' },
  { x: 52, y: 52, w: 4,  h: 12, label: 'L-sleeve' },
  { x: 4,  y: 20, w: 4,  h: 12, label: 'R-leg' },
  { x: 4,  y: 36, w: 4,  h: 12, label: 'R-pant' },
  { x: 20, y: 52, w: 4,  h: 12, label: 'L-leg' },
  { x: 4,  y: 52, w: 4,  h: 12, label: 'L-pant' },
];

const PALETTE_DEFAULTS = [
  '#000000', '#FFFFFF', '#FF8C42', '#E05A5A',
  '#4EC27A', '#5B8CFF', '#D97AED', '#F5B942',
];

// ── module state (re-initialised by renderSkinEditor) ──────────────────────
let canvas, ctx;          // 64×64 truth
let view,   vctx;         // zoomed view
let preview, pctx;        // 1× preview
let mountEl;

let activeTool   = 'pencil';
let activeColor  = '#FF8C42';
let brushSize    = 1;
let variant      = 'classic';
let overlayOn    = false;
let drawing      = false;
let lastPx       = null;  // for line interpolation between mousemove events
let recentColors = [];
const undoStack  = [];
const redoStack  = [];

// ── entry point ────────────────────────────────────────────────────────────
export async function renderSkinEditor(mount) {
  mountEl = mount;
  // Reset undo/redo so swapping tabs doesn't carry stale state across screens.
  undoStack.length = 0;
  redoStack.length = 0;
  drawing = false;
  lastPx = null;

  mount.innerHTML = `
    <div class="sk-toolbar">
      <div class="sk-group" role="toolbar" aria-label="Drawing tools">
        <button class="sk-btn sk-tool" data-tool="pencil"  title="Pencil (B)">✎</button>
        <button class="sk-btn sk-tool" data-tool="eraser"  title="Eraser (E)">⌫</button>
        <button class="sk-btn sk-tool" data-tool="eyedrop" title="Eyedropper (I)">⊕</button>
        <button class="sk-btn sk-tool" data-tool="fill"    title="Fill (G)">⬢</button>
      </div>
      <div class="sk-group">
        <label class="sk-color-wrap" title="Click to pick a color">
          <input type="color" id="sk-color" value="${activeColor}" />
          <span class="sk-hex" id="sk-hex">${activeColor.toUpperCase()}</span>
        </label>
        <div class="sk-palette" id="sk-palette"></div>
      </div>
      <div class="sk-group">
        <label class="sk-toggle"><span>Brush</span>
          <select id="sk-brush">
            <option value="1">1 px</option>
            <option value="2">2 px</option>
            <option value="3">3 px</option>
          </select>
        </label>
      </div>
      <div class="sk-group">
        <button class="sk-btn" id="sk-undo" title="Undo (Ctrl+Z)">↶</button>
        <button class="sk-btn" id="sk-redo" title="Redo (Ctrl+Shift+Z)">↷</button>
      </div>
      <div class="sk-group">
        <label class="sk-toggle">
          <input type="checkbox" id="sk-overlay" ${overlayOn ? 'checked' : ''} />
          <span>UV overlay</span>
        </label>
      </div>
      <div class="sk-group sk-group-right">
        <button class="sk-btn" id="sk-load"  title="Download your current Minecraft skin into the editor">Load current</button>
        <button class="sk-btn" id="sk-open"  title="Open a PNG from disk">Open PNG…</button>
        <button class="sk-btn" id="sk-clear" title="Wipe to transparent">Clear</button>
        <select id="sk-variant" title="Body model the upload will use">
          <option value="classic" ${variant === 'classic' ? 'selected' : ''}>Classic (Steve)</option>
          <option value="slim"    ${variant === 'slim'    ? 'selected' : ''}>Slim (Alex)</option>
        </select>
        <button class="sk-btn" id="sk-save">Save PNG</button>
        <button class="sk-btn btn-primary" id="sk-apply" title="Upload as your Minecraft skin">Apply as my skin</button>
      </div>
    </div>

    <div class="sk-stage">
      <div class="sk-canvas-wrap">
        <canvas id="sk-view" width="${W * ZOOM}" height="${H * ZOOM}"
                class="sk-canvas" aria-label="Skin pixel canvas"></canvas>
      </div>
      <aside class="sk-side">
        <div class="sk-side-label">Preview (1×)</div>
        <canvas id="sk-preview" width="${W}" height="${H}" class="sk-preview"></canvas>
        <div class="sk-side-label">Tip</div>
        <div class="sk-hint muted" id="sk-hint">Pencil — click or drag to paint.</div>
        <div class="sk-status" id="sk-status"></div>
      </aside>
    </div>
    <input type="file" id="sk-file" accept="image/png" hidden />
  `;

  // ── canvas refs ──
  canvas = document.createElement('canvas');
  canvas.width = W; canvas.height = H;
  ctx = canvas.getContext('2d', { willReadFrequently: true });
  ctx.imageSmoothingEnabled = false;

  view = mount.querySelector('#sk-view');
  vctx = view.getContext('2d');
  vctx.imageSmoothingEnabled = false;

  preview = mount.querySelector('#sk-preview');
  pctx = preview.getContext('2d');
  pctx.imageSmoothingEnabled = false;

  renderPalette();
  wireToolButtons();
  wireColor();
  wireBrush();
  wireUndoRedo();
  wireOverlay();
  wireVariant();
  wireFileActions();
  wireCanvasInput();
  wireKeyboard();

  setActiveTool('pencil');
  redrawAll();
}

// ── tool selection ────────────────────────────────────────────────────────
function setActiveTool(tool) {
  activeTool = tool;
  for (const b of mountEl.querySelectorAll('.sk-tool')) {
    b.classList.toggle('active', b.dataset.tool === tool);
  }
  const hint = mountEl.querySelector('#sk-hint');
  hint.textContent = ({
    pencil:  'Pencil — click or drag to paint pixels in the active color.',
    eraser:  'Eraser — click or drag to clear pixels (alpha 0).',
    eyedrop: 'Eyedropper — click any pixel to copy its color.',
    fill:    'Fill — click a pixel to flood-fill the connected region.',
  })[tool] || '';
  view.style.cursor = tool === 'eyedrop' ? 'copy' : tool === 'fill' ? 'pointer' : 'crosshair';
}

function wireToolButtons() {
  for (const b of mountEl.querySelectorAll('.sk-tool')) {
    b.addEventListener('click', () => setActiveTool(b.dataset.tool));
  }
}

// ── color picker + palette ────────────────────────────────────────────────
function renderPalette() {
  const p = mountEl.querySelector('#sk-palette');
  const all = [...recentColors, ...PALETTE_DEFAULTS.filter(c => !recentColors.includes(c))].slice(0, 8);
  p.innerHTML = all.map(c => `<button class="sk-swatch" style="background:${esc(c)};" data-color="${esc(c)}" title="${esc(c)}"></button>`).join('');
  for (const s of p.querySelectorAll('.sk-swatch')) {
    s.addEventListener('click', () => setActiveColor(s.dataset.color));
  }
}

function setActiveColor(hex) {
  activeColor = hex.toUpperCase();
  mountEl.querySelector('#sk-color').value = activeColor;
  mountEl.querySelector('#sk-hex').textContent = activeColor;
  // Move to head of recents (cap 8, dedupe).
  recentColors = [activeColor, ...recentColors.filter(c => c !== activeColor)].slice(0, 8);
  renderPalette();
}

function wireColor() {
  const inp = mountEl.querySelector('#sk-color');
  inp.addEventListener('input', () => setActiveColor(inp.value));
}

function wireBrush() {
  mountEl.querySelector('#sk-brush').addEventListener('change', (e) => {
    brushSize = Math.max(1, Math.min(3, parseInt(e.target.value, 10) || 1));
  });
}

function wireOverlay() {
  mountEl.querySelector('#sk-overlay').addEventListener('change', (e) => {
    overlayOn = e.target.checked;
    redrawAll();
  });
}

function wireVariant() {
  mountEl.querySelector('#sk-variant').addEventListener('change', (e) => {
    variant = e.target.value === 'slim' ? 'slim' : 'classic';
  });
}

// ── undo / redo ───────────────────────────────────────────────────────────
function snapshot() {
  try { return ctx.getImageData(0, 0, W, H); } catch (_) { return null; }
}
function pushUndo() {
  const s = snapshot();
  if (!s) return;
  undoStack.push(s);
  if (undoStack.length > UNDO_MAX) undoStack.shift();
  redoStack.length = 0;
}
function undo() {
  const s = undoStack.pop();
  if (!s) return;
  const cur = snapshot();
  if (cur) redoStack.push(cur);
  ctx.putImageData(s, 0, 0);
  redrawAll();
}
function redo() {
  const s = redoStack.pop();
  if (!s) return;
  const cur = snapshot();
  if (cur) undoStack.push(cur);
  ctx.putImageData(s, 0, 0);
  redrawAll();
}
function wireUndoRedo() {
  mountEl.querySelector('#sk-undo').addEventListener('click', undo);
  mountEl.querySelector('#sk-redo').addEventListener('click', redo);
}

// ── canvas input (paint / pick / fill) ────────────────────────────────────
function pxAt(evt) {
  const rect = view.getBoundingClientRect();
  const x = Math.floor((evt.clientX - rect.left) / (rect.width  / W));
  const y = Math.floor((evt.clientY - rect.top)  / (rect.height / H));
  return (x >= 0 && x < W && y >= 0 && y < H) ? { x, y } : null;
}

function paintAt(p, mode) {
  if (!p) return;
  const r = Math.max(0, brushSize - 1);
  const x0 = Math.max(0, p.x - Math.floor(r / 2));
  const y0 = Math.max(0, p.y - Math.floor(r / 2));
  const x1 = Math.min(W, p.x + Math.ceil(brushSize / 2));
  const y1 = Math.min(H, p.y + Math.ceil(brushSize / 2));
  if (mode === 'erase') ctx.clearRect(x0, y0, x1 - x0, y1 - y0);
  else { ctx.fillStyle = activeColor; ctx.fillRect(x0, y0, x1 - x0, y1 - y0); }
}

// Bresenham-style interpolation so a fast drag doesn't leave gaps.
function paintLine(a, b, mode) {
  if (!a) { paintAt(b, mode); return; }
  let { x: x0, y: y0 } = a;
  const { x: x1, y: y1 } = b;
  const dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0);
  const sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
  let err = dx + dy;
  for (;;) {
    paintAt({ x: x0, y: y0 }, mode);
    if (x0 === x1 && y0 === y1) break;
    const e2 = 2 * err;
    if (e2 >= dy) { err += dy; x0 += sx; }
    if (e2 <= dx) { err += dx; y0 += sy; }
  }
}

function pickAt(p) {
  if (!p) return;
  const d = ctx.getImageData(p.x, p.y, 1, 1).data;
  if (d[3] === 0) return;       // transparent — nothing to pick
  const hex = '#' + [d[0], d[1], d[2]].map(n => n.toString(16).padStart(2, '0')).join('').toUpperCase();
  setActiveColor(hex);
}

function floodFill(p) {
  if (!p) return;
  const img = ctx.getImageData(0, 0, W, H);
  const d = img.data;
  const i0 = (p.y * W + p.x) * 4;
  const target = [d[i0], d[i0 + 1], d[i0 + 2], d[i0 + 3]];
  // Parse activeColor → RGBA bytes.
  const m = activeColor.replace('#', '');
  const fill = [parseInt(m.slice(0, 2), 16), parseInt(m.slice(2, 4), 16), parseInt(m.slice(4, 6), 16), 255];
  if (target[0] === fill[0] && target[1] === fill[1] && target[2] === fill[2] && target[3] === fill[3]) return;
  const eq = (i) => d[i] === target[0] && d[i + 1] === target[1] && d[i + 2] === target[2] && d[i + 3] === target[3];
  const stack = [p];
  while (stack.length) {
    const { x, y } = stack.pop();
    if (x < 0 || y < 0 || x >= W || y >= H) continue;
    const i = (y * W + x) * 4;
    if (!eq(i)) continue;
    d[i] = fill[0]; d[i + 1] = fill[1]; d[i + 2] = fill[2]; d[i + 3] = fill[3];
    stack.push({ x: x + 1, y }, { x: x - 1, y }, { x, y: y + 1 }, { x, y: y - 1 });
  }
  ctx.putImageData(img, 0, 0);
}

function wireCanvasInput() {
  const begin = (e) => {
    const p = pxAt(e);
    if (!p) return;
    pushUndo();
    drawing = true;
    lastPx = p;
    if (activeTool === 'pencil')      paintAt(p, 'paint');
    else if (activeTool === 'eraser') paintAt(p, 'erase');
    else if (activeTool === 'eyedrop') { pickAt(p); drawing = false; }
    else if (activeTool === 'fill')    { floodFill(p); drawing = false; }
    redrawAll();
  };
  const move = (e) => {
    if (!drawing) return;
    const p = pxAt(e);
    if (!p) return;
    paintLine(lastPx, p, activeTool === 'eraser' ? 'erase' : 'paint');
    lastPx = p;
    redrawAll();
  };
  const end = () => { drawing = false; lastPx = null; };
  view.addEventListener('mousedown', begin);
  view.addEventListener('mousemove', move);
  window.addEventListener('mouseup', end);
  view.addEventListener('mouseleave', () => { lastPx = null; });
}

// ── keyboard shortcuts ────────────────────────────────────────────────────
function wireKeyboard() {
  const onKey = (e) => {
    if (e.target && (e.target.tagName === 'INPUT' || e.target.tagName === 'SELECT' || e.target.tagName === 'TEXTAREA')) return;
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z') {
      e.preventDefault();
      if (e.shiftKey) redo(); else undo();
      return;
    }
    switch (e.key.toLowerCase()) {
      case 'b': setActiveTool('pencil');  break;
      case 'e': setActiveTool('eraser');  break;
      case 'i': setActiveTool('eyedrop'); break;
      case 'g': setActiveTool('fill');    break;
    }
  };
  document.addEventListener('keydown', onKey);
  mountEl.addEventListener('fox:screen-unmount', () => document.removeEventListener('keydown', onKey), { once: true });
}

// ── file actions: open / save / clear / load current / apply ──────────────
function setStatus(msg, kind) {
  const el = mountEl.querySelector('#sk-status');
  if (!el) return;
  el.textContent = msg || '';
  el.className = 'sk-status ' + (kind === 'ok' ? 'sk-status-ok' : kind === 'err' ? 'sk-status-err' : 'muted');
}

function drawIntoBacking(img) {
  ctx.clearRect(0, 0, W, H);
  // 64×64 (modern) → draw as-is. 64×32 (legacy) → top half only.
  if (img.naturalWidth === W && img.naturalHeight === H) {
    ctx.drawImage(img, 0, 0);
  } else if (img.naturalWidth === W && img.naturalHeight === H / 2) {
    ctx.drawImage(img, 0, 0);  // legacy top-half body parts stay in place
  } else {
    // Unknown layout — scale to fit (best effort).
    ctx.drawImage(img, 0, 0, W, H);
  }
  redrawAll();
}

function wireFileActions() {
  const fileInput = mountEl.querySelector('#sk-file');
  mountEl.querySelector('#sk-open').addEventListener('click', () => fileInput.click());
  fileInput.addEventListener('change', () => {
    const f = fileInput.files && fileInput.files[0];
    if (!f) return;
    const r = new FileReader();
    r.onload = () => {
      const img = new Image();
      img.onload  = () => { pushUndo(); drawIntoBacking(img); setStatus(`Loaded ${f.name} (${img.naturalWidth}×${img.naturalHeight})`, 'ok'); };
      img.onerror = () => setStatus('Could not decode PNG', 'err');
      img.src = r.result;
    };
    r.onerror = () => setStatus('Could not read file', 'err');
    r.readAsDataURL(f);
    fileInput.value = '';
  });

  mountEl.querySelector('#sk-save').addEventListener('click', () => {
    canvas.toBlob((blob) => {
      if (!blob) { setStatus('Save failed (canvas tainted?)', 'err'); return; }
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = 'fox-skin.png';
      document.body.appendChild(a); a.click(); a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1500);
      setStatus('Saved fox-skin.png', 'ok');
    }, 'image/png');
  });

  mountEl.querySelector('#sk-clear').addEventListener('click', () => {
    pushUndo();
    ctx.clearRect(0, 0, W, H);
    redrawAll();
    setStatus('Cleared canvas');
  });

  mountEl.querySelector('#sk-load').addEventListener('click', async () => {
    setStatus('Fetching your current skin…');
    try {
      const r = await window.fox.fetchSkinPng();
      if (!r || !r.ok) { setStatus(r && r.error ? r.error : 'Load failed', 'err'); return; }
      const img = new Image();
      img.onload  = () => {
        pushUndo();
        drawIntoBacking(img);
        if (r.variant === 'slim' || r.variant === 'classic') {
          variant = r.variant;
          mountEl.querySelector('#sk-variant').value = variant;
        }
        setStatus(`Loaded current skin (${r.variant}).`, 'ok');
      };
      img.onerror = () => setStatus('Could not decode current skin', 'err');
      img.src = 'data:image/png;base64,' + r.base64;
    } catch (err) {
      setStatus(err.message || 'Load failed', 'err');
    }
  });

  mountEl.querySelector('#sk-apply').addEventListener('click', async () => {
    if (!confirm(`Upload this canvas as your Minecraft skin (${variant})?`)) return;
    const btn = mountEl.querySelector('#sk-apply');
    btn.disabled = true; const orig = btn.textContent; btn.textContent = 'Uploading…';
    setStatus('Uploading skin…');
    try {
      const dataUrl = canvas.toDataURL('image/png');
      const base64 = dataUrl.replace(/^data:image\/png;base64,/, '');
      const r = await window.fox.uploadSkinBytes({ base64, variant });
      if (r && r.ok) setStatus('Applied! It may take a moment to appear in-game.', 'ok');
      else           setStatus((r && r.error) || 'Upload failed', 'err');
    } catch (err) {
      setStatus(err.message || 'Upload failed', 'err');
    } finally {
      btn.disabled = false; btn.textContent = orig;
    }
  });
}

// ── render pipeline ───────────────────────────────────────────────────────
function redrawAll() {
  // Step 1: tiled transparency checkerboard (so users can see alpha 0 vs black).
  drawCheckerboard(vctx, view.width, view.height);
  // Step 2: zoomed-up backing canvas.
  vctx.imageSmoothingEnabled = false;
  vctx.drawImage(canvas, 0, 0, W, H, 0, 0, view.width, view.height);
  // Step 3: optional UV overlay.
  if (overlayOn) drawUvOverlay(vctx);

  // 1× preview.
  pctx.clearRect(0, 0, W, H);
  pctx.drawImage(canvas, 0, 0);
}

function drawCheckerboard(c, w, h) {
  const tile = ZOOM;   // one tile = one logical pixel of the skin
  c.fillStyle = '#23232b';
  c.fillRect(0, 0, w, h);
  c.fillStyle = '#2c2c36';
  for (let y = 0; y < h; y += tile) {
    for (let x = ((y / tile) % 2) * tile; x < w; x += tile * 2) {
      c.fillRect(x, y, tile, tile);
    }
  }
}

function drawUvOverlay(c) {
  c.save();
  c.lineWidth = 1;
  c.font = '10px ui-sans-serif, system-ui';
  c.textBaseline = 'top';
  for (const f of UV_FACES) {
    const x = f.x * ZOOM + 0.5, y = f.y * ZOOM + 0.5;
    const w = f.w * ZOOM - 1,   h = f.h * ZOOM - 1;
    c.strokeStyle = 'rgba(255, 140, 66, 0.85)';
    c.strokeRect(x, y, w, h);
    c.fillStyle = 'rgba(0, 0, 0, 0.55)';
    const text = f.label;
    const tw = c.measureText(text).width + 4;
    c.fillRect(x + 2, y + 2, tw, 12);
    c.fillStyle = '#fff';
    c.fillText(text, x + 4, y + 3);
  }
  c.restore();
}

// ── helpers ───────────────────────────────────────────────────────────────
function esc(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}
