// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
// Orbit web SPA, direction 5 "Cinematic + top bar": a top bar (logo · Home · Movies · Shows · Live TV ·
// Discover, then search, clock and the profile avatar) over a colour-matched cinematic backdrop.
// On narrow screens (phones) the layout is 5b: a header and a bottom bar with labels under the icons.
// Screens: Home, Movies / Shows (Recommended · Browse · Collections), title details, Live TV (guide,
// player), Discover (Seerr), Search, My list, Recordings, Settings. Dark theme only.

import { api, myList } from './api.js';
import { playerIsOpen } from './player.js';
import { fmtTime, menu } from './util.js';
import { tvScreen } from './tv.js';
import { playLive, resumeLastChannel, forgetCurrent } from './playback.js';
import { prefs } from './prefs.js';
import { vodDetail } from './vod.js';
import { settingsScreen, applyCaptionPrefs } from './settings.js';
import { myListScreen } from './mylist.js';
import { searchScreen } from './search.js';
import { recordingsScreen } from './recordings.js';
import { editorScreen } from './editor.js';
import { startReminders } from './remind.js';
import * as apiMod from './api.js';
import { useStore } from './reminders.js';
import { uiLoad, uiSave } from './uistate.js';
import { ensureSignedIn } from './signin.js';
import { orbitLoad, addNavChip, approveTvDialog, orbitMode, orbitMe, showProfilePicker } from './orbit.js';
import { configure as configureCatalog } from './catalog.js';
import { homeScreen } from './home.js';
import { sectionScreen } from './section.js';
import { discoverScreen } from './discover.js';
import { titleScreen } from './title.js';
import { cachedTitle } from './ui5.js';
import { plainScene } from './backdrop.js';

