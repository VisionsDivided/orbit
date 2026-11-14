// Copyright 2026 The Orbit authors. SPDX-License-Identifier: Apache-2.0
// Orbit accounts and profiles in the web app (server: docs/orbit/API-v1.md).
// - tvBro mode (no accounts): nothing changes; Settings offers "Set up accounts" to the admin.
// - Orbit mode: sign in with a username and password, "Who's watching?" when an account has more
//   than one profile, a profile chip in the side menu, and Settings ▸ Accounts (profiles, this
//   account's devices, and for the owner / admins the household's accounts).
// Everything works with a remote: arrows move between fields and tiles, OK selects, Back / Escape closes.
// Names are always set as text (never as HTML).
import { apiReq, apiAuthPost, deviceLabel, endStream } from './api.js';
import { h, toast } from './util.js';
import { forgetAccountHere } from './signout.js';

/** Sign out on this browser: the stream slot is given back, the session ends, and everything the browser
 *  cached for this account and profile is removed (signout.js), so the next person starts clean. */
export async function signOutHere() {
  endStream();
  try { await apiReq('/v1/auth/logout', { method: 'POST', body: '{}' }); } catch {}
  await forgetAccountHere();
  location.reload();
}

let status = null;
let me = null;

/** Load the server's mode and, in Orbit mode, who this device is signed in as. */
export async function orbitLoad() {
  try { status = await (await fetch('/v1/status', { credentials: 'same-origin' })).json(); } catch { status = { accounts: false }; }
  me = null;
  if (status && status.accounts) { try { me = await apiReq('/v1/me'); } catch { me = null; } }
  return { status, me };
}
export const orbitMode = () => !!(status && status.accounts);
export const orbitMe = () => me;

const ROLE = { owner: 'Owner', admin: 'Admin', member: 'Member' };
const initial = (s) => (String(s || '?').trim()[0] || '?').toUpperCase();

/** Arrow keys move between the focusable things inside el (no trap: Escape / Back still close). */
function arrowFocus(el, e, grid) {
  const items = [...el.querySelectorAll('input, select, button, [tabindex="0"]')].filter((x) => !x.disabled && x.offsetParent !== null);
  const i = items.indexOf(document.activeElement);
  if (i < 0) return false;
  const ae = document.activeElement;
  const text = ae.tagName === 'INPUT' && ae.type !== 'checkbox';
  let d = 0;
  if (e.key === 'ArrowDown') d = grid ? grid : 1;
  else if (e.key === 'ArrowUp') d = grid ? -grid : -1;
  else if (!text && e.key === 'ArrowRight') d = 1;
  else if (!text && e.key === 'ArrowLeft') d = -1;
  if (!d) return false;
  const j = Math.max(0, Math.min(items.length - 1, i + d));
  if (j !== i) items[j].focus();
  return true;
}

/** A dialog with fields: [{name, label, type: 'text'|'password'|'select'|'checkbox', options?, value?}].
 *  Resolves to {name: value} or null (Cancel / Escape). check(values) may return an error text. */
export function formDialog(title, fields, note, okText, check) {
  return new Promise((resolve) => {
    const inputs = {};
    const rows = fields.map((f) => {
      let el;
      if (f.type === 'select') {
        el = h('select', { class: 'focusable signin-input', 'aria-label': f.label }, ...f.options.map(([v, t]) => h('option', { value: v }, t)));
        if (f.value) el.value = f.value;
      } else if (f.type === 'checkbox') {
        el = h('input', { type: 'checkbox', class: 'focusable', 'aria-label': f.label });
        el.checked = !!f.value;
        inputs[f.name] = el;
        return h('label', { class: 'orbit-check' }, el, f.label);
      } else {
        el = h('input', { type: f.type || 'text', class: 'focusable signin-input', 'aria-label': f.label, maxlength: f.max || '200',
          autocomplete: f.autocomplete || 'off', autocapitalize: 'off', spellcheck: 'false' });
        if (f.value) el.value = f.value;
      }
      inputs[f.name] = el;
      return [h('label', {}, f.label), el];
    });
    const msg = h('div', { class: 'hint signin-msg', role: 'status' });
    const ok = h('button', { class: 'btn focusable', type: 'submit' }, okText || 'OK');
    const cancel = h('button', { class: 'btn secondary focusable', type: 'button' }, 'Cancel');
    const form = h('form', { class: 'modal signin-card orbit-form', role: 'dialog', 'aria-label': title },
      h('div', { class: 'modal-title' }, title), note ? h('p', { class: 'hint' }, note) : null, ...rows.flat(), msg,
      h('div', { class: 'pin-btns' }, ok, cancel));
    const back = h('div', { class: 'modal-back' }, form);
    const done = (v) => { back.remove(); resolve(v); };
    const values = () => Object.fromEntries(Object.entries(inputs).map(([k, el]) => [k, el.type === 'checkbox' ? el.checked : el.value]));
    form.addEventListener('submit', (e) => {
      e.preventDefault();
      const v = values();
      const err = check ? check(v) : null;
      if (err) { msg.textContent = err; return; }
      done(v);
    });
    cancel.addEventListener('click', () => done(null));
    form.addEventListener('keydown', (e) => {
      e.stopPropagation();
      if (e.key === 'Escape') { e.preventDefault(); done(null); return; }
      if (arrowFocus(form, e)) e.preventDefault();
    });
    document.body.append(back);
    setTimeout(() => { const first = form.querySelector('input, select'); if (first) first.focus(); }, 30);
  });
}

