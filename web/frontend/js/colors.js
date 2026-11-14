// Copyright 2026 The Orbit authors. SPDX-License-Identifier: Apache-2.0
// Colour maths for the cinematic backdrop (direction 5): the two colours of a title's backdrop
// (dominant m1, secondary m2), the dark base mb, and the corner mesh gradient made from them.
// Pure functions (no DOM), so node:test covers them; backdrop.js does the canvas part.

/** The orbit palette: used when a title has no colours and none can be read from its image. */
export const ORBIT = Object.freeze(['#6b45e6', '#2fd3ff']);

/** '#abc' / '#aabbcc' / 'rgb(1,2,3)' / [r,g,b] -> [r,g,b] (0..255), or null. */
export function parseColor(c) {
  if (Array.isArray(c) && c.length >= 3 && c.slice(0, 3).every((n) => Number.isFinite(+n))) return c.slice(0, 3).map((n) => clamp(Math.round(+n)));
  if (typeof c !== 'string') return null;
  const s = c.trim();
  let m = /^#?([0-9a-f]{3})$/i.exec(s);
  if (m) return [...m[1]].map((x) => parseInt(x + x, 16));
  m = /^#?([0-9a-f]{6})([0-9a-f]{2})?$/i.exec(s);
  if (m) return [0, 2, 4].map((i) => parseInt(m[1].slice(i, i + 2), 16));
  m = /^rgba?\(\s*(\d+)\s*[, ]\s*(\d+)\s*[, ]\s*(\d+)/i.exec(s);
  if (m) return [m[1], m[2], m[3]].map((x) => clamp(+x));
  return null;
}
const clamp = (n) => Math.max(0, Math.min(255, n));
export const toHex = ([r, g, b]) => '#' + [r, g, b].map((n) => clamp(Math.round(n)).toString(16).padStart(2, '0')).join('');
const rgba = ([r, g, b], a) => `rgba(${r},${g},${b},${a})`;

/** HSL lightness and saturation (0..1) of [r,g,b]. */
export function hsl([r, g, b]) {
  r /= 255; g /= 255; b /= 255;
  const max = Math.max(r, g, b), min = Math.min(r, g, b), l = (max + min) / 2;
  const d = max - min;
  const s = d === 0 ? 0 : d / (1 - Math.abs(2 * l - 1));
  return { s, l };
}
const dist = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2]);

/** The dark base under the mesh: m1 taken down to about 7-9 % (the mockup's pairs: #c3261b -> #110304). */
export function baseOf(m1) {
  const c = parseColor(m1) || parseColor(ORBIT[0]);
  return toHex(c.map((n) => n * 0.09));
}

/** A usable [m1, m2] pair from whatever a server or the image gave; null when nothing usable. */
export function normalizePair(colors) {
  if (!Array.isArray(colors) || !colors.length) return null;
  const a = parseColor(colors[0]); if (!a) return null;
  const b = parseColor(colors[1]) || [0, 0, 0];
  return [toHex(a), toHex(b)];
}

/**
 * Two colours from RGBA pixel data (a downscaled copy of the backdrop: 64x36 is plenty).
 * m1: the most common colourful mid-tone (saturation counts extra); m2: the most common colour that
 * is clearly different from m1 (it may be near black or near white, as in the mockup's pairs).
 * Returns ['#rrggbb', '#rrggbb'] or null (no pixels).
 */
export function extractPair(data, step = 4) {
  if (!data || data.length < 4) return null;
  const buckets = new Map();   // 4 bits per channel -> {n, r, g, b}
  for (let i = 0; i + 3 < data.length; i += step) {
    if (data[i + 3] < 128) continue;
    const r = data[i], g = data[i + 1], b = data[i + 2];
    const k = ((r >> 4) << 8) | ((g >> 4) << 4) | (b >> 4);
    const e = buckets.get(k);
    if (e) { e.n++; e.r += r; e.g += g; e.b += b; } else buckets.set(k, { n: 1, r, g, b });
  }
  if (!buckets.size) return null;
  const list = [...buckets.values()].map((e) => ({ n: e.n, c: [e.r / e.n, e.g / e.n, e.b / e.n] }));
  let best = null, bestS = -1;
  for (const e of list) {
    const { s, l } = hsl(e.c);
    if (l < 0.1 || l > 0.86) continue;
    const score = e.n * (0.25 + s) * (1 - Math.abs(l - 0.45));
    if (score > bestS) { bestS = score; best = e; }
  }
  if (!best) best = list.reduce((a, b) => (b.n > a.n ? b : a));
  let sec = null, secN = -1;
  for (const e of list) if (dist(e.c, best.c) > 90 && e.n > secN) { secN = e.n; sec = e; }
  const m1 = best.c.map(Math.round);
  const m2 = sec ? sec.c.map(Math.round) : (hsl(m1).l > 0.5 ? [0, 0, 0] : [240, 244, 255]);
  return [toHex(m1), toHex(m2)];
}

/** The CSS background of the mesh layer (DESIGN-SPEC-5 §6), as rgba stops (no color-mix needed). */
export function meshBackground(pair) {
  const p = normalizePair(pair) || ORBIT;
  const m1 = parseColor(p[0]), m2 = parseColor(p[1]);
  return [
    `radial-gradient(72% 82% at 100% 0%, ${rgba(m1, 0.88)}, transparent 70%)`,
    `radial-gradient(55% 65% at 0% 100%, ${rgba(m2, 0.22)}, transparent 72%)`,
    `radial-gradient(60% 55% at 0% 0%, ${rgba(m1, 0.4)}, transparent 70%)`,
    `radial-gradient(60% 60% at 100% 100%, ${rgba(m2, 0.16)}, transparent 70%)`,
    baseOf(p[0]),
  ].join(', ');
}
