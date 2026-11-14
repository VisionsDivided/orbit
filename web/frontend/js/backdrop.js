// Copyright 2026 The Orbit authors. SPDX-License-Identifier: Apache-2.0
// The cinematic backdrop under every screen (DESIGN-SPEC-5 §6): a mesh in the featured title's two
// colours, its artwork top-right fading into the mesh, and a vignette. A new title cross-fades the
// whole layer (900 ms; instant with reduced motion). Colours come from the item (`colors`, from the
// server); when they are null they are read from the backdrop image itself on a small canvas.
// The image must be same-origin to be read (the server's /img and /v1/media/image proxies are);
// anything else (or a failed read) falls back to the orbit palette.

import { meshBackground, extractPair, normalizePair, ORBIT } from './colors.js';

const scene = () => document.getElementById('scene');
const pairCache = new Map();          // image url -> [m1, m2]
let cur = { art: null, pair: null, opts: '' };
let token = 0;

/** Can a canvas read this image? (same origin only: the /img and /v1 proxies, data: URLs) */
export function readable(url) {
  if (!url) return false;
  if (url.startsWith('data:') || url.startsWith('/')) return !url.startsWith('//');
  try { return new URL(url, location.href).origin === location.origin; } catch { return false; }
}

function loadImage(url) {
  return new Promise((resolve) => {
    if (!url) { resolve(null); return; }
    const im = new Image();
    im.decoding = 'async';
    im.onload = () => resolve(im);
    im.onerror = () => resolve(null);
    im.src = url;
  });
}

/** [m1, m2] read from a loaded image (a 64x36 copy is sampled), or null. */
export function samplePair(im) {
  try {
    const c = document.createElement('canvas');
    c.width = 64; c.height = 36;
    const g = c.getContext('2d', { willReadFrequently: true });
    g.drawImage(im, 0, 0, c.width, c.height);
    return extractPair(g.getImageData(0, 0, c.width, c.height).data);
  } catch { return null; }   // tainted canvas (cross-origin) or no 2D context
}

/** The colours of an image url (cached). */
export async function colorsOf(url) {
  if (!url) return null;
  if (pairCache.has(url)) return pairCache.get(url);
  if (!readable(url)) return null;
  const im = await loadImage(url);
  const p = im ? samplePair(im) : null;
  pairCache.set(url, p);
  if (pairCache.size > 300) pairCache.delete(pairCache.keys().next().value);
  return p;
}

/**
 * Show a title's backdrop. spec: {art: image url | null, colors: [c1, c2] | null, artOpacity?: 0..1,
 * dim?: boolean (text-heavy pages: stronger bottom scrim)} or null for the plain orbit mesh.
 * Returns the [m1, m2] pair that is shown.
 */
export async function setScene(spec) {
  const el = scene(); if (!el) return null;
  spec = spec || {};
  const art = spec.art || null;
  const opts = `${spec.artOpacity == null ? 1 : spec.artOpacity}|${spec.dim ? 1 : 0}`;
  el.classList.toggle('dim', !!spec.dim);
  let pair = normalizePair(spec.colors);
  if (cur.art === art && opts === cur.opts && (!pair || String(pair) === String(cur.pair))) return cur.pair;
  const my = ++token;
  // load the art first (so it fades in whole), and read its colours if the item had none
  const im = art ? await loadImage(art) : null;
  if (my !== token) return null;
  if (!pair && art) {
    if (pairCache.has(art)) pair = pairCache.get(art);
    else if (im && readable(art)) { pair = samplePair(im); pairCache.set(art, pair); }
  }
  pair = pair || ORBIT;
  const layers = el.querySelectorAll('.bgl');
  const on = el.querySelector('.bgl.on') || layers[0];
  const next = [...layers].find((x) => x !== on) || on;
  next.querySelector('.mesh').style.background = meshBackground(pair);
  const a = next.querySelector('.bgart');
  a.style.backgroundImage = im ? `url("${art.replace(/"/g, '%22')}")` : 'none';
  a.style.opacity = String(spec.artOpacity == null ? 1 : spec.artOpacity);
  // force the start state before flipping, so the transition runs
  void next.offsetWidth;
  next.classList.add('on');
  if (next !== on) on.classList.remove('on');
  cur = { art, pair, opts };
  document.documentElement.style.setProperty('--m1', pair[0]);
  document.documentElement.style.setProperty('--m2', pair[1]);
  return pair;
}

/** The plain orbit mesh (screens without a featured title: settings, search, guide). */
export function plainScene(dim) { return setScene({ art: null, colors: ORBIT, dim: !!dim }); }

/** Calls fn at most once per `ms` after the last call (focus moves: about 250 ms, DESIGN-SPEC-5 §6). */
export function settle(fn, ms = 250) {
  let t = 0;
  const f = (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); };
  f.cancel = () => clearTimeout(t);
  return f;
}
