// Unit tests for the Modrinth dependency walker in recommendedMods.installOne.
//
// All Modrinth HTTP calls (versions JSON, jar downloads, /version/<vid> lookups)
// go through `ctx.fetchJson` / `ctx.fetchVersion` / `ctx.fetchBuffer`, so we
// hand-stub them and the tests stay fully offline.
//
// Each test gets a fresh temp gameDir so the manifest at
// <gameDir>/config/fox-launcher/recommended-mods.json starts empty.

const fs   = require('fs');
const os   = require('os');
const path = require('path');

const { installOne } = require('../src/main/recommendedMods');

// ──────────────────────────────────────────────────────────────────────────
// helpers

let tmpDir;
beforeEach(() => {
  tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'foxdep-'));
  fs.mkdirSync(path.join(tmpDir, 'mods'), { recursive: true });
});
afterEach(() => { try { fs.rmSync(tmpDir, { recursive: true, force: true }); } catch (_) {} });

function readManifest() {
  const p = path.join(tmpDir, 'config', 'fox-launcher', 'recommended-mods.json');
  try { return JSON.parse(fs.readFileSync(p, 'utf8')); } catch (_) { return {}; }
}
const modsHas = (name) => fs.existsSync(path.join(tmpDir, 'mods', name));

// Build a fake Modrinth /version entry for a given project + filename, with
// any number of dep entries. Hashes omitted so installResolved skips verify.
function makeVersion({ projectId, vid, filename, deps = [], mcVersion = '26.1.2' }) {
  return {
    id: vid,
    project_id: projectId,
    loaders: ['fabric'],
    game_versions: [mcVersion],
    files: [{ url: `https://example.test/${filename}`, filename, primary: true, hashes: {} }],
    dependencies: deps,
  };
}

// Build a ctx where fetchJson returns the version list for a given slug/pid,
// fetchVersion returns a specific pinned version, and fetchBuffer returns
// fake jar bytes (so writeAtomic creates the file). Calls are tracked.
function makeCtx({ versionsBySlug = {}, versionsByVid = {}, sharedVisited = new Set() } = {}) {
  const calls = { fetchJson: [], fetchVersion: [], fetchBuffer: [] };
  return {
    calls,
    visited: sharedVisited,
    deps: [],
    modsDir: path.join(tmpDir, 'mods'),
    onProgress: () => {},
    async fetchJson(url) {
      calls.fetchJson.push(url);
      const m = url.match(/project\/([^/]+)\/version$/);
      if (m) {
        const list = versionsBySlug[decodeURIComponent(m[1])];
        if (!list) throw new Error(`fake fetchJson: no versions for ${m[1]}`);
        return list;
      }
      throw new Error('fake fetchJson: unhandled URL ' + url);
    },
    async fetchVersion(vid) {
      calls.fetchVersion.push(vid);
      const v = versionsByVid[vid];
      if (!v) throw new Error(`fake fetchVersion: ${vid} not stubbed`);
      return v;
    },
    async fetchBuffer(url) {
      calls.fetchBuffer.push(url);
      return Buffer.from('FAKE-JAR-' + url);
    },
  };
}

// ──────────────────────────────────────────────────────────────────────────
// tests

test('installs the parent + its required dependency, recording both', async () => {
  const parent = makeVersion({
    projectId: 'PARENT', vid: 'V_PARENT', filename: 'parent-1.0.jar',
    deps: [{ project_id: 'DEPA', version_id: null, dependency_type: 'required' }],
  });
  const depA = makeVersion({ projectId: 'DEPA', vid: 'V_DEPA', filename: 'depa-2.0.jar' });
  const ctx = makeCtx({ versionsBySlug: { parent: [parent], DEPA: [depA] } });

  const r = await installOne('parent', tmpDir, '26.1.2', { ctx });

  expect(r.status).toBe('installed');
  expect(modsHas('parent-1.0.jar')).toBe(true);
  expect(modsHas('depa-2.0.jar')).toBe(true);

  const mf = readManifest();
  expect(mf.parent.filename).toBe('parent-1.0.jar');
  expect(mf.parent.projectId).toBe('PARENT');
  expect(mf.deps.DEPA.filename).toBe('depa-2.0.jar');
  expect(mf.deps.DEPA.parentSlug).toBe('parent');

  // Dep is reported via ctx.deps as a row the caller can append to results.
  expect(ctx.deps).toEqual([
    expect.objectContaining({ slug: 'DEPA', status: 'installed', displayName: '(dependency)', dep: true }),
  ]);
});

test('uses dep.version_id to fetch a pinned version when set', async () => {
  const parent = makeVersion({
    projectId: 'P', vid: 'V_P', filename: 'p.jar',
    deps: [{ project_id: 'PINNED', version_id: 'V_PINNED', dependency_type: 'required' }],
  });
  const pinned = makeVersion({ projectId: 'PINNED', vid: 'V_PINNED', filename: 'pinned-9.9.jar' });
  const ctx = makeCtx({ versionsBySlug: { p: [parent] }, versionsByVid: { V_PINNED: pinned } });

  await installOne('p', tmpDir, '26.1.2', { ctx });

  expect(ctx.calls.fetchVersion).toContain('V_PINNED');
  // We should NOT have listed the dep project's versions when a vid is pinned.
  expect(ctx.calls.fetchJson.some(u => u.includes('project/PINNED/version'))).toBe(false);
  expect(modsHas('pinned-9.9.jar')).toBe(true);
});

