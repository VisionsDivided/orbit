// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
// Guide grid math: one shared time scale for the ruler, the programme blocks and the "now"
// line. Pure functions (epoch seconds in, pixels out) so they can be unit-tested in Node.

export const WINDOW_MIN = 150;               // ~2.5 h visible
export const WINDOW_SEC = WINDOW_MIN * 60;
export const STEP = 1800;                    // edges shift the window by 30 min
export const AHEAD_LIMIT = 72 * 3600;        // how far right the window may go
export const CHUNK = 6 * 3600;               // guide paging unit (backend fetch granularity)

export const floor30 = (t) => Math.floor(t / 1800) * 1800;

/** The guide starts at the current half hour (05:00 at 05:17) and only goes forward: the past is
 *  never shown in the grid (catch-up is reached from the programme / channel menu). */
export const initialStart = (now) => floor30(now);

/** A window start kept inside [current half hour, now + AHEAD_LIMIT]: one behind "now" snaps to now. */
export function clampWin(winStart, now) {
  const lo = floor30(now), hi = floor30(now + AHEAD_LIMIT);
  return Math.min(hi, Math.max(lo, winStart));
}

/** Window start after a 30-minute step (never into the past). */
export function shifted(winStart, delta, now) { return clampWin(winStart + delta, now); }

/**
 * Left from a focused programme. Returns { prog, win } (focus that programme, window start win)
 * or { label: true } (focus goes to the channel name: at the window start, which is never before
 * the current half hour, Left never scrolls into the past and never traps focus).
 */
export function leftOf(progs, focus, winStart, now) {
  const lo = floor30(now);
  if (!progs || !progs.length || !focus) return { label: true };
  const pv = prevProg(progs, focus);
  if (pv && pv.stop > winStart) return { prog: pv, win: winStart };          // visible: just move
  if (winStart > lo) {                                                        // the window can still go back (not past now)
    if (pv) { const win = clampWin(Math.min(floor30(pv.start), winStart - STEP), now); if (pv.stop > win) return { prog: pv, win }; }
    // the previous one is in the past (or there is none): the window steps back towards now first,
    // the focus stays; the channel name comes once the window is at now
    return { prog: focus, win: clampWin(winStart - STEP, now) };
  }
  return { label: true };
}

/** Right from the channel name: the programme on now, with the window back at now. */
export function rightOfLabel(progs, now) {
  const win = initialStart(now);
  return { prog: progAt(progs, now) || firstVisible(progs, win, win + WINDOW_SEC), win };
}

/**
 * Window start after focus lands on a programme. One that is at least partly visible never
 * pulls the window back; one entirely outside (or starting inside and running past the end) moves it.
 */
export function reveal(pStart, pStop, winStart) {
  const winEnd = winStart + WINDOW_SEC;
  if (pStop <= winStart || pStart >= winEnd) return floor30(pStart);
  if (pStart >= winStart && pStop > winEnd) return Math.min(floor30(pStart), floor30(pStop - WINDOW_SEC + 1799));
  return winStart;
}

/** x offset and width (px) of [start, stop) clipped to the window, or null when outside. */
export function block(start, stop, winStart, winEnd, pxPerMin, gap = 2) {
  const s = Math.max(start, winStart), e = Math.min(stop, winEnd);
  if (e <= s) return null;
  return { x: ((s - winStart) / 60) * pxPerMin, w: Math.max(1, ((e - s) / 60) * pxPerMin - gap), clippedLeft: start < winStart };
}

export const tickX = (t, winStart, pxPerMin) => ((t - winStart) / 60) * pxPerMin;

/** Ruler ticks (every 30 min) inside [winStart, winEnd]. */
export function ticks(winStart, winEnd) {
  const out = [];
  for (let t = Math.ceil(winStart / 1800) * 1800; t <= winEnd; t += 1800) out.push(t);
  return out;
}

/**
 * Segments for one row: programmes clipped to the window plus "No guide data" gaps
 * (only gaps longer than minGap seconds are shown). progs must be sorted by start.
 */
export function rowSegments(progs, winStart, winEnd, minGap = 120) {
  const out = [];
  if (!progs || !progs.length) return [{ gap: true, start: winStart, stop: winEnd }];
  let cursor = winStart;
  for (const p of progs) {
    if (p.stop <= winStart || p.start >= winEnd) continue;
    const s = Math.max(p.start, winStart);
    if (s > cursor + minGap) out.push({ gap: true, start: cursor, stop: s });
    out.push({ gap: false, prog: p, start: p.start, stop: p.stop });
    cursor = Math.max(cursor, Math.min(p.stop, winEnd));
  }
  if (winEnd > cursor + minGap) out.push({ gap: true, start: cursor, stop: winEnd });
  return out;
}

