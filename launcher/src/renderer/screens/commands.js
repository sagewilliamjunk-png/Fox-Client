// Command Generator screen — an MCStacker-style builder for Minecraft commands.
//
// Pure renderer feature: no IPC, no main-process work. Every command is
// defined declaratively as a list of fields + a `build(values)` function that
// assembles the final command string. The output updates live on every input.
//
// Syntax target: modern Java Edition (1.21.x / data-component item format).
// The most-used commands (give/summon/effect/tp/setblock/fill/gamemode/time/
// weather/etc.) are version-stable; the item-component bits (custom name,
// enchantments, unbreakable) use the 1.21.5+ component syntax.
//
// Architecture:
//   COMMANDS         registry of command definitions
//   fieldHtml(f)     renders one field's HTML by type
//   readField(...)   reads one field's value back out of the DOM
//   build pipeline   collectValues() -> cmd.build() -> output box

// ── shared suggestion data ──────────────────────────────────────────────────
// Curated, not exhaustive. Users can type any namespaced id; these just drive
// the autocomplete <datalist>s so the common cases are one keystroke away.

const ITEMS = [
  'diamond_sword','diamond_pickaxe','diamond_axe','diamond_shovel','diamond_hoe',
  'netherite_sword','netherite_pickaxe','netherite_axe','netherite_helmet',
  'netherite_chestplate','netherite_leggings','netherite_boots',
  'diamond_helmet','diamond_chestplate','diamond_leggings','diamond_boots',
  'iron_sword','iron_pickaxe','iron_axe','iron_ingot','iron_block',
  'gold_ingot','gold_block','diamond','diamond_block','emerald','netherite_ingot',
  'elytra','shield','bow','crossbow','trident','fishing_rod','flint_and_steel',
  'totem_of_undying','ender_pearl','ender_eye','blaze_rod','blaze_powder',
  'experience_bottle','enchanted_golden_apple','golden_apple','golden_carrot',
  'cooked_beef','bread','cake','cookie','potion','splash_potion','lingering_potion',
  'tipped_arrow','spectral_arrow','arrow','firework_rocket','firework_star',
  'tnt','obsidian','beacon','conduit','shulker_box','ender_chest','chest','barrel',
  'crafting_table','furnace','anvil','enchanting_table','grindstone','smithing_table',
  'oak_log','oak_planks','cobblestone','stone','dirt','grass_block','sand','glass',
  'redstone','redstone_block','repeater','comparator','observer','piston','sticky_piston',
  'water_bucket','lava_bucket','milk_bucket','bucket','torch','lantern','glowstone',
  'name_tag','lead','saddle','spawner','command_block','structure_block','barrier',
  'written_book','writable_book','book','paper','map','clock','compass','spyglass',
  'music_disc_pigstep','dragon_head','player_head','nether_star','heart_of_the_sea',
  'wither_skeleton_skull','phantom_membrane','echo_shard','recovery_compass','mace',
];

const BLOCKS = [
  'stone','granite','diorite','andesite','deepslate','cobblestone','cobbled_deepslate',
  'dirt','grass_block','podzol','sand','red_sand','gravel','clay','mud','snow_block',
  'oak_log','oak_planks','spruce_planks','birch_planks','dark_oak_planks',
  'obsidian','crying_obsidian','bedrock','glass','tinted_glass','white_stained_glass',
  'iron_block','gold_block','diamond_block','emerald_block','netherite_block',
  'redstone_block','lapis_block','coal_block','copper_block','amethyst_block',
  'glowstone','sea_lantern','shroomlight','redstone_lamp','beacon','conduit',
  'crafting_table','furnace','blast_furnace','smoker','barrel','chest','ender_chest',
  'hopper','dropper','dispenser','observer','piston','sticky_piston','slime_block',
  'honey_block','tnt','target','note_block','jukebox','lectern','bell','lodestone',
  'command_block','chain_command_block','repeating_command_block','structure_block',
  'barrier','light','spawner','sculk','sculk_catalyst','sculk_shrieker','sculk_sensor',
  'water','lava','air','cave_air','fire','soul_fire','soul_sand','soul_soil','magma_block',
  'netherrack','nether_bricks','blackstone','basalt','end_stone','purpur_block',
  'white_wool','white_concrete','white_terracotta','white_bed','torch','soul_torch',
];

const ENTITIES = [
  'allay','armadillo','armor_stand','axolotl','bat','bee','blaze','bogged','breeze',
  'camel','cat','cave_spider','chicken','cod','cow','creaking','creeper','dolphin',
  'donkey','drowned','elder_guardian','ender_dragon','enderman','endermite','evoker',
  'fox','frog','ghast','glow_squid','goat','guardian','hoglin','horse','husk',
  'iron_golem','llama','magma_cube','mooshroom','mule','ocelot','panda','parrot',
  'phantom','pig','piglin','piglin_brute','pillager','polar_bear','pufferfish',
  'rabbit','ravager','salmon','sheep','shulker','silverfish','skeleton','skeleton_horse',
  'slime','sniffer','snow_golem','spider','squid','stray','strider','tadpole','trader_llama',
  'tropical_fish','turtle','vex','villager','vindicator','wandering_trader','warden',
  'witch','wither','wither_skeleton','wolf','zoglin','zombie','zombie_villager',
  'zombified_piglin','tnt','falling_block','item','experience_orb','arrow','fireball',
  'lightning_bolt','area_effect_cloud','marker','interaction','text_display','item_display',
  'block_display','boat','chest_boat','minecart','chest_minecart','tnt_minecart',
];

const EFFECTS = [
  'speed','slowness','haste','mining_fatigue','strength','instant_health','instant_damage',
  'jump_boost','nausea','regeneration','resistance','fire_resistance','water_breathing',
  'invisibility','blindness','night_vision','hunger','weakness','poison','wither',
  'health_boost','absorption','saturation','glowing','levitation','luck','unluck',
  'slow_falling','conduit_power','dolphins_grace','bad_omen','hero_of_the_village',
  'darkness','trial_omen','raid_omen','wind_charged','weaving','oozing','infested',
];

const ENCHANTS = [
  'protection','fire_protection','feather_falling','blast_protection','projectile_protection',
  'respiration','aqua_affinity','thorns','depth_strider','frost_walker','binding_curse',
  'soul_speed','swift_sneak','sharpness','smite','bane_of_arthropods','knockback',
  'fire_aspect','looting','sweeping_edge','efficiency','silk_touch','unbreaking','fortune',
  'power','punch','flame','infinity','luck_of_the_sea','lure','loyalty','impaling',
  'riptide','channeling','multishot','quick_charge','piercing','density','breach',
  'wind_burst','mending','vanishing_curse',
];

const PARTICLES = [
  'flame','smoke','large_smoke','cloud','explosion','explosion_emitter','firework',
  'heart','angry_villager','happy_villager','crit','enchanted_hit','portal','dragon_breath',
  'end_rod','dripping_water','dripping_lava','splash','bubble','rain','note','poof',
  'lava','soul','soul_fire_flame','ash','crimson_spore','warped_spore','sculk_soul',
  'sculk_charge_pop','electric_spark','wax_on','wax_off','scrape','glow','snowflake',
  'dust','dust_color_transition','falling_dust','totem_of_undying','witch','spit',
];

const SOUNDS = [
  'minecraft:entity.experience_orb.pickup','minecraft:entity.player.levelup',
  'minecraft:block.note_block.pling','minecraft:block.note_block.bell',
  'minecraft:entity.ender_dragon.growl','minecraft:entity.wither.spawn',
  'minecraft:ui.button.click','minecraft:entity.item.pickup',
  'minecraft:entity.lightning_bolt.thunder','minecraft:block.bell.use',
  'minecraft:entity.firework_rocket.launch','minecraft:entity.firework_rocket.blast',
  'minecraft:block.anvil.land','minecraft:entity.villager.yes','minecraft:entity.villager.no',
  'minecraft:music_disc.pigstep','minecraft:entity.warden.sonic_boom',
  'minecraft:ambient.cave','minecraft:entity.enderman.teleport',
];

const ATTRIBUTES = [
  'max_health','movement_speed','attack_damage','attack_speed','armor','armor_toughness',
  'knockback_resistance','luck','max_absorption','jump_strength','scale','step_height',
  'gravity','safe_fall_distance','fall_damage_multiplier','block_interaction_range',
  'entity_interaction_range','burning_time','explosion_knockback_resistance','oxygen_bonus',
  'water_movement_efficiency','movement_efficiency','sneaking_speed','attack_knockback',
  'follow_range','spawn_reinforcements','tempt_range',
];

const GAMERULES = [
  'doDaylightCycle','doWeatherCycle','keepInventory','mobGriefing','doMobSpawning',
  'doFireTick','doMobLoot','doTileDrops','doEntityDrops','commandBlockOutput',
  'doImmediateRespawn','fallDamage','fireDamage','drowningDamage','naturalRegeneration',
  'randomTickSpeed','showDeathMessages','sendCommandFeedback','spawnRadius','playersSleepingPercentage',
  'doInsomnia','disableRaids','doPatrolSpawning','doTraderSpawning','maxEntityCramming',
  'doWardenSpawning','universalAnger','forgiveDeadPlayers','spectatorsGenerateChunks',
];

const STRUCTURES = [
  'village_plains','village_desert','village_savanna','village_snowy','village_taiga',
  'fortress','bastion_remnant','end_city','stronghold','mineshaft','mineshaft_mesa',
  'monument','mansion','ancient_city','trial_chambers','ruined_portal','ruined_portal_nether',
  'shipwreck','shipwreck_beached','buried_treasure','pillager_outpost','ocean_ruin_cold',
  'ocean_ruin_warm','swamp_hut','desert_pyramid','jungle_pyramid','igloo','nether_fossil',
  'trail_ruins','trial_chambers',
];

const BIOMES = [
  'plains','sunflower_plains','desert','forest','flower_forest','birch_forest','dark_forest',
  'jungle','sparse_jungle','bamboo_jungle','taiga','snowy_taiga','old_growth_pine_taiga',
  'savanna','savanna_plateau','badlands','swamp','mangrove_swamp','beach','snowy_beach',
  'ocean','deep_ocean','warm_ocean','lukewarm_ocean','cold_ocean','frozen_ocean','river',
  'frozen_river','snowy_plains','ice_spikes','mushroom_fields','meadow','cherry_grove',
  'grove','snowy_slopes','jagged_peaks','frozen_peaks','stony_peaks','dripstone_caves',
  'lush_caves','deep_dark','nether_wastes','soul_sand_valley','crimson_forest','warped_forest',
  'basalt_deltas','the_end','end_highlands','end_midlands','small_end_islands','end_barrens',
];

const LOOT_TABLES = [
  'minecraft:chests/simple_dungeon','minecraft:chests/abandoned_mineshaft',
  'minecraft:chests/desert_pyramid','minecraft:chests/jungle_temple','minecraft:chests/igloo_chest',
  'minecraft:chests/stronghold_corridor','minecraft:chests/end_city_treasure',
  'minecraft:chests/nether_bridge','minecraft:chests/bastion_treasure','minecraft:chests/woodland_mansion',
  'minecraft:chests/buried_treasure','minecraft:chests/shipwreck_treasure','minecraft:chests/village/village_weaponsmith',
  'minecraft:chests/ancient_city','minecraft:chests/trial_chambers/reward','minecraft:entities/zombie',
  'minecraft:entities/skeleton','minecraft:entities/creeper','minecraft:entities/ender_dragon',
  'minecraft:entities/wither','minecraft:entities/sheep','minecraft:gameplay/fishing',
  'minecraft:gameplay/fishing/treasure',
];

const ITEM_SLOTS = [
  'weapon.mainhand','weapon.offhand','armor.head','armor.chest','armor.legs','armor.feet',
  'horse.saddle','horse.chest','horse.armor','hotbar.0','hotbar.1','hotbar.2','hotbar.3',
  'hotbar.4','hotbar.5','hotbar.6','hotbar.7','hotbar.8','inventory.0','inventory.1',
  'container.0','container.1','container.2','enderchest.0','villager.0',
];

const DAMAGE_TYPES = [
  'generic','generic_kill','in_fire','on_fire','lava','hot_floor','in_wall','cramming',
  'drown','starve','cactus','fall','fly_into_wall','out_of_world','magic','wither','anvil',
  'falling_block','dragon_breath','sweet_berry_bush','freeze','sting','mob_attack','player_attack',
  'arrow','trident','fireball','thrown','explosion','player_explosion','sonic_boom','lightning_bolt',
];

const MOD_OPERATIONS = ['add_value', 'add_multiplied_base', 'add_multiplied_total'];
const EQUIP_SLOT_ENUM = ['any', 'mainhand', 'offhand', 'hand', 'head', 'chest', 'legs', 'feet', 'armor', 'body'];

// ── module state ──────────────────────────────────────────────────────────────

let currentCmd = 'give';
let copyResetTimer = null;

// ── entry point ─────────────────────────────────────────────────────────────

