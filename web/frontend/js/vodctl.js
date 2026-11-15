// Copyright 2026 The Orbit authors. SPDX-License-Identifier: Apache-2.0
// The film / episode player's control logic without the DOM (vodbar.js draws it; node tests check it):
// which action a key does, the time and episode lines, where Escape goes, the progress bar's maths.

/** The keyboard shortcuts, as shown in the settings menu (key label, what it does). */
export const SHORTCUTS = [
  ['Space · K', 'Play / pause'],
  ['← / →', 'Back / forward 10 s'],
  ['J / L', 'Back 10 s / forward 30 s'],
  ['↑ / ↓', 'Volume up / down'],
  ['M', 'Mute'],
  ['F', 'Full screen'],
  ['C', 'Subtitles on / off'],
  ['N · P', 'Next / previous episode'],
  ['Esc', 'Close menu · leave full screen · close'],
];

export const SPEEDS = [0.5, 0.75, 1, 1.25, 1.5, 2];
/** Controls hide after this long without input while playing. */
export const HIDE_MS = 3000;

/**
 * The action for a key in the film / episode player, or null (the key is not the player's: Enter on a
 * focused button, Tab). Space and K only ever play / pause: they never activate the focused button.
 * onSlider: the focus is on the volume slider (Left / Right change the volume there).
 */
export function vodKey(key, { onSlider = false } = {}) {
  switch (key) {
    case ' ': case 'Spacebar': case 'k': case 'K': case 'MediaPlayPause': return 'toggle';
    case 'MediaPlay': return 'play';
    case 'MediaPause': return 'pause';
    case 'ArrowLeft': return onSlider ? 'volDown' : 'back10';
    case 'ArrowRight': return onSlider ? 'volUp' : 'fwd10';
    case 'j': case 'J': case 'MediaRewind': return 'back10';
    case 'l': case 'L': case 'MediaFastForward': return 'fwd30';
    case 'ArrowUp': return 'volUp';
    case 'ArrowDown': return 'volDown';
    case 'm': case 'M': case 'AudioVolumeMute': return 'mute';
    case 'f': case 'F': return 'fs';
    case 'c': case 'C': return 'subs';
    case 'n': case 'N': case 'MediaTrackNext': case 'PageDown': return 'next';
    case 'p': case 'P': case 'MediaTrackPrevious': case 'PageUp': return 'prev';
    case 'MediaStop': return 'stop';
    case 'Escape': case 'Backspace': case 'GoBack': case 'BrowserBack': return 'escape';
    default: return null;
  }
}

/** What Escape does now: close the open menu, else leave full screen, else close the player. */
export function escapeStep({ menuOpen, fullscreen }) {
  return menuOpen ? 'menu' : fullscreen ? 'fullscreen' : 'close';
}

/** "39:39 / 40:21", or with left: "39:39 / −0:42". */
export function timeLine(t, d, left, fmt) {
  if (!d) return fmt(t);
  return `${fmt(Math.min(t, d))} / ${left ? '−' + fmt(Math.max(0, d - t)) : fmt(d)}`;
}

/** The episode line under the title: "S1 E6 · Name" (or "S01E06 - Name") -> "S1 · E6 — Name"; anything else as it is. */
export function epLine(sub) {
  const s = String(sub || '').trim();
  const m = /^S0*(\d+)\s*[·.:-]?\s*E0*(\d+)\s*(?:[·:–—-]\s*(.*))?$/i.exec(s);
  if (!m) return s;
  return `S${m[1]} · E${m[2]}` + (m[3] && m[3].trim() ? ' — ' + m[3].trim() : '');
}

/** The title time under a pointer at x on a bar from left to left + width (clamped to the bar). */
export function timeAt(x, left, width, d) {
  if (!width || !d) return 0;
  return Math.min(1, Math.max(0, (x - left) / width)) * d;
}

/** Buffered ranges (title seconds) as [from, to] fractions of d, for the bar. */
export function bufferedFracs(ranges, d) {
  if (!d) return [];
  return ranges.filter(([a, b]) => b > a).map(([a, b]) => [Math.max(0, a / d), Math.min(1, b / d)]);
}

/** A double tap on a phone: which side of the picture skips (left third back, right third forward), or null. */
export function tapSide(x, width) {
  if (!width) return null;
  const f = x / width;
  return f < 0.36 ? 'back' : f > 0.64 ? 'fwd' : null;
}
