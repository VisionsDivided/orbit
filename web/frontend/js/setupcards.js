// Copyright 2026 The Orbit authors. SPDX-License-Identifier: Apache-2.0
// Settings ▸ Requests: connect the household's Seerr (owner / admin), link Orbit accounts to Seerr users;
// members see the status. Settings ▸ Sources: link Plex (PIN at plex.tv/link) and Jellyfin (Quick Connect or
// username and password), list the linked servers with their state, Unlink. Each card keeps its own state and
// redraws itself, so Settings can put the same element back after it redraws.
// The Seerr address and API key are typed here and sent to the server only: the inputs are never filled in
// (not even with the saved address), the browser is asked not to remember them, and nothing is stored here.

import { h, toast } from './util.js';
import { apiReq } from './api.js';
import { orbitMode, orbitMe } from './orbit.js';
import { seerrBody, seerrState, mappingRows, suggestedCount, connView, signInSteps, secsLeft, mmss, pollDelay, withScheme, KIND_LABEL } from './setuplogic.js';

const post = (path, body) => apiReq(path, { method: 'POST', body: JSON.stringify(body || {}) });
/** Whether the signed-in account is the owner or an admin. orbitMe() can be empty for a moment (Settings opens right
 *  after signing in, and the Accounts card reloads it), so the answer is kept once known. */
let adminKnown = null;
async function ensureMe() {
  let me = orbitMe();
  if (!me && orbitMode()) { try { me = await apiReq('/v1/me'); } catch { me = null; } }
  if (me && me.account) adminKnown = me.account.role !== 'member';
}
const isAdmin = () => { const me = orbitMe(); return me && me.account ? me.account.role !== 'member' : !!adminKnown; };

/** A Settings row: label with a small description on the left, controls on the right. */
function srow(label, small, ...right) {
  return h('div', { class: 'srow5' }, h('div', { class: 'srow-l' }, label, small ? h('small', {}, small) : null), ...right);
}
const btn = (text, k, fn, kind = 'sec') => h('button', { class: 'btn5 ' + kind + ' focusable', type: 'button', 'data-k': k, onclick: fn }, text);
const dot = (tone, text) => h('span', { class: 'st5 ' + tone }, text);
/** A text field that the browser should neither fill in nor remember. */
function field(label, k, opts = {}) {
  const i = h('input', { class: 'in5 focusable', 'data-k': k, type: opts.type || 'text', placeholder: opts.placeholder || '', autocomplete: opts.type === 'password' ? 'new-password' : 'off',
    autocapitalize: 'off', spellcheck: 'false', 'data-lpignore': 'true', 'data-1p-ignore': 'true', maxlength: opts.max || '300', 'aria-label': label });
  return { i, el: h('label', { class: 'fld5' }, h('span', {}, label), i) };
}
/** Keeps focus on the same control (by data-k) across a redraw of the card. */
function redraw(card, kids) {
  const ae = document.activeElement, k = ae && card.contains(ae) && ae.dataset ? ae.dataset.k : null;
  card.replaceChildren(...kids.flat().filter(Boolean));
  if (k) { const f = card.querySelector(`[data-k="${k}"]`); if (f) f.focus(); }
}

