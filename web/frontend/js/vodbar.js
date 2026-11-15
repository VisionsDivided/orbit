// Copyright 2026 The Orbit authors. SPDX-License-Identifier: Apache-2.0
// The film / episode control bar (IPTV films and episodes, Plex / Jellyfin through the home server), direction 5
// glass with ideas from Plex's web player: a thin progress bar that thickens on hover (buffered range, scrubber,
// time tooltip, click or drag to seek); title, episode and time on the left; transport in the middle; subtitles,
// audio, settings, picture in picture, volume and full screen on the right. A settings panel (a bottom sheet on
// phones) holds speed, quality, aspect, subtitle appearance, audio delay, sleep timer and the shortcuts.
//
// Everything the viewer sees of the controls (top line with title and clock, progress bar, time, transport,
// buttons) is one element, .vb, shown and hidden together by ONE class on the player layer (.chrome) with ONE
// timer and ONE fade: never a part on its own. Live TV keeps its own overlay (player.js).

import { h, fmtDur, fmtTime, nowSec } from './util.js';
import { prefs, setPref } from './prefs.js';
import { vodKey, escapeStep, timeLine, epLine, timeAt, bufferedFracs, tapSide, SHORTCUTS, SPEEDS, HIDE_MS } from './vodctl.js';

const ICONS = {
  play: '<path d="M7 4.5v15l13-7.5z" fill="currentColor" stroke="none"/>',
  pause: '<path d="M7 4.5h3.5v15H7zM13.5 4.5H17v15h-3.5z" fill="currentColor" stroke="none"/>',
  back10: '<path d="M4 12a8 8 0 1 0 2.4-5.7"/><path d="M4 4v4h4"/><text x="12" y="15.6" font-size="7.5" font-weight="700" text-anchor="middle" fill="currentColor" stroke="none">10</text>',
  fwd30: '<path d="M20 12a8 8 0 1 1-2.4-5.7"/><path d="M20 4v4h-4"/><text x="12" y="15.6" font-size="7.5" font-weight="700" text-anchor="middle" fill="currentColor" stroke="none">30</text>',
  prev: '<path d="M6 5v14"/><path d="M19 5.5v13L9 12z" fill="currentColor" stroke="none"/>',
  next: '<path d="M18 5v14"/><path d="M5 5.5v13L15 12z" fill="currentColor" stroke="none"/>',
  stop: '<rect x="6" y="6" width="12" height="12" rx="1.5" fill="currentColor" stroke="none"/>',
  gear: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/>',
  subs: '<rect x="3" y="5" width="18" height="14" rx="2.5"/><path d="M7 11h3M12 11h5M7 15h6M15 15h2"/>',
  audio: '<path d="M3 13v-1a9 9 0 0 1 18 0v1"/><rect x="3" y="13" width="4" height="7" rx="1.5"/><rect x="17" y="13" width="4" height="7" rx="1.5"/>',
  pip: '<rect x="3" y="5" width="18" height="14" rx="2"/><rect x="12" y="11" width="7" height="6" rx="1" fill="currentColor" stroke="none"/>',
  vol: '<path d="M4 9.5h3.5L12 5.5v13l-4.5-4H4z" fill="currentColor" stroke="none"/><path d="M15.5 9a4 4 0 0 1 0 6M18 6.5a7.5 7.5 0 0 1 0 11"/>',
  volLow: '<path d="M4 9.5h3.5L12 5.5v13l-4.5-4H4z" fill="currentColor" stroke="none"/><path d="M15.5 9a4 4 0 0 1 0 6"/>',
  mute: '<path d="M4 9.5h3.5L12 5.5v13l-4.5-4H4z" fill="currentColor" stroke="none"/><path d="M16 9.5l5 5M21 9.5l-5 5"/>',
  fs: '<path d="M4 9V4h5M15 4h5v5M20 15v5h-5M9 20H4v-5"/>',
  fsExit: '<path d="M9 4v5H4M20 9h-5V4M15 20v-5h5M4 15h5v5"/>',
  back: '<path d="M15 6l-6 6 6 6"/>',
  right: '<path d="M9 6l6 6-6 6"/>',
  check: '<path d="M5 12.5l4.5 4.5L19 7"/>',
  close: '<path d="M6 6l12 12M18 6L6 18"/>',
};
const svg = (name, cls = '') => {
  const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  s.setAttribute('viewBox', '0 0 24 24'); s.setAttribute('class', 'ic ' + cls); s.setAttribute('aria-hidden', 'true');
  s.innerHTML = ICONS[name] || ''; return s;
};
const loadLS = (k, d) => { try { const v = localStorage.getItem(k); return v == null ? d : v; } catch { return d; } };
const saveLS = (k, v) => { try { localStorage.setItem(k, String(v)); } catch {} };

