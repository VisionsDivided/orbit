// Copyright 2026 The Orbit authors. SPDX-License-Identifier: Apache-2.0
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { vodKey, escapeStep, timeLine, epLine, timeAt, bufferedFracs, tapSide, SHORTCUTS } from '../frontend/js/vodctl.js';

const fmt = (s) => { s = Math.floor(s); return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`; };

test('Space and K only play / pause (never "restart" or the focused button)', () => {
  for (const k of [' ', 'k', 'K', 'MediaPlayPause']) assert.equal(vodKey(k), 'toggle', k);
  assert.equal(vodKey(' ', { onSlider: true }), 'toggle');
});

test('the film player keys', () => {
  assert.equal(vodKey('ArrowLeft'), 'back10');
  assert.equal(vodKey('ArrowRight'), 'fwd10');
  assert.equal(vodKey('j'), 'back10');
  assert.equal(vodKey('L'), 'fwd30');
  assert.equal(vodKey('ArrowUp'), 'volUp');
  assert.equal(vodKey('ArrowDown'), 'volDown');
  assert.equal(vodKey('m'), 'mute');
  assert.equal(vodKey('f'), 'fs');
  assert.equal(vodKey('c'), 'subs');
  assert.equal(vodKey('N'), 'next');
  assert.equal(vodKey('Escape'), 'escape');
  assert.equal(vodKey('Backspace'), 'escape');
  assert.equal(vodKey('Enter'), null);          // the focused control (player.js / vodbar.js)
  assert.equal(vodKey('Tab'), null);
  // on the volume slider Left / Right change the volume
  assert.equal(vodKey('ArrowLeft', { onSlider: true }), 'volDown');
  assert.equal(vodKey('ArrowRight', { onSlider: true }), 'volUp');
});

test('Escape: menu, then full screen, then close', () => {
  assert.equal(escapeStep({ menuOpen: true, fullscreen: true }), 'menu');
  assert.equal(escapeStep({ menuOpen: false, fullscreen: true }), 'fullscreen');
  assert.equal(escapeStep({ menuOpen: false, fullscreen: false }), 'close');
});

test('time line and episode line', () => {
  assert.equal(timeLine(2379, 2421, false, fmt), '39:39 / 40:21');
  assert.equal(timeLine(2379, 2421, true, fmt), '39:39 / −0:42');
  assert.equal(timeLine(65, 0, false, fmt), '1:05');
  assert.equal(epLine('S1 E6 · The Long Night'), 'S1 · E6 — The Long Night');
  assert.equal(epLine('S01E06 - Pilot'), 'S1 · E6 — Pilot');
  assert.equal(epLine('S2 E10'), 'S2 · E10');
  assert.equal(epLine('Recording · Channel'), 'Recording · Channel');
  assert.equal(epLine(''), '');
});

test('progress bar maths', () => {
  assert.equal(timeAt(150, 100, 200, 1000), 250);
  assert.equal(timeAt(50, 100, 200, 1000), 0);
  assert.equal(timeAt(400, 100, 200, 1000), 1000);
  assert.deepEqual(bufferedFracs([[0, 100], [500, 500], [800, 1200]], 1000), [[0, 0.1], [0.8, 1]]);
  assert.equal(tapSide(50, 390), 'back');
  assert.equal(tapSide(340, 390), 'fwd');
  assert.equal(tapSide(195, 390), null);
  assert.ok(SHORTCUTS.some(([k]) => k.includes('Space')));
});

// The owner's rule: every control shows and hides together. One element (.vb) holds them all, only it fades,
// and only the layer's .chrome class (one timer in vodbar.js) toggles it.
test('the film controls show and hide as one (one class, one timer, one fade)', () => {
  const css = readFileSync(new URL('../frontend/css/orbit.css', import.meta.url), 'utf8');
  const js = readFileSync(new URL('../frontend/js/vodbar.js', import.meta.url), 'utf8');
  const start = css.indexOf('film / episode player (vodbar.js)');
  assert.ok(start > 0);
  const block = css.slice(start);
  // the only opacity transition is on .vb itself
  const rules = [...block.matchAll(/([^{}]+)\{([^{}]*)\}/g)].map((m) => [m[1].trim(), m[2]]);
  const fades = rules.filter(([, body]) => /transition:[^;]*opacity/.test(body)).map(([sel]) => sel);
  assert.deepEqual(fades, ['.vb'], 'only .vb fades');
  assert.ok(rules.some(([sel, body]) => sel === '#player-layer.chrome .vb' && /opacity:\s*1/.test(body)));
  // no part of the controls has its own opacity / visibility / display toggle by class
  for (const [sel, body] of rules) {
    if (/\.vb-(top|bottom|prog|row|left|mid|right|time|title|ep|clock|toptitle)\b/.test(sel) && /chrome/.test(sel)) assert.doesNotMatch(body, /opacity|visibility/, sel);
  }
  // the controls live inside one element, and one timer toggles one class
  assert.match(js, /const el = h\('div', \{ class: 'vb' \}, top, bottom\)/);
  assert.match(js, /const bottom = h\('div', \{ class: 'vb-bottom' \}, prog, row\)/);
  assert.equal((js.match(/classList\.(add|remove)\('chrome'\)/g) || []).length, 3, 'show() adds, the timer and hide() remove');
  assert.equal((js.match(/hideTimer = setTimeout/g) || []).length, 2, 'one timer (re-armed in place)');
});
