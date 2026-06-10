// First-run wizard.
//
// Shown once, the first time a signed-in user lands on the home screen.
// Three steps, full-screen overlay so the user can't miss it:
//   1. Welcome — pitch Fox Client's features in 4 bullets
//   2. Pick a profile template — replaces the default "Default" profile's
//      settings with the selected template (Casual, Anarchy, Ranked PvP,
//      Modded, Vanilla-safe). User can skip.
//   3. Ready — "Hit PLAY to start" with a Done button that flips
//      firstRunComplete and dismisses the overlay.
//
// The wizard owns its own DOM under #wizard-root and tears itself down on
// completion. No router integration — it's a transient overlay that
// app.js shows after the home navigate() based on the settings flag.

import { escapeHtml } from '../util.js';

let wizardState = {
  step: 0,
  template: null,
  templates: [],
  onDismiss: null, // resolves the showWizard() promise
};

/**
 * Open the first-run wizard. Returns a promise that resolves when the user
 * either completes or dismisses it. Caller is responsible for the
 * settings:patch that flips firstRunComplete.
 */
export function showWizard() {
  return new Promise((resolve) => {
    wizardState = {
      step: 0,
      template: null,
      templates: [],
      onDismiss: resolve,
    };
    mountOverlay();
    loadTemplates();
    render();
  });
}

function mountOverlay() {
  let host = document.getElementById('wizard-root');
  if (host) return;
  host = document.createElement('div');
  host.id = 'wizard-root';
  host.className = 'wizard-backdrop';
  host.setAttribute('role', 'dialog');
  host.setAttribute('aria-modal', 'true');
  document.body.appendChild(host);
}

function teardown() {
  const host = document.getElementById('wizard-root');
  if (host) host.remove();
  const onDismiss = wizardState.onDismiss;
  wizardState.onDismiss = null;
  if (onDismiss) onDismiss();
}

async function loadTemplates() {
  try {
    wizardState.templates = await window.fox.profileTemplates();
  } catch (_) {
    wizardState.templates = [];
  }
  render();
}

function render() {
  const host = document.getElementById('wizard-root');
  if (!host) return;
  const stepMarkup = wizardState.step === 0 ? renderWelcome()
                   : wizardState.step === 1 ? renderTemplate()
                   : renderReady();
  host.innerHTML = `
    <div class="wizard-shell">
      <div class="wizard-header">
        <img src="assets/fox.png" alt="" class="wizard-logo" />
        <div class="wizard-step-dots">
          ${[0,1,2].map(i => `<span class="wizard-dot ${i === wizardState.step ? 'active' : ''} ${i < wizardState.step ? 'done' : ''}"></span>`).join('')}
        </div>
        <button class="wizard-skip" id="wizard-skip" title="Skip the welcome tour">Skip</button>
      </div>
      <div class="wizard-body">${stepMarkup}</div>
      <div class="wizard-footer">
        ${wizardState.step > 0
          ? '<button class="btn" id="wizard-back">← Back</button>'
          : '<span></span>'}
        ${wizardState.step < 2
          ? `<button class="btn btn-primary" id="wizard-next">${wizardState.step === 1 && !wizardState.template ? 'Skip template →' : 'Next →'}</button>`
          : '<button class="btn btn-primary" id="wizard-done">✓ Start playing</button>'}
      </div>
    </div>
  `;
  wireFooter();
  if (wizardState.step === 1) wireTemplateCards();
}

function renderWelcome() {
  return `
    <h1 class="wizard-title">Welcome to Fox Client</h1>
    <p class="wizard-sub">A fast, honest Minecraft launcher with a baked-in client mod. Free and open.</p>
    <div class="wizard-features">
      <div class="wizard-feature">
        <div class="wizard-feature-ico">🎮</div>
        <div>
          <div class="wizard-feature-title">One-click play</div>
          <div class="wizard-feature-desc">Auto-installs Minecraft, Fabric, and the Fox Client mod. No external launcher needed.</div>
        </div>
      </div>
      <div class="wizard-feature">
        <div class="wizard-feature-ico">👥</div>
        <div>
          <div class="wizard-feature-title">Profiles &amp; isolation</div>
          <div class="wizard-feature-desc">Each profile gets its own worlds, mods, and even a separate Microsoft account if you want.</div>
        </div>
      </div>
      <div class="wizard-feature">
        <div class="wizard-feature-ico">🔍</div>
        <div>
          <div class="wizard-feature-title">Built-in Modrinth browser</div>
          <div class="wizard-feature-desc">Search, install, and update mods without leaving the launcher.</div>
        </div>
      </div>
      <div class="wizard-feature">
        <div class="wizard-feature-ico">🦊</div>
        <div>
          <div class="wizard-feature-title">50+ in-game features</div>
          <div class="wizard-feature-desc">Minimap, FPS / ping / coords HUD, cosmetic capes, zoom, shulker tooltips, and more.</div>
        </div>
      </div>
    </div>
  `;
}