/** Caption look from prefs -> the ::cue variables (same as Settings › Playback). */
export function applyCaptionLook() {
  const p = prefs(), r = document.documentElement.style;
  r.setProperty('--cue-size', (p.captionSize || 100) + '%');
  r.setProperty('--cue-bg', p.captionBg === 'none' ? 'transparent' : p.captionBg === 'solid' ? '#000' : 'rgba(0,0,0,.6)');
}
const CAPTION_SIZES = [[75, 'Small'], [100, 'Normal'], [130, 'Large'], [160, 'Extra large']];
const CAPTION_BGS = [['none', 'None'], ['shade', 'Shaded'], ['solid', 'Solid black']];

/**
 * c: { layer, video, title(), subtitle(), pos(), total(), buffered() -> [[from, to]] title seconds, busy() (a
 *      seek is restarting the stream), togglePlay(), seekTo(t), canEp(dir), episode(dir), stop(),
 *      subsRows(), toggleSubs(), audioRows(), qualityRows(), aspectRows(), delayRows(), sleepRows(), extraRows(),
 *      pipOk(), inPip(), togglePip(), toggleFs(), onMenu(open) }
 * Rows: [{label, value?, on?, act()}]. Returns { el, show(), render(), onKey(e) -> handled, menuOpen(), destroy() }.
 */
