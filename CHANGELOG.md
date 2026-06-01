# Changelog

All notable changes to Kitsune Client are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [1.3.7] — 2026-06-01

### Fixed

- **Launcher updates getting stuck in `pending/`.** When electron-updater
  finished downloading a release, the renderer showed a toast that
  auto-dismissed after 9 seconds and the update was meant to apply when the
  app *quit* — but clicking the X button hides to tray, so for users who
  never explicitly use Tray → Quit, releases would sit on disk indefinitely
  and never install. Replaced the toast with a persistent **"↻ Update v1.3.x
  ready — Restart →"** pill in the sidebar; clicking it calls
  `autoUpdater.quitAndInstall(false, true)`, which forces a real quit and
  applies the update.
- **Close-with-pending-update prompt.** If you click X while an update is
  downloaded and the game isn't running, the launcher now asks
  "Install update v1.3.x now? [Install & restart] [Not now] [Cancel]"
  instead of silently hiding to tray and stranding the install.
- **Auto-update errors are no longer silent.** Added
  `autoUpdater.on('error', …)` so failures surface as a toast ("Auto-update
  failed: <message>") instead of vanishing into a `.catch(() => {})`. That
  silent catch had been hiding every failure for four releases straight.

## [1.3.6] — 2026-06-01

### Added

- **"↻ Reinstall mods" button** in Home → Quick Actions. One click wipes the
  recommended-mod manifest, deletes every recommended-mod jar in the mods
  folder (your Fox client mod and any custom mods are kept), and runs a full
  install pass with the dependency walker — fixing any duplicate-jar or
  missing-dep situation in under a minute. Backed by a new `reinstallAll`
  helper in `recommendedMods.js` and `recommended:reinstallAll` IPC.

### Fixed

- **Stronger duplicate-jar detection** when installing recommended mods. The
  installer previously only removed the *manifest-recorded* previous filename
  on a version bump; jars that pre-dated the manifest (e.g. `fabric-api-0.149`
  alongside the new `fabric-api-0.150`) would slip through and crash Fabric
  Loader with "duplicate mod" on launch. Every install now also purges any
  on-disk jar that shares the new jar's prefix-stem — so old versions can't
  pile up.

## [1.3.5] — 2026-05-29

### Added

- **Save button in the Profiles editor header** next to *Set as active*, so you
  can commit changes without scrolling to the bottom of the form. The existing
  bottom Save button is unchanged; both fire the same handler and the new one
  inherits the locked-profile disabled state.

## [1.3.4] — 2026-05-29

### Fixed

- **Fabric "Incompatible mods found" crash on first launch.** The recommended-mod
  installer downloaded the slugs in our curated list but never read Modrinth's
  `dependencies` array, so mods like Visuality (needs `cloth-config`) and
  Visual Workbench (needs `forge-config-api-port` + `puzzles-lib`) were installed
  without their required libraries and Fabric Loader refused to launch. The
  installer now walks every version's `required` dependencies transitively,
  installs them by `project_id` (which is stable even when the dep's slug
  differs from its mod id), and tracks them in a `deps:` block of the manifest.
- **Existing broken installs self-heal.** When a mod is already on disk for the
  current MC version the installer used to short-circuit immediately; now it
  still resolves and downloads any missing required dependencies, so the next
  launch of v1.3.4 fixes the user's `.minecraft/mods` without any manual step.
- Cycle-safe and idempotent: a shared `visited` set means `fabric-api` is
  resolved exactly once even though most recommended mods require it. Optional,
  embedded, and incompatible deps are ignored.

## [1.3.3] — 2026-05-29

### Fixed

- **Sidebar avatar showed the letter initial instead of the player's skin head.**
  Crafatar (the single avatar source) had gone down (Cloudflare HTTP 521) and the
  handler swallowed the error. Replaced with an ordered fallback chain
  (Crafatar → mc-heads.net → minotar.net) so the head appears as long as any
  provider is reachable, plus an in-memory 10-minute cache so navigation /
  account refreshes don't re-hit the network on every render.

## [1.3.2] — 2026-05-29

### Added — Command Generator

- **35 new commands**: clone, fillbiome, place, locate, forceload, data, item,
  loot, bossbar, advancement, recipe, team, trigger, function, schedule,
  datapack, spreadplayers, rotate, spectate, defaultgamemode, tick, random,
  msg, me, teammsg, op/deop, kick, ban / pardon / ban-ip, whitelist, publish,
  setidletimeout, save-all, list, seed, stop. **64 commands total.**
- **/give can finally do the cool stuff**: lore (pipe-separated lines), full
  **attribute modifiers** (attribute · amount · operation · slot, repeatable),
  dyed color (accepts `#FF0000` / `255,0,0` / decimal int), rarity, max stack
  size, damage, and a raw-components passthrough for anything else.
- **/attribute** now handles `base set/get`, `get`, `modifier add/remove`, and
  `modifier value get`.
- New autocomplete lists: structures, biomes, loot tables, item slots,
  damage types.

## [1.3.1] — 2026-05-29

Post-release fixes from a live shakedown of v1.3.0.

### Fixed

- **Client jar showed "none" / wouldn't auto-update.** Settings from before the
  repo move kept the stale `Kitsune/Fox-Client` value, which 404'd the client
  jar download. `settings.js` now migrates that legacy value to the real repo.
- **Duplicate mod jars after a version bump.** The recommended-mod installer
  matched existing jars by slug prefix, which missed mods whose jar name
  differs from the slug (e.g. `simple-voice-chat` → `voicechat-*.jar`), leaving
  two versions side-by-side — which stops Fabric from launching. Install now
  removes the previously-recorded jar (tracked in the manifest) when the
  filename changes.
- **Recurring "failed N" auto-install toast.** Removed EMI, MemoryLeakFix, and
  World Host from the recommended set — none have a Minecraft 26.x build yet, so
  they failed on every boot. They'll be re-added when 26.x versions ship.

## [1.3.0] — 2026-05-28

First public release since 1.0.0 — consolidates the 1.1–1.3 development line.

### Added — Fox Launcher

- **Command Generator** — MCStacker-style visual builder for 29 commands,
  including an `/execute` chain builder, a full target-selector builder
  (`@p/@a/@r/@e/@s` + ranges/sort/limit/gamemode/tags), item components
  (custom name, enchantments, unbreakable), and saved commands.
- **Modpack support** — import any Modrinth `.mrpack`, and export a profile to
  a shareable `.mrpack` (dependency-free ZIP writer, SHA-512 verified import).
- **In-launcher Modrinth marketplace** inside the Profiles tab, with
  per-profile mod-update detection and one-click updates.
- **First-run setup wizard**, a keyboard-shortcuts reference in Settings, a
  screenshots gallery, minimize-to-tray, a visible multi-account switcher, a
  live news feed, and an auto mod-update check on boot.
- Expanded recommended-mod pack (Sodium, Lithium, Iris, EMI, Jade, voice chat,
  and more) installed and version-managed automatically.

### Added — Client (mod)

- **Minimap + full-screen World Map** (`M`) — texture-based rendering, vanilla
  `MapColor` terrain, biome-tinted mode, per-dimension caches, cave mode,
  Tab-held mob heads (pixel-art icons for 17 vanilla mobs), and PNG export.
- **Waypoint system** — deathpoints, sets/groups, per-waypoint editing
  (name/color/symbol/scope), a cycle keybind, chat sharing, 3D in-world
  billboards, and a footsteps trail.
- **Mount HUD**, **Hotbar Scroll Lock**, **Chat Aliases** (replaces Quick
  Commands), and an ephemeral **Loot History** with item icons.
- Cape system rewrite with velocity-driven physics sway.
- Ported the four legacy "FoxFeature" QoL features (Zoom, Shulker/Map Tooltip,
  Adaptive FPS Limit) to native modules.

### Changed

- Toggle Sprint no longer flips state while you're typing in any screen.
- HUD polish pass to close competitor feature gaps; expanded Dynamic Crosshair
  color tiers; Chat Highlights pipe syntax.
- Repo cleanup, README refresh, and a smaller launcher build (maximum
  compression, Electron locales trimmed to en-US).

### Fixed

- Three pre-release audit rounds: critical bugs, memory leaks / races, and
  security hardening across both the launcher and the mod.
- Recommended mods now re-download when the Minecraft version changes, with
  SHA-512 / SHA-1 verification.
- Discord RPC re-sends activity after a pipe reconnect.
- Profile edits are preserved across tab switches.

## [1.0.0] — 2026-04-20

First stable release. Every feature in this changelog is implemented,
tested, and wired through the ClickGUI.

### Added

- **String settings (F1).** New `StringSetting` type with inline editing
  in the ClickGUI (click → type → Enter commits, Esc cancels). Serialised
  as a plain string in the module JSON.
- **Profile import/export (F5).** New `ProfileIO` util. Settings →
  Export Profile writes a signed envelope to
  `config/kitsune/exports/<name>-<timestamp>.json`. Import opens the OS's
  native file picker (LWJGL `TinyFileDialogs`), parses the JSON, and
  creates a new profile slot (auto-renames on collision).
- **CrosshairDamageIndicator module (F6).** Floating "-X.X" numbers near
  the crosshair when you deal damage. Settings: color, crit color,
  duration, scale, rise speed, crit marker, drop shadow.
- **WeaponSwapReminder module (F7).** Flashes a toast when you take damage
  in combat while holding a tool, food, or potion. Settings: once-per-bout,
  combat window, per-category opt-in (food / tools / empty hand).
- **HUD editor keybind (F2).** `End` now opens the HUD editor from any screen.
- **Periodic auto-save (F3).** Profiles and HUD layouts are now flushed to
  disk every 30 seconds as a crash-safety net, on top of the existing
  explicit saves.
- **ClickGUI setting tooltips (F4).** Hover any setting for 400 ms to see
  its description.
- **Shooting-star variant pool (F8).** Title-screen meteors rotate through
  classic / fast / slow-comet / twin / meteor-shower each cycle. (The
  aurora variant was cut after visual testing — it read as a bright green
  bar rather than a horizon wave.)
- **Mascot fireflies (F9).** Five firefly particles orbit the idle fox
  mascot with pulsing alpha.
- **Starry-sky perf cache (F10).** Star phase offsets and brightness flags
  are pre-computed at class load; the per-frame hot path is one
  `Math.sin` per star plus a handful of mults and casts.
- **SAFETY.md (F11).** A top-level document that spells out exactly what
  the mod does and — more importantly — what it deliberately does not do.
  Linked from the README.
- **CHANGELOG.md (D2).** This file.
- **Mod icon (D3).** `assets/kitsune/icon.png` wired through `fabric.mod.json`.

### Changed

- **Version bumped to 1.0.0** (D1).
- **README rewritten** for v1.0: module count table, updated keybinds,
  screenshots section, refreshed install instructions.
- **FoxButton hover animation (P1).** 150 ms alpha lerp on border colour
  and top-highlight instead of step change. Adds a subtle warm-amber
  text fade on hover.
- **ClickGUI search icon (P5).** Magnifier glyph rendered in the empty
  search box so the affordance is obvious.
- **Keybind capture UX (P6).** Row pulses orange at 2 Hz while armed and
  shows "[press any key… Esc=clear]".
- **Wordmark colours (B7).** "FOX CLIENT" now renders in `FoxTheme.FOX_ORANGE`
  with a 1 px `FoxTheme.BARK` shadow, matching the rest of the pill-button
  colour language (previously clashed gold + yellow).
- **Keybind audit (D5).** HUD editor default moved to `End` (was `Right
  Shift`, which collides with ClickGUI).

### Fixed

- **ChatHighlights keywords now editable (B1).** Previously hard-coded as
  "fox,kitsune,hi,hello"; now backed by the new `StringSetting`.
- **TitleScreenMixin double-sync (B5).** Removed the redundant
  `FeatureRegistry.syncEnabledStates()` call after `ProfileManager.switchTo`
  (which already does the sync via `ModuleManager.applyProfileState`).

### Removed

- **Dead `FoxTheme.LOGO` and `FoxTheme.ICON` constants (B6).** Zero call
  sites; deleted.
- **Unused HitboxModule settings (B4).** `showEyeLine`, `showLookVector`,
  `lineColor` were placeholders that never had a render implementation;
  removed along with their now-unused imports. The module keeps its single
  working setting — the F3+B toggle.

### Verified (no change needed)

- **DisconnectConfirm (B2)** already implemented in `PauseScreenMixin`
  via `kitsune$wrapDisconnect` — a two-click confirmation toast. Plan
  claim was stale.
- **ChunkBorders module (B3)** is a HUD-info widget, not a render overlay;
  it never had the "radius / show-mob-spawning" dead settings the plan
  referenced. Plan claim was stale.
- **Notification stacking (P3)** already shipped: `MAX_VISIBLE = 4`,
  bottom-up vertical stack with 3 px gap.

---

## [0.2.0] — 2026-02-14 (Polish & Bugfix pass)

### Added
- 41 native modules across 9 categories.
- ClickGUI with search, tabbed categories, collapsible sections.
- HUD editor with drag, snap, ghost widgets, and vanilla HUD proxies
  (hotbar, health, food, air, experience).
- Per-module enabled-state snapshot into profile JSON.
- Auto-reconnect-after-restart with exponential backoff.
- Native modules now sync with `ProfileManager.switchTo`.

### Fixed
- Multiplayer button click no-op on title screen.
- Fox Settings screen entry points from Fox Menu + pause menu + ClickGUI gear.
- ClickGUI drag/scroll/label-color bugs.

---

## [0.1.0] — 2026-01-07 (Initial architecture)

- Profile system ported from the internal `modeswitch` mod.
- Mod-jar swap / pre-launch bootstrap framework.
- Server-rule matching + restart-confirm screen.
- Fox-themed title screen, Fox Main Menu, starry-sky background.
- First QoL feature set: Zoom, Full Brightness, Armor HUD, Shulker
  Tooltip, Map Tooltip, Adaptive FPS Limit, Particle Cull.
