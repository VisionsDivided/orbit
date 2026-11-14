// Copyright 2026 The Orbit authors. SPDX-License-Identifier: Apache-2.0
// The web player's stream-limit prompt: names like the TV's, and the ask-then-start loop.
import test from 'node:test';
import assert from 'node:assert/strict';
import { streamRows, claimSlot, isMine } from '../frontend/js/streamprompt.js';

const use = (id, label, extra = {}) => ({ id, sid: id.slice(0, 6), label, profile: '', device: '', what: '', recording: false, ...extra });

test('names: the device name, else the profile, else the label', () => {
  const r = streamRows([use('a', 'TV (Model)', { profile: 'Kids', device: 'Kitchen', what: 'News' }),
    use('b', 'TV (Other)', { profile: 'Dad' }), use('c', 'Chrome on Mac'), use('d', '')]);
  assert.deepEqual(r.map((x) => x.name), ['Kitchen', 'Dad', 'Chrome on Mac', 'Another device']);
  assert.equal(r[0].button, 'Stop Kitchen and watch');
  assert.equal(r[0].what, 'News');
});

test('equal names are told apart by the label, then numbered', () => {
  const r = streamRows([use('a', 'TV (A)', { device: 'Kitchen' }), use('b', 'TV (B)', { device: 'Kitchen' }),
    use('c', 'Chrome on Windows'), use('d', 'Chrome on Windows')]);
  assert.deepEqual(r.map((x) => x.name), ['Kitchen on TV (A)', 'Kitchen on TV (B)', 'Chrome on Windows (1)', 'Chrome on Windows (2)']);
});

test('recordings are listed without a button; old servers without ids give none', () => {
  const r = streamRows([use('a', 'Recorder', { recording: true, what: 'Show' }), use('', 'TV')]);
  assert.deepEqual(r.map((x) => [x.name, x.stoppable]), [['Recording', false], ['TV', false]]);
});

const full = { max: 2, count: 2, active: [use('aaaaaa111', 'TV', { device: 'Kitchen' }), use('bbbbbb222', 'Chrome', { profile: 'Dad' })] };

test('a free slot: no question, start as before', async () => {
  const starts = [];
  const r = await claimSlot({ status: async () => ({ max: 2, count: 1, active: [full.active[0]] }), sid: 'me' + 'x'.repeat(30), interactive: true,
    start: async (rep) => { starts.push(rep); return { ok: true, replaced: 0 }; }, ask: async () => assert.fail('asked') });
  assert.deepEqual(starts, [undefined]);
  assert.equal(r.ok, true);
});

test('full: asks, and stops the stream chosen', async () => {
  const starts = []; let asked = null;
  const r = await claimSlot({ status: async () => full, sid: 'me' + 'x'.repeat(30), interactive: true,
    start: async (rep) => { starts.push(rep); return { ok: true, replaced: 1 }; },
    ask: async (max, rows) => { asked = [max, rows.map((x) => x.button)]; return rows[1].id; } });
  assert.deepEqual(asked, [2, ['Stop Kitchen and watch', 'Stop Dad and watch']]);
  assert.deepEqual(starts, ['bbbbbb222']);
  assert.equal(r.ok, true);
});

test('Cancel starts nothing; the preview never asks and never stops anyone', async () => {
  const start = async () => assert.fail('started');
  assert.deepEqual(await claimSlot({ status: async () => full, sid: 'me' + 'x'.repeat(30), interactive: true, start, ask: async () => null }),
    { ok: false, cancelled: true });
  assert.deepEqual(await claimSlot({ status: async () => full, sid: 'me' + 'x'.repeat(30), interactive: false, start, ask: async () => assert.fail('asked') }),
    { ok: false, full: true });
});

test('this tab already holds a slot: no question', async () => {
  const sid = 'bbbbbb222' + 'x'.repeat(30);
  assert.equal(isMine(full, sid), true);
  const r = await claimSlot({ status: async () => full, sid, interactive: true, start: async (rep) => ({ ok: true, rep }), ask: async () => assert.fail('asked') });
  assert.equal(r.rep, undefined);
});

test('the chosen stream ended meanwhile: asks again with the new list', async () => {
  const now = { max: 2, count: 2, active: [full.active[0], use('cccccc333', 'Firefox')] };
  const asks = []; const starts = [];
  const r = await claimSlot({ status: async () => full, sid: 'me' + 'x'.repeat(30), interactive: true,
    start: async (rep) => { starts.push(rep); return starts.length === 1 ? { ok: false, changed: true, streams: now } : { ok: true, replaced: 1 }; },
    ask: async (max, rows) => { asks.push(rows.map((x) => x.name)); return rows[rows.length - 1].id; } });
  assert.deepEqual(asks, [['Kitchen', 'Dad'], ['Kitchen', 'Firefox']]);
  assert.deepEqual(starts, ['bbbbbb222', 'cccccc333']);
  assert.equal(r.ok, true);
});

test('no stream list (tvBro mode or an older server): start as before', async () => {
  for (const status of [async () => null, async () => { throw new Error('offline'); }]) {
    const r = await claimSlot({ status, sid: 's', interactive: true, start: async (rep) => ({ ok: true, rep }), ask: async () => assert.fail('asked') });
    assert.deepEqual(r, { ok: true, rep: undefined });
  }
});
