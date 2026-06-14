# Changelog

All notable changes to Kitsune Client are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added — Client (mod)

- **Fight Summary** *(Combat)* — when a fight ends (no hits dealt or damage
  taken for a short window), posts a one-line recap toast: hits landed, damage
  dealt vs. taken, and bout duration. Self-contained: tracks its own bout from
  the shared `PlayerAttackMixin` hit feed plus a health-delta watch, so it
  doesn't depend on Combo Counter / Damage Tally being on. Display-only.

## [1.6.0] — 2026-06-13

A four-front release: a fair-play PvP toolkit, a generalized cosmetics system,
a launcher server browser, and reliability work so the v1.5.0 release mishaps
can't recur.

### Added — Client (mod)

- **Combo Counter** *(Combat)* — consecutive hits landed within a reset window,
  with a session best and a colour ramp as it climbs. Fed by the same
  `PlayerAttackMixin` hook as Combat Timer, so it counts the connect regardless
  of whether a clientside damage delta is visible.
- **Damage Tally** *(Combat)* — running session totals of damage dealt
  (measured clientside, matching the Crosshair Damage Indicator) vs. taken
  (exact, from your own health decreases), with an optional ratio.
- **Target HUD: "Sticky After Hit"** — opt-in mode that keeps the HUD pinned to
  the last entity you struck for a few seconds, so it doesn't vanish when you
  strafe off-target mid-fight.
- **Particle Trail** *(Cosmetic)* — the second cosmetic type: trail-cosmetic
  owners emit a client-side particle trail while moving (flame / hearts / soul
  fire / snow, per the manifest). No render mixin — pure `addParticle`, so
  trivially server-safe.
- **Three more built-in capes** — Aurora, Crimson, Gold (9 total).

### Added — Fox Launcher

- **Server browser** — a new Servers tab: save servers, see live status (MOTD,
  player count, latency) via a from-scratch Server List Ping engine, and
  **quick-join** (writes the server into the active profile's auto-join field
  and launches through the normal path — the launcher never speaks the join
  protocol itself).

### Changed — internals

- **Cosmetic registry generalized** from cape-only to multi-type
  (`cosmetic/CosmeticRegistry.java`): a new optional `trails` manifest block
  with per-trail particle keys, and the `owners` block now grants ids of
  either type, routed by which registry defines them. Fully back-compatible —
  existing cape-only manifests behave exactly as before; `CapesModule` and
  `AvatarRendererCapeMixin` are untouched.

### Reliability

- **CI release-asset guard** (`.github/workflows/release.yml`): the workflow now
  fails if a release is missing `latest.yml`, the installer, or the mod jar —
  *before* it deletes the previous release. This turns the exact silent
  `electron-builder` "skipped publishing" failure that broke v1.5.0's launcher
  self-update into a red build instead of a green one.
- **Recommended-mods auto-recheck**: EMI, MemoryLeakFix, and World Host are back
  in the pack flagged `recheck: true` — the install pass resolves each for the
  current MC version and includes it only if a build now exists, with no
  "failed N" toast while we wait and automatic inclusion the moment 26.x ships.

### Tests

- Launcher suite 174 → 190: SLP VarInt/status-response/MOTD parsing, the
  recommended-mods recheck gate, and the server-entry validator. Mod-side
  `HudSettingsCompatTest` extended to cover the two new Combat widgets.

## [1.5.0] — 2026-06-10

A full improvement pass over both halves of the repo: new fair-play modules,
two launcher tools, a large HUD-code refactor, and a 107 → 173 test-count jump.

### Removed — KeepSprint (release blocker)

- **KeepSprintModule is gone.** It shipped in 1.4.1 by mistake: SAFETY.md has
  always listed KeepSprint in the "never present in the codebase" list, and
  sprint-reset bypass is exactly what GrimAC-class anti-cheats flag. The class
  was deleted (not disabled). Profiles that saved a KeepSprint entry load
  fine — unknown modules are skipped by design.
