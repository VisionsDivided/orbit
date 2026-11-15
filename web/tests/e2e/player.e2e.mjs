// Copyright 2026 The Orbit authors. SPDX-License-Identifier: Apache-2.0
// The film / episode player in a real browser (same mock stack as focus.e2e.mjs: a mock Xtream server whose
// films and episodes are one ffmpeg-made MP4):
// - Space pauses and resumes at the same position (it used to restart the film: the quick-settings bar opened
//   with "Restart" lit, and Space activated the lit item);
// - while the player is open the page behind it is inert, and a key whose target is outside the player still
//   goes to the player;
// - every control shows and hides together (one element, one class, one timer);
// - Escape closes the settings menu first, then the player; previous / next episode only where they apply.
//
//   node --test web/tests/e2e/player.e2e.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { loadPlaywright, missing, startStack, sleep } from './stack.mjs';

const pw = loadPlaywright();
const skip = missing(pw);

test('film player: Space, focus trap, controls together, menus, episodes', { skip, timeout: 240000 }, async (t) => {
  const stack = await startStack();
  const B = stack.base;
  let browser = null;
  t.after(async () => { try { if (browser) await browser.close(); } catch {} await stack.stop(); });
  const launch = async (channel) => pw.chromium.launch({ channel, headless: true, args: ['--autoplay-policy=no-user-gesture-required', '--mute-audio'] });
  try { browser = await launch('chrome'); } catch { browser = await launch('msedge'); }
  const page = await stack.auth(await browser.newPage({ viewport: { width: 1400, height: 860 } }));
  const errors = []; page.on('pageerror', (e) => errors.push(String(e)));
  await page.goto(B + '/#home');
  await sleep(800);
  const list = (kind) => page.evaluate(async (k) => (await (await fetch('/api/vod/list?kind=' + k)).json()).items, kind);
  const films = await list('movie');
  const film = films.find((f) => /Test Film/.test(f.name));
  assert.ok(film, 'a mock film');
  const vt = () => page.evaluate(() => { const v = document.querySelector('#player-layer video'); return v ? { t: v.currentTime, paused: v.paused, src: v.currentSrc } : null; });
  const playing = (min) => page.waitForFunction((m) => { const v = document.querySelector('#player-layer video'); return v && v.currentTime > m && !v.paused; }, min, { timeout: 45000 });

  await t.test('Space pauses and resumes in place (keyboard: Play focused on the details page)', async () => {
    await page.goto(B + '/#detail:movie:' + encodeURIComponent(film.id));
    await page.waitForSelector('.btns .focusable', { timeout: 15000 });
    await page.focus('.btns .focusable');
    await page.keyboard.press('Enter');
    await playing(3);
    const src = (await vt()).src;
    await page.keyboard.press(' '); await sleep(700);
    const a = await vt();
    assert.equal(a.paused, true, 'Space paused');
    assert.ok(a.t > 2.5, 'not back at the start: ' + a.t);
    await sleep(1200);
    const a2 = await vt();
    assert.ok(Math.abs(a2.t - a.t) < 0.3 && a2.paused, 'stays paused where it was');
    await page.keyboard.press(' '); await sleep(1500);
    const b = await vt();
    assert.equal(b.paused, false, 'Space resumed');
    assert.ok(b.t >= a.t, `resumed from ${a.t}, not the start (${b.t})`);
    assert.equal(b.src, src, 'the same stream (no new play request)');
    assert.equal(await page.evaluate(() => document.getElementById('app').inert), true, 'the page behind is inert');
    // K too; and a key whose target is outside the player (the focus lost to <body>) still goes to the player
    await page.evaluate(() => document.activeElement && document.activeElement.blur());
    await page.keyboard.press('k'); await sleep(500);
    assert.equal((await vt()).paused, true, 'K with the focus on <body> paused');
    const c = (await vt()).t;
    await page.keyboard.press('Space'); await sleep(1200);
    const d = await vt();
    assert.ok(!d.paused && d.t >= c, 'Space with the focus on <body> resumed in place');
    // a button in the bar has the focus (a mouse click left it there): Space still only plays / pauses
    await page.mouse.move(700, 400);
    await page.focus('.vb-btn.vb-stop');
    await page.keyboard.press(' '); await sleep(600);
    assert.equal(await page.evaluate(() => !document.getElementById('player-layer').hidden), true, 'Space did not click Stop');
    assert.equal((await vt()).paused, true, 'Space paused');
    await page.keyboard.press(' '); await sleep(600);
  });

  await t.test('all controls show and hide together; arrows seek; J / L', async () => {
    await page.mouse.move(600, 300); await page.mouse.move(620, 320); await sleep(300);
    const shown = await page.evaluate(() => ({ chrome: document.getElementById('player-layer').classList.contains('chrome'), op: getComputedStyle(document.querySelector('.vb')).opacity }));
    assert.equal(shown.chrome, true); assert.equal(shown.op, '1');
    await sleep(3700);   // 3 s without input while playing
    const st = await page.evaluate(() => {
      const L = document.getElementById('player-layer'), vb = document.querySelector('.vb');
      const parts = ['.vb-top', '.vb-clock', '.vb-toptitle', '.vb-bottom', '.vb-prog', '.vb-time', '.vb-title', '.vb-mid', '.vb-right'];
      return { chrome: L.classList.contains('chrome'), vb: getComputedStyle(vb).opacity, cursor: getComputedStyle(L).cursor,
        inside: parts.every((s) => { const e = document.querySelector(s); return e && vb.contains(e); }),
        own: parts.map((s) => getComputedStyle(document.querySelector(s)).opacity) };
    });
    assert.equal(st.chrome, false, 'hidden after 3 s');
    assert.equal(st.vb, '0');
    assert.equal(st.cursor, 'none', 'the cursor hides with them');
    assert.ok(st.inside, 'every control is inside .vb');
    assert.ok(st.own.every((o) => o === '1'), 'no part fades on its own: ' + st.own);
    // a key shows them; Right / Left seek 10 s; L 30 s
    const t0 = (await vt()).t;
    await page.keyboard.press('ArrowRight'); await sleep(600);
    assert.equal(await page.evaluate(() => document.getElementById('player-layer').classList.contains('chrome')), true, 'a key shows the controls');
    assert.ok((await vt()).t >= t0 + 9, 'Right: +10 s');
    const t1 = (await vt()).t;
    await page.keyboard.press('l'); await sleep(600);
    assert.ok((await vt()).t >= t1 + 28, 'L: +30 s');
    const t2 = (await vt()).t;
    await page.keyboard.press('j'); await sleep(600);
    assert.ok((await vt()).t <= t2 - 8, 'J: −10 s');
    // paused: the controls stay
    await page.keyboard.press(' '); await sleep(3800);
    assert.equal(await page.evaluate(() => document.getElementById('player-layer').classList.contains('chrome')), true, 'paused: still shown');
    await page.keyboard.press(' ');
  });

  await t.test('progress bar click seeks; settings menu; Escape order', async () => {
    await page.mouse.move(500, 300); await sleep(200);
    const r = await page.$eval('.vb-prog', (e) => { const b = e.getBoundingClientRect(); return { x: b.left + b.width * 0.75, y: b.top + b.height / 2 }; });
    await page.mouse.click(r.x, r.y); await sleep(1500);
    const v = await vt();
    assert.ok(v.t > 170 && v.t < 200, 'clicked at 75 % of 240 s: ' + v.t);
    await page.click('.vb-btn[aria-label="Settings"]'); await sleep(300);
    const rows = await page.$$eval('.vb-menu .vb-mi', (b) => b.map((x) => x.textContent));
    for (const want of ['Playback speed', 'Aspect', 'Subtitle appearance', 'Keyboard shortcuts']) assert.ok(rows.some((x) => x.includes(want)), want + ' in ' + rows);
    await page.click('.vb-mi:has-text("Aspect")'); await sleep(200);
    assert.ok((await page.$$eval('.vb-menu .vb-mi', (b) => b.map((x) => x.textContent))).some((x) => x.includes('Fit, crop black bars')));
    await page.keyboard.press('Escape'); await sleep(300);
    assert.equal(await page.evaluate(() => document.querySelector('.vb-menu').hidden), true, 'Escape closed the menu');
    assert.equal(await page.evaluate(() => !document.getElementById('player-layer').hidden), true, '... and not the player');
    await page.keyboard.press('Escape'); await sleep(800);
    assert.equal(await page.evaluate(() => document.getElementById('player-layer').hidden), true, 'second Escape closed the player');
    assert.equal(await page.evaluate(() => document.getElementById('app').inert), false, 'the page is usable again');
  });

  await t.test('episodes: previous hidden on the first, next shown; N plays the next in place', async () => {
    const shows = await list('series');
    const show = shows.find((s) => s.name === 'Mock Show');
    await page.goto(B + '/#detail:series:' + encodeURIComponent(show.id));
    await page.waitForSelector('.btns .focusable', { timeout: 15000 });
    await page.focus('.btns .focusable');
    await page.keyboard.press('Enter');
    await playing(1);
    await page.mouse.move(500, 300); await sleep(300);
    const nav = await page.evaluate(() => ({ prev: document.querySelector('.vb-btn[aria-label^="Previous"]').hidden, next: document.querySelector('.vb-btn[aria-label^="Next"]').hidden, ep: document.querySelector('.vb-ep').textContent }));
    assert.equal(nav.prev, true, 'no previous episode before S1 E1');
    assert.equal(nav.next, false);
    assert.match(nav.ep, /^S1 · E1 — /);
    await page.keyboard.press('n'); await sleep(2500);
    assert.match(await page.evaluate(() => document.querySelector('.vb-ep').textContent), /^S1 · E2 — /);
    assert.equal(await page.evaluate(() => document.querySelector('.vb-btn[aria-label^="Previous"]').hidden), false);
    await page.keyboard.press('Escape'); await sleep(600);
  });

  assert.deepEqual(errors, [], 'no page errors');
});