test('ignores optional/incompatible/embedded dependencies', async () => {
  const parent = makeVersion({
    projectId: 'P', vid: 'V_P', filename: 'p.jar',
    deps: [
      { project_id: 'OPT', dependency_type: 'optional' },
      { project_id: 'EMB', dependency_type: 'embedded' },
      { project_id: 'INC', dependency_type: 'incompatible' },
    ],
  });
  const ctx = makeCtx({ versionsBySlug: { p: [parent] } });

  const r = await installOne('p', tmpDir, '26.1.2', { ctx });

  expect(r.status).toBe('installed');
  expect(ctx.deps).toEqual([]);
  expect(ctx.calls.fetchBuffer.length).toBe(1); // only the parent
});

test('cycle guard: a → b → a installs each exactly once', async () => {
  const A = makeVersion({
    projectId: 'A', vid: 'V_A', filename: 'a.jar',
    deps: [{ project_id: 'B', dependency_type: 'required' }],
  });
  const B = makeVersion({
    projectId: 'B', vid: 'V_B', filename: 'b.jar',
    deps: [{ project_id: 'A', dependency_type: 'required' }],
  });
  const ctx = makeCtx({ versionsBySlug: { a: [A], B: [B], A: [A] } });

  await installOne('a', tmpDir, '26.1.2', { ctx });

  expect(modsHas('a.jar')).toBe(true);
  expect(modsHas('b.jar')).toBe(true);
  expect(ctx.calls.fetchBuffer.filter(u => u.endsWith('/a.jar')).length).toBe(1);
  expect(ctx.calls.fetchBuffer.filter(u => u.endsWith('/b.jar')).length).toBe(1);
});

test('dep with no Fabric-26.1.2 version yields no-version but does not abort the parent', async () => {
  const parent = makeVersion({
    projectId: 'P', vid: 'V_P', filename: 'p.jar',
    deps: [{ project_id: 'MISSING', dependency_type: 'required' }],
  });
  // MISSING has versions, but none for fabric+26.1.2 (different MC).
  const stale = makeVersion({ projectId: 'MISSING', vid: 'V_S', filename: 's.jar', mcVersion: '1.20.4' });
  const ctx = makeCtx({ versionsBySlug: { p: [parent], MISSING: [stale] } });

  const r = await installOne('p', tmpDir, '26.1.2', { ctx });

  expect(r.status).toBe('installed');         // parent still installed
  expect(modsHas('p.jar')).toBe(true);
  expect(modsHas('s.jar')).toBe(false);
  expect(ctx.deps).toEqual([
    expect.objectContaining({ slug: 'MISSING', status: 'no-version', dep: true }),
  ]);
});

test('on-disk dep is recorded in the manifest and not re-downloaded', async () => {
  const parent = makeVersion({
    projectId: 'P', vid: 'V_P', filename: 'p.jar',
    deps: [{ project_id: 'D', dependency_type: 'required' }],
  });
  const dep = makeVersion({ projectId: 'D', vid: 'V_D', filename: 'd.jar' });
  // Pre-create the dep jar on disk (e.g. user already had it).
  fs.writeFileSync(path.join(tmpDir, 'mods', 'd.jar'), 'EXISTING');

  const ctx = makeCtx({ versionsBySlug: { p: [parent], D: [dep] } });
  await installOne('p', tmpDir, '26.1.2', { ctx });

  // Dep was discovered (1 fetchJson for its version list), but not downloaded.
  expect(ctx.calls.fetchBuffer.filter(u => u.endsWith('/d.jar')).length).toBe(0);
  expect(fs.readFileSync(path.join(tmpDir, 'mods', 'd.jar'), 'utf8')).toBe('EXISTING');
  const mf = readManifest();
  expect(mf.deps.D.filename).toBe('d.jar');
  expect(mf.deps.D.parentSlug).toBe('p');
});

test('already-installed parent still walks deps so existing broken installs self-heal', async () => {
  const parent = makeVersion({
    projectId: 'P', vid: 'V_P', filename: 'p.jar',
    deps: [{ project_id: 'D', dependency_type: 'required' }],
  });
  const dep = makeVersion({ projectId: 'D', vid: 'V_D', filename: 'd.jar' });

  // Pretend the parent was installed by an older launcher: jar on disk +
  // manifest entry for the current mcVersion, but no dep.
  fs.writeFileSync(path.join(tmpDir, 'mods', 'p.jar'), 'OLD');
  const mfDir = path.join(tmpDir, 'config', 'fox-launcher');
  fs.mkdirSync(mfDir, { recursive: true });
  fs.writeFileSync(
    path.join(mfDir, 'recommended-mods.json'),
    JSON.stringify({ p: { mcVersion: '26.1.2', filename: 'p.jar' } }),
  );

  const ctx = makeCtx({ versionsBySlug: { p: [parent], D: [dep] } });
  const r = await installOne('p', tmpDir, '26.1.2', { ctx });

  expect(r.status).toBe('skipped');
  expect(modsHas('d.jar')).toBe(true);              // dep was healed
  const mf = readManifest();
  expect(mf.deps.D.filename).toBe('d.jar');
  expect(mf.p.projectId).toBe('P');                  // back-filled
});
