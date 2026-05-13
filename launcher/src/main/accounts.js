// Multi-account store — ~/.foxlauncher/accounts.json
//
// Manages a list of Microsoft / Minecraft accounts that the user has signed
// into.  Only non-sensitive display fields (username, uuid, savedAt) are
// stored here; the msRefreshToken lives in the same file so we can refresh
// silently, but the Minecraft accessToken is NOT persisted (it's short-lived
// and re-derived on demand via getValid()).
//
// Schema:
//   {
//     accounts: [
//       {
//         id:             string,   // random hex tag (not the MC UUID)
//         username:       string,
//         uuid:           string,   // Minecraft UUID (no hyphens)
//         msRefreshToken: string,
//         savedAt:        number,   // ms timestamp
//         guest:          boolean,  // true for offline/guest entries
//       }
//     ],
//     activeAccountId: string | null
//   }
//
// Migration: if an old-style ~/.foxlauncher/auth.json exists and accounts.json
// does not, importLegacy() moves the single cached record into this structure
// so existing users don't get logged out on upgrade.

const fs   = require('fs');
const path = require('path');
const crypto = require('crypto');

const paths = require('./paths');

const ACCOUNTS_PATH = path.join(paths.root, 'accounts.json');

// ---- internal helpers ----

function generateId() {
  return crypto.randomBytes(8).toString('hex');
}

function read() {
  try {
    const raw = JSON.parse(fs.readFileSync(ACCOUNTS_PATH, 'utf8'));
    if (!Array.isArray(raw.accounts)) raw.accounts = [];
    return raw;
  } catch (_) {
    return { accounts: [], activeAccountId: null };
  }
}

function write(data) {
  paths.ensureAll();
  const tmp = ACCOUNTS_PATH + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(data, null, 2), { mode: 0o600 });
  fs.renameSync(tmp, ACCOUNTS_PATH);
}

// ---- migration ----

/**
 * If accounts.json doesn't exist yet but the old auth.json does, import it
 * as the first entry so existing users survive the upgrade without re-login.
 * Runs once — no-op afterwards.
 */
function importLegacy() {
  if (fs.existsSync(ACCOUNTS_PATH)) return; // already migrated
  try {
    const legacy = JSON.parse(fs.readFileSync(paths.auth, 'utf8'));
    if (!legacy || !legacy.uuid) return;
    const id = generateId();
    const data = {
      accounts: [{
        id,
        username:       legacy.username || 'Unknown',
        uuid:           legacy.uuid,
        msRefreshToken: legacy.msRefreshToken || null,
        savedAt:        legacy.savedAt || Date.now(),
        guest:          !!legacy.guest,
      }],
      activeAccountId: id,
    };
    write(data);
  } catch (_) {
    // No legacy auth or corrupt — start fresh
  }
}

// ---- public API ----

/** Return the full store (after migrating legacy if needed). */
function getStore() {
  importLegacy();
  return read();
}

/** List accounts — returns only display-safe fields (no refresh token). */
function list() {
  const { accounts } = getStore();
  return accounts.map(({ id, username, uuid, savedAt, guest }) => ({
    id, username, uuid, savedAt, guest: !!guest,
  }));
}

/** Get the full record for the active account (includes msRefreshToken). */
function getActive() {
  const store = getStore();
  if (!store.activeAccountId) return null;
  return store.accounts.find(a => a.id === store.activeAccountId) || null;
}

/** Get the id of the currently active account. */
function getActiveId() {
  return getStore().activeAccountId;
}

/**
 * Add or update an account record.
 * If an account with the same `uuid` already exists, update it in place.
 * Returns the account id.
 *
 * @param {{ username, uuid, msRefreshToken, savedAt, guest }} record
 * @param {boolean} makeActive  If true, set this account as active.
 */
function upsert(record, makeActive = true) {
  const store = getStore();
  const existing = store.accounts.find(a => a.uuid === record.uuid);
  let id;
  if (existing) {
    id = existing.id;
    Object.assign(existing, {
      username:       record.username,
      msRefreshToken: record.msRefreshToken ?? existing.msRefreshToken,
      savedAt:        record.savedAt || Date.now(),
      guest:          !!record.guest,
    });
  } else {
    id = generateId();
    store.accounts.push({
      id,
      username:       record.username,
      uuid:           record.uuid,
      msRefreshToken: record.msRefreshToken || null,
      savedAt:        record.savedAt || Date.now(),
      guest:          !!record.guest,
    });
  }
  if (makeActive) store.activeAccountId = id;
  write(store);
  return id;
}

/**
 * Update only the refresh token (and savedAt) for an existing account.
 * Used by the silent-refresh flow so stale tokens don't block re-login.
 */
function updateTokens(id, { msRefreshToken, savedAt }) {
  const store = getStore();
  const account = store.accounts.find(a => a.id === id);
  if (!account) return false;
  if (msRefreshToken !== undefined) account.msRefreshToken = msRefreshToken;
  if (savedAt !== undefined) account.savedAt = savedAt;
  write(store);
  return true;
}

/**
 * Switch the active account.
 * Returns the new active account record (display fields only), or null.
 */
function setActive(id) {
  const store = getStore();
  const found = store.accounts.find(a => a.id === id);
  if (!found) return null;
  store.activeAccountId = id;
  write(store);
  return { id: found.id, username: found.username, uuid: found.uuid, savedAt: found.savedAt, guest: !!found.guest };
}

/**
 * Remove an account.  If it was the active one, auto-switch to the most
 * recently saved remaining account.
 * Returns the new activeAccountId (or null if no accounts remain).
 */
function remove(id) {
  const store = getStore();
  store.accounts = store.accounts.filter(a => a.id !== id);
  if (store.activeAccountId === id) {
    // Pick the most recently saved remaining account
    const sorted = [...store.accounts].sort((a, b) => (b.savedAt || 0) - (a.savedAt || 0));
    store.activeAccountId = sorted.length ? sorted[0].id : null;
  }
  write(store);
  return store.activeAccountId;
}

/** True iff the accounts file exists with at least one entry. */
function hasAny() {
  const store = getStore();
  return store.accounts.length > 0;
}

module.exports = { list, getActive, getActiveId, upsert, updateTokens, setActive, remove, hasAny, importLegacy };
