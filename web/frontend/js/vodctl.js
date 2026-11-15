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

// ---------- live TV (the same bar in live mode: vodbar.js with c.live) ----------

/** The keyboard shortcuts of a live channel, as shown in the settings menu. */
export const LIVE_SHORTCUTS = [
  ['Space · K', 'Play / pause'],
  ['↑ / ↓', 'Channel up / down'],
  ['PgUp / PgDn', 'Channel up / down'],
  ['0 – 9', 'Channel by number'],
  ['← / →', 'Back to the guide'],
  ['+ / −', 'Volume up / down'],
  ['M', 'Mute'],
  ['F', 'Full screen'],
  ['C', 'Subtitles on / off'],
  ['I · Enter', 'Show / hide the controls'],
  ['Esc', 'Close menu · leave full screen · back to the guide'],
];

/**
 * The action for a key on a live channel, or null (not the bar's: digits are channel numbers in player.js,
 * Enter clicks the focused button). Space and K only ever play / pause, as in films.
 */
export function liveKey(key, { onSlider = false } = {}) {
  switch (key) {
    case ' ': case 'Spacebar': case 'k': case 'K': case 'MediaPlayPause': return 'toggle';
    case 'MediaPlay': return 'play';
    case 'MediaPause': return 'pause';
    case 'ArrowUp': case 'PageUp': case 'ChannelUp': case 'MediaTrackNext': case 'n': case 'N': return 'chUp';
    case 'ArrowDown': case 'PageDown': case 'ChannelDown': case 'MediaTrackPrevious': case 'p': case 'P': return 'chDown';
    case 'ArrowLeft': return onSlider ? 'volDown' : 'close';
    case 'ArrowRight': return onSlider ? 'volUp' : 'close';
    case '+': case '=': case 'AudioVolumeUp': return 'volUp';
    case '-': case '_': case 'AudioVolumeDown': return 'volDown';
    case 'm': case 'M': case 'AudioVolumeMute': return 'mute';
    case 'f': case 'F': return 'fs';
    case 'c': case 'C': return 'subs';
    case 'i': case 'I': case 'Info': return 'info';
    case 'ContextMenu': return 'settings';
    case 'MediaStop': return 'stop';
    case 'Escape': case 'Backspace': case 'GoBack': case 'BrowserBack': return 'escape';
    default: return null;
  }
}

/** How far through programme p ({start, stop} epoch seconds) `now` is, 0..1; null without a usable programme. */
export function progFrac(p, now) {
  if (!p || !(p.stop > p.start)) return null;
  return Math.min(1, Math.max(0, (now - p.start) / (p.stop - p.start)));
}

/** The programme's title line: "Title · S2 E5 Subtitle" (only the parts it has). */
export function progTitle(p) {
  if (!p) return '';
  return String(p.title || '') + (p.ep ? ' · ' + p.ep : '') + (p.sub ? ' ' + p.sub : '');
}

/** "20:00 – 21:00 · 23 min left" while it is on, else just the range (a catch-up programme). */
export function progLine(p, now, fmtT) {
  if (!p) return '';
  const range = `${fmtT(p.start)} – ${fmtT(p.stop)}`;
  if (!(now >= p.start && now < p.stop)) return range;
  return `${range} · ${Math.max(1, Math.ceil((p.stop - now) / 60))} min left`;
}

/** "Next: 21:00 · Title", or '' without a next programme. */
export function nextLine(n, fmtT) {
  return n && n.title ? `Next: ${fmtT(n.start)} · ${n.title}` : '';
}

/** The channel as the top line names it: "101  Name". */
export function chLine(ch) {
  if (!ch) return '';
  return [ch.number, ch.name].filter((x) => x != null && x !== '').join('  ');
}

/** Holding ← / → in catch-up accelerates: the step (s) after n repeats of the same key. */
export function holdStep(n) {
  return [10, 20, 30, 60][Math.min(3, Math.floor(Math.max(0, n) / 4))];
}