// ---------------------------------------------------------------- Requests (Seerr)
export function requestsSetupCard() {
  const card = h('section', { class: 'card glass setup5', 'data-cat': 'requests' }, h('div', { class: 'card-h' }, h('h3', {}, 'Requests')), h('div', { class: 'hint' }, 'Loading…'));
  let st = null, editing = false, busy = false, result = null, users = null, usersErr = '';
  // the form's fields live across redraws (what was typed stays until it is saved)
  const addr = field('Seerr address', 'seerr-url', { placeholder: 'http://192.0.2.20:5055' });
  const key = field('API key', 'seerr-key', { type: 'password', max: '200' });

  async function refresh() {
    await ensureMe();
    if (!orbitMode()) { draw(); return; }
    try { st = await apiReq('/v1/discover/status'); } catch { st = null; }
    users = null; usersErr = '';
    draw();
    if (st && st.configured && isAdmin()) loadUsers();
  }
  async function loadUsers() {
    try { users = await apiReq('/v1/discover/users'); usersErr = ''; } catch (e) { users = null; usersErr = e.message; }
    draw();
  }

  async function send(test) {
    const r = seerrBody({ baseUrl: addr.i.value, apiKey: key.i.value }, { keySet: !!(st && st.keySet), test });
    if (r.error) { result = { tone: 'warn', text: r.error }; draw(); return; }
    busy = true; result = { tone: 'off', text: test ? 'Testing…' : 'Checking and saving…' }; draw();
    try {
      const j = await post('/v1/discover/settings', r.body);
      if (test) result = { tone: 'ok', text: `Seerr answered${j.version ? ' (version ' + j.version + ')' : ''} and accepted the key. Save to use it.` };
      else { result = null; editing = false; addr.i.value = ''; key.i.value = ''; toast('Seerr connected'); }
    } catch (e) { result = { tone: 'warn', text: (test ? 'Test failed: ' : 'Not saved: ') + e.message }; }
    busy = false;
    if (test) draw(); else refresh();
  }
  async function disconnect() {
    if (!confirm('Disconnect Seerr? Discover and requests stop working until it is connected again. The address and key are deleted from the server; account links are kept.')) return;
    try { await post('/v1/discover/settings', { remove: true }); toast('Seerr disconnected'); } catch (e) { toast(e.message); }
    editing = false; result = null; refresh();
  }
  async function map(accountId, value) {
    try {
      await post('/v1/discover/map', { accountId, seerrUserId: value ? +value : null });
      toast(value ? 'Linked' : 'Unlinked');
    } catch (e) { toast('Not changed: ' + e.message); }
    refresh();                                             // your own link shows in the status too
  }
  async function autoLink() {
    try { const r = await post('/v1/discover/map', { auto: true }); toast(`${(r.linked || []).length} account(s) linked`); } catch (e) { toast(e.message); }
    refresh();
  }

  function form() {
    key.i.placeholder = st && st.keySet ? 'Leave empty to keep the saved key (same server only)' : 'Seerr ▸ Settings ▸ General ▸ API Key';
    for (const f of [addr, key]) f.i.disabled = busy;
    return h('div', { class: 'form5' },
      h('div', { class: 'fld5-row' }, addr.el, key.el),
      h('div', { class: 'btns5' },
        h('button', { class: 'btn5 sec focusable', type: 'button', 'data-k': 'seerr-test', disabled: busy, onclick: () => send(true) }, 'Test connection'),
        h('button', { class: 'btn5 pri focusable', type: 'button', 'data-k': 'seerr-save', disabled: busy, onclick: () => send(false) }, 'Save'),
        st && st.configured ? btn('Cancel', 'seerr-cancel', () => { editing = false; result = null; addr.i.value = ''; key.i.value = ''; draw(); }) : null),
      result ? h('div', { class: 'res5 ' + result.tone, role: 'status' }, result.text) : null,
      h('div', { class: 'hint' }, 'The server tests the address and key, then keeps them encrypted. Browsers and members never see the key.'));
  }

  function mapping() {
    if (usersErr) return h('div', { class: 'res5 warn' }, 'Seerr users could not be loaded: ' + usersErr);
    if (!users) return h('div', { class: 'hint' }, 'Loading Seerr users…');
    const rows = mappingRows(users), n = suggestedCount(users);
    return h('div', { class: 'map5' },
      h('div', { class: 'sub5-h' }, 'Accounts and Seerr users'),
      h('div', { class: 'hint' }, 'Each person requests as their own Seerr user, so Seerr\'s permissions, approvals and quotas apply. Accounts without one can browse Discover but not request.'),
      ...rows.map((r) => {
        const sel = h('select', { class: 'focusable', 'data-k': 'map-' + r.id, 'aria-label': 'Seerr user for ' + r.name },
          ...r.options.map(([v, t]) => h('option', { value: v, selected: v === r.value }, t)));
        sel.onchange = () => map(r.id, sel.value);
        return srow(r.name, r.value ? null : (r.suggested ? 'Not linked · a match was found' : 'Not linked'), sel);
      }),
      n ? h('div', { class: 'btns5' }, btn(`Link ${n} suggested`, 'map-auto', autoLink)) : null);
  }

  function draw() {
    const kids = [h('div', { class: 'card-h' }, h('h3', {}, 'Requests'))];
    if (!orbitMode()) {
      kids.push(h('div', { class: 'hint' }, 'Discover and requests (Seerr) need Orbit accounts: set them up under Profiles ▸ Accounts.'));
      return redraw(card, kids);
    }
    const s = seerrState(st), admin = isAdmin();
    kids.push(srow('Seerr', admin && st && st.baseUrl ? 'Server: ' + st.baseUrl : 'Discover and requests',
      dot(s.tone, s.text),
      admin && st && st.configured && !editing ? btn('Change', 'seerr-change', () => { editing = true; result = null; draw(); addr.i.focus(); }) : null,
      admin && st && st.configured ? btn('Disconnect', 'seerr-rm', disconnect) : null));
    if (st && st.configured) kids.push(srow('Your Seerr user', null, dot(st.linked ? 'ok' : 'off', st.linked ? 'Linked: you can request' : (admin ? 'Not linked: pick yours below' : 'Not linked: ask the owner to link it'))));
    if (admin && st && (!st.configured || editing)) kids.push(form());
    if (admin && st && st.configured) kids.push(mapping());
    if (!admin) kids.push(h('div', { class: 'hint' }, 'The owner or an admin connects Seerr and links accounts to Seerr users.'));
    redraw(card, kids);
  }

  refresh();
  return { el: card, refresh };
}

