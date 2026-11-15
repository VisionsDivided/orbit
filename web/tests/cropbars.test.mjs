// The web player's "Fit, crop black bars": finding the picture inside a frame with baked-in bars.
import test from 'node:test';
import assert from 'node:assert/strict';

import { contentBox } from '../frontend/js/cropbars.js';

function frame(w, h, lit) {          // lit(x, y) -> true for picture pixels
  const d = new Uint8ClampedArray(w * h * 4);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const v = lit(x, y) ? 160 : 4, i = (y * w + x) * 4;
    d[i] = d[i + 1] = d[i + 2] = v; d[i + 3] = 255;
  }
  return d;
}

test('a 2.39:1 film letterboxed in a 16:9 frame', () => {
  const w = 96, h = 54, bar = 7;                  // 54 * (1 - (16/9)/2.39) / 2 ~ 7 rows each
  const b = contentBox(frame(w, h, (x, y) => y >= bar && y < h - bar), w, h);
  assert.ok(b);
  assert.equal(b.x0, 0); assert.equal(b.x1, 1);
  assert.equal(b.y0, bar / h); assert.equal(b.y1, (h - bar) / h);
});

test('pillarboxed 4:3 content', () => {
  const w = 96, h = 54;
  const b = contentBox(frame(w, h, (x) => x >= 12 && x < 84), w, h);
  assert.equal(b.x0, 12 / w); assert.equal(b.x1, 84 / w); assert.equal(b.y0, 0); assert.equal(b.y1, 1);
});

test('a dark scene tells nothing', () => {
  assert.equal(contentBox(frame(96, 54, (x, y) => y > 25 && y < 30), 96, 54), null);
});
