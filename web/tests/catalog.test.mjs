// Copyright 2026 The Orbit authors. SPDX-License-Identifier: Apache-2.0
import test from 'node:test';
import assert from 'node:assert/strict';
import { normalizeItem, normalizeRows, normalizeCollection, iptvRef, pathKind, normKind, ROUTES, getJson, configure, forgetMissing, tmdbProxy } from '../frontend/js/catalog.js';

test('an Item is normalised (contract fields, other names tolerated)', () => {
  const it = normalizeItem({ key: 'm|p0|7', kind: 'movie', title: 'Ember Line', year: '2025', poster: '/img?a', backdrop: null, rating: 7.9,
    genres: ['Thriller'], source: 'iptv', colors: ['#c3261b', '#000000'], progress: { secs: 60, dur: 600 }, id: '7', conn: null, locked: true });
  assert.equal(it.key, 'm|p0|7'); assert.equal(it.kind, 'movie'); assert.equal(it.year, 2025); assert.equal(it.source, 'iptv');
  assert.deepEqual(it.progress, { secs: 60, dur: 600 }); assert.equal(it.locked, true); assert.equal(it.id, '7'); assert.equal(it.conn, null);
  const s = normalizeItem({ key: 's|c1|9', type: 'series', name: 'Fathom Station', genre: 'Drama, Sci-fi', source: 'Plex', colors: null, next: { id: 'e5', season: 2, num: 4 } });
  assert.equal(s.kind, 'show'); assert.equal(s.title, 'Fathom Station'); assert.deepEqual(s.genres, ['Drama', 'Sci-fi']); assert.equal(s.source, 'plex');
  assert.equal(s.colors, null); assert.equal(s.next.num, 4);
  assert.equal(normalizeItem({ title: 'no key' }), null);
});

test('rows drop empty ones and bad items', () => {
  const rows = normalizeRows([{ id: 'continue', title: 'Continue watching', items: [{ key: 'm|p0|1', title: 'A' }, null] }, { id: 'recent', title: 'Recent', items: [] }]);
  assert.equal(rows.length, 1); assert.equal(rows[0].items.length, 1);
});

test('collections keep source and kind', () => {
  const c = normalizeCollection({ id: 'c1', name: 'Saga', count: 8, source: 'plex', kind: 'movie' });
  assert.equal(c.source, 'plex'); assert.equal(c.kind, 'movie'); assert.equal(c.count, 8);
  assert.equal(normalizeCollection({}), null);
});

test('IPTV items open the /api detail page with their VOD id; Plex items do not', () => {
  assert.deepEqual(iptvRef({ key: 'm|p0|7', source: 'iptv' }), { kind: 'movie', id: '7' });
  assert.deepEqual(iptvRef({ key: 's|p1|9', source: 'iptv' }), { kind: 'series', id: 'p1~9' });
  assert.deepEqual(iptvRef({ key: 's|p1|9', source: 'iptv', id: 'p1~9', conn: null }), { kind: 'series', id: 'p1~9' });
  assert.deepEqual(iptvRef({ key: 'm|123' }), { kind: 'movie', id: '123' });
  assert.equal(iptvRef({ key: 'm|c1|abc', source: 'plex', id: 'abc', conn: 'c1' }), null);
  assert.equal(iptvRef({ key: 'x' }), null);
});

test('kinds and routes', () => {
  assert.equal(pathKind('series'), 'shows'); assert.equal(pathKind('movie'), 'movies'); assert.equal(normKind('tv'), 'show');
  assert.equal(ROUTES.browse('movies', { source: 'local', genre: 'Sci-fi', sort: 'added', offset: 0, limit: 60 }), '/v1/catalog/movies/browse?source=local&genre=Sci-fi&sort=added&offset=0&limit=60');
  assert.equal(ROUTES.collection('a b'), '/v1/catalog/collections/a%20b');
});

const res = (status, body) => ({ status, ok: status >= 200 && status < 300, json: async () => body });

test('a missing route (404) answers {missing}, and is remembered for a while', async () => {
  forgetMissing();
  let calls = 0;
  const f = async () => { calls++; return res(404, { error: 'not found' }); };
  assert.deepEqual((await getJson('/v1/catalog/home', 'home', f)).missing, true);
  assert.deepEqual((await getJson('/v1/catalog/home', 'home', f)).missing, true);
  assert.equal(calls, 1);
  forgetMissing();
});

test('401: missing in tvBro mode, signed out in Orbit mode', async () => {
  forgetMissing();
  let out = 0;
  configure({ accounts: () => false, onUnauthorized: () => { out++; } });
  assert.equal((await getJson('/v1/catalog/home', null, async () => res(401, {}))).missing, true);
  configure({ accounts: () => true });
  await assert.rejects(getJson('/v1/catalog/home', null, async () => res(401, {})), (e) => e.status === 401);
  assert.equal(out, 1);
  await assert.rejects(getJson('/v1/catalog/home', null, async () => res(500, { error: 'boom' })), /boom/);
  const ok = await getJson('/v1/catalog/home', null, async () => res(200, { rows: [] }));
  assert.deepEqual(ok, { ok: true, data: { rows: [] } });
});

test('TMDB artwork goes through the server proxy; anything else gives no image', () => {
  assert.equal(tmdbProxy('https://image.tmdb.org/t/p/w342/abc_D-1.jpg'), '/v1/discover/image?p=%2Fw342%2Fabc_D-1.jpg');
  assert.equal(tmdbProxy('https://image.tmdb.org/t/p/original/x.webp'), '/v1/discover/image?p=%2Foriginal%2Fx.webp');
  for (const u of ['https://image.tmdb.org/t/p/w999/a.jpg', 'https://image.tmdb.org/t/p/w500/a.svg', 'http://image.tmdb.org/t/p/w500/a.jpg',
    'https://image.tmdb.org/t/p/w500/../a.jpg', 'https://evil.example.com/t/p/w500/a.jpg', null]) assert.equal(tmdbProxy(u), '', String(u));
});