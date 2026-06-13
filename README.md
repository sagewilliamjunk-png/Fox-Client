# Fox Client

A fox-themed, **fair-play** Minecraft client mod for **Fabric on Minecraft 26.1.2**.

This was coded largely with claude, and I don't wish to decieve people into thinking I coded it myself.
I don't plan on making money from this, I just wanted to make something for myself and some friends

> **Status:** v1.6.0 — Stable &nbsp;|&nbsp; [⬇ Download Fox Launcher](https://github.com/sagewilliamjunk-png/Fox-Client/releases/latest)

Fox Client is built around three hard rules:

1. **Server-safe by design.** No kill-aura, xray, fly, reach, tracers, or
   packet manipulation — ever. See [SAFETY.md](SAFETY.md) for the full audit
   trail.
2. **Server-aware.** Per-server rules can auto-disable companion mods that a
   host bans, with a pre-join confirmation and an exponential-backoff
   auto-reconnect.
3. **Bundled QoL + optimisation + HUD.** One mod instead of a stack — zoom,
   full-bright, armor HUD, shulker tooltips, chat heads, FPS graph, paper
   doll, potion timers, and 30-plus more features under a tabbed ClickGUI.

---

## Features

Sixty-plus modules across eight categories (Combat, Movement, Player, Render,
HUD, Chat, Misc, Cosmetic). Everything is toggleable in the ClickGUI
(default `Right Shift`).

| Category  | Notable modules |
|-----------|-----------------|
| Combat    | Dynamic Crosshair, Crosshair Damage Indicator, Weapon Swap Reminder, Combat Timer, Durability Alert, **Combo Counter**, **Damage Tally** |
| Movement  | Toggle Sprint, Free Look, Anti-AFK |
| Player    | Auto Eat, Auto Respawn, No Hurt Cam |
| Render    | Hitbox (F3+B), Chunk Borders, Block Overlay, Menu Blur, Light Level, Hit Flash, Smooth Scroll, Weather Time, 3D Waypoint markers |
| HUD       | **Minimap** (Xaero-style terrain + mob heads), Coords, **Compass strip**, FPS Graph, Potion Timers, Paper Doll, Shield Status, Kill/Death Tracker, Session Stats, Server Info / TPS, **Mount HUD**, Keystrokes, CPS, Totem counter, **Inventory Preview**, **AFK Timer** |
| Chat      | Chat Highlights, Chat Logger, Chat Aliases, Transparent Chat |
| Misc      | Loot History, Death Screen, Disconnect Confirm, Memory Cleaner, **Hotbar Scroll Lock**, Zoom, Shulker / Map Tooltip, **Screenshot → Clipboard**, Adaptive FPS Limit, Deathpoint waypoints |
| Cosmetic  | Custom capes (9 built-in), **particle trails**, fox-themed title screen, idle mascot, procedural starry sky |

Plus a full **HUD editor** (drag-and-drop with vanilla-proxy widgets for
hotbar / health / food / air / xp), an **in-world / world-map waypoint
system**, and a profile system for one-key swapping between PvP, Survival,
Vanilla, and Custom layouts.

## Fox Launcher

The bundled desktop launcher ([`launcher/`](launcher/)) is a standalone
Electron app that does more than start the game:

- **One-click setup** — installs Java, Minecraft, Fabric, and mods for you.
- **Microsoft + multi-account** sign-in with a quick account switcher, plus an
  offline guest mode for singleplayer/LAN.
- **Isolated profiles** — each with its own mods, config, and saves.
- **Modpack import _and_ export** — install any Modrinth `.mrpack`, or bundle
  your own profile into a shareable `.mrpack`.
- **Command Generator** — an MCStacker-style visual builder for `/give`,
  `/summon`, `/execute` chains, and 25-plus more commands (modern 1.21.x
  syntax) with a target-selector builder and saved commands.
- **Server browser** — save servers, see live status (MOTD, player count,
  ping) via built-in Server List Ping, and quick-join straight into the game.
- **World Backups** — one-click zip of any singleplayer world to a directory
  that survives reinstalls, with restore and "keep both" modes.
- **Log uploader** — share a game log as an mclo.gs link (home-dir paths
  scrubbed) instead of pasting 5000 lines into Discord.
- **Java args presets** — Default / Performance / Low memory / Custom JVM
  flags without researching GC tuning yourself.
- **Screenshots gallery**, recommended-mod installer, and minimize-to-tray.

## Install (recommended)

**Use Fox Launcher** — it handles Java, Minecraft, Fabric, and mod installation automatically:

1. Download **[Fox Launcher Setup](https://github.com/sagewilliamjunk-png/Fox-Client/releases/latest)** from the latest release
2. Run the installer and sign in with your Microsoft account
3. Click Play — everything else is automatic

## Manual install

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) 0.19.x or newer for Minecraft 26.1.2.
2. Drop `kitsune-client-1.6.0.jar` and the matching `fabric-api` jar into your `mods/` folder.
3. Launch.

## Build from source

```bash
./gradlew build          # produces build/libs/kitsune-client-1.6.0.jar
./gradlew runClient      # launches a dev client
```

Requires **JDK 25** (Minecraft 26.1.2 compiles to release 25).

Recommended companion mods (not bundled — drop them in yourself):

## Key bindings

| Key | Action |
|---|---|
| `]` | Open Fox Menu |
| `Right Shift` | Open Module Menu |
| `End` | Open HUD editor |
| `V` (hold) | Zoom |
| `G` | Toggle Full Brightness |

All bindings are rebindable in vanilla Controls under category *"Fox Client"*.

## Profiles

Four named profiles, each storing every vanilla option + each module's
enabled state + custom HUD layout. Switching a profile applies the whole set
atomically and fires a toast. Profiles live at
`config/kitsune/profiles.json` and are auto-saved every 30 seconds as a
crash-safety net.

Export and import are in **Fox Settings → Export/Import Profile** (uses
your OS's native file picker).

## Per-server rules

When you click a server in the multiplayer list, Kitsune checks
`config/kitsune/server_rules.json` for matching host patterns. If a rule
says "disable mods X, Y on this host" and those mods are currently loaded,
you get a restart prompt. Accepting moves the JARs to
`mods/.kitsune-disabled/` (via a pre-launch hook on the next start) and
auto-reconnects you to the saved server after restart.

Default rules ship for Hypixel, Mineplex, and a 2b2t warning. **They're a
starting point, not a guarantee — read [SAFETY.md](SAFETY.md).**

## Screenshots

*Screenshots coming soon.*

## Project layout

This repository is a monorepo with two deliverables: the Fabric **mod** (at the
root) and the Electron **launcher** (in `launcher/`).

```
Fox-Client/
├── src/main/java/dev/kitsune/client/   ← the Fabric mod
│   ├── KitsuneClient.java                  ← ClientModInitializer entrypoint
│   ├── core/                               ← config, profiles, profile IO
│   ├── module/                             ← 50+ modules across 8 categories
│   │   ├── combat/  movement/  render/  hud/
│   │   └── chat/    misc/      cosmetic/
│   ├── hud/                                ← HudManager + widgets + editor
│   ├── worldmap/                           ← minimap / world-map + waypoints
│   ├── gui/clickgui/                       ← ClickGUI screen + panels
│   ├── setting/                            ← Boolean/Slider/Color/Mode/Keybind/String
│   ├── server/                             ← per-server rules + auto-reconnect
│   ├── event/                              ← internal pub/sub bus
│   ├── screen/                             ← Fox menu, starry sky, mascot
│   └── mixin/                              ← Minecraft integration
├── src/main/resources/                 ← fabric.mod.json, mixins, assets
├── build.gradle / gradlew              ← mod build (JDK 25, Fabric Loom)
│
└── launcher/                           ← the Electron desktop launcher
    ├── src/main/                           ← main process (IPC, auth, installers)
    ├── src/preload/                        ← context-bridge surface
    ├── src/renderer/                       ← UI (screens, styles, assets)
    └── tests/                              ← Jest suite
```

## License

MIT — see [LICENSE](LICENSE).

## Acknowledgements

The profile system, config hot-swap, and screen patterns were generalised
from the prior internal `modeswitch` mod that lived in this repo.

Starry sky, fox mascot, and in-menu visuals are pure procedural rendering —
no external textures except the user-supplied `fox_head.png` in
`assets/kitsune/textures/gui/`.