const main = document.getElementById('main');
const appEl = document.getElementById('app');
const topbar = document.getElementById('topbar');
const DEFAULT = 'home';
let routedHash = null;      // the hash the screen on show was made for
main.addEventListener('scroll', () => {
  const hs = location.hash.replace(/^#/, '') || DEFAULT;
  // a scroll that lands between a hash change and its route() (e.g. a clicked card scrolled into view)
  // belongs to the old screen: it must not become the new screen's saved scroll
  if (routedHash !== null && hs !== routedHash) return;
  const sc = Object.assign({}, uiLoad().scroll || {}); sc[hs] = main.scrollTop;
  const keys = Object.keys(sc); if (keys.length > 30) delete sc[keys[0]];
  uiSave({ scroll: sc });
  appEl.classList.toggle('scrolled', main.scrollTop > 8);
}, { passive: true });
let signedIn = false;
let active = null;   // current screen controller { onKey?, onKeyUp?, focusIn?, destroy? }

// ---------- router ----------
const screens = {};
const TITLES = { home: '', movies: 'Movies', series: 'Shows', live: 'Live TV', discover: 'Discover', search: 'Search', mylist: 'My list', recordings: 'Recordings', settings: 'Settings', editor: 'Channels', detail: '', title: '' };
/** The top bar item lit for a route (none for Settings: the avatar gets its ring instead). */
function navFor(name, arg) {
  if (name === 'detail') return arg.startsWith('series') ? 'series' : 'movies';
  if (name === 'title') { const it = cachedTitle(decodeURIComponent(arg || '')); return it && it.kind === 'show' ? 'series' : 'movies'; }
  if (name === 'play' || name === 'editor' || name === 'recordings') return 'live';
  if (name === 'pair') return 'settings';
  return name;
}
window.addEventListener('hashchange', route);
let restoreTimer = null;
function route() {
  const hs = location.hash.replace(/^#/, '') || DEFAULT; const ci = hs.indexOf(':');
  routedHash = hs;
  uiSave({ hash: hs });
  appEl.classList.remove('scrolled');
  // the page scroll of each section comes back too (best effort: once its content is there)
  clearTimeout(restoreTimer);
  const sy = (uiLoad().scroll || {})[hs];
  if (sy) restoreTimer = setTimeout(() => { if ((location.hash.replace(/^#/, '') || DEFAULT) === hs) main.scrollTop = sy; }, 700);
  else main.scrollTop = 0;
  const name = ci < 0 ? hs : hs.slice(0, ci), arg = ci < 0 ? '' : hs.slice(ci + 1);
  const lit = navFor(name, arg);
  for (const b of document.querySelectorAll('.nav-item')) { const on = b.dataset.screen === lit; b.classList.toggle('active', on); if (on) b.setAttribute('aria-current', 'page'); else b.removeAttribute('aria-current'); }
  document.querySelector('.tb-search').classList.toggle('active', lit === 'search');
  document.querySelector('.tb-avatar').classList.toggle('on', lit === 'settings');
  document.body.dataset.screen = name in screens ? name : DEFAULT;
  topbar.querySelector('.tb-title').textContent = TITLES[lit] != null ? TITLES[lit] : '';
  if (active && active.destroy) { try { active.destroy(); } catch {} }
  active = null; lastFocus = null;
  if (!signedIn && name !== 'settings') { active = screens.settings(); return; }
  const r = (screens[name] || screens[DEFAULT])(arg);
  if (r && typeof r.then !== 'function') active = r;
}
document.querySelectorAll('.nav-item').forEach((b) => b.addEventListener('click', () => { location.hash = b.dataset.screen; }));
document.querySelector('.tb-search').addEventListener('click', () => { location.hash = 'search'; });
document.querySelector('.tb-avatar').addEventListener('click', avatarMenu);
topbar.querySelector('.tb-logo').addEventListener('click', (e) => { e.preventDefault(); location.hash = 'home'; });

/** The avatar: Settings and the places that have no top bar item of their own. */
function avatarMenu() {
  const me = orbitMe();
  menu(me && me.profile ? me.profile.name : 'Orbit', [
    { label: '⚙ Settings', action: () => { location.hash = 'settings'; } },
    orbitMode() && me && me.profiles && me.profiles.length ? { label: "👤 Who's watching?", action: () => showProfilePicker(me) } : null,
    { label: '★ My list', action: () => { location.hash = 'mylist'; } },
    { label: '⏺ Recordings', action: () => { location.hash = 'recordings'; } },
    { label: '🔍 Search', action: () => { location.hash = 'search'; } },
  ].filter(Boolean));
}

// ---------- the top bar (bottom bar on phones) and the keys ----------
/** The bar the keys move in: the top bar on wide screens, the bottom bar + header buttons on phones. */
function barItems() {
  const phone = getComputedStyle(document.getElementById('tabbar')).display !== 'none';
  const list = phone ? [...document.querySelectorAll('#tabbar .nav-item'), ...topbar.querySelectorAll('.tb-btn')]
    : [...topbar.querySelectorAll('#nav .nav-item, .tb-btn')];
  return list.filter((b) => b.offsetParent !== null);
}
const nav = {
  /** Focus the lit bar item (the avatar while in Settings). */
  focusActive() {
    const items = barItems();
    const a = items.find((b) => b.classList.contains('active') || b.classList.contains('on')) || items[0];
    if (a) a.focus();
  },
};
// the last item focused in the current screen: Down from the bar (or an arrow after the focus was lost)
// comes back to it instead of jumping to the top
let lastFocus = null;
main.addEventListener('focusin', (e) => { if (!(active && active.virtualFocus)) lastFocus = e.target; });
const usable = (el) => el && el.isConnected && main.contains(el) && el.offsetParent !== null && !el.disabled;
function enterScreen() {
  if (!active) return;
  if (!active.virtualFocus && usable(lastFocus)) { lastFocus.focus({ preventScroll: true }); lastFocus.scrollIntoView({ block: 'nearest', inline: 'nearest' }); return; }
  if (active.focusIn) active.focusIn();
  // an empty screen (nothing to focus): the focus stays in the bar instead of getting lost
  if (!active.virtualFocus && !main.contains(document.activeElement)) nav.focusActive();
}
document.addEventListener('keydown', (e) => {
  if (playerIsOpen() || document.querySelector('.modal-back, .op-back, .signin-back')) return;
  const ae = document.activeElement;
  const inBar = ae && ae.closest && ae.closest('#topbar, #tabbar');
  if (inBar && (ae.classList.contains('nav-item') || ae.classList.contains('tb-btn'))) {
    const items = barItems(); const i = items.indexOf(ae);
    const bottom = !!ae.closest('#tabbar');
    if (e.key === 'ArrowRight') { e.preventDefault(); if (items[i + 1]) items[i + 1].focus(); return; }
    if (e.key === 'ArrowLeft') { e.preventDefault(); if (i > 0) items[i - 1].focus(); return; }
    if ((e.key === 'ArrowDown' && !bottom) || (e.key === 'ArrowUp' && bottom)) { e.preventDefault(); ae.blur(); enterScreen(); return; }
    if (e.key === 'Enter') {
      e.preventDefault();
      if (ae.classList.contains('nav-item') && ae.classList.contains('active')) { ae.blur(); enterScreen(); } else ae.click();
      return;
    }
    return;
  }
  // nothing focused (a click on empty space, a closed dialog): an arrow brings the focus back where it was
  if ((!ae || ae === document.body) && e.key.startsWith('Arrow') && active && !active.virtualFocus) {
    e.preventDefault();
    if (usable(lastFocus)) lastFocus.focus(); else enterScreen();
    return;
  }
  if (ae && (ae.tagName === 'INPUT' || ae.tagName === 'TEXTAREA')) {
    // a text field keeps its keys, except the ones that leave it (no trap on a remote): Up / Down,
    // Left at the start of the text, Escape
    if (e.defaultPrevented) return;
    let atStart = false; try { atStart = ae.selectionStart === 0 && ae.selectionEnd === 0; } catch {}
    const leave = e.key === 'Escape' || (ae.tagName === 'INPUT' && (e.key === 'ArrowUp' || e.key === 'ArrowDown')) || (e.key === 'ArrowLeft' && atStart);
    if (!leave) return;
  }
  // a <select>: Enter / Space open it (the browser's own picker); arrows move the focus like everywhere else
  // (Up / Down would otherwise change its value and never leave it)
  if (ae && ae.tagName === 'SELECT' && (e.key === 'Enter' || e.key === ' ')) return;
  if (active && active.onKey && active.onKey(e)) e.preventDefault();
});
document.addEventListener('keyup', (e) => { if (active && active.onKeyUp) active.onKeyUp(e); });
// keyboard users get focus rings; a mouse click does not leave one behind
document.addEventListener('keydown', () => document.documentElement.classList.add('kbd'), true);
document.addEventListener('pointerdown', () => document.documentElement.classList.remove('kbd'), true);

// the clock in the top bar
function tickClock() { const c = topbar.querySelector('.tb-clock'); if (c) c.textContent = fmtTime(Math.floor(Date.now() / 1000)); }
setInterval(tickClock, 15000);

// ---------- screens ----------
screens.home = () => homeScreen(main, nav);
screens.live = function () { plainScene(true); return tvScreen(main, nav); };
// #play:<channel key> deep link: TV screen underneath, channel full screen on top
screens.play = function (key) {
  plainScene(true);
  const tv = tvScreen(main, nav);
  api.channelsByKeys([decodeURIComponent(key || '')]).then((r) => { if (r.items[0]) playLive(r.items[0]); else import('./util.js').then((u) => u.toast('Channel not found')); }).catch(() => {});
  return tv;
};
screens.movies = (arg) => sectionScreen(main, 'movie', nav, arg);
screens.series = (arg) => sectionScreen(main, 'series', nav, arg);
screens.detail = (arg) => { const [kind, ...rest] = (arg || '').split(':'); return vodDetail(main, kind === 'series' ? 'series' : 'movie', rest.join(':'), nav); };
screens.title = (arg) => titleScreen(main, nav, decodeURIComponent(arg || ''));
screens.discover = (arg) => discoverScreen(main, nav, arg);
screens.mylist = () => { plainScene(); return myListScreen(main, nav); };
screens.search = (arg) => { plainScene(); return searchScreen(main, nav, arg ? decodeURIComponent(arg) : ''); };
screens.recordings = () => { plainScene(); return recordingsScreen(main, nav); };
screens.editor = (arg) => { plainScene(true); return editorScreen(main, nav, arg ? decodeURIComponent(arg) : null); };
screens.settings = (arg) => settingsScreen(main, nav, { setSignedIn(v) { signedIn = v; refreshNav(); } }, arg);
// Orbit: #pair:<code> is the link in a TV's QR code: Settings with the "Link a TV" dialog on top
screens.pair = (code) => {
  const s = screens.settings('profiles');
  approveTvDialog(decodeURIComponent(code || '')).then(() => { if (location.hash.startsWith('#pair')) location.hash = 'settings'; });
  return s;
};

function refreshNav() { document.querySelectorAll('.nav-item, .tb-search').forEach((b) => { b.classList.toggle('dim', !signedIn); }); }

// ---------- boot ----------
// a channel logo that does not load is hidden (no inline handlers: the CSP forbids them)
document.addEventListener('error', (e) => { const t = e.target; if (t && t.tagName === 'IMG' && t.classList.contains('lg')) t.style.visibility = 'hidden'; }, true);

(async function boot() {
  tickClock();
  plainScene();
  const sess = await ensureSignedIn();          // this device's session (sign-in page otherwise)
  if (!sess) return;
  await orbitLoad();                            // Orbit: accounts mode and who is watching
  // signed in to an account but no profile on this session (a fresh sign-in, or its profile is gone):
  // "Who's watching?" first; picking one reloads the page with that profile
  if (orbitMode() && orbitMe() && !orbitMe().profile) { showProfilePicker(orbitMe(), { force: true }); return; }
  addNavChip();
  configureCatalog({
    headers: () => { const t = apiMod.parentalToken(); return t ? { 'X-Parental': t } : {}; },
    onUnauthorized: () => window.dispatchEvent(new CustomEvent('tvbro-signin', { detail: 'login' })),
    accounts: () => orbitMode(),
  });
  document.documentElement.dataset.role = sess.role;
  signedIn = sess.signedIn;
  useStore(apiMod);
  await apiMod.loadState();
  document.documentElement.classList.toggle('solid', !!prefs().solid);
  fmtTime.fmt = prefs().timeFormat || '12';
  tickClock();
  applyCaptionPrefs();
  startReminders();
  window.addEventListener('tvbro-state', () => { document.documentElement.classList.toggle('solid', !!prefs().solid); fmtTime.fmt = prefs().timeFormat || '12'; tickClock(); applyCaptionPrefs(); });
  refreshNav();
  // the last channel (the newest of this device's and the server's) is the current one again; one that
  // no longer exists is forgotten, so the guide's first channel takes over
  const last = signedIn ? resumeLastChannel() : null;
  if (last) api.channelsByKeys([last.key]).then((r) => { if (!r.items.length) forgetCurrent(last.key); }).catch(() => {});
  // back where this device left off: section, guide position, full screen + channel
  const ui = uiLoad();
  if (!location.hash) location.hash = signedIn ? (ui.hash && !/^(play|editor|pair|title)/.test(ui.hash) ? ui.hash : DEFAULT) : 'settings';
  route();
  if (signedIn && ui.full && last && (location.hash.replace(/^#/, '') || DEFAULT) === 'live') {
    playLive(last, { group: last.groupId || ui.playingGroup || null });
  }
})();
export { myList };
