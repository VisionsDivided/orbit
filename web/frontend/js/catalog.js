// Copyright 2026 The Orbit authors. SPDX-License-Identifier: Apache-2.0
// The /v1/catalog/* feeds (Home, Recommended, Browse, Collections), contract in
// docs/orbit/API-v1.md and the agents' brief. Everything the screens read goes through the
// normalizers below, so a renamed field is one line in FIELDS, and a route that moves is one line in
// ROUTES. A route the server does not have yet (404 / 501, an older server) answers
// {missing: true}; the screens then fall back to the views built from the /api data.
// No DOM here (node:test imports it).

/** Where each feed lives. kind: 'movies' | 'shows'. */
export const ROUTES = {
  home: () => '/v1/catalog/home',
  recommended: (kind) => `/v1/catalog/${kind}/recommended`,
  browse: (kind, q) => `/v1/catalog/${kind}/browse?` + query(q),
  collections: (kind) => `/v1/catalog/${kind}/collections`,
  collection: (id) => '/v1/catalog/collections/' + encodeURIComponent(id),
};
/** Item field -> the names the server may use for it (first one present wins). */
export const FIELDS = {
  key: ['key'],
  kind: ['kind', 'type', 'mediaType'],
  title: ['title', 'name'],
  year: ['year'],
  poster: ['poster', 'posterUrl', 'cover'],
  backdrop: ['backdrop', 'backdropUrl', 'fanart'],
  rating: ['rating', 'score'],
  genres: ['genres', 'genre'],
  source: ['source', 'sources'],
  added: ['added', 'addedAt'],
  released: ['released', 'releaseDate'],
  colors: ['colors', 'colours', 'palette'],
  progress: ['progress', 'resume'],
  overview: ['overview', 'plot', 'synopsis', 'summary'],
  runtime: ['runtime', 'duration'],
};
export const SORTS = [['added', 'Recently added'], ['released', 'Recently released'], ['title', 'Title A–Z'], ['rating', 'Rating']];
export const SOURCES = [['all', 'All'], ['local', 'Local'], ['iptv', 'IPTV']];

function query(q = {}) {
  const p = new URLSearchParams();
  for (const k of ['source', 'genre', 'sort', 'offset', 'limit']) if (q[k] != null && q[k] !== '') p.set(k, String(q[k]));
  return p.toString();
}
const pick = (o, names) => { for (const n of names) if (o && o[n] !== undefined && o[n] !== null) return o[n]; return null; };
const num = (v) => (v === null || v === undefined || v === '' || !Number.isFinite(+v) ? null : +v);

/** 'movie' | 'show' from whatever the server says ('series', 'tv', 'shows' are shows). */
export function normKind(k) { return /^(show|shows|series|tv|episode)$/i.test(String(k || '')) ? 'show' : 'movie'; }
/** The path segment of a screen kind: 'movie' / 'series' / 'show' -> 'movies' / 'shows'. */
export const pathKind = (k) => (normKind(k) === 'show' ? 'shows' : 'movies');

/** A catalogue Item in the shape the screens use (unknown fields kept on .raw). */
export function normalizeItem(raw) {
  if (!raw || typeof raw !== 'object') return null;
  const key = pick(raw, FIELDS.key);
  if (key === null) return null;
  let genres = pick(raw, FIELDS.genres);
  if (typeof genres === 'string') genres = genres.split(/\s*[,/|]\s*/).filter(Boolean);
  let source = pick(raw, FIELDS.source);
  if (Array.isArray(source)) source = source[0] || null;
  const pr = pick(raw, FIELDS.progress);
  const progress = pr && num(pr.secs) !== null ? { secs: num(pr.secs), dur: num(pr.dur) || 0 } : null;
  const colors = pick(raw, FIELDS.colors);
  return {
    key: String(key),
    kind: normKind(pick(raw, FIELDS.kind)),
    title: String(pick(raw, FIELDS.title) || ''),
    year: num(pick(raw, FIELDS.year)),
    poster: pick(raw, FIELDS.poster),
    backdrop: pick(raw, FIELDS.backdrop),
    rating: num(pick(raw, FIELDS.rating)),
    genres: Array.isArray(genres) ? genres.map(String) : [],
    source: source ? String(source).toLowerCase() : null,
    added: pick(raw, FIELDS.added),
    released: pick(raw, FIELDS.released),
    colors: Array.isArray(colors) && colors.length ? colors : null,
    progress,
    overview: pick(raw, FIELDS.overview) || '',
    runtime: num(pick(raw, FIELDS.runtime)),
    id: raw.id != null ? String(raw.id) : null,
    conn: raw.conn != null ? String(raw.conn) : null,
    locked: !!raw.locked,
    next: raw.next && typeof raw.next === 'object' ? raw.next : null,
    raw,
  };
}
const items = (list) => (Array.isArray(list) ? list.map(normalizeItem).filter(Boolean) : []);
export function normalizeRows(rows) {
  return (Array.isArray(rows) ? rows : []).map((r, i) => ({ id: String((r && r.id) || 'row' + i), title: String((r && (r.title || r.name)) || ''), items: items(r && (r.items || r.results)) }))
    .filter((r) => r.items.length);
}
export function normalizeCollection(c) {
  if (!c || typeof c !== 'object' || c.id == null) return null;
  return { id: String(c.id), name: String(c.name || c.title || ''), count: num(c.count) || 0, poster: c.poster || null, backdrop: c.backdrop || null,
    colors: Array.isArray(c.colors) && c.colors.length ? c.colors : null, overview: c.overview || c.summary || '', source: c.source || null, kind: c.kind ? normKind(c.kind) : null, raw: c };
}

