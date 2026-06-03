// 3D pixel-art skin editor — paint directly on a rotatable player model.
//
// Architecture
//   • A hidden 64×64 HTMLCanvasElement (`tex`) is the source of truth.
//   • THREE.CanvasTexture wraps it; every body-part mesh samples from it with
//     hand-set UVs that match Minecraft's standard 64×64 layout.
//   • Left-click on the 3D view → Raycaster picks a face → hit.uv is the
//     normalised UV at the click → convert to pixel coords → paint with the
//     active tool, mark the CanvasTexture as needing GPU upload.
//   • Right-drag orbits the camera (yaw / pitch). Wheel zooms.
//   • Outer layer (hat / jacket / sleeves / pants) toggle, Classic vs Slim
//     arms, transparent-pixel checkerboard, mini 2D texture preview in the
//     sidebar so the user can also paint there for fine work.
//   • The Mojang-skin protocol calls (Load current / Apply as my skin) go
//     through the existing main-process IPC. No token ever reaches the renderer.

import * as THREE from '../vendor/three.module.js';

// ── constants ──────────────────────────────────────────────────────────────
const W = 64, H = 64;
const UNDO_MAX = 60;

// Minecraft 64×64 UV layout. Each face is [x, y, w, h] in texture pixels.
// Modern 1.8+ skin: outer "layer 2" boxes (hat, jacket, sleeves, pants) get
// rendered slightly larger over the inner ones.
const UV = {
  head:    { right: [0, 8, 8, 8],   front: [8, 8, 8, 8],   left: [16, 8, 8, 8],   back: [24, 8, 8, 8],   top: [8, 0, 8, 8],   bottom: [16, 0, 8, 8] },
  hat:     { right: [32, 8, 8, 8],  front: [40, 8, 8, 8],  left: [48, 8, 8, 8],   back: [56, 8, 8, 8],   top: [40, 0, 8, 8],  bottom: [48, 0, 8, 8] },
  body:    { right: [16, 20, 4, 12],front: [20, 20, 8, 12],left: [28, 20, 4, 12], back: [32, 20, 8, 12], top: [20, 16, 8, 4], bottom: [28, 16, 8, 4] },
  jacket:  { right: [16, 36, 4, 12],front: [20, 36, 8, 12],left: [28, 36, 4, 12], back: [32, 36, 8, 12], top: [20, 32, 8, 4], bottom: [28, 32, 8, 4] },
  rarm:    { right: [40, 20, 4, 12],front: [44, 20, 4, 12],left: [48, 20, 4, 12], back: [52, 20, 4, 12], top: [44, 16, 4, 4], bottom: [48, 16, 4, 4] },
  rsleeve: { right: [40, 36, 4, 12],front: [44, 36, 4, 12],left: [48, 36, 4, 12], back: [52, 36, 4, 12], top: [44, 32, 4, 4], bottom: [48, 32, 4, 4] },
  larm:    { right: [32, 52, 4, 12],front: [36, 52, 4, 12],left: [40, 52, 4, 12], back: [44, 52, 4, 12], top: [36, 48, 4, 4], bottom: [40, 48, 4, 4] },
  lsleeve: { right: [48, 52, 4, 12],front: [52, 52, 4, 12],left: [56, 52, 4, 12], back: [60, 52, 4, 12], top: [52, 48, 4, 4], bottom: [56, 48, 4, 4] },
  rleg:    { right: [0, 20, 4, 12], front: [4, 20, 4, 12], left: [8, 20, 4, 12],  back: [12, 20, 4, 12], top: [4, 16, 4, 4],  bottom: [8, 16, 4, 4] },
  rpants:  { right: [0, 36, 4, 12], front: [4, 36, 4, 12], left: [8, 36, 4, 12],  back: [12, 36, 4, 12], top: [4, 32, 4, 4],  bottom: [8, 32, 4, 4] },
  lleg:    { right: [16, 52, 4, 12],front: [20, 52, 4, 12],left: [24, 52, 4, 12], back: [28, 52, 4, 12], top: [20, 48, 4, 4], bottom: [24, 48, 4, 4] },
  lpants:  { right: [0, 52, 4, 12], front: [4, 52, 4, 12], left: [8, 52, 4, 12],  back: [12, 52, 4, 12], top: [4, 48, 4, 4],  bottom: [8, 48, 4, 4] },
};