// ---------------------------------------------------------------- Sources (Plex, Jellyfin)
export function sourcesSetupCard() {
  const card = h('section', { class: 'card glass setup5', 'data-cat': 'sources' }, h('div', { class: 'card-h' }, h('h3', {}, 'Media servers')), h('div', { class: 'hint' }, 'Loading…'));
  let conns = null, loadErr = '', pending = null, gen = 0, timer = null, tick = null, busy = false, pwOpen = false, msg = null, alive = true;
  const jfUrl = field('Jellyfin address', 'jf-url', { placeholder: 'http://192.0.2.30:8096' });
  const plexUrl = field('Plex address (optional)', 'plex-url', { placeholder: 'http://192.0.2.20:32400' });
  const jfUser = field('Jellyfin username', 'jf-user', { max: '100' });
  const jfPass = field('Password', 'jf-pass', { type: 'password', max: '200' });

  async function refresh() {
    await ensureMe();
    if (!orbitMode()) { draw(); return; }
    try { conns = (await apiReq('/v1/media/connections')).connections; loadErr = ''; } catch (e) { conns = conns || []; loadErr = e.message; }
    draw();
    if (conns.length) {                                    // then ask each server whether it answers
      try { const c = (await apiReq('/v1/media/connections?check=1')).connections; if (alive) { conns = c; draw(); } } catch {}
    }
  }

  function stopWaiting() { gen++; clearTimeout(timer); clearInterval(tick); pending = null; }
  function wait(start) {
    stopWaiting();
    const my = gen;
    pending = { ...start, ...signInSteps(start) };
    tick = setInterval(() => { if (!alive || my !== gen) return clearInterval(tick); const t = card.querySelector('.code5 .left5'); if (t) t.textContent = leftText(); if (!secsLeft(pending.expires)) { stopWaiting(); msg = { tone: 'warn', text: 'The code expired. Start again.' }; draw(); } }, 1000);
    const step = async () => {
      if (!alive || my !== gen) return;
      try {
        const r = await post('/v1/media/connect/poll', { id: start.id });
        if (my !== gen) return;
        if (r.status === 'linked') {
          const n = (r.connections || []).length;
          stopWaiting(); jfUrl.i.value = ''; msg ={ tone: 'ok', text: `${KIND_LABEL[start.kind]} linked${n > 1 ? ` (${n} servers)` : ''}.` };
          toast(KIND_LABEL[start.kind] + ' linked'); refresh(); return;
        }
      } catch (e) {
        if (my !== gen) return;
        stopWaiting(); msg = { tone: 'warn', text: e.status === 404 ? 'The sign-in expired. Start again.' : 'Not linked: ' + e.message }; draw(); return;
      }
      timer = setTimeout(step, pollDelay(start.interval));
    };
    timer = setTimeout(step, pollDelay(start.interval));
    msg = null; draw();
    const c = card.querySelector('[data-k="code-cancel"]'); if (c) c.focus();
  }
  const leftText = () => pending ? 'Code valid for ' + mmss(secsLeft(pending.expires)) : '';

  async function startPlex() {
    busy = true; msg = { tone: 'off', text: 'Asking Plex for a code…' }; draw();
    const baseUrl = withScheme(plexUrl.i.value);
    try { wait(await post('/v1/media/connect/start', baseUrl ? { kind: 'plex', baseUrl } : { kind: 'plex' })); } catch (e) { msg = { tone: 'warn', text: 'Plex: ' + e.message }; }
    busy = false; draw();
  }
  async function startJellyfin() {
    const baseUrl = withScheme(jfUrl.i.value);
    if (!baseUrl) { msg = { tone: 'warn', text: 'Enter the Jellyfin address, for example http://192.0.2.30:8096' }; draw(); jfUrl.i.focus(); return; }
    busy = true; msg = { tone: 'off', text: 'Asking Jellyfin for a Quick Connect code…' }; draw();
    try { wait(await post('/v1/media/connect/start', { kind: 'jellyfin', baseUrl })); }
    catch (e) {
      if (e.status === 409) { pwOpen = true; msg = { tone: 'warn', text: 'Quick Connect is off on this Jellyfin server. Sign in with a username and password instead.' }; }
      else msg = { tone: 'warn', text: 'Jellyfin: ' + e.message };
    }
    busy = false; draw();
  }
  async function passwordJellyfin() {
    const baseUrl = withScheme(jfUrl.i.value), username = jfUser.i.value.trim(), password = jfPass.i.value;
    if (!baseUrl || !username || !password) { msg = { tone: 'warn', text: 'Enter the Jellyfin address, username and password.' }; draw(); return; }
    busy = true; msg = { tone: 'off', text: 'Signing in to Jellyfin…' }; draw();
    try {
      await post('/v1/media/connect/password', { kind: 'jellyfin', baseUrl, username, password });
      jfPass.i.value = ''; jfUser.i.value = ''; jfUrl.i.value = ''; pwOpen = false;
      msg = { tone: 'ok', text: 'Jellyfin linked.' }; toast('Jellyfin linked'); refresh();
    } catch (e) { msg = { tone: 'warn', text: e.status === 403 ? 'Jellyfin refused the username or password.' : 'Jellyfin: ' + e.message }; }
    jfPass.i.value = '';                                   // never kept in the page after a try
    busy = false; draw();
  }
  async function unlink(c) {
    const v = connView(c);
    if (!confirm(`Unlink ${v.title}? Its films and shows leave your catalogue; the server's sign-in is removed from Orbit.`)) return;
    try { await post('/v1/media/connections/delete', { id: c.id }); toast('Unlinked'); } catch (e) { toast(e.message); }
    refresh();
  }

  function codePanel() {
    const p = pending;
    return h('div', { class: 'code5', role: 'status' },
      h('div', { class: 'code5-l' },
        h('div', { class: 'sub5-h' }, 'Link ' + KIND_LABEL[p.kind]),
        h('p', {}, p.steps),
        h('div', { class: 'code5-code', 'aria-label': 'Code ' + p.code.split('').join(' ') }, p.code),
        h('small', { class: 'left5' }, leftText()), h('small', {}, ' · waiting for you to enter it…')),
      h('div', { class: 'btns5' },
        p.link ? h('a', { class: 'btn5 pri focusable', 'data-k': 'code-open', href: p.link, target: '_blank', rel: 'noopener noreferrer' }, 'Open ' + p.link.replace(/^https?:\/\//, '')) : null,
        btn('Cancel', 'code-cancel', () => { stopWaiting(); msg = null; draw(); })));
  }

  function draw() {
    const kids = [h('div', { class: 'card-h' }, h('h3', {}, 'Media servers'))];
    if (!orbitMode()) {
      kids.push(h('div', { class: 'hint' }, 'Plex and Jellyfin need Orbit accounts: set them up under Profiles ▸ Accounts.'));
      return redraw(card, kids);
    }
    const admin = isAdmin();
    if (loadErr) kids.push(h('div', { class: 'res5 warn' }, 'Could not list the servers: ' + loadErr));
    if (conns === null) kids.push(h('div', { class: 'hint' }, 'Loading…'));
    else if (!conns.length) kids.push(h('div', { class: 'hint' }, 'No Plex or Jellyfin server is linked to your account yet.'));
    else kids.push(...conns.map((c) => { const v = connView(c); return srow(v.title, v.sub, dot(v.state.tone, v.state.text), btn('Unlink', 'unl-' + c.id, () => unlink(c))); }));
    if (admin) {
      if (pending) kids.push(codePanel());
      else {
        for (const f of [jfUrl, jfUser, jfPass]) f.i.disabled = busy;
        plexUrl.i.disabled = busy;
        kids.push(h('div', { class: 'srow5 jf5' },
          h('div', { class: 'srow-l' }, 'Plex', h('small', {}, 'Sign in with a code at plex.tv/link. Every Plex server of that user this server can reach is linked. If yours is not found, type its address here.')),
          h('div', { class: 'form5' },
            h('div', { class: 'fld5-row' }, plexUrl.el),
            h('div', { class: 'btns5' },
              h('button', { class: 'btn5 sec focusable', type: 'button', 'data-k': 'plex-link', disabled: busy, onclick: startPlex }, 'Link Plex')))));
        kids.push(h('div', { class: 'srow5 jf5' },
          h('div', { class: 'srow-l' }, 'Jellyfin', h('small', {}, 'Quick Connect: you get a code to enter in Jellyfin. Or sign in once with a password; it is not stored.')),
          h('div', { class: 'form5' },
            h('div', { class: 'fld5-row' }, jfUrl.el, pwOpen ? jfUser.el : null, pwOpen ? jfPass.el : null),
            h('div', { class: 'btns5' },
              pwOpen ? h('button', { class: 'btn5 pri focusable', type: 'button', 'data-k': 'jf-signin', disabled: busy, onclick: passwordJellyfin }, 'Sign in')
                : h('button', { class: 'btn5 sec focusable', type: 'button', 'data-k': 'jf-qc', disabled: busy, onclick: startJellyfin }, 'Quick Connect'),
              btn(pwOpen ? 'Use Quick Connect' : 'Use a password', 'jf-mode', () => { pwOpen = !pwOpen; msg = null; jfPass.i.value = ''; draw(); })))));
      }
    }
    if (msg) kids.push(h('div', { class: 'res5 ' + msg.tone, role: 'status' }, msg.text));
    kids.push(h('div', { class: 'hint' }, admin
      ? 'Each account links its own Plex or Jellyfin user, so what a person may see and their watch state stay theirs. Sign-in tokens stay on this server, encrypted.'
      : 'Your linked servers. The owner or an admin links new Plex and Jellyfin servers.'));
    redraw(card, kids);
  }

  refresh();
  return { el: card, refresh, destroy() { alive = false; stopWaiting(); } };
}
