// Resources hub — the tab that owns the side-tools that don't fit anywhere
// else. Currently houses the MCStacker-style Command Generator and the
// pixel-art Skin Editor; future home for anything else of that shape (loot
// table editor, command-block JSON, particle previewer…).
//
// Implementation is intentionally thin: this file just renders the tab strip
// + a content slot, and delegates to the existing per-tool screen renderers.
// Each tool keeps its own state internally, so switching tabs and back
// rebuilds it from scratch — clean and avoids stale-DOM bugs.

import { renderCommands }     from './commands.js';
import { renderSkinEditor }   from './skinEditor.js';
import { renderWorldBackups } from './worldBackups.js';

const TABS = [
  { id: 'commands', label: '⌘ Command Generator', render: renderCommands },
  { id: 'skin',     label: '🎨 Skin Editor',       render: renderSkinEditor },
  { id: 'backups',  label: '💾 World Backups',     render: renderWorldBackups },
];

let activeTab = 'commands';

export async function renderResources(mount) {
  mount.innerHTML = `
    <div class="rsc-header">
      <h1 class="screen-title" style="margin:0;">Resources</h1>
      <div class="rsc-sub muted">Side-tools — build Minecraft commands, paint your own skin, and back up your worlds.</div>
    </div>
    <div class="rsc-tabs" role="tablist">
      ${TABS.map(t => `
        <button class="rsc-tab ${t.id === activeTab ? 'active' : ''}" role="tab"
                aria-selected="${t.id === activeTab}" data-tab="${t.id}">
          ${t.label}
        </button>
      `).join('')}
    </div>
    <div class="rsc-pane" id="rsc-pane"></div>
  `;
  for (const btn of mount.querySelectorAll('.rsc-tab')) {
    btn.addEventListener('click', () => selectTab(btn.dataset.tab, mount));
  }
  selectTab(activeTab, mount);
}

function selectTab(id, mount) {
  const tab = TABS.find(t => t.id === id) || TABS[0];
  activeTab = tab.id;
  for (const btn of mount.querySelectorAll('.rsc-tab')) {
    const active = btn.dataset.tab === tab.id;
    btn.classList.toggle('active', active);
    btn.setAttribute('aria-selected', String(active));
  }
  const pane = mount.querySelector('#rsc-pane');
  if (!pane) return;
  pane.innerHTML = '';
  try { tab.render(pane); }
  catch (err) { pane.innerHTML = `<div class="error">Failed to render ${tab.label}: ${err.message}</div>`; }
}
