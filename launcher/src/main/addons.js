// Fox Client addon catalog + per-launch addons.json writer.
//
// The mod reads <gameDir>/config/kitsune/addons.json on init to decide
// whether to register each "addon" (gray-zone or otherwise opt-in) module.
// We rewrite that file BEFORE every Java spawn so the active profile's
// choices take effect on the very next launch — no second restart needed.
//
// The id strings in CATALOG must exactly match dev.kitsune.client.addon
// .AddonCatalog on the mod side. Adding a new addon = update both.

const fs = require('fs');
const path = require('path');

/**
 * The full set of addon flags Fox Client ships. Order = display order in
 * the launcher's profile editor. Each entry's `id` is the canonical key
 * stored in profile.disabledAddons and written to addons.json.
 */
const CATALOG = [
  {
    id: 'grayzone.anti_afk',
    group: 'grayzone',
    displayName: 'Anti-AFK',
    description: 'Periodic tiny view nudge to defeat idle kicks.',
    risk: 'high',
    riskNote: 'Input automation. Most servers ban it explicitly.',
  },
  {
    id: 'grayzone.hitboxes',
    group: 'grayzone',
    displayName: 'Hitboxes',
    description: 'Toggles vanilla F3+B entity hitboxes via the mod\'s ClickGUI.',
    risk: 'medium',
    riskNote: 'Server-safe (vanilla feature) but the toggle pattern is a known anti-cheat heuristic.',
  },
  {
    id: 'grayzone.reach_display',
    group: 'grayzone',
    displayName: 'Reach display',
    description: 'HUD readout of the distance to whatever the crosshair is over.',
    risk: 'low',
    riskNote: 'Purely informational. The word "reach" alone trips some anti-cheat name filters.',
  },
  {
    id: 'grayzone.free_look',
    group: 'grayzone',
    displayName: 'Free Look',
    description: 'Decoupled camera (Lunar/Optifine FreeLook).',
    risk: 'high',
    riskNote: 'Universally banned on competitive PvP. Keep off when joining those servers.',
  },
];

/** Catalog as a plain array, e.g. for the profile editor UI. */
function catalog() { return CATALOG.map(a => ({ ...a })); }

/**
 * Write the addons.json the mod consumes. `disabledIds` is the union of
 * the addons the active profile wants disabled.
 */
function writeAddonsJson(gameDir, disabledIds) {
  const dir = path.join(gameDir, 'config', 'kitsune');
  try { fs.mkdirSync(dir, { recursive: true }); }
  catch (_) { return false; }
  const target = path.join(dir, 'addons.json');
  const tmp = target + '.tmp';
  const valid = new Set(CATALOG.map(a => a.id));
  const cleaned = (disabledIds || []).filter(id => valid.has(id));
  const payload = JSON.stringify({ disabled: cleaned }, null, 2);
  try {
    fs.writeFileSync(tmp, payload);
    fs.renameSync(tmp, target);
    return true;
  } catch (_) {
    return false;
  }
}

module.exports = { catalog, writeAddonsJson, CATALOG };