// Slim variant — 3-wide arms instead of 4. Front/back faces are narrower.
const UV_SLIM = {
  rarm:    { right: [40, 20, 4, 12],front: [44, 20, 3, 12],left: [47, 20, 4, 12], back: [51, 20, 3, 12], top: [44, 16, 3, 4], bottom: [47, 16, 3, 4] },
  rsleeve: { right: [40, 36, 4, 12],front: [44, 36, 3, 12],left: [47, 36, 4, 12], back: [51, 36, 3, 12], top: [44, 32, 3, 4], bottom: [47, 32, 3, 4] },
  larm:    { right: [32, 52, 4, 12],front: [36, 52, 3, 12],left: [39, 52, 4, 12], back: [43, 52, 3, 12], top: [36, 48, 3, 4], bottom: [39, 48, 3, 4] },
  lsleeve: { right: [48, 52, 4, 12],front: [52, 52, 3, 12],left: [55, 52, 4, 12], back: [59, 52, 3, 12], top: [52, 48, 3, 4], bottom: [55, 48, 3, 4] },
};

// Player parts. Each: inner box size + position, outer "layer 2" UVs, and a
// scale factor for the outer box (1.125 = +1px each side for jacket-like).
const PARTS_CLASSIC = [
  { key: 'head', size: [8, 8, 8],   pos: [0, 28, 0],   inner: 'head',   outer: 'hat',     outerScale: 1.125 },
  { key: 'body', size: [8, 12, 4],  pos: [0, 18, 0],   inner: 'body',   outer: 'jacket',  outerScale: 1.125 },
  { key: 'rarm', size: [4, 12, 4],  pos: [-6, 18, 0],  inner: 'rarm',   outer: 'rsleeve', outerScale: 1.125 },
  { key: 'larm', size: [4, 12, 4],  pos: [6, 18, 0],   inner: 'larm',   outer: 'lsleeve', outerScale: 1.125 },
  { key: 'rleg', size: [4, 12, 4],  pos: [-2, 6, 0],   inner: 'rleg',   outer: 'rpants',  outerScale: 1.125 },
  { key: 'lleg', size: [4, 12, 4],  pos: [2, 6, 0],    inner: 'lleg',   outer: 'lpants',  outerScale: 1.125 },
];
const PARTS_SLIM = PARTS_CLASSIC.map(p => p.key === 'rarm' || p.key === 'larm'
  ? { ...p, size: [3, 12, 4] }
  : p);

const PALETTE_DEFAULTS = [
  '#000000', '#FFFFFF', '#FF8C42', '#E05A5A',
  '#4EC27A', '#5B8CFF', '#D97AED', '#F5B942',
];

// ── module state (re-initialised by renderSkinEditor) ──────────────────────
let mountEl;
let tex, ctx2d;                 // 64×64 backing canvas + 2d context
let renderer, scene, camera, root, three;
let canvasTexture;              // THREE.CanvasTexture wrapping `tex`
let raycaster, mouseNDC;
let parts = [];                 // [{ key, group, innerMesh, outerMesh }]
let pickables = [];             // meshes we raycast against
let needsRender = false;        // requestAnimationFrame batching

let activeTool   = 'pencil';
let activeColor  = '#FF8C42';
let brushSize    = 1;
let variant      = 'classic';
let outerVisible = true;
let recentColors = [];
const undoStack  = [];
const redoStack  = [];

// Mouse interaction state
let mouseDown = 0;              // 0 = none, 1 = paint stroke, 2 = orbit
let painting = false;
let lastPx = null;
let camYaw = 0.55, camPitch = -0.15, camDist = 80;
let orbitStart = null;

