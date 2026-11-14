// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
// Browser test of focus / remote-key navigation on every screen, the guide's "never the past" rule,
// the preview that does not follow focus, and leaving full screen with the same <video> playing.
// The shell is Orbit direction 5: a top bar (Home · Movies · Shows · Live TV · Discover, then search and the
// avatar). Left / Right move along the bar, Down (or OK on the lit item) enters the screen, Up at the top of a
// screen (or Left at its left edge, Escape) comes back to the bar. My list, Recordings and Settings are in the
// avatar's menu.
//
// No real provider: a mock M3U + XMLTV (now-12h .. now+24h) and an ffmpeg-made HLS clip are served by
// a local HTTP server; the real backend (web/server) runs against it on a free port with a temp data
// dir; Chrome (or Edge) is driven by Playwright.
//
//   node --test web/tests/e2e/
//
// Needs: python 3 + cryptography, ffmpeg, Playwright (PLAYWRIGHT_DIR, or a
// normal `playwright` install), Chrome or Edge, and web/frontend/vendor/hls.min.js (fetched at deploy;
// copy it from the CT). Missing pieces skip the test with the reason.
import test from 'node:test';
import assert from 'node:assert/strict';
import { loadPlaywright, missing, startStack, sleep } from './stack.mjs';

const pw = loadPlaywright();
const skip = missing(pw);

