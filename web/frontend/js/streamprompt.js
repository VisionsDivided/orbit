// Copyright 2026 The Orbit authors. SPDX-License-Identifier: Apache-2.0
// The provider's stream limit is full: ask which stream to stop, as the TV does (docs/orbit/API-v1.md,
// "The provider's stream limit"). Every active stream is listed by name with its own "Stop <name> and watch",
// recordings without a button, plus Cancel. The pure parts (names, the ask-and-start loop) are unit-tested
// (web/tests/streamprompt.test.mjs); the dialog itself is the only DOM code here.

/** One line per active stream, named like the TV's prompt (shared/.../StreamChoice.kt): the device's name, else
 *  the profile, else the device label; two equal names get the label added, then a number. */
export function streamRows(active) {
  const t = (s) => String(s || '').trim();
  const base = (u) => (u.recording ? 'Recording' : t(u.device) || t(u.profile) || t(u.label) || 'Another device');
  const bases = active.map(base);
  const named = active.map((u, i) => {
    const b = bases[i];
    const dup = !u.recording && bases.filter((x) => x === b).length > 1;
    const hasName = t(u.device) || t(u.profile);
    return dup && hasName && t(u.label) && t(u.label) !== b ? `${b} on ${t(u.label)}` : b;
  });
  const seen = new Map();
  return active.map((u, i) => {
    const n = named[i];
    let name = n;
    if (!u.recording && named.filter((x) => x === n).length > 1) { seen.set(n, (seen.get(n) || 0) + 1); name = `${n} (${seen.get(n)})`; }
    const id = u.id || '';
    return { id, name, what: t(u.what), recording: !!u.recording, stoppable: !u.recording && !!id,
      button: `Stop ${name.slice(0, 40)} and watch` };
  });
}

/** Is one of the active streams this tab's own? (/v1/streams gives the first 6 characters of each sid.) */
export const isMine = (st, sid) => (st.active || []).some((a) => a.sid && sid.startsWith(a.sid));

/**
 * Take a stream slot, asking first when every slot is in use.
 * - status(): the /v1/streams answer, or null when unknown (tvBro mode, an older server): then start as before.
 * - start(replace): POST /api/stream/start with that `replace` (undefined = none); resolves to its answer.
 * - ask(max, rows): resolves to the id to stop, '' (stop the oldest: servers without ids) or null (Cancel).
 * - interactive: false for the small preview, which never stops anyone: it just stays off.
 * Resolves to the start answer, or {ok: false, cancelled: true} / {ok: false, full: true}.
 */
export async function claimSlot({ status, start, ask, sid, interactive }) {
  let st = null;
  try { st = await status(); } catch { st = null; }
  for (let tries = 0; tries < 3; tries++) {
    let replace;
    if (st && st.active && !isMine(st, sid) && st.count >= st.max) {
      if (!interactive) return { ok: false, full: true };
      const pick = await ask(st.max, streamRows(st.active));
      if (pick == null) return { ok: false, cancelled: true };
      replace = pick || undefined;
    }
    const r = await start(replace);
    if (r && r.changed && r.streams) { st = r.streams; continue; }   // the chosen stream ended meanwhile: ask again
    return r || { ok: true };
  }
  return { ok: false, cancelled: true };
}

/** The dialog: resolves to the chosen stream's id, '' (oldest, servers without ids) or null (Cancel / Escape). */
export function askWhichStream(max, rows) {
  if (!document.getElementById('stream-ask-css')) {          // its own few rules, kept out of the shared stylesheet
    const css = document.createElement('style');
    css.id = 'stream-ask-css';
    css.textContent = '.stream-ask{max-width:640px}.stream-ask-row{display:flex;align-items:center;gap:12px;margin:10px 0}'
      + '.stream-ask-text{flex:1;min-width:0}.stream-ask-name{font-size:1.1em;overflow-wrap:anywhere}';
    document.head.append(css);
  }
  return new Promise((resolve) => {
    const el =(tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
    const box = el('div', 'modal stream-ask');
    box.setAttribute('role', 'dialog');
    box.setAttribute('aria-label', 'Every stream is in use');
    box.append(el('div', 'modal-title', max === 1 ? 'The stream is in use' : `All ${max} streams are in use`),
      el('p', 'hint', `Your provider allows ${max} at a time. Choose which one to stop:`));
    const back = el('div', 'modal-back');
    const done = (v) => { back.remove(); document.removeEventListener('keydown', onKey, true); resolve(v); };
    const buttons = [];
    for (const r of rows) {
      const line = el('div', 'stream-ask-row');
      const txt = el('div', 'stream-ask-text');
      txt.append(el('div', 'stream-ask-name', r.name), el('div', 'hint', (r.what || 'Watching') + (r.recording ? '  (recording, keeps going)' : '')));
      line.append(txt);
      if (r.stoppable) {
        const b = el('button', 'btn focusable', r.button);
        b.type = 'button';
        b.addEventListener('click', () => done(r.id));
        line.append(b); buttons.push(b);
      }
      box.append(line);
    }
    const row = el('div', 'pin-btns');
    if (!buttons.length && rows.some((r) => !r.recording)) {        // a server from before stream ids
      const b = el('button', 'btn focusable', 'Stop the oldest and watch here');
      b.type = 'button'; b.addEventListener('click', () => done('')); row.append(b); buttons.push(b);
    }
    const cancel = el('button', 'btn secondary focusable', 'Cancel');
    cancel.type = 'button'; cancel.addEventListener('click', () => done(null));
    row.append(cancel); buttons.push(cancel);
    box.append(row);
    back.append(box);
    // keys stay in the dialog: Up / Down move between the buttons, Escape / Back cancel
    function onKey(e) {
      if (!back.isConnected) return;
      if (e.key === 'Escape' || e.key === 'BrowserBack' || e.key === 'GoBack') { e.preventDefault(); e.stopPropagation(); done(null); return; }
      const i = buttons.indexOf(document.activeElement);
      const d = e.key === 'ArrowDown' || e.key === 'ArrowRight' ? 1 : e.key === 'ArrowUp' || e.key === 'ArrowLeft' ? -1 : 0;
      if (d) { e.preventDefault(); e.stopPropagation(); buttons[Math.max(0, Math.min(buttons.length - 1, (i < 0 ? 0 : i + d)))].focus(); return; }
      if (e.key !== 'Enter' && e.key !== ' ' && e.key !== 'Tab') e.stopPropagation();
    }
    document.addEventListener('keydown', onKey, true);
    document.body.append(back);
    setTimeout(() => buttons[0].focus(), 30);
  });
}