// ── entry point ────────────────────────────────────────────────────────────
export async function renderSkinEditor(mount) {
  mountEl = mount;
  undoStack.length = 0; redoStack.length = 0;
  mouseDown = 0; painting = false; lastPx = null;

  mount.innerHTML = `
    <div class="sk-toolbar">
      <div class="sk-group" role="toolbar" aria-label="Drawing tools">
        <button class="sk-btn sk-tool" data-tool="pencil"  title="Pencil (B)">✎</button>
        <button class="sk-btn sk-tool" data-tool="eraser"  title="Eraser (E)">⌫</button>
        <button class="sk-btn sk-tool" data-tool="eyedrop" title="Eyedropper (I)">⊕</button>
        <button class="sk-btn sk-tool" data-tool="fill"    title="Fill (G)">⬢</button>
      </div>
      <div class="sk-group">
        <label class="sk-color-wrap" title="Pick color">
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
          <input type="checkbox" id="sk-outer" checked />
          <span>Outer layer</span>
        </label>
        <select id="sk-variant" title="Body model the upload will use">
          <option value="classic">Classic (Steve)</option>
          <option value="slim">Slim (Alex)</option>
        </select>
      </div>
      <div class="sk-group sk-group-right">
        <button class="sk-btn" id="sk-load"  title="Download your current Minecraft skin">Load current</button>
        <button class="sk-btn" id="sk-open"  title="Open a PNG from disk">Open PNG…</button>
        <button class="sk-btn" id="sk-clear" title="Wipe to transparent">Clear</button>
        <button class="sk-btn" id="sk-save">Save PNG</button>
        <button class="sk-btn btn-primary" id="sk-apply" title="Upload as your Minecraft skin">Apply as my skin</button>
      </div>
    </div>

    <div class="sk-stage sk-stage-3d">
      <div class="sk-viewport" id="sk-viewport">
        <canvas id="sk-3d" class="sk-3d-canvas" aria-label="3D player model"></canvas>
        <div class="sk-viewport-hint muted">Left-click to paint · Right-drag to orbit · Wheel to zoom</div>
      </div>
      <aside class="sk-side">
        <div class="sk-side-label">Texture (1×)</div>
        <canvas id="sk-preview" width="${W}" height="${H}" class="sk-preview" title="Click to paint pixels directly"></canvas>
        <div class="sk-side-label">Tip</div>
        <div class="sk-hint muted" id="sk-hint">Pencil — click any face of the model to paint.</div>
        <div class="sk-status" id="sk-status"></div>
      </aside>
    </div>
    <input type="file" id="sk-file" accept="image/png" hidden />
  `;

  // ── canvases ──
  tex = document.createElement('canvas');
  tex.width = W; tex.height = H;
  ctx2d = tex.getContext('2d', { willReadFrequently: true });
  ctx2d.imageSmoothingEnabled = false;

  // ── three.js setup ──
  const view3d = mount.querySelector('#sk-3d');
  setupThree(view3d);
  buildModel();

  // ── 2D mini preview also clickable ──
  setupMiniPreview();

  renderPalette();
  wireToolButtons();
  wireColor();
  wireBrush();
  wireUndoRedo();
  wireOuterToggle();
  wireVariant();
  wireFileActions();
  wireCanvas3DInput();
  wireKeyboard();

  setActiveTool('pencil');
  requestRender();
  observeResize(view3d);
}

// ── three.js scene ────────────────────────────────────────────────────────
function setupThree(canvasEl) {
  three = THREE;
  renderer = new THREE.WebGLRenderer({ canvas: canvasEl, antialias: true, alpha: true });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
  renderer.setClearColor(0x1a1a22, 1);
  resizeRenderer();

  scene = new THREE.Scene();
  camera = new THREE.PerspectiveCamera(35, 1, 0.1, 500);
  updateCameraFromOrbit();

  // Soft directional + ambient — enough to read the texture under shading.
  const amb = new THREE.AmbientLight(0xffffff, 0.7);
  const dir = new THREE.DirectionalLight(0xffffff, 0.6);
  dir.position.set(40, 60, 30);
  scene.add(amb, dir);

  root = new THREE.Group();
  scene.add(root);

  canvasTexture = new THREE.CanvasTexture(tex);
  canvasTexture.magFilter = THREE.NearestFilter;
  canvasTexture.minFilter = THREE.NearestFilter;
  canvasTexture.flipY = true;       // matches our pixel-coord conversion below
  canvasTexture.generateMipmaps = false;

  raycaster = new THREE.Raycaster();
  mouseNDC = new THREE.Vector2();
}

