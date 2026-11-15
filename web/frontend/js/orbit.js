// Copyright 2026 The Orbit authors. SPDX-License-Identifier: Apache-2.0
// Orbit accounts and profiles in the web app (server: docs/orbit/API-v1.md).
// - tvBro mode (no accounts): nothing changes; Settings offers "Set up accounts" to the admin.
// - Orbit mode: sign in with a username and password, "Who's watching?" when an account has more
//   than one profile, the profile's avatar in the top bar, and Settings ▸ Accounts (profiles, this
//   account's devices, and for the owner / admins the household's accounts).
// Everything works with a remote: arrows move between fields and tiles, OK selects, Back / Escape closes.
// Names are always set as text (never as HTML).
import { api, apiReq, apiAuthPost, deviceLabel, endStream } from './api.js';
import { h, toast } from './util.js';
import { prefs } from './prefs.js';
import { ic as icon } from './ui5.js';
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
// Two styles (Settings ▸ Profiles ▸ Profile picker style, saved in the profile's prefs as
// pickerStyle): 'orbit' (default; DESIGN-SPEC-5 §8: the profiles on an elliptical orbit around the
// planet, the focused one at the bottom, Left / Right turn the orbit) and 'classic' (one centred row).
// One "Manage profiles" button; no colour-key legend.
const GRADS = [['#ff9a76', '#c8507f'], ['#8de0b0', '#2a8f84'], ['#8892b0', '#39425f'], ['#ffd166', '#e0803a'], ['#7fb8ff', '#4057c8'], ['#f39ad6', '#8a5cff'], ['#9fe6ff', '#2a8fb0'], ['#c9a6ff', '#6b45e6']];
const gradOf = (p, cur) => { if (cur) return 'linear-gradient(135deg,#2fd3ff,#8a5cff)'; let n = 0; for (const ch of String(p.id || p.name)) n = (n * 31 + ch.charCodeAt(0)) >>> 0; const g = GRADS[n % GRADS.length]; return `linear-gradient(135deg,${g[0]},${g[1]})`; };
const reducedMotion = () => { try { return matchMedia('(prefers-reduced-motion: reduce)').matches; } catch { return false; } };

