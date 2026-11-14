// Copyright 2026 The Orbit authors. SPDX-License-Identifier: Apache-2.0
// Signing out of an account on this browser leaves nothing of that account or profile behind, so the next person
// starts clean. The server keeps all account data; the browser only caches a little (checked 2026-11-14):
// - localStorage  tvbro.ui: where the UI was, including the channel last played (uistate.js);
//                 tvbro.reminders: reminders from before they moved to the server (reminders.js no longer writes it)
// - sessionStorage tvbro.parental: this tab's parental unlock; tvbro.sid: the tab's stream id (api.js)
// - IndexedDB, Cache Storage, service workers: none used (any database named tvbro* / orbit* is removed anyway).
// Kept: tvbro.reload (sessionStorage), the guard against reload loops in signin.js; it names nobody.

const PREFIXES = ['tvbro.', 'orbit.'];
const KEEP = new Set(['tvbro.reload']);

/** Removes this app's keys from the given Storage-like objects; returns the keys removed. */
export function clearStores(...stores) {
  const gone = [];
  for (const st of stores) {
    if (!st) continue;
    try {
      const keys = [];
      for (let i = 0; i < st.length; i++) { const k = st.key(i); if (k != null) keys.push(k); }
      for (const k of keys) if (PREFIXES.some((p) => k.startsWith(p)) && !KEEP.has(k)) { st.removeItem(k); gone.push(k); }
    } catch { /* storage blocked (private mode): nothing was kept there */ }
  }
  return gone;
}

/** Deletes this app's IndexedDB databases (none today; for safety if one is added). */
export async function clearDatabases(idb) {
  if (!idb || typeof idb.databases !== 'function') return [];
  let list = [];
  try { list = await idb.databases(); } catch { return []; }
  const gone = [];
  for (const d of list || []) {
    if (d && d.name && PREFIXES.some((p) => d.name.startsWith(p.slice(0, -1)))) { try { idb.deleteDatabase(d.name); gone.push(d.name); } catch {} }
  }
  return gone;
}

/** Forget the account's cached data in this browser (and tell the modules holding a copy in memory). */
export async function forgetAccountHere() {
  const g = globalThis;
  let local = null, session = null;
  try { local = g.localStorage; } catch {}
  try { session = g.sessionStorage; } catch {}
  clearStores(local, session);
  await clearDatabases(g.indexedDB);
  try { g.dispatchEvent(new Event('tvbro-account-cleared')); } catch {}
}