// ---------- sign in (Orbit mode) ----------
/** Covers the app until an account signs in, then "Who's watching?" (several profiles) or reload. */
export function showAccountSignIn() {
  const user = h('input', { type: 'text', class: 'focusable signin-input', autocomplete: 'username', 'aria-label': 'Username',
    maxlength: '32', autocapitalize: 'off', spellcheck: 'false' });
  const pw = h('input', { type: 'password', class: 'focusable signin-input', autocomplete: 'current-password', 'aria-label': 'Password', maxlength: '200' });
  const msg = h('div', { class: 'hint signin-msg', role: 'status' });
  const btn = h('button', { class: 'btn focusable', type: 'submit' }, 'Sign in');
  const form = h('form', { class: 'signin-card glass', autocomplete: 'on' },
    h('img', { src: 'brand/orbit-signin.webp', alt: 'Orbit, your media, in orbit', class: 'signin-logo' }),
    h('h1', {}, 'Sign in'),
    h('p', { class: 'hint' }, 'Sign in with your Orbit account. Ask the owner of this server if you do not have one yet.'),
    h('label', {}, 'Username'), user, h('label', {}, 'Password'), pw, msg, btn);
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    msg.textContent = '';
    btn.disabled = true;
    try {
      const j = await apiAuthPost('/v1/auth/login', { username: user.value.trim(), password: pw.value, device: deviceLabel() });
      back.remove();
      if (j.profiles && j.profiles.length > 1) showProfilePicker(j, { force: true });
      else location.reload();
    } catch (err) {
      msg.textContent = err.status === 429 ? `Too many wrong passwords. Try again in ${err.retryAfter || 30} s.`
        : err.status === 403 ? 'Wrong username or password.' : err.message;
      btn.disabled = false;
      pw.select();
    }
  });
  form.addEventListener('keydown', (e) => { e.stopPropagation(); if (e.key === 'ArrowDown' || e.key === 'ArrowUp') { if (arrowFocus(form, e)) e.preventDefault(); } });
  const back = h('div', { class: 'signin-back' }, form);
  document.body.append(back);
  setTimeout(() => user.focus(), 30);
}

// ---------- who's watching ----------
/** Profile tiles; OK picks one (the page reloads with that profile's lists). force: no Back. */
export function showProfilePicker(m, opts = {}) {
  const info = m || me;
  if (!info) return;
  const cur = info.profile ? info.profile.id : null;
  const tiles = info.profiles.map((p) => h('button', { class: 'orbit-tile focusable' + (p.id === cur ? ' current' : ''), type: 'button',
    'aria-label': p.name + (p.kids ? ' (kids)' : '') + (p.id === cur ? ' (current)' : ''),
    onclick: async () => {
      try { await apiReq('/v1/profiles/select', { method: 'POST', body: JSON.stringify({ id: p.id }) }); location.reload(); }
      catch (e) { toast(e.message); }
    } },
    h('span', { class: 'orbit-avatar' + (p.kids ? ' kids' : '') }, initial(p.name)), h('span', { class: 'orbit-name' }, p.name),
    p.kids ? h('span', { class: 'orbit-badge' }, 'Kids') : null));
  const grid = h('div', { class: 'orbit-tiles' }, ...tiles);
  const box = h('div', { class: 'orbit-picker', role: 'dialog', 'aria-label': "Who's watching?" },
    h('h1', {}, "Who's watching?"), grid,
    h('p', { class: 'hint' }, `Signed in as ${info.account.displayName}. Profiles have their own My list, favourite channels and resume points.`),
    opts.force ? null : h('button', { class: 'btn secondary focusable', type: 'button', onclick: () => close() }, 'Back'));
  const back = h('div', { class: 'modal-back orbit-picker-back' }, box);
  const close = () => back.remove();
  box.addEventListener('keydown', (e) => {
    e.stopPropagation();
    if ((e.key === 'Escape' || e.key === 'Backspace' || e.key === 'BrowserBack') && !opts.force) { e.preventDefault(); close(); return; }
    if (arrowFocus(box, e)) e.preventDefault();
  });
  document.body.append(back);
  setTimeout(() => (grid.querySelector('.current') || tiles[0]).focus(), 30);
}