/**
 * The IPTV detail route of an item whose key is in the /api scheme ('m|p0|123', 's|p1|9', or the
 * resume form 'm|123'): {kind: 'movie'|'series', id} (the /api VOD id), else null.
 */
export function iptvRef(it) {
  if (!it || !it.key) return null;
  if (it.source && it.source !== 'iptv') return null;
  const p = String(it.key).split('|');
  if (p[0] !== 'm' && p[0] !== 's') return null;
  const kind = p[0] === 'm' ? 'movie' : 'series';
  if (it.id && !it.conn) return { kind, id: String(it.id) };    // the catalogue sends the /api VOD id itself
  if (p.length === 2) return p[1] ? { kind, id: p[1] } : null;
  const src = p[1], raw = p.slice(2).join('|');
  if (!raw) return null;
  return { kind, id: src === 'p0' ? raw : `${src}~${raw}` };
}

// ---------------------------------------------------------------- requests
let cfg = { headers: () => ({}), onUnauthorized: () => {}, accounts: () => true };
/** headers(): extra request headers (the parental token); onUnauthorized(): the session ended;
 *  accounts(): Orbit accounts mode (in tvBro mode a 401/403 just means "not available here"). */
export function configure(c) { cfg = Object.assign({}, cfg, c); }
const missingUntil = new Map();   // route name -> time until which it is taken as missing
const MISSING_MS = 5 * 60000;

/** GET a /v1 JSON route: {ok: true, data} | {missing: true}. Other errors throw (e.status set). */
export async function getJson(path, name, fetchFn = (typeof fetch === 'function' ? fetch : null)) {
  if (name && (missingUntil.get(name) || 0) > Date.now()) return { missing: true };
  const r = await fetchFn(path, { credentials: 'same-origin', headers: cfg.headers() });
  const gone = r.status === 404 || r.status === 501 || r.status === 405 || ((r.status === 401 || r.status === 403) && !cfg.accounts());
  if (gone) { if (name) missingUntil.set(name, Date.now() + MISSING_MS); return { missing: true, status: r.status }; }
  if (r.status === 401) { cfg.onUnauthorized(); const e = new Error('sign in first'); e.status = 401; throw e; }
  if (!r.ok) { let m = 'HTTP ' + r.status; try { m = (await r.json()).error || m; } catch {} const e = new Error(m); e.status = r.status; throw e; }
  return { ok: true, data: await r.json() };
}
export function forgetMissing() { missingUntil.clear(); }

export const catalog = {
  async home() {
    const r = await getJson(ROUTES.home(), 'home'); if (r.missing) return null;
    return { hero: items(r.data.hero), rows: normalizeRows(r.data.rows) };
  },
  async recommended(kind) {
    const r = await getJson(ROUTES.recommended(pathKind(kind)), 'recommended'); if (r.missing) return null;
    return { hero: items(r.data.hero), rows: normalizeRows(r.data.rows) };
  },
  async browse(kind, q) {
    const r = await getJson(ROUTES.browse(pathKind(kind), q), 'browse'); if (r.missing) return null;
    const d = r.data || {};
    return { total: num(d.total) || 0, genres: Array.isArray(d.genres) ? d.genres.map((g) => (typeof g === 'string' ? g : g && (g.name || g.id))).filter(Boolean) : [], items: items(d.items) };
  },
  async collections(kind) {
    const r = await getJson(ROUTES.collections(pathKind(kind)), 'collections'); if (r.missing) return null;
    return { collections: (r.data.collections || []).map(normalizeCollection).filter(Boolean) };
  },
  /** null = the route is missing; {notFound: true} = no such collection. */
  async collection(id) {
    const r = await getJson(ROUTES.collection(id), null);
    if (r.missing) return r.status === 404 ? { notFound: true } : null;
    return { collection: normalizeCollection(r.data.collection) || { id, name: '', count: 0 }, items: items(r.data.items) };
  },
};