export async function renderCommands(mount) {
  if (copyResetTimer) { clearTimeout(copyResetTimer); copyResetTimer = null; }

  mount.innerHTML = `
    <div class="cmd-header">
      <h1 class="screen-title" style="margin:0;">Command Generator</h1>
      <div class="cmd-sub muted">
        Build Minecraft Java commands visually — fill the form, copy the result.
        Targets modern <strong>1.21.x</strong> syntax.
      </div>
    </div>

    <div class="cmd-layout">
      <aside class="cmd-picker-wrap">
        <input id="cmd-search" class="input cmd-search" type="text"
               placeholder="Filter commands…" autocomplete="off" spellcheck="false"
               aria-label="Filter commands" />
        <div class="cmd-picker" id="cmd-picker" role="tablist" aria-label="Command type"></div>
      </aside>

      <section class="cmd-pane">
        <div class="cmd-pane-head">
          <div>
            <div class="cmd-pane-title" id="cmd-pane-title"></div>
            <div class="cmd-pane-desc muted" id="cmd-pane-desc"></div>
          </div>
        </div>

        <form class="cmd-form" id="cmd-form" autocomplete="off" spellcheck="false"></form>

        <div class="cmd-output-wrap">
          <div class="cmd-output-label">
            <span>Generated command</span>
            <span class="cmd-output-hint muted" id="cmd-output-warn"></span>
          </div>
          <div class="cmd-output-row">
            <code class="cmd-output" id="cmd-output" tabindex="0"></code>
            <button class="btn cmd-save-btn" id="cmd-save" type="button" title="Save this command">★ Save</button>
            <button class="btn btn-primary cmd-copy-btn" id="cmd-copy" type="button" title="Copy to clipboard">Copy</button>
          </div>
          <div class="cmd-output-note muted">
            Paste into chat or a command block. Long commands (over 256 chars) need a command block.
          </div>

          <div class="cmd-saved" id="cmd-saved-wrap" hidden>
            <div class="cmd-saved-head">
              <span>Saved commands</span>
              <button class="link cmd-saved-clear" id="cmd-saved-clear" type="button">Clear all</button>
            </div>
            <div class="cmd-saved-list" id="cmd-saved-list"></div>
          </div>
        </div>
      </section>
    </div>

    <!-- shared datalists for autocomplete -->
    ${datalist('dl-items', ITEMS)}
    ${datalist('dl-blocks', BLOCKS)}
    ${datalist('dl-entities', ENTITIES)}
    ${datalist('dl-effects', EFFECTS)}
    ${datalist('dl-enchants', ENCHANTS)}
    ${datalist('dl-particles', PARTICLES)}
    ${datalist('dl-sounds', SOUNDS, /*raw*/ true)}
    ${datalist('dl-gamerules', GAMERULES, /*raw*/ true)}
    ${datalist('dl-attributes', ATTRIBUTES)}
    ${datalist('dl-structures', STRUCTURES)}
    ${datalist('dl-biomes', BIOMES)}
    ${datalist('dl-loot', LOOT_TABLES, /*raw*/ true)}
    ${datalist('dl-slots', ITEM_SLOTS, /*raw*/ true)}
    ${datalist('dl-damagetypes', DAMAGE_TYPES)}
    ${datalist('dl-selectors', ['@s', '@p', '@a', '@r', '@e'], /*raw*/ true)}
    ${datalist('dl-dimensions', ['minecraft:overworld', 'minecraft:the_nether', 'minecraft:the_end'], /*raw*/ true)}
  `;

  // Attach the live-rebuild delegation once on the persistent form node.
  // (selectCommand only swaps the form's innerHTML, so the node itself —
  // and these listeners — survive command switches without stacking.)
  const form = document.getElementById('cmd-form');
  if (form) {
    form.addEventListener('input', rebuild);
    form.addEventListener('change', onFormChange);
  }

  // Picker filter.
  const search = document.getElementById('cmd-search');
  if (search) search.addEventListener('input', () => filterPicker(search.value));

  // Save current command + saved-list controls.
  const saveBtn = document.getElementById('cmd-save');
  if (saveBtn) saveBtn.addEventListener('click', saveCurrent);
  const clearBtn = document.getElementById('cmd-saved-clear');
  if (clearBtn) clearBtn.addEventListener('click', () => {
    if (confirm('Remove all saved commands?')) { writeSaved([]); renderSaved(); }
  });

  renderPicker();
  renderSaved();
  selectCommand(currentCmd);
}

// ── picker filtering ──────────────────────────────────────────────────────────

function filterPicker(query) {
  const q = (query || '').trim().toLowerCase();
  for (const btn of document.querySelectorAll('.cmd-pick-btn')) {
    const id = btn.dataset.cmd || '';
    const c  = COMMANDS[id] || {};
    const hay = (c.name + ' ' + (c.tag || '') + ' ' + (c.desc || '')).toLowerCase();
    btn.style.display = (!q || hay.includes(q)) ? '' : 'none';
  }
}

// ── command picker (left rail) ─────────────────────────────────────────────────

function renderPicker() {
  const picker = document.getElementById('cmd-picker');
  if (!picker) return;
  picker.innerHTML = Object.entries(COMMANDS).map(([id, c]) => `
    <button class="cmd-pick-btn" data-cmd="${esc(id)}" role="tab"
            aria-selected="${id === currentCmd}">
      <span class="cmd-pick-name">/${esc(c.name)}</span>
      <span class="cmd-pick-tag muted">${esc(c.tag || '')}</span>
    </button>
  `).join('');

  for (const btn of picker.querySelectorAll('.cmd-pick-btn')) {
    btn.addEventListener('click', () => selectCommand(btn.dataset.cmd));
  }
}

function selectCommand(id) {
  if (!COMMANDS[id]) id = 'give';
  currentCmd = id;
  const cmd = COMMANDS[id];

  for (const btn of document.querySelectorAll('.cmd-pick-btn')) {
    const active = btn.dataset.cmd === id;
    btn.classList.toggle('active', active);
    btn.setAttribute('aria-selected', String(active));
  }

  const titleEl = document.getElementById('cmd-pane-title');
  const descEl  = document.getElementById('cmd-pane-desc');
  if (titleEl) titleEl.textContent = '/' + cmd.name;
  if (descEl)  descEl.textContent  = cmd.desc || '';

  const form = document.getElementById('cmd-form');
  if (!form) return;
  form.innerHTML = cmd.fields.map(fieldHtml).join('');

  wireForm(form);
  rebuild();
}

// ── field rendering ─────────────────────────────────────────────────────────

function fieldHtml(f) {
  const id   = 'f-' + f.key;
  const hint = f.hint ? `<span class="cmd-field-hint muted">${esc(f.hint)}</span>` : '';
  const label = `<label class="cmd-field-label" for="${id}">${esc(f.label)}${f.required ? ' <span class="cmd-req">*</span>' : ''}</label>`;

  switch (f.type) {
    case 'text':
    case 'suggest': {
      const list = f.list ? ` list="${esc(f.list)}"` : '';
      return wrap(f, `
        ${label}
        <input class="input cmd-input" id="${id}" data-key="${esc(f.key)}" type="text"
               placeholder="${esc(f.placeholder || '')}"${list}
               value="${esc(f.default || '')}" />
        ${hint}
      `);
    }
    case 'number': {
      const min = f.min != null ? ` min="${f.min}"` : '';
      const max = f.max != null ? ` max="${f.max}"` : '';
      const step = f.step != null ? ` step="${f.step}"` : '';
      return wrap(f, `
        ${label}
        <input class="input cmd-input" id="${id}" data-key="${esc(f.key)}" type="number"
               placeholder="${esc(f.placeholder || '')}"${min}${max}${step}
               value="${f.default != null ? esc(f.default) : ''}" />
        ${hint}
      `);
    }
    case 'select': {
      const opts = f.options.map(o => {
        const val = typeof o === 'string' ? o : o.value;
        const lbl = typeof o === 'string' ? o : o.label;
        const sel = String(val) === String(f.default) ? ' selected' : '';
        return `<option value="${esc(val)}"${sel}>${esc(lbl)}</option>`;
      }).join('');
      return wrap(f, `${label}<select class="select cmd-input" id="${id}" data-key="${esc(f.key)}">${opts}</select>${hint}`);
    }
    case 'bool': {
      return wrap(f, `
        <label class="cmd-check">
          <input type="checkbox" id="${id}" data-key="${esc(f.key)}"${f.default ? ' checked' : ''} />
          <span>${esc(f.label)}</span>
        </label>
        ${hint}
      `);
    }
    case 'coords': {
      const d = f.default || {};
      return wrap(f, `
        ${label}
        <div class="cmd-coords">
          <input class="input cmd-coord" data-key="${esc(f.key)}" data-axis="x" type="text" placeholder="x" value="${esc(d.x || '')}" />
          <input class="input cmd-coord" data-key="${esc(f.key)}" data-axis="y" type="text" placeholder="y" value="${esc(d.y || '')}" />
          <input class="input cmd-coord" data-key="${esc(f.key)}" data-axis="z" type="text" placeholder="z" value="${esc(d.z || '')}" />
        </div>
        ${hint || '<span class="cmd-field-hint muted">Use ~ for relative, ^ for local. Blank = ~ ~ ~.</span>'}
      `);
    }
    case 'target': {
      return targetFieldHtml(f);
    }
    case 'enchantments': {
      return wrap(f, `
        ${label}
        <div class="cmd-pairs" data-key="${esc(f.key)}" data-kind="enchant"></div>
        <button type="button" class="btn btn-sm cmd-pair-add">+ Add enchantment</button>
        ${hint}
      `);
    }
    case 'scores': {
      return wrap(f, `
        ${label}
        <div class="cmd-pairs" data-key="${esc(f.key)}" data-kind="score"></div>
        <button type="button" class="btn btn-sm cmd-pair-add">+ Add score</button>
        ${hint}
      `);
    }
    case 'modifiers': {
      return wrap(f, `
        ${label}
        <div class="cmd-pairs" data-key="${esc(f.key)}" data-kind="modifier"></div>
        <button type="button" class="btn btn-sm cmd-pair-add">+ Add attribute modifier</button>
        ${hint || '<span class="cmd-field-hint muted">Each modifier: attribute · amount · operation · equipment slot.</span>'}
      `);
    }
    case 'execute': {
      return `
        <div class="cmd-field cmd-field-wide cmd-field-execute">
          ${label}
          <div class="cmd-exec-rows" data-key="${esc(f.key)}"></div>
          <button type="button" class="btn btn-sm cmd-exec-add" data-key="${esc(f.key)}">+ Add subcommand</button>
          <span class="cmd-field-hint muted">
            Chain modifiers (as / at / if / positioned…) then a <code>run</code> step. Subcommands apply left-to-right.
          </span>
        </div>
      `;
    }
    default:
      return '';
  }
}

// ── /execute subcommand specifications ──────────────────────────────────────────
// Each entry: a label for the dropdown + the input "roles" it renders. readExec
// + buildExec below stay in sync with this table.

const EXEC_SUBS = {
  as:             { label: 'as <targets>',                roles: ['targets'] },
  at:             { label: 'at <targets>',                roles: ['targets'] },
  positioned:     { label: 'positioned <x y z>',          roles: ['coords'] },
  positioned_as:  { label: 'positioned as <targets>',     roles: ['targets'] },
  rotated:        { label: 'rotated <yaw pitch>',         roles: ['yaw', 'pitch'] },
  rotated_as:     { label: 'rotated as <targets>',        roles: ['targets'] },
  facing:         { label: 'facing <x y z>',              roles: ['coords'] },
  facing_entity:  { label: 'facing entity <targets>',     roles: ['targets', 'anchor'] },
  align:          { label: 'align <axes>',                roles: ['axes'] },
  in:             { label: 'in <dimension>',              roles: ['dimension'] },
  anchored:       { label: 'anchored <eyes|feet>',        roles: ['anchor'] },
  if_entity:      { label: 'if entity <targets>',         roles: ['targets'] },
  unless_entity:  { label: 'unless entity <targets>',     roles: ['targets'] },
  if_block:       { label: 'if block <x y z> <block>',    roles: ['coords', 'block'] },
  unless_block:   { label: 'unless block <x y z> <block>',roles: ['coords', 'block'] },
  if_raw:         { label: 'if … (custom)',               roles: ['raw'] },
  unless_raw:     { label: 'unless … (custom)',           roles: ['raw'] },
  store_raw:      { label: 'store … (custom)',            roles: ['raw'] },
  run:            { label: 'run <command>',               roles: ['command'] },
};

function execRowHtml() {
  const opts = Object.entries(EXEC_SUBS)
    .map(([k, v]) => `<option value="${esc(k)}">${esc(v.label)}</option>`).join('');
  return `
    <div class="cmd-exec-row">
      <select class="select cmd-input cmd-exec-type">${opts}</select>
      <div class="cmd-exec-inputs"></div>
      <button type="button" class="cmd-exec-del" title="Remove" aria-label="Remove">✕</button>
    </div>`;
}

