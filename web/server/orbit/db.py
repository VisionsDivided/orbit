# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""orbit.db: one SQLite file with numbered migrations.

Migrations are ``migrations/NNN_name.sql``, applied in order inside one transaction each; the
database's ``PRAGMA user_version`` is the number of the last one applied. Before an existing
database is upgraded, a copy is kept next to it (``orbit.db.bak-v<old>``). A database newer than
this code is refused rather than guessed at."""
from __future__ import annotations

import os
import re
import shutil
import sqlite3
import threading

MIGRATIONS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "migrations")
_NAME = re.compile(r"^(\d{3})_[a-z0-9_]+\.sql$")


class TooNew(RuntimeError):
    pass


def migrations(folder: str = MIGRATIONS) -> list[tuple[int, str]]:
    """[(number, path)] sorted; numbers must run 1, 2, 3 ... without gaps."""
    found = []
    for f in os.listdir(folder):
        m = _NAME.match(f)
        if m:
            found.append((int(m.group(1)), os.path.join(folder, f)))
    found.sort()
    for i, (n, _) in enumerate(found, 1):
        if n != i:
            raise RuntimeError(f"migration numbers must run 1..n without gaps (found {n} at position {i})")
    return found


class Database:
    def __init__(self, path: str, folder: str = MIGRATIONS):
        self.path = path
        self.lock = threading.RLock()
        self.db = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA foreign_keys=ON")
        self.migrate(folder)

    def close(self):
        with self.lock:
            self.db.close()

    def version(self) -> int:
        return self.db.execute("PRAGMA user_version").fetchone()[0]

    def migrate(self, folder: str = MIGRATIONS) -> int:
        todo = migrations(folder)
        with self.lock:
            have = self.version()
            if have > len(todo):
                raise TooNew(f"{self.path} is at version {have}, this code knows {len(todo)}: update Orbit")
            if have == len(todo):
                return have
            if have > 0:
                self.db.execute("PRAGMA wal_checkpoint(TRUNCATE)")
                shutil.copy2(self.path, f"{self.path}.bak-v{have}")
            for n, p in todo[have:]:
                with open(p, encoding="utf-8") as f:
                    sql = f.read()
                try:
                    self.db.execute("BEGIN IMMEDIATE")
                    for stmt in _statements(sql):
                        self.db.execute(stmt)
                    self.db.execute(f"PRAGMA user_version = {n}")
                    self.db.execute("COMMIT")
                except Exception:
                    self.db.execute("ROLLBACK")
                    raise
            return self.version()

    def q(self, sql: str, args=()) -> list:
        with self.lock:
            return self.db.execute(sql, args).fetchall()

    def one(self, sql: str, args=()):
        with self.lock:
            return self.db.execute(sql, args).fetchone()

    def run(self, sql: str, args=()) -> int:
        """Execute a write; returns the number of rows changed."""
        with self.lock:
            return self.db.execute(sql, args).rowcount


def _statements(sql: str) -> list[str]:
    """Split a migration file into statements (no semicolons inside strings in our migrations)."""
    lines = [ln for ln in sql.splitlines() if not ln.strip().startswith("--")]
    return [s.strip() for s in "\n".join(lines).split(";") if s.strip()]