function resizeRenderer() {
  const canvas = renderer.domElement;
  const w = canvas.clientWidth, h = canvas.clientHeight;
  if (canvas.width !== w || canvas.height !== h) {
    renderer.setSize(w, h, false);
    if (camera) {
      camera.aspect = w / h;
      camera.updateProjectionMatrix();
    }
    requestRender();
  }
}

function observeResize(el) {
  // Recompute renderer + camera whenever the viewport changes size.
  if ('ResizeObserver' in window) {
    const ro = new ResizeObserver(() => resizeRenderer());
    ro.observe(el);
    mountEl.addEventListener('fox:screen-unmount', () => ro.disconnect(), { once: true });
  } else {
    window.addEventListener('resize', resizeRenderer);
  }
}

// ── model build ───────────────────────────────────────────────────────────
function buildModel() {
  // Tear down any existing meshes (variant / rebuild path).
  while (root.children.length) root.remove(root.children[0]);
  parts.length = 0;
  pickables.length = 0;

  const partsSpec = variant === 'slim' ? PARTS_SLIM : PARTS_CLASSIC;
  const uvLayer   = variant === 'slim' ? { ...UV, ...UV_SLIM } : UV;

  // Material — inner solid, outer transparent. NearestFilter so pixels stay crisp.
  const innerMat = new THREE.MeshLambertMaterial({
    map: canvasTexture, transparent: false, alphaTest: 0.5,
  });
  const outerMat = new THREE.MeshLambertMaterial({
    map: canvasTexture, transparent: true, alphaTest: 0.01, depthWrite: false,
  });

  for (const spec of partsSpec) {
    const group = new THREE.Group();
    group.position.set(spec.pos[0], spec.pos[1], spec.pos[2]);

    // Inner mesh.
    const innerGeo = makeBoxWithUVs(spec.size, uvLayer[spec.inner]);
    const innerMesh = new THREE.Mesh(innerGeo, innerMat);
    innerMesh.userData.layer = 'inner';
    innerMesh.userData.part = spec.key;
    group.add(innerMesh);

    // Outer mesh (slightly larger box, transparent material).
    const s = spec.outerScale;
    const outerGeo = makeBoxWithUVs([spec.size[0] * s, spec.size[1] * s, spec.size[2] * s], uvLayer[spec.outer]);
    const outerMesh = new THREE.Mesh(outerGeo, outerMat);
    outerMesh.userData.layer = 'outer';
    outerMesh.userData.part = spec.key;
    outerMesh.visible = outerVisible;
    group.add(outerMesh);

    root.add(group);
    parts.push({ key: spec.key, group, innerMesh, outerMesh });
    pickables.push(innerMesh, outerMesh);
  }
  requestRender();
}

/** Build a BoxGeometry with per-face UVs mapped to the given Minecraft regions.
 *  Three.js BoxGeometry face order is +X, -X, +Y, -Y, +Z, -Z. Our convention
 *  puts the player's "front" facing +Z (toward the default camera). */
function makeBoxWithUVs(size, regions) {
  const [w, h, d] = size;
  const geo = new THREE.BoxGeometry(w, h, d);
  const uv = geo.getAttribute('uv');
  const setFace = (faceIdx, region) => {
    if (!region) return;
    const [px, py, fw, fh] = region;
    const u0 = px / W, u1 = (px + fw) / W;
    const v0 = 1 - py / H, v1 = 1 - (py + fh) / H;
    // BoxGeometry vertex order per face: TL, TR, BL, BR.
    uv.setXY(faceIdx * 4 + 0, u0, v0);
    uv.setXY(faceIdx * 4 + 1, u1, v0);
    uv.setXY(faceIdx * 4 + 2, u0, v1);
    uv.setXY(faceIdx * 4 + 3, u1, v1);
  };
  // Mapping: Three +X face holds the texture's "left" region (player's left
  // is in +X when facing +Z); -X holds "right"; +Z is front, -Z back.
  setFace(0, regions.left);
  setFace(1, regions.right);
  setFace(2, regions.top);
  setFace(3, regions.bottom);
  setFace(4, regions.front);
  setFace(5, regions.back);
  uv.needsUpdate = true;
  return geo;
}

