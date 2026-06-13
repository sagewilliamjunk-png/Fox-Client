// Tests for filterAvailable — the v1.6.0 recheck gate that includes
// `recheck: true` mods (EMI / MemoryLeakFix / World Host) only when a build
// for the current MC version actually exists, with no "failed" toast while
// they're still pending.

const { filterAvailable, RECOMMENDED } = require('../src/main/recommendedMods');

const MC = '26.1.2';

// Minimal Modrinth /version entry that pickVersion will accept for MC.
function ver(mcVersion = MC) {
  return [{
    id: 'v1', project_id: 'p1', loaders: ['fabric'],
    game_versions: [mcVersion],
    files: [{ url: 'https://example.test/x.jar', filename: 'x.jar', primary: true, hashes: {} }],
    dependencies: [],
  }];
}

test('non-recheck mods always pass through with no network call', async () => {
  const calls = [];
  const fetchJson = (url) => { calls.push(url); return Promise.resolve(ver()); };
  const list = [{ slug: 'sodium' }, { slug: 'lithium' }];
  const out = await filterAvailable(list, MC, fetchJson);
  expect(out.map(m => m.slug)).toEqual(['sodium', 'lithium']);
  expect(calls).toHaveLength(0); // no recheck flag → no lookup
});

test('recheck mod is INCLUDED when a build exists for the MC version', async () => {
  const fetchJson = () => Promise.resolve(ver(MC));
  const out = await filterAvailable([{ slug: 'emi', recheck: true }], MC, fetchJson);
  expect(out.map(m => m.slug)).toEqual(['emi']);
});

test('recheck mod is SILENTLY DROPPED when no build matches the MC version', async () => {
  const fetchJson = () => Promise.resolve(ver('1.21.11')); // only an old build
  const out = await filterAvailable([{ slug: 'emi', recheck: true }], MC, fetchJson);
  expect(out).toHaveLength(0); // excluded, but no throw / no failure result
});

test('a fetch failure drops the recheck mod for this pass without throwing', async () => {
  const fetchJson = () => Promise.reject(new Error('network down'));
  await expect(
    filterAvailable([{ slug: 'world-host', recheck: true }], MC, fetchJson)
  ).resolves.toEqual([]);
});

test('mixed list keeps non-recheck mods and only the available recheck ones', async () => {
  const fetchJson = (url) =>
    Promise.resolve(url.includes('emi') ? ver(MC) : ver('1.21.11'));
  const list = [
    { slug: 'sodium' },
    { slug: 'emi', recheck: true },          // available
    { slug: 'memoryleakfix', recheck: true }, // not available
  ];
  const out = await filterAvailable(list, MC, fetchJson);
  expect(out.map(m => m.slug)).toEqual(['sodium', 'emi']);
});

test('the three pending mods are registered as recheck entries', () => {
  const bySlug = Object.fromEntries(RECOMMENDED.map(m => [m.slug, m]));
  for (const slug of ['emi', 'memoryleakfix', 'world-host']) {
    expect(bySlug[slug]).toBeDefined();
    expect(bySlug[slug].recheck).toBe(true);
  }
});