- SAFETY.md got a follow-up audit: the gray-zone list now names the four
  addon-gated modules (Free Look, Reach Display, Hitboxes, Anti-AFK), Minimap
  is correctly listed as shipped, and a new "Automation modules — where we
  draw the line" section explains why Auto Eat and Anti-AFK stay (and how
  they're gated) while AutoSoup/AutoClicker stay banned.

### Added — Client (mod)

- **Compass HUD** *(HUD)* — Lunar-style sliding cardinal strip centred on
  your yaw (or a compact "NE (135°)" text mode), with edge fade and an
  accent centre tick.
- **Combat Timer** *(Combat)* — counts down after you take or land a hit so
  you know when the combat-log window is over. Hurt detection via
  `hurtTime` edge; hits-dealt via the existing `PlayerAttackMixin` hook.
- **Durability Alert** *(Combat)* — one-shot warning toast (and optional
  ding) when armor or a held tool crosses a threshold %. Re-arms on item
  swap or repair.
- **Screenshot Clipboard** *(Misc)* — new screenshots are copied straight to
  the OS clipboard for pasting into Discord. Deliberately mixin-free: it
  watches the screenshots folder and copies once the async write settles.
- **Inventory Preview** *(HUD)* — your 9×3 main inventory as a HUD grid.
- **AFK Timer** *(HUD)* — appears after N idle minutes and counts how long
  you've been away. Display only (the gray-zone Anti-AFK module is separate
  and unchanged).
- **Four new built-in capes** — Ember, Midnight, Snowfox, Forest — granted to
  everyone via the cosmetics manifest.

### Added — Fox Launcher

- **Log uploader** — Logs → Upload posts the buffer to mclo.gs and copies the
  share link. Confirms first (the link is public), scrubs your home-directory
  paths, and truncates to mclo.gs limits keeping the newest lines.
- **Java args presets** — Settings → Java: Default / Performance (G1 client
  tuning) / Low memory / Custom. Preset flags are applied before per-profile
  JVM args, so profiles still win on conflict.
- **World Backups** — new Resources sub-tab. One-click zip of any world to
  ~/.foxlauncher/backups (outside the game dir, so reinstalls can't eat
  them), with restore (incl. "keep both" restore-as-copy) and delete.
- **Modpack import audit trail** — SHA-512 mismatches, unverifiable files,
  and download failures during an .mrpack import are now written to
  `~/.foxlauncher/logs/import-audit-*.log` and surfaced in the result.

### Changed — internals

- **New `BaseHudModule` base class** absorbs the registration, visibility,
  widget-identity, and panel/accent-bar rendering that 14 HUD modules each
  duplicated (~500 lines of copy-paste gone). Saved layouts and configs are
  untouched: widget ids, setting names, and defaults are all preserved;
  modules with bespoke appearance settings (Coords, Server Info, Session
  Stats, K/D, Keystrokes) keep them and override the color hooks instead.
- **New `ClickTracker` utility** replaces the duplicated rolling-window CPS
  code in the CPS and Keystrokes modules.
- **New `Palette` constants** replace scattered raw ARGB hex in the migrated
  modules.
- `GameRendererMixin` renamed to `CameraZoomMixin` — it always targeted
  `Camera`, not `GameRenderer`; the old name was a leftover from before the
  26.x FOV pipeline change. (A merge with `GameRendererNoHurtCamMixin` was
  considered and rejected: they target different classes.)
- Launcher: shared `renderer/util.js` (`escapeHtml` / `formatRelative` /
  `formatBytes`) replaces five per-screen copies; new `main/gameDirs.js`
  consolidates ~20 inline game-dir resolution duplicates in ipc.js.

### Fixed — the client crashed on boot since 1.4.1

Two of 1.4.1's mixins were written against pre-26.x method signatures and were
never runtime-tested — every launch since has died during mixin apply:

- **`ScreenStickyShulkerMixin`** targeted `Screen.render`, which MC 26.x
  renamed to `extractRenderState`. Retargeted; the Alt+Shift pinned shulker
  grid now actually loads.
- **`GameRendererNoHurtCamMixin`** declared the old
  `bobHurt(PoseStack, float)` shape; 26.x passes
  `(CameraRenderState, PoseStack)`. Its `require = 0` only forgives a missing
  target, not a descriptor mismatch, so this was a hard crash. Fixed; No Hurt
  Cam works.

