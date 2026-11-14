// Copyright 2026 The Orbit authors. SPDX-License-Identifier: Apache-2.0
import test from 'node:test';
import assert from 'node:assert/strict';
import { resumable, epCode, sortSeasons, sortEpisodes, mediaRef } from '../frontend/js/mediautil.js';

test('resumable: past 30 s, before the last 30 s, not watched', () => {
  assert.equal(resumable(null), false);
  assert.equal(resumable({ secs: 10, dur: 600 }), false);
  assert.equal(resumable({ secs: 120, dur: 600 }), true);
  assert.equal(resumable({ secs: 580, dur: 600 }), false);
  assert.equal(resumable({ secs: 120, dur: 0 }), true);
  assert.equal(resumable({ secs: 120, dur: 600, watched: true }), false);
});

test('epCode', () => {
  assert.equal(epCode({ season: 1, episode: 2 }), 'S1 E2');
  assert.equal(epCode({ season: 2, num: 5 }), 'S2 E5');
  assert.equal(epCode({ episode: 3 }), 'E3');
  assert.equal(epCode(null), '');
});

test('seasons and episodes in order, specials last', () => {
  assert.deepEqual(sortSeasons([{ season: 2 }, { season: 0 }, { season: 1 }, { season: null }]).map((s) => s.season), [1, 2, 0, null]);
  assert.deepEqual(sortEpisodes([{ episode: 3 }, { episode: 1 }, {}]).map((e) => e.episode), [1, 3, undefined]);
});

test('mediaRef reads catalogue keys of media-server titles only', () => {
  assert.deepEqual(mediaRef('m|0123456789abcdef|10'), { kind: 'movie', conn: '0123456789abcdef', id: '10' });
  assert.deepEqual(mediaRef('s|0123456789abcdef|abc-1'), { kind: 'show', conn: '0123456789abcdef', id: 'abc-1' });
  assert.equal(mediaRef('m|p0|123'), null);
  assert.equal(mediaRef('m|0123456789abcdef|../x'), null);
});
