// Copyright 2026 The Orbit authors. SPDX-License-Identifier: Apache-2.0
// Live TV in the shared control bar (vodbar.js live mode): keys, the programme lines and progress, the channel line.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { liveKey, vodKey, progFrac, progTitle, progLine, nextLine, chLine, holdStep, LIVE_SHORTCUTS } from '../frontend/js/vodctl.js';

const hm = (t) => { const d = new Date(t * 1000); return `${String(d.getUTCHours()).padStart(2, '0')}:${String(d.getUTCMinutes()).padStart(2, '0')}`; };
const P = { title: 'Evening News', start: 72000, stop: 75600 };   // 20:00 – 21:00 (UTC)

test('live: Space and K only play / pause, as in films (never a menu, never the focused button)', () => {
  for (const k of [' ', 'Spacebar', 'k', 'K', 'MediaPlayPause']) assert.equal(liveKey(k), 'toggle', k);
  assert.equal(liveKey(' ', { onSlider: true }), 'toggle');
  assert.equal(liveKey('Enter'), null, 'Enter is the focused button / the controls, decided by the bar');
});

test('live: channel up / down keys as before (arrows, page keys, remote channel keys)', () => {
  for (const k of ['ArrowUp', 'PageUp', 'ChannelUp', 'MediaTrackNext', 'n']) assert.equal(liveKey(k), 'chUp', k);
  for (const k of ['ArrowDown', 'PageDown', 'ChannelDown', 'MediaTrackPrevious', 'p']) assert.equal(liveKey(k), 'chDown', k);
  assert.equal(liveKey('ArrowLeft'), 'close'); assert.equal(liveKey('ArrowRight'), 'close');
  assert.equal(liveKey('ArrowLeft', { onSlider: true }), 'volDown');
  assert.equal(liveKey('ArrowRight', { onSlider: true }), 'volUp');
  for (const d of '0123456789') assert.equal(liveKey(d), null, 'digits are channel numbers (player.js)');
  assert.equal(liveKey('Escape'), 'escape'); assert.equal(liveKey('m'), 'mute'); assert.equal(liveKey('f'), 'fs');
  assert.equal(liveKey('i'), 'info'); assert.equal(liveKey('ContextMenu'), 'settings');
  assert.equal(liveKey('+'), 'volUp'); assert.equal(liveKey('-'), 'volDown');
  // films keep their own keys: up / down is the volume there
  assert.equal(vodKey('ArrowUp'), 'volUp');
  assert.ok(LIVE_SHORTCUTS.some(([k, w]) => k.includes('Space') && /pause/i.test(w)));
});

test('programme progress: fraction of the programme that has aired, clamped', () => {
  assert.equal(progFrac(P, 72000), 0);
  assert.equal(progFrac(P, 73800), 0.5);
  assert.equal(progFrac(P, 80000), 1);
  assert.equal(progFrac(P, 60000), 0);
  assert.equal(progFrac(null, 73800), null);
  assert.equal(progFrac({ start: 5, stop: 5 }, 5), null, 'a programme without length has no bar');
});

test('programme lines: title, time range with minutes left, next', () => {
  assert.equal(progTitle(P), 'Evening News');
  assert.equal(progTitle({ title: 'Quiz', ep: 'S2 E5', sub: 'Finals' }), 'Quiz · S2 E5 Finals');
  assert.equal(progTitle(null), '');
  assert.equal(progLine(P, 73800, hm), '20:00 – 21:00 · 30 min left');
  assert.equal(progLine(P, 75590, hm), '20:00 – 21:00 · 1 min left');
  assert.equal(progLine(P, 90000, hm), '20:00 – 21:00', 'catch-up / not on now: just the range');
  assert.equal(progLine(null, 1, hm), '');
  assert.equal(nextLine({ title: 'Weather', start: 75600 }, hm), 'Next: 21:00 · Weather');
  assert.equal(nextLine(null, hm), '');
});

test('channel line and the catch-up hold acceleration', () => {
  assert.equal(chLine({ number: 101, name: 'Alpha One' }), '101  Alpha One');
  assert.equal(chLine({ name: 'No Number' }), 'No Number');
  assert.equal(chLine(null), '');
  assert.deepEqual([0, 3, 4, 8, 12, 40].map(holdStep), [10, 10, 20, 30, 60, 60]);
});

// live TV uses the same bar as films: no second overlay with its own timer or fade
test('live has no overlay of its own: player.js hands every mode to vodbar.js', () => {
  const js = readFileSync(new URL('../frontend/js/player.js', import.meta.url), 'utf8');
  const css = readFileSync(new URL('../frontend/css/style.css', import.meta.url), 'utf8') + readFileSync(new URL('../frontend/css/orbit.css', import.meta.url), 'utf8');
  for (const gone of ['pl-info', 'pl-qs', 'qs-btn', 'player-top', 'pl-clock', 'showChrome', 'renderQs', "classList.add('chrome')"]) {
    assert.ok(!js.includes(gone), 'player.js still has ' + gone);
  }
  for (const gone of ['.pl-info', '.pl-qs', '.qs-btn', '.player-top', '.pl-clock']) assert.ok(!css.includes(gone), 'CSS still has ' + gone);
  assert.match(js, /bar = createVodBar\(\{/);
  assert.match(js, /live: isVod \? null : \{/);
});
