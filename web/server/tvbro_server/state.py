# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""Server-side user state in SQLite, shared by every device on the LAN: My list, resume
positions / watched marks, reminders, search history and preferences."""
from __future__ import annotations

import json
import sqlite3
import threading
import time

SCOPES = {"mylist": 2000, "resume": 2000, "reminders": 500, "history": 20, "prefs": 200}
MAX_VALUE = 16 * 1024


def _key_ok(scope, key) -> bool:
    return scope in SCOPES and isinstance(key, str) and 0 < len(key) <= 300


def clean_put(scope, key, value):
    """The JSON text to store for a put, or None when the scope, key or value is not allowed."""
    if not _key_ok(scope, key):
        return None
    raw = json.dumps(value)
    return raw if len(raw) <= MAX_VALUE else None


def clean_op(d):
    """One change of a batch, ``{"op": "put"|"del", "scope", "key", "value"?}`` -> ``(op, scope, key, raw)``, or
    None when it is not allowed."""
    if not isinstance(d, dict):
        return None
    op, scope, key = d.get("op", "put"), d.get("scope"), d.get("key")
    if op == "del":
        return ("del", scope, key, None) if _key_ok(scope, key) else None
    if op != "put":
        return None
    raw = clean_put(scope, key, d.get("value"))
    return None if raw is None else ("put", scope, key, raw)


class StateStore:
    def __init__(self, path: str):
        self._lock = threading.Lock()
        self._last = 0.0  # last "updated" written; see _stamp
        self.db = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("CREATE TABLE IF NOT EXISTS kv (scope TEXT NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL,"
                        " updated REAL NOT NULL, PRIMARY KEY (scope, key))")
        self._last = self.db.execute("SELECT COALESCE(MAX(updated), 0) FROM kv").fetchone()[0]

    def all(self) -> dict:
        out: dict = {"mylist": [], "resume": {}, "reminders": [], "history": [], "prefs": {}}
        with self._lock:
            rows = self.db.execute("SELECT scope, key, value FROM kv ORDER BY updated DESC").fetchall()
        for scope, key, value in rows:
            try:
                v = json.loads(value)
            except ValueError:
                continue
            if scope in ("resume", "prefs"):
                out[scope][key] = v
            elif scope in out:
                out[scope].append(v)
        return out

    def _stamp(self) -> float:
        """A strictly increasing 'updated' value (call with the lock held). time.time() can return the
        same value for writes in quick succession (coarse clocks, e.g. Windows), which made the newest-first
        order of My list and friends arbitrary."""
        self._last = max(time.time(), self._last + 1e-6)
        return self._last

    def put(self, scope: str, key: str, value) -> bool:
        raw = clean_put(scope, key, value)
        if raw is None:
            return False
        with self._lock:
            self._put_locked(scope, key, raw)
            self._trim_locked(scope)
        return True

    def _put_locked(self, scope: str, key: str, raw: str):
        self.db.execute("INSERT INTO kv(scope, key, value, updated) VALUES (?,?,?,?) "
                        "ON CONFLICT(scope, key) DO UPDATE SET value=excluded.value, updated=excluded.updated",
                        (scope, key, raw, self._stamp()))

    def _trim_locked(self, scope: str):
        """Keep each scope bounded (oldest go first)."""
        self.db.execute("DELETE FROM kv WHERE scope=? AND key NOT IN (SELECT key FROM kv WHERE scope=? "
                        "ORDER BY updated DESC LIMIT ?)", (scope, scope, SCOPES[scope]))

    def apply_locked(self, ops: list) -> int:
        """Apply checked ops ``(op, scope, key, raw)`` from ``clean_op`` ("put" or "del"), in order. Call with the
        lock held, inside the caller's transaction (orbit/sync.py commits a batch with episode changes at once)."""
        touched = set()
        for op, scope, key, raw in ops:
            if op == "put":
                self._put_locked(scope, key, raw)
                touched.add(scope)
            else:
                self.db.execute("DELETE FROM kv WHERE scope=? AND key=?", (scope, key))
        for scope in touched:
            self._trim_locked(scope)
        return len(ops)

    def delete(self, scope: str, key: str):
        with self._lock:
            self.db.execute("DELETE FROM kv WHERE scope=? AND key=?", (scope, key))

    def clear(self, scope: str):
        if scope in SCOPES:
            with self._lock:
                self.db.execute("DELETE FROM kv WHERE scope=?", (scope,))