// Render the inputs for a given subcommand type into a row's input container.
function execInputsHtml(type) {
  const spec = EXEC_SUBS[type] || EXEC_SUBS.as;
  return spec.roles.map(role => {
    switch (role) {
      case 'targets':
        return `<input class="input cmd-input cmd-exec-in" data-role="targets" type="text" list="dl-selectors" placeholder="@s" />`;
      case 'coords':
        return `<span class="cmd-exec-coords">
          <input class="input cmd-input cmd-exec-in" data-role="cx" type="text" placeholder="~" />
          <input class="input cmd-input cmd-exec-in" data-role="cy" type="text" placeholder="~" />
          <input class="input cmd-input cmd-exec-in" data-role="cz" type="text" placeholder="~" />
        </span>`;
      case 'yaw':
        return `<input class="input cmd-input cmd-exec-in" data-role="yaw" type="text" placeholder="yaw" />`;
      case 'pitch':
        return `<input class="input cmd-input cmd-exec-in" data-role="pitch" type="text" placeholder="pitch" />`;
      case 'anchor':
        return `<select class="select cmd-input cmd-exec-in" data-role="anchor"><option value="eyes">eyes</option><option value="feet">feet</option></select>`;
      case 'axes':
        return `<input class="input cmd-input cmd-exec-in" data-role="axes" type="text" placeholder="xyz" />`;
      case 'dimension':
        return `<input class="input cmd-input cmd-exec-in" data-role="dimension" type="text" list="dl-dimensions" placeholder="minecraft:the_nether" />`;
      case 'block':
        return `<input class="input cmd-input cmd-exec-in" data-role="block" type="text" list="dl-blocks" placeholder="stone" />`;
      case 'raw':
        return `<input class="input cmd-input cmd-exec-in" data-role="raw" type="text" placeholder="score @s objA matches 1.." />`;
      case 'command':
        return `<input class="input cmd-input cmd-exec-in cmd-exec-cmd" data-role="command" type="text" placeholder="say hello   (no leading slash)" />`;
      default:
        return '';
    }
  }).join('');
}

function wrap(f, inner) {
  return `<div class="cmd-field cmd-field-${esc(f.type)}${f.wide ? ' cmd-field-wide' : ''}">${inner}</div>`;
}

// Target selector field — variable picker + collapsible argument grid.
function targetFieldHtml(f) {
  const id = 'f-' + f.key;
  const variables = [
    { value: '@p', label: '@p — nearest player' },
    { value: '@a', label: '@a — all players' },
    { value: '@r', label: '@r — random player' },
    { value: '@e', label: '@e — all entities' },
    { value: '@s', label: '@s — self (executor)' },
    { value: '__name__', label: 'Player name…' },
  ];
  const def = f.default || '@p';
  return `
    <div class="cmd-field cmd-field-target cmd-field-wide">
      <label class="cmd-field-label" for="${id}">${esc(f.label)}${f.required ? ' <span class="cmd-req">*</span>' : ''}</label>
      <div class="cmd-target-top">
        <select class="select cmd-input cmd-target-var" id="${id}" data-key="${esc(f.key)}" data-role="variable">
          ${variables.map(v => `<option value="${esc(v.value)}"${v.value === def ? ' selected' : ''}>${esc(v.label)}</option>`).join('')}
        </select>
        <input class="input cmd-input cmd-target-name" data-key="${esc(f.key)}" data-role="name"
               type="text" placeholder="PlayerName" style="display:none;" />
        <button type="button" class="btn btn-sm cmd-target-toggle" data-key="${esc(f.key)}">Selector args ▾</button>
      </div>
      <div class="cmd-target-args" data-key="${esc(f.key)}" style="display:none;">
        ${targetArg(f.key, 'type', 'type', 'minecraft:zombie', 'dl-entities', 'Entity type. Prefix ! to exclude.')}
        ${targetArg(f.key, 'name', 'name', 'Steve', '', 'Exact entity/player name.')}
        ${targetArgNum(f.key, 'limit', 'limit', '1')}
        ${targetArgSelect(f.key, 'sort', 'sort', ['', 'nearest', 'furthest', 'random', 'arbitrary'])}
        ${targetArg(f.key, 'distMin', 'distance ≥', '0', '', 'Min distance (blocks).')}
        ${targetArg(f.key, 'distMax', 'distance ≤', '10', '', 'Max distance (blocks).')}
        ${targetArg(f.key, 'levelMin', 'level ≥', '0', '', 'Min XP level.')}
        ${targetArg(f.key, 'levelMax', 'level ≤', '30', '', 'Max XP level.')}
        ${targetArgSelect(f.key, 'gamemode', 'gamemode', ['', 'survival', 'creative', 'adventure', 'spectator'])}
        ${targetArg(f.key, 'tag', 'tag', 'myTag', '', 'Scoreboard tag. Prefix ! to exclude.')}
        ${targetArg(f.key, 'team', 'team', 'red', '', 'Team name. Prefix ! to exclude.')}
        ${targetArg(f.key, 'xRot', 'x_rotation', '0..90', '', 'Vertical pitch range.')}
        ${targetArg(f.key, 'yRot', 'y_rotation', '-90..90', '', 'Horizontal yaw range.')}
      </div>
    </div>
  `;
}
function targetArg(key, sub, label, ph, list, hint) {
  return `
    <div class="cmd-targ-cell">
      <label>${esc(label)}</label>
      <input class="input cmd-input cmd-targ" data-key="${esc(key)}" data-arg="${esc(sub)}"
             type="text" placeholder="${esc(ph)}"${list ? ` list="${esc(list)}"` : ''} title="${esc(hint || '')}" />
    </div>`;
}
function targetArgNum(key, sub, label, ph) {
  return `
    <div class="cmd-targ-cell">
      <label>${esc(label)}</label>
      <input class="input cmd-input cmd-targ" data-key="${esc(key)}" data-arg="${esc(sub)}"
             type="number" min="1" placeholder="${esc(ph)}" />
    </div>`;
}
function targetArgSelect(key, sub, label, opts) {
  return `
    <div class="cmd-targ-cell">
      <label>${esc(label)}</label>
      <select class="select cmd-input cmd-targ" data-key="${esc(key)}" data-arg="${esc(sub)}">
        ${opts.map(o => `<option value="${esc(o)}">${o === '' ? '(any)' : esc(o)}</option>`).join('')}
      </select>
    </div>`;
}

// ── form wiring ─────────────────────────────────────────────────────────────

function wireForm(form) {
  // NOTE: the input/change delegation is attached once in renderCommands on
  // the persistent form node — don't re-add it here or it stacks per switch.

  // Target: show/hide the player-name box + toggle the args panel.
  for (const sel of form.querySelectorAll('.cmd-target-var')) {
    syncTargetNameVisibility(sel);
  }
  for (const btn of form.querySelectorAll('.cmd-target-toggle')) {
    btn.addEventListener('click', () => {
      const panel = form.querySelector(`.cmd-target-args[data-key="${cssEsc(btn.dataset.key)}"]`);
      if (!panel) return;
      const open = panel.style.display !== 'none';
      panel.style.display = open ? 'none' : '';
      btn.textContent = open ? 'Selector args ▾' : 'Selector args ▴';
    });
  }

  // Repeatable pair rows (enchantments / scores).
  for (const btn of form.querySelectorAll('.cmd-pair-add')) {
    btn.addEventListener('click', () => {
      const container = btn.previousElementSibling;
      if (container && container.classList.contains('cmd-pairs')) {
        addPairRow(container);
        rebuild();
      }
    });
  }

  // /execute subcommand chain.
  for (const btn of form.querySelectorAll('.cmd-exec-add')) {
    btn.addEventListener('click', () => {
      const rows = form.querySelector(`.cmd-exec-rows[data-key="${cssEsc(btn.dataset.key)}"]`);
      if (rows) { addExecRow(rows); rebuild(); }
    });
  }
  // Seed a helpful starter chain (as @s → run) the first time the field renders.
  for (const rows of form.querySelectorAll('.cmd-exec-rows')) {
    if (!rows.children.length) {
      addExecRow(rows, 'as');
      addExecRow(rows, 'run');
    }
  }
}

// Append one subcommand row, optionally pre-selecting a type.
function addExecRow(rows, type) {
  const tmp = document.createElement('div');
  tmp.innerHTML = execRowHtml().trim();
  const row = tmp.firstElementChild;
  rows.appendChild(row);

  const typeSel = row.querySelector('.cmd-exec-type');
  const inputs  = row.querySelector('.cmd-exec-inputs');
  if (type) typeSel.value = type;
  inputs.innerHTML = execInputsHtml(typeSel.value);

  typeSel.addEventListener('change', () => {
    inputs.innerHTML = execInputsHtml(typeSel.value);
    rebuild();
  });
  row.querySelector('.cmd-exec-del').addEventListener('click', () => { row.remove(); rebuild(); });
}

function onFormChange(e) {
  // Keep the player-name input in sync with the variable dropdown.
  if (e.target.classList && e.target.classList.contains('cmd-target-var')) {
    syncTargetNameVisibility(e.target);
  }
  rebuild();
}

function syncTargetNameVisibility(sel) {
  const form = sel.closest('form');
  const nameBox = form && form.querySelector(`.cmd-target-name[data-key="${cssEsc(sel.dataset.key)}"]`);
  const argsBtn = form && form.querySelector(`.cmd-target-toggle[data-key="${cssEsc(sel.dataset.key)}"]`);
  const isName = sel.value === '__name__';
  if (nameBox) nameBox.style.display = isName ? '' : 'none';
  // Selector args only apply to @-variables, not plain names.
  if (argsBtn) argsBtn.style.display = isName ? 'none' : '';
  if (isName) {
    const panel = form && form.querySelector(`.cmd-target-args[data-key="${cssEsc(sel.dataset.key)}"]`);
    if (panel) panel.style.display = 'none';
  }
}

function addPairRow(container) {
  const kind = container.dataset.kind;
  const row = document.createElement('div');
  row.className = 'cmd-pair-row';
  if (kind === 'enchant') {
    row.innerHTML = `
      <input class="input cmd-input cmd-pair-k" type="text" list="dl-enchants" placeholder="sharpness" />
      <input class="input cmd-input cmd-pair-v" type="number" min="1" max="255" placeholder="lvl" value="1" />
      <button type="button" class="cmd-pair-del" title="Remove">✕</button>
    `;
  } else if (kind === 'modifier') {
    row.classList.add('cmd-pair-row-mod');
    row.innerHTML = `
      <input class="input cmd-input cmd-pair-k" type="text" list="dl-attributes" placeholder="attack_damage" />
      <input class="input cmd-input cmd-pair-v" type="number" step="0.1" placeholder="amount" value="1" />
      <select class="select cmd-input cmd-pair-op">${MOD_OPERATIONS.map(o => `<option value="${esc(o)}">${esc(o)}</option>`).join('')}</select>
      <select class="select cmd-input cmd-pair-slot">${EQUIP_SLOT_ENUM.map(o => `<option value="${esc(o)}">${esc(o)}</option>`).join('')}</select>
      <button type="button" class="cmd-pair-del" title="Remove">✕</button>
    `;
  } else {
    row.innerHTML = `
      <input class="input cmd-input cmd-pair-k" type="text" placeholder="objective" />
      <input class="input cmd-input cmd-pair-v" type="text" placeholder="1..10" />
      <button type="button" class="cmd-pair-del" title="Remove">✕</button>
    `;
  }
  row.querySelector('.cmd-pair-del').addEventListener('click', () => { row.remove(); rebuild(); });
  container.appendChild(row);
}

// ── value collection ──────────────────────────────────────────────────────────

function collectValues(form, fields) {
  const v = {};
  for (const f of fields) {
    v[f.key] = readField(form, f);
  }
  return v;
}

function readField(form, f) {
  switch (f.type) {
    case 'bool': {
      const el = form.querySelector(`#f-${cssEsc(f.key)}`);
      return !!(el && el.checked);
    }
    case 'coords': {
      const get = (axis) => {
        const el = form.querySelector(`.cmd-coord[data-key="${cssEsc(f.key)}"][data-axis="${axis}"]`);
        return el ? el.value.trim() : '';
      };
      return { x: get('x'), y: get('y'), z: get('z') };
    }
    case 'target': {
      const sel  = form.querySelector(`.cmd-target-var[data-key="${cssEsc(f.key)}"]`);
      const name = form.querySelector(`.cmd-target-name[data-key="${cssEsc(f.key)}"]`);
      const variable = sel ? sel.value : '@p';
      const args = {};
      for (const el of form.querySelectorAll(`.cmd-targ[data-key="${cssEsc(f.key)}"]`)) {
        const val = (el.value || '').trim();
        if (val !== '') args[el.dataset.arg] = val;
      }
      return { variable, name: name ? name.value.trim() : '', args };
    }
    case 'enchantments':
    case 'scores':
    case 'modifiers': {
      const container = form.querySelector(`.cmd-pairs[data-key="${cssEsc(f.key)}"]`);
      const out = [];
      if (container) {
        for (const row of container.querySelectorAll('.cmd-pair-row')) {
          const k = row.querySelector('.cmd-pair-k').value.trim();
          const val = row.querySelector('.cmd-pair-v').value.trim();
          if (!k) continue;
          const entry = { k, v: val };
          const opEl   = row.querySelector('.cmd-pair-op');
          const slotEl = row.querySelector('.cmd-pair-slot');
          if (opEl)   entry.op = opEl.value;
          if (slotEl) entry.slot = slotEl.value;
          out.push(entry);
        }
      }
      return out;
    }
    case 'execute': {
      const rowsEl = form.querySelector(`.cmd-exec-rows[data-key="${cssEsc(f.key)}"]`);
      const rows = [];
      if (rowsEl) {
        for (const row of rowsEl.querySelectorAll('.cmd-exec-row')) {
          const type = row.querySelector('.cmd-exec-type').value;
          const inputs = {};
          for (const el of row.querySelectorAll('.cmd-exec-in')) {
            inputs[el.dataset.role] = (el.value || '').trim();
          }
          rows.push({ type, inputs });
        }
      }
      return rows;
    }
    default: {
      const el = form.querySelector(`#f-${cssEsc(f.key)}`);
      return el ? el.value.trim() : '';
    }
  }
}

