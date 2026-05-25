// Modrinth marketplace integration.
//
// Three public entry points consumed by IPC handlers:
//   - search(query, mcVersion, opts)  — facetted hit list
//   - project(slug)                    — full detail + version list
//   - install(slug, gameDir, mcVersion) — download + atomic write + manifest
//
// All three obey:
//   - 15 s connect timeout via httpClient.fetchJson
//   - SHA-512 verification on downloaded jars (already done by recommendedMods)
//   - Fabric loader filter and exact mcVersion match
//
// The install path piggybacks on recommendedMods.installOne so we get the
// version-aware manifest, stale-jar removal, and hash check for free.

const path = require('path');
const fs   = require('fs');
const { fetchJson } = require('./httpClient');
const recommendedMods = require('./recommendedMods');

const MODRINTH_BASE = 'https://api.modrinth.com/v2';

/** Build Modrinth's facet array. They take it as a query parameter encoded as
 *  a JSON string of arrays (outer = AND, inner = OR). */
function buildFacets(mcVersion) {
  const facets = [
    ['project_type:mod'],
    ['categories:fabric'], // loader
  ];
  if (mcVersion) facets.push([`versions:${mcVersion}`]);
  return JSON.stringify(facets);
}

/**
 * Hit /v2/search and return the lightweight card list the UI grid renders.
 * Caps results at 40 per page (Modrinth's max is 100 but we paginate locally).
 *
 * @returns {Promise<{hits:Array, totalHits:number, error?:string}>}
 */
async function search(query, mcVersion, opts = {}) {
  const limit  = Math.min(40, Math.max(1, opts.limit || 20));
  const offset = Math.max(0, opts.offset || 0);
  const params = new URLSearchParams({
    query: String(query || ''),
    limit: String(limit),
    offset: String(offset),
    facets: buildFacets(mcVersion),
    index: opts.sort || 'relevance', // relevance|downloads|follows|newest|updated
  });
  const url = `${MODRINTH_BASE}/search?${params}`;
  try {
    const r = await fetchJson(url, { userAgent: 'Fox-Launcher (modrinth-browser)' });
    return {
      hits: (r.hits || []).map(toCard),
      totalHits: r.total_hits || 0,
    };
  } catch (err) {
    return { hits: [], totalHits: 0, error: err.message || String(err) };
  }
}

/** Reduce a Modrinth search hit to the fields the UI actually renders. */
function toCard(hit) {
  return {
    slug:        hit.slug,
    projectId:   hit.project_id,
    title:       hit.title,
    description: hit.description,
    author:      hit.author,
    downloads:   hit.downloads,
    follows:     hit.follows,
    icon:        hit.icon_url || null,
    categories:  hit.categories || [],
    clientSide:  hit.client_side,
    serverSide:  hit.server_side,
  };
}

/**
 * Full project detail + version list, filtered to fabric+mcVersion versions.
 * Used by the "details" panel when the user clicks a card.
 */
async function project(slug, mcVersion) {
  try {
    const [info, versions] = await Promise.all([
      fetchJson(`${MODRINTH_BASE}/project/${encodeURIComponent(slug)}`, { userAgent: 'Fox-Launcher (modrinth-browser)' }),
      fetchJson(`${MODRINTH_BASE}/project/${encodeURIComponent(slug)}/version`, { userAgent: 'Fox-Launcher (modrinth-browser)' }),
    ]);
    const matching = (versions || []).filter(v =>
      (v.loaders || []).includes('fabric') &&
      (!mcVersion || (v.game_versions || []).includes(mcVersion))
    );
    return {
      slug:        info.slug,
      projectId:   info.id,
      title:       info.title,
      description: info.description,
      bodyMarkdown: info.body || '',
      icon:        info.icon_url || null,
      downloads:   info.downloads,
      follows:     info.followers || info.follows || 0,
      categories:  info.categories || [],
      clientSide:  info.client_side,
      serverSide:  info.server_side,
      sourceUrl:   info.source_url || null,
      issuesUrl:   info.issues_url || null,
      wikiUrl:     info.wiki_url || null,
      discordUrl:  info.discord_url || null,
      versions: matching.map(v => ({
        id:            v.id,
        name:          v.name,
        versionNumber: v.version_number,
        versionType:   v.version_type, // release|beta|alpha
        gameVersions:  v.game_versions || [],
        loaders:       v.loaders || [],
        datePublished: v.date_published,
        downloads:     v.downloads,
        primaryFile:   (v.files || []).find(f => f.primary) || (v.files || [])[0] || null,
        dependencies:  (v.dependencies || []).map(d => ({
          type: d.dependency_type, // required|optional|incompatible|embedded
          projectId: d.project_id,
          versionId: d.version_id,
        })),
      })),
    };
  } catch (err) {
    return { error: err.message || String(err) };
  }
}

/**
 * Install the slug into the given gameDir's mods folder for the current
 * mcVersion. Wraps recommendedMods.installOne so we reuse hash verification +
 * stale-jar removal + the recommended-mods.json manifest entry. After install,
 * also walks any required dependencies once-deep (no transitive resolution).
 */
async function install(slug, gameDir, mcVersion, opts = {}) {
  if (!gameDir || !fs.existsSync(gameDir)) {
    return { slug, status: 'error', error: 'Game directory does not exist.' };
  }
  // Primary install via the existing recommended-mods path.
  const result = await recommendedMods.installOne(slug, gameDir, mcVersion, opts);
  if (result.status !== 'installed' || !opts.installDependencies) return result;

  // Look up dependencies once-deep. We only resolve required deps that are
  // ALSO mods (project_type=mod); ignore resource-pack/data-pack deps for now.
  try {
    const detail = await project(slug, mcVersion);
    if (detail.error || !detail.versions.length) return result;
    const version = detail.versions[0];
    const deps = (version.dependencies || []).filter(d => d.type === 'required' && d.projectId);
    const depResults = [];
    for (const d of deps) {
      // Resolve project id → slug. Modrinth's /v2/project/{id} accepts either id or slug.
      try {
        const depDetail = await project(d.projectId, mcVersion);
        if (!depDetail.error && depDetail.slug) {
          const r = await recommendedMods.installOne(depDetail.slug, gameDir, mcVersion);
          depResults.push({ slug: depDetail.slug, status: r.status });
        }
      } catch (_) { /* skip on per-dep failure */ }
    }
    result.dependencies = depResults;
  } catch (_) { /* non-fatal — primary install already succeeded */ }
  return result;
}

module.exports = { search, project, install };
