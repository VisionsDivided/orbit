# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""Orbit TV pairing (orbit/pairing.py): a short code on the TV, approved by a signed-in person."""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.error
import urllib.request
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "server"))
sys.path.insert(0, HERE)
from orbit import pairing as PA  # noqa: E402
from orbit.accounts import Accounts, ApiError  # noqa: E402
from tvbro_server import auth as AU  # noqa: E402
from test_orbit_accounts import free_port  # noqa: E402


class Clock:
    def __init__(self):
        self.t = 1_000_000.0

    def __call__(self):
        return self.t


@mock.patch("tvbro_server.auth.ITERATIONS", 1000)
class Unit(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()
        self.clock = Clock()
        self.auth = AU.Auth(self.d, b"k" * 32, self.clock)
        self.acc = Accounts(self.d, self.auth, self.clock)
        with open(os.path.join(self.d, "orbit-setup-token"), encoding="utf-8") as f:
            sid = self.acc.setup(f.read(), "alex", "correct horse 1")
        self.alex = self.acc.gate(self.auth.session(sid))
        self.pr = PA.Pairings(self.acc, self.clock)

    def test_codes_are_readable(self):
        code = self.pr.start("TV", "192.0.2.5")["code"]
        self.assertRegex(code, r"^[BCDFGHJKLMNPQRSTVWXZ2-9]{4}-[BCDFGHJKLMNPQRSTVWXZ2-9]{4}$")
        self.assertEqual(PA.normalize(" " + code.lower().replace("-", " ") + " "), code.replace("-", ""))

    def test_approve_then_collect_once(self):
        p = self.pr.start("Living room TV", "192.0.2.5")
        self.assertEqual(self.pr.poll(p["pairId"])["status"], "pending")
        kids = self.acc.add_profile(self.alex["account_id"], "Kids", True)
        self.pr.approve(self.alex, p["code"].lower(), kids["id"], "192.0.2.9")
        got = self.pr.poll(p["pairId"])
        self.assertEqual(got["status"], "approved")
        self.assertEqual(got["me"]["profile"]["id"], kids["id"])
        tv = self.acc.gate(self.auth.session(got["session"]))
        self.assertEqual((tv["account_id"], tv["profile_id"]), (self.alex["account_id"], kids["id"]))
        self.assertIn("Living room TV", [s["device"] for s in self.acc.sessions(self.alex)])
        with self.assertRaises(ApiError):
            self.pr.poll(p["pairId"])                       # only once

    def test_expiry_and_an_uncollected_session_ends(self):
        p = self.pr.start("TV", "192.0.2.5")
        self.clock.t += PA.TTL + 1
        with self.assertRaises(ApiError):
            self.pr.poll(p["pairId"])
        with self.assertRaises(ApiError):
            self.pr.approve(self.alex, p["code"], None, "192.0.2.9")
        q = self.pr.start("TV", "192.0.2.5")
        self.pr.approve(self.alex, q["code"], None, "192.0.2.9")
        before = len(self.auth.sessions)
        self.clock.t += PA.TTL + 1
        self.pr.start("Other", "192.0.2.6")                 # any call tidies up
        self.assertEqual(len(self.auth.sessions), before - 1)

    def test_wrong_codes_are_slowed_down_and_profiles_are_checked(self):
        p = self.pr.start("TV", "192.0.2.5")
        for _ in range(5):
            with self.assertRaises(ApiError) as e:
                self.pr.approve(self.alex, "BBBB-BBBB", None, "192.0.2.66")
            self.assertEqual(e.exception.code, 404)
        with self.assertRaises(ApiError) as e:
            self.pr.approve(self.alex, p["code"], None, "192.0.2.66")
        self.assertEqual(e.exception.code, 429)
        with self.assertRaises(ApiError) as e:
            self.pr.approve(self.alex, p["code"], "ffffffffffffffff", "192.0.2.9")
        self.assertEqual(e.exception.code, 404, "not your profile")

    def test_tv_sessions_and_playlists(self):
        from orbit.api import Api, Call
        p = self.pr.start("TV", "192.0.2.5")
        self.pr.approve(self.alex, p["code"], None, "192.0.2.9")
        tv = self.acc.gate(self.auth.session(self.pr.poll(p["pairId"])["session"]))
        self.assertEqual((tv["session_kind"], self.alex["session_kind"]), ("tv", "web"))
        pl = [{"id": "p0", "name": "Main", "kind": "xtream", "server": "http://tv.example.com", "user": "u", "pass": "x"}]
        api = Api(self.acc, self.pr, playlists=lambda: pl)
        self.assertEqual(api.handle(Call("GET", "/v1/device/playlists", tv)).obj, {"playlists": pl})
        self.assertEqual(api.handle(Call("GET", "/v1/device/playlists", self.alex)).code, 403)

    def test_limits(self):
        for _ in range(PA.MAX_PER_CLIENT):
            self.pr.start("TV", "192.0.2.5")
        with self.assertRaises(ApiError) as e:
            self.pr.start("TV", "192.0.2.5")
        self.assertEqual(e.exception.code, 429)


class Server(unittest.TestCase):
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
                return f.status, dict(f.headers), json.loads(f.read() or b"{}")
        except urllib.error.HTTPError as e:
            return e.code, dict(e.headers), json.loads(e.read() or b"{}")

    def tv(self, path, body):                        # what the Android app sends: JSON + X-TvBro, no cookie, no Origin
        return self.req("POST", path, body, {"X-TvBro": "1"})

    def test_flow(self):
        self.assertEqual(self.tv("/v1/pair/start", {"device": "TV"})[0], 409, "no accounts yet")
        with open(os.path.join(self.data, "orbit-setup-token"), encoding="utf-8") as f:
            token = f.read()
        code, hdr, me = self.tv("/v1/setup", {"token": token, "username": "alex", "password": "correct horse 1"})
        alex = (hdr["Set-Cookie"].split(";", 1)[0], me["csrf"])
        auth = lambda who: {"Cookie": who[0], "X-CSRF-Token": who[1]}   # noqa: E731
        _, _, kids = self.req("POST", "/v1/profiles", {"name": "Kids", "kids": True}, auth(alex))

        # the TV starts; without X-TvBro nobody else's page can start one
        self.assertEqual(self.req("POST", "/v1/pair/start", {"device": "TV"})[0], 403)
        code, _, p = self.tv("/v1/pair/start", {"device": "Living room TV"})
        self.assertEqual(code, 200)
        self.assertNotIn("Set-Cookie", self.tv("/v1/pair/poll", {"pairId": p["pairId"]})[1])
        self.assertEqual(self.tv("/v1/pair/poll", {"pairId": p["pairId"]})[2]["status"], "pending")

        # approving needs a session and CSRF; a second account cannot use Alex's profile
        self.assertEqual(self.req("POST", "/v1/pair/approve", {"code": p["code"]})[0], 401)
        self.assertEqual(self.req("POST", "/v1/pair/approve", {"code": p["code"]}, {"Cookie": alex[0]})[0], 403)
        self.req("POST", "/v1/accounts", {"username": "sam", "password": "another pass 2"}, auth(alex))
        _, h2, sme = self.tv("/v1/auth/login", {"username": "sam", "password": "another pass 2"})
        sam = (h2["Set-Cookie"].split(";", 1)[0], sme["csrf"])
        self.assertEqual(self.req("POST", "/v1/pair/approve", {"code": p["code"], "profileId": kids["id"]}, auth(sam))[0], 404)

        code, _, j = self.req("POST", "/v1/pair/approve", {"code": p["code"], "profileId": kids["id"]}, auth(alex))
        self.assertEqual((code, j["device"]), (200, "Living room TV"))
        code, _, got = self.tv("/v1/pair/poll", {"pairId": p["pairId"]})
        self.assertEqual((code, got["status"], got["me"]["profile"]["name"]), (200, "approved", "Kids"))
        self.assertEqual(self.tv("/v1/pair/poll", {"pairId": p["pairId"]})[0], 404, "collected once")

        # the TV now uses its session like any device: reads and writes its profile's state
        tvs = ("tvbro_s=" + got["session"], got["csrf"])
        self.assertEqual(self.req("GET", "/v1/me", headers=auth(tvs))[2]["profile"]["name"], "Kids")
        self.assertEqual(self.req("POST", "/api/state/mylist", {"key": "p0|ch9", "value": {"key": "p0|ch9", "kind": "live"}},
                                  auth(tvs))[0], 200)
        self.assertEqual([x["key"] for x in self.req("GET", "/api/state", headers=auth(tvs))[2]["mylist"]], ["p0|ch9"])

        # only the linked TV may fetch the playlists (logins for direct playback); a browser may not
        code, _, pl = self.req("GET", "/v1/device/playlists", headers=auth(tvs))
        self.assertEqual((code, pl), (200, {"playlists": []}))
        self.assertEqual(self.req("GET", "/v1/device/playlists", headers=auth(alex))[0], 403)
        self.assertEqual(self.req("GET", "/v1/device/playlists")[0], 401)
        # show progress of the TV's profile (episode sync)
        self.assertEqual(self.req("GET", "/v1/progress/shows", headers=auth(tvs))[2], {"episodes": [], "shows": []})
        code, _, r = self.req("POST", "/v1/progress/episodes", {"items": [{"seriesId": "7", "epId": "101", "secs": 120, "dur": 2400,
                                                                          "at": int(time.time() * 1000)}]}, auth(tvs))
        self.assertEqual((code, r["applied"]), (200, 1))
        self.assertEqual(self.req("GET", "/v1/progress/shows", headers=auth(tvs))[2]["episodes"][0]["secs"], 120)

        # and it can be signed out from Alex's devices list
        dev = next(s for s in self.req("GET", "/v1/sessions", headers=auth(alex))[2]["sessions"] if s["device"] == "Living room TV")
        self.assertEqual(self.req("POST", "/v1/sessions/revoke", {"id": dev["id"]}, auth(alex))[0], 200)
        self.assertEqual(self.req("GET", "/v1/me", headers=auth(tvs))[0], 401)


if __name__ == "__main__":
    unittest.main()