// ── build + output ─────────────────────────────────────────────────────────────

function rebuild() {
  const cmd  = COMMANDS[currentCmd];
  const form = document.getElementById('cmd-form');
  const out  = document.getElementById('cmd-output');
  const warn = document.getElementById('cmd-output-warn');
  if (!cmd || !form || !out) return;

  const values = collectValues(form, cmd.fields);
  let result = '';
  try {
    result = cmd.build(values) || '';
  } catch (err) {
    result = '';
  }
  out.textContent = result ? '/' + result : '';

  if (warn) {
    const len = result.length + 1;
    if (!result) {
      warn.textContent = '';
    } else if (len > 256) {
      warn.textContent = `${len} chars — too long for chat, use a command block`;
      warn.classList.add('cmd-warn');
    } else {
      warn.textContent = `${len} chars`;
      warn.classList.remove('cmd-warn');
    }
  }
}

// ── selector + helpers used by build() functions ────────────────────────────

// Build a target string from a {variable, name, args} value object.
function selector(t) {
  if (!t) return '@p';
  if (t.variable === '__name__') return t.name || '@p';
  const a = t.args || {};
  const parts = [];
  if (a.type)     parts.push(`type=${a.type}`);
  if (a.name)     parts.push(`name=${quoteIfNeeded(a.name)}`);
  if (a.tag)      parts.push(`tag=${a.tag}`);
  if (a.team)     parts.push(`team=${a.team}`);
  if (a.gamemode) parts.push(`gamemode=${a.gamemode}`);
  // distance range
  const dist = rangeStr(a.distMin, a.distMax);
  if (dist) parts.push(`distance=${dist}`);
  // level range
  const lvl = rangeStr(a.levelMin, a.levelMax);
  if (lvl) parts.push(`level=${lvl}`);
  if (a.xRot) parts.push(`x_rotation=${a.xRot}`);
  if (a.yRot) parts.push(`y_rotation=${a.yRot}`);
  // limit / sort last (cosmetic ordering, matches vanilla docs)
  if (a.limit) parts.push(`limit=${a.limit}`);
  if (a.sort)  parts.push(`sort=${a.sort}`);
  return parts.length ? `${t.variable}[${parts.join(',')}]` : t.variable;
}

// Turn min/max into vanilla range syntax: "2..10", "2..", "..10", or "5".
function rangeStr(min, max) {
  const hasMin = min != null && min !== '';
  const hasMax = max != null && max !== '';
  if (!hasMin && !hasMax) return '';
  if (hasMin && hasMax) return min === max ? `${min}` : `${min}..${max}`;
  if (hasMin) return `${min}..`;
  return `..${max}`;
}

// Coordinate triple → "x y z", defaulting blanks to ~. If all blank, "~ ~ ~".
function coords(c, fallback = '~') {
  if (!c) return `${fallback} ${fallback} ${fallback}`;
  const x = c.x || fallback, y = c.y || fallback, z = c.z || fallback;
  return `${x} ${y} ${z}`;
}
// Coords that must be provided (setblock/fill) — blanks become ~.
function coordsReq(c) { return coords(c, '~'); }

function ns(id) {
  if (!id) return '';
  return id.includes(':') ? id : 'minecraft:' + id;
}

// Build a single /execute subcommand string from {type, inputs}.
function buildExecSub(row) {
  const i = row.inputs || {};
  const pos = () => `${i.cx || '~'} ${i.cy || '~'} ${i.cz || '~'}`;
  switch (row.type) {
    case 'as':            return `as ${i.targets || '@s'}`;
    case 'at':            return `at ${i.targets || '@s'}`;
    case 'positioned':    return `positioned ${pos()}`;
    case 'positioned_as': return `positioned as ${i.targets || '@s'}`;
    case 'rotated':       return `rotated ${i.yaw || '~'} ${i.pitch || '~'}`;
    case 'rotated_as':    return `rotated as ${i.targets || '@s'}`;
    case 'facing':        return `facing ${pos()}`;
    case 'facing_entity': return `facing entity ${i.targets || '@s'} ${i.anchor || 'eyes'}`;
    case 'align':         return `align ${i.axes || 'xyz'}`;
    case 'in':            return `in ${ns(i.dimension || 'overworld')}`;
    case 'anchored':      return `anchored ${i.anchor || 'eyes'}`;
    case 'if_entity':     return `if entity ${i.targets || '@s'}`;
    case 'unless_entity': return `unless entity ${i.targets || '@s'}`;
    case 'if_block':      return `if block ${pos()} ${ns(i.block || 'stone')}`;
    case 'unless_block':  return `unless block ${pos()} ${ns(i.block || 'stone')}`;
    case 'if_raw':        return i.raw ? `if ${i.raw}` : '';
    case 'unless_raw':    return i.raw ? `unless ${i.raw}` : '';
    case 'store_raw':     return i.raw ? `store ${i.raw}` : '';
    case 'run':           return i.command ? `run ${i.command.replace(/^\//, '')}` : '';
    default:              return '';
  }
}

function quoteIfNeeded(s) {
  return /^[A-Za-z0-9_.+-]+$/.test(s) ? s : `"${s.replace(/"/g, '\\"')}"`;
}