test('focus navigation, guide window, preview and full screen in a real browser', { skip, timeout: 240000 }, async (t) => {
  const stack = await startStack();
  const B = stack.base;
  let browser = null;
  t.after(async () => { try { if (browser) await browser.close(); } catch {} await stack.stop(); });

  const launch = async (channel) => pw.chromium.launch({ channel, headless: true, args: ['--autoplay-policy=no-user-gesture-required', '--mute-audio'] });
  try { browser = await launch('chrome'); } catch { browser = await launch('msedge'); }
  const page = await stack.auth(await browser.newPage({ viewport: { width: 1400, height: 860 } }));
  const errors = [], logs = []; page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => { logs.push(m.type() + ': ' + m.text()); if (logs.length > 40) logs.shift(); });
  page.on('requestfailed', (r) => { logs.push('failed: ' + r.url().slice(0, 120) + ' ' + (r.failure() || {}).errorText); });
  page.on('response', (r) => { if (r.status() >= 400) logs.push('http ' + r.status() + ' ' + r.url().slice(0, 120)); });
  const vstate = (sel) => page.evaluate((sel) => { const v = document.querySelector(sel); return v ? JSON.stringify({ src: v.currentSrc.slice(0, 80), rs: v.readyState, ns: v.networkState, t: v.currentTime, paused: v.paused, err: v.error && v.error.message }) : 'no video'; }, sel);
  await page.goto(B + '/#live');

  const tv = () => page.evaluate(() => { const r = document.querySelector('.tv2'); return r && r.tvState ? r.tvState() : null; });
  const key = async (k, n = 1) => { for (let i = 0; i < n; i++) { await page.keyboard.press(k); await sleep(60); } await sleep(120); };
  const active = () => page.evaluate(() => { const a = document.activeElement; return a ? { tag: a.tagName, cls: a.className, text: (a.textContent || '').trim().slice(0, 40), screen: a.dataset ? a.dataset.screen : null, inMain: !!a.closest('#main'), inBar: !!a.closest('#topbar, #tabbar'), avatar: a.classList.contains('tb-avatar'), id: a.dataset && (a.dataset.fk || '') } : null; });
  /** Exactly one focus indicator is visible (DOM focus in the top bar or in a screen, or the guide's own). */
  const focusVisible = () => page.evaluate(() => {
    const a = document.activeElement, vis = (el) => { const cs = getComputedStyle(el); return (cs.boxShadow && cs.boxShadow !== 'none') || (cs.outlineStyle !== 'none' && parseFloat(cs.outlineWidth) > 0); };
    if (a && a !== document.body && a.closest('#topbar, #main, .modal, #player-layer')) {
      if (a.closest('.tv2')) { /* the guide uses its own marks */ } else {
        // cards ring their picture; the search field rings its rounded frame
        const pic = a.querySelector(':scope > .pic'), frame = a.closest('.srch5-field');
        return vis(a) || (pic && vis(pic)) || (frame && getComputedStyle(frame).outlineStyle !== 'none') ? 'dom' : 'dom-invisible:' + a.className;
      }
    }
    const marks = document.querySelectorAll('.gitem.kf, .g-row.lf .g-label, .g-blk.foc');
    return marks.length === 1 ? 'guide' : 'guide-marks:' + marks.length;
  });
  /** Walk the top bar (focus already in it) to a screen's item ('avatar' for the avatar) with Left / Right. */
  const gotoNav = async (screen) => {
    for (let i = 0; i < 12; i++) {
      const d = await page.evaluate((s) => {
        const it = [...document.querySelectorAll('#topbar #nav .nav-item, #topbar .tb-btn')];
        return it.findIndex((x) => (s === 'avatar' ? x.classList.contains('tb-avatar') : x.dataset.screen === s)) - it.indexOf(document.activeElement);
      }, screen);
      if (d === 0) return; await key(d > 0 ? 'ArrowRight' : 'ArrowLeft');
    }
  };
  /** Put the focus on the lit top bar item (a test that failed may have left it in a screen). */
  const toBar = () => page.evaluate(() => { document.querySelectorAll('.modal-back').forEach((x) => x.remove()); const b = document.querySelector('#topbar .nav-item.active') || document.querySelector('#topbar .nav-item'); b.focus(); });
  /** Open a screen from the avatar's menu (focus in the top bar); the focus comes back to the avatar. */
  const viaAvatar = async (label) => {
    await gotoNav('avatar'); await key('Enter');
    await page.waitForSelector('.modal .menu-item', { timeout: 5000 });
    const i = await page.$$eval('.modal .menu-item', (b, l) => b.findIndex((x) => x.textContent.includes(l)), label);
    assert.ok(i >= 0, 'avatar menu has ' + label);
    await key('ArrowDown', i); await key('Enter'); await sleep(600);
  };
  /** Up to the guide's first row (one more Up would go to the group tabs above it). */
  const toRow0 = async () => { for (let i = 0; i < 20; i++) { const s = await tv(); if (s.row === 0 && (s.zone === 'label' || s.zone === 'grid')) return; await key('ArrowUp'); } };
  const assertFocus = async (msg) => { const f = await focusVisible(); assert.ok(f === 'dom' || f === 'guide', `${msg}: focus not visible (${f})`); };

  // the guide is there with programmes
  await page.waitForFunction(() => document.querySelectorAll('.g-blk[data-s]').length > 8, null, { timeout: 30000 });
  await page.waitForFunction(() => { const r = document.querySelector('.tv2'); return r && r.tvState && r.tvState().current; }, null, { timeout: 10000 });
  const s0 = await tv();

  await t.test('1. the guide starts at the current half hour and never shows the past', async () => {
    const now = Math.floor(Date.now() / 1000);
    assert.equal(s0.winStart, Math.floor(now / 1800) * 1800);
    // Right into the grid, then Left many times: never before the half hour, focus ends on the channel name
    await key('ArrowRight');
    let s = await tv(); assert.equal(s.zone, 'grid');
    assert.ok(s.focus && s.focus.start <= now && s.focus.stop > now, 'Right from the channel name lands on the programme on now');
    await key('ArrowRight', 6);
    s = await tv(); assert.ok(s.focus.start > now, 'moved forward');
    const trace = [];
    for (let i = 0; i < 25; i++) { await page.keyboard.press('ArrowLeft'); await sleep(40); const x = await tv(); trace.push(x.zone + ' ' + (x.winStart - Math.floor(now / 1800) * 1800) / 60 + ' ' + (x.focus ? (x.focus.start - now) / 60 + '..' + (x.focus.stop - now) / 60 : '-')); assert.ok(x.winStart >= Math.floor(now / 1800) * 1800, 'window went into the past'); if (x.zone !== 'grid') break; }
    s = await tv(); assert.equal(s.zone, 'label', 'Left at the window start moves focus to the channel name');
    assert.equal(s.winStart, Math.floor(now / 1800) * 1800, 'window back at now: ' + trace.join(' | '));
    await assertFocus('channel name');
    // no programme block in the grid is entirely in the past
    const ends = await page.evaluate(() => [...document.querySelectorAll('.g-blk[data-s]')].map((b) => +b.dataset.s));
    assert.ok(ends.length && Math.min(...ends) > now - 3 * 3600, 'only programmes around now and later are drawn');
    // Home / Now and the "earlier" button never go before now either
    await page.click('.ruler-btns .rb:first-child'); s = await tv(); assert.equal(s.winStart, Math.floor(now / 1800) * 1800);
  });

  await t.test('2. channel name > group tabs > sub-menu row > top bar; Down comes back the same way, focus restored', async () => {
    let s = await tv(); const row = s.row;
    await key('ArrowLeft'); s = await tv(); assert.equal(s.zone, 'groups'); await assertFocus('groups');
    const sel = await page.evaluate(() => document.querySelector('.gitem.kf').classList.contains('sel'));
    assert.ok(sel, 'entering the group tabs focuses the selected group');
    await key('ArrowUp'); s = await tv(); assert.equal(s.zone, 'sub', 'Up from the tabs: the sub-menu row (Channels · Guide · Recordings · My list)');
    assert.equal(await page.evaluate(() => document.querySelectorAll('.tv-sub .pill.kf').length), 1, 'one sub-menu item marked');
    await key('ArrowUp'); let a = await active(); assert.equal(a.screen, 'live', 'Up from the sub-menu row goes to the top bar (Live TV)'); await assertFocus('top bar');
    await key('ArrowLeft'); a = await active(); assert.equal(a.screen, 'series', 'Left in the top bar moves along it');
    await key('ArrowRight'); a = await active(); assert.equal(a.screen, 'live', 'and Right back (the screen is not changed by moving)');
    assert.equal(await page.evaluate(() => location.hash), '#live');
    await key('ArrowDown'); s = await tv(); assert.equal(s.zone, 'groups', 'Down from the top bar returns to the group tabs');
    await key('ArrowDown'); s = await tv(); assert.equal(s.zone, 'label'); assert.equal(s.row, row, 'Down from the tabs: the same channel row');
    await key('ArrowRight'); s = await tv(); assert.equal(s.zone, 'grid');
    // Up / Down at the ends do not trap or jump
    await toRow0(); s = await tv(); assert.equal(s.row, 0); await assertFocus('top row');
    await key('ArrowUp'); s = await tv(); assert.equal(s.zone, 'groups', 'Up from the first row: the group tabs');
    await key('ArrowDown'); s = await tv(); assert.equal(s.row, 0, 'and back down to the first row');
    await key('ArrowRight');
    await key('ArrowDown', 3); s = await tv(); assert.equal(s.row, 3);
  });

  await t.test('2b. quick tabs and the "All groups" side menu: search, pin (Red), choose, Escape', async () => {
    let s = await tv(); const group0 = s.group, row0 = s.row;
    assert.deepEqual(s.tabs.slice(0, 3), ['__fav', '__all', '__recent'], 'Favourites, All, Recent first');
    assert.equal(s.tabs[s.tabs.length - 1], '__menu', '"All groups" last');
    await toRow0(); await key('ArrowUp'); s = await tv(); assert.equal(s.zone, 'groups');
    await key('ArrowRight', s.tabs.length); s = await tv(); assert.equal(s.tabs[s.gi], '__menu');
    assert.equal(s.group, group0, 'moving onto "All groups" does not change the group');
    await key('Enter'); s = await tv(); assert.ok(s.menuOpen, 'OK opens the side menu');
    assert.ok(await page.evaluate(() => document.activeElement && document.activeElement.classList.contains('gm-q')), 'typing goes to the search field');
    await page.keyboard.type('sport'); await sleep(900);
    const labels = await page.evaluate(() => [...document.querySelectorAll('.gm-row')].map((r) => r.className.includes('gm-chan') ? 'chan' : r.textContent));
    assert.ok(labels.some((l) => l.startsWith('Sport')), 'the group is found: ' + labels.join(' | '));
    assert.ok(labels.includes('chan'), 'and channels whose name matches');
    await key('ArrowDown'); s = await tv(); assert.ok(s.menuFocus >= 0);
    await page.evaluate(() => document.activeElement.dispatchEvent(new KeyboardEvent('keydown', { key: 'ColorF0Red', bubbles: true, cancelable: true })));
    await sleep(200); s = await tv(); assert.deepEqual(s.pins, ['Sport'], 'Red pins the focused group');
    await key('Enter'); s = await tv();
    assert.ok(!s.menuOpen, 'choosing a group closes the menu'); assert.equal(s.zone, 'label');
    assert.ok(s.tabs.includes(s.group) && s.tabs.indexOf(s.group) === 3, 'the pinned group is a tab after Recent');
    const saved = await page.evaluate(async () => (await (await fetch('/api/state')).json()).prefs.liveGroupsPinned);
    assert.deepEqual(saved, ['Sport'], 'saved in the profile prefs (liveGroupsPinned)');
    // Escape closes the menu and leaves the focus on "All groups"
    await toRow0(); await key('ArrowUp'); s = await tv(); await key('ArrowRight', s.tabs.length); await key('Enter');
    s = await tv(); assert.ok(s.menuOpen);
    assert.ok(await page.evaluate(() => { const r = document.querySelector('.gm-row.kf'); return !!(r && r.classList.contains('cur') && r.querySelector('.gm-pin.on')); }), 'the menu opens on the group shown now');
    await page.evaluate(() => document.activeElement.dispatchEvent(new KeyboardEvent('keydown', { key: 'ColorF0Red', bubbles: true, cancelable: true })));
    await sleep(200); s = await tv(); assert.deepEqual(s.pins, [], 'Red again unpins');
    await key('Escape'); s = await tv(); assert.ok(!s.menuOpen, 'Escape closes'); assert.equal(s.zone, 'groups'); assert.equal(s.tabs[s.gi], '__menu');
    // back to where the guide was
    const i0 = s.tabs.indexOf(group0); assert.ok(i0 >= 0);
    await key('ArrowLeft', s.gi - i0); await key('Enter'); await sleep(600);
    s = await tv(); assert.equal(s.group, group0);
    await key('ArrowDown', row0); await key('ArrowRight');
  });

  await t.test('3. moving focus does not change the preview; Enter switches it; Enter again goes full screen', async () => {
    let s = await tv(); const cur = s.current;
    await page.waitForFunction((k) => document.querySelector('.tv2').tvState().previewKey === k, cur, { timeout: 10000 });
    await key('ArrowUp', 3); await key('ArrowDown', 2); await sleep(1200);
    s = await tv(); assert.equal(s.current, cur, 'current channel unchanged'); assert.equal(s.previewKey, cur, 'preview did not follow focus');
    assert.notEqual(s.rowKey, cur);
    const target = s.rowKey;
    await key('Enter'); s = await tv();
    assert.equal(s.current, target, 'Enter on another channel makes it current');
    assert.equal(await page.evaluate(() => document.getElementById('player-layer').hidden), true, 'not full screen yet');
    await page.waitForFunction((k) => document.querySelector('.tv2').tvState().previewKey === k, target, { timeout: 10000 });
    try { await page.waitForFunction(() => { const v = document.querySelector('.pv video'); return v && v.currentTime > 0.5 && !v.paused; }, null, { timeout: 20000 }); }
    catch (e) { throw new Error('preview does not play: ' + (await vstate('.pv video')) + '\n' + logs.join('\n')); }
    await key('Enter');
    assert.equal(await page.evaluate(() => document.getElementById('player-layer').hidden), false, 'Enter on the playing channel goes full screen');
  });

  await t.test('4. Back from full screen: shrink animation, same <video> keeps playing with sound, focus on the playing channel', async () => {
    await page.waitForFunction(() => { const v = document.querySelector('#player-layer video'); return v && v.currentTime > 1 && !v.paused && v.readyState >= 3; }, null, { timeout: 30000 });
    await page.evaluate(() => {
      const v = document.querySelector('#player-layer video'); v.__mark = 'fs'; v.__ev = [];
      for (const n of ['pause', 'emptied', 'abort', 'loadstart']) v.addEventListener(n, () => v.__ev.push(n));
      v.__t0 = v.currentTime;
    });
    // the quick-settings bar closes first (topmost layer), then full screen
    await key('ArrowDown'); assert.equal(await page.evaluate(() => !document.querySelector('.pl-qs').hidden), true, 'quick settings open');
    await key('Escape'); assert.equal(await page.evaluate(() => !document.getElementById('player-layer').hidden), true, 'Escape closed only the quick settings');
    // the info overlay (if shown) is the next layer, then full screen itself; one layer per Escape
    let anim = '';
    for (let i = 0; i < 3; i++) {
      const infoUp = await page.evaluate(() => !!document.querySelector('#player-layer .pl-info:not([hidden])'));
      await page.keyboard.press('Escape'); await sleep(60);
      const st = await page.evaluate(() => ({ tr: document.getElementById('player-layer').style.transform || '', open: !document.getElementById('player-layer').hidden, info: !!document.querySelector('#player-layer .pl-info:not([hidden])') }));
      if (infoUp) { assert.equal(st.info, false, 'Escape hid the info overlay'); assert.ok(st.open || st.tr, 'and did not close full screen too'); continue; }
      anim = st.tr; break;
    }
    await sleep(800);
    assert.equal(await page.evaluate(() => document.getElementById('player-layer').hidden), true, 'full screen closed');
    const v = await page.evaluate(() => { const v = document.querySelector('.pv video'); return v && { mark: v.__mark, paused: v.paused, muted: v.muted, ev: v.__ev, t0: v.__t0, t: v.currentTime }; });
    assert.ok(v, 'video in the preview'); assert.equal(v.mark, 'fs', 'the same <video> element moved into the preview');
    assert.equal(v.paused, false); assert.equal(v.muted, false, 'sound kept');
    assert.deepEqual(v.ev, [], 'no pause / reload while shrinking');
    await sleep(1000);
    const t2 = await page.evaluate(() => document.querySelector('.pv video').currentTime);
    assert.ok(t2 > v.t, 'still playing after the shrink');
    assert.ok(anim === '' || /scale/.test(anim), 'shrink uses a transform');
    const s = await tv();
    assert.equal(s.rowKey, s.current, 'focus lands on the playing channel'); assert.equal(s.docked, true);
    await assertFocus('after full screen');
    // moving focus now still leaves the docked picture alone
    await key('ArrowDown', 2); await sleep(1000);
    const v2 = await page.evaluate(() => { const v = document.querySelector('.pv video'); return { mark: v.__mark, paused: v.paused, ev: v.__ev }; });
    assert.equal(v2.mark, 'fs'); assert.equal(v2.paused, false); assert.deepEqual(v2.ev, []);
    await key('ArrowUp', 2);
  });

  await t.test('5. menus are the topmost layer; catch-up is reached from the channel menu', async () => {
    await toRow0();                                 // Alpha News (catch-up)
    const before = await tv();
    await key('m');
    assert.ok(await page.$('.modal-back'), 'menu open'); await assertFocus('menu');
    const labels = await page.$$eval('.modal .menu-item', (b) => b.map((x) => x.textContent));
    const ci = labels.findIndex((l) => l.startsWith('⟲ Catch-up: earlier'));
    assert.ok(ci >= 0, 'channel menu offers catch-up: ' + labels.join(' | '));
    await key('ArrowDown', 3); await key('ArrowUp', 6); await assertFocus('menu after arrows');
    await key('Escape'); assert.equal(await page.$('.modal-back'), null, 'Escape closed the menu');
    let s = await tv(); assert.equal(s.zone, before.zone, 'and only the menu'); assert.equal(s.row, before.row);
    await key('m');
    const items = await page.$$('.modal .menu-item'); await items[ci].click();
    await page.waitForFunction(() => document.querySelector('.modal-title') && /catch-up/.test(document.querySelector('.modal-title').textContent), null, { timeout: 5000 });
    const n = await page.$$eval('.modal .menu-item', (b) => b.length);
    assert.ok(n > 5, 'past programmes listed');
    await key('Escape'); assert.equal(await page.$('.modal-back'), null);
    s = await tv(); assert.equal(s.zone, before.zone);
    // Escape on the screen: guide > groups > top bar
    await key('Escape'); s = await tv(); assert.equal(s.zone, 'groups');
    await key('Escape'); const a = await active(); assert.equal(a.screen, 'live');
  });

  await t.test('6. every other screen: arrows reach items, Up / Left go to the top bar, Down restores the item, Escape closes', async () => {
    // from the bar: Movies, Shows, Discover, Search; from the avatar's menu: My list, Recordings, Settings
    const screens = [['movies'], ['series'], ['discover'], ['search'], ['mylist', 'My list'], ['recordings', 'Recordings'], ['settings', 'Settings']];
    for (const [screen, menuLabel] of screens) {
      let a = await active();
      if (!a.inBar) await toBar();
      if (menuLabel) {
        await viaAvatar(menuLabel);
        await page.waitForFunction((s) => location.hash.replace('#', '').split(':')[0] === s, screen, { timeout: 5000 });
        a = await active(); assert.ok(a.inBar, screen + ': after the menu the focus is back in the top bar'); await assertFocus('bar ' + screen);
      } else {
        await gotoNav(screen);
        a = await active(); assert.equal(a.screen, screen, 'top bar reaches ' + screen); await assertFocus('bar ' + screen);
        await key('Enter'); await sleep(600);          // opens the screen
        a = await active(); assert.equal(a.screen, screen, 'opening keeps the focus in the top bar');
      }
      await key('ArrowDown'); await sleep(300);        // into the screen
      a = await active();
      const hasItems = await page.evaluate(() => [...document.querySelectorAll('#main .focusable')].some((e) => e.offsetParent !== null));
      if (!hasItems) { assert.ok(!a.inMain || a.inBar, screen + ': nothing to focus, no trap'); continue; }
      if (screen === 'search' && a.tag === 'INPUT') { await key('ArrowDown'); a = await active(); }
      assert.ok(a.inMain, screen + ': Down enters the screen'); await assertFocus(screen);
      // move around: focus always visible, never lost
      for (const k of ['ArrowDown', 'ArrowDown', 'ArrowRight', 'ArrowUp', 'ArrowDown']) {
        await key(k); a = await active();
        if (a.tag === 'SELECT') {
          const v0 = await page.evaluate(() => document.activeElement.value);
          await key('ArrowDown');
          const after = await page.evaluate(() => ({ tag: document.activeElement.tagName, value: document.activeElement.tagName === 'SELECT' ? document.activeElement.value : null }));
          assert.ok(after.tag !== 'SELECT' || after.value === v0, 'Down on a select moves on and does not change its value');
          a = await active();
        }
        assert.ok(a.inMain || a.inBar, `${screen} ${k}: focus lost`); await assertFocus(`${screen} ${k}`);
      }
      if (!a.inMain) continue;
      // Up until the top bar; the item focused just before it is the one Down must bring back
      const markHere = () => page.evaluate(() => { document.querySelectorAll('[data-e2e]').forEach((x) => delete x.dataset.e2e); const e = document.activeElement; if (e && e.closest('#main')) e.dataset.e2e = 'here'; });
      for (let i = 0; i < 25; i++) { a = await active(); if (!a.inMain) break; await markHere(); await key('ArrowUp'); }
      a = await active(); assert.ok(a.inBar, screen + ': Up ends in the top bar');
      await key('ArrowDown');
      const back = await page.evaluate(() => document.activeElement && document.activeElement.dataset.e2e === 'here');
      const inMain = await page.evaluate(() => !!(document.activeElement && document.activeElement.closest('#main')));
      assert.ok(inMain, screen + ': Down returns into the screen');
      // a text field may legitimately be left through Up; the restored item is the last one focused
      assert.ok(back || screen === 'search', screen + ': Down restores the last focused item');
      await key('Escape'); a = await active(); assert.ok(a.inBar, screen + ': Escape goes to the top bar');
    }
  });

  await t.test('6b. Movies and Shows Browse: Enter opens the page, Escape comes back to the same card; arrows in the grid', async () => {
    for (const screen of ['movies', 'series']) {
      await page.evaluate((s) => { location.hash = s + ':browse'; }, screen);
      try { await page.waitForFunction(() => document.querySelectorAll('#main .grid5 .card5').length > 1, null, { timeout: 15000 }); }
      catch (e) { throw new Error(screen + ': no cards: ' + JSON.stringify(await page.evaluate(() => ({ hash: location.hash, main: document.querySelector('#main').innerText.slice(0, 300) }))) + '\n' + logs.join('\n')); }
      await toBar();
      let a = await active(); assert.equal(a.screen, screen, screen + ': lit in the top bar');
      await key('ArrowDown');
      a = await active(); assert.ok(a.inMain, screen + ': focus in the screen');
      // walk down from the sub-menu row to a card of the grid
      for (let i = 0; i < 4; i++) { const isCard = await page.evaluate(() => !!document.activeElement.closest('.grid5 .card5')); if (isCard) break; await key('ArrowDown'); }
      await key('ArrowRight');
      const id = await page.evaluate(() => document.activeElement.closest('.grid5') && document.activeElement.dataset.key);
      assert.ok(id, screen + ': a card has the focus'); await assertFocus(screen + ' card');
      await key('Enter'); await page.waitForFunction(() => location.hash.startsWith('#detail:'), null, { timeout: 5000 });
      await page.waitForFunction(() => document.activeElement && document.activeElement.closest('#main'), null, { timeout: 10000 });
      await assertFocus(screen + ' page');
      await key('ArrowDown'); await assertFocus(screen + ' page Down');
      await key('Escape'); await page.waitForFunction(() => !location.hash.startsWith('#detail:'), null, { timeout: 5000 });
      await page.waitForFunction((id) => document.activeElement && document.activeElement.dataset.key === id, id, { timeout: 10000 });
      await assertFocus(screen + ' back on the card');
      await key('Escape'); a = await active(); assert.ok(a.inBar, screen + ': Escape goes to the top bar');
    }
  });

  await t.test('6c. time passing: a window left behind now snaps to now; an ended focused programme moves to the one on now', async () => {
    const p2 = await stack.auth(await browser.newPage({ viewport: { width: 1400, height: 860 } }));
    try {
      await p2.clock.install();
      await p2.goto(B + '/#live');
      await p2.waitForFunction(() => document.querySelectorAll('.g-blk[data-s]').length > 8, null, { timeout: 30000 });
      const st = () => p2.evaluate(() => document.querySelector('.tv2').tvState());
      const press = async (k, n = 1) => { for (let i = 0; i < n; i++) { await p2.keyboard.press(k); await p2.clock.runFor(80); } };
      // the guide comes back where this browser left it (maybe on the group tabs): into the guide first
      // (on the tabs, Down goes to the channel name; one Right fewer then, as Right on the old groups list did that step)
      let down = 0;
      for (; down < 3 && !['label', 'grid'].includes((await st()).zone); down++) await press('ArrowDown');
      if (!down) await press('ArrowRight');
      await press('ArrowRight', 10);
      await p2.clock.runFor(200); await sleep(1500);   // the guide pages the moves asked for arrive (real network time)
      const before = await st(), t0 = await p2.evaluate(() => Math.floor(Date.now() / 1000));
      assert.equal(before.zone, 'grid');
      assert.ok(before.winStart > Math.floor(t0 / 1800) * 1800, 'the window was moved ahead of now (not just following the clock)');
      await p2.clock.fastForward((before.winStart - t0 + 3600) * 1000);   // an hour past the window's start; the 30 s tick runs
      await p2.clock.runFor(31000);
      const after = await st(), now = await p2.evaluate(() => Math.floor(Date.now() / 1000));
      assert.equal(after.winStart, Math.floor(now / 1800) * 1800, 'window snapped to the current half hour');
      assert.ok(after.focus && after.focus.stop > now && after.focus.start < after.winStart + 150 * 60, 'focus is on a programme that has not ended, inside the window');
      // and once that programme is over too, focus moves to the one on now
      await p2.clock.fastForward((after.focus.stop - now + 60) * 1000); await p2.clock.runFor(31000);
      const later = await st(), n2 = await p2.evaluate(() => Math.floor(Date.now() / 1000));
      assert.ok(later.focus && later.focus.start <= n2 && later.focus.stop > n2, 'an ended focused programme moves to the one on now');
      assert.equal(later.winStart, Math.floor(n2 / 1800) * 1800);
    } finally { await p2.close(); }
  });

  await t.test('7. back on TV: the guide restores its place; no page errors', async () => {
    await toBar(); await gotoNav('live'); await key('Enter'); await sleep(800);
    await page.waitForFunction(() => document.querySelector('.tv2') && document.querySelector('.tv2').tvState, null, { timeout: 10000 });
    await key('ArrowDown');
    const s = await tv(); assert.ok(['groups', 'label', 'grid'].includes(s.zone)); await assertFocus('TV again');
    assert.equal(s.winStart, Math.floor(Date.now() / 1000 / 1800) * 1800);
    assert.deepEqual(errors, [], 'page errors: ' + errors.join('\n'));
  });
});