/** Profile picker; OK picks one (the page reloads with that profile's lists). force: no Back. */
export function showProfilePicker(m, opts = {}) {
  const info = m || me;
  if (!info) return;
  document.querySelectorAll('.op-back').forEach((x) => x.remove());
  const style = opts.style || (prefs().pickerStyle === 'classic' ? 'classic' : 'orbit');
  const cur = info.profile ? info.profile.id : null;
  const pick = async (p) => {
    try { await apiReq('/v1/profiles/select', { method: 'POST', body: JSON.stringify({ id: p.id }) }); location.reload(); }
    catch (e) { toast(e.message); }
  };
  const entries = info.profiles.map((p) => ({ p }));
  if (info.profiles.length < 8) entries.push({ add: true });
  const tiles = entries.map((en) => {
    const p = en.p;
    const t = en.add
      ? h('button', { class: 'op-tile add focusable', type: 'button', 'aria-label': 'Add profile' }, h('span', { class: 'op-av' }, '+'), h('span', { class: 'op-nm' }, 'Add profile'))
      : h('button', { class: 'op-tile focusable' + (p.id === cur ? ' current' : ''), type: 'button', 'aria-label': p.name + (p.kids ? ' (kids)' : '') + (p.id === cur ? ' (current)' : '') },
        h('span', { class: 'op-av', style: { background: gradOf(p, false) } }, initial(p.name)),
        h('span', { class: 'op-nm' }, p.name, p.kids ? h('span', { class: 'bd' }, 'Kids') : null));
    t._en = en;
    return t;
  });
  const addProfile = async () => {
    const v = await formDialog('Add a profile', [{ name: 'name', label: 'Name', max: '40' }, { name: 'kids', label: 'Kids profile', type: 'checkbox' }],
      null, 'Add', (x) => (x.name.trim() ? null : 'Give it a name'));
    if (!v) { focusAt(fi); return; }
    try { await apiReq('/v1/profiles', { method: 'POST', body: JSON.stringify({ name: v.name.trim(), kids: v.kids }) }); toast('Profile added'); await orbitLoad(); close(); showProfilePicker(me, opts); }
    catch (e) { toast(e.message); }
  };
  const manage = h('button', { class: 'btn5 sec focusable op-manage', type: 'button', onclick: async () => {
    if (opts.force) { const p = info.profiles.find((x) => x.id === cur) || info.profiles[0]; location.hash = 'settings:profiles'; if (p) await pick(p); return; }
    close(); location.hash = 'settings:profiles';
  } });
  manage.append(icon('gear'), h('span', {}, 'Manage profiles'));
  const wall = h('div', { class: 'op-wall', 'aria-hidden': 'true' });
  const stage = h('div', { class: 'op-stage ' + style });
  const box = h('div', { class: 'op-box', role: 'dialog', 'aria-modal': 'true', 'aria-label': "Who's watching?" },
    h('div', { class: 'op-head' }, h('h1', {}, "Who's watching?"), h('p', {}, `Profile picker style: ${style === 'orbit' ? 'Orbit' : 'Classic'} · change it in Settings › Profiles`)),
    stage, h('div', { class: 'op-foot' }, manage));
  const back = h('div', { class: 'op-back' }, wall, h('div', { class: 'op-mesh' }), h('div', { class: 'op-dark' }), box);
  if (style === 'orbit') stage.append(h('div', { class: 'op-ring' }), h('img', { class: 'op-planet', src: 'brand/orbit-planet.webp', alt: '' }));
  stage.append(...tiles);
  const close = () => { cancelAnimationFrame(raf); window.removeEventListener('resize', layout); back.remove(); };
  let fi = Math.max(0, tiles.findIndex((t) => t.classList.contains('current')));
  let rot = fi, raf = 0;
  const n = tiles.length;
  function layout() {
    if (style !== 'orbit') return;
    const W = stage.clientWidth, H = stage.clientHeight; if (!W || !H) return;
    const k = Math.min(W / 1920, H / 760);
    const cx = W / 2, cy = H * 0.42, rx = Math.min(W * 0.36, 660 * k * 1.1), ry = Math.max(120, 250 * k * 1.15);
    const ring = stage.querySelector('.op-ring');
    Object.assign(ring.style, { left: cx - rx + 'px', top: cy - ry + 'px', width: rx * 2 + 'px', height: ry * 2 + 'px' });
    const pl = stage.querySelector('.op-planet'); const pw = Math.min(460 * k * 1.1, rx * 0.75);
    Object.assign(pl.style, { width: pw + 'px', left: cx - pw / 2 + 'px', top: cy - pw * 0.34 + 'px' });
    const step = Math.min(39, 360 / Math.max(n, 1)) * Math.PI / 180;
    tiles.forEach((t, i) => {
      let d = i - rot; if (d > n / 2) d -= n; if (d < -n / 2) d += n;
      const a = Math.PI / 2 + d * step;
      const front = Math.sin(a);                  // 1 at the bottom (focused), < 0 behind the planet
      const near = Math.max(0, 1 - Math.abs(d));  // 1 for the focused one
      const size = (150 + 50 * near) * Math.max(k * 1.1, 0.5);
      t.style.setProperty('--sz', size + 'px');
      t.style.left = cx + rx * Math.cos(a) - size / 2 + 'px';
      t.style.top = cy + ry * Math.sin(a) - size / 2 + 'px';
      t.style.opacity = String(front < -0.2 ? 0.35 : 1);
      t.style.zIndex = String(10 + Math.round(front * 10));
      t.classList.toggle('front', i === fi);
    });
  }
  function turnTo(i) {
    fi = (i + n) % n;
    const from = rot; let to = fi; let d = to - from; if (d > n / 2) to -= n; if (d < -n / 2) to += n;
    cancelAnimationFrame(raf);
    if (reducedMotion() || style !== 'orbit') { rot = fi; layout(); return; }
    const t0 = performance.now(), dur = 400;
    const tick = (now) => { const p = Math.min(1, (now - t0) / dur); const e = p < 0.5 ? 2 * p * p : 1 - Math.pow(-2 * p + 2, 2) / 2; rot = from + (to - from) * e; if (p >= 1) rot = fi; layout(); if (p < 1) raf = requestAnimationFrame(tick); };
    raf = requestAnimationFrame(tick);
  }
  function focusAt(i) { const t = tiles[(i + n) % n]; if (t) t.focus({ preventScroll: true }); }
  tiles.forEach((t, i) => {
    t.addEventListener('focus', () => { if (fi !== i || style !== 'orbit') turnTo(i); else layout(); });
    t.addEventListener('click', () => { if (t._en.add) addProfile(); else pick(t._en.p); });
  });
  box.addEventListener('keydown', (e) => {
    e.stopPropagation();
    if ((e.key === 'Escape' || e.key === 'Backspace' || e.key === 'BrowserBack') && !opts.force) { e.preventDefault(); close(); return; }
    const onTile = tiles.includes(document.activeElement);
    if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') { e.preventDefault(); if (onTile) focusAt(fi + (e.key === 'ArrowRight' ? 1 : -1)); return; }
    if (e.key === 'ArrowDown') { e.preventDefault(); manage.focus(); return; }
    if (e.key === 'ArrowUp') { e.preventDefault(); focusAt(fi); return; }
    if (e.key === 'Tab') {   // keep the focus in the dialog
      const f = [...tiles, manage]; const i = f.indexOf(document.activeElement);
      e.preventDefault(); f[(i + (e.shiftKey ? -1 : 1) + f.length) % f.length].focus();
    }
  });
  document.body.append(back);
  window.addEventListener('resize', layout);
  layout();
  setTimeout(() => focusAt(fi), 30);
  // a faint wall of posters behind (best effort; nothing when the catalogue is empty)
  api.vodList('movie', '__all', 1).then((r) => {
    const ps = (r.items || []).map((x) => api.img(x.poster)).filter(Boolean).slice(0, 30);
    wall.replaceChildren(...ps.map((src) => h('img', { src, alt: '', loading: 'lazy' })));
  }).catch(() => {});
}

