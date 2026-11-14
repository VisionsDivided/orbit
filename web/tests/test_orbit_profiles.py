# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""Orbit per-profile state (orbit/profilestate.py): My list / favourites, resume, show progress
and backups belong to one profile; the household's data so far goes to the owner at setup."""
import json
import os
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "server"))
sys.path.insert(0, HERE)
from orbit.profilestate import ProfileStores  # noqa: E402
from tvbro_server import series as SER  # noqa: E402
from tvbro_server.state import StateStore  # noqa: E402
from test_orbit_accounts import free_port  # noqa: E402

EPS = [{"id": "101", "season": 1, "num": 1, "duration": "00:40:00"},
       {"id": "102", "season": 1, "num": 2, "duration": "00:40:00"}]
P1, P2 = "aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb"


class Stores(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()
        self.house = StateStore(os.path.join(self.d, "state.db"))
        self.house_series = SER.SeriesStore(self.house, None)
        self.mode = {"orbit": False}
        self.ps = ProfileStores(self.d, self.house, self.house_series, lambda sid: ("Show", None, [dict(e) for e in EPS]),
                                lambda: self.mode["orbit"])

    def tearDown(self):
        self.ps.close()
        self.house.db.close()
        shutil.rmtree(self.d, ignore_errors=True)

    def test_tvbro_mode_uses_the_household(self):
        st, se = self.ps.for_session(None)
        self.assertIs(st, self.house)
        self.assertIs(se, self.house_series)

    def test_profiles_are_separate_files(self):
        self.mode["orbit"] = True
        a, _ = self.ps.for_session({"profile_id": P1})
        b, _ = self.ps.for_session({"profile_id": P2})
        a.put("mylist", "p0|ch1", {"key": "p0|ch1", "kind": "live"})
        self.assertEqual(len(a.all()["mylist"]), 1)
        self.assertEqual(b.all()["mylist"], [])
        self.assertEqual(self.house.all()["mylist"], [])
        self.assertTrue(os.path.exists(os.path.join(self.d, "profiles", P1, "state.db")))
        self.assertIs(self.ps.for_profile(P1)[0], a, "opened once, then reused")

    def test_show_progress_is_per_profile(self):
        self.mode["orbit"] = True
        _, sa = self.ps.for_profile(P1)
        _, sb = self.ps.for_profile(P2)
        sa.record("7", "101", 600, 2400, name="Show")
        self.assertEqual(len(sa.progress("7")), 1)
        self.assertEqual(sb.progress("7"), {})
        self.assertEqual(sb.continue_rows(), [])

    def test_orbit_mode_without_a_profile_is_refused(self):
        self.mode["orbit"] = True
        for sess in (None, {}, {"profile_id": None}):
            with self.assertRaises(PermissionError):
                self.ps.for_session(sess)
        for bad in ("../etc", "AAAAAAAAAAAAAAAA", "a" * 15, 5):
            with self.assertRaises(ValueError):
                self.ps.for_profile(bad)

    def test_adopt_copies_the_household_and_drop_removes(self):
        self.house.put("mylist", "m|p0|9", {"key": "m|p0|9", "kind": "movie"})
        self.house.put("resume", "m|p0|9", {"secs": 300, "dur": 5400})
        self.ps.adopt_household(P1)
        self.mode["orbit"] = True
        st, _ = self.ps.for_profile(P1)
        a = st.all()
        self.assertEqual([x["key"] for x in a["mylist"]], ["m|p0|9"])
        self.assertEqual(a["resume"]["m|p0|9"]["secs"], 300)
        self.assertEqual(len(self.house.all()["mylist"]), 1, "the household file is kept as it was")
        self.ps.drop(P1)
        self.assertFalse(os.path.exists(os.path.join(self.d, "profiles", P1)))


class Server(unittest.TestCase):
    """The real server: household data moves to the owner; profiles and accounts stay apart."""

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
                raw = f.read()
                return f.status, dict(f.headers), json.loads(raw or b"{}")
        except urllib.error.HTTPError as e:
            return e.code, dict(e.headers), json.loads(e.read() or b"{}")

    def as_(self, who, method, path, body=None):
        return self.req(method, path, body, {"Cookie": who[0], "X-CSRF-Token": who[1]})

    def sign_in(self, path, body):
        code, hdr, j = self.req("POST", path, body, {"X-TvBro": "1"})
        self.assertEqual(code, 200, j)
        return hdr.get("Set-Cookie", "").split(";", 1)[0], j["csrf"]

    def mylist(self, who):
        code, _, j = self.as_(who, "GET", "/api/state")
        self.assertEqual(code, 200, j)
        return [x["key"] for x in j["mylist"]]

    def fav(self, who, key, kind="live"):
        code, _, j = self.as_(who, "POST", "/api/state/mylist", {"key": key, "value": {"key": key, "kind": kind}})
        self.assertEqual(code, 200, j)

    def test_flow(self):
        # tvBro mode: the household's My list (with a favourite channel) and a resume point
        _, hdr, j = self.req("GET", "/api/session")
        house = (hdr.get("Set-Cookie", "").split(";", 1)[0], j["csrf"])
        self.fav(house, "p0|ch1")
        self.as_(house, "POST", "/api/state/resume", {"key": "m|p0|9", "value": {"secs": 300, "dur": 5400}})

        # setup: the owner's first profile starts with that data
        with open(os.path.join(self.data, "orbit-setup-token"), encoding="utf-8") as f:
            token = f.read()
        alex = self.sign_in("/v1/setup", {"token": token, "username": "alex", "password": "correct horse 1"})
        self.assertEqual(self.mylist(alex), ["p0|ch1"])
        self.assertEqual(self.as_(alex, "GET", "/api/state")[2]["resume"]["m|p0|9"]["secs"], 300)

        # a Kids profile starts empty and keeps its own favourites
        _, _, kids = self.as_(alex, "POST", "/v1/profiles", {"name": "Kids", "kids": True})
        main_id = self.as_(alex, "GET", "/v1/me")[2]["profile"]["id"]
        self.assertEqual(self.as_(alex, "POST", "/v1/profiles/select", {"id": kids["id"]})[0], 200)
        self.assertEqual(self.mylist(alex), [])
        self.fav(alex, "p0|cartoons")
        self.as_(alex, "POST", "/v1/profiles/select", {"id": main_id})
        self.assertEqual(self.mylist(alex), ["p0|ch1"])

        # another device of the same account picks its own profile
        alex_tv = self.sign_in("/v1/auth/login", {"username": "alex", "password": "correct horse 1", "device": "TV"})
        self.as_(alex_tv, "POST", "/v1/profiles/select", {"id": kids["id"]})
        self.assertEqual(self.mylist(alex_tv), ["p0|cartoons"])
        self.assertEqual(self.mylist(alex), ["p0|ch1"])

        # another account sees none of it, and Alex sees none of Sam's
        self.as_(alex, "POST", "/v1/accounts", {"username": "sam", "password": "another pass 2"})
        sam = self.sign_in("/v1/auth/login", {"username": "sam", "password": "another pass 2"})
        self.assertEqual(self.mylist(sam), [])
        self.assertNotIn("m|p0|9", self.as_(sam, "GET", "/api/state")[2]["resume"])
        self.fav(sam, "p0|sport")
        self.assertEqual(self.mylist(sam), ["p0|sport"])
        self.assertEqual(self.mylist(alex), ["p0|ch1"])
        self.assertEqual(self.as_(sam, "GET", "/api/series/continue")[2]["items"], [])
        # clearing your list clears only yours
        self.as_(sam, "POST", "/api/state/mylist", {"op": "clear"})
        self.assertEqual(self.mylist(alex), ["p0|ch1"])

        # the owner's backup holds the owner's current profile, not Sam's or Kids'
        code, _, bk = self.as_(alex, "GET", "/api/backup")
        self.assertEqual(code, 200)
        raw = json.dumps(bk)
        self.assertIn("p0|ch1", raw)
        self.assertNotIn("p0|sport", raw)
        self.assertNotIn("p0|cartoons", raw)

        # deleting a profile deletes its data; a device on it falls back to the first profile
        self.assertEqual(self.as_(alex, "POST", "/v1/profiles/delete", {"id": kids["id"]})[0], 200)
        self.assertFalse(os.path.exists(os.path.join(self.data, "profiles", kids["id"])))
        self.assertEqual(self.as_(alex_tv, "GET", "/v1/me")[2]["profile"]["id"], main_id)
        self.assertEqual(self.mylist(alex_tv), ["p0|ch1"])

    def test_session_without_a_valid_profile_is_told_so(self):
        """A session whose profile is no longer there (stale data): /v1/me says profile null, the profile routes answer
        409 with auth "profile" (the page then shows "Who's watching?"), never a 500; selecting a profile fixes it."""
        tok = os.path.join(self.data, "orbit-setup-token")
        if os.path.exists(tok):
            with open(tok, encoding="utf-8") as f:
                self.sign_in("/v1/setup", {"token": f.read(), "username": "alex", "password": "correct horse 1"})
        who = self.sign_in("/v1/auth/login", {"username": "alex", "password": "correct horse 1", "device": "stale"})
        con = sqlite3.connect(os.path.join(self.data, "orbit.db"))
        con.execute("UPDATE account_sessions SET profile_id='gone' WHERE device='stale'")
        con.commit()
        con.close()
        time.sleep(5.5)                             # past the server's short per-session cache
        me = self.as_(who, "GET", "/v1/me")[2]
        self.assertIsNone(me["profile"])
        code, _, j = self.as_(who, "GET", "/api/state")
        self.assertEqual((code, j.get("auth")), (409, "profile"), j)
        code, _, j = self.as_(who, "POST", "/api/state/mylist", {"key": "p0|x", "value": {"key": "p0|x", "kind": "live"}})
        self.assertEqual((code, j.get("auth")), (409, "profile"), j)
        self.assertEqual(self.as_(who, "POST", "/v1/profiles/select", {"id": me["profiles"][0]["id"]})[0], 200)
        self.assertEqual(self.as_(who, "GET", "/v1/me")[2]["profile"]["id"], me["profiles"][0]["id"])
        self.assertEqual(self.as_(who, "GET", "/api/state")[0], 200)


if __name__ == "__main__":
    unittest.main()
