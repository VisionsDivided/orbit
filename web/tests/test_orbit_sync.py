# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""Orbit sync for devices (orbit/sync.py): the profile-state revision (download only when changed) and batches
applied in one transaction."""
import json
import os
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "server"))
sys.path.insert(0, HERE)
from orbit import sync as SY  # noqa: E402
from orbit import api as V1  # noqa: E402
from orbit.accounts import ApiError  # noqa: E402
from tvbro_server import series as SER  # noqa: E402
from tvbro_server.state import StateStore  # noqa: E402
from test_orbit_accounts import free_port  # noqa: E402

EPS = [{"id": "101", "season": 1, "num": 1, "title": "One", "duration": "00:40:00"},
       {"id": "102", "season": 1, "num": 2, "title": "Two", "duration": "00:40:00"}]


def join_recompute():
    for t in threading.enumerate():
        if t.name in ("orbit-series-recompute", "series-recompute"):
            t.join(10)


class Revision(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()
        self.path = os.path.join(self.d, "state.db")
        self.st = StateStore(self.path)
        self.se = SER.SeriesStore(self.st, lambda sid: ("Show", None, [dict(e) for e in EPS]))

    def tearDown(self):
        join_recompute()
        with self.st._lock:
            self.st.db.close()
        shutil.rmtree(self.d, ignore_errors=True)

    def test_same_revision_until_something_is_written(self):
        a = SY.state(self.st, self.se)
        self.assertTrue(a["changed"])
        self.assertIn("state", a)
        self.assertIn("progress", a)
        b = SY.state(self.st, self.se, since=a["rev"])
        self.assertEqual(b, {"rev": a["rev"], "changed": False}, "nothing written: only the revision")
        self.st.all()                                   # reads change nothing
        SY.state(self.st, self.se)
        self.assertEqual(SY.rev(self.st), a["rev"])

    def test_every_kind_of_write_changes_it(self):
        writes = [
            lambda: self.st.put("mylist", "p0|1", {"key": "p0|1"}),
            lambda: self.st.put("mylist", "p0|1", {"key": "p0|1"}),          # same value again: still counted
            lambda: self.st.delete("mylist", "p0|1"),
            lambda: self.st.put("resume", "m|p0|9", {"secs": 300, "dur": 5400, "at": 1}),
            lambda: self.st.clear("resume"),
            lambda: self.se.record("7", "101", 600, 2400, name="Show"),
            lambda: self.se.hide("7"),
            lambda: SY.batch(self.st, self.se, {"state": [{"op": "put", "scope": "prefs", "key": "x", "value": 1}]}),
        ]
        seen = {SY.rev(self.st)}
        for w in writes:
            w()
            r = SY.rev(self.st)
            self.assertNotIn(r, seen)
            seen.add(r)

    def test_a_write_from_another_connection_changes_it(self):
        r = SY.rev(self.st)
        other = sqlite3.connect(self.path)
        other.execute("INSERT INTO kv(scope, key, value, updated) VALUES ('mylist', 'p0|2', '{}', 1)")
        other.commit()
        other.close()
        self.assertNotEqual(SY.rev(self.st), r)

    def test_reopening_the_file_changes_it(self):
        r = SY.rev(self.st)
        st2 = StateStore(self.path)                   # a server restart opens the file again
        try:
            self.assertNotEqual(SY.rev(st2), r)
        finally:
            st2.db.close()

    def test_bad_since_is_a_full_answer(self):
        for since in (None, "", "x", 5, ["a"]):
            self.assertTrue(SY.state(self.st, self.se, since=since)["changed"])


class Batch(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()
        self.st = StateStore(os.path.join(self.d, "state.db"))
        self.se = SER.SeriesStore(self.st, lambda sid: ("Show", None, [dict(e) for e in EPS]))

    def tearDown(self):
        join_recompute()
        with self.st._lock:
            self.st.db.close()
        shutil.rmtree(self.d, ignore_errors=True)

    def test_state_and_episodes_in_one_call(self):
        self.st.put("mylist", "p0|old", {"key": "p0|old"})
        now = time.time() * 1000
        r = SY.batch(self.st, self.se, {
            "state": [
                {"op": "put", "scope": "mylist", "key": "p0|1", "value": {"key": "p0|1", "kind": "live"}},
                {"op": "put", "scope": "mylist", "key": "m|p0|9", "value": {"key": "m|p0|9", "kind": "movie"}},
                {"op": "del", "scope": "mylist", "key": "p0|old"},
                {"scope": "resume", "key": "m|p0|9", "value": {"secs": 300, "dur": 5400, "at": int(now)}},   # op defaults to put
            ],
            "episodes": [{"seriesId": "7", "epId": "101", "secs": 600, "dur": 2400, "done": False, "at": now, "name": "Show"}],
        })
        self.assertEqual(r, {"state": 4, "episodes": 1, "shows": 1})
        a = self.st.all()
        self.assertEqual([x["key"] for x in a["mylist"]], ["m|p0|9", "p0|1"], "newest first, the deleted one gone")
        self.assertEqual(a["resume"]["m|p0|9"]["secs"], 300)
        self.assertEqual(self.se.progress("7")["101"]["secs"], 600)

    def test_ops_apply_in_order(self):
        SY.batch(self.st, self.se, {"state": [
            {"op": "put", "scope": "mylist", "key": "p0|1", "value": {"key": "p0|1"}},
            {"op": "del", "scope": "mylist", "key": "p0|1"},
            {"op": "put", "scope": "mylist", "key": "p0|2", "value": {"key": "p0|2"}},
        ]})
        self.assertEqual([x["key"] for x in self.st.all()["mylist"]], ["p0|2"])

    def test_one_bad_change_applies_nothing(self):
        good = {"op": "put", "scope": "mylist", "key": "p0|1", "value": {"key": "p0|1"}}
        ep = {"seriesId": "7", "epId": "101", "secs": 600, "dur": 2400, "at": time.time() * 1000}
        bad_bodies = [
            {"state": [good, {"op": "put", "scope": "nope", "key": "k", "value": 1}]},
            {"state": [good, {"op": "put", "scope": "mylist", "key": "", "value": 1}]},
            {"state": [good, {"op": "put", "scope": "mylist", "key": "k" * 301, "value": 1}]},
            {"state": [good, {"op": "put", "scope": "mylist", "key": 5, "value": 1}]},
            {"state": [good, {"op": "put", "scope": "mylist", "key": "big", "value": "x" * 20000}]},
            {"state": [good, {"op": "clear", "scope": "mylist", "key": "k"}]},
            {"state": [good, "not an object"]},
            {"state": "not a list"},
            {"state": [good], "episodes": [ep, {"seriesId": "", "epId": "102"}]},
            {"state": [good], "episodes": "not a list"},
            {"state": [good] * (SY.MAX_OPS + 1)},
            {"state": [good], "episodes": [ep] * 501},
        ]
        before = SY.rev(self.st)
        for body in bad_bodies:
            with self.assertRaises(ApiError) as e:
                SY.batch(self.st, self.se, body)
            self.assertEqual(e.exception.code, 400)
        self.assertEqual(self.st.all()["mylist"], [])
        self.assertEqual(self.se.progress("7"), {})
        self.assertEqual(SY.rev(self.st), before, "nothing was written")

    def test_a_failure_while_applying_rolls_everything_back(self):
        good = {"op": "put", "scope": "mylist", "key": "p0|1", "value": {"key": "p0|1"}}
        ep = {"seriesId": "7", "epId": "101", "secs": 600, "dur": 2400, "at": time.time() * 1000}
        real = SY.PROG._apply

        def boom(*a, **k):
            raise RuntimeError("disk full")
        SY.PROG._apply = boom
        try:
            with self.assertRaises(RuntimeError):
                SY.batch(self.st, self.se, {"state": [good], "episodes": [ep]})
        finally:
            SY.PROG._apply = real
        self.assertEqual(self.st.all()["mylist"], [], "the state change was rolled back with the episodes")
        SY.batch(self.st, self.se, {"state": [good]})             # the connection is usable again
        self.assertEqual(len(self.st.all()["mylist"]), 1)

    def test_empty_batch_is_fine_and_scopes_stay_bounded(self):
        self.assertEqual(SY.batch(self.st, self.se, {}), {"state": 0, "episodes": 0, "shows": 0})
        from tvbro_server import state as STATE
        ops = [{"op": "put", "scope": "history", "key": f"q{i}", "value": f"q{i}"} for i in range(STATE.SCOPES["history"] + 5)]
        SY.batch(self.st, self.se, {"state": ops})
        hist = self.st.all()["history"]
        self.assertEqual(len(hist), STATE.SCOPES["history"])
        self.assertEqual(hist[0], f"q{len(ops) - 1}", "the newest are kept")

    def test_older_episode_changes_are_skipped_as_before(self):
        now = time.time() * 1000
        SY.batch(self.st, self.se, {"episodes": [{"seriesId": "7", "epId": "101", "secs": 900, "dur": 2400, "at": now}]})
        r = SY.batch(self.st, self.se, {"episodes": [{"seriesId": "7", "epId": "101", "secs": 100, "dur": 2400, "at": now - 60000}]})
        self.assertEqual(r["episodes"], 0)
        self.assertEqual(self.se.progress("7")["101"]["secs"], 900)


class Routes(unittest.TestCase):
    """Mounted into the /v1 route table: signed in only, the session's profile only."""

    class _Pair:
        def tick(self):
            pass

    def setUp(self):
        self.d = tempfile.mkdtemp()
        self.st = StateStore(os.path.join(self.d, "state.db"))
        self.se = SER.SeriesStore(self.st, None)

        def stores(sess):
            if not sess.get("profile_id"):
                raise PermissionError("no profile selected")
            return self.st, self.se
        self.api = V1.Api(accounts=None, pairings=self._Pair())
        self.api.routes.update(SY.routes(stores))
        self.sess = {"account_id": "a1", "profile_id": "p1"}

    def tearDown(self):
        join_recompute()
        with self.st._lock:
            self.st.db.close()
        shutil.rmtree(self.d, ignore_errors=True)

    def call(self, method, path, sess, body=None, query=None):
        return self.api.handle(V1.Call(method, path, sess, body or {}, "192.0.2.1", query or {}))

    def test_routes(self):
        self.assertEqual(self.call("GET", "/v1/sync/state", None).code, 401)
        self.assertEqual(self.call("POST", "/v1/sync/batch", None, {"state": []}).code, 401)
        self.assertEqual(self.call("GET", "/v1/sync/state", {"account_id": "a1"}).code, 409)
        r = self.call("POST", "/v1/sync/batch", self.sess,
                      {"state": [{"op": "put", "scope": "mylist", "key": "p0|1", "value": {"key": "p0|1"}}]})
        self.assertEqual((r.code, r.obj["state"]), (200, 1))
        full = self.call("GET", "/v1/sync/state", self.sess).obj
        self.assertEqual([x["key"] for x in full["state"]["mylist"]], ["p0|1"])
        same = self.call("GET", "/v1/sync/state", self.sess, query={"since": full["rev"]})
        self.assertEqual(same.obj, {"rev": full["rev"], "changed": False})
        bad = self.call("POST", "/v1/sync/batch", self.sess, {"state": [{"scope": "nope", "key": "k"}]})
        self.assertEqual(bad.code, 400)
        self.assertIn("state[0]", bad.obj["error"])


