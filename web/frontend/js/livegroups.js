// Copyright 2026 The Orbit authors. SPDX-License-Identifier: Apache-2.0
// Live TV groups: the quick-select tabs and the "All groups" side menu. Pure functions (no DOM), so they can be
// unit-tested in Node and mirror the TV app's shared Kotlin (shared/):
// - folders: "Sports | NHL Teams" goes into the folder "Sports" as "NHL Teams"; a name without " | " stays at the
//   top level (listed first);
// - pins: the profile's pinned groups, saved in the server profile prefs under PIN_PREF (a JSON array of full group
//   names, most recent first, at most MAX_PINS), shared by the web app and the TV.

export const SEP = ' | ';
export const PIN_PREF = 'liveGroupsPinned';
export const MAX_PINS = 8;
export const RECENT_PREF = 'liveRecent';
export const MAX_RECENT = 12;

/** {folder, short} for "Folder | Rest", or null when the name has no " | " (or nothing before / after it). */
export function splitGroup(name) {
  const s = String(name || '');
  const i = s.indexOf(SEP);
  if (i < 0) return null;
  const folder = s.slice(0, i).trim(), short = s.slice(i + SEP.length).trim();
  return folder && short ? { folder, short } : null;
}

/**
 * The side menu's tree. groups: [{id, name, count?, ...}] in provider order (the special ones, ids starting with
 * "__", are left out). Returns [{type: 'group', group, label}] for the top-level groups first, then
 * [{type: 'folder', name, count, groups: [{group, label}]}] in order of first appearance; count = number of groups.
 */
export function foldGroups(groups) {
  const top = [], folders = new Map();
  for (const g of groups || []) {
    if (!g || String(g.id).startsWith('__')) continue;
    const sp = splitGroup(g.name);
    if (!sp) { top.push({ type: 'group', group: g, label: g.name }); continue; }
    let f = folders.get(sp.folder.toLowerCase());
    if (!f) { f = { type: 'folder', name: sp.folder, count: 0, groups: [] }; folders.set(sp.folder.toLowerCase(), f); }
    f.groups.push({ group: g, label: sp.short }); f.count++;
  }
  return [...top, ...folders.values()];
}

/** Every word of q (case-insensitive) appears in s. */
export function matches(s, q) {
  const words = String(q || '').toLowerCase().split(/\s+/).filter(Boolean);
  if (!words.length) return true;
  const hay = String(s || '').toLowerCase();
  return words.every((w) => hay.includes(w));
}

/**
 * The tree filtered by a search: groups whose full name matches (so "sports nhl" finds "Sports | NHL Teams");
 * a folder whose name matches keeps all its groups. Folders left are returned open (open: true).
 */
export function filterTree(tree, q) {
  if (!String(q || '').trim()) return tree;
  const out = [];
  for (const n of tree) {
    if (n.type === 'group') { if (matches(n.group.name, q)) out.push(n); continue; }
    const gs = matches(n.name, q) ? n.groups : n.groups.filter((x) => matches(x.group.name, q));
    if (gs.length) out.push({ ...n, groups: gs, open: true });
  }
  return out;
}

/** The pins pref value (an array, or its JSON text from an older client) as a clean list: names, no repeats, at most 8. */
export function normPins(v) {
  let a = v;
  if (typeof a === 'string') { try { a = JSON.parse(a); } catch { a = []; } }
  if (!Array.isArray(a)) return [];
  const out = [];
  for (const x of a) {
    if (typeof x !== 'string' || !x.trim() || out.includes(x)) continue;
    out.push(x);
    if (out.length >= MAX_PINS) break;
  }
  return out;
}
export const isPinned = (pins, name) => normPins(pins).includes(name);
/** Pin (to the front: most recent first, the oldest falls off past 8) or unpin a group by its full name. */
export function togglePin(pins, name) {
  const p = normPins(pins);
  if (p.includes(name)) return p.filter((x) => x !== name);
  return [name, ...p].slice(0, MAX_PINS);
}

/**
 * The quick-select tabs: ★ Favourites, All, Recent, the pinned groups (found by name; a pin whose group is gone
 * is skipped), the selected group when it is none of these (picked in the side menu), then "All groups ☰".
 * Returns [{id, name, kind: 'fav'|'all'|'recent'|'pin'|'sel'|'menu', group?}].
 */
export function quickTabs(groups, pins, selectedId) {
  const gs = groups || [];
  const fav = gs.find((g) => g.id === '__fav'), all = gs.find((g) => g.id === '__all');
  const tabs = [
    { id: '__fav', name: '★ Favourites', kind: 'fav', group: fav },
    { id: '__all', name: (all && all.name) || 'All', kind: 'all', group: all },
    { id: '__recent', name: 'Recent', kind: 'recent' },
  ];
  const byName = new Map();
  for (const g of gs) if (!String(g.id).startsWith('__') && !byName.has(g.name)) byName.set(g.name, g);
  for (const n of normPins(pins)) { const g = byName.get(n); if (g) tabs.push({ id: g.id, name: g.name, kind: 'pin', group: g }); }
  if (selectedId && !tabs.some((t) => t.id === selectedId)) {
    const g = gs.find((x) => x.id === selectedId);
    if (g) tabs.push({ id: g.id, name: g.name, kind: 'sel', group: g });
  }
  tabs.push({ id: '__menu', name: 'All groups', kind: 'menu' });
  return tabs;
}

/** The recent channels pref after watching ch: most recent first, no repeats, at most 12 (slim copies). */
export function addRecent(list, ch) {
  if (!ch || !ch.key) return Array.isArray(list) ? list : [];
  const slim = { key: ch.key, name: ch.name, logo: ch.logo || null, number: ch.number || null, group: ch.group || null };
  return [slim, ...(Array.isArray(list) ? list : []).filter((x) => x && x.key !== ch.key)].slice(0, MAX_RECENT);
}
