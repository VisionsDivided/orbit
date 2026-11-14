# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""Orbit show progress sync (orbit/progress.py): episode marks and show summaries per profile, newest wins."""
import os
import shutil
import sys
import tempfile
import threading
import time
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "server"))
from orbit import progress as PR  # noqa: E402
from orbit.accounts import ApiError  # noqa: E402
from tvbro_server import series as SER  # noqa: E402
from tvbro_server.state import StateStore  # noqa: E402

EPS = [{"id": "101", "season": 1, "num": 1, "title": "One", "duration": "00:40:00"},
       {"id": "102", "season": 1, "num": 2, "title": "Two", "duration": "00:40:00"},
       {"id": "103", "season": 1, "num": 3, "title": "Three", "duration": "00:40:00"}]


class Progress(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()
        self.state = StateStore(os.path.join(self.d, "state.db"))
        self.store = SER.SeriesStore(self.state, lambda sid: ("Show", "http://img.example.com/p.jpg", [dict(e) for e in EPS]))

    def tearDown(self):
        # the background recompute must finish before the connection closes (closing under its feet crashes SQLite)
        for t in threading.enumerate():
            if t.name in ("orbit-series-recompute", "series-recompute"):
                t.join(10)
        with self.store._lock:
            self.state.db.close()
        shutil.rmtree(self.d, ignore_errors=True)

    def wait_recompute(self):
        for _ in range(50):
            with self.store._lock:
                if not self.store.db.execute("SELECT 1 FROM series WHERE stale=1").fetchone():
                    return
            time.sleep(0.05)

    def test_state_lists_episodes_and_shows_in_ms(self):
        self.store.record("7", "101", 2400, 2400, ended=True, name="Show")
        self.store.record("7", "102", 600, 2400, name="Show")
        st = PR.shows_state(self.store)
        eps = {e["epId"]: e for e in st["episodes"]}
        self.assertTrue(eps["101"]["done"])
        self.assertEqual((eps["102"]["secs"], eps["102"]["done"]), (600, False))
        self.assertGreater(eps["102"]["at"], 10 ** 12, "milliseconds")
        show = st["shows"][0]
        self.assertEqual((show["id"], show["key"], show["next"]["id"]), ("7", "s|p0|7", "102"))

    def test_push_newest_wins_and_next_is_recomputed(self):
        now = time.time() * 1000
        r = PR.push_episodes(self.store, [
            {"seriesId": "7", "epId": "101", "secs": 2400, "dur": 2400, "done": True, "at": now - 5000, "name": "Show"},
            {"seriesId": "7", "epId": "102", "secs": 300, "dur": 2400, "done": False, "at": now - 1000},
        ])
        self.assertEqual(r, {"applied": 2, "shows": 1})
        self.wait_recompute()
        st = PR.shows_state(self.store)
        self.assertEqual(st["shows"][0]["next"]["id"], "102")
        self.assertEqual(st["shows"][0]["lastEp"], "102")
        # an older change for 102 does not overwrite the newer one
        r = PR.push_episodes(self.store, [{"seriesId": "7", "epId": "102", "secs": 30, "dur": 2400, "done": False, "at": now - 9000}])
        self.assertEqual(r["applied"], 0)
        self.assertEqual({e["epId"]: e["secs"] for e in PR.shows_state(self.store)["episodes"]}["102"], 300)
        # finishing the last episodes makes the show finished
        PR.push_episodes(self.store, [{"seriesId": "7", "epId": e, "secs": 2400, "dur": 2400, "done": True, "at": now + i}
                                      for i, e in enumerate(("102", "103"))])
        self.wait_recompute()
        self.assertTrue(PR.shows_state(self.store)["shows"][0]["finished"])

    def test_an_episode_keeps_its_show(self):
        now = time.time() * 1000
        PR.push_episodes(self.store, [{"seriesId": "7", "epId": "101", "secs": 100, "dur": 2400, "at": now - 5000}])
        PR.push_episodes(self.store, [{"seriesId": "8", "epId": "101", "secs": 200, "dur": 2400, "at": now}])
        ep = PR.shows_state(self.store)["episodes"][0]
        self.assertEqual((ep["seriesId"], ep["secs"]), ("7", 200))

    def test_bad_input(self):
        for items in ("x", [1], [{"seriesId": "", "epId": "1"}], [{"seriesId": "7", "epId": "1", "secs": -5}],
                      [{"seriesId": "7", "epId": "1", "secs": "a lot"}], [{}] * (PR.MAX_ITEMS + 1)):
            with self.assertRaises(ApiError):
                PR.push_episodes(self.store, items)

    def test_device_clock_ahead_counts_as_now(self):
        PR.push_episodes(self.store, [{"seriesId": "7", "epId": "101", "secs": 100, "dur": 2400, "at": (time.time() + 86400) * 1000}])
        at = PR.shows_state(self.store)["episodes"][0]["at"] / 1000
        self.assertLess(at, time.time() + 5)


if __name__ == "__main__":
    unittest.main()
