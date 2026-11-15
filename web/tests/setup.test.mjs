// Copyright 2026 The Orbit authors. SPDX-License-Identifier: Apache-2.0
// Settings ▸ Requests (Seerr) and Settings ▸ Sources (Plex, Jellyfin): setuplogic.js.
import test from 'node:test';
import assert from 'node:assert/strict';
import { withScheme, seerrBody, seerrState, seerrUserLabel, mappingRows, suggestedCount, connView, signInSteps, secsLeft, mmss, pollDelay, agoText } from '../frontend/js/setuplogic.js';

test('addresses get http:// when no scheme was typed', () => {
  assert.equal(withScheme(' 192.0.2.20:5055 '), 'http://192.0.2.20:5055');
  assert.equal(withScheme('https://seerr.example.com'), 'https://seerr.example.com');
  assert.equal(withScheme('HTTP://192.0.2.20'), 'HTTP://192.0.2.20');
  assert.equal(withScheme('   '), '');
});

test('the Seerr form: address and key needed, the key only sent when typed', () => {
  assert.match(seerrBody({ baseUrl: '', apiKey: 'k' }).error, /address/);
  assert.match(seerrBody({ baseUrl: '192.0.2.20:5055', apiKey: '' }).error, /API key/);
  assert.match(seerrBody({ baseUrl: '192.0.2.20:5055', apiKey: 'ab cd' }).error, /spaces/);
  assert.deepEqual(seerrBody({ baseUrl: '192.0.2.20:5055', apiKey: ' key-0123456789 ' }).body, { baseUrl: 'http://192.0.2.20:5055', apiKey: 'key-0123456789' });
  // a saved key may be kept: no apiKey in the body
  assert.deepEqual(seerrBody({ baseUrl: 'http://192.0.2.20:5055', apiKey: '' }, { keySet: true }).body, { baseUrl: 'http://192.0.2.20:5055' });
  // Test connection asks the server not to save
  assert.deepEqual(seerrBody({ baseUrl: 'http://192.0.2.20:5055', apiKey: 'key-0123456789' }, { test: true }).body,
    { baseUrl: 'http://192.0.2.20:5055', apiKey: 'key-0123456789', test: true });
});

test('the Seerr status line', () => {
  assert.equal(seerrState(null).tone, 'warn');
  assert.deepEqual(seerrState({ configured: false }), { text: 'Not connected', tone: 'off' });
  assert.equal(seerrState({ configured: true, reachable: false }).tone, 'warn');
  assert.deepEqual(seerrState({ configured: true, reachable: true, version: '2.0.0' }), { text: 'Connected · Seerr 2.0.0', tone: 'ok' });
});

const USERS = {
  users: [{ id: 1, name: 'Admin', email: 'admin@example.com', username: 'admin', plexUsername: '', jellyfinUsername: '', admin: true },
    { id: 2, name: 'Sam', email: 'sam@example.com', username: '', plexUsername: 'sammy', jellyfinUsername: '' }],
  accounts: [{ id: 'a1', username: 'alex', displayName: 'Alex', seerrUserId: 1, suggested: null },
    { id: 'a2', username: 'sammy', displayName: 'Sam', seerrUserId: null, suggested: 2 },
    { id: 'a3', username: 'jo', displayName: 'jo', seerrUserId: null, suggested: null }],
};

test('Seerr user labels show the other name when it differs', () => {
  assert.equal(seerrUserLabel(USERS.users[0]), 'Admin (admin@example.com)');
  assert.equal(seerrUserLabel(USERS.users[1]), 'Sam (sammy)');
  assert.equal(seerrUserLabel({ id: 9 }), 'User 9');
});

test('the mapping list: one select per account, the suggestion marked', () => {
  const rows = mappingRows(USERS);
  assert.deepEqual(rows.map((r) => [r.id, r.name, r.value]), [['a1', 'Alex (alex)', '1'], ['a2', 'Sam (sammy)', ''], ['a3', 'jo', '']]);
  assert.deepEqual(rows[1].options.map((o) => o[0]), ['', '1', '2']);
  assert.match(rows[1].options[2][1], /suggested/);
  assert.doesNotMatch(rows[0].options[1][1], /suggested/);
  assert.match(rows[0].options[1][1], /Seerr admin/, 'Seerr admins are marked (only the owner may link one)');
  assert.doesNotMatch(rows[0].options[2][1], /Seerr admin/);
  assert.equal(suggestedCount(USERS), 1);
  assert.deepEqual(mappingRows(null), []);
});

test('linked servers: name, user and state', () => {
  const now = 1_800_000_000;
  const c = { id: 'c1', kind: 'jellyfin', baseUrl: 'http://192.0.2.30:8096', serverName: 'Den', userName: 'alex', lastOk: now - 120 };
  assert.deepEqual(connView(c, now), { title: 'Den', sub: 'Jellyfin · as alex · http://192.0.2.30:8096', state: { text: 'Last reached 2 min ago', tone: 'off' } });
  assert.deepEqual(connView({ ...c, ok: true }, now).state, { text: 'Online', tone: 'ok' });
  assert.equal(connView({ ...c, ok: false, error: 'Jellyfin: link it again' }, now).state.text, 'Sign-in expired: link it again');
  assert.equal(connView({ ...c, ok: false, error: 'timed out' }, now).state.text, 'Cannot be reached: timed out');
  assert.equal(connView({ id: 'c2', kind: 'plex', baseUrl: '', serverName: '' }, now).title, 'Plex');
  assert.equal(agoText(now - 3 * 86400, now), '3 days ago');
});

test('sign-in steps for Plex (PIN) and Jellyfin (Quick Connect)', () => {
  const plex = signInSteps({ method: 'pin', code: 'AB12', linkUrl: 'https://plex.tv/link' });
  assert.equal(plex.code, 'AB12');
  assert.equal(plex.link, 'https://plex.tv/link');
  assert.match(plex.steps, /plex\.tv\/link/);
  const jf = signInSteps({ method: 'quickconnect', code: '123456', server: { name: 'Den' } });
  assert.equal(jf.link, undefined);
  assert.match(jf.steps, /In Den, open your profile ▸ Quick Connect/);
});

test('countdown and poll delay', () => {
  assert.equal(secsLeft(1000, 940), 60);
  assert.equal(secsLeft(1000, 1100), 0);
  assert.equal(mmss(605), '10:05');
  assert.equal(pollDelay(5), 5000);
  assert.equal(pollDelay(0.1), 2000);
  assert.equal(pollDelay(60), 10000);
  assert.equal(pollDelay(undefined), 3000);
});
