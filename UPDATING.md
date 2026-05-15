# Updating to a New Minecraft Version

> **For AI assistants:** Read this entire file before touching any code.
> Every section exists because a real mistake was made or nearly made.
> Do not skip sections or assume you already know the answer.

---

## Fill in before starting

When a human hands you this file, they will give you a version number.
Fill it in here and refer to these values throughout the whole task:

```
NEW_MC_VERSION  = _______________   (e.g. 26.2.0  or  1.22.1)
OLD_MC_VERSION  = _______________   (currently 26.1.2 — confirm in gradle.properties)
```

---

## Part 1 — Questions you MUST answer before writing any code

Answer every question below. If you cannot answer one, **stop and ask the human**
rather than guessing. Wrong guesses here cascade into broken launches and
confusing crashes that are hard to diagnose.

### 1.1  Is the version string exactly right?

Minecraft has two naming formats in use:
- **Old format:** `1.21.11`, `1.20.4` — major.minor.patch
- **New year-based format:** `26.1.2`, `25.2.0` — YY.minor.patch  
  (Mojang switched starting with 2025/2026 releases)

The version string used in this codebase **must exactly match** what Mojang's
own version manifest and Modrinth use. Even one digit wrong means:
- Fabric refuses to install ("profile not found")
- Modrinth returns empty mod lists (no mods install)
- The purge regex leaves old mods in place (launch crash)

**How to confirm the exact string:**
```
https://launchermeta.mojang.com/mc/game/version_manifest_v2.json
```
Find the version in the `versions` array and copy `id` exactly.

### 1.2  Does Fabric Loader support this version yet?

```
https://meta.fabricmc.net/v2/versions/loader/<NEW_MC_VERSION>/<any_loader_version>/profile/json
```
If this returns a 404, Fabric hasn't released support yet. **Do not update.**
Launching without Fabric means no mods at all.

The current loader version (in `gradle.properties`) is the stable one; it
usually works on new MC versions without bumping it, but verify:
```
https://meta.fabricmc.net/v2/versions/loader
```
Find the first entry where `"stable": true`. That is the loader version to use.

### 1.3  Do the essential mods have a release for this version?

Check each slug on Modrinth. The launcher auto-installs these:

| Mod            | Modrinth slug    | Required? |
|----------------|------------------|-----------|
| Sodium         | `sodium`         | Yes       |
| Lithium        | `lithium`        | Yes       |
| FerriteCore    | `ferrite-core`   | Yes       |
| MemoryLeakFix  | `memoryleakfix`  | Yes       |
| ImmediatelyFast| `immediatelyfast`| Yes       |
| EntityCulling  | `entityculling`  | Yes       |
| Iris Shaders   | `iris`           | No (optional) |

For each slug, query:
```
https://api.modrinth.com/v2/project/<slug>/version?game_versions=["<NEW_MC_VERSION>"]&loaders=["fabric"]
```
If a required mod returns `[]` (empty array), that mod has **no release** for
this version yet. Note which mods are missing — they will not be installed
and the launcher should handle this gracefully (it logs `no-version` and moves
on). This is acceptable. What is NOT acceptable is installing the wrong version
(see Part 3 — Common Mistakes).

### 1.4  Is a new Fabric API version needed?

The Fabric API jar goes in `mods/` and is version-specific. Check:
```
https://api.modrinth.com/v2/project/P7dR8mSH/version?game_versions=["<NEW_MC_VERSION>"]&loaders=["fabric"]
```
Note the `version_number` of the first result. This is what gets auto-installed
at launch. You do not hardcode it — the launcher fetches it at runtime — but
confirm a release exists.