class Server(unittest.TestCase):
    """The real server: the routes are mounted and the old routes still work (old TVs)."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp()
        cls.data = os.path.join(cls.tmp, "data")
        cls.port = free_port()
        env = dict(os.environ, TVBRO_PORT=str(cls.port), TVBRO_DATA=cls.data, PYTHONDONTWRITEBYTECODE="1")
        env.pop("TVBRO_KEY", None)
        cls.log = open(os.path.join(cls.tmp, "server.log"), "wb")
        cls.proc = subprocess.Popen([sys.executable, "run.py"], cwd=os.path.join(HERE, "..", "server"),
                                    env=env, stdout=cls.log, stderr=subprocess.STDOUT)
        cls.base = "http://127.0.0.1:%d" % cls.port
        for _ in range(150):
            try:
                cls.req("GET", "/api/health")
                break
            except Exception:                       # noqa: BLE001
                time.sleep(0.1)

    @classmethod
    def tearDownClass(cls):
        cls.proc.terminate()
        try:
            cls.proc.wait(10)
        except subprocess.TimeoutExpired:
            cls.proc.kill()
        cls.log.close()
        shutil.rmtree(cls.tmp, ignore_errors=True)

    @classmethod
    def req(cls, method, path, body=None, headers=None):
        h = {"Content-Type": "application/json"} if body is not None else {}
        h.update(headers or {})
        data = json.dumps(body).encode() if body is not None else None
        r = urllib.request.Request(cls.base + path, data=data, method=method, headers=h)
        try:
            with urllib.request.urlopen(r, timeout=60) as f:
                return f.status, json.loads(f.read() or b"{}")
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read() or b"{}")

    def test_flow(self):
        with open(os.path.join(self.data, "orbit-setup-token"), encoding="utf-8") as f:
            token = f.read()
        r = urllib.request.Request(self.base + "/v1/setup", method="POST",
                                   data=json.dumps({"token": token, "username": "alex", "password": "correct horse 1"}).encode(),
                                   headers={"Content-Type": "application/json", "X-TvBro": "1"})
        with urllib.request.urlopen(r, timeout=60) as f:
            cookie = f.headers.get("Set-Cookie", "").split(";", 1)[0]
            csrf = json.loads(f.read())["csrf"]
        me = {"Cookie": cookie, "X-CSRF-Token": csrf}

        code, full = self.req("GET", "/v1/sync/state", headers=me)
        self.assertEqual(code, 200, full)
        code, same = self.req("GET", "/v1/sync/state?since=" + full["rev"], headers=me)
        self.assertEqual(same, {"rev": full["rev"], "changed": False})

        code, j = self.req("POST", "/v1/sync/batch", {"state": [
            {"op": "put", "scope": "mylist", "key": "p0|1", "value": {"key": "p0|1", "kind": "live"}},
            {"op": "put", "scope": "mylist", "key": "p0|2", "value": {"key": "p0|2", "kind": "live"}}]}, headers=me)
        self.assertEqual((code, j["state"]), (200, 2), j)
        code, now = self.req("GET", "/v1/sync/state?since=" + full["rev"], headers=me)
        self.assertTrue(now["changed"])
        self.assertEqual(sorted(x["key"] for x in now["state"]["mylist"]), ["p0|1", "p0|2"])

        # the single-item route of older TVs still works and moves the revision too
        code, _ = self.req("POST", "/api/state/mylist", {"op": "del", "key": "p0|1"}, headers=me)
        self.assertEqual(code, 200)
        code, after = self.req("GET", "/v1/sync/state?since=" + now["rev"], headers=me)
        self.assertTrue(after["changed"])
        self.assertEqual([x["key"] for x in after["state"]["mylist"]], ["p0|2"])
        code, old = self.req("GET", "/api/state", headers=me)
        self.assertEqual([x["key"] for x in old["mylist"]], ["p0|2"])

        # no CSRF token: refused like every other write
        code, _ = self.req("POST", "/v1/sync/batch", {"state": []}, headers={"Cookie": cookie})
        self.assertEqual(code, 403)


if __name__ == "__main__":
    unittest.main()