export function createVodBar(c) {
  const { layer, video } = c;
  let leftMode = loadLS('orbit.timeLeft', '0') === '1';
  let hideTimer = null, hover = false, drag = null, menu = null, spinTimer = null;

  // ---------- the controls: one element, one visibility state ----------
  const btn = (icon, label, fn, cls = '') => {
    const b = h('button', { class: 'vb-btn ' + cls, type: 'button', 'aria-label': label, title: label, onclick: (e) => { e.stopPropagation(); fn(e); show(); } });
    b.append(svg(icon)); return b;
  };
  const setIcon = (b, name) => { if (b._ic !== name) { b._ic = name; b.replaceChildren(svg(name)); } };

  const backBtn = h('button', { class: 'vb-btn vb-close', type: 'button', 'aria-label': 'Back', title: 'Back (Esc)', onclick: (e) => { e.stopPropagation(); c.stop(); } }, svg('back'));
  const topTitle = h('div', { class: 'vb-toptitle' });
  const clock = h('div', { class: 'vb-clock' });
  const top = h('div', { class: 'vb-top' }, backBtn, topTitle, clock);

  const buf = h('div', { class: 'vb-buf' });
  const fill = h('div', { class: 'vb-fill' });
  const knob = h('div', { class: 'vb-knob' });
  const tip = h('div', { class: 'vb-tip', hidden: true });
  const track = h('div', { class: 'vb-track' }, buf, fill, knob);
  const prog = h('div', { class: 'vb-prog', role: 'slider', tabindex: '-1', 'aria-label': 'Seek', 'aria-valuemin': '0' }, track, tip);

  const titleEl = h('div', { class: 'vb-title' });
  const epEl = h('div', { class: 'vb-ep' });
  const timeEl = h('button', { class: 'vb-time', type: 'button', title: 'Show the time left', onclick: (e) => { e.stopPropagation(); leftMode = !leftMode; saveLS('orbit.timeLeft', leftMode ? 1 : 0); render(); show(); } });
  const left = h('div', { class: 'vb-left' }, titleEl, epEl, timeEl);

  const prevBtn = btn('prev', 'Previous episode (P)', () => c.episode(-1), 'vb-ep-btn');
  const backBtn10 = btn('back10', 'Back 10 seconds (J)', () => skip(-10));
  const playBtn = btn('play', 'Play (Space)', () => c.togglePlay(), 'vb-play');
  const fwdBtn = btn('fwd30', 'Forward 30 seconds (L)', () => skip(30));
  const nextBtn = btn('next', 'Next episode (N)', () => c.episode(1), 'vb-ep-btn');
  const stopBtn = btn('stop', 'Stop', () => c.stop(), 'vb-stop');
  const mid = h('div', { class: 'vb-mid' }, prevBtn, backBtn10, playBtn, fwdBtn, nextBtn, stopBtn);

  const subsBtn = btn('subs', 'Subtitles (C)', () => openMenu('subs'));
  const audioBtn = btn('audio', 'Audio track', () => openMenu('audio'));
  const gearBtn = btn('gear', 'Settings', () => openMenu('settings'));
  const pipBtn = btn('pip', 'Picture in picture', () => c.togglePip());
  const muteBtn = btn('vol', 'Mute (M)', () => setMuted(!video.muted));
  const vol = h('input', { class: 'vb-vol', type: 'range', min: 0, max: 1, step: 0.05, 'aria-label': 'Volume' });
  vol.addEventListener('input', () => { video.volume = +vol.value; if (video.muted && +vol.value > 0) video.muted = false; saveVol(); render(); show(); });
  vol.addEventListener('click', (e) => e.stopPropagation());
  const volBox = h('div', { class: 'vb-volbox' }, muteBtn, vol);
  const fsBtn = btn('fs', 'Full screen (F)', () => c.toggleFs());
  const right = h('div', { class: 'vb-right' }, subsBtn, audioBtn, gearBtn, pipBtn, volBox, fsBtn);

  const row = h('div', { class: 'vb-row' }, left, mid, right);
  const bottom = h('div', { class: 'vb-bottom' }, prog, row);
  const el = h('div', { class: 'vb' }, top, bottom);
  bottom.addEventListener('pointerenter', (e) => { if (e.pointerType === 'mouse') hover = true; });
  bottom.addEventListener('pointerleave', () => { hover = false; show(); });
  // clicks on the controls never reach the picture (play / pause) behind them
  for (const x of [top, bottom]) x.addEventListener('click', (e) => e.stopPropagation());

  const spin = h('div', { class: 'vb-spin', hidden: true, role: 'status', 'aria-label': 'Loading' }, h('i'));
  const skipL = h('div', { class: 'vb-skip l', 'aria-hidden': 'true' });
  const skipR = h('div', { class: 'vb-skip r', 'aria-hidden': 'true' });
  const panel = h('div', { class: 'vb-menu', hidden: true, role: 'menu' });
  const sheetBack = h('div', { class: 'vb-sheetback', hidden: true, onclick: (e) => { e.stopPropagation(); closeMenu(); } });
  layer.classList.add('vod');
  layer.append(el, spin, skipL, skipR, sheetBack, panel);

  // ---------- show / hide: one timer ----------
  const canHide = () => !video.paused && !menu && !hover && !drag && !video.ended;
  function show() {
    layer.classList.add('chrome');
    clearTimeout(hideTimer);
    hideTimer = setTimeout(function tick() {
      if (canHide()) layer.classList.remove('chrome'); else hideTimer = setTimeout(tick, HIDE_MS);
    }, HIDE_MS);
  }
  function hide() { if (!menu) { clearTimeout(hideTimer); layer.classList.remove('chrome'); } }

  // ---------- drawing ----------
  function render() {
    const d = c.total(), t = drag ? drag.t : c.pos();
    const title = c.title() || '', sub = epLine(c.subtitle());
    if (titleEl.textContent !== title) titleEl.textContent = title;
    if (epEl.textContent !== sub) epEl.textContent = sub;
    epEl.hidden = !sub;
    const tt = sub ? `${title} · ${sub}` : title;
    if (topTitle.textContent !== tt) topTitle.textContent = tt;
    timeEl.textContent = timeLine(t, d, leftMode, fmtDur);
    timeEl.title = leftMode ? 'Show the length' : 'Show the time left';
    clock.textContent = fmtTime(nowSec());
    const f = d ? Math.min(1, Math.max(0, t / d)) : 0;
    fill.style.width = (f * 100).toFixed(2) + '%';
    knob.style.left = (f * 100).toFixed(2) + '%';
    const bf = bufferedFracs(c.buffered(), d).find(([a, b]) => a <= f + 0.002 && b >= f - 0.002) || null;
    buf.style.left = bf ? (bf[0] * 100).toFixed(2) + '%' : '0';
    buf.style.width = bf ? ((bf[1] - bf[0]) * 100).toFixed(2) + '%' : '0';
    prog.setAttribute('aria-valuemax', String(Math.floor(d || 0)));
    prog.setAttribute('aria-valuenow', String(Math.floor(t)));
    prog.setAttribute('aria-valuetext', timeLine(t, d, false, fmtDur));
    const paused = video.paused;
    setIcon(playBtn, paused ? 'play' : 'pause');
    playBtn.setAttribute('aria-label', paused ? 'Play (Space)' : 'Pause (Space)'); playBtn.title = playBtn.getAttribute('aria-label');
    prevBtn.hidden = !c.canEp(-1); nextBtn.hidden = !c.canEp(1);
    subsBtn.hidden = c.subsRows().length < 2;
    subsBtn.classList.toggle('on', c.subsRows().some((r) => r.on && r.track));
    audioBtn.hidden = c.audioRows().length < 2;
    pipBtn.hidden = !c.pipOk();
    setIcon(muteBtn, video.muted || video.volume === 0 ? 'mute' : video.volume < 0.5 ? 'volLow' : 'vol');
    muteBtn.setAttribute('aria-label', video.muted ? 'Unmute (M)' : 'Mute (M)');
    if (document.activeElement !== vol) vol.value = String(video.muted ? 0 : video.volume);
    vol.style.setProperty('--v', ((video.muted ? 0 : video.volume) * 100).toFixed(0) + '%');
    setIcon(fsBtn, document.fullscreenElement ? 'fsExit' : 'fs');
    fsBtn.setAttribute('aria-label', document.fullscreenElement ? 'Leave full screen (F)' : 'Full screen (F)');
    layer.classList.toggle('paused', paused);
    if (menu && menu.live) drawMenu(false);
  }

  // ---------- progress bar: hover tooltip, click or drag to seek ----------
  const tAt = (x) => { const r = track.getBoundingClientRect(); return timeAt(x, r.left, r.width, c.total()); };
  function showTip(x) {
    const d = c.total(); if (!d) { tip.hidden = true; return; }
    const r = track.getBoundingClientRect(), t = tAt(x);
    tip.textContent = fmtDur(t); tip.hidden = false;
    const w = tip.offsetWidth || 50;
    tip.style.left = Math.min(r.width - w / 2, Math.max(w / 2, x - r.left)) + 'px';
  }
  prog.addEventListener('pointermove', (e) => { showTip(e.clientX); if (drag) { drag.t = tAt(e.clientX); render(); } });
  prog.addEventListener('pointerleave', () => { if (!drag) tip.hidden = true; });
  prog.addEventListener('pointerdown', (e) => {
    if (e.button !== 0 || !c.total()) return;
    e.preventDefault(); e.stopPropagation();
    try { prog.setPointerCapture(e.pointerId); } catch {}
    drag = { t: tAt(e.clientX) }; prog.classList.add('drag'); showTip(e.clientX); render(); show();
  });
  const endDrag = (e, commit) => {
    if (!drag) return;
    const t = e && e.clientX != null ? tAt(e.clientX) : drag.t;
    drag = null; prog.classList.remove('drag'); tip.hidden = true;
    if (commit) c.seekTo(t);
    render(); show();
  };
  prog.addEventListener('pointerup', (e) => endDrag(e, true));
  prog.addEventListener('pointercancel', () => endDrag(null, false));
  prog.addEventListener('click', (e) => e.stopPropagation());

  // ---------- volume ----------
  const saveVol = () => saveLS('orbit.volume', video.volume.toFixed(2));
  { const v = parseFloat(loadLS('orbit.volume', '1')); if (v >= 0 && v <= 1) video.volume = v; }
  function setMuted(m) { video.muted = m; if (!m && video.volume === 0) video.volume = 0.5; render(); }
  function volBy(d) {
    if (video.muted && d > 0) video.muted = false;
    video.volume = Math.min(1, Math.max(0, Math.round((video.volume + d) * 20) / 20));
    saveVol(); render(); show();
  }
  function skip(d) { c.seekTo(Math.max(0, c.pos() + d)); render(); }

  // ---------- picture, buffering spinner ----------
  const busyOn = () => { clearTimeout(spinTimer); spinTimer = setTimeout(() => { spin.hidden = false; }, 250); };
  const busyOff = () => { clearTimeout(spinTimer); if (!c.busy()) spin.hidden = true; };
  const evs = [['waiting', busyOn], ['seeking', busyOn], ['loadstart', busyOn], ['playing', busyOff], ['canplay', busyOff], ['seeked', busyOff],
    ['pause', () => { busyOff(); render(); show(); }], ['play', () => { render(); show(); }], ['volumechange', () => render()],
    ['ratechange', () => render()], ['timeupdate', () => { if (layer.classList.contains('chrome')) render(); }], ['ended', () => { busyOff(); render(); show(); }]];
  for (const [n, f] of evs) video.addEventListener(n, f);
  const onFs = () => { render(); show(); };
  document.addEventListener('fullscreenchange', onFs);

  // mouse: a click on the picture plays / pauses, a double click is full screen; a moving mouse shows the controls.
  // Touch: a tap shows or hides the controls; a double tap on the left or right third skips 10 s.
  let lastTap = null, tapTimer = null;
  const stageOf = () => layer.querySelector('.pl-stage');
  function onPointerMove(e) { if (e.pointerType === 'mouse') show(); }
  function onStageUp(e) {
    if (menu) { closeMenu(); return; }
    if (e.pointerType === 'mouse') {
      if (e.button !== 0) return;
      if (c.unmuteFirst && c.unmuteFirst()) { show(); return; }
      c.togglePlay(); show(); return;
    }
    const r = layer.getBoundingClientRect(), side = tapSide(e.clientX - r.left, r.width), now = Date.now();
    if (lastTap && now - lastTap.at < 320 && side && side === lastTap.side) {
      clearTimeout(tapTimer); lastTap = { at: now, side };     // (a third tap keeps skipping)
      skip(side === 'back' ? -10 : 10); ripple(side); return;
    }
    lastTap = { at: now, side };
    clearTimeout(tapTimer);
    tapTimer = setTimeout(() => { if (c.unmuteFirst && c.unmuteFirst()) { show(); return; } if (layer.classList.contains('chrome')) hide(); else show(); }, 300);
  }
  function ripple(side) {
    const x = side === 'back' ? skipL : skipR;
    x.textContent = side === 'back' ? '« 10 s' : '10 s »';
    x.classList.remove('on'); void x.offsetWidth; x.classList.add('on');
  }
  const stage = stageOf();
  layer.addEventListener('pointermove', onPointerMove);
  if (stage) { stage.addEventListener('pointerup', onStageUp); stage.addEventListener('dblclick', (e) => { e.stopPropagation(); if (!menu) c.toggleFs(); }); }

  // ---------- menus (settings, subtitles, audio): one panel, a bottom sheet on phones ----------
  function speedRows() {
    return SPEEDS.map((v) => ({ label: v === 1 ? 'Normal' : v + '×', on: Math.abs(video.playbackRate - v) < 0.01, act: () => { video.defaultPlaybackRate = v; video.playbackRate = v; } }));
  }
  function lookRows() {
    const p = prefs();
    return [
      { head: 'Size' },
      ...CAPTION_SIZES.map(([v, l]) => ({ label: l, on: (p.captionSize || 100) === v, act: () => { setPref('captionSize', v); applyCaptionLook(); } })),
      { head: 'Background' },
      ...CAPTION_BGS.map(([v, l]) => ({ label: l, on: (p.captionBg || 'shade') === v, act: () => { setPref('captionBg', v); applyCaptionLook(); } })),
    ];
  }
  const valueOf = (rows) => { const r = rows.find((x) => x.on); return r ? r.label : ''; };
  const PAGES = {
    settings: () => {
      const q = c.qualityRows(), rows = [
        { label: 'Playback speed', value: valueOf(speedRows()), page: 'speed' },
      ];
      if (q.length > 1) rows.push({ label: 'Quality', value: valueOf(q), page: 'quality' });
      rows.push({ label: 'Aspect', value: valueOf(c.aspectRows()), page: 'aspect' });
      rows.push({ label: 'Subtitle appearance', value: (CAPTION_SIZES.find(([v]) => v === (prefs().captionSize || 100)) || [0, 'Normal'])[1], page: 'look' });
      rows.push({ label: 'Audio delay', value: `+${c.delayMs()} ms`, page: 'delay' });
      rows.push({ label: 'Sleep timer', value: c.sleepLeft() ? c.sleepLeft() + ' min left' : 'Off', page: 'sleep' });
      for (const x of c.extraRows()) rows.push(x);
      rows.push({ label: 'Keyboard shortcuts', page: 'keys' });
      return { title: 'Settings', rows };
    },
    speed: () => ({ title: 'Playback speed', back: 'settings', rows: speedRows() }),
    quality: () => ({ title: 'Quality', back: 'settings', rows: c.qualityRows() }),
    aspect: () => ({ title: 'Aspect', back: 'settings', rows: c.aspectRows() }),
    look: () => ({ title: 'Subtitle appearance', back: menu && menu.from === 'subs' ? 'subs' : 'settings', rows: lookRows() }),
    delay: () => ({ title: `Audio delay · +${c.delayMs()} ms`, back: 'settings', rows: c.delayRows(), stay: true }),
    sleep: () => ({ title: 'Sleep timer', back: 'settings', rows: c.sleepRows() }),
    keys: () => ({ title: 'Keyboard shortcuts', back: 'settings', keys: true, rows: [] }),
    subs: () => ({ title: 'Subtitles', rows: [...c.subsRows(), { label: 'Appearance', page: 'look', sep: true }] }),
    audio: () => ({ title: 'Audio', rows: c.audioRows() }),
  };
  function openMenu(page, from) {
    if (menu && menu.page === page && !from) { closeMenu(); return; }
    menu = { page, from: from || (menu ? menu.from : page), idx: 0, live: true };
    panel.hidden = false; sheetBack.hidden = false; layer.classList.add('menu-open');
    if (c.onMenu) c.onMenu(true);
    drawMenu(true); show();
  }
  function closeMenu(refocus = true) {
    if (!menu) return;
    const from = menu.from;
    menu = null; panel.hidden = true; sheetBack.hidden = true; panel.replaceChildren(); layer.classList.remove('menu-open');
    if (c.onMenu) c.onMenu(false);
    if (refocus) { const b = from === 'subs' ? subsBtn : from === 'audio' ? audioBtn : gearBtn; if (document.documentElement.classList.contains('kbd') && !b.hidden) b.focus({ preventScroll: true }); else layer.focus({ preventScroll: true }); }
    show();
  }
  function drawMenu(focus) {
    if (!menu) return;
    const pg = PAGES[menu.page]();
    const ae = document.activeElement, had = ae && panel.contains(ae) ? [...panel.querySelectorAll('.vb-mi')].indexOf(ae) : -1;
    const kids = [];
    const head = h('div', { class: 'vb-mhead' });
    if (pg.back) head.append(h('button', { class: 'vb-mi vb-mback', type: 'button', role: 'menuitem', onclick: (e) => { e.stopPropagation(); goPage(pg.back); } }, svg('back'), h('span', {}, pg.title)));
    else head.append(h('div', { class: 'vb-mtitle' }, pg.title));
    head.append(h('button', { class: 'vb-mx', type: 'button', 'aria-label': 'Close', tabindex: '-1', onclick: (e) => { e.stopPropagation(); closeMenu(); } }, svg('close')));
    kids.push(head);
    const list = h('div', { class: 'vb-mlist' });
    if (pg.keys) {
      for (const [k, what] of SHORTCUTS) list.append(h('div', { class: 'vb-key' }, h('kbd', {}, k), h('span', {}, what)));
    }
    for (const r of pg.rows) {
      if (r.head) { list.append(h('div', { class: 'vb-mh' }, r.head)); continue; }
      const b = h('button', { class: 'vb-mi' + (r.on ? ' on' : '') + (r.sep ? ' sep' : ''), type: 'button', role: r.page ? 'menuitem' : 'menuitemradio', 'aria-checked': r.page ? null : (r.on ? 'true' : 'false'),
        onclick: (e) => {
          e.stopPropagation();
          if (r.page) { goPage(r.page); return; }
          r.act();
          if (pg.stay) drawMenu(false);
          else if (pg.back) goPage(pg.back); else closeMenu();
        } },
      h('span', { class: 'vb-mck' }, r.page ? null : (r.on ? svg('check') : null)),
      h('span', { class: 'vb-ml' }, r.label),
      r.value != null ? h('span', { class: 'vb-mv' }, r.value) : null,
      r.page ? svg('right', 'vb-mgo') : null);
      list.append(b);
    }
    if (!pg.rows.length && !pg.keys) list.append(h('div', { class: 'vb-mh' }, 'Nothing to choose here'));
    kids.push(list);
    panel.replaceChildren(...kids);
    panel.dataset.page = menu.page;
    const items = [...panel.querySelectorAll('.vb-mi')];
    if (focus) {
      const on = items.findIndex((x) => x.classList.contains('on'));
      const i = on >= 0 ? on : (pg.back && items.length > 1 ? 1 : 0);
      if (items[i]) items[i].focus({ preventScroll: true });
    } else if (had >= 0 && items[had]) items[had].focus({ preventScroll: true });
    menu.live = !!(pg.live);
  }
  function goPage(p) { if (!menu) return; menu.page = p; drawMenu(true); }
  function menuKey(e, act) {
    const items = [...panel.querySelectorAll('.vb-mi')];
    const i = items.indexOf(document.activeElement);
    const k = e.key;
    if (k === 'ArrowDown' || k === 'ArrowUp') {
      const n = items.length; if (!n) return true;
      const j = i < 0 ? 0 : (i + (k === 'ArrowDown' ? 1 : -1) + n) % n;
      items[j].focus({ preventScroll: true }); return true;
    }
    if (k === 'ArrowLeft') { const pg = PAGES[menu.page](); if (pg.back) goPage(pg.back); else closeMenu(); return true; }
    if (k === 'ArrowRight') { const b = items[i]; if (b && b.querySelector('.vb-mgo')) b.click(); return true; }
    if (k === 'Enter') { if (i >= 0) items[i].click(); else if (items[0]) items[0].focus(); return true; }
    if (act === 'escape') { if (k === 'Backspace') { const pg = PAGES[menu.page](); if (pg.back) { goPage(pg.back); return true; } } closeMenu(); return true; }
    return false;
  }

  // ---------- keys (player.js sends every key while the player is open) ----------
  function onKey(e) {
    const act = vodKey(e.key, { onSlider: document.activeElement === vol });
    if (menu && menuKey(e, act)) return true;
    if (e.key === 'Enter') {                       // Enter: the focused control, else play / pause
      const ae = document.activeElement;
      show();
      if (ae && ae !== layer && layer.contains(ae) && ae.tagName === 'BUTTON') { ae.click(); return true; }
      c.togglePlay(); return true;
    }
    if (!act) { show(); return false; }
    show();
    switch (act) {
      case 'toggle': if (!e.repeat) c.togglePlay(); break;
      case 'play': video.play().catch(() => {}); break;
      case 'pause': video.pause(); break;
      case 'back10': skip(-10); break;
      case 'fwd10': skip(10); break;
      case 'fwd30': skip(30); break;
      case 'volUp': volBy(0.05); break;
      case 'volDown': volBy(-0.05); break;
      case 'mute': if (!e.repeat) setMuted(!video.muted); break;
      case 'fs': if (!e.repeat) c.toggleFs(); break;
      case 'subs': if (!e.repeat) { c.toggleSubs(); render(); } break;
      case 'next': if (!e.repeat && c.canEp(1)) c.episode(1); break;
      case 'prev': if (!e.repeat && c.canEp(-1)) c.episode(-1); break;
      case 'stop': c.stop(); break;
      case 'escape': {
        if (e.repeat) break;
        const st = escapeStep({ menuOpen: !!menu, fullscreen: !!document.fullscreenElement });
        if (st === 'menu') closeMenu(); else if (st === 'fullscreen') document.exitFullscreen().catch(() => {}); else c.stop();
        break;
      }
      default: return false;
    }
    return true;
  }

  applyCaptionLook();
  render(); show();
  return {
    el, render, show, onKey,
    menuOpen: () => !!menu,
    closeMenu: () => closeMenu(false),
    busy: (on) => { if (on) busyOn(); else { clearTimeout(spinTimer); spin.hidden = true; } },
    destroy() {
      clearTimeout(hideTimer); clearTimeout(spinTimer); clearTimeout(tapTimer);
      for (const [n, f] of evs) video.removeEventListener(n, f);
      document.removeEventListener('fullscreenchange', onFs);
      layer.removeEventListener('pointermove', onPointerMove);
      layer.classList.remove('vod', 'paused', 'menu-open');
    },
  };
}