// ── camera orbit ──────────────────────────────────────────────────────────
function updateCameraFromOrbit() {
  // Spherical → cartesian, pivot at body center (y=14).
  const pivot = new THREE.Vector3(0, 18, 0);
  const cp = Math.cos(camPitch), sp = Math.sin(camPitch);
  const cy = Math.cos(camYaw),  sy = Math.sin(camYaw);
  camera.position.set(
    pivot.x + camDist * cp * sy,
    pivot.y + camDist * sp,
    pivot.z + camDist * cp * cy,
  );
  camera.lookAt(pivot);
  requestRender();
}

// ── render loop ───────────────────────────────────────────────────────────
function requestRender() {
  if (needsRender) return;
  needsRender = true;
  requestAnimationFrame(() => {
    needsRender = false;
    resizeRenderer();
    renderer.render(scene, camera);
  });
}

// ── input on the 3D viewport ──────────────────────────────────────────────
function wireCanvas3DInput() {
  const view = renderer.domElement;
  view.addEventListener('contextmenu', (e) => e.preventDefault());

  view.addEventListener('mousedown', (e) => {
    e.preventDefault();
    if (e.button === 2) {
      // Right button → orbit.
      mouseDown = 2;
      orbitStart = { x: e.clientX, y: e.clientY, yaw: camYaw, pitch: camPitch };
    } else if (e.button === 0) {
      // Left button → tool. Snapshot for undo BEFORE the first paint.
      mouseDown = 1;
      pushUndo();
      paintFromEvent(e);
      painting = activeTool === 'pencil' || activeTool === 'eraser';
    }
  });

  view.addEventListener('mousemove', (e) => {
    if (mouseDown === 2 && orbitStart) {
      const dx = e.clientX - orbitStart.x;
      const dy = e.clientY - orbitStart.y;
      camYaw   = orbitStart.yaw   - dx * 0.01;
      camPitch = Math.max(-1.4, Math.min(1.4, orbitStart.pitch + dy * 0.01));
      updateCameraFromOrbit();
    } else if (mouseDown === 1 && painting) {
      paintFromEvent(e);
    }
  });

  const release = () => { mouseDown = 0; painting = false; lastPx = null; orbitStart = null; };
  window.addEventListener('mouseup', release);
  view.addEventListener('mouseleave', () => { lastPx = null; });

  view.addEventListener('wheel', (e) => {
    e.preventDefault();
    camDist = Math.max(30, Math.min(200, camDist * (1 + e.deltaY * 0.001)));
    updateCameraFromOrbit();
  }, { passive: false });
}

function pickFromEvent(e) {
  const view = renderer.domElement;
  const rect = view.getBoundingClientRect();
  mouseNDC.x = ((e.clientX - rect.left) / rect.width)  * 2 - 1;
  mouseNDC.y = -(((e.clientY - rect.top) / rect.height) * 2 - 1);
  raycaster.setFromCamera(mouseNDC, camera);
  // Filter by outer-layer visibility so painting through a hidden hat goes to head.
  const meshes = pickables.filter(m => m.visible);
  const hits = raycaster.intersectObjects(meshes, false);
  return hits.length ? hits[0] : null;
}

function paintFromEvent(e) {
  const hit = pickFromEvent(e);
  if (!hit || !hit.uv) return;
  // hit.uv is in [0, 1] — convert to integer pixel coords on the 64×64 texture.
  // Our texture has flipY=true so the V axis is already Y-up there too; for
  // pixel coords we want top-left origin, so flip back.
  const px = Math.min(W - 1, Math.max(0, Math.floor(hit.uv.x * W)));
  const py = Math.min(H - 1, Math.max(0, Math.floor((1 - hit.uv.y) * H)));
  const p = { x: px, y: py };

  if (activeTool === 'eyedrop') { pickAt(p); return; }
  if (activeTool === 'fill')    { floodFill(p); markDirty(); return; }
  // Pencil / eraser — line-interpolate from last pixel so a fast drag is solid.
  paintLine(lastPx, p, activeTool === 'eraser' ? 'erase' : 'paint');
  lastPx = p;
  markDirty();
}

