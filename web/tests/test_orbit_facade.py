# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""Hidden provider logins (orbit/facade.py): linked TVs reach the provider through the home server's Xtream facade.

The provider here is a fake on 127.0.0.1 (loopback is allowed for the test suite only,
TVBRO_ALLOW_LOOPBACK_SOURCES=1). Nothing talks to a real provider."""
import gzip
import http.server
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.parse
import urllib.request
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "server"))
sys.path.insert(0, HERE)
from orbit import facade as F  # noqa: E402
from orbit.accounts import Accounts  # noqa: E402
from orbit import pairing as PA  # noqa: E402
from tvbro_server import auth as AU  # noqa: E402
from test_orbit_accounts import free_port  # noqa: E402

USER, PW = "realuser", "Zq9-hunter2"          # the fake provider's login: must never reach the TV
M3U_TOKEN = "m3utok77"                         # an M3U provider's login, in its addresses
VOD = bytes(range(256)) * 64                   # 16 KiB film


# --------------------------------------------------------------------------- pure parts


class Pure(unittest.TestCase):
    def test_hide_logins_env_wins(self):
        with mock.patch.dict(os.environ, {F.ENV: ""}):
            self.assertTrue(F.hide_logins({}))                       # on by default
            self.assertFalse(F.hide_logins({"hideProviderLogin": False}))
            self.assertFalse(F.forced())
        with mock.patch.dict(os.environ, {F.ENV: "0"}):
            self.assertFalse(F.hide_logins({"hideProviderLogin": True}))
            self.assertTrue(F.forced())
        with mock.patch.dict(os.environ, {F.ENV: "1"}):
            self.assertTrue(F.hide_logins({"hideProviderLogin": False}))

    def test_device_playlists_carry_no_login(self):
        pl = [{"id": "p0", "name": "A", "kind": "xtream", "server": "http://tv.example.com", "user": USER, "pass": PW,
               "url": "", "epgUrl": ""},
              {"id": "p1", "name": "B", "kind": "m3u", "server": "", "user": "", "pass": "",
               "url": "http://tv.example.com/get.php?username=%s&password=%s" % (USER, PW),
               "epgUrl": "http://tv.example.com/a.xml,http://tv.example.com/b.xml"}]
        out = F.device_playlists(pl, "http://192.0.2.10:8750", "tv" + "a" * 16, "b" * 32)
        s = json.dumps(out)
        self.assertNotIn(PW, s)
        self.assertNotIn(USER, s)
        self.assertNotIn("tv.example.com", s)
        self.assertEqual((out[0]["server"], out[0]["user"], out[0]["pass"]), ("http://192.0.2.10:8750/xt/p0", "tv" + "a" * 16, "b" * 32))
        self.assertTrue(out[1]["url"].startswith("http://192.0.2.10:8750/xt/p1/get.php?username=tv"))
        self.assertEqual(len(out[1]["epgUrl"].split(",")), 2)

    def test_hls_rewrite(self):
        src = (b"#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:6,\nseg1.ts\n#EXTINF:6,\n"
               b"http://cdn.example.com/hls/" + USER.encode() + b"/" + PW.encode() + b"/seg2.ts?x=1\n")
        out = F.rewrite_hls(src, "http://tv.example.com/live/a/b/1.m3u8", lambda u: "<" + u + ">").decode()
        self.assertIn('URI="<http://tv.example.com/live/a/b/key.bin>"', out)
        self.assertIn("<http://tv.example.com/live/a/b/seg1.ts>", out)
        self.assertIn("<http://cdn.example.com/hls/%s/%s/seg2.ts?x=1>" % (USER, PW), out)

    def test_m3u_rewrite_and_templates(self):
        src = ('#EXTM3U url-tvg="http://tv.example.com/epg.xml?t=%s"\n'
               '#EXTINF:-1 tvg-id="a" tvg-logo="http://img.example.com/a.png" catchup="default" '
               'catchup-source="http://tv.example.com/ts/%s/{utc}/{duration}.ts",A\n'
               'http://tv.example.com/live/%s/1.ts\n'
               '#EXTINF:-1 catchup="append" catchup-source="?utc={utc}",B\nhttp://tv.example.com/live/%s/2.m3u8\n'
               % (M3U_TOKEN, M3U_TOKEN, M3U_TOKEN, M3U_TOKEN))
        seal = lambda s: "SEALED"                                    # noqa: E731
        out = F.rewrite_m3u(src, lambda u: "S(" + u + ")", lambda t: F.template_link(t, seal, "/T/"),
                            lambda u: "P(" + u + ")")
        self.assertIn('url-tvg="P(http://tv.example.com/epg.xml?t=%s)"' % M3U_TOKEN, out)
        self.assertIn('catchup-source="/T/SEALED.ts?v0={utc}&v1={duration}"', out)
        self.assertIn('catchup-source="?utc={utc}"', out)             # relative: appended by the TV, left alone
        self.assertIn('tvg-logo="http://img.example.com/a.png"', out)   # images load directly
        self.assertIn("S(http://tv.example.com/live/%s/1.ts)" % M3U_TOKEN, out)
        self.assertEqual(F.fill_template("http://h/{utc}/x/${end}", {"v0": "100", "v1": "200"}), "http://h/100/x/200")

    def test_scrub(self):
        sc = F.Scrub("http://tv.example.com", USER, PW, "tvdev", "devsecret", "http://192.0.2.10:8750",
                     lambda u: "/xt/p0/s/sealed")
        body = json.dumps({"user_info": {"username": USER, "password": PW, "auth": 1, "max_connections": "2"},
                           "server_info": {"url": "tv.example.com", "port": "8080"}}).encode()
        self.assertTrue(sc.needed(body))
        out = json.loads(sc.apply(body))
        self.assertEqual((out["user_info"]["username"], out["user_info"]["password"]), ("tvdev", "devsecret"))
        self.assertEqual((out["user_info"]["max_connections"], out["server_info"]["url"], out["server_info"]["port"]),
                         ("2", "192.0.2.10", "8750"))
        streams = json.dumps([{"name": "Room 123 " + PW[:3], "stream_id": 1,
                               "direct_source": "http://tv.example.com/live/%s/%s/1.ts" % (USER, PW)}]).encode()
        self.assertTrue(sc.needed(streams))
        out = json.loads(sc.apply(streams))
        self.assertEqual(out[0]["direct_source"], "/xt/p0/s/sealed")
        self.assertEqual(out[0]["name"], "Room 123 " + PW[:3])     # names are never touched
        plain = json.dumps([{"name": "News", "stream_icon": "http://img.example.com/1.png"}]).encode()
        self.assertFalse(sc.needed(plain))                          # passed through byte for byte

    def test_redact(self):
        p = "/xt/p0/live/tv0123456789abcdef/" + "c" * 32 + "/101.ts"
        self.assertEqual(F.redact(p), "/xt/p0/live/-/-/101.ts")
        self.assertEqual(F.redact("/api/state"), "/api/state")


@mock.patch("tvbro_server.auth.ITERATIONS", 1000)
class Keys(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()
        self.auth = AU.Auth(self.d, b"k" * 32)
        self.acc = Accounts(self.d, self.auth)
        with open(os.path.join(self.d, "orbit-setup-token"), encoding="utf-8") as f:
            self.alex = self.acc.gate(self.auth.session(self.acc.setup(f.read(), "alex", "correct horse 1")))
        pr = PA.Pairings(self.acc)
        p = pr.start("TV", "192.0.2.5")
        pr.approve(self.alex, p["code"], None, "192.0.2.9")
        self.tv = self.acc.gate(self.auth.session(pr.poll(p["pairId"])["session"]))
        self.keys = F.DeviceKeys(self.acc, b"s" * 32)

    def tearDown(self):
        self.acc.close()
        shutil.rmtree(self.d, ignore_errors=True)

    def test_only_tv_sessions_and_revocation(self):
        self.assertIsNone(self.keys.creds(self.alex), "a browser sign-in gets no facade login")
        u, p = self.keys.creds(self.tv)
        self.assertEqual(self.keys.creds(self.tv), (u, p), "stable, so the TV's playlist list does not change")
        self.assertEqual(self.keys.check(u, p), self.tv["h"])
        self.assertIsNone(self.keys.check(u, "0" * 32))
        self.assertIsNone(self.keys.check("tv" + "0" * 16, p))
        self.assertIsNone(self.keys.check(u, p.upper()))
        self.assertIsNone(F.DeviceKeys(self.acc, b"x" * 32).check(u, p), "another server key: another secret")
        dev = next(s for s in self.acc.sessions(self.alex) if not s["current"])
        self.acc.revoke(self.alex, dev["id"])
        self.assertIsNone(self.keys.check(u, p), "unlinked: dead at once")


# --------------------------------------------------------------------------- end to end, real server process


class Provider(http.server.BaseHTTPRequestHandler):
    """A fake Xtream / M3U provider. Every request must carry the real login."""
    protocol_version = "HTTP/1.1"
    seen: list = []
    port = 0

    def log_message(self, *a):
        pass

    def _send(self, code, body, ctype="application/json", extra=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        q = dict(urllib.parse.parse_qsl(u.query))
        Provider.seen.append((self.path, dict(self.headers)))
        base = "http://127.0.0.1:%d" % Provider.port
        creds = "/%s/%s/" % (USER, PW)
        if u.path in ("/player_api.php", "/xmltv.php", "/get.php"):
            if (q.get("username"), q.get("password")) != (USER, PW):
                return self._send(200, b'{"user_info":{"auth":0}}')
        if u.path == "/player_api.php":
            a = q.get("action")
            if a is None:
                return self._send(200, json.dumps({"user_info": {"username": USER, "password": PW, "auth": 1, "status": "Active",
                                                                 "max_connections": "2"},
                                                   "server_info": {"url": "127.0.0.1", "port": str(Provider.port)}}).encode())
            if a == "get_live_categories":
                return self._send(200, b'[{"category_id":"1","category_name":"News"}]')
            if a == "get_live_streams":
                return self._send(200, json.dumps([
                    {"num": 1, "name": "One", "stream_id": 101, "category_id": "1", "stream_icon": base + "/logo/101.png",
                     "direct_source": base + "/live" + creds + "101.ts"},
                    {"num": 2, "name": "Two", "stream_id": 102, "category_id": "1", "direct_source": ""}]).encode())
            if a == "get_short_epg":
                return self._send(200, json.dumps({"epg_listings": [{"title": "TmV3cw==", "stream": q.get("stream_id")}]}).encode())
            return self._send(200, b"[]")
        if u.path == "/xmltv.php":
            return self._send(200, b'<?xml version="1.0"?><tv><channel id="one"/></tv>', "text/xml")
        if u.path == "/epg-m3u.xml":
            return self._send(200, b"<tv/>", "application/xml") if q.get("t") == M3U_TOKEN else self._send(403, b"no")
        if u.path == "/list.m3u":
            if q.get("t") != M3U_TOKEN:
                return self._send(403, b"no")
            m3u = ('#EXTM3U url-tvg="{b}/epg-m3u.xml?t={t}"\n'
                   '#EXTINF:-1 tvg-id="m1" tvg-logo="{b}/logo/m1.png" catchup="default" '
                   'catchup-source="{b}/cu/{t}/m1.ts?utc={{utc}}&d={{duration}}",M One\n{b}/m/{t}/m1.ts\n').format(b=base, t=M3U_TOKEN)
            return self._send(200, m3u.encode(), "audio/x-mpegurl")
        if u.path == "/m/%s/m1.ts" % M3U_TOKEN:
            return self._send(200, b"M3U-STREAM" * 100, "video/mp2t", {"X-Query": u.query})
        if u.path == "/cu/%s/m1.ts" % M3U_TOKEN:
            return self._send(200, ("CATCHUP utc=%s d=%s" % (q.get("utc"), q.get("d"))).encode(), "video/mp2t")
        if u.path == "/live/%s/%s/seg/a.ts" % (USER, PW):           # relative to the HLS playlist
            return self._send(200, b"SEG-A" * 10, "video/mp2t")
        if u.path.startswith("/live" + creds):
            sid = u.path.rsplit("/", 1)[1]
            if sid == "101.ts":
                return self._send(200, b"\x47" * 188 * 50, "video/mp2t")
            if sid == "102.ts":                                       # a load balancer: redirect with a token
                return self._send(302, b"", "text/plain", {"Location": base + "/lb/tok123/102.ts"})
            if sid == "103.m3u8":                                     # HLS: relative and login-bearing segments
                body = ("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg/a.ts\n#EXTINF:6,\n"
                        "{b}/hlsr{c}103/b.ts\n").format(b=base, c=creds)
                return self._send(200, body.encode(), "application/vnd.apple.mpegurl")
            if sid == "104.ts":                                       # a live stream that never ends
                self.send_response(200)
                self.send_header("Content-Type", "video/mp2t")
                self.send_header("Connection", "close")
                self.end_headers()
                try:
                    while True:
                        self.wfile.write(b"\x47" * 188 * 10)
                        time.sleep(0.02)
                except OSError:
                    return
            return self._send(404, b"no")
        if u.path == "/hlsr/%s/%s/103/b.ts" % (USER, PW):
            return self._send(200, b"SEG-B" * 10, "video/mp2t")
        if u.path == "/lb/tok123/102.ts":
            return self._send(200, b"REDIRECTED" * 10, "video/mp2t")
        if u.path == "/movie%s7.mkv" % creds:
            rng = self.headers.get("Range")
            m = re.match(r"bytes=(\d+)-(\d*)$", rng or "")
            if m:
                a, b = int(m.group(1)), int(m.group(2) or len(VOD) - 1)
                return self._send(206, VOD[a:b + 1], "video/x-matroska",
                                  {"Content-Range": "bytes %d-%d/%d" % (a, b, len(VOD)), "Accept-Ranges": "bytes"})
            return self._send(200, VOD, "video/x-matroska", {"Accept-Ranges": "bytes"})
        return self._send(404, b"no")


class Server(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.prov = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Provider)
        cls.prov.daemon_threads = True
        Provider.port = cls.prov.server_address[1]
        threading.Thread(target=cls.prov.serve_forever, daemon=True).start()
        cls.tmp = tempfile.mkdtemp()
        cls.data = os.path.join(cls.tmp, "data")
        cls.port = free_port()
        env = dict(os.environ, TVBRO_PORT=str(cls.port), TVBRO_DATA=cls.data, PYTHONDONTWRITEBYTECODE="1",
                   TVBRO_ALLOW_LOOPBACK_SOURCES="1")
        for k in ("TVBRO_KEY", F.ENV, "TVBRO_ALLOW_PRIVATE_SOURCES"):
            env.pop(k, None)
        cls.logpath = os.path.join(cls.tmp, "server.log")
        cls.log = open(cls.logpath, "wb")
        cls.proc = subprocess.Popen([sys.executable, "run.py"], cwd=os.path.join(HERE, "..", "server"),
                                    env=env, stdout=cls.log, stderr=subprocess.STDOUT)
        cls.base = "http://127.0.0.1:%d" % cls.port
        for _ in range(150):
            try:
                cls.req("GET", "/api/health")
                break
            except Exception:                       # noqa: BLE001
                time.sleep(0.1)
        cls._link()

    @classmethod
    def tearDownClass(cls):
        cls.proc.terminate()
        try:
            cls.proc.wait(10)
        except subprocess.TimeoutExpired:
            cls.proc.kill()
        cls.log.close()
        cls.prov.shutdown()
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

    @classmethod
    def raw(cls, url, headers=None):
        """(status, headers, body) of a GET, without following redirects into anything but the server."""
        r = urllib.request.Request(url if url.startswith("http") else cls.base + url, headers=headers or {})
        try:
            with urllib.request.urlopen(r, timeout=30) as f:
                return f.status, dict(f.headers), f.read()
        except urllib.error.HTTPError as e:
            return e.code, dict(e.headers), e.read()

    @classmethod
    def _link(cls):
        with open(os.path.join(cls.data, "orbit-setup-token"), encoding="utf-8") as f:
            token = f.read()
        tv = {"X-TvBro": "1"}
        _, hdr, me = cls.req("POST", "/v1/setup", {"token": token, "username": "alex", "password": "correct horse 1"}, tv)
        cls.alex = {"Cookie": hdr["Set-Cookie"].split(";", 1)[0], "X-CSRF-Token": me["csrf"]}
        pbase = "http://127.0.0.1:%d" % Provider.port
        code, _, j = cls.req("POST", "/api/signin", {"kind": "xtream", "name": "X", "server": pbase, "user": USER,
                                                     "pass": PW}, cls.alex)
        assert code == 200 and j["id"] == "p0", (code, j)
        code, _, j = cls.req("POST", "/api/signin", {"kind": "m3u", "name": "M", "url": pbase + "/list.m3u?t=" + M3U_TOKEN,
                                                     "epgUrl": pbase + "/epg-m3u.xml?t=" + M3U_TOKEN}, cls.alex)
        assert code == 200 and j["id"] == "p1", (code, j)
        cls.tv_sess = cls._pair("Living room TV")

    @classmethod
    def _pair(cls, name):
        tv = {"X-TvBro": "1"}
        _, _, p = cls.req("POST", "/v1/pair/start", {"device": name}, tv)
        cls.req("POST", "/v1/pair/approve", {"code": p["code"]}, cls.alex)
        _, _, got = cls.req("POST", "/v1/pair/poll", {"pairId": p["pairId"]}, tv)
        return {"Cookie": "tvbro_s=" + got["session"], "X-CSRF-Token": got["csrf"]}

    def playlists(self, who=None):
        code, _, j = self.req("GET", "/v1/device/playlists", headers=who or self.tv_sess)
        self.assertEqual(code, 200)
        return {p["id"]: p for p in j["playlists"]}

    def facade(self, who=None):
        x = self.playlists(who)["p0"]
        return x["server"], x["user"], x["pass"]

    # ---- the tests ----
    def test_1_playlists_hide_the_login(self):
        pl = self.playlists()
        s = json.dumps(pl)
        for secret in (PW, USER, M3U_TOKEN, "127.0.0.1:%d" % Provider.port):
            self.assertNotIn(secret, s)
        server, user, pw = self.facade()
        self.assertEqual(server, self.base + "/xt/p0")
        self.assertRegex(user, r"^tv[0-9a-f]{16}$")
        self.assertRegex(pw, r"^[0-9a-f]{32}$")
        self.assertEqual(self.facade(), (server, user, pw), "stable between fetches")
        self.assertEqual(pl["p1"]["kind"], "m3u")
        self.assertTrue(pl["p1"]["url"].startswith(self.base + "/xt/p1/get.php?"))
        self.assertEqual(self.req("GET", "/v1/device/playlists", headers=self.alex)[0], 403, "browsers: still never")

    def test_2_catalogue_passthrough(self):
        server, user, pw = self.facade()
        q = urllib.parse.urlencode({"username": user, "password": pw})
        code, _, body = self.raw(server + "/player_api.php?" + q)
        self.assertEqual(code, 200)
        info = json.loads(body)
        self.assertEqual((info["user_info"]["username"], info["user_info"]["password"], info["user_info"]["max_connections"]),
                         (user, pw, "2"))
        self.assertEqual(info["server_info"]["port"], str(self.port))
        code, _, body = self.raw(server + "/player_api.php?" + q + "&action=get_live_streams")
        streams = json.loads(body)
        self.assertEqual([s["stream_id"] for s in streams], [101, 102])
        self.assertNotIn(PW.encode(), body)
        self.assertTrue(streams[0]["direct_source"].startswith("/xt/p0/s/"))
        self.assertEqual(streams[0]["stream_icon"], "http://127.0.0.1:%d/logo/101.png" % Provider.port, "images stay direct")
        code, _, body = self.raw(server + "/player_api.php?" + q + "&action=get_short_epg&stream_id=101&limit=2")
        self.assertEqual(json.loads(body)["epg_listings"][0]["stream"], "101")
        self.assertTrue(any("stream_id=101" in p and "limit=2" in p for p, _ in Provider.seen))
        code, hdr, body = self.raw(server + "/xmltv.php?" + q)
        self.assertEqual((code, body[:5]), (200, b"<?xml"))
        # gzip for the big lists when the TV asks for it
        code, hdr, body = self.raw(server + "/player_api.php?" + q, {"Accept-Encoding": "gzip"})
        self.assertEqual(json.loads(gzip.decompress(body) if hdr.get("Content-Encoding") == "gzip" else body)["user_info"]["auth"], 1)

    def test_3_live_ts_and_redirect(self):
        server, user, pw = self.facade()
        code, hdr, body = self.raw("%s/live/%s/%s/101.ts" % (server, user, pw), {"User-Agent": "ExoPlayerLib/2.19"})
        self.assertEqual((code, body), (200, b"\x47" * 188 * 50))
        self.assertEqual(hdr["Content-Type"], "video/mp2t")
        path, headers = next((p, h) for p, h in reversed(Provider.seen) if p.endswith("/101.ts"))
        self.assertEqual(path, "/live/%s/%s/101.ts" % (USER, PW))
        self.assertEqual(headers.get("User-Agent"), "ExoPlayerLib/2.19", "the TV's player as before")
        code, _, body = self.raw("%s/live/%s/%s/102.ts" % (server, user, pw))
        self.assertEqual((code, body), (200, b"REDIRECTED" * 10), "the provider's redirect is followed by the server")
        self.assertEqual(self.raw("%s/live/%s/%s/999.ts" % (server, user, pw))[0], 404)
        self.assertEqual(self.raw("%s/live/%s/%s/..%%2f..%%2fx" % (server, user, pw))[0], 404)

    def test_4_vod_range(self):
        server, user, pw = self.facade()
        code, hdr, body = self.raw("%s/movie/%s/%s/7.mkv" % (server, user, pw), {"Range": "bytes=100-299"})
        self.assertEqual(code, 206)
        self.assertEqual(body, VOD[100:300])
        self.assertEqual(hdr["Content-Range"], "bytes 100-299/%d" % len(VOD))
        self.assertEqual(hdr["Accept-Ranges"], "bytes")
        code, hdr, body = self.raw("%s/movie/%s/%s/7.mkv" % (server, user, pw))
        self.assertEqual((code, len(body), hdr["Content-Length"]), (200, len(VOD), str(len(VOD))))

    def test_5_hls_rewrite(self):
        server, user, pw = self.facade()
        code, hdr, body = self.raw("%s/live/%s/%s/103.m3u8" % (server, user, pw))
        self.assertEqual(code, 200)
        self.assertIn("mpegurl", hdr["Content-Type"])
        text = body.decode()
        self.assertNotIn(PW, text)
        self.assertNotIn(str(Provider.port), text)
        segs = [l for l in text.splitlines() if l and not l.startswith("#")]
        self.assertEqual(len(segs), 2)
        for s in segs:
            self.assertTrue(s.startswith("/xt/p0/s/%s/%s/" % (user, pw)) and s.endswith(".ts"), s)
        self.assertEqual(self.raw(segs[0])[2], b"SEG-A" * 10)
        self.assertEqual(self.raw(segs[1])[2], b"SEG-B" * 10)
        bad = segs[0].rsplit("/", 1)[0] + "/AAAAAAAAAAAAAAAAAAAAAAAA.ts"
        self.assertEqual(self.raw(bad)[0], 403, "only addresses the server sealed")

    def test_6_m3u_facade(self):
        pl = self.playlists()["p1"]
        code, _, body = self.raw(pl["url"])
        self.assertEqual(code, 200)
        text = body.decode()
        self.assertNotIn(M3U_TOKEN, text)
        self.assertIn('tvg-logo="http://127.0.0.1:%d/logo/m1.png"' % Provider.port, text)
        stream = [l for l in text.splitlines() if l and not l.startswith("#")][0]
        self.assertTrue(stream.startswith(self.base + "/xt/p1/s/"))
        self.assertEqual(self.raw(stream)[2], b"M3U-STREAM" * 100)
        # catch-up "append": what the TV adds reaches the provider
        self.assertEqual(self.raw(stream + "?utc=5")[1].get("X-Query"), None)    # (not echoed back to the TV)
        self.assertTrue(any(p.endswith("/m1.ts?utc=5") for p, _ in Provider.seen))
        # catch-up template: placeholders stay fillable, values reach the provider
        tpl = re.search(r'catchup-source="([^"]+)"', text).group(1)
        self.assertNotIn(M3U_TOKEN, tpl)
        self.assertIn("{utc}", tpl)
        self.assertEqual(self.raw(tpl.replace("{utc}", "1700000000").replace("{duration}", "3600"))[2],
                         b"CATCHUP utc=1700000000 d=3600")
        # guides: the playlist's own guide address and the one in the M3U header
        self.assertEqual(self.raw(pl["epgUrl"])[2], b"<tv/>")
        hdr_epg = re.search(r'url-tvg="([^"]+)"', text).group(1)
        self.assertEqual(self.raw(hdr_epg)[2], b"<tv/>")

    def test_7_auth_failures(self):
        server, user, pw = self.facade()
        for path in ("%s/live/%s/%s/101.ts" % (server, user, "0" * 32),
                     "%s/live/%s/%s/101.ts" % (server, "tv" + "0" * 16, pw),
                     "%s/live/%s/%s/101.ts" % (server, USER, PW),                  # the real login is no good here
                     "%s/player_api.php?username=%s&password=%s" % (server, user, "x"),
                     "%s/player_api.php" % server):
            code, _, body = self.raw(path)
            self.assertEqual(code, 401, path)
            self.assertEqual(json.loads(body)["user_info"]["auth"], 0)
        self.assertEqual(self.raw("%s/live/%s/%s/101.ts" % (server.replace("/p0", "/p9"), user, pw))[0], 404)
        self.assertEqual(self.raw("/xt/p1/live/%s/%s/1.ts" % (user, pw))[0], 404, "p1 is an M3U playlist")

    def test_8_setting_off_gives_the_logins_again(self):
        self.assertEqual(self.req("POST", "/api/settings", {"hideProviderLogin": False}, self.alex)[0], 200)
        try:
            p0 = self.playlists()["p0"]
            self.assertEqual((p0["user"], p0["pass"]), (USER, PW))
            st = self.req("GET", "/api/sync/status", headers=self.alex)[2]["settings"]
            self.assertEqual((st["hideProviderLogin"], st["hideProviderLoginForced"]), (False, False))
        finally:
            self.req("POST", "/api/settings", {"hideProviderLogin": True}, self.alex)
        self.assertNotEqual(self.playlists()["p0"]["pass"], PW)

    def test_9_stream_count_and_revocation(self):
        sess = self._pair("Kitchen TV")
        server, user, pw = self.facade(sess)
        r = urllib.request.urlopen(self.base + "/xt/p0/live/%s/%s/104.ts" % (user, pw), timeout=10)
        self.assertEqual(r.read(188), b"\x47" * 188)
        st = self.req("GET", "/v1/streams", headers=sess)[2]
        self.assertIn("Kitchen TV", [x["device"] for x in st["relays"]])
        self.assertEqual(st["count"], 0, "the provider-slot count is unchanged (the TV registers its own stream)")
        # unlink: the running relay ends and the login is dead
        dev = next(s for s in self.req("GET", "/v1/sessions", headers=self.alex)[2]["sessions"] if s["device"] == "Kitchen TV")
        self.assertEqual(self.req("POST", "/v1/sessions/revoke", {"id": dev["id"]}, self.alex)[0], 200)
        t0 = time.time()
        while r.read(64 * 1024):
            self.assertLess(time.time() - t0, 10, "relay still running after unlink")
        r.close()
        self.assertEqual(self.raw("%s/live/%s/%s/101.ts" % (server, user, pw))[0], 401)
        self.assertEqual(self.raw("%s/player_api.php?username=%s&password=%s" % (server, user, pw))[0], 401)

    def test_z_logs_hold_no_login(self):
        self.facade()
        time.sleep(0.3)
        with open(self.logpath, "rb") as f:
            log = f.read().decode("utf-8", "replace")
        _, user, pw = self.facade()
        self.assertIn("/xt/p0/live/-/-/101.ts", log)
        for secret in (PW, M3U_TOKEN, pw):
            self.assertNotIn(secret, log)


if __name__ == "__main__":
    unittest.main()
