# Fox Client — Safety Statement

Fox Client is built with one hard rule: **it never gives the player a mechanical advantage that vanilla Minecraft does not.**

## Things Fox Client will never ship

The following modules are not just disabled — they are **not present in the codebase**. There is no hidden config flag, no opt-in, no developer build that turns them on. Adding them would require a fork.

- Reach extension / hitbox extension
- KillAura, AimAssist, AutoAim, TriggerBot
- ChestESP, X-Ray, OreFinder, EntityESP
- AutoClicker, AutoTotem, AutoArmor, AutoSoup
- KeepSprint, NoSlowdown, Velocity, FastBow
- Fly, Speed, Step, Scaffold, Tower
- Packet manipulation, NoFall, AntiKnockback
- Anything that opens a backdoor for server-side cheat modules

## Things Fox Client *does* ship that some servers ban individually

Some servers consider these unfair even though they don't manipulate gameplay. Fox Client ships them but lets you (and the per-server rule system) disable them per server:

- **Zoom** — purely a camera FOV change. Banned by some PvP / hide-and-seek servers.
- **Full Brightness** — gamma override. Banned by some hardcore / horror servers.
- **Armor HUD** — visual overlay only. Banned by some competitive UHC events.
- **Minimap** (shipped in v1.3) — renders only terrain and entities the client
  already knows about; no chunk probing, no hidden-entity reveal. Some
  competitive servers ban minimaps outright — use the per-server rules.
- **Freecam** (NOT shipped; would be subject to a default DISABLE rule on most major servers).

Four further modules are **addon-gated gray zone**: Free Look, Reach Display
HUD, Hitboxes, and Anti-AFK. Each is gated behind a per-profile addon flag the
launcher writes — when the flag is off the module is never even constructed.

### Automation modules — where we draw the line

Two shipped modules simulate player input, and the distinction from the
banned list above matters:

- **Auto Eat** *(Player)* — switches to a food slot and holds the vanilla
  use key when hunger is low. It automates a non-combat maintenance chore at
  vanilla speed; it cannot click faster than a human, gives no combat
  advantage, and skips golden apples by default. This is materially different
  from AutoSoup/AutoClicker (combat-speed advantages), which stay banned.
  Some servers still consider any input automation unfair — disable it
  per-server if in doubt.
- **Anti-AFK** *(Movement, addon-gated)* — periodic camera nudge / jump /
  sneak to defeat idle kicks. This is movement automation and several servers
  explicitly ban it, which is why it is double-gated: the addon flag must be
  on **and** the user must enable an explicit "I accept the risk" setting,
  otherwise enabling it is a no-op with a warning toast.
- **Toggle Sprint** *(Movement)* — auto-holds sprint (and optionally sneak), the
  same convenience Lunar/Badlion ship and that vanilla exposes as an
  accessibility option. The large majority of servers allow it. The one thing
  that *would* cross the line — re-asserting sprint the tick after a hit to
  defeat the knockback sprint-reset, which is exactly why **KeepSprint** was
  removed — is explicitly prevented: Toggle Sprint never sets the sprint flag
  while `hurtTime > 0`, so it cannot act as a sprint-reset bypass.

**KeepSprint was removed in v1.5.0.** It shipped briefly in v1.4.1 by
mistake — it is on the never-ship list above (sprint-reset bypass is exactly
the kind of mechanical advantage this document promises we don't ship, and
GrimAC-class anti-cheats flag it). The module class was deleted, not
disabled.

The default `server_rules.json` ships with starter rules for the largest servers, but Fox Client makes **no guarantee** that the rules are complete or up to date with each server's current TOS. **You are responsible for verifying that your installed mods comply with each server's rules.** Fox Client is a tool to make compliance easier, not a substitute for reading the rules.

## How the per-server toggle system works

When you click "Connect" on a server in the multiplayer list:

1. Fox Client checks `config/kitsune/server_rules.json` for any rule whose host pattern matches the server.
2. For each rule with `action: DISABLE`:
   - Mod IDs in `modIds` are checked against currently-loaded Fabric mods.
   - Feature IDs in `featureIds` are added to a runtime override set (no restart needed for these).
3. If any matching mod IDs are currently loaded, Fox Client shows the **Restart Confirm Screen** instead of connecting.
4. If you accept, Fox Client:
   - Queues the mod-jar moves to a pending file in `mods/`.
   - Persists the target server address.
   - Closes Minecraft.
5. On your **next launch**, Fox Client's PreLaunch entrypoint runs *before* Fabric loads any mods, processes the pending file, and physically moves the JARs into `mods/.foxclient-disabled/`.
6. You're auto-reconnected to the saved server (unless you turned that off in `config.json`).

To re-enable disabled mods, switch to a profile that doesn't disable them, or use the Fox menu's mod toggle UI (which queues an `ENABLE` move and triggers another restart).

## What if a server bans something Fox Client doesn't disable for it?

Two options:

1. **Add a rule yourself.** Open `config/kitsune/server_rules.json` and add an entry. The format is documented in `ServerRule.java`. Or use the in-game Server Rules editor when it lands in v0.2.
2. **File an issue / PR** with the server name + their published mod policy. Default rules are kept conservative — if a major server publishes a clear ban list, we'll merge it.

## What if Fox Client gets me banned anyway?

Fox Client is provided as-is, with no warranty. Server bans are between you and the server. The codebase is open enough that you can audit any feature module and verify what it does — every feature has javadoc explaining its server-safety classification.