For `gradle.properties` (the mod's build), you need to set `fabric_api_version`
to the version string Modrinth returns (e.g. `0.148.2+26.1.2`).

### 1.5  Did the Java version requirement change?

Mojang occasionally raises the minimum Java version with new MC releases.
Check the new version's `wiki.vg` page or Mojang's release notes.
- MC 1.20.5+ requires Java 21
- As of 26.x, Java 21 is still the baseline

If the requirement changed, update `REQUIRED_MAJOR` in
`launcher/src/main/java.js`.

### 1.6  Does the mod itself compile against the new MC version?

The Kitsune client mod (in `src/`) is Fabric mod code written against Minecraft
internals. Before updating the launcher's target version, confirm either:
- The mod has already been updated for `NEW_MC_VERSION` (check `gradle.properties`)
- You are intentionally launching the new version with plain Fabric only
  (the launcher supports this — `clientSupported = false` when the version
  doesn't match `TARGET_MC_VERSION`)

**Do NOT set `TARGET_MC_VERSION` to a version the mod hasn't been built for
unless you explicitly want "Fabric-only" mode for that version.**

---

## Part 2 — Files to change (and what to change in each)

Only change these files. Do not touch other files unless a compile error or
runtime failure forces it — and document why if you do.

### 2.1  `gradle.properties` (mod build — root of repo)

```properties
minecraft_version=<NEW_MC_VERSION>
loader_version=<FABRIC_LOADER_VERSION>      # from question 1.2
fabric_api_version=<FABRIC_API_VERSION>     # from question 1.4
```

Leave `mod_version`, `maven_group`, `archives_base_name`, and Gradle settings
alone unless there is a specific reason to change them.

### 2.2  `launcher/src/main/launcher.js`

```js
const TARGET_MC_VERSION = '<NEW_MC_VERSION>';
```

This is the single source of truth for which MC version the Fox Client jar
targets. All version comparison logic derives from this constant.
Do not hardcode the version string anywhere else.

### 2.3  `launcher/src/main/java.js` — only if Java requirement changed

```js
const REQUIRED_MAJOR = 21;  // bump only if Mojang raised the bar
```

### 2.4  Nothing else in the launcher needs changing for a routine version bump.

The following adapt automatically at runtime:
- Fabric installer fetches the profile JSON for `TARGET_MC_VERSION`
- `recommendedMods.js` queries Modrinth with the new version string
- `fabricInstaller.js` fetches the correct Fabric API jar
- The purge logic uses `TARGET_MC_VERSION` when cleaning old mod jars

---

## Part 3 — Common AI mistakes (read carefully)

These are mistakes AI assistants have made on this codebase before.
Each one caused a real launch failure or a confusing bug.

### ❌ Mistake 1: Using the wrong version string format

**Wrong:** Setting `TARGET_MC_VERSION = '26.1'` or `'26.1.2.0'`  
**Right:** Copy the string character-for-character from Mojang's manifest.

The version string flows into:
- Fabric API URLs (wrong string → 404 → no Fabric API installed)
- Modrinth queries (wrong string → empty result → mods skipped silently)
- Filename comparisons in the purge regex (wrong string → old mods not removed → launch crash)

### ❌ Mistake 2: Changing the version before checking mod support

Updating `TARGET_MC_VERSION` when essential mods have no release yet means:
- The purge deletes the old (working) versions of those mods
- The installer finds nothing for the new version
- The user launches with fewer mods than before, possibly crashing

**Rule:** If fewer than 4 of the 6 required mods have a release, wait. Tell
the human which mods are missing so they can decide.

### ❌ Mistake 3: Updating the launcher but not the mod (or vice versa)

`TARGET_MC_VERSION` in `launcher.js` and `minecraft_version` in `gradle.properties`
must always match, OR you must understand that they intentionally differ
(Fabric-only mode). If they differ accidentally, the client jar will either:
- Refuse to load (Fabric version mismatch crash)
- Load but behave incorrectly against the wrong mappings

After any change, verify both files agree:
```
launcher/src/main/launcher.js   →  TARGET_MC_VERSION = 'X.Y.Z'
gradle.properties               →  minecraft_version=X.Y.Z
```

### ❌ Mistake 4: Assuming the purge regex handles new filename patterns

Modrinth mod filenames embed the MC version in various patterns:
```
immediatelyfast-1.14.2+1.21.11.jar   → +1.21.11
sodium-fabric-0.6.12+mc1.21.1.jar   → +mc1.21.1
lithium-fabric-mc1.21.11-0.14.7.jar → -mc1.21.11
memoryleakfix-fabric+mc-1.21.1.jar  → +mc-1.21.1
```
The purge regex in `launcher.js:purgeIncompatibleMods` must match all of these.
After a version bump, **check** that existing mods in `mods/` would be correctly
identified and removed. If a new mod naming convention appears, update the regex.

Current regex (confirm it still covers new mods):
```js
const m = f.match(/\+m?c?[-_]?(\d+\.\d+)/) ||
           f.match(/[-_]mc(\d+\.\d+)/i);
```

### ❌ Mistake 5: Hardcoding the version in more than one place

Search for the old version string before finishing:
```
grep -r "OLD_MC_VERSION" launcher/src/
```
If it appears anywhere other than `launcher.js:TARGET_MC_VERSION`, that is a bug.
Version-specific strings must not be scattered across the codebase.

### ❌ Mistake 6: Not verifying Fabric loader compatibility

A new MC version sometimes ships before Fabric Loader supports it. Trying to
install an unsupported version will make `fabricInstaller.js` throw an HTTP 404,
which is caught and falls back to vanilla. The user sees a confusing
"Launching without Fabric" message with no mods. Always verify Fabric support
first (question 1.2).

### ❌ Mistake 7: Forgetting that snapshots/pre-releases use different version strings

If `NEW_MC_VERSION` is a snapshot (e.g. `26w14a`, `1.22-pre1`), be aware:
- The version row in profile settings categorises it as a "pre-release" not a release
- Mods rarely have snapshot builds; almost all will return `no-version`
- The Fabric loader may only have a beta build for it
- `TARGET_MC_VERSION` should generally only be set to **stable releases**

Snapshots are fine to install and play manually; they should not be the new
`TARGET_MC_VERSION` until Mojang marks the version as a stable release.

### ❌ Mistake 8: Running the build before mod updates are done

If you update `gradle.properties` to a new MC version, `./gradlew build` will
likely fail until the Kitsune mod source is also updated for that version's API.
Do not tell the user the update is complete until either:
1. The mod compiles and the jar is built, OR
2. You have explicitly scoped the update to "launcher only, Fabric-only mode"

---

## Part 4 — Step-by-step process

Follow in order. Do not skip steps.

```
[ ] 1. Fill in NEW_MC_VERSION and OLD_MC_VERSION at the top of this file.

[ ] 2. Answer all questions in Part 1.
       If any answer is "I don't know" or a question cannot be answered
       from public sources, STOP and ask the human.

[ ] 3. Check mod availability (1.3). List which required mods have releases
       and which do not. Show this list to the human before proceeding.

[ ] 4. Update gradle.properties:
         minecraft_version
         loader_version      (if changed)
         fabric_api_version

[ ] 5. Update launcher/src/main/launcher.js:
         TARGET_MC_VERSION

[ ] 6. Update launcher/src/main/java.js if Java requirement changed.

[ ] 7. Verify both version strings match (gradle.properties ↔ launcher.js).

[ ] 8. Search for the old version string:
         grep -r "OLD_MC_VERSION" launcher/src/
       Fix any stray occurrences.

[ ] 9. If the mod source needs updating (new MC API changes), do that now.
       Otherwise, note that the Fox Client jar will not be installed for
       the new version until the mod is rebuilt.

[ ] 10. Build the mod:
          cd <repo-root>
          ./gradlew build
        Fix any compile errors. Common causes:
          - Renamed Minecraft methods (check Fabric's migration guide)
          - Removed or changed Fabric API hooks
          - Yarn mappings changes

[ ] 11. Test the launcher:
          cd launcher
          npm start
        Walk through this checklist:
          [ ] Home screen loads without errors
          [ ] Java badge shows correctly
          [ ] Fabric is installed automatically on first PLAY
          [ ] Mod jars for the new version are downloaded
          [ ] Old version mods are purged (check mods/ folder)
          [ ] Game launches and reaches the main menu
          [ ] Fox Client features work (if mod was rebuilt)

[ ] 12. If step 10 or 11 fails, diagnose before reporting success.
        Do not tell the human it is done until the game actually launches.
```

---

## Part 5 — Version string reference

| Concept                  | Where it lives                                      | Format example   |
|--------------------------|-----------------------------------------------------|------------------|
| MC version (mod build)   | `gradle.properties` → `minecraft_version`           | `26.1.2`         |
| MC version (launcher)    | `launcher/src/main/launcher.js` → `TARGET_MC_VERSION` | `26.1.2`       |
| Fabric loader version    | `gradle.properties` → `loader_version`              | `0.19.2`         |
| Fabric API (mod build)   | `gradle.properties` → `fabric_api_version`          | `0.148.2+26.1.2` |
| Fabric API (launcher)    | Fetched at runtime from Modrinth — not hardcoded    | —                |
| Recommended mods         | Fetched at runtime from Modrinth — not hardcoded    | —                |

The launcher fetches Fabric loader, Fabric API, and all recommended mods
dynamically at launch time. **None of their versions are hardcoded** in the
launcher source. Only `TARGET_MC_VERSION` needs to change in the launcher.

---

## Part 6 — What "done" looks like

The update is complete when ALL of the following are true:

1. `gradle.properties` and `launcher.js:TARGET_MC_VERSION` contain the new
   version string and they match each other.
2. `./gradlew build` finishes without errors (or you have documented why the
   mod build is intentionally deferred).
3. `npm start` in `launcher/` shows the new version on the home screen.
4. Clicking PLAY installs Fabric for the new version automatically.
5. The mods directory contains jars for the new MC version, not the old one.
6. The game reaches the main menu without an "Incompatible mods" crash.

If any of these is false, the update is not done.