// A single-quoted SNBT string holding a JSON text component, with single
// quotes inside escaped so a value like "Bob's" stays valid.
function textArg(text, extra = {}) {
  const json = JSON.stringify({ text, italic: false, ...extra }).replace(/'/g, "\\'");
  return `'${json}'`;
}

// Assemble the [components] suffix for /give items. Targets modern (1.21.x)
// data-component syntax. Stable components are surfaced as fields; the raw
// passthrough covers anything else (and version-volatile components).
function itemComponents(values) {
  const parts = [];

  if (values.name) parts.push(`minecraft:custom_name=${textArg(values.name)}`);

  if (values.lore) {
    const lines = String(values.lore).split('|').map(s => s.trim()).filter(Boolean);
    if (lines.length) {
      parts.push(`minecraft:lore=[${lines.map(l => textArg(l, { color: 'gray' })).join(',')}]`);
    }
  }

  const ench = values.enchantments || [];
  if (ench.length) {
    parts.push(`minecraft:enchantments={${ench.map(e => `"${ns(e.k)}":${e.v || 1}`).join(',')}}`);
  }

  const mods = values.attribute_modifiers || [];
  if (mods.length) {
    const arr = mods.map((m, i) => {
      const id = m.id || `fox:modifier_${i}`;
      return `{type:"${ns(m.k)}",amount:${m.v || 0},operation:"${m.op || 'add_value'}",slot:"${m.slot || 'any'}",id:"${id}"}`;
    }).join(',');
    parts.push(`minecraft:attribute_modifiers=[${arr}]`);
  }

  if (values.dyed)  parts.push(`minecraft:dyed_color=${parseColor(values.dyed)}`);
  if (values.rarity && values.rarity !== 'common') parts.push(`minecraft:rarity="${values.rarity}"`);
  if (values.maxStack && String(values.maxStack) !== '') parts.push(`minecraft:max_stack_size=${values.maxStack}`);
  if (values.itemDamage && String(values.itemDamage) !== '') parts.push(`minecraft:damage=${values.itemDamage}`);
  if (values.unbreakable) parts.push('minecraft:unbreakable={}');
  if (values.glint)       parts.push('minecraft:enchantment_glint_override=true');

  if (values.rawComponents) {
    const raw = String(values.rawComponents).trim().replace(/^\[|\]$/g, '').trim();
    if (raw) parts.push(raw);
  }

  return parts.length ? `[${parts.join(',')}]` : '';
}

// Accept "#RRGGBB", "RRGGBB", a decimal int, or "r,g,b" → decimal int string.
function parseColor(s) {
  s = String(s).trim();
  if (/^#?[0-9a-fA-F]{6}$/.test(s)) return String(parseInt(s.replace('#', ''), 16));
  const rgb = s.split(',').map(n => parseInt(n.trim(), 10));
  if (rgb.length === 3 && rgb.every(n => !isNaN(n))) return String((rgb[0] << 16) | (rgb[1] << 8) | rgb[2]);
  const n = parseInt(s, 10);
  return isNaN(n) ? '16777215' : String(n);
}

// ── command registry ─────────────────────────────────────────────────────────

const COMMANDS = {
  execute: {
    name: 'execute', tag: 'control', desc: 'Chain context modifiers (as / at / if / positioned…) and run a command.',
    fields: [
      { key: 'chain', type: 'execute', label: 'Subcommand chain', wide: true },
    ],
    build: (v) => {
      const rows = v.chain || [];
      const parts = rows.map(buildExecSub).filter(Boolean);
      return parts.length ? `execute ${parts.join(' ')}` : 'execute';
    },
  },

  give: {
    name: 'give', tag: 'items', desc: 'Give a fully-customised item — name, lore, enchantments, attribute modifiers, and more.',
    fields: [
      { key: 't', type: 'target', label: 'Target', default: '@p', required: true },
      { key: 'item', type: 'suggest', label: 'Item', list: 'dl-items', placeholder: 'diamond_sword', default: 'diamond_sword', required: true },
      { key: 'count', type: 'number', label: 'Count', min: 1, max: 6400, default: 1 },
      { key: 'name', type: 'text', label: 'Custom name', placeholder: 'Excalibur' },
      { key: 'lore', type: 'text', label: 'Lore', wide: true, placeholder: 'Line one | Line two',
        hint: 'Separate multiple lines with | (pipe).' },
      { key: 'enchantments', type: 'enchantments', label: 'Enchantments', wide: true },
      { key: 'attribute_modifiers', type: 'modifiers', label: 'Attribute modifiers', wide: true },
      { key: 'rarity', type: 'select', label: 'Rarity (name color)', options: ['common', 'uncommon', 'rare', 'epic'], default: 'common' },
      { key: 'dyed', type: 'text', label: 'Dye color (leather)', placeholder: '#FF0000 or 255,0,0' },
      { key: 'maxStack', type: 'number', label: 'Max stack size', min: 1, max: 99, placeholder: 'default' },
      { key: 'itemDamage', type: 'number', label: 'Damage (durability used)', min: 0, placeholder: '0' },
      { key: 'unbreakable', type: 'bool', label: 'Unbreakable' },
      { key: 'glint', type: 'bool', label: 'Force enchant glint' },
      { key: 'rawComponents', type: 'text', label: 'Extra components (raw)', wide: true,
        placeholder: 'minecraft:food={nutrition:4},minecraft:max_damage=200',
        hint: 'Advanced: any extra components, comma-separated. Inserted verbatim.' },
    ],
    build: (v) => {
      const item = ns(v.item || 'stone') + itemComponents(v);
      const count = v.count && String(v.count) !== '1' ? ' ' + v.count : '';
      return `give ${selector(v.t)} ${item}${count}`;
    },
  },

  summon: {
    name: 'summon', tag: 'entities', desc: 'Spawn an entity at a position with optional NBT.',
    fields: [
      { key: 'entity', type: 'suggest', label: 'Entity', list: 'dl-entities', placeholder: 'zombie', default: 'zombie', required: true },
      { key: 'pos', type: 'coords', label: 'Position' },
      { key: 'name', type: 'text', label: 'Custom name', placeholder: 'Boss' },
      { key: 'noai', type: 'bool', label: 'No AI (frozen)' },
      { key: 'invul', type: 'bool', label: 'Invulnerable' },
      { key: 'silent', type: 'bool', label: 'Silent' },
      { key: 'glow', type: 'bool', label: 'Glowing' },
      { key: 'persist', type: 'bool', label: 'Persistent (never despawn)' },
      { key: 'nbt', type: 'text', label: 'Extra NBT', wide: true, placeholder: '{Health:40f}',
        hint: 'Raw NBT merged into the entity. Advanced.' },
    ],
    build: (v) => {
      const nbt = {};
      if (v.name)   nbt.CustomName = JSON.stringify({ text: v.name });
      if (v.noai)   nbt.NoAI = true;
      if (v.invul)  nbt.Invulnerable = true;
      if (v.silent) nbt.Silent = true;
      if (v.glow)   nbt.Glowing = true;
      if (v.persist) nbt.PersistenceRequired = true;
      const flags = Object.entries(nbt).map(([k, val]) => {
        if (typeof val === 'boolean') return `${k}:${val ? '1b' : '0b'}`;
        if (k === 'CustomName') return `${k}:'${val}'`;
        return `${k}:${val}`;
      });
      let extra = (v.nbt || '').trim();
      if (extra.startsWith('{')) extra = extra.slice(1, -1).trim();
      if (extra) flags.push(extra);
      const tag = flags.length ? ` {${flags.join(',')}}` : '';
      const pos = coords(v.pos);
      // Only emit position if non-default or NBT present (entity needs pos before NBT).
      const needPos = tag || pos !== '~ ~ ~';
      return `summon ${ns(v.entity || 'pig')}${needPos ? ' ' + pos : ''}${tag}`;
    },
  },

  effect: {
    name: 'effect', tag: 'status', desc: 'Apply or clear a status effect.',
    fields: [
      { key: 'action', type: 'select', label: 'Action', options: ['give', 'clear'], default: 'give' },
      { key: 't', type: 'target', label: 'Target', default: '@p', required: true },
      { key: 'effect', type: 'suggest', label: 'Effect', list: 'dl-effects', placeholder: 'speed', default: 'speed' },
      { key: 'infinite', type: 'bool', label: 'Infinite duration' },
      { key: 'seconds', type: 'number', label: 'Duration (seconds)', min: 0, max: 1000000, default: 30 },
      { key: 'amplifier', type: 'number', label: 'Amplifier (0 = level I)', min: 0, max: 255, default: 0 },
      { key: 'hide', type: 'bool', label: 'Hide particles' },
    ],
    build: (v) => {
      if (v.action === 'clear') {
        const eff = v.effect ? ' ' + ns(v.effect) : '';
        return `effect clear ${selector(v.t)}${eff}`;
      }
      const dur = v.infinite ? 'infinite' : (v.seconds !== '' ? v.seconds : '30');
      const amp = v.amplifier !== '' ? v.amplifier : '0';
      // Only emit trailing optionals when meaningful.
      let tail = ` ${dur} ${amp}`;
      if (v.hide) tail += ' true';
      else if (String(amp) === '0' && String(dur) === '30') tail = ''; // both default → omit
      return `effect give ${selector(v.t)} ${ns(v.effect || 'speed')}${tail}`;
    },
  },

  teleport: {
    name: 'teleport', tag: 'movement', desc: 'Teleport entities to a position or to another entity.',
    fields: [
      { key: 't', type: 'target', label: 'Who to teleport', default: '@s', required: true },
      { key: 'mode', type: 'select', label: 'Destination type', options: [
        { value: 'pos', label: 'Coordinates' }, { value: 'entity', label: 'Another entity' }], default: 'pos' },
      { key: 'pos', type: 'coords', label: 'Destination coords' },
      { key: 'dest', type: 'text', label: 'Destination entity', placeholder: '@p or PlayerName' },
      { key: 'yaw', type: 'text', label: 'Yaw (facing)', placeholder: '0' },
      { key: 'pitch', type: 'text', label: 'Pitch', placeholder: '0' },
    ],
    build: (v) => {
      if (v.mode === 'entity') {
        return `teleport ${selector(v.t)} ${v.dest || '@p'}`;
      }
      const rot = (v.yaw || v.pitch) ? ` ${v.yaw || '~'} ${v.pitch || '~'}` : '';
      return `teleport ${selector(v.t)} ${coords(v.pos)}${rot}`;
    },
  },

  setblock: {
    name: 'setblock', tag: 'world', desc: 'Place a single block at a position.',
    fields: [
      { key: 'pos', type: 'coords', label: 'Position', required: true },
      { key: 'block', type: 'suggest', label: 'Block', list: 'dl-blocks', placeholder: 'stone', default: 'stone', required: true },
      { key: 'states', type: 'text', label: 'Block states', placeholder: 'facing=north,half=top',
        hint: 'Optional, comma-separated. Wrapped in [ ] automatically.' },
      { key: 'mode', type: 'select', label: 'Mode', options: ['replace', 'destroy', 'keep'], default: 'replace' },
    ],
    build: (v) => {
      const states = v.states ? `[${v.states}]` : '';
      const mode = v.mode && v.mode !== 'replace' ? ' ' + v.mode : '';
      return `setblock ${coordsReq(v.pos)} ${ns(v.block || 'stone')}${states}${mode}`;
    },
  },

  fill: {
    name: 'fill', tag: 'world', desc: 'Fill a cuboid region with a block.',
    fields: [
      { key: 'from', type: 'coords', label: 'From', required: true },
      { key: 'to', type: 'coords', label: 'To', required: true },
      { key: 'block', type: 'suggest', label: 'Block', list: 'dl-blocks', placeholder: 'stone', default: 'stone', required: true },
      { key: 'states', type: 'text', label: 'Block states', placeholder: 'facing=north' },
      { key: 'mode', type: 'select', label: 'Mode', options: ['replace', 'destroy', 'hollow', 'keep', 'outline'], default: 'replace' },
      { key: 'replaceFilter', type: 'suggest', label: 'Replace only (filter)', list: 'dl-blocks',
        placeholder: 'air', hint: 'Only used when mode = replace.' },
    ],
    build: (v) => {
      const states = v.states ? `[${v.states}]` : '';
      let mode = '';
      if (v.mode === 'replace' && v.replaceFilter) mode = ` replace ${ns(v.replaceFilter)}`;
      else if (v.mode && v.mode !== 'replace') mode = ' ' + v.mode;
      return `fill ${coordsReq(v.from)} ${coordsReq(v.to)} ${ns(v.block || 'stone')}${states}${mode}`;
    },
  },

  gamemode: {
    name: 'gamemode', tag: 'players', desc: 'Change a player\'s game mode.',
    fields: [
      { key: 'mode', type: 'select', label: 'Mode', options: ['survival', 'creative', 'adventure', 'spectator'], default: 'creative' },
      { key: 't', type: 'target', label: 'Target', default: '@s' },
    ],
    build: (v) => `gamemode ${v.mode || 'creative'} ${selector(v.t)}`,
  },

  effect_xp: {
    name: 'experience', tag: 'players', desc: 'Add, set, or query player experience.',
    fields: [
      { key: 'action', type: 'select', label: 'Action', options: ['add', 'set', 'query'], default: 'add' },
      { key: 't', type: 'target', label: 'Target', default: '@s', required: true },
      { key: 'amount', type: 'number', label: 'Amount', min: 0, default: 10 },
      { key: 'unit', type: 'select', label: 'Unit', options: ['levels', 'points'], default: 'levels' },
    ],
    build: (v) => {
      if (v.action === 'query') return `experience query ${selector(v.t)} ${v.unit || 'levels'}`;
      return `experience ${v.action || 'add'} ${selector(v.t)} ${v.amount !== '' ? v.amount : 0} ${v.unit || 'levels'}`;
    },
  },

  enchant: {
    name: 'enchant', tag: 'items', desc: 'Enchant the item a player is holding (legacy command).',
    fields: [
      { key: 't', type: 'target', label: 'Target', default: '@s', required: true },
      { key: 'ench', type: 'suggest', label: 'Enchantment', list: 'dl-enchants', placeholder: 'sharpness', default: 'sharpness', required: true },
      { key: 'level', type: 'number', label: 'Level', min: 1, max: 255, default: 1 },
    ],
    build: (v) => {
      const lvl = v.level && String(v.level) !== '1' ? ' ' + v.level : '';
      return `enchant ${selector(v.t)} ${ns(v.ench || 'sharpness')}${lvl}`;
    },
  },

  time: {
    name: 'time', tag: 'world', desc: 'Set or add to the world time.',
    fields: [
      { key: 'action', type: 'select', label: 'Action', options: ['set', 'add', 'query'], default: 'set' },
      { key: 'preset', type: 'select', label: 'Preset', options: [
        { value: '', label: 'Custom value' }, 'day', 'noon', 'night', 'midnight'], default: 'day' },
      { key: 'value', type: 'number', label: 'Custom value (ticks)', min: 0, default: 1000 },
      { key: 'queryType', type: 'select', label: 'Query', options: ['daytime', 'gametime', 'day'], default: 'daytime' },
    ],
    build: (v) => {
      if (v.action === 'query') return `time query ${v.queryType || 'daytime'}`;
      if (v.action === 'set' && v.preset) return `time set ${v.preset}`;
      return `time ${v.action || 'set'} ${v.value !== '' ? v.value : 0}`;
    },
  },

  weather: {
    name: 'weather', tag: 'world', desc: 'Set the weather, optionally for a duration.',
    fields: [
      { key: 'type', type: 'select', label: 'Weather', options: ['clear', 'rain', 'thunder'], default: 'clear' },
      { key: 'duration', type: 'number', label: 'Duration (seconds)', min: 0, placeholder: 'optional' },
    ],
    build: (v) => {
      const dur = v.duration !== '' ? ' ' + v.duration : '';
      return `weather ${v.type || 'clear'}${dur}`;
    },
  },

  difficulty: {
    name: 'difficulty', tag: 'world', desc: 'Set the world difficulty.',
    fields: [
      { key: 'level', type: 'select', label: 'Difficulty', options: ['peaceful', 'easy', 'normal', 'hard'], default: 'normal' },
    ],
    build: (v) => `difficulty ${v.level || 'normal'}`,
  },

  gamerule: {
    name: 'gamerule', tag: 'world', desc: 'Set or query a game rule.',
    fields: [
      { key: 'rule', type: 'suggest', label: 'Rule', list: 'dl-gamerules', placeholder: 'keepInventory', default: 'keepInventory', required: true },
      { key: 'value', type: 'text', label: 'Value', placeholder: 'true / false / number',
        hint: 'Leave blank to query the current value.' },
    ],
    build: (v) => {
      const val = v.value !== '' ? ' ' + v.value : '';
      return `gamerule ${v.rule || 'keepInventory'}${val}`;
    },
  },

  kill: {
    name: 'kill', tag: 'entities', desc: 'Remove entities (or kill a player).',
    fields: [
      { key: 't', type: 'target', label: 'Target', default: '@e' },
    ],
    build: (v) => `kill ${selector(v.t)}`,
  },

  clear: {
    name: 'clear', tag: 'items', desc: 'Clear items from a player\'s inventory.',
    fields: [
      { key: 't', type: 'target', label: 'Target', default: '@s' },
      { key: 'item', type: 'suggest', label: 'Item (optional)', list: 'dl-items', placeholder: 'all items' },
      { key: 'max', type: 'number', label: 'Max count', min: 0, placeholder: 'all' },
    ],
    build: (v) => {
      let s = `clear ${selector(v.t)}`;
      if (v.item) {
        s += ' ' + ns(v.item);
        if (v.max !== '') s += ' ' + v.max;
      }
      return s;
    },
  },

  tellraw: {
    name: 'tellraw', tag: 'text', desc: 'Send a raw JSON text message to players.',
    fields: [
      { key: 't', type: 'target', label: 'Target', default: '@a', required: true },
      { key: 'text', type: 'text', label: 'Message', wide: true, placeholder: 'Hello world',
        hint: 'Plain text is wrapped automatically. Or paste a full JSON text component.' },
      { key: 'color', type: 'select', label: 'Color', options: [
        '', 'white', 'yellow', 'gold', 'red', 'aqua', 'green', 'blue', 'light_purple', 'gray', 'dark_red', 'dark_green'], default: '' },
      { key: 'bold', type: 'bool', label: 'Bold' },
      { key: 'italic', type: 'bool', label: 'Italic' },
    ],
    build: (v) => {
      let comp;
      const trimmed = (v.text || '').trim();
      if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
        comp = trimmed;
      } else {
        const obj = { text: v.text || '' };
        if (v.color)  obj.color = v.color;
        if (v.bold)   obj.bold = true;
        if (v.italic) obj.italic = true;
        comp = JSON.stringify(obj);
      }
      return `tellraw ${selector(v.t)} ${comp}`;
    },
  },

  title: {
    name: 'title', tag: 'text', desc: 'Display a title, subtitle, or action bar message.',
    fields: [
      { key: 't', type: 'target', label: 'Target', default: '@a', required: true },
      { key: 'slot', type: 'select', label: 'Slot', options: ['title', 'subtitle', 'actionbar'], default: 'title' },
      { key: 'text', type: 'text', label: 'Text', wide: true, placeholder: 'Welcome!' },
      { key: 'color', type: 'select', label: 'Color', options: [
        '', 'white', 'yellow', 'gold', 'red', 'aqua', 'green', 'blue', 'light_purple'], default: '' },
      { key: 'bold', type: 'bool', label: 'Bold' },
    ],
    build: (v) => {
      const obj = { text: v.text || '' };
      if (v.color) obj.color = v.color;
      if (v.bold)  obj.bold = true;
      return `title ${selector(v.t)} ${v.slot || 'title'} ${JSON.stringify(obj)}`;
    },
  },

  playsound: {
    name: 'playsound', tag: 'audio', desc: 'Play a sound effect for players.',
    fields: [
      { key: 'sound', type: 'suggest', label: 'Sound', list: 'dl-sounds', placeholder: 'minecraft:entity.player.levelup', default: 'minecraft:entity.player.levelup', required: true },
      { key: 'source', type: 'select', label: 'Source', options: [
        'master', 'music', 'record', 'weather', 'block', 'hostile', 'neutral', 'player', 'ambient', 'voice'], default: 'master' },
      { key: 't', type: 'target', label: 'Target', default: '@a', required: true },
      { key: 'pos', type: 'coords', label: 'Position', hint: 'Where the sound originates. Blank = at the player (~ ~ ~).' },
      { key: 'volume', type: 'number', label: 'Volume', min: 0, step: 0.1, default: 1 },
      { key: 'pitch', type: 'number', label: 'Pitch', min: 0, max: 2, step: 0.1, default: 1 },
    ],
    build: (v) => {
      const snd = v.sound || 'minecraft:entity.player.levelup';
      let s = `playsound ${snd} ${v.source || 'master'} ${selector(v.t)}`;
      const pos = coords(v.pos);
      const vol = v.volume !== '' ? v.volume : '1';
      const pit = v.pitch !== '' ? v.pitch : '1';
      // Only emit pos/volume/pitch when not all defaults.
      if (pos !== '~ ~ ~' || String(vol) !== '1' || String(pit) !== '1') {
        s += ` ${pos} ${vol} ${pit}`;
      }
      return s;
    },
  },

  particle: {
    name: 'particle', tag: 'visual', desc: 'Spawn particles at a position.',
    fields: [
      { key: 'name', type: 'suggest', label: 'Particle', list: 'dl-particles', placeholder: 'flame', default: 'flame', required: true },
      { key: 'pos', type: 'coords', label: 'Position' },
      { key: 'delta', type: 'coords', label: 'Spread (dx dy dz)', hint: 'Random offset / direction box.' },
      { key: 'speed', type: 'number', label: 'Speed', min: 0, step: 0.1, default: 0 },
      { key: 'count', type: 'number', label: 'Count', min: 0, default: 10 },
      { key: 'force', type: 'bool', label: 'Force (visible far away)' },
    ],
    build: (v) => {
      let s = `particle ${ns(v.name || 'flame')} ${coords(v.pos)}`;
      const delta = coords(v.delta, '0');
      const speed = v.speed !== '' ? v.speed : '0';
      const count = v.count !== '' ? v.count : '0';
      s += ` ${delta} ${speed} ${count}`;
      if (v.force) s += ' force';
      return s;
    },
  },

  scoreboard: {
    name: 'scoreboard', tag: 'data', desc: 'Set a player\'s score on an objective.',
    fields: [
      { key: 'op', type: 'select', label: 'Operation', options: ['set', 'add', 'remove', 'reset', 'get'], default: 'set' },
      { key: 't', type: 'target', label: 'Target', default: '@s', required: true },
      { key: 'objective', type: 'text', label: 'Objective', placeholder: 'kills', required: true },
      { key: 'value', type: 'number', label: 'Value', default: 1 },
    ],
    build: (v) => {
      const obj = v.objective || 'objective';
      if (v.op === 'reset' || v.op === 'get') return `scoreboard players ${v.op} ${selector(v.t)} ${obj}`;
      return `scoreboard players ${v.op || 'set'} ${selector(v.t)} ${obj} ${v.value !== '' ? v.value : 0}`;
    },
  },

  attribute: {
    name: 'attribute', tag: 'entities', desc: 'Get/set an attribute base value, or add/remove temporary modifiers.',
    fields: [
      { key: 't', type: 'target', label: 'Target', default: '@s', required: true },
      { key: 'attr', type: 'suggest', label: 'Attribute', list: 'dl-attributes', placeholder: 'max_health',
        default: 'max_health', required: true },
      { key: 'op', type: 'select', label: 'Action', options: [
        { value: 'base set', label: 'base set' },
        { value: 'base get', label: 'base get' },
        { value: 'get', label: 'get (total)' },
        { value: 'modifier add', label: 'modifier add' },
        { value: 'modifier remove', label: 'modifier remove' },
        { value: 'modifier value get', label: 'modifier value get' }], default: 'base set' },
      { key: 'value', type: 'number', label: 'Value', step: 0.1, default: 20,
        hint: 'For base set and modifier add.' },
      { key: 'modId', type: 'text', label: 'Modifier id', placeholder: 'fox:my_bonus',
        hint: 'For modifier add/remove/get.' },
      { key: 'modOp', type: 'select', label: 'Modifier operation', options: MOD_OPERATIONS, default: 'add_value' },
    ],
    build: (v) => {
      const attr = ns(v.attr || 'max_health');
      const sel = selector(v.t);
      const val = v.value !== '' ? v.value : 0;
      const id = v.modId ? ns(v.modId) : 'fox:modifier';
      switch (v.op) {
        case 'base set':           return `attribute ${sel} ${attr} base set ${val}`;
        case 'get':                return `attribute ${sel} ${attr} get`;
        case 'modifier add':       return `attribute ${sel} ${attr} modifier add ${id} ${val} ${v.modOp || 'add_value'}`;
        case 'modifier remove':    return `attribute ${sel} ${attr} modifier remove ${id}`;
        case 'modifier value get': return `attribute ${sel} ${attr} modifier value get ${id}`;
        default:                   return `attribute ${sel} ${attr} base get`;
      }
    },
  },

  tag: {
    name: 'tag', tag: 'entities', desc: 'Add or remove a scoreboard tag on entities.',
    fields: [
      { key: 'op', type: 'select', label: 'Action', options: ['add', 'remove', 'list'], default: 'add' },
      { key: 't', type: 'target', label: 'Target', default: '@s', required: true },
      { key: 'name', type: 'text', label: 'Tag name', placeholder: 'myTag' },
    ],
    build: (v) => {
      if (v.op === 'list') return `tag ${selector(v.t)} list`;
      return `tag ${selector(v.t)} ${v.op || 'add'} ${v.name || 'tag'}`;
    },
  },

  damage: {
    name: 'damage', tag: 'entities', desc: 'Deal damage to entities, optionally with a damage type.',
    fields: [
      { key: 't', type: 'target', label: 'Target', default: '@e', required: true },
      { key: 'amount', type: 'number', label: 'Amount', min: 0, step: 0.5, default: 5 },
      { key: 'type', type: 'text', label: 'Damage type', placeholder: 'minecraft:generic',
        hint: 'Optional, e.g. fall, lava, magic, generic.' },
    ],
    build: (v) => {
      const amt = v.amount !== '' ? v.amount : '1';
      const type = v.type ? ' ' + ns(v.type) : '';
      return `damage ${selector(v.t)} ${amt}${type}`;
    },
  },

  ride: {
    name: 'ride', tag: 'entities', desc: 'Make one entity start or stop riding another.',
    fields: [
      { key: 't', type: 'target', label: 'Rider', default: '@s', required: true },
      { key: 'op', type: 'select', label: 'Action', options: [
        { value: 'mount', label: 'mount' }, { value: 'dismount', label: 'dismount' }], default: 'mount' },
      { key: 'vehicle', type: 'text', label: 'Vehicle', placeholder: '@e[type=horse,limit=1]',
        hint: 'Only used when mounting.' },
    ],
    build: (v) => {
      if (v.op === 'dismount') return `ride ${selector(v.t)} dismount`;
      return `ride ${selector(v.t)} mount ${v.vehicle || '@e[limit=1]'}`;
    },
  },

  worldborder: {
    name: 'worldborder', tag: 'world', desc: 'Resize or reposition the world border.',
    fields: [
      { key: 'op', type: 'select', label: 'Action', options: [
        { value: 'set', label: 'set diameter' }, { value: 'add', label: 'add diameter' },
        { value: 'center', label: 'set center' }], default: 'set' },
      { key: 'size', type: 'number', label: 'Diameter (blocks)', min: 1, default: 100 },
      { key: 'time', type: 'number', label: 'Transition time (s)', min: 0, placeholder: 'instant' },
      { key: 'cx', type: 'text', label: 'Center X', placeholder: '0' },
      { key: 'cz', type: 'text', label: 'Center Z', placeholder: '0' },
    ],
    build: (v) => {
      if (v.op === 'center') return `worldborder center ${v.cx || '0'} ${v.cz || '0'}`;
      const t = v.time !== '' ? ' ' + v.time : '';
      return `worldborder ${v.op || 'set'} ${v.size !== '' ? v.size : 100}${t}`;
    },
  },

  spawnpoint: {
    name: 'spawnpoint', tag: 'players', desc: 'Set a player\'s personal respawn point.',
    fields: [
      { key: 't', type: 'target', label: 'Target', default: '@s' },
      { key: 'pos', type: 'coords', label: 'Position', hint: 'Blank = current position (~ ~ ~).' },
      { key: 'angle', type: 'text', label: 'Spawn angle', placeholder: '0' },
    ],
    build: (v) => {
      const pos = coords(v.pos);
      const angle = v.angle ? ' ' + v.angle : '';
      // spawnpoint [target] [pos] [angle]
      if (pos === '~ ~ ~' && !v.angle) return `spawnpoint ${selector(v.t)}`;
      return `spawnpoint ${selector(v.t)} ${pos}${angle}`;
    },
  },

  setworldspawn: {
    name: 'setworldspawn', tag: 'world', desc: 'Set the world\'s shared spawn point.',
    fields: [
      { key: 'pos', type: 'coords', label: 'Position', hint: 'Blank = current position (~ ~ ~).' },
      { key: 'angle', type: 'text', label: 'Spawn angle', placeholder: '0' },
    ],
    build: (v) => {
      const pos = coords(v.pos);
      if (pos === '~ ~ ~' && !v.angle) return 'setworldspawn';
      return `setworldspawn ${pos}${v.angle ? ' ' + v.angle : ''}`;
    },
  },

  say: {
    name: 'say', tag: 'text', desc: 'Broadcast a message to everyone (shows your name).',
    fields: [
      { key: 'msg', type: 'text', label: 'Message', wide: true, placeholder: 'Hello everyone!', required: true },
    ],
    build: (v) => `say ${v.msg || ''}`.trimEnd(),
  },

  // ── world editing ──────────────────────────────────────────────────────────
  clone: {
    name: 'clone', tag: 'world', desc: 'Copy a region of blocks from one place to another.',
    fields: [
      { key: 'begin', type: 'coords', label: 'Source corner 1', required: true },
      { key: 'end', type: 'coords', label: 'Source corner 2', required: true },
      { key: 'dest', type: 'coords', label: 'Destination (lowest corner)', required: true },
      { key: 'mask', type: 'select', label: 'Mask', options: ['replace', 'masked', 'filtered'], default: 'replace' },
      { key: 'filter', type: 'suggest', label: 'Filter block', list: 'dl-blocks', placeholder: 'stone',
        hint: 'Only when mask = filtered.' },
      { key: 'mode', type: 'select', label: 'Mode', options: ['normal', 'force', 'move'], default: 'normal' },
    ],
    build: (v) => {
      let s = `clone ${coords(v.begin)} ${coords(v.end)} ${coords(v.dest)}`;
      if (v.mask === 'filtered') s += ` filtered ${ns(v.filter || 'stone')}`;
      else if (v.mask && v.mask !== 'replace') s += ' ' + v.mask;
      if (v.mode && v.mode !== 'normal') s += (v.mask && v.mask !== 'replace' ? '' : ' replace') + ' ' + v.mode;
      return s;
    },
  },

  fillbiome: {
    name: 'fillbiome', tag: 'world', desc: 'Change the biome within a region.',
    fields: [
      { key: 'from', type: 'coords', label: 'From', required: true },
      { key: 'to', type: 'coords', label: 'To', required: true },
      { key: 'biome', type: 'suggest', label: 'Biome', list: 'dl-biomes', placeholder: 'plains', default: 'plains', required: true },
      { key: 'replace', type: 'suggest', label: 'Replace only (filter)', list: 'dl-biomes', placeholder: 'desert' },
    ],
    build: (v) => {
      let s = `fillbiome ${coords(v.from)} ${coords(v.to)} ${ns(v.biome || 'plains')}`;
      if (v.replace) s += ` replace ${ns(v.replace)}`;
      return s;
    },
  },

  place: {
    name: 'place', tag: 'world', desc: 'Place a feature, structure, jigsaw, or template.',
    fields: [
      { key: 'type', type: 'select', label: 'Type', options: ['feature', 'structure', 'jigsaw', 'template'], default: 'structure' },
      { key: 'id', type: 'suggest', label: 'Id', list: 'dl-structures', placeholder: 'village_plains', required: true },
      { key: 'pos', type: 'coords', label: 'Position' },
    ],
    build: (v) => {
      const pos = coords(v.pos);
      return `place ${v.type || 'structure'} ${ns(v.id || 'village_plains')}${pos !== '~ ~ ~' ? ' ' + pos : ''}`;
    },
  },

  locate: {
    name: 'locate', tag: 'world', desc: 'Find the nearest structure, biome, or point of interest.',
    fields: [
      { key: 'type', type: 'select', label: 'Type', options: ['structure', 'biome', 'poi'], default: 'structure' },
      { key: 'id', type: 'suggest', label: 'Id', list: 'dl-structures', placeholder: 'fortress', required: true },
    ],
    build: (v) => `locate ${v.type || 'structure'} ${ns(v.id || 'village_plains')}`,
  },

  forceload: {
    name: 'forceload', tag: 'world', desc: 'Keep chunks loaded even when no player is nearby.',
    fields: [
      { key: 'action', type: 'select', label: 'Action', options: ['add', 'remove', 'remove all', 'query'], default: 'add' },
      { key: 'from', type: 'coords', label: 'From (chunk block pos)' },
      { key: 'to', type: 'coords', label: 'To (optional)' },
    ],
    build: (v) => {
      if (v.action === 'remove all') return 'forceload remove all';
      const from = coords(v.from);
      const to = coords(v.to);
      let s = `forceload ${v.action || 'add'} ${from}`;
      if (v.action !== 'query' && to !== '~ ~ ~' && to !== from) s += ' ' + to;
      return s;
    },
  },

  // ── data / NBT ──────────────────────────────────────────────────────────────
  data: {
    name: 'data', tag: 'data', desc: 'Get, merge, modify, or remove NBT on an entity, block, or storage.',
    fields: [
      { key: 'action', type: 'select', label: 'Action', options: ['get', 'merge', 'modify', 'remove'], default: 'get' },
      { key: 'holder', type: 'select', label: 'Holder', options: ['entity', 'block', 'storage'], default: 'entity' },
      { key: 'sel', type: 'text', label: 'Entity / storage id', list: 'dl-selectors', placeholder: '@s   or   my:storage' },
      { key: 'pos', type: 'coords', label: 'Block position' },
      { key: 'path', type: 'text', label: 'NBT path', placeholder: 'Health' },
      { key: 'data', type: 'text', wide: true, label: 'NBT / modify source',
        placeholder: '{Health:20f}    or    set value 5    or    set from entity @p Pos[0]' },
    ],
    build: (v) => {
      const holder = v.holder === 'block' ? `block ${coords(v.pos)}`
                   : v.holder === 'storage' ? `storage ${v.sel || 'minecraft:my_storage'}`
                   : `entity ${v.sel || '@s'}`;
      switch (v.action) {
        case 'merge':  return `data merge ${holder} ${v.data || '{}'}`;
        case 'remove': return `data remove ${holder} ${v.path || ''}`.trimEnd();
        case 'modify': return `data modify ${holder} ${v.path || ''} ${v.data || 'set value 0'}`.replace(/\s+/g, ' ').trimEnd();
        default:       return `data get ${holder}${v.path ? ' ' + v.path : ''}`;
      }
    },
  },

  item: {
    name: 'item', tag: 'items', desc: 'Replace or modify an item in a specific inventory slot.',
    fields: [
      { key: 'action', type: 'select', label: 'Action', options: ['replace', 'modify'], default: 'replace' },
      { key: 'holder', type: 'select', label: 'Holder', options: ['entity', 'block'], default: 'entity' },
      { key: 'sel', type: 'text', label: 'Entity', list: 'dl-selectors', placeholder: '@s' },
      { key: 'pos', type: 'coords', label: 'Block position' },
      { key: 'slot', type: 'suggest', label: 'Slot', list: 'dl-slots', placeholder: 'weapon.mainhand', default: 'weapon.mainhand', required: true },
      { key: 'item', type: 'suggest', label: 'Item (replace)', list: 'dl-items', placeholder: 'diamond' },
      { key: 'count', type: 'number', label: 'Count', min: 1, placeholder: '1' },
      { key: 'modifier', type: 'text', label: 'Modifier id (modify)', placeholder: 'my:modifier' },
    ],
    build: (v) => {
      const holder = v.holder === 'block' ? `block ${coords(v.pos)}` : `entity ${v.sel || '@s'}`;
      const slot = v.slot || 'weapon.mainhand';
      if (v.action === 'modify') return `item modify ${holder} ${slot} ${v.modifier || 'my:modifier'}`;
      const count = v.count && String(v.count) !== '1' ? ' ' + v.count : '';
      return `item replace ${holder} ${slot} with ${ns(v.item || 'stone')}${count}`;
    },
  },

  loot: {
    name: 'loot', tag: 'items', desc: 'Generate items from a loot table — give, spawn, or insert them.',
    fields: [
      { key: 'method', type: 'select', label: 'Destination', options: [
        { value: 'give', label: 'give → players' },
        { value: 'spawn', label: 'spawn → position' },
        { value: 'insert', label: 'insert → container' }], default: 'give' },
      { key: 't', type: 'target', label: 'Players (give)', default: '@p' },
      { key: 'pos', type: 'coords', label: 'Position (spawn / insert)' },
      { key: 'source', type: 'select', label: 'Source', options: [
        { value: 'loot', label: 'loot table' }, { value: 'kill', label: 'kill (entity drops)' }, { value: 'mine', label: 'mine (block drops)' }], default: 'loot' },
      { key: 'table', type: 'suggest', label: 'Loot table', list: 'dl-loot', placeholder: 'minecraft:chests/simple_dungeon' },
      { key: 'killEntity', type: 'text', label: 'Entity (kill)', list: 'dl-selectors', placeholder: '@e[type=zombie,limit=1]' },
      { key: 'minePos', type: 'coords', label: 'Block (mine)' },
    ],
    build: (v) => {
      const dest = v.method === 'spawn' ? `spawn ${coords(v.pos)}`
                 : v.method === 'insert' ? `insert ${coords(v.pos)}`
                 : `give ${selector(v.t)}`;
      const source = v.source === 'kill' ? `kill ${v.killEntity || '@e[limit=1]'}`
                   : v.source === 'mine' ? `mine ${coords(v.minePos)}`
                   : `loot ${v.table || 'minecraft:chests/simple_dungeon'}`;
      return `loot ${dest} ${source}`;
    },
  },

  // ── scoreboard / progression ─────────────────────────────────────────────────
  bossbar: {
    name: 'bossbar', tag: 'data', desc: 'Create and control custom boss bars.',
    fields: [
      { key: 'action', type: 'select', label: 'Action', options: ['add', 'set', 'remove', 'list', 'get'], default: 'add' },
      { key: 'id', type: 'text', label: 'Bar id', placeholder: 'fox:my_bar' },
      { key: 'prop', type: 'select', label: 'Property (set/get)', options: [
        'name', 'color', 'style', 'value', 'max', 'visible', 'players'], default: 'value' },
      { key: 'value', type: 'text', label: 'Value', placeholder: '50  /  red  /  "Title"  /  true' },
    ],
    build: (v) => {
      const id = v.id || 'fox:my_bar';
      switch (v.action) {
        case 'add':    return `bossbar add ${id} ${textArg(v.value || 'Boss Bar')}`;
        case 'remove': return `bossbar remove ${id}`;
        case 'list':   return 'bossbar list';
        case 'get':    return `bossbar get ${id} ${v.prop || 'value'}`;
        default: {
          let val = v.value || '';
          if (v.prop === 'name') val = textArg(val || 'Boss Bar');
          return `bossbar set ${id} ${v.prop || 'value'} ${val}`.trimEnd();
        }
      }
    },
  },

  advancement: {
    name: 'advancement', tag: 'players', desc: 'Grant or revoke advancements.',
    fields: [
      { key: 'action', type: 'select', label: 'Action', options: ['grant', 'revoke'], default: 'grant' },
      { key: 't', type: 'target', label: 'Target', default: '@s', required: true },
      { key: 'scope', type: 'select', label: 'Scope', options: ['everything', 'only', 'from', 'through', 'until'], default: 'everything' },
      { key: 'advancement', type: 'text', label: 'Advancement', placeholder: 'minecraft:story/mine_diamond' },
      { key: 'criterion', type: 'text', label: 'Criterion (optional)', placeholder: '' },
    ],
    build: (v) => {
      let s = `advancement ${v.action || 'grant'} ${selector(v.t)} ${v.scope || 'everything'}`;
      if (v.scope !== 'everything' && v.advancement) {
        s += ' ' + ns(v.advancement);
        if (v.scope === 'only' && v.criterion) s += ' ' + v.criterion;
      }
      return s;
    },
  },

  recipe: {
    name: 'recipe', tag: 'players', desc: 'Unlock or remove crafting recipes for players.',
    fields: [
      { key: 'action', type: 'select', label: 'Action', options: ['give', 'take'], default: 'give' },
      { key: 't', type: 'target', label: 'Target', default: '@s', required: true },
      { key: 'recipe', type: 'text', label: 'Recipe', placeholder: '* (all) or minecraft:diamond_sword', default: '*' },
    ],
    build: (v) => {
      const r = v.recipe && v.recipe !== '*' ? ns(v.recipe) : '*';
      return `recipe ${v.action || 'give'} ${selector(v.t)} ${r}`;
    },
  },

  team: {
    name: 'team', tag: 'data', desc: 'Create and manage teams (colors, collision, friendly fire…).',
    fields: [
      { key: 'action', type: 'select', label: 'Action', options: ['add', 'join', 'leave', 'modify', 'empty', 'remove', 'list'], default: 'add' },
      { key: 'team', type: 'text', label: 'Team name', placeholder: 'red' },
      { key: 'members', type: 'text', label: 'Members (join)', list: 'dl-selectors', placeholder: '@a' },
      { key: 'display', type: 'text', label: 'Display name (add)', placeholder: 'Red Team' },
      { key: 'key', type: 'select', label: 'Option (modify)', options: [
        'color', 'friendlyFire', 'seeFriendlyInvisibles', 'nametagVisibility', 'collisionRule', 'deathMessageVisibility', 'prefix', 'suffix'], default: 'color' },
      { key: 'val', type: 'text', label: 'Value (modify)', placeholder: 'red / true / always' },
    ],
    build: (v) => {
      const team = v.team || 'team';
      switch (v.action) {
        case 'join':   return `team join ${team} ${v.members || '@s'}`;
        case 'leave':  return `team leave ${v.members || '@s'}`;
        case 'empty':  return `team empty ${team}`;
        case 'remove': return `team remove ${team}`;
        case 'list':   return v.team ? `team list ${team}` : 'team list';
        case 'modify': {
          let val = v.val || '';
          if (v.key === 'prefix' || v.key === 'suffix') val = textArg(val);
          return `team modify ${team} ${v.key || 'color'} ${val}`.trimEnd();
        }
        default:       return v.display ? `team add ${team} ${textArg(v.display)}` : `team add ${team}`;
      }
    },
  },

  trigger: {
    name: 'trigger', tag: 'data', desc: 'Activate a trigger objective (usable by non-op players).',
    fields: [
      { key: 'objective', type: 'text', label: 'Objective', placeholder: 'myTrigger', required: true },
      { key: 'mode', type: 'select', label: 'Mode', options: ['(simple)', 'add', 'set'], default: '(simple)' },
      { key: 'value', type: 'number', label: 'Value', default: 1 },
    ],
    build: (v) => {
      const obj = v.objective || 'myTrigger';
      if (v.mode === 'add') return `trigger ${obj} add ${v.value !== '' ? v.value : 1}`;
      if (v.mode === 'set') return `trigger ${obj} set ${v.value !== '' ? v.value : 1}`;
      return `trigger ${obj}`;
    },
  },

  // ── functions / datapacks ──────────────────────────────────────────────────
  function: {
    name: 'function', tag: 'control', desc: 'Run a datapack function (optionally with arguments).',
    fields: [
      { key: 'id', type: 'text', label: 'Function', placeholder: 'my_pack:my_function', required: true },
      { key: 'args', type: 'text', label: 'Arguments (NBT)', wide: true, placeholder: '{count:5,name:"Bob"}' },
    ],
    build: (v) => `function ${v.id || 'my_pack:my_function'}${v.args ? ' ' + v.args : ''}`,
  },

  schedule: {
    name: 'schedule', tag: 'control', desc: 'Run a function after a delay, or clear a scheduled one.',
    fields: [
      { key: 'action', type: 'select', label: 'Action', options: ['function', 'clear'], default: 'function' },
      { key: 'id', type: 'text', label: 'Function', placeholder: 'my_pack:my_function', required: true },
      { key: 'time', type: 'text', label: 'Delay', placeholder: '10s / 200t / 1d', default: '1t' },
      { key: 'mode', type: 'select', label: 'Mode', options: ['replace', 'append'], default: 'replace' },
    ],
    build: (v) => {
      if (v.action === 'clear') return `schedule clear ${v.id || 'my_pack:my_function'}`;
      const mode = v.mode && v.mode !== 'replace' ? ' ' + v.mode : '';
      return `schedule function ${v.id || 'my_pack:my_function'} ${v.time || '1t'}${mode}`;
    },
  },

  datapack: {
    name: 'datapack', tag: 'control', desc: 'Enable, disable, or list datapacks.',
    fields: [
      { key: 'action', type: 'select', label: 'Action', options: ['enable', 'disable', 'list'], default: 'list' },
      { key: 'name', type: 'text', label: 'Pack name', placeholder: '"file/my_pack"' },
      { key: 'listKind', type: 'select', label: 'List', options: ['', 'available', 'enabled'], default: '' },
    ],
    build: (v) => {
      if (v.action === 'list') return `datapack list${v.listKind ? ' ' + v.listKind : ''}`;
      return `datapack ${v.action} ${v.name || '"file/my_pack"'}`;
    },
  },

  // ── players / movement ─────────────────────────────────────────────────────
  spreadplayers: {
    name: 'spreadplayers', tag: 'players', desc: 'Randomly teleport targets around a center point.',
    fields: [
      { key: 'cx', type: 'text', label: 'Center X', placeholder: '0', default: '~' },
      { key: 'cz', type: 'text', label: 'Center Z', placeholder: '0', default: '~' },
      { key: 'spread', type: 'number', label: 'Min spread distance', min: 0, step: 0.1, default: 5 },
      { key: 'range', type: 'number', label: 'Max range', min: 1, step: 0.1, default: 50 },
      { key: 'maxHeight', type: 'number', label: 'Max height (optional)', placeholder: 'top' },
      { key: 'respectTeams', type: 'bool', label: 'Keep teams together' },
      { key: 't', type: 'target', label: 'Targets', default: '@a', required: true },
    ],
    build: (v) => {
      const h = v.maxHeight !== '' ? ` under ${v.maxHeight}` : '';
      return `spreadplayers ${v.cx || '~'} ${v.cz || '~'} ${v.spread !== '' ? v.spread : 5} ${v.range !== '' ? v.range : 50}${h} ${v.respectTeams ? 'true' : 'false'} ${selector(v.t)}`;
    },
  },

  rotate: {
    name: 'rotate', tag: 'movement', desc: 'Rotate an entity to a yaw/pitch or to face a target.',
    fields: [
      { key: 't', type: 'target', label: 'Target', default: '@s', required: true },
      { key: 'mode', type: 'select', label: 'Mode', options: [
        { value: 'angle', label: 'to yaw/pitch' }, { value: 'facing', label: 'face position' }, { value: 'facing_entity', label: 'face entity' }], default: 'angle' },
      { key: 'yaw', type: 'text', label: 'Yaw', placeholder: '~ / 90' },
      { key: 'pitch', type: 'text', label: 'Pitch', placeholder: '~ / 0' },
      { key: 'pos', type: 'coords', label: 'Position (face position)' },
      { key: 'entity', type: 'text', label: 'Entity (face entity)', list: 'dl-selectors', placeholder: '@p' },
    ],
    build: (v) => {
      const sel = selector(v.t);
      if (v.mode === 'facing') return `rotate ${sel} facing ${coords(v.pos)}`;
      if (v.mode === 'facing_entity') return `rotate ${sel} facing entity ${v.entity || '@p'}`;
      return `rotate ${sel} ${v.yaw || '~'} ${v.pitch || '~'}`;
    },
  },

  spectate: {
    name: 'spectate', tag: 'players', desc: 'Make a spectator view another entity (or stop).',
    fields: [
      { key: 'target', type: 'text', label: 'Entity to spectate', list: 'dl-selectors', placeholder: '@p' },
      { key: 'player', type: 'text', label: 'Spectator', list: 'dl-selectors', placeholder: '@s' },
    ],
    build: (v) => {
      if (!v.target) return 'spectate';
      return `spectate ${v.target}${v.player ? ' ' + v.player : ''}`;
    },
  },

  defaultgamemode: {
    name: 'defaultgamemode', tag: 'players', desc: 'Set the default game mode for new players.',
    fields: [
      { key: 'mode', type: 'select', label: 'Mode', options: ['survival', 'creative', 'adventure', 'spectator'], default: 'survival' },
    ],
    build: (v) => `defaultgamemode ${v.mode || 'survival'}`,
  },

  // ── tick / rng ──────────────────────────────────────────────────────────────
  tick: {
    name: 'tick', tag: 'control', desc: 'Control the game-tick rate — freeze, step, sprint, or set rate.',
    fields: [
      { key: 'action', type: 'select', label: 'Action', options: ['query', 'rate', 'freeze', 'unfreeze', 'step', 'sprint'], default: 'query' },
      { key: 'value', type: 'text', label: 'Value', placeholder: '20  /  100t  /  stop' },
    ],
    build: (v) => {
      if (v.action === 'rate')   return `tick rate ${v.value || '20'}`;
      if (v.action === 'step')   return `tick step ${v.value || '10'}`;
      if (v.action === 'sprint') return `tick sprint ${v.value || '1000'}`;
      return `tick ${v.action || 'query'}`;
    },
  },

  random: {
    name: 'random', tag: 'data', desc: 'Roll a random value within a range.',
    fields: [
      { key: 'action', type: 'select', label: 'Action', options: ['value', 'roll'], default: 'value' },
      { key: 'range', type: 'text', label: 'Range', placeholder: '1..6', default: '1..6', required: true },
      { key: 'sequence', type: 'text', label: 'Sequence id (optional)', placeholder: 'my:sequence' },
    ],
    build: (v) => `random ${v.action || 'value'} ${v.range || '1..6'}${v.sequence ? ' ' + ns(v.sequence) : ''}`,
  },

  // ── messaging ────────────────────────────────────────────────────────────────
  msg: {
    name: 'msg', tag: 'text', desc: 'Send a private message (alias of /tell, /w).',
    fields: [
      { key: 't', type: 'target', label: 'Target', default: '@p', required: true },
      { key: 'message', type: 'text', label: 'Message', wide: true, placeholder: 'psst…', required: true },
    ],
    build: (v) => `msg ${selector(v.t)} ${v.message || ''}`.trimEnd(),
  },

  me: {
    name: 'me', tag: 'text', desc: 'Emote in third person ("* Steve waves").',
    fields: [
      { key: 'action', type: 'text', label: 'Action text', wide: true, placeholder: 'waves hello', required: true },
    ],
    build: (v) => `me ${v.action || ''}`.trimEnd(),
  },

  teammsg: {
    name: 'teammsg', tag: 'text', desc: 'Message only your own team (alias /tm).',
    fields: [
      { key: 'message', type: 'text', label: 'Message', wide: true, placeholder: 'enemy at spawn!', required: true },
    ],
    build: (v) => `teammsg ${v.message || ''}`.trimEnd(),
  },

  // ── server admin ──────────────────────────────────────────────────────────────
  op: {
    name: 'op', tag: 'server', desc: 'Grant or revoke operator status.',
    fields: [
      { key: 'action', type: 'select', label: 'Action', options: ['op', 'deop'], default: 'op' },
      { key: 'player', type: 'text', label: 'Player', placeholder: 'Steve', required: true },
    ],
    build: (v) => `${v.action || 'op'} ${v.player || 'Player'}`,
  },

  kick: {
    name: 'kick', tag: 'server', desc: 'Kick a player from the server.',
    fields: [
      { key: 'player', type: 'text', label: 'Player', list: 'dl-selectors', placeholder: 'Steve', required: true },
      { key: 'reason', type: 'text', label: 'Reason (optional)', wide: true, placeholder: 'Bye!' },
    ],
    build: (v) => `kick ${v.player || 'Player'}${v.reason ? ' ' + v.reason : ''}`,
  },

  ban: {
    name: 'ban', tag: 'server', desc: 'Ban / pardon players or IPs.',
    fields: [
      { key: 'action', type: 'select', label: 'Action', options: ['ban', 'pardon', 'ban-ip', 'pardon-ip'], default: 'ban' },
      { key: 'target', type: 'text', label: 'Player / IP', placeholder: 'Steve', required: true },
      { key: 'reason', type: 'text', label: 'Reason (ban only)', wide: true, placeholder: 'Griefing' },
    ],
    build: (v) => {
      const a = v.action || 'ban';
      const r = (a === 'ban' || a === 'ban-ip') && v.reason ? ' ' + v.reason : '';
      return `${a} ${v.target || 'Player'}${r}`;
    },
  },

  whitelist: {
    name: 'whitelist', tag: 'server', desc: 'Manage the server whitelist.',
    fields: [
      { key: 'action', type: 'select', label: 'Action', options: ['on', 'off', 'add', 'remove', 'list', 'reload'], default: 'list' },
      { key: 'player', type: 'text', label: 'Player (add/remove)', placeholder: 'Steve' },
    ],
    build: (v) => {
      const a = v.action || 'list';
      if (a === 'add' || a === 'remove') return `whitelist ${a} ${v.player || 'Player'}`;
      return `whitelist ${a}`;
    },
  },

  publish: {
    name: 'publish', tag: 'server', desc: 'Open the current singleplayer world to LAN.',
    fields: [
      { key: 'allow', type: 'bool', label: 'Allow cheats' },
      { key: 'mode', type: 'select', label: 'Game mode', options: ['survival', 'creative', 'adventure', 'spectator'], default: 'survival' },
      { key: 'port', type: 'number', label: 'Port', min: 1, max: 65535, placeholder: 'auto' },
    ],
    build: (v) => {
      // publish [allowCommands] [gamemode] [port]
      if (v.port !== '') return `publish ${v.allow ? 'true' : 'false'} ${v.mode || 'survival'} ${v.port}`;
      if (v.allow || (v.mode && v.mode !== 'survival')) return `publish ${v.allow ? 'true' : 'false'} ${v.mode || 'survival'}`;
      return 'publish';
    },
  },

  setidletimeout: {
    name: 'setidletimeout', tag: 'server', desc: 'Auto-kick players idle for N minutes (0 = never).',
    fields: [
      { key: 'minutes', type: 'number', label: 'Minutes', min: 0, default: 0 },
    ],
    build: (v) => `setidletimeout ${v.minutes !== '' ? v.minutes : 0}`,
  },

  'save-all': {
    name: 'save-all', tag: 'server', desc: 'Save the world to disk.',
    fields: [
      { key: 'flush', type: 'bool', label: 'Flush (force immediate)' },
    ],
    build: (v) => `save-all${v.flush ? ' flush' : ''}`,
  },

  list: {
    name: 'list', tag: 'server', desc: 'List online players.',
    fields: [
      { key: 'uuids', type: 'bool', label: 'Show UUIDs' },
    ],
    build: (v) => `list${v.uuids ? ' uuids' : ''}`,
  },

  seed: {
    name: 'seed', tag: 'server', desc: 'Show the world seed.',
    fields: [],
    build: () => 'seed',
  },

  stop: {
    name: 'stop', tag: 'server', desc: 'Stop the server (saves first).',
    fields: [],
    build: () => 'stop',
  },
};

// ── copy button + datalist helpers ─────────────────────────────────────────────

function datalist(id, values) {
  // Suggestions are the short (un-namespaced) ids for items/blocks/etc.;
  // build() prepends "minecraft:" via ns(). Sound/gamerule lists pass full
  // strings already. Either way we just emit the value verbatim.
  return `<datalist id="${id}">${values.map(v => `<option value="${esc(v)}"></option>`).join('')}</datalist>`;
}

// Wire the copy button once the DOM exists. Called from renderCommands via
// event delegation on document — but simplest is to attach after render.
document.addEventListener('click', (e) => {
  const btn = e.target.closest && e.target.closest('#cmd-copy');
  if (!btn) return;
  const out = document.getElementById('cmd-output');
  const text = out ? out.textContent : '';
  if (!text) return;
  copyText(text);
  btn.textContent = 'Copied!';
  btn.classList.add('cmd-copied');
  if (copyResetTimer) clearTimeout(copyResetTimer);
  copyResetTimer = setTimeout(() => {
    btn.textContent = 'Copy';
    btn.classList.remove('cmd-copied');
  }, 1400);
});

function copyText(text) {
  // navigator.clipboard needs a secure context (not always true for file://),
  // so fall back to the legacy execCommand path which works in Electron's
  // Chromium renderer.
  try {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).catch(() => legacyCopy(text));
      return;
    }
  } catch (_) { /* fall through */ }
  legacyCopy(text);
}
function legacyCopy(text) {
  const ta = document.createElement('textarea');
  ta.value = text;
  ta.style.position = 'fixed';
  ta.style.opacity = '0';
  document.body.appendChild(ta);
  ta.select();
  try { document.execCommand('copy'); } catch (_) {}
  document.body.removeChild(ta);
}

