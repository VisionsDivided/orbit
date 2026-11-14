# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""Per-profile state: My list (and so favourite channels), resume points, reminders, search
history, preferences and show progress.

Each profile has its own SQLite file, ``profiles/<profile id>/state.db``, opened with tvBro's
own StateStore and SeriesStore. Profiles are kept apart by being different files, not by a
column a query could forget. In tvBro mode (no accounts) the household's ``state.db`` is used
as before."""
from __future__ import annotations

import os
import re
import shutil
import sqlite3
import threading
from typing import Callable, Optional

from tvbro_server import series as SER
from tvbro_server.state import StateStore

_PID = re.compile(r"^[0-9a-f]{16}$")      # orbit/accounts.py ids: secrets.token_hex(8)


class ProfileStores:
    def __init__(self, data_dir: str, household_state: StateStore, household_series, fetch_eps: Optional[Callable],
                 enabled: Callable[[], bool]):
        self.root = os.path.join(data_dir, "profiles")
        self.household = (household_state, household_series)
        self.fetch_eps = fetch_eps
        self.enabled = enabled
        self._lock = threading.Lock()
        self._open: dict[str, tuple] = {}

    def _path(self, pid: str) -> str:
        if not isinstance(pid, str) or not _PID.match(pid):
            raise ValueError("profile id")
        return os.path.join(self.root, pid, "state.db")

    def for_profile(self, pid: str) -> tuple:
        """(StateStore, SeriesStore) of one profile, opened on first use."""
        with self._lock:
            got = self._open.get(pid)
            if got:
                return got
            path = self._path(pid)
            os.makedirs(os.path.dirname(path), mode=0o700, exist_ok=True)
            st = StateStore(path)
            se = SER.SeriesStore(st, self.fetch_eps)
            self._open[pid] = (st, se)
        SER.start_background(se)
        return st, se

    def for_session(self, sess: Optional[dict]) -> tuple:
        """The stores a request uses: the household's in tvBro mode, else the session's profile.
        Orbit mode without a profile on the session is refused, never answered with shared data."""
        if not self.enabled():
            return self.household
        pid = (sess or {}).get("profile_id")
        if not pid:
            raise PermissionError("no profile selected")
        return self.for_profile(pid)

    def adopt_household(self, pid: str):
        """At setup: the owner's first profile starts with the household's data so far (My list,
        favourites, resume, show progress). The household file stays as it is, as a copy."""
        path = self._path(pid)
        os.makedirs(os.path.dirname(path), mode=0o700, exist_ok=True)
        with self._lock:
            if pid in self._open:
                return
            st = self.household[0]
            dest = sqlite3.connect(path)
            try:
                with st._lock:
                    st.db.backup(dest)
            finally:
                dest.close()

    def drop(self, pid: str):
        """A deleted profile: close and remove its file."""
        path = self._path(pid)
        with self._lock:
            got = self._open.pop(pid, None)
            if got:
                with got[0]._lock:
                    got[0].db.close()
        shutil.rmtree(os.path.dirname(path), ignore_errors=True)

    def close(self):
        with self._lock:
            for st, _ in self._open.values():
                with st._lock:
                    st.db.close()
            self._open.clear()