// ---------- top bar avatar ----------
/** The top bar's avatar shows who is watching (the current profile's initial). */
export function addNavChip() {
  const av = document.querySelector('.tb-avatar');
  if (!av) return;
  if (me && me.profile) {
    av.textContent = initial(me.profile.name);
    av.setAttribute('aria-label', `${me.profile.name}: profile and settings`);
    av.title = me.profile.name;
  } else { av.textContent = ''; av.append(icon('user')); }
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
      if (a.role !== 'member') kids.push(row('TVs and computers', linkedTvs()));
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

  /** Owner / admin: every linked TV and desktop app of the household, with Rename and Unlink (the server says which
   *  may be unlinked: the owner any, admins their own and members'). The name is what the stream prompt shows. */
  function linkedTvs() {
    if (!tvs.length) return h('span', { class: 'hint' }, 'No TV or computer is linked yet. Link a TV with "Link a TV…" above; the desktop app signs in with an account.');
    return h('div', { class: 'orbit-list' }, ...tvs.map((d) => h('div', { class: 'orbit-li' },
      h('span', {}, d.name || d.device || (d.kind === 'desktop' ? 'Computer' : 'TV'), d.current ? ' (this device)' : ''),
      h('span', { class: 'hint' }, [d.kind === 'desktop' ? 'computer' : 'TV', d.name ? d.device : 'not named',
        `${d.account.displayName}${d.profile ? ', profile ' + d.profile : ''}`,
        'last used ' + fmtSeen(d.lastSeen)].filter(Boolean).join(' · ')),
      btn('Rename', 'orbit-tvren-' + d.id, () => renameTv(d)),
      d.canUnlink ? btn('Unlink', 'orbit-tvunl-' + d.id, async () => {
        if (!confirm(`Unlink "${d.name || d.device}"? It is signed out and removes ${d.account.displayName}'s playlists, My list and watch progress from that ${d.kind === 'desktop' ? 'computer' : 'TV'}.`)) return;
        try { await post('/v1/devices/unlink', { id: d.id }); toast(d.kind === 'desktop' ? 'That computer is signed out' : 'That TV is unlinked'); refresh(); } catch (e) { toast(e.message); }
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