// ── saved commands (localStorage) ───────────────────────────────────────────────

const SAVED_KEY = 'fox.commands.saved';
const SAVED_MAX = 50;

function readSaved() {
  try {
    const raw = localStorage.getItem(SAVED_KEY);
    const arr = raw ? JSON.parse(raw) : [];
    return Array.isArray(arr) ? arr.filter(s => typeof s === 'string') : [];
  } catch (_) { return []; }
}
function writeSaved(list) {
  try { localStorage.setItem(SAVED_KEY, JSON.stringify(list.slice(0, SAVED_MAX))); } catch (_) {}
}

function saveCurrent() {
  const out = document.getElementById('cmd-output');
  const text = out ? out.textContent.trim() : '';
  if (!text) return;
  const list = readSaved();
  // De-dupe: move an existing identical entry to the top instead of stacking.
  const existing = list.indexOf(text);
  if (existing !== -1) list.splice(existing, 1);
  list.unshift(text);
  writeSaved(list);
  renderSaved();

  const btn = document.getElementById('cmd-save');
  if (btn) {
    btn.textContent = '★ Saved';
    btn.classList.add('cmd-copied');
    setTimeout(() => { btn.textContent = '★ Save'; btn.classList.remove('cmd-copied'); }, 1200);
  }
}

