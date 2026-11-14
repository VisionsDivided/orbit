// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
// Where this device left the UI (section, guide group / row / scroll, full screen or guide,
// the playing channel): restored on reload, a reopened tab or a PWA relaunch. Per device
// (localStorage): it is UI state, not shared state. Every access is guarded (private mode).

const KEY = 'tvbro.ui';
let cache = null;
export function uiLoad() {
  if (cache) return cache;
  try { cache = JSON.parse(localStorage.getItem(KEY) || '{}') || {}; } catch { cache = {}; }
  return cache;
}
let timer = null;
export function uiSave(patch) {
  Object.assign(uiLoad(), patch);
  clearTimeout(timer);
  timer = setTimeout(flush, 300);
}
function flush() { try { localStorage.setItem(KEY, JSON.stringify(uiLoad())); } catch {} }
window.addEventListener('pagehide', flush);
// signed out (signout.js): the stored copy is gone; forget the one in memory too, so it is not written back
window.addEventListener('tvbro-account-cleared', () => { clearTimeout(timer); cache = {}; });
document.addEventListener('visibilitychange', () => { if (document.hidden) flush(); });
