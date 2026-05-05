# Changelog

All notable changes to Kitsune Client are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

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