function renderSaved() {
  const wrap = document.getElementById('cmd-saved-wrap');
  const list = document.getElementById('cmd-saved-list');
  if (!wrap || !list) return;
  const items = readSaved();
  if (!items.length) { wrap.hidden = true; list.innerHTML = ''; return; }
  wrap.hidden = false;
  list.innerHTML = items.map((cmd, i) => `
    <div class="cmd-saved-row" data-idx="${i}">
      <code class="cmd-saved-text" title="${esc(cmd)}">${esc(cmd)}</code>
      <button class="btn btn-sm cmd-saved-copy" data-idx="${i}" type="button" title="Copy">Copy</button>
      <button class="cmd-saved-del" data-idx="${i}" type="button" title="Remove" aria-label="Remove">✕</button>
    </div>
  `).join('');

  for (const btn of list.querySelectorAll('.cmd-saved-copy')) {
    btn.addEventListener('click', () => {
      const cmd = items[parseInt(btn.dataset.idx, 10)];
      if (cmd == null) return;
      copyText(cmd);
      btn.textContent = '✓';
      setTimeout(() => { btn.textContent = 'Copy'; }, 1000);
    });
  }
  for (const btn of list.querySelectorAll('.cmd-saved-del')) {
    btn.addEventListener('click', () => {
      const idx = parseInt(btn.dataset.idx, 10);
      const cur = readSaved();
      cur.splice(idx, 1);
      writeSaved(cur);
      renderSaved();
    });
  }
}

// ── small utils ───────────────────────────────────────────────────────────────

function esc(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

// Escape a value for use inside a CSS attribute selector ([data-key="..."]).
function cssEsc(s) {
  return String(s == null ? '' : s).replace(/["\\]/g, '\\$&');
}