Caught by the new release smoke test (boot the real client to the title
screen and read the log) — v1.5.0 boots clean: 68 modules registered, all 6
capes loaded, no mixin errors.

### Performance — Minimap & World Map

- **Budgeted terrain pipeline.** Terrain tiles are now computed from a
  nearest-chunk-first work queue drained 32 (surface) / 8 (cave) tiles per
  tick. Previously a cave-mode refresh at max range recomputed up to ~1 200
  chunk tiles synchronously in a single tick — a guaranteed stutter every
  2 seconds underground.
- **Terrain edits finally show up.** Surface tiles used to be computed once
  and never refreshed (mined blocks stayed on the map until you walked 17
  chunks away). The periodic sweep now recomputes tiles continuously; the
  GPU upload is skipped when pixels are identical (`MapTextureCache.upsert`
  no-change early-out), so the steady-state cost is heightmap reads only.
- **Cave scan depth capped** at 48 blocks below the player (was: down to
  world bottom, ~380 levels per column in a 1.18+ world).
- **Light overlay moved out of the render loop.** It used to issue ~16k
  light queries per FRAME at max range; it now samples on a 10-tick cadence
  in onTick and the render pass just projects cached points.
- **Slime chunks & chunk grid** render as pose-transformed world-space fills
  (1 per chunk / ~70 lines total) instead of 256 per-pixel fills per chunk
  per frame — and the grid now draws actual lines, not corner dots.
- **Circle frame baked to textures.** Circle mode drew the background disc +
  border rings as ~900 row fills per frame; they're now two cached textures
  (rebuilt only on size/cave change) blitted once each. The frame's corners
  also mask the square terrain spill that used to leak outside the circle.
- **World map discovery budgeted** (16 tiles/tick instead of up to 169 in
  one tick when entering a new area) and the player's 3×3 chunks are
  re-swept every 2 s so edits appear on the persistent map too — without
  dirtying the save file when nothing changed. New tiles also follow the
  minimap's full color mode (Vanilla Map / Biome Tinted / Altitude) instead
  of a boolean tint.

### Tests & hardening

- Launcher suite grew from 107 to 173 tests: resource/shader-pack delete
  traversal guards, renderer utils, gameDirs resolution branches, log-upload
  scrub/truncate, world-backup roundtrip (incl. ZIP-slip guard), import
  audit-trail formatting, Java-preset validation, and unknown-key stripping
  through `settings:patch` / `profiles:patch`.
- Mod side: new JUnit tests for `ClickTracker` and `ServerRule` host globs.
- Recommended mods recheck: EMI, MemoryLeakFix, and World Host still have no
  MC 26.x builds on Modrinth — they remain disabled.

## [1.4.1] — 2026-06-03

Finishes the requests deferred in 1.3.9.

### Added — 4 new modules + Player category gets its first inhabitants

- **Auto Eat** *(Player)* — when hunger drops at/below a threshold, switches
  to the best food in the hotbar, holds right-click until full, restores the
  original slot. Server-safe; just simulates the same input you'd give.
- **Auto Respawn** *(Player)* — clicks Respawn for you after a short delay so
  you can still read the death message but don't have to fiddle with the UI.
- **No Hurt Cam** *(Player)* — at 0 strength, cancels the GameRenderer
  `bobHurt` pass entirely so the screen doesn't tilt when you take damage.
- **Keep Sprint** *(Movement)* — reasserts the sprint flag the same tick MC
  drops it to damage, as long as forward is still held. Lunar/Badlion ship the
  same feature; clean, no-mixin onTick implementation.

### Changed — Shulker tooltip behaviour matches the spec exactly now

- **Shift** alone now shows the visual 9×3 grid (was Alt+Shift). Cleaner
  match to user expectation: "Shift → show what's inside."
- **Alt+Shift** now *pins* the grid at the cursor position the moment you
  press it and **keeps drawing it** there until you release the keys — even
  if your mouse moves away from the shulker. New `ShulkerPinManager` +
  `ScreenStickyShulkerMixin` handle the capture / lifecycle / overlay render.

