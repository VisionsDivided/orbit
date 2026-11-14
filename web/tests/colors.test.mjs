// Copyright 2026 The Orbit authors. SPDX-License-Identifier: Apache-2.0
import test from 'node:test';
import assert from 'node:assert/strict';
import { parseColor, toHex, baseOf, normalizePair, extractPair, meshBackground, ORBIT } from '../frontend/js/colors.js';

test('colours parse from hex, rgb() and arrays', () => {
  assert.deepEqual(parseColor('#c3261b'), [195, 38, 27]);
  assert.deepEqual(parseColor('#fff'), [255, 255, 255]);
  assert.deepEqual(parseColor('rgb(1, 2, 3)'), [1, 2, 3]);
  assert.deepEqual(parseColor([300, -4, 7]), [255, 0, 7]);
  assert.equal(parseColor('nope'), null);
  assert.equal(toHex([195, 38, 27]), '#c3261b');
});

test('the dark base is the main colour at about 9 %', () => {
  assert.equal(baseOf('#c3261b'), '#120302');
  assert.equal(baseOf(null), baseOf(ORBIT[0]));
});

test('a server pair is normalised; nothing usable is null', () => {
  assert.deepEqual(normalizePair(['#C3261B', 'rgb(0,0,0)']), ['#c3261b', '#000000']);
  assert.deepEqual(normalizePair(['#16968a']), ['#16968a', '#000000']);
  assert.equal(normalizePair(null), null);
  assert.equal(normalizePair(['x', 'y']), null);
});

function pixels(list) {      // [[r,g,b,count], ...] -> RGBA bytes
  const out = [];
  for (const [r, g, b, n] of list) for (let i = 0; i < n; i++) out.push(r, g, b, 255);
  return Uint8ClampedArray.from(out);
}

test('m1 is the dominant colourful mid-tone, m2 a clearly different common colour', () => {
  // mostly near-black, a big red area, a little white
  const [m1, m2] = extractPair(pixels([[5, 5, 8, 600], [200, 40, 30, 300], [240, 240, 240, 40]]), 4);
  const r1 = parseColor(m1), r2 = parseColor(m2);
  assert.ok(r1[0] > 150 && r1[1] < 80, 'm1 is the red: ' + m1);
  assert.ok(r2[0] < 30 && r2[1] < 30, 'm2 is the dark one: ' + m2);
});

test('a one-colour image still gives two colours', () => {
  const p = extractPair(pixels([[30, 150, 140, 100]]));
  assert.equal(p.length, 2);
  assert.notEqual(p[0], p[1]);
  assert.equal(extractPair(new Uint8ClampedArray(0)), null);
});

test('transparent pixels are ignored', () => {
  const data = Uint8ClampedArray.from([255, 0, 0, 0, 255, 0, 0, 0, 20, 100, 200, 255]);
  const [m1] = extractPair(data);
  assert.ok(parseColor(m1)[2] > 150);
});

test('the mesh has the four corner gradients over the base', () => {
  const css = meshBackground(['#c3261b', '#000000']);
  assert.equal((css.match(/radial-gradient/g) || []).length, 4);
  assert.ok(css.includes('rgba(195,38,27,0.88)'));
  assert.ok(css.trim().endsWith('#120302'));
  assert.ok(meshBackground(null).includes('rgba(107,69,230,0.88)'));   // orbit fallback
});