/** Programme playing at time t, or null. */
export function progAt(progs, t) {
  if (!progs) return null;
  for (const p of progs) if (p.start <= t && p.stop > t) return p;
  return null;
}
export const nextProg = (progs, p) => (progs || []).find((x) => x.start >= p.stop) || null;
export function prevProg(progs, p) {
  let r = null;
  for (const x of progs || []) if (x.stop <= p.start) r = x;
  return r;
}
/** First programme visible in the window (used when focus enters a row). */
export function firstVisible(progs, winStart, winEnd) {
  return (progs || []).find((p) => p.stop > winStart && p.start < winEnd) || null;
}

/** Chunk indices (CHUNK-aligned) needed to cover [from, to). */
export function chunksFor(from, to) {
  const out = [];
  for (let c = Math.floor(from / CHUNK); c * CHUNK < to; c++) out.push(c);
  return out;
}

/**
 * One channel's programmes made drawable: providers send overlapping entries (two titles printed over each other)
 * and put fillers ("Next Game Today") or inner entries inside a live game. The rules (the same on the server,
 * parsers.normalize_programmes, and in the Kotlin EpgIndex.tidy):
 * 1. entries with stop <= start are dropped;
 * 2. sorted by start, then the longer first (stable: equal ones keep their input order);
 * 3. of entries starting at the same time only the first (the longest) is kept;
 * 4. an entry lying fully inside an earlier kept entry with the same title is dropped (duplicates included);
 * 5. timeline overlay: at any moment the entry shown is the kept one covering it with the latest start, so when an
 *    inner entry ends the enclosing one resumes (Block 1-5, Match 2-3 -> Block 1-2, Match 2-3, Block 3-5;
 *    A 1-3, B 2-4 -> A 1-2, B 2-4); adjacent pieces of one entry are merged;
 * 6. zero-length pieces are dropped.
 * A piece is a copy of its entry with other start / stop; an entry shown whole comes back as it was, and the input
 * is not changed. O(n log n): one sweep with a stack of open entries.
 */
export function normalizeProgs(progs) {
  const ps = (progs || []).filter((p) => p && p.stop > p.start)
    .sort((a, b) => a.start - b.start || (b.stop - b.start) - (a.stop - a.start));
  const kept = [];
  const reach = new Map(); // latest stop of each title kept so far: all of them start no later than p
  for (const p of ps) {
    if (kept.length && kept[kept.length - 1].start === p.start) continue;
    const r = reach.get(p.title);
    if (r !== undefined && r >= p.stop) continue;
    reach.set(p.title, r === undefined ? p.stop : Math.max(r, p.stop));
    kept.push(p);
  }
  const pieces = []; // [entry index, start, stop]
  const emit = (i, a, b) => {
    if (b <= a) return;
    const last = pieces[pieces.length - 1];
    if (last && last[0] === i && last[2] === a) last[2] = b;
    else pieces.push([i, a, b]);
  };
  const stack = []; // open entries, later starts on top; ones already ended are removed lazily
  let cur = 0;
  const advance = (t) => { // t null: to the end
    while (stack.length) {
      const i = stack[stack.length - 1], top = kept[i];
      if (top.stop <= cur) { stack.pop(); continue; }
      const end = t === null || top.stop <= t ? top.stop : t;
      emit(i, cur, end);
      cur = end;
      if (t !== null && top.stop > t) break;
      stack.pop();
    }
    if (t !== null) cur = Math.max(cur, t);
  };
  for (let i = 0; i < kept.length; i++) {
    if (!stack.length) cur = kept[i].start;
    advance(kept[i].start);
    stack.push(i);
  }
  advance(null);
  return pieces.map(([i, a, b]) => {
    const p = kept[i];
    return a === p.start && b === p.stop ? p : { ...p, start: a, stop: b };
  });
}

/** Merge programme lists from several chunks: dedupe by start, normalise (normalizeProgs). */
export function mergeProgs(lists) {
  const all = [];
  for (const l of lists) for (const p of l || []) all.push(p);
  return normalizeProgs(all);
}

/** State of a programme for the UI: 'now' | 'future' | 'catchup' (past, in archive) | 'past'. */
export function progState(p, now, ch) {
  if (p.start > now) return 'future';
  if (p.stop > now) return 'now';
  if (ch && ch.catchup && p.start >= now - (ch.catchupDays || 0) * 86400) return 'catchup';
  return 'past';
}

/**
 * What a search result shows for a programme: catch-up clock if past and in the archive,
 * progress + minutes left if on now, air time if future; null if past and not watchable.
 */
export function searchInfo(p, now, ch) {
  const st = progState(p, now, ch);
  if (st === 'now') { const pr = progress(p, now); return { kind: 'now', frac: pr.frac, minLeft: pr.minLeft, watch: 'live' }; }
  if (st === 'future') return { kind: 'future', at: p.start, inMin: Math.ceil((p.start - now) / 60), watch: null };
  if (st === 'catchup') return { kind: 'catchup', at: p.start, watch: 'catchup' };
  return null;
}

/** Progress (0..1) and minutes left of a programme at time now. */
export function progress(p, now) {
  const len = Math.max(1, p.stop - p.start);
  return { frac: Math.min(1, Math.max(0, (now - p.start) / len)), minLeft: Math.max(0, Math.ceil((p.stop - now) / 60)) };
}