// ── 2D mini preview painting (sidebar) ────────────────────────────────────
function setupMiniPreview() {
  const prev = mountEl.querySelector('#sk-preview');
  const px2 = prev.getContext('2d');
  px2.imageSmoothingEnabled = false;

  // Render the backing → preview at 2× CSS scale (handled by stylesheet).
  function redrawPreview() {
    px2.clearRect(0, 0, W, H);
    px2.drawImage(tex, 0, 0);
  }
  redrawPreview();
  mountEl.addEventListener('sk-tex-dirty', redrawPreview);

  // Allow painting on the preview too — at 2× display so a logical pixel
  // is 2 CSS pixels on either side. Compute via getBoundingClientRect.
  let painting2 = false;
  const pxAt = (e) => {
    const r = prev.getBoundingClientRect();
    const x = Math.floor((e.clientX - r.left) * (W / r.width));
    const y = Math.floor((e.clientY - r.top)  * (H / r.height));
    return (x >= 0 && x < W && y >= 0 && y < H) ? { x, y } : null;
  };
  prev.addEventListener('mousedown', (e) => {
    if (e.button !== 0) return;
    pushUndo();
    painting2 = true;
    const p = pxAt(e);
    if (!p) return;
    if (activeTool === 'eyedrop') { pickAt(p); painting2 = false; return; }
    if (activeTool === 'fill')    { floodFill(p); markDirty(); painting2 = false; return; }
    paintLine(null, p, activeTool === 'eraser' ? 'erase' : 'paint');
    lastPx = p;
    markDirty();
  });
  prev.addEventListener('mousemove', (e) => {
    if (!painting2) return;
    const p = pxAt(e);
    if (!p) return;
    paintLine(lastPx, p, activeTool === 'eraser' ? 'erase' : 'paint');
    lastPx = p;
    markDirty();
  });
  window.addEventListener('mouseup', () => { painting2 = false; lastPx = null; });
}

function markDirty() {
  canvasTexture.needsUpdate = true;
  mountEl.dispatchEvent(new CustomEvent('sk-tex-dirty'));
  requestRender();
}

// ── tool implementations (paint pixels on `tex`) ──────────────────────────
function paintAt(p, mode) {
  if (!p) return;
  const r = Math.max(0, brushSize - 1);
  const x0 = Math.max(0, p.x - Math.floor(r / 2));
  const y0 = Math.max(0, p.y - Math.floor(r / 2));
  const x1 = Math.min(W, p.x + Math.ceil(brushSize / 2));
  const y1 = Math.min(H, p.y + Math.ceil(brushSize / 2));
  if (mode === 'erase') ctx2d.clearRect(x0, y0, x1 - x0, y1 - y0);
  else { ctx2d.fillStyle = activeColor; ctx2d.fillRect(x0, y0, x1 - x0, y1 - y0); }
}

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
  const d = ctx2d.getImageData(p.x, p.y, 1, 1).data;
  if (d[3] === 0) return;
  const hex = '#' + [d[0], d[1], d[2]].map(n => n.toString(16).padStart(2, '0')).join('').toUpperCase();
  setActiveColor(hex);
}

function floodFill(p) {
  if (!p) return;
  const img = ctx2d.getImageData(0, 0, W, H);
  const d = img.data;
  const i0 = (p.y * W + p.x) * 4;
  const target = [d[i0], d[i0 + 1], d[i0 + 2], d[i0 + 3]];
  const m = activeColor.replace('#', '');
  const fill = [parseInt(m.slice(0, 2), 16), parseInt(m.slice(2, 4), 16), parseInt(m.slice(4, 6), 16), 255];
  if (target.every((v, i) => v === fill[i])) return;
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
  ctx2d.putImageData(img, 0, 0);
}

// ── undo / redo ───────────────────────────────────────────────────────────
function snapshot() {
  try { return ctx2d.getImageData(0, 0, W, H); } catch (_) { return null; }
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
  const cur = snapshot(); if (cur) redoStack.push(cur);
  ctx2d.putImageData(s, 0, 0);
  markDirty();
}
function redo() {
  const s = redoStack.pop();
  if (!s) return;
  const cur = snapshot(); if (cur) undoStack.push(cur);
  ctx2d.putImageData(s, 0, 0);
  markDirty();
}

