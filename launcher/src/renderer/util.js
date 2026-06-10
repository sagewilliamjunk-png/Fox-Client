// Shared renderer helpers. Keep this module dependency-free (no DOM, no
// window.fox) so the Jest suite can import it directly.

/** Minimal HTML escaper for any text we splice into innerHTML. Defense in
 *  depth — even strings we believe are trusted (Microsoft error responses,
 *  Mojang usernames) get run through this. */
export function escapeHtml(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

/** "42s ago" / "3m ago" / "5h ago" / "2d ago", falling back to a locale date
 *  beyond a week. Accepts an epoch-ms timestamp; null/0 renders as "—". */
export function formatRelative(ts) {
  if (!ts) return '—';
  const diffSec = Math.max(0, Math.round((Date.now() - ts) / 1000));
  if (diffSec < 60)   return `${diffSec}s ago`;
  if (diffSec < 3600) return `${Math.round(diffSec / 60)}m ago`;
  if (diffSec < 86400) return `${Math.round(diffSec / 3600)}h ago`;
  if (diffSec < 7 * 86400) return `${Math.round(diffSec / 86400)}d ago`;
  return new Date(ts).toLocaleDateString();
}

/** Human-readable byte count: B under 1 KB, whole KB under 1 MB, then MB. */
export function formatBytes(b) {
  if (b < 1024) return `${b} B`;
  if (b < 1024 * 1024) return `${Math.round(b / 1024)} KB`;
  return `${(b / (1024 * 1024)).toFixed(1)} MB`;
}
