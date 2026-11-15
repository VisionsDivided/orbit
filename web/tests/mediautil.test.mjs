// Copyright 2026 The Orbit authors. SPDX-License-Identifier: Apache-2.0
import test from 'node:test';
import assert from 'node:assert/strict';
import { resumable, epCode, sortSeasons, sortEpisodes, mediaRef, stopBody, sendStop, hlsErrorText, errorText } from '../frontend/js/mediautil.js';

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

test('stopBody: the stop report, position rounded', () => {
  assert.deepEqual(JSON.parse(stopBody('s'.repeat(32), 61.234)), { session: 's'.repeat(32), positionSecs: 61.2, state: 'stopped' });
  assert.equal(JSON.parse(stopBody('a', -3)).positionSecs, 0);
});

test('sendStop: a text/plain beacon first, else a keepalive fetch with the CSRF token', async () => {
  const sent = [];
  const nav = { sendBeacon: (url, blob) => { sent.push([url, blob]); return true; } };
  assert.equal(sendStop('abc', 10, { navigator: nav, fetch: () => { throw new Error('no'); }, csrf: 't' }), 'beacon');
  assert.equal(sent[0][0], '/v1/media/progress');
  assert.equal(sent[0][1].type, 'text/plain');
  assert.deepEqual(JSON.parse(await sent[0][1].text()), { session: 'abc', positionSecs: 10, state: 'stopped' });
  const calls = [];
  const fetch = (url, o) => { calls.push([url, o]); return Promise.resolve({}); };
  assert.equal(sendStop('abc', 5, { navigator: { sendBeacon: () => false }, fetch, csrf: 'tok' }), 'fetch', 'the beacon queue was full');
  assert.equal(sendStop('abc', 5, { navigator: {}, fetch, csrf: 'tok' }), 'fetch', 'no sendBeacon');
  assert.equal(calls[0][1].keepalive, true);
  assert.deepEqual(calls[0][1].headers, { 'Content-Type': 'application/json', 'X-CSRF-Token': 'tok' });
  assert.equal(sendStop('abc', 5, { navigator: {}, fetch: () => { throw new Error('x'); } }), null);
});

test('hlsErrorText: the server\'s own words, else a plain fallback', () => {
  const xhr = (status, body) => ({ response: { code: status }, networkDetails: { responseType: 'text', responseText: body } });
  assert.equal(hlsErrorText(xhr(502, '{"error": "Plex refused to start transcoding this title (it answered 400)."}')),
    'Plex refused to start transcoding this title (it answered 400).');
  const buf = new TextEncoder().encode('{"error":"Plex stopped sending this title"}').buffer;
  assert.equal(hlsErrorText({ response: { code: 502 }, networkDetails: { responseType: 'arraybuffer', response: buf } }), 'Plex stopped sending this title');
  assert.equal(hlsErrorText(xhr(502, '<html>')), 'The server could not send this title (HTTP 502).');
  assert.equal(hlsErrorText({ type: 'networkError' }), 'The home server did not answer.');
  assert.equal(hlsErrorText({ type: 'mediaError' }), 'This browser could not play the title.');
  assert.equal(hlsErrorText(null), 'The home server did not answer.');
  assert.equal(errorText('{"error": 5}'), '');
});
