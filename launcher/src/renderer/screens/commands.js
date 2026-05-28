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
    case 'scores': {
      const container = form.querySelector(`.cmd-pairs[data-key="${cssEsc(f.key)}"]`);
      const out = [];
      if (container) {
        for (const row of container.querySelectorAll('.cmd-pair-row')) {
          const k = row.querySelector('.cmd-pair-k').value.trim();
          const val = row.querySelector('.cmd-pair-v').value.trim();
          if (k) out.push({ k, v: val });
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

// Assemble the [components] suffix for /give items.
function itemComponents(values) {
  const parts = [];
  if (values.name) {
    // SNBT single-quoted string holding a JSON text component. Single quotes
    // inside the JSON are escaped so a name like "Bob's" stays valid.
    const json = JSON.stringify({ text: values.name, italic: false }).replace(/'/g, "\\'");
    parts.push(`minecraft:custom_name='${json}'`);
  }
  const ench = values.enchantments || [];
  if (ench.length) {
    const levels = ench.map(e => `"${ns(e.k)}":${e.v || 1}`).join(',');
    parts.push(`minecraft:enchantments={${levels}}`);
  }
  if (values.unbreakable) parts.push('minecraft:unbreakable={}');
  if (values.glint)       parts.push('minecraft:enchantment_glint_override=true');
  return parts.length ? `[${parts.join(',')}]` : '';
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
    name: 'give', tag: 'items', desc: 'Give an item (with optional name, enchantments) to players.',
    fields: [
      { key: 't', type: 'target', label: 'Target', default: '@p', required: true },
      { key: 'item', type: 'suggest', label: 'Item', list: 'dl-items', placeholder: 'diamond_sword', default: 'diamond_sword', required: true },
      { key: 'count', type: 'number', label: 'Count', min: 1, max: 6400, default: 1 },
      { key: 'name', type: 'text', label: 'Custom name', placeholder: 'Excalibur' },
      { key: 'enchantments', type: 'enchantments', label: 'Enchantments', wide: true },
      { key: 'unbreakable', type: 'bool', label: 'Unbreakable' },
      { key: 'glint', type: 'bool', label: 'Force enchant glint' },
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
    name: 'attribute', tag: 'entities', desc: 'Set or modify an entity attribute base value.',
    fields: [
      { key: 't', type: 'target', label: 'Target', default: '@s', required: true },
      { key: 'attr', type: 'suggest', label: 'Attribute', list: 'dl-attributes', placeholder: 'max_health',
        default: 'max_health', required: true },
      { key: 'op', type: 'select', label: 'Action', options: [
        { value: 'base set', label: 'base set' }, { value: 'base get', label: 'base get' }, { value: 'get', label: 'get (total)' }], default: 'base set' },
      { key: 'value', type: 'number', label: 'Value', step: 0.1, default: 20 },
    ],
    build: (v) => {
      const attr = ns(v.attr || 'max_health');
      if (v.op === 'base set') return `attribute ${selector(v.t)} ${attr} base set ${v.value !== '' ? v.value : 0}`;
      return `attribute ${selector(v.t)} ${attr} ${v.op || 'base get'}`;
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
