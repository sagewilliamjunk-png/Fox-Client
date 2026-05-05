Drop your cape PNGs here.

Format: standard Minecraft 1.8+ cape sheet — 64x32 RGBA PNG. Front face is
the left half (0,0) to (32,32); back face is the right half. The Mojang
"OptiFine cape" guide is the easiest reference if you've never made one.

For each PNG:
  1. Save it here as <id>.png   (id = lowercase letters / digits / _ / -)
  2. Register it in ../cosmetic-owners.json under "capes":
       "<id>": { "displayName": "Pretty Name" }
  3. Grant ownership by listing it under a UUID in "owners":
       "<player-uuid>": ["<id>"]

The mod picks up new cape entries on the next resource reload (F3+T).