// ── UI wiring ─────────────────────────────────────────────────────────────
function setActiveTool(tool) {
  activeTool = tool;
  for (const b of mountEl.querySelectorAll('.sk-tool')) {
    b.classList.toggle('active', b.dataset.tool === tool);
  }
  const hint = mountEl.querySelector('#sk-hint');
  hint.textContent = ({
    pencil:  'Pencil — click a face to paint, drag to draw a stroke.',
    eraser:  'Eraser — click to clear pixels (alpha 0).',
    eyedrop: 'Eyedropper — click any face to copy that pixel\'s color.',
    fill:    'Fill — click a pixel to flood-fill its color region on that face.',
  })[tool] || '';
  const view = renderer.domElement;
  view.style.cursor = tool === 'eyedrop' ? 'copy' : tool === 'fill' ? 'pointer' : 'crosshair';
}

function wireToolButtons() {
  for (const b of mountEl.querySelectorAll('.sk-tool')) {
    b.addEventListener('click', () => setActiveTool(b.dataset.tool));
  }
}

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

function wireOuterToggle() {
  mountEl.querySelector('#sk-outer').addEventListener('change', (e) => {
    outerVisible = !!e.target.checked;
    for (const p of parts) p.outerMesh.visible = outerVisible;
    requestRender();
  });
}

function wireVariant() {
  mountEl.querySelector('#sk-variant').addEventListener('change', (e) => {
    variant = e.target.value === 'slim' ? 'slim' : 'classic';
    buildModel();
  });
}

function wireUndoRedo() {
  mountEl.querySelector('#sk-undo').addEventListener('click', undo);
  mountEl.querySelector('#sk-redo').addEventListener('click', redo);
}

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
      case 'r': camYaw = 0.55; camPitch = -0.15; camDist = 80; updateCameraFromOrbit(); break;
    }
  };
  document.addEventListener('keydown', onKey);
  mountEl.addEventListener('fox:screen-unmount', () => document.removeEventListener('keydown', onKey), { once: true });
}

// ── file actions ──────────────────────────────────────────────────────────
function setStatus(msg, kind) {
  const el = mountEl.querySelector('#sk-status');
  if (!el) return;
  el.textContent = msg || '';
  el.className = 'sk-status ' + (kind === 'ok' ? 'sk-status-ok' : kind === 'err' ? 'sk-status-err' : 'muted');
}

function drawIntoBacking(img) {
  ctx2d.clearRect(0, 0, W, H);
  if (img.naturalWidth === W && (img.naturalHeight === H || img.naturalHeight === H / 2)) {
    ctx2d.drawImage(img, 0, 0);
  } else {
    ctx2d.drawImage(img, 0, 0, W, H);
  }
  markDirty();
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
    tex.toBlob((blob) => {
      if (!blob) { setStatus('Save failed', 'err'); return; }
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url; a.download = 'fox-skin.png';
      document.body.appendChild(a); a.click(); a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1500);
      setStatus('Saved fox-skin.png', 'ok');
    }, 'image/png');
  });

  mountEl.querySelector('#sk-clear').addEventListener('click', () => {
    pushUndo();
    ctx2d.clearRect(0, 0, W, H);
    markDirty();
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
          buildModel();
        }
        setStatus(`Loaded current skin (${r.variant}).`, 'ok');
      };
      img.onerror = () => setStatus('Could not decode current skin', 'err');
      img.src = 'data:image/png;base64,' + r.base64;
    } catch (err) { setStatus(err.message || 'Load failed', 'err'); }
  });

  mountEl.querySelector('#sk-apply').addEventListener('click', async () => {
    if (!confirm(`Upload this canvas as your Minecraft skin (${variant})?`)) return;
    const btn = mountEl.querySelector('#sk-apply');
    btn.disabled = true; const orig = btn.textContent; btn.textContent = 'Uploading…';
    setStatus('Uploading skin…');
    try {
      const dataUrl = tex.toDataURL('image/png');
      const base64 = dataUrl.replace(/^data:image\/png;base64,/, '');
      const r = await window.fox.uploadSkinBytes({ base64, variant });
      if (r && r.ok) setStatus('Applied! It may take a moment to appear in-game.', 'ok');
      else           setStatus((r && r.error) || 'Upload failed', 'err');
    } catch (err) { setStatus(err.message || 'Upload failed', 'err'); }
    finally { btn.disabled = false; btn.textContent = orig; }
  });
}

// ── helpers ───────────────────────────────────────────────────────────────
function esc(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}