function renderTemplate() {
  if (!wizardState.templates.length) {
    return `
      <h1 class="wizard-title">Pick a starting template</h1>
      <p class="wizard-sub muted">Loading templates…</p>
    `;
  }
  return `
    <h1 class="wizard-title">Pick a starting template</h1>
    <p class="wizard-sub">Sets sensible defaults for your first profile. You can change everything later, and add more profiles any time.</p>
    <div class="wizard-template-grid">
      ${wizardState.templates.map(t => `
        <button class="wizard-template ${wizardState.template === t.id ? 'selected' : ''}" data-template-id="${escapeHtml(t.id)}">
          <div class="wizard-template-label">${escapeHtml(t.label || t.id)}</div>
          <div class="wizard-template-desc">${escapeHtml(t.description || '')}</div>
        </button>
      `).join('')}
    </div>
    <div class="wizard-template-hint muted">Not sure? Skip — you can apply any template later from the Profiles tab.</div>
  `;
}

function renderReady() {
  const tplName = wizardState.template
    ? (wizardState.templates.find(t => t.id === wizardState.template) || {}).label || wizardState.template
    : null;
  return `
    <h1 class="wizard-title">You're ready to play</h1>
    <p class="wizard-sub">${tplName
      ? `Your <strong>Default</strong> profile is set up with the <strong>${escapeHtml(tplName)}</strong> template.`
      : `Your <strong>Default</strong> profile is set up with vanilla settings.`}</p>
    <div class="wizard-ready-actions">
      <div class="wizard-ready-tip">
        <div class="wizard-ready-tip-ico">▶</div>
        <div>Hit the big <strong>PLAY</strong> button on the Home screen to launch.</div>
      </div>
      <div class="wizard-ready-tip">
        <div class="wizard-ready-tip-ico">☰</div>
        <div>Visit <strong>Profiles</strong> any time to add more profiles or browse Modrinth.</div>
      </div>
      <div class="wizard-ready-tip">
        <div class="wizard-ready-tip-ico">⚙</div>
        <div>The <strong>Settings</strong> tab has RAM, theme, Discord rich-presence, and more.</div>
      </div>
    </div>
  `;
}

function wireFooter() {
  const skip = document.getElementById('wizard-skip');
  const back = document.getElementById('wizard-back');
  const next = document.getElementById('wizard-next');
  const done = document.getElementById('wizard-done');
  if (skip) skip.addEventListener('click', finish);
  if (back) back.addEventListener('click', () => { if (wizardState.step > 0) { wizardState.step--; render(); } });
  if (next) next.addEventListener('click', advance);
  if (done) done.addEventListener('click', finish);
}

function wireTemplateCards() {
  for (const card of document.querySelectorAll('.wizard-template')) {
    card.addEventListener('click', () => {
      wizardState.template = card.dataset.templateId;
      // Visual selection update without full re-render
      for (const c of document.querySelectorAll('.wizard-template')) c.classList.remove('selected');
      card.classList.add('selected');
      const next = document.getElementById('wizard-next');
      if (next) next.textContent = 'Next →';
    });
  }
}

async function advance() {
  if (wizardState.step === 1 && wizardState.template) {
    // Apply the picked template to the Default profile so the user lands on
    // home with sensible settings. The applyProfileTemplate IPC handles the
    // partial-update; we only catch errors silently because the wizard is
    // best-effort UX, not a critical path.
    try {
      await window.fox.applyProfileTemplate('default', wizardState.template);
    } catch (_) { /* non-fatal */ }
  }
  wizardState.step++;
  render();
}

async function finish() {
  try { await window.fox.patchSettings({ firstRunComplete: true }); }
  catch (_) { /* non-fatal */ }
  teardown();
}