// ---------- side menu chip ----------
/** A side-menu item showing the current profile; OK opens "Who's watching?". */
export function addNavChip() {
  if (!me || !me.profile || document.querySelector('.nav-profile')) return;
  const settings = document.querySelector('.nav-item[data-screen="settings"]');
  if (!settings) return;
  const chip = h('button', { class: 'nav-item nav-profile', 'data-screen': 'profile', tabindex: '0', title: 'Switch profile',
    onclick: () => showProfilePicker(me) },
    h('span', { class: 'ic orbit-avatar small' + (me.profile.kids ? ' kids' : '') }, initial(me.profile.name)), h('span', {}, me.profile.name));
  settings.before(chip);
}

// ---------- link a TV ----------
/** Approve the code a TV shows (code: pre-filled from the TV's QR link, #pair:CODE). */
export async function approveTvDialog(code) {
  if (!orbitMode()) await orbitLoad();
  if (!orbitMode() || !me) { toast('Sign in with an account first'); return; }
  const v = await formDialog('Link a TV', [
    { name: 'code', label: 'Code shown on the TV', value: code || '', max: '12' },
    { name: 'profile', label: 'Profile on that TV', type: 'select', value: me.profile ? me.profile.id : '',
      options: me.profiles.map((p) => [p.id, p.name + (p.kids ? ' (kids)' : '')]) },
  ], `The TV signs in to your account (${me.account.displayName}). You can sign it out any time under Your devices.`, 'Link',
  (x) => (x.code.replace(/[^0-9a-z]/gi, '').length === 8 ? null : 'The code has 8 letters and digits'));
  if (!v) return false;
  try {
    // first see which device is waiting behind the code (a link from someone else could be any device)
    const w = await post('/v1/pair/peek', { code: v.code });
    if (!confirm(`Link "${w.device}" (from ${w.address}, waiting ${w.waitingSecs} s) to your account? Only approve a TV you can see showing this code.`)) return false;
    const r = await post('/v1/pair/approve', { code: v.code, profileId: v.profile });
    toast(`${r.device} is linked`);
    return true;
  } catch (e) { toast(e.status === 429 ? 'Too many wrong codes; wait a little' : e.message); return false; }
}

