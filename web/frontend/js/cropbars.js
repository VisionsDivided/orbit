// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
/**
 * The picture inside the frame, without black bars baked into the video (a 2.39:1 film in a 16:9 frame): samples a
 * small copy of the frame and returns its content box as fractions {x0, x1, y0, y1}, or null (no frame yet, a dark
 * scene, or a frame the page may not read).
 */
export function contentBox(data, w, h, dark = 24) {
  const rowLit = (y) => { let n = 0; for (let x = 0; x < w; x++) { const i = (y * w + x) * 4; if (data[i] + data[i + 1] + data[i + 2] > dark * 3) n++; } return n > w * 0.02; };
  const colLit = (x, y0, y1) => { let n = 0; for (let y = y0; y <= y1; y++) { const i = (y * w + x) * 4; if (data[i] + data[i + 1] + data[i + 2] > dark * 3) n++; } return n > (y1 - y0 + 1) * 0.02; };
  let y0 = 0, y1 = h - 1;
  while (y0 < h && !rowLit(y0)) y0++;
  while (y1 > y0 && !rowLit(y1)) y1--;
  if (y1 - y0 + 1 < h * 0.4) return null;             // too dark to tell
  let x0 = 0, x1 = w - 1;
  while (x0 < w && !colLit(x0, y0, y1)) x0++;
  while (x1 > x0 && !colLit(x1, y0, y1)) x1--;
  if (x1 - x0 + 1 < w * 0.4) return null;
  return { x0: x0 / w, x1: (x1 + 1) / w, y0: y0 / h, y1: (y1 + 1) / h };
}
