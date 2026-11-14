// Copyright 2026 The Orbit authors. SPDX-License-Identifier: Apache-2.0
// Unit tests for the Live TV groups: folders, search, pins pref, quick tabs, recent. Run: node --test web/tests/livegroups.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import * as L from '../frontend/js/livegroups.js';

const G = (id, name, count = 3) => ({ id, name, count });
const GROUPS = [
  G('__fav', 'Favorites'), G('__all', 'All', 900),
  G('p0:1', 'Sports | NHL Teams'), G('p0:2', 'UK | News'), G('p0:3', 'Movies'), G('p0:4', 'Sports | NFL Replays'),
  G('p0:5', 'US | Locals'), G('p0:6', 'Kids'), G('p0:7', 'sports | Golf'), G('p0:8', 'Ambiance | Channels'),
  G('p0:9', ' | Odd'), G('p0:10', 'Trailing | '), G('p0:11', 'A|B'),
];

test('splitGroup: text before the first " | " is the folder', () => {
  assert.deepEqual(L.splitGroup('Sports | NHL Teams'), { folder: 'Sports', short: 'NHL Teams' });
  assert.deepEqual(L.splitGroup('Sports | NHL | Replays'), { folder: 'Sports', short: 'NHL | Replays' });
  assert.equal(L.splitGroup('Movies'), null);
  assert.equal(L.splitGroup('A|B'), null, 'only " | " with spaces');
  assert.equal(L.splitGroup(' | Odd'), null, 'nothing before');
  assert.equal(L.splitGroup('Trailing | '), null, 'nothing after');
  assert.equal(L.splitGroup(null), null);
});

test('foldGroups: top-level groups first, then folders in order of first appearance, specials left out', () => {
  const t = L.foldGroups(GROUPS);
  assert.deepEqual(t.filter((n) => n.type === 'group').map((n) => n.label), ['Movies', 'Kids', ' | Odd', 'Trailing | ', 'A|B']);
  assert.ok(t.findIndex((n) => n.type === 'folder') > t.map((n) => n.type).lastIndexOf('group'), 'groups before folders');
  const f = t.filter((n) => n.type === 'folder');
  assert.deepEqual(f.map((n) => [n.name, n.count]), [['Sports', 3], ['UK', 1], ['US', 1], ['Ambiance', 1]]);
  assert.deepEqual(f[0].groups.map((x) => x.label), ['NHL Teams', 'NFL Replays', 'Golf'], 'case-insensitive folder, prefix removed');
  assert.equal(f[0].groups[0].group.id, 'p0:1');
  assert.ok(!t.some((n) => n.type === 'group' && n.group.id.startsWith('__')));
  assert.deepEqual(L.foldGroups(null), []);
});

test('foldGroups: about 200 groups fold into a short list', () => {
  const many = [];
  for (const pre of ['Sports', 'UK', 'US', 'CA', 'FR', 'DE', 'Ambiance', 'Kids']) for (let i = 0; i < 25; i++) many.push(G(`p0:${pre}${i}`, `${pre} | Group ${i}`));
  const t = L.foldGroups(many);
  assert.equal(t.length, 8);
  assert.ok(t.every((n) => n.count === 25));
});

test('filterTree: "hockey" style search over full names, folders open', () => {
  const t = L.foldGroups(GROUPS);
  const r = L.filterTree(t, 'nhl');
  assert.equal(r.length, 1);
  assert.equal(r[0].name, 'Sports'); assert.equal(r[0].open, true);
  assert.deepEqual(r[0].groups.map((x) => x.label), ['NHL Teams']);
  assert.deepEqual(L.filterTree(t, 'sports')[0].groups.length, 3, 'a matching folder keeps all its groups');
  assert.deepEqual(L.filterTree(t, 'sports nfl')[0].groups.map((x) => x.label), ['NFL Replays'], 'every word');
  assert.deepEqual(L.filterTree(t, 'KIDS').map((n) => n.label), ['Kids']);
  assert.equal(L.filterTree(t, '  '), t);
  assert.deepEqual(L.filterTree(t, 'nothing-like-this'), []);
});

test('pins pref: clean list, most recent first, at most 8', () => {
  assert.deepEqual(L.normPins(null), []);
  assert.deepEqual(L.normPins('["UK | News","UK | News",3,""]'), ['UK | News'], 'JSON text, repeats and junk removed');
  assert.deepEqual(L.normPins('not json'), []);
  let p = [];
  p = L.togglePin(p, 'Sports | NHL Teams');
  p = L.togglePin(p, 'UK | News');
  assert.deepEqual(p, ['UK | News', 'Sports | NHL Teams']);
  assert.ok(L.isPinned(p, 'UK | News'));
  p = L.togglePin(p, 'UK | News');
  assert.deepEqual(p, ['Sports | NHL Teams'], 'unpin');
  for (let i = 0; i < 10; i++) p = L.togglePin(p, 'G' + i);
  assert.equal(p.length, L.MAX_PINS);
  assert.equal(p[0], 'G9'); assert.ok(!p.includes('Sports | NHL Teams'), 'the oldest falls off');
  assert.equal(L.PIN_PREF, 'liveGroupsPinned');
  assert.deepEqual(JSON.parse(JSON.stringify(p)), p, 'stored as a plain JSON array');
});

test('quickTabs: Favourites, All, Recent, pins in order, the selected group, All groups', () => {
  const tabs = L.quickTabs(GROUPS, ['UK | News', 'Gone | Group', 'Sports | NHL Teams'], '__all');
  assert.deepEqual(tabs.map((t) => t.id), ['__fav', '__all', '__recent', 'p0:2', 'p0:1', '__menu']);
  assert.deepEqual(tabs.map((t) => t.kind), ['fav', 'all', 'recent', 'pin', 'pin', 'menu']);
  const sel = L.quickTabs(GROUPS, ['UK | News'], 'p0:6');
  assert.deepEqual(sel.map((t) => t.id), ['__fav', '__all', '__recent', 'p0:2', 'p0:6', '__menu']);
  assert.equal(sel[4].kind, 'sel');
  assert.equal(L.quickTabs(GROUPS, [], 'p0:2').filter((t) => t.id === 'p0:2').length, 1);
  assert.equal(L.quickTabs([], [], null).length, 4);
});

test('addRecent: most recent first, no repeats, at most 12', () => {
  let r = [];
  for (let i = 0; i < 15; i++) r = L.addRecent(r, { key: 'k' + i, name: 'C' + i, extra: 'x' });
  assert.equal(r.length, L.MAX_RECENT);
  assert.equal(r[0].key, 'k14');
  assert.equal(r[0].extra, undefined, 'slim copies');
  r = L.addRecent(r, { key: 'k10', name: 'C10' });
  assert.equal(r[0].key, 'k10');
  assert.equal(r.filter((x) => x.key === 'k10').length, 1);
  assert.deepEqual(L.addRecent(null, null), []);
});