// ---------- Settings ▸ Accounts ----------
const fmtSeen = (ts) => ts ? new Date(ts * 1000).toLocaleString([], { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : '—';
const post = (path, body) => apiReq(path, { method: 'POST', body: JSON.stringify(body || {}) });
const USERNAME_HELP = '3 to 32 letters, digits, ".", "_" or "-"';

/** The Accounts card for Settings. It keeps its own state and redraws itself; the same element can
 *  be put back into the page after Settings redraws. isAdmin(): this device has the admin role (setup). */
export function orbitSettingsCard(isAdmin) {
  const card = h('section', { class: 'card glass orbit-card' });
  let sessions = [], accounts = [], tvs = [];
  const row = (label, ...val) => h('div', { class: 'set-row' }, h('div', { class: 'set-k' }, label), h('div', { class: 'set-v' }, ...val));
  const btn = (text, k, fn) => h('button', { class: 'btn secondary focusable', 'data-k': k, onclick: fn }, text);

  async function refresh() {
    await orbitLoad();
    if (orbitMode() && me) {
      try { sessions = (await apiReq('/v1/sessions')).sessions; } catch { sessions = []; }
      accounts = [];
      if (me.account.role !== 'member') { try { accounts = (await apiReq('/v1/accounts')).accounts; } catch { accounts = []; } }
      tvs = [];
      if (me.account.role !== 'member') { try { tvs = (await apiReq('/v1/devices')).devices; } catch { tvs = []; } }
    }
    draw();
  }

  function draw() {
    const ae = document.activeElement, focusKey = ae && card.contains(ae) && ae.dataset ? ae.dataset.k : null;
    const kids = [h('div', { class: 'card-h' }, h('h3', {}, 'Accounts'))];
    if (!orbitMode()) {
      kids.push(row('Accounts', 'Off: everyone shares one My list and resume points'));
      if (isAdmin() && status && status.setup) kids.push(row('', btn('Set up accounts…', 'orbit-setup', setup)));
      kids.push(h('div', { class: 'hint' }, 'With accounts, every person signs in with their own username and password, and each account has up to 8 profiles with their own My list, favourite channels, resume points and show progress. Setting up needs the one-time setup code the server keeps in its data folder (file orbit-setup-token).'));
    } else if (me) {
      const a = me.account;
      kids.push(row('Signed in as', `${a.displayName} (${a.username}, ${ROLE[a.role] || a.role})`,
        btn('Sign out on this device', 'orbit-logout', signOutHere)));
      kids.push(row('Profile', me.profile ? me.profile.name + (me.profile.kids ? ' (kids)' : '') : '—',
        btn('Switch profile…', 'orbit-switch', () => showProfilePicker(me))));
      kids.push(row('Profiles', h('div', { class: 'orbit-list' }, ...me.profiles.map((p) => h('div', { class: 'orbit-li' },
        h('span', {}, p.name + (p.kids ? ' (kids)' : '')),
        me.profiles.length > 1 ? btn('Delete', 'orbit-pdel-' + p.id, async () => {
          if (!confirm(`Delete the profile "${p.name}"? Its My list, favourites, resume points and show progress are deleted too.`)) return;
          try { await post('/v1/profiles/delete', { id: p.id }); toast('Profile deleted'); if (me.profile && p.id === me.profile.id) location.reload(); else refresh(); } catch (e) { toast(e.message); }
        }) : null)),
        me.profiles.length < 8 ? btn('Add profile…', 'orbit-padd', addProfile) : null)));
      kids.push(row('Your devices', h('div', { class: 'orbit-list' }, ...sessions.map((s) => h('div', { class: 'orbit-li' },
        h('span', {}, (s.name ? `${s.name} (${s.device || 'device'})` : s.device || 'Unnamed device') + (s.current ? ' (this device)' : '')), h('span', { class: 'hint' }, 'last used ' + fmtSeen(s.lastSeen)),
        s.current ? null : btn('Sign out', 'orbit-rev-' + s.id, async () => {
          try { await post('/v1/sessions/revoke', { id: s.id }); toast('That device is signed out'); refresh(); } catch (e) { toast(e.message); }
        }))),
        btn('Link a TV…', 'orbit-pair', async () => { if (await approveTvDialog('')) refresh(); }))));
      if (a.role !== 'member') kids.push(row('Linked TVs', linkedTvs()));
      if (a.role !== 'member') {
        kids.push(row('Household accounts', h('div', { class: 'orbit-list' }, ...accounts.map((x) => h('div', { class: 'orbit-li' },
          h('span', {}, `${x.displayName} (${x.username})`), h('span', { class: 'hint' }, (ROLE[x.role] || x.role) + (x.disabled ? ', disabled' : '')),
          x.role === 'owner' || x.id === a.id || (x.role === 'admin' && a.role !== 'owner') ? null
            : btn(x.disabled ? 'Enable' : 'Disable', 'orbit-dis-' + x.id, async () => {
              if (!x.disabled && !confirm(`Disable ${x.displayName}? Every device signed in to that account is signed out.`)) return;
              try { await post('/v1/accounts/disable', { id: x.id, disabled: !x.disabled }); refresh(); } catch (e) { toast(e.message); }
            }))),
          btn('Add account…', 'orbit-aadd', addAccount))));
      }
      kids.push(h('div', { class: 'hint' }, 'Each profile has its own My list, favourite channels, resume points, reminders and show progress. Playlists, the channel layout, the guide, recordings and the parental PIN are shared by the household.'));
    }
    card.replaceChildren(...kids);
    if (focusKey) { const f = card.querySelector(`[data-k="${focusKey}"]`); if (f) f.focus(); }
  }

  /** Owner / admin: every linked TV of the household, with Rename and Unlink (the server says which may be
   *  unlinked: the owner any, admins their own and members'). The name is what the stream prompt shows. */
  function linkedTvs() {
    if (!tvs.length) return h('span', { class: 'hint' }, 'No TV is linked yet. Link one with "Link a TV…" above.');
    return h('div', { class: 'orbit-list' }, ...tvs.map((d) => h('div', { class: 'orbit-li' },
      h('span', {}, d.name || d.device || 'TV', d.current ? ' (this device)' : ''),
      h('span', { class: 'hint' }, [d.name ? d.device : 'not named', `${d.account.displayName}${d.profile ? ', profile ' + d.profile : ''}`,
        'last used ' + fmtSeen(d.lastSeen)].filter(Boolean).join(' · ')),
      btn('Rename', 'orbit-tvren-' + d.id, () => renameTv(d)),
      d.canUnlink ? btn('Unlink', 'orbit-tvunl-' + d.id, async () => {
        if (!confirm(`Unlink "${d.name || d.device}"? It is signed out and removes ${d.account.displayName}'s playlists, My list and watch progress from that TV.`)) return;
        try { await post('/v1/devices/unlink', { id: d.id }); toast('That TV is unlinked'); refresh(); } catch (e) { toast(e.message); }
      }) : null)));
  }

  async function renameTv(d) {
    const v = await formDialog('Name this TV', [{ name: 'name', label: 'Name (1 to 32 letters, digits or spaces)', value: d.name || '', max: '32' }],
      'Other devices see this name when every stream is in use ("Stop Kitchen and watch").', 'Save',
      (x) => (x.name.trim() ? null : 'Give it a name, like Kitchen or Bedroom'));
    if (!v) return;
    try { await post('/v1/devices/rename', { id: d.id, name: v.name.trim() }); toast('Renamed'); refresh(); } catch (e) { toast(e.message); }
  }

  async function setup() {
    const v = await formDialog('Set up accounts', [
      { name: 'token', label: 'Setup code (from the file orbit-setup-token in the server\'s data folder)' },
      { name: 'username', label: 'Your username (' + USERNAME_HELP + ')', autocomplete: 'username', max: '32' },
      { name: 'displayName', label: 'Your name (shown on screen)', max: '60' },
      { name: 'password', label: 'Password (8 characters or more)', type: 'password', autocomplete: 'new-password' },
      { name: 'again', label: 'Password again', type: 'password', autocomplete: 'new-password' },
    ], 'You become the owner. After this, every device signs in with an account, and devices that are open now are signed out. Your current My list, favourites and resume points move to your first profile.',
    'Set up', (x) => (x.password !== x.again ? 'The passwords differ' : null));
    if (!v) return;
    try {
      await apiAuthPost('/v1/setup', { token: v.token.trim(), username: v.username.trim(), displayName: v.displayName.trim() || undefined,
        password: v.password, device: deviceLabel() });
      location.reload();
    } catch (e) { toast('Not set up: ' + (e.status === 403 ? 'wrong setup code' : e.message)); }
  }

  async function addProfile() {
    const v = await formDialog('Add a profile', [{ name: 'name', label: 'Name', max: '40' }, { name: 'kids', label: 'Kids profile', type: 'checkbox' }],
      null, 'Add', (x) => (x.name.trim() ? null : 'Give it a name'));
    if (!v) return;
    try { await post('/v1/profiles', { name: v.name.trim(), kids: v.kids }); toast('Profile added'); refresh(); } catch (e) { toast(e.message); }
  }

  async function addAccount() {
    const fields = [
      { name: 'username', label: 'Username (' + USERNAME_HELP + ')', max: '32' },
      { name: 'displayName', label: 'Name (shown on screen)', max: '60' },
      { name: 'password', label: 'Password (8 characters or more)', type: 'password', autocomplete: 'new-password' },
      { name: 'again', label: 'Password again', type: 'password', autocomplete: 'new-password' },
    ];
    if (me.account.role === 'owner') fields.push({ name: 'role', label: 'Role', type: 'select', options: [['member', 'Member (watches; cannot change playlists or settings)'], ['admin', 'Admin (can also manage playlists, settings and members)']] });
    const v = await formDialog('Add an account', fields, 'Tell the person their username and password; they can sign in on any device.', 'Add',
      (x) => (x.password !== x.again ? 'The passwords differ' : null));
    if (!v) return;
    try {
      await post('/v1/accounts', { username: v.username.trim(), displayName: v.displayName.trim() || undefined, password: v.password, role: v.role || 'member' });
      toast('Account added'); refresh();
    } catch (e) { toast('Not added: ' + e.message); }
  }

  draw();
  refresh();
  return card;
}
