# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""Cheap sync for devices (docs/orbit/API-v1.md, "Sync for devices"): a revision of the profile's state so a TV
downloads My list, resume points and show progress only when something changed, and one batch call that applies
many changes in a single transaction instead of one request per item.

The revision is not stored anywhere: it is made from the profile's SQLite connection, which every writer of that
profile's ``state.db`` uses (StateStore, SeriesStore, show progress, restore). ``total_changes`` counts every row
written through it, ``PRAGMA data_version`` changes when another connection commits, and a random part made when
the file is opened changes after a restart. So any write gives a new revision; a revision can change without a
visible difference (an episode list cached from the provider), which only costs one download."""
from __future__ import annotations

import secrets
import threading
import time
import weakref
from typing import Callable

from tvbro_server.state import clean_op

from . import progress as PROG
from .accounts import ApiError
from .api import Call, Reply

MAX_OPS = 500                                   # state changes per batch (episodes: progress.MAX_ITEMS)
_nonces: "weakref.WeakKeyDictionary" = weakref.WeakKeyDictionary()
_nonce_lock = threading.Lock()


def _nonce(st) -> str:
    with _nonce_lock:
        n = _nonces.get(st)
        if n is None:
            n = _nonces[st] = secrets.token_hex(4)
        return n


def rev(st) -> str:
    """The profile state's revision: equal strings mean nothing was written in between."""
    with st._lock:
        dv = st.db.execute("PRAGMA data_version").fetchone()[0]
        n = st.db.total_changes
    return f"{_nonce(st)}.{dv}.{n}"


def state(st, se, since=None) -> dict:
    """Everything a TV syncs, or only the revision when it equals ``since``. The revision is read first, so a write
    made while the answer is put together gives a newer revision next time (one more download, never a missed one)."""
    r = rev(st)
    if isinstance(since, str) and since == r:
        return {"rev": r, "changed": False}
    return {"rev": r, "changed": True, "state": st.all(), "progress": PROG.shows_state(se)}


def batch(st, se, body: dict, now=time.time) -> dict:
    """``{"state": [{"op": "put"|"del", "scope", "key", "value"?}], "episodes": [episode change]}``: checked first
    (400, nothing applied), then applied in order in one transaction."""
    ops = body.get("state") if body.get("state") is not None else []
    if not isinstance(ops, list) or len(ops) > MAX_OPS:
        raise ApiError(400, f"state: a list of at most {MAX_OPS}")
    clean = []
    for i, d in enumerate(ops):
        c = clean_op(d)
        if c is None:
            raise ApiError(400, f"state[{i}]: op put or del, a known scope, a key of 1 to 300 characters, "
                                f"a value of at most 16 KB")
        clean.append(c)
    eps = body.get("episodes") if body.get("episodes") is not None else []
    ep_clean = PROG.clean_items(eps, now, "episodes")
    touched, applied = {}, 0
    with st._lock:                              # SeriesStore shares this connection and lock
        st.db.execute("BEGIN IMMEDIATE")
        try:
            st.apply_locked(clean)
            if ep_clean:
                touched, applied = PROG._apply(se, ep_clean, now)
            st.db.execute("COMMIT")
        except Exception:
            st.db.execute("ROLLBACK")
            raise
    if touched:
        PROG._recompute_later(se)
    return {"state": len(clean), "episodes": applied, "shows": len(touched)}


def routes(stores: Callable[[dict], tuple]) -> dict:
    """The /v1/sync routes, mounted into the /v1 route table (orbit/api.py) by the server."""
    def profile(c: Call):
        try:
            return stores(c.sess)
        except PermissionError:
            raise ApiError(409, "no profile selected")

    def get(c: Call) -> Reply:
        st, se = profile(c)
        return Reply(state(st, se, c.query.get("since")))

    def post(c: Call) -> Reply:
        st, se = profile(c)
        return Reply(batch(st, se, c.body))

    return {("GET", "/v1/sync/state"): get, ("POST", "/v1/sync/batch"): post}
