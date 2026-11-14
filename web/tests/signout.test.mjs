// Copyright 2026 The Orbit authors. SPDX-License-Identifier: Apache-2.0
// Signing out of an account clears what this browser cached for it (signout.js).
import test from 'node:test';
import assert from 'node:assert/strict';
import { clearStores, clearDatabases, forgetAccountHere } from '../frontend/js/signout.js';

class Store {
  constructor(o) { this.m = new Map(Object.entries(o)); }
  get length() { return this.m.size; }
  key(i) { return [...this.m.keys()][i] ?? null; }
  getItem(k) { return this.m.has(k) ? this.m.get(k) : null; }
  setItem(k, v) { this.m.set(k, String(v)); }
  removeItem(k) { this.m.delete(k); }
  keys() { return [...this.m.keys()].sort(); }
}

test("the app's keys go, other sites' keys and the reload guard stay", () => {
  const local = new Store({ 'tvbro.ui': '{"playing":{"key":"p0|1"}}', 'tvbro.reminders': '[]', 'orbit.x': '1', 'other.app': 'keep' });
  const session = new Store({ 'tvbro.parental': '{}', 'tvbro.sid': 'abc', 'tvbro.reload': '1' });
  const gone = clearStores(local, session);
  assert.deepEqual(gone.sort(), ['orbit.x', 'tvbro.parental', 'tvbro.reminders', 'tvbro.sid', 'tvbro.ui']);
  assert.deepEqual(local.keys(), ['other.app']);
  assert.deepEqual(session.keys(), ['tvbro.reload']);
});

test('blocked or missing storage is fine', () => {
  const blocked = { get length() { throw new Error('SecurityError'); } };
  assert.deepEqual(clearStores(null, blocked), []);
});

test("only this app's IndexedDB databases are deleted", async () => {
  const deleted = [];
  const idb = { databases: async () => [{ name: 'tvbro-cache' }, { name: 'orbit' }, { name: 'someone-else' }], deleteDatabase: (n) => deleted.push(n) };
  assert.deepEqual(await clearDatabases(idb), ['tvbro-cache', 'orbit']);
  assert.deepEqual(deleted, ['tvbro-cache', 'orbit']);
  assert.deepEqual(await clearDatabases({}), []);
  assert.deepEqual(await clearDatabases(undefined), []);
});

test('forgetAccountHere clears both storages and tells the page', async () => {
  const local = new Store({ 'tvbro.ui': '{}' });
  const session = new Store({ 'tvbro.sid': 'x' });
  let told = 0;
  const saved = {};
  for (const k of ['localStorage', 'sessionStorage', 'indexedDB', 'dispatchEvent']) saved[k] = Object.getOwnPropertyDescriptor(globalThis, k);
  try {
    Object.defineProperty(globalThis, 'localStorage', { value: local, configurable: true });
    Object.defineProperty(globalThis, 'sessionStorage', { value: session, configurable: true });
    Object.defineProperty(globalThis, 'indexedDB', { value: undefined, configurable: true });
    Object.defineProperty(globalThis, 'dispatchEvent', { value: (e) => { if (e.type === 'tvbro-account-cleared') told++; }, configurable: true });
    await forgetAccountHere();
  } finally {
    for (const [k, d] of Object.entries(saved)) { if (d) Object.defineProperty(globalThis, k, d); else delete globalThis[k]; }
  }
  assert.deepEqual([local.length, session.length, told], [0, 0, 1]);
});
