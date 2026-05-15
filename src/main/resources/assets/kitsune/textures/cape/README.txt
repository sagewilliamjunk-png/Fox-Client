Drop your cape PNGs here.

Format: standard Minecraft cape sheet — RGBA PNG, 2:1 width:height ratio.
Recommended resolution: 256x128 (4× vanilla, noticeably sharper). Vanilla
minimum is 64x32 — any power-of-two multiple of that ratio works fine since
the renderer uses normalised UV coords, not hardcoded pixel offsets.
Front face is the left half; back face is the right half. The OptiFine cape
guide is the easiest reference if you've never made one.

For each PNG:
  1. Save it here as <id>.png   (id = lowercase letters / digits / _ / -)
  2. Register it in ../cosmetic-owners.json under "capes":
       "<id>": { "displayName": "Pretty Name" }
  3. Grant ownership by listing it under a UUID in "owners":
       "<player-uuid>": ["<id>"]

The mod picks up new cape entries on the next resource reload (F3+T).
