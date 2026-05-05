# Kitsune Client

A fox-themed, **fair-play** Minecraft client mod for **Fabric on Minecraft 1.21.11**.

> **Status:** v1.0.0 — Stable

Kitsune Client (internally still referred to as "Fox Client" in some older
source files) is built around three hard rules:

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

Forty-one modules across nine categories. Everything is toggleable in the
ClickGUI (default `Right Shift`).

| Category  | Count | Notable modules |
|-----------|------:|-----------------|
| Combat    | 4 | Dynamic Crosshair, Damage Indicator, Weapon Swap Reminder, Reach Display |
| Movement  | 3 | Toggle Sprint, Free Look, Anti-AFK |
| Render    | 9 | Hitbox (F3+B), Chunk Borders (F3+G), Block Overlay, Menu Blur, Light Level, Hit Flash, Smooth Scroll, Weather Time |
| HUD       | 10 | Coords, FPS Graph, Potion Timers, Paper Doll, Shield Status, Kill/Death Tracker, Session Stats, Server Info, Armor Durability, Reach/Cooldown |
| Chat      | 3 | Chat Highlights, Chat Logger, Transparent Chat |
| Misc      | 5 | Loot History, Death Screen, Quick Commands, Disconnect Confirm, Capitalized Font |
| QoL       | 6+ | Zoom, Full Brightness, Armor Trims, Container Recolor, Chat Heads, Map Tooltip, Shulker Tooltip |
| Optimisation | 2 | Adaptive FPS Limit (on unfocus), Particle Cull |
| Cosmetic  | — | Fox-themed title screen, idle mascot, procedural starry sky |

Plus a full **HUD editor** (drag-and-drop with vanilla-proxy widgets for
hotbar / health / food / air / xp) and a 4-profile system for one-key
swapping between PvP, Survival, Vanilla, and Custom layouts.

## Build

```bash
./gradlew build          # produces build/libs/kitsune-client-1.0.0.jar
./gradlew runClient      # launches a dev client
```

Requires **JDK 21**.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) 0.18.6 or newer for Minecraft 1.21.11.
2. Drop `kitsune-client-1.0.0.jar` and the matching `fabric-api` jar into your `mods/` folder.
3. Launch.

Recommended companion mods (not bundled — drop them in yourself):

- **Sodium** + **Iris Shaders** — GPU rendering + shaders
- **Lithium** + **FerriteCore** — server-tick & memory optimisation
- **Simple Voice Chat** — proximity voice (server-side mod required)
- **Jade** — block info HUD

## Launcher

Fox Launcher is a desktop app (built with Electron) that handles everything
between clicking a button and actually playing on Kitsune — Microsoft login,
Java detection, Fabric version resolution, automatic mod updates, and live log
streaming. You never touch a terminal or copy jar files by hand.

**What it does:**
- Signs you in via the **OAuth 2.0 device-code flow** (opens a browser tab,
  you enter a short code — no passwords stored in the launcher)
- Auto-detects Java 21+ on your machine; shows a clear error if none is found
- Reads the Minecraft version metadata that the official launcher already
  downloaded, then builds and spawns the full Java launch command
- Downloads the latest Kitsune jar from GitHub Releases on startup and drops
  it into your `mods/` folder automatically (can be turned off in Settings)
- Streams game stdout/stderr into a built-in **Logs** tab in real time
- Keeps running in the system tray while the game is open so you can relaunch
  quickly without reopening the app

All launcher data lives in `~/.foxlauncher/` (credentials, settings, cached
jars). Nothing is written outside that folder or your existing Minecraft game
directory.

### How to run the launcher

**Prerequisites — do these once:**

1. **Install Java 21 or newer.**
   Download from [Adoptium](https://adoptium.net/) or any JDK 21+ distribution.
   The launcher auto-detects it; no manual path needed unless you have multiple
   JDKs and want to pick a specific one.

2. **Install Minecraft 1.21.11 via the official Minecraft Launcher.**
   Open the official launcher, add a 1.21.11 installation, and press Play once.
   This downloads the game files Fox Launcher will read. You don't need to play
   through the official launcher again after this.

3. **Install Fabric Loader 0.18.6 or newer.**
   Run the [Fabric Installer](https://fabricmc.net/use/installer/), select
   Minecraft 1.21.11, and click Install. This creates a `fabric-loader-*`
   profile that Fox Launcher picks up automatically.

**Running the launcher from source:**

4. Make sure **Node.js 20+** is installed
   (`node --version` should print `v20.*` or higher).

5. Clone or download this repo, then open a terminal in the repo root:
   ```bash
   cd launcher
   npm install
   npm start
   ```
   The launcher window opens. That's it.

**Or run a packaged build (no Node.js required):**

4. Download the latest `.exe` (Windows), `.dmg` (macOS), or `.AppImage`
   (Linux) from the [GitHub Releases page](../../releases).

5. Run the installer / open the app image. No Node.js or npm needed for
   packaged builds.

**First launch:**

6. Click **Sign In**. A browser tab opens with a Microsoft login page and a
   short device code. Enter the code, log in with the Microsoft account linked
   to your Minecraft purchase, and switch back to the launcher — it completes
   automatically.

7. *(Optional)* Open **Settings** to adjust RAM allocation, select a custom
   Java path, or change your game directory if it isn't in the default location.

8. Click **Play**. The launcher downloads the latest Kitsune jar if needed,
   injects it into your `mods/` folder, and launches the game. Switch to the
   **Logs** tab to watch game output in real time.

> For developer setup (DevTools mode, packaging a distributable) see
> [`launcher/README.md`](launcher/README.md).

---

## Key bindings

| Key | Action |
|---|---|
| `]` | Open Fox Menu |
| `Right Shift` | Open ClickGUI |
| `[` | Open HUD editor |
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

Place your screenshots in `docs/screenshots/`:

- `title.png` — Fox-themed title screen with starry sky + mascot
- `clickgui.png` — Tabbed ClickGUI with search + profiles
- `hud-editor.png` — Drag-and-drop HUD layout editor
- `hud-overlay.png` — In-game overlay (coords, potion timers, paper doll)

## Project layout

```
src/main/java/dev/kitsune/client/
├── KitsuneClient.java              ← ClientModInitializer entrypoint
├── PreLaunchBootstrap.java         ← PreLaunchEntrypoint (mod-jar swaps)
├── core/                           ← Config, profiles, profile IO
├── module/                         ← 41 modules across 9 categories
│   ├── combat/  movement/  render/ hud/  chat/
│   └── misc/    cosmetic/  optimisation/
├── features/                       ← Legacy "FoxFeature" QoL layer (wrapped as modules)
├── hud/                            ← HudManager + widgets + editor
├── gui/clickgui/                   ← ClickGUI screen + panels
├── setting/                        ← Boolean/Slider/Color/Mode/Keybind/String
├── server/                         ← Per-server rules + auto-reconnect
├── event/                          ← Internal pub/sub bus
├── screen/                         ← Fox menu, starry sky, mascot
└── mixin/                          ← Minecraft integration
```

## License

MIT — see [LICENSE](LICENSE).

## Acknowledgements

The profile system, config hot-swap, and screen patterns were generalised
from the prior internal `modeswitch` mod that lived in this repo.

Starry sky, fox mascot, and in-menu visuals are pure procedural rendering —
no external textures except the user-supplied `fox_head.png` in
`assets/kitsune/textures/gui/`.
