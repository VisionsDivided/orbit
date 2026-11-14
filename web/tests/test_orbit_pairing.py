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

    def test_desktop_sign_in_is_a_device_session(self):
        """The desktop app signs in with its account ("kind": "desktop"): playlists like a linked TV, viewer role."""
        from orbit.api import Api, Call
        pl = [{"id": "p0", "name": "Main", "kind": "xtream", "server": "http://tv.example.com", "user": "u", "pass": "x"}]
        api = Api(self.acc, self.pr, playlists=lambda: pl)
        body = {"username": "alex", "password": "correct horse 1", "device": "Orbit on Windows", "kind": "desktop"}
        r = api.handle(Call("POST", "/v1/auth/login", None, body, "192.0.2.5"))
        self.assertEqual(r.code, 200)
        self.assertEqual(r.obj["account"]["username"], "alex")
        desk = self.acc.gate(self.auth.session(r.session))
        self.assertEqual((desk["session_kind"], desk["role"]), ("desktop", "viewer"), "never the owner's admin role")
        self.assertEqual(api.handle(Call("GET", "/v1/device/playlists", desk)).obj, {"playlists": pl})
        self.assertIn("Orbit on Windows", [s["device"] for s in self.acc.sessions(self.alex)])
        # any other kind (or none) is a browser sign-in: no playlists; "tv" cannot be claimed without a pairing code
        for kind in (None, "web", "tv", "admin"):
            r = api.handle(Call("POST", "/v1/auth/login", None, dict(body, kind=kind), "192.0.2.5"))
            web = self.acc.gate(self.auth.session(r.session))
            self.assertEqual(web["session_kind"], "web", kind)
            self.assertEqual(api.handle(Call("GET", "/v1/device/playlists", web)).code, 403, kind)

    def test_limits(self):
        self.assertLessEqual(PA.MAX_PER_CLIENT, 3)
        self.assertLessEqual(PA.MAX_PENDING, 20)
        for _ in range(PA.MAX_PER_CLIENT):
            self.pr.start("TV", "192.0.2.5")
        with self.assertRaises(ApiError) as e:
            self.pr.start("TV", "192.0.2.5")
        self.assertEqual(e.exception.code, 429)
        self.assertIn("pairing codes waiting", e.exception.msg)
        self.assertEqual(e.exception.retry_after, PA.TTL + 1)       # when its oldest code expires
        self.pr.start("TV", "192.0.2.6")                          # another address still may

    def test_global_cap(self):
        n = 0
        while n < PA.MAX_PENDING:
            for _ in range(min(PA.MAX_PER_CLIENT, PA.MAX_PENDING - n)):
                self.pr.start("TV", "192.0.2.%d" % (10 + n // PA.MAX_PER_CLIENT))
                n += 1
        self.clock.t += 30
        with self.assertRaises(ApiError) as e:
            self.pr.start("TV", "192.0.2.200")                    # a new address, but the server is full
        self.assertEqual(e.exception.code, 429)
        self.assertIn("too many TVs are waiting", e.exception.msg)
        self.assertEqual(e.exception.retry_after, PA.TTL - 30 + 1)
        self.clock.t += PA.TTL                                    # all expired: room again
        self.pr.start("TV", "192.0.2.200")

    def test_cap_over_http_is_429_with_retry_after(self):
        from orbit.api import Api, Call
        api = Api(self.acc, self.pr)
        for _ in range(PA.MAX_PER_CLIENT):
            self.assertEqual(api.handle(Call("POST", "/v1/pair/start", None, {"device": "TV"}, "192.0.2.7")).code, 200)
        r = api.handle(Call("POST", "/v1/pair/start", None, {"device": "TV"}, "192.0.2.7"))
        self.assertEqual(r.code, 429)
        self.assertIn("error", r.obj)
        self.assertEqual(r.headers.get("Retry-After"), str(PA.TTL + 1))


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
        # the desktop app signs in with the account (X-TvBro, no Origin) and is a device like the TV
        code, hd, dme = self.tv("/v1/auth/login", {"username": "alex", "password": "correct horse 1",
                                                    "device": "Orbit on Windows", "kind": "desktop"})
        self.assertEqual(code, 200)
        desk = (hd["Set-Cookie"].split(";", 1)[0], dme["csrf"])
        self.assertEqual(self.req("GET", "/v1/device/playlists", headers=auth(desk))[:3:2], (200, {"playlists": []}))
        self.assertEqual(self.req("POST", "/v1/profiles/select", {"id": kids["id"]}, auth(desk))[2]["profile"]["name"], "Kids")
        # show progress of the TV's profile (episode sync)
        self.assertEqual(self.req("GET", "/v1/progress/shows", headers=auth(tvs))[2], {"episodes": [], "shows": []})
        code, _, r = self.req("POST", "/v1/progress/episodes", {"items": [{"seriesId": "7", "epId": "101", "secs": 120, "dur": 2400,
                                                                          "at": int(time.time() * 1000)}]}, auth(tvs))
        self.assertEqual((code, r["applied"]), (200, 1))
        self.assertEqual(self.req("GET", "/v1/progress/shows", headers=auth(tvs))[2]["episodes"][0]["secs"], 120)

        # one stream count for TVs and browsers (tvBro's StreamRegistry): the TV checks, starts, keeps it alive
        st = self.req("GET", "/v1/streams", headers=auth(tvs))[2]
        self.assertEqual((st["max"], st["count"]), (1, 0))
        tv_sid = "tv" + "a" * 30
        self.assertEqual(self.req("POST", "/api/stream/start", {"sid": tv_sid, "label": "Living room TV", "what": "News"}, auth(tvs))[2]["ok"], True)
        st = self.req("GET", "/v1/streams", headers=auth(tvs))[2]
        self.assertEqual((st["count"], st["active"][0]["label"], st["active"][0]["what"]), (1, "Living room TV", "News"))
        # another device takes the only slot: the TV's next heartbeat says it was stopped
        self.req("POST", "/api/stream/start", {"sid": "web" + "b" * 29, "label": "Chrome", "what": "Film"}, auth(alex))
        self.assertIn("stopped", self.req("POST", "/api/stream/heartbeat", {"sid": tv_sid}, auth(tvs))[2])
        # the TV picks which stream to stop: every stream has a public id and the profile that started it
        st = self.req("GET", "/v1/streams", headers=auth(tvs))[2]
        web = st["active"][0]
        self.assertEqual((web["label"], web["what"]), ("Chrome", "Film"))
        self.assertTrue(web["profile"])                        # Alex's profile name
        self.assertNotIn(web["id"], "web" + "b" * 29)
        # a stale choice (that stream is gone, every slot still taken) changes nothing and returns the current list
        r = self.req("POST", "/api/stream/start", {"sid": tv_sid, "label": "Living room TV", "what": "News", "replace": "0" * 12}, auth(tvs))[2]
        self.assertEqual((r["ok"], r["changed"], r["streams"]["active"][0]["id"]), (False, True, web["id"]))
        r = self.req("POST", "/api/stream/start", {"sid": tv_sid, "label": "Living room TV", "what": "News", "replace": web["id"]}, auth(tvs))[2]
        self.assertEqual((r["ok"], r["replaced"]), (True, 1))
        self.assertIn("stopped", self.req("POST", "/api/stream/heartbeat", {"sid": "web" + "b" * 29}, auth(alex))[2])

        # and it can be signed out from Alex's devices list
        dev = next(s for s in self.req("GET", "/v1/sessions", headers=auth(alex))[2]["sessions"] if s["device"] == "Living room TV")
        self.assertEqual(self.req("POST", "/v1/sessions/revoke", {"id": dev["id"]}, auth(alex))[0], 200)
        self.assertEqual(self.req("GET", "/v1/me", headers=auth(tvs))[0], 401)

        # behind nginx (this test talks from 127.0.0.1, trusted by default): the pairing address is the right-most
        # untrusted X-Forwarded-For entry, never what the client wrote in front of it
        xff = lambda chain: {"X-TvBro": "1", "X-Forwarded-For": chain}   # noqa: E731
        _, _, p2 = self.req("POST", "/v1/pair/start", {"device": "Den TV"}, xff("203.0.113.66, 192.0.2.77"))
        _, _, seen = self.req("POST", "/v1/pair/peek", {"code": p2["code"]}, auth(alex))
        self.assertEqual((seen["device"], seen["address"]), ("Den TV", "192.0.2.77"))
        # each forwarded client has its own pairing budget; a fake left-hand entry does not give a new one
        for _ in range(PA.MAX_PER_CLIENT - 1):
            self.assertEqual(self.req("POST", "/v1/pair/start", {"device": "TV"}, xff("198.51.100.%d, 192.0.2.77" % _))[0], 200)
        code, hdr, j = self.req("POST", "/v1/pair/start", {"device": "TV"}, xff("198.51.100.99, 192.0.2.77"))
        self.assertEqual(code, 429, j)
        self.assertIn("Retry-After", hdr)
        self.assertEqual(self.req("POST", "/v1/pair/start", {"device": "TV"}, xff("192.0.2.78"))[0], 200)


if __name__ == "__main__":
    unittest.main()
