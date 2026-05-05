# Fox Launcher

A desktop launcher for the **Kitsune** Minecraft client.

Built with Electron, vanilla JS, and zero runtime dependencies beyond Electron itself.

## Requirements

- **Node.js 20+** and **npm** (only for building/running the launcher — end users won't need these once it's packaged)
- **Java 21+** (Minecraft 1.20.5+ requires it — the launcher auto-detects but can't install Java for you)
- **Minecraft 1.21+ installed via the official launcher**. Fox Launcher reads existing version metadata from `<game dir>/versions/` to build the launch command (same approach as Prism Launcher / MultiMC). You don't need to use the official launcher to play — just to initially download the version.

## Setup

```bash
cd launcher
npm install
npm start
```

For development (opens DevTools automatically):

```bash
npm run dev
```

For packaging into a distributable `.exe` / `.dmg` / `.AppImage`:

```bash
npm run dist
```

## How it works

### Data layout

All launcher state lives in `~/.foxlauncher/`:

| Path | Contents |
| --- | --- |
| `settings.json`     | User preferences (Java path, RAM, game dir, resolution, auto-update, etc.) |
| `auth.json`         | Cached Microsoft + Minecraft auth tokens (mode 0600) |
| `profiles.json`     | User-created client profiles |
| `versions/`         | Downloaded Kitsune client jars, one subdir per release tag |
| `versions/manifest.json` | Metadata about which jar is currently installed |
| `logs/`             | (Reserved) rotated game stdout/stderr |
| `cache/`            | (Reserved) future caching |

### Authentication

Uses the **OAuth 2.0 device code flow**. No embedded browser, no redirects — the user opens a URL, types a short code, and the launcher polls for completion. Then the launcher exchanges tokens through:

1. Microsoft → Xbox Live
2. Xbox Live → XSTS
3. XSTS → Minecraft
4. Minecraft access token → profile (UUID, username)

Refresh tokens are used silently on subsequent launches. Client ID is the public Mojang / Xbox Live launcher client ID used by every open-source Minecraft launcher.

### Java detection

Checked in order:

1. The user's configured `javaPath` (settings)
2. `JAVA_HOME`
3. Platform-specific common install locations (Program Files on Windows, `/Library/Java/JavaVirtualMachines` on macOS, `/usr/lib/jvm` on Linux)
4. `java` on `PATH` as a last resort

Each candidate is probed via `java -version`; the highest major version wins. If nothing ≥ Java 21 is found, the launch is blocked with a clear error.

### Minecraft launch

The launcher:

1. Resolves the user's target version JSON from `<gameDir>/versions/<id>/<id>.json`
2. Walks any `inheritsFrom` chain (Fabric / Quilt profiles)
3. Evaluates OS / feature rules for libraries and arguments
4. Builds the classpath from `libraries/`
5. Substitutes `${auth_player_name}`, `${auth_access_token}`, `${classpath}`, `${natives_directory}`, etc.
6. Adds G1 GC flags and the user's `-Xms` / `-Xmx` settings
7. Spawns Java and streams stdout/stderr into the logs tab

### Client updates

The launcher polls the configured GitHub repo's **latest release** and downloads the first asset matching `kitsune-client*.jar`. The file is cached in `~/.foxlauncher/versions/<tag>/`, then copied into `<gameDir>/mods/kitsune-client.jar` at launch time (replacing any older jar with the same name prefix).

- Retries on transient failures with exponential backoff (3 attempts)
- Tracks the installed tag + asset id in `versions/manifest.json` — re-downloads are skipped when up-to-date
- Auto-update can be disabled in Settings

## Architecture

```
launcher/
├── package.json
├── src/
│   ├── main/                  # Node-land; runs in Electron main process
│   │   ├── index.js           # App + BrowserWindow lifecycle
│   │   ├── ipc.js             # All IPC handlers
│   │   ├── paths.js           # ~/.foxlauncher/ resolution
│   │   ├── settings.js        # Atomic JSON persistence
│   │   ├── auth.js            # MSA device-code flow → MC token
│   │   ├── java.js            # Java detection + probing
│   │   ├── mcVersion.js       # Version JSON parse + command build
│   │   ├── launcher.js        # Spawn + log streaming + mod install
│   │   ├── updater.js         # GitHub release poll + download
│   │   └── logs.js            # Shared ring buffer + pub-sub
│   ├── preload/
│   │   └── index.js           # contextBridge exposing window.fox.*
│   └── renderer/              # Browser-land; zero framework
│       ├── index.html
│       ├── styles.css
│       ├── app.js             # Hash routing + shell
│       ├── screens/
│       │   ├── home.js
│       │   ├── play.js
│       │   ├── settings.js
│       │   ├── versions.js
│       │   ├── profiles.js
│       │   └── logs.js
│       └── assets/
│           └── fox.png
```

### Security posture

- Renderer runs with `contextIsolation: true`, `nodeIntegration: false`, `sandbox: false`
- Only the whitelisted IPC channels in `ipc.js` are callable from the renderer
- CSP restricts renderer to same-origin for scripts/styles/images
- External links always open in the system browser, never inside the app
- `auth.json` is written with mode `0600` (owner-only read/write) on POSIX

## Error handling

| Scenario | Behavior |
| --- | --- |
| No Java installed | Launch blocked, Settings + Play screens show `Java not found` |
| Java is too old | Launch blocked with `Java 21+ required` message |
| No Minecraft install | Play screen shows `Game directory not found — install Minecraft via the official launcher first` |
| No version installed | `No versions found — install one via the official launcher` |
| MSA account has no Xbox profile | `Create an Xbox profile at xbox.com, then try again` |
| MSA child account | `Add to a family group to sign in` |
| Doesn't own Minecraft | `This Microsoft account does not own Minecraft Java Edition` |
| Download fails | Retries 3× with exponential backoff, then surfaces the error |
| Refresh token expired | Silently falls back to interactive login |

## Keyboard & window behavior

- The sidebar header is a draggable region (you can drag the window by grabbing it)
- Hash-based routing means `#home`, `#play`, `#settings` etc. survive reload
- Window closing: if a game is running and `keepLauncherOpen` is on, the app keeps running

## Known limitations

- **No asset/library downloading.** The launcher doesn't download vanilla Minecraft itself — it reads what the official launcher already installed. This is intentional: vanilla asset download is a ~1000-LoC subsystem with its own manifest format and is better handled by the official launcher.
- **No native Fabric / Forge installation.** If you need Fabric loader, run the official Fabric installer once — Fox Launcher then picks up the `<gameDir>/versions/fabric-loader-*/` profile automatically.
- **No microphone / narrator features.** These exist in vanilla only.
- **Windows-only packaging tested manually.** macOS / Linux packaging is configured in `package.json > build` but unverified.

## Development notes

- No bundler, no TypeScript, no React. Each renderer screen is a plain ESM module that exports a `render(mount)` function.
- Main process uses `require()` (CommonJS); renderer uses `import` (ESM) because `<script type="module">` works directly.
- Adding a new IPC channel: declare the handler in `src/main/ipc.js`, expose it in `src/preload/index.js`, call it via `window.fox.*` from the renderer.
- Adding a new screen: create `src/renderer/screens/foo.js` exporting `renderFoo(mount)`, add it to the `ROUTES` map in `app.js`, and add a nav button in `index.html`.
