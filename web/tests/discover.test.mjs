// Copyright 2026 The Orbit authors. SPDX-License-Identifier: Apache-2.0
import test from 'node:test';
import assert from 'node:assert/strict';
import { tmdbImage, etaSecs, etaText, seasonsText, addToRow, wantsMore, seerrStatus } from '../frontend/js/discoverlogic.js';

test('Discover artwork goes through /v1/discover/image, also when the server signed it into /img', () => {
  const tmdb = 'https://image.tmdb.org/t/p/w342/abc_D-1.jpg';
  const want = '/v1/discover/image?p=' + encodeURIComponent('/w342/abc_D-1.jpg');
  assert.equal(tmdbImage(tmdb), want);
  // what the web app really gets: app.py signs every poster / backdrop URL in a JSON answer
  assert.equal(tmdbImage('/img?u=' + encodeURIComponent(tmdb) + '&s=0123abcd'), want);
  // anything else is not Discover artwork
  for (const u of ['/img?u=' + encodeURIComponent('https://example.com/p.jpg') + '&s=1', 'https://image.tmdb.org/t/p/w9999/a.jpg',
    'https://image.tmdb.org/t/p/w342/a.svg', '/img?bad', '', null, 'https://evil.example.com/t/p/w342/a.jpg']) assert.equal(tmdbImage(u), '', String(u));
});

test('download eta: an ISO time from the server, seconds from older callers', () => {
  const now = Date.parse('2026-11-14T10:00:00.000Z');
  assert.equal(etaSecs('2026-11-14T10:18:00.000Z', now), 1080);
  assert.equal(etaSecs(600, now), 600);
  assert.equal(etaSecs(null, now), 0);
  assert.equal(etaSecs('soon', now), 0);
  assert.deepEqual([etaText(30), etaText(1080), etaText(7200), etaText(3 * 86400)], ['1 min', '18 min', '2 h', '3 days']);
});

test('status badges read the server\'s Download (percent, ISO eta)', () => {
  const eta = new Date(Date.now() + 18 * 60000 + 5000).toISOString();
  const st = seerrStatus({ status: 'processing', download: { percent: 60, eta, size: 5, sizeLeft: 2, items: 2 } });
  assert.equal(st.cls, 'st-dl'); assert.equal(st.pct, 60); assert.equal(st.label, 'Downloading 60% · ~18 min');
  // a past eta is left out instead of "~1 min"
  assert.equal(seerrStatus({ status: 'processing', download: { percent: 99, eta: '2020-01-01T00:00:00.000Z' } }).label, 'Downloading 99%');
  assert.equal(seerrStatus({ status: 'requestable' }).action, true);
  assert.equal(seerrStatus({ status: 'available' }).label, 'Available');
  assert.equal(seerrStatus({ status: 'pending', requestStatus: 'approved' }).label, 'Approved');
  assert.equal(seerrStatus({ status: 'requestable' }, { status: 'pending', mediaStatus: 'pending' }).label, 'Requested');
});

test('season choice wording', () => {
  assert.equal(seasonsText([1, 2, 3], 3), 'all seasons');
  assert.equal(seasonsText([2], 1), 'season 2');
  assert.equal(seasonsText([1, 3], 3), 'seasons 1, 3');
});

test('a row adds each title once, and only its type', () => {
  const row = { type: 'tv', items: [], seen: new Set() };
  const page = [{ type: 'movie', tmdb: 1 }, { type: 'tv', tmdb: 2 }, { type: 'tv', tmdb: 2 }, null, { type: 'tv' }];
  assert.deepEqual(addToRow(row, page).map((t) => t.tmdb), [2]);
  assert.deepEqual(addToRow(row, [{ type: 'tv', tmdb: 2 }, { type: 'tv', tmdb: 3 }]).map((t) => t.tmdb), [3]);
  const all = { type: null, items: [], seen: new Set() };
  assert.equal(addToRow(all, page).length, 2, 'a mixed row keeps films and shows');
});

test('a row loads its next page near its end only', () => {
  const row = { page: 1, pages: 3, busy: false };
  assert.equal(wantsMore(row, { index: 10, count: 20, near: 6 }), false);
  assert.equal(wantsMore(row, { index: 14, count: 20, near: 6 }), true);
  assert.equal(wantsMore(row, { scrollLeft: 0, width: 1000, scrollWidth: 4000, px: 1400 }), false);
  assert.equal(wantsMore(row, { scrollLeft: 1700, width: 1000, scrollWidth: 4000, px: 1400 }), true);
  assert.equal(wantsMore({ ...row, page: 3 }, { index: 19, count: 20 }), false, 'no more pages');
  assert.equal(wantsMore({ ...row, busy: true }, { index: 19, count: 20 }), false, 'one page at a time');
});