## [1.4.0] — 2026-06-03

### Changed — Skin Editor is now 3D

- The Skin Editor was a flat 64×64 PNG canvas — which the user pointed out is
  nobody's idea of how to make a skin. **Rebuilt as a 3D paintable player
  model** using Three.js (vendored, no runtime CDN dependency). Left-click
  any face on the model to paint that pixel of the texture; right-drag to
  orbit; mouse wheel to zoom; **R** to reset camera.
- Full Minecraft body geometry — head, body, two arms, two legs — each with
  the standard 64×64 UV layout per face. **Outer "layer 2"** boxes (hat,
  jacket, sleeves, pants) render slightly larger over the inner ones; a
  sidebar toggle hides them so you can paint through to the inner layer.
- **Classic / Slim** variant selector rebuilds the arm geometry to 4-wide
  (Steve) or 3-wide (Alex) on the fly.
- All existing tools work in 3D — pencil, eraser, eyedropper, flood-fill,
  brush sizes 1-3 px, undo/redo, B/E/I/G shortcuts.
- A small **2D texture preview** in the sidebar shows the raw UV layout and
  is also clickable for fine pixel-level work where the 3D view can't reach.
- Load current skin / Save PNG / Apply as my skin all unchanged — the
  upload path still goes through the main-process IPC so the access token
  never touches the renderer.

### Plumbing

- Vendored Three.js (`launcher/src/renderer/vendor/three.module.js`, ~1.3 MB
  raw; compresses to ~250 KB in the installer). No bare-specifier imports —
  loaded by relative path so the renderer's CSP `script-src 'self'` is
  satisfied without changes.

## [1.3.9] — 2026-06-03

### Fixed

- **Capes rendered black/purple** ("missing-texture" pattern). The cape system
  was passing the full resource path to `ClientAsset.ResourceTexture`'s
  single-arg constructor, which internally does
  `id.withPath(s -> "textures/" + s + ".png")` — turning
  `kitsune:textures/cape/fox.png` into
  `kitsune:textures/textures/cape/fox.png.png` (double prefix, no such file).
  Now we pass the short logical id (`kitsune:cape/<id>`) and the constructor
  resolves the right path. Owners' cape textures render again.
- **Zoom didn't work on V**. Default keybind was `C`, which collides on
  some setups. Changed default to `V` to match Lunar/Badlion conventions —
  the user can still rebind in Options → Controls under "Fox Client".
- **Shulker box tooltip — corrected modifier behavior** to match the design
  spec: no modifier shows an item-by-item text summary of contents (the
  "letters" — `8× Cobblestone`, `12× Iron`, …, truncated at 8 lines), Shift
  passes through to the visual 9×3 grid (rendered by `ItemTooltipImageMixin`)
  with no extra text cluttering the popup. Alt+Shift sticky behavior is a
  TODO requiring a separate screen-overlay render hook.

## [1.3.8] — 2026-06-01

### Added

- **Skin Editor** — a 64×64 pixel-art canvas built into the launcher.
  Tools: pencil, eraser, eyedropper, flood-fill. Brush sizes 1–3 px,
  undo/redo (Ctrl+Z / Ctrl+Shift+Z), keyboard shortcuts (B / E / I / G), an
  8-swatch palette with recent-color memory, a toggleable UV overlay that
  labels every body part, and a live 1× preview. Workflows: **Open PNG** /
  **Save PNG** for round-tripping with disk, **Load current skin** to pull
  your active Minecraft skin straight into the canvas, **Apply as my skin**
  to upload the canvas as your new skin (classic/slim variant selector).
- **Resources** tab in the sidebar — replaces the **Commands** entry and
  hosts both the existing Command Generator and the new Skin Editor as
  sub-tabs. The old `#commands` route still lands here for back-compat.

### Plumbing

- New main-process helpers `fetchPngBuffer` and `uploadSkinBytes` in
  `skins.js`; new IPC handlers `skins:fetchPng` and `skins:uploadBytes`;
  preload bindings `fetchSkinPng` and `uploadSkinBytes`. All Minecraft API
  traffic stays in the main process — the renderer never gets a token.

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
