# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""Show progress sync for linked TVs (docs/orbit/API-v1.md): all episode marks and show summaries of a profile,
and a batch of episode changes from a device, newest change wins per episode.

Works on tvBro's SeriesStore (series.py) of the session's profile, without changing it: same tables, same lock.
Times on the wire are milliseconds; the store keeps seconds."""
from __future__ import annotations

import json
import threading
import time

from tvbro_server import series as SER

from .accounts import ApiError

MAX_ITEMS = 500
_workers: dict = {}                       # id(store) -> the one recompute thread running for it
_workers_lock = threading.Lock()


def _recompute_later(store):
    """At most one background recompute per store at a time (a push while one runs is picked up by it: it reads
    every stale show when it starts, and the next push starts a new one if needed)."""
    with _workers_lock:
        t = _workers.get(id(store))
        if t is not None and t.is_alive():
            return
        t = threading.Thread(target=store.recompute_stale, name="orbit-series-recompute", daemon=True)
        _workers[id(store)] = t
    t.start()


def _ms(t) -> int:
    return int(round(float(t or 0) * 1000))


def shows_state(store) -> dict:
    """Every episode mark and every show summary of this profile."""
    with store._lock:
        eps = store.db.execute("SELECT id, series_id, secs, dur, done, at FROM episodes").fetchall()
        shows = store.db.execute("SELECT id, skey, name, poster, last_ep, next, finished, hidden, watched_at FROM series").fetchall()
    return {
        "episodes": [{"seriesId": s, "epId": i, "secs": int(sc or 0), "dur": int(d or 0), "done": bool(dn), "at": _ms(a)}
                     for i, s, sc, d, dn, a in eps],
        "shows": [{"id": i, "key": k, "name": n, "poster": p, "lastEp": le, "next": json.loads(nx) if nx else None,
                   "finished": bool(f), "hidden": bool(h), "at": _ms(w)}
                  for i, k, n, p, le, nx, f, h, w in shows],
    }


def _text(v, field: str) -> str:
    s = str(v if v is not None else "").strip()
    if not s or len(s) > 100:
        raise ApiError(400, f"{field}: 1 to 100 characters")
    return s


def _num(v, field: str, top: float = 10 * 86400) -> float:
    """A number from 0 to ``top`` (seconds by default: positions and durations up to ten days)."""
    try:
        f = float(v or 0)
    except (TypeError, ValueError):
        raise ApiError(400, f"{field}: a number")
    if f != f or f < 0 or f > top:
        raise ApiError(400, f"{field}: out of range")
    return f


def clean_items(items, now=time.time, field: str = "items") -> list:
    """Checked episode changes (400 for anything malformed, so a batch is applied whole or not at all)."""
    if not isinstance(items, list) or len(items) > MAX_ITEMS:
        raise ApiError(400, f"{field}: a list of at most {MAX_ITEMS}")
    clean = []
    for it in items:
        if not isinstance(it, dict):
            raise ApiError(400, f"{field}: objects")
        at = _num(it.get("at"), "at", top=1e14) / 1000.0        # milliseconds since 1970
        if at > now() + 300:
            at = now()                                    # a device clock ahead of ours: count it as now
        clean.append((_text(it.get("seriesId"), "seriesId"), _text(it.get("epId"), "epId"), _num(it.get("secs"), "secs"),
                      _num(it.get("dur"), "dur"), it.get("done") is True, at,
                      str(it.get("name") or "")[:200] or None, str(it.get("poster") or "")[:500] or None))
    return clean


def push_episodes(store, items, now=time.time) -> dict:
    """Apply episode changes from a device. Each: {seriesId, epId, secs, dur, done, at (ms), name?, poster?}.
    A change older than what the server has for that episode is skipped. Changed shows get their next episode
    recomputed in the background (that may ask the provider for the episode list)."""
    clean = clean_items(items, now)
    applied, touched = 0, {}
    with store._lock:
        store.db.execute("BEGIN IMMEDIATE")        # one commit for the whole batch, not one per episode
        try:
            touched, applied = _apply(store, clean, now)
            store.db.execute("COMMIT")
        except Exception:
            store.db.execute("ROLLBACK")
            raise
    if touched:
        _recompute_later(store)
    return {"applied": applied, "shows": len(touched)}


def _apply(store, clean, now):
    applied, touched = 0, {}
    for sid, ep, secs, dur, done, at, name, poster in clean:
        old = store.db.execute("SELECT at, season, num, series_id FROM episodes WHERE id=?", (ep,)).fetchone()
        if old and float(old[0] or 0) >= at - 1e-3:
            continue
        if old and old[3] and old[3] != sid:
            sid = old[3]                       # an episode keeps the show it belongs to (no silent moves)
        store.db.execute("INSERT OR REPLACE INTO episodes(id, series_id, season, num, secs, dur, done, at) VALUES (?,?,?,?,?,?,?,?)",
                         (ep, sid, old[1] if old else None, old[2] if old else None, dur if done and dur else secs, dur,
                          1 if done else 0, at))
        applied += 1
        t = touched.get(sid)
        if t is None or at > t[1]:
            touched[sid] = (ep, at, name or (t[2] if t else None), poster or (t[3] if t else None))
    for sid, (ep, at, name, poster) in touched.items():
        store.db.execute(
            "INSERT INTO series(id, name, poster, skey, last_ep, watched_at, updated, stale) VALUES (?,?,?,?,?,?,?,1) "
            "ON CONFLICT(id) DO UPDATE SET name=coalesce(excluded.name, series.name), poster=coalesce(excluded.poster, series.poster), "
            "last_ep=CASE WHEN excluded.watched_at >= series.watched_at THEN excluded.last_ep ELSE series.last_ep END, "
            "watched_at=max(series.watched_at, excluded.watched_at), hidden=0, stale=1",
            (sid, name, poster, SER._series_key(sid), ep, at, now()))
    return touched, applied
