# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""Orbit discover and requests through Seerr (orbit/seerr.py, orbit/discover.py).

A small mock Seerr on 127.0.0.1 (loopback is allowed for the test suite only, TVBRO_ALLOW_LOOPBACK_SOURCES=1)
answers the documented /api/v1 calls and records what it was sent. The /v1 routes are exercised through
``Api.handle`` as an owner and a member, and once through the real server (run.py) for the wiring."""
import json
import logging
import os
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
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "server"))
from orbit import discover as D  # noqa: E402
from orbit import seerr as S  # noqa: E402
from orbit.accounts import Accounts  # noqa: E402
from orbit.api import Api, Call  # noqa: E402
from tvbro_server import auth as AU  # noqa: E402
from tvbro_server import net as NET  # noqa: E402

KEY = "test-seerr-key-0123456789abcdef"        # synthetic


def movie(i, title, date, status=None, requests=None):
    m = {"id": i, "mediaType": "movie", "title": title, "releaseDate": date, "overview": f"About {title}.",
         "posterPath": f"/p{i}.jpg", "backdropPath": f"/b{i}.jpg", "voteAverage": 7.25}
    if status:
        m["mediaInfo"] = {"status": status, "requests": requests or []}
    return m


def show(i, name, date, info=None, seasons=(0, 1, 2, 3)):
    s = {"id": i, "mediaType": "tv", "name": name, "firstAirDate": date, "overview": "", "posterPath": f"/t{i}.jpg",
         "backdropPath": None, "voteAverage": 8,
         "seasons": [{"seasonNumber": n, "episodeCount": 10, "name": f"Season {n}"} for n in seasons]}
    if info:
        s["mediaInfo"] = info
    return s


class MockSeerr:
    """The parts of Seerr's REST API that Orbit uses, with synthetic data."""

    def __init__(self):
        self.calls = []                       # (method, path, query, headers, body)
        self.down = False
        self.users = [{"id": 1, "displayName": "Admin", "email": "admin@example.com", "username": "admin"},
                      {"id": 2, "displayName": "Sam", "email": "sam@example.com", "plexUsername": "sammy"},
                      {"id": 3, "displayName": "Jo", "email": "jo@example.com", "jellyfinUsername": "jo"}]
        self.titles = {
            ("movie", 550): movie(550, "Requestable Film", "1999-10-15"),
            ("movie", 603): movie(603, "Available Film", "1999-03-31", status=5),
            ("movie", 680): movie(680, "Pending Film", "1994-09-10", status=2,
                                  requests=[{"id": 4, "status": 1, "createdAt": "2026-11-01T10:00:00.000Z"}]),
            ("tv", 1399): show(1399, "Partly Show", "2011-04-17", info={
                "status": 4, "seasons": [{"seasonNumber": 1, "status": 5}],
                "requests": [{"id": 5, "status": 1, "createdAt": "2026-11-02T10:00:00.000Z",
                              "seasons": [{"seasonNumber": 2, "status": 2}]}]}),
            ("tv", 2000): show(2000, "New Show", "2027-01-01", seasons=(1,)),
        }
        self.requests = [{"id": 4, "status": 1, "type": "movie", "createdAt": "2026-11-01T10:00:00.000Z",
                          "media": {"tmdbId": 680, "mediaType": "movie", "status": 2},
                          "requestedBy": {"id": 2, "displayName": "Sam"}},
                         {"id": 5, "status": 1, "type": "tv", "createdAt": "2026-11-02T10:00:00.000Z",
                          "media": {"tmdbId": 1399, "mediaType": "tv", "status": 4},
                          "seasons": [{"seasonNumber": 2}], "requestedBy": {"id": 3, "displayName": "Jo"}}]
        mock_ = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def _send(self, code, obj):
                b = json.dumps(obj).encode()
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(b)))
                self.end_headers()
                self.wfile.write(b)

            def do_GET(self):
                mock_.handle(self, "GET")

            def do_POST(self):
                mock_.handle(self, "POST")

        self.srv = ThreadingHTTPServer(("127.0.0.1", 0), H)
        self.base = "http://127.0.0.1:%d" % self.srv.server_address[1]
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()

    def stop(self):
        self.srv.shutdown()
        self.srv.server_close()

    def results(self, keys):
        return {"page": 1, "totalPages": 1, "results": [self.titles[k] for k in keys]}

    def handle(self, h, method):
        u = urllib.parse.urlsplit(h.path)
        n = int(h.headers.get("Content-Length") or 0)
        body = json.loads(h.rfile.read(n)) if n else None
        q = urllib.parse.parse_qs(u.query)
        self.calls.append((method, u.path, u.query, dict(h.headers), body))
        if self.down:
            return h._send(503, {"message": "maintenance"})
        p = u.path
        if p == "/api/v1/status":
            return h._send(200, {"version": "2.0.0"})
        if h.headers.get("X-Api-Key") != KEY:
            return h._send(403, {"message": "You do not have permission to access this endpoint"})
        person = {"id": 31, "mediaType": "person", "name": "Some Actor"}
        if p == "/api/v1/auth/me":
            return h._send(200, {"id": 1, "displayName": "Admin"})
        if p == "/api/v1/discover/trending":
            r = self.results([("movie", 550), ("tv", 1399)])
            r["results"].insert(1, person)
            return h._send(200, r)
        if p == "/api/v1/discover/movies":
            return h._send(200, self.results([("movie", 550), ("movie", 603)]))
        if p == "/api/v1/discover/tv":
            return h._send(200, self.results([("tv", 1399)]))
        if p == "/api/v1/discover/movies/upcoming":
            return h._send(200, self.results([("movie", 680)]))
        if p == "/api/v1/discover/tv/upcoming":
            return h._send(200, self.results([("tv", 2000)]))
        if p == "/api/v1/search":
            if [x.lower() for x in q.get("query", [])] == ["available film"]:
                r = self.results([("movie", 603)])
                r["results"].append(person)
                return h._send(200, r)
            return h._send(200, {"page": 1, "totalPages": 0, "results": []})
        for kind in ("movie", "tv"):
            if p.startswith(f"/api/v1/{kind}/"):
                t = self.titles.get((kind, int(p.rsplit("/", 1)[1])))
                return h._send(200, t) if t else h._send(404, {"message": "Unable to retrieve"})
        if p == "/api/v1/user":
            return h._send(200, {"pageInfo": {"results": len(self.users)}, "results": self.users})
        if p.startswith("/api/v1/user/"):
            uid = int(p.rsplit("/", 1)[1])
            u_ = next((x for x in self.users if x["id"] == uid), None)
            return h._send(200, u_) if u_ else h._send(404, {"message": "User not found"})
        if p == "/api/v1/request" and method == "GET":
            by = q.get("requestedBy")
            res = [r for r in self.requests if not by or str(r["requestedBy"]["id"]) == by[0]]
            return h._send(200, {"pageInfo": {"results": len(res)}, "results": res})
        if p == "/api/v1/request" and method == "POST":
            uid = int(h.headers.get("X-Api-User") or 1)
            kind, tmdb = body["mediaType"], body["mediaId"]
            seasons = [{"seasonNumber": s} for s in body.get("seasons") or []]
            req = {"id": 9, "status": 1, "type": kind, "createdAt": "2026-11-14T10:00:00.000Z",
                   "media": {"tmdbId": tmdb, "mediaType": kind, "status": 2}, "seasons": seasons,
                   "requestedBy": {"id": uid, "displayName": "Sam"}}
            self.requests.append(req)
            t = self.titles[(kind, tmdb)]
            info = t.setdefault("mediaInfo", {"status": 2, "requests": [], "seasons": []})
            info["requests"] = info.get("requests", []) + [req]
            info["status"] = max(info.get("status", 1), 2)
            return h._send(201, req)
        return h._send(404, {"message": "not found"})

    def last(self, method, path):
        return [c for c in self.calls if c[0] == method and c[1] == path]


class Clock:
    def __init__(self):
        self.t = 1_800_000_000.0

    def __call__(self):
        return self.t


@mock.patch("tvbro_server.auth.ITERATIONS", 1000)
@mock.patch.dict(os.environ, {"TVBRO_ALLOW_LOOPBACK_SOURCES": "1"})
class Discover(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()
        self.seerr = MockSeerr()
        self.auth = AU.Auth(self.d, b"k" * 32)
        self.acc = Accounts(self.d, self.auth)
        with open(os.path.join(self.d, "orbit-setup-token"), encoding="utf-8") as f:
            sid = self.acc.setup(f.read(), "alex", "correct horse 1", "Alex")
        self.owner = self.acc.gate(self.auth.session(sid))
        self.sam_acc = self.acc.create_account(self.owner, "sammy", "another pass 2", "Sam")
        self.sam = self.acc.gate(self.auth.session(self.acc.login("sammy", "another pass 2", "192.0.2.12")))
        self.clock = Clock()
        self.cfg = S.SeerrConfig(self.d)
        self.disc = D.Discover(self.acc, self.cfg, clock=self.clock)
        self.api = Api(self.acc)
        self.api.routes.update(self.disc.routes())

    def tearDown(self):
        self.seerr.stop()
        self.acc.close()
        shutil.rmtree(self.d, ignore_errors=True)

    def call(self, who, method, path, body=None, **query):
        r = self.api.handle(Call(method, path, who, body or {}, "192.0.2.10", {k: str(v) for k, v in query.items()}))
        return r.code, r.obj

    def configure(self):
        code, j = self.call(self.owner, "POST", "/v1/discover/settings", {"baseUrl": self.seerr.base + "/", "apiKey": KEY})
        self.assertEqual(code, 200, j)
        return j

    def link_sam(self):
        code, j = self.call(self.owner, "POST", "/v1/discover/map", {"accountId": self.sam_acc["id"], "seerrUserId": 2})
        self.assertEqual(code, 200, j)

    # ---- settings ----
    def test_settings_admin_only_tested_and_key_never_shown_or_logged(self):
        records = []
        h = logging.Handler(logging.DEBUG)
        h.emit = lambda rec: records.append(h.format(rec) if h.formatter else rec.getMessage())
        root = logging.getLogger()
        old_level = root.level
        root.addHandler(h)
        root.setLevel(logging.DEBUG)
        try:
            code, j = self.call(self.owner, "GET", "/v1/discover/status")
            self.assertEqual((code, j["configured"]), (200, False))
            self.assertEqual(self.call(self.owner, "GET", "/v1/discover/rows")[0], 409)
            # a member cannot change the settings
            code, j = self.call(self.sam, "POST", "/v1/discover/settings", {"baseUrl": self.seerr.base, "apiKey": KEY})
            self.assertEqual(code, 403)
            self.assertFalse(self.cfg.configured())
            # a wrong key is refused after testing it; nothing is saved
            code, j = self.call(self.owner, "POST", "/v1/discover/settings",
                                {"baseUrl": self.seerr.base, "apiKey": "wrong-key-123456"})
            self.assertEqual(code, 400, j)
            self.assertFalse(self.cfg.configured())
            # bad addresses
            for bad in ("ftp://192.0.2.20/", "http://192.0.2.20:22", "", "http://user:pw@192.0.2.20:5055"):
                self.assertEqual(self.call(self.owner, "POST", "/v1/discover/settings",
                                           {"baseUrl": bad, "apiKey": KEY})[0], 400, bad)
            saved = self.configure()
            self.assertEqual(saved["baseUrl"], self.seerr.base)
            self.assertEqual(saved["version"], "2.0.0")
            self.assertTrue(self.seerr.last("GET", "/api/v1/auth/me"), "the key was tested before saving")
            # the key travels only in the header, never in a URL
            self.assertTrue(all(KEY not in c[2] and KEY not in c[1] for c in self.seerr.calls))
            # the member sees whether it works, but not the address or the key
            code, st = self.call(self.sam, "GET", "/v1/discover/status")
            self.assertEqual((st["configured"], st["reachable"], st["linked"]), (True, True, False))
            self.assertNotIn("baseUrl", st)
            code, st_admin = self.call(self.owner, "GET", "/v1/discover/status")
            self.assertEqual((st_admin["baseUrl"], st_admin["keySet"]), (self.seerr.base, True))
            # changing only the address keeps the saved key
            self.assertEqual(self.call(self.owner, "POST", "/v1/discover/settings",
                                       {"baseUrl": self.seerr.base + "/api/v1"})[0], 200)
            self.assertEqual(self.cfg.get()["apiKey"], KEY)
            # another server without the key typed again: refused, so the saved key never goes to a new address
            code, j = self.call(self.owner, "POST", "/v1/discover/settings", {"baseUrl": "http://seerr-other.example.com:5055"})
            self.assertEqual(code, 400)
            self.assertIn("API key again", j["error"])
            self.assertTrue(self.cfg.get()["baseUrl"].startswith(self.seerr.base))
            # every answer and the file on disk are free of the key
            for who in (self.owner, self.sam):
                for path in ("/v1/discover/status", "/v1/discover/rows", "/v1/discover/users"):
                    self.assertNotIn(KEY, json.dumps(self.call(who, "GET", path)[1]))
            with open(os.path.join(self.d, "seerr.enc"), "rb") as f:
                self.assertNotIn(KEY.encode(), f.read())
            # it is sealed with the server key: a fresh config reads it back
            self.assertEqual(S.SeerrConfig(self.d).get()["apiKey"], KEY)
            self.assertNotIn(KEY, repr(S.SeerrClient(self.seerr.base, KEY)))
        finally:
            root.removeHandler(h)
            root.setLevel(old_level)
        self.assertTrue(records, "something was logged")
        self.assertFalse([r for r in records if KEY in r])
        # remove
        self.assertEqual(self.call(self.owner, "POST", "/v1/discover/settings", {"remove": True})[1], {"configured": False})
        self.assertFalse(self.cfg.configured())

    def test_private_hosts_are_the_configured_seerr_only(self):
        self.assertEqual(self.disc.private_hosts(), set())
        self.cfg.set_server("http://Seerr.Example.lan:5055", KEY)
        self.assertEqual(self.disc.private_hosts(), {("seerr.example.lan", 5055)})
        self.cfg.set_server("https://seerr.example.com/sub", KEY)
        self.assertEqual(self.disc.private_hosts(), {("seerr.example.com", 443)})
        with self.cfg.probing("http://192.0.2.30:5055"):
            self.assertIn(("192.0.2.30", 5055), self.disc.private_hosts())
        self.assertNotIn(("192.0.2.30", 5055), self.disc.private_hosts())

    # ---- discovery ----
    def test_rows_normalized_with_status_mapping(self):
        self.configure()
        code, j = self.call(self.sam, "GET", "/v1/discover/rows")       # an unlinked member can browse
        self.assertEqual(code, 200, j)
        self.assertFalse(j["stale"])
        rows = {r["id"]: r["items"] for r in j["rows"]}
        self.assertEqual(list(rows), ["trending", "movies", "tv", "upcoming"])
        self.assertEqual([(t["type"], t["tmdb"]) for t in rows["trending"]], [("movie", 550), ("tv", 1399)],
                         "people are left out")
        first = rows["trending"][0]
        self.assertEqual(first, {"type": "movie", "tmdb": 550, "title": "Requestable Film", "year": 1999,
                                 "overview": "About Requestable Film.",
                                 "poster": "https://image.tmdb.org/t/p/w342/p550.jpg",
                                 "backdrop": "https://image.tmdb.org/t/p/w1280/b550.jpg", "rating": 7.2,
                                 "status": "requestable"})
        self.assertEqual({t["tmdb"]: t["status"] for t in rows["movies"]}, {550: "requestable", 603: "available"})
        self.assertEqual(rows["tv"][0]["status"], "partial")
        self.assertEqual(rows["tv"][0]["requestStatus"], "pending")
        self.assertIsNone(rows["tv"][0]["backdrop"])
        self.assertEqual([(t["type"], t["tmdb"]) for t in rows["upcoming"]], [("movie", 680), ("tv", 2000)])
        self.assertEqual(rows["upcoming"][0]["status"], "pending")
        # cached: a second call does not reach Seerr
        n = len(self.seerr.calls)
        self.call(self.sam, "GET", "/v1/discover/rows")
        self.assertEqual(len(self.seerr.calls), n)

    def test_status_codes(self):
        self.assertEqual([S.media_status({"status": n}) for n in (1, 2, 3, 4, 5, 6, None)],
                         ["requestable", "pending", "processing", "partial", "available", "requestable", "requestable"])
        self.assertEqual(S.media_status(None), "requestable")
        self.assertIsNone(S.image("https://evil.example.com/x.jpg", "w342"))
        self.assertIsNone(S.image("/../x.jpg", "w342"))

    def test_search_and_title(self):
        self.configure()
        code, j = self.call(self.sam, "GET", "/v1/discover/search", q="Available Film")
        self.assertEqual(code, 200, j)
        self.assertEqual([(t["tmdb"], t["status"]) for t in j["results"]], [(603, "available")])
        raw = self.seerr.last("GET", "/api/v1/search")[-1][2]
        self.assertIn("query=Available%20Film", raw)
        self.assertEqual(self.call(self.sam, "GET", "/v1/discover/search", q="")[0], 400)
        self.assertEqual(self.call(self.sam, "GET", "/v1/discover/search", q="x" * 101)[0], 400)
        code, t = self.call(self.sam, "GET", "/v1/discover/title", type="tv", tmdb=1399)
        self.assertEqual(code, 200, t)
        self.assertEqual(t["seasons"], [{"season": 1, "episodes": 10, "name": "Season 1", "status": "available"},
                                        {"season": 2, "episodes": 10, "name": "Season 2", "status": "pending"},
                                        {"season": 3, "episodes": 10, "name": "Season 3", "status": "requestable"}])
        self.assertEqual(self.call(self.sam, "GET", "/v1/discover/title", type="tv", tmdb=4242)[0], 404)
        self.assertEqual(self.call(self.sam, "GET", "/v1/discover/title", type="book", tmdb=1)[0], 400)
        self.assertEqual(self.call(self.sam, "GET", "/v1/discover/title", type="movie", tmdb="x")[0], 400)

    def test_availability_override_hook(self):
        self.configure()
        self.disc.availability_override = lambda tmdb, kind: {"status": "available", "play": {"source": "plex"}} \
            if (kind, tmdb) == ("movie", 550) else None
        code, t = self.call(self.sam, "GET", "/v1/discover/title", type="movie", tmdb=550)
        self.assertEqual((t["status"], t["play"]), ("available", {"source": "plex"}))
        self.assertIsNone(D.availability_override(550, "movie"), "no-op until the Phase 5 catalogue merge")

    # ---- requests ----
    def test_request_as_the_mapped_user(self):
        self.configure()
        self.link_sam()
        code, j = self.call(self.sam, "POST", "/v1/requests", {"type": "movie", "tmdb": 550})
        self.assertEqual(code, 201, j)
        self.assertTrue(j["created"])
        self.assertEqual((j["request"]["status"], j["request"]["tmdb"]), ("pending", 550))
        self.assertEqual(j["title"]["status"], "pending")
        posted = self.seerr.last("POST", "/api/v1/request")
        self.assertEqual(len(posted), 1)
        _m, _p, _q, hdr, body = posted[0]
        self.assertEqual(hdr.get("X-Api-User"), "2")
        self.assertEqual(hdr.get("X-Api-Key"), KEY)
        self.assertEqual(body, {"mediaType": "movie", "mediaId": 550})
        # TV: only the seasons that are neither available nor requested yet
        code, j = self.call(self.sam, "POST", "/v1/requests", {"type": "tv", "tmdb": 1399})
        self.assertEqual(code, 201, j)
        self.assertEqual(self.seerr.last("POST", "/api/v1/request")[-1][4],
                         {"mediaType": "tv", "mediaId": 1399, "seasons": [3]})
        self.assertEqual(self.call(self.sam, "POST", "/v1/requests", {"type": "tv", "tmdb": 1399, "seasons": [9]})[0], 400)
        self.assertEqual(self.call(self.sam, "POST", "/v1/requests", {"type": "movie", "tmdb": 550, "seasons": [1]})[0], 400)
        # my requests (as Sam's Seerr user), with titles
        code, j = self.call(self.sam, "GET", "/v1/requests")
        self.assertEqual(code, 200, j)
        self.assertEqual(sorted(r["tmdb"] for r in j["requests"]), [550, 680, 1399])
        self.assertTrue(all(r["requestedBy"]["seerrUserId"] == 2 for r in j["requests"]))
        self.assertEqual({r["tmdb"]: r["title"]["title"] for r in j["requests"]}[680], "Pending Film")
        listed = self.seerr.last("GET", "/api/v1/request")[-1]
        self.assertIn("requestedBy=2", listed[2])
        self.assertEqual(listed[3].get("X-Api-User"), "2")
        # all=1 is for admins
        self.assertEqual(self.call(self.sam, "GET", "/v1/requests", all=1)[0], 403)
        code, j = self.call(self.owner, "GET", "/v1/requests", all=1)
        self.assertEqual(len(j["requests"]), 4)
        self.assertIn(self.sam_acc["id"], {r["requestedBy"].get("accountId") for r in j["requests"]})

    def test_duplicate_request_returns_the_existing_state(self):
        self.configure()
        self.link_sam()
        for kind, tmdb, status in (("movie", 603, "available"), ("movie", 680, "pending")):
            code, j = self.call(self.sam, "POST", "/v1/requests", {"type": kind, "tmdb": tmdb})
            self.assertEqual((code, j["created"], j["title"]["status"]), (200, False, status), j)
        code, j = self.call(self.sam, "POST", "/v1/requests", {"type": "tv", "tmdb": 1399, "seasons": [1, 2]})
        self.assertEqual((code, j["created"]), (200, False))
        self.assertEqual(self.seerr.last("POST", "/api/v1/request"), [], "nothing was requested twice")
        # requesting the same film twice: the second answer is the first request's state
        self.assertEqual(self.call(self.sam, "POST", "/v1/requests", {"type": "movie", "tmdb": 550})[0], 201)
        code, j = self.call(self.sam, "POST", "/v1/requests", {"type": "movie", "tmdb": 550})
        self.assertEqual((code, j["created"], j["title"]["status"], j["title"]["requestStatus"]),
                         (200, False, "pending", "pending"))
        self.assertEqual(len(self.seerr.last("POST", "/api/v1/request")), 1)

    def test_unlinked_account_gets_409(self):
        self.configure()
        code, j = self.call(self.sam, "POST", "/v1/requests", {"type": "movie", "tmdb": 550})
        self.assertEqual(code, 409)
        self.assertIn("ask the owner to link your Seerr user", j["error"])
        self.assertEqual(self.call(self.sam, "GET", "/v1/requests")[1], {"requests": [], "linked": False})
        self.assertEqual(self.seerr.last("POST", "/api/v1/request"), [])

    def test_mapping_admin_only_and_auto_match(self):
        self.configure()
        self.assertEqual(self.call(self.sam, "POST", "/v1/discover/map",
                                   {"accountId": self.sam_acc["id"], "seerrUserId": 2})[0], 403)
        self.assertEqual(self.call(self.sam, "GET", "/v1/discover/users")[0], 403)
        self.assertEqual(self.call(self.owner, "POST", "/v1/discover/map",
                                   {"accountId": self.sam_acc["id"], "seerrUserId": 77})[0], 400, "unknown Seerr user")
        self.assertEqual(self.call(self.owner, "POST", "/v1/discover/map",
                                   {"accountId": "nope", "seerrUserId": 2})[0], 404)
        code, j = self.call(self.owner, "GET", "/v1/discover/users")
        self.assertEqual(code, 200, j)
        self.assertEqual([u["id"] for u in j["users"]], [1, 2, 3])
        sugg = {a["username"]: a["suggested"] for a in j["accounts"]}
        self.assertEqual(sugg, {"alex": None, "sammy": 2}, "sammy = Sam's Plex username")
        code, j = self.call(self.owner, "POST", "/v1/discover/map", {"auto": True})
        self.assertEqual(j["linked"], [{"accountId": self.sam_acc["id"], "seerrUserId": 2}])
        self.assertEqual(self.cfg.seerr_user(self.sam_acc["id"]), 2)
        self.assertTrue(self.call(self.sam, "GET", "/v1/discover/status")[1]["linked"])
        self.call(self.owner, "POST", "/v1/discover/map", {"accountId": self.sam_acc["id"], "seerrUserId": None})
        self.assertIsNone(self.cfg.seerr_user(self.sam_acc["id"]))

    # ---- Seerr down ----
    def test_seerr_down_serves_stale_rows_and_refuses_requests(self):
        self.configure()
        self.link_sam()
        self.assertEqual(self.call(self.sam, "GET", "/v1/discover/rows")[0], 200)
        self.assertEqual(self.call(self.sam, "GET", "/v1/discover/title", type="movie", tmdb=550)[0], 200)
        self.seerr.stop()                                   # connection refused from now on
        self.clock.t += D.ROWS_TTL + 1                      # the cached rows are old
        code, j = self.call(self.sam, "GET", "/v1/discover/rows")
        self.assertEqual(code, 200, j)
        self.assertTrue(j["stale"])
        self.assertEqual(len(j["rows"][0]["items"]), 2)
        code, t = self.call(self.sam, "GET", "/v1/discover/title", type="movie", tmdb=550)
        self.assertEqual((code, t["stale"]), (200, False), "titles are kept an hour")
        self.clock.t += D.TITLE_TTL
        self.assertTrue(self.call(self.sam, "GET", "/v1/discover/title", type="movie", tmdb=550)[1]["stale"])
        code, j = self.call(self.sam, "POST", "/v1/requests", {"type": "movie", "tmdb": 550})
        self.assertEqual(code, 503, j)
        self.assertEqual(self.call(self.sam, "GET", "/v1/discover/search", q="nothing cached")[0], 503)
        st = self.call(self.sam, "GET", "/v1/discover/status")[1]
        self.assertEqual((st["configured"], st["reachable"]), (True, False))

    def test_seerr_5xx_counts_as_down_and_nothing_cached_is_503(self):
        self.configure()
        self.seerr.down = True
        self.assertEqual(self.call(self.sam, "GET", "/v1/discover/rows")[0], 503)

    def test_key_revoked_in_seerr_is_reported(self):
        self.configure()
        self.cfg.set_server(self.seerr.base, "revoked-key-0000")
        code, j = self.call(self.sam, "GET", "/v1/discover/rows")
        self.assertEqual(code, 502)
        self.assertIn("API key", j["error"])

    def test_cache_is_bounded(self):
        c = S.TTLCache(3, Clock())
        for i in range(5):
            c.put(i, i)
        self.assertEqual([c.stale(i) for i in range(5)], [None, None, 2, 3, 4])


def free_port() -> int:
    import socket
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


class Server(unittest.TestCase):
    """The real server: the routes are mounted, GET query strings reach them, the key stays out of the log."""

    def setUp(self):
        self.seerr = MockSeerr()
        self.tmp = tempfile.mkdtemp()
        self.data = os.path.join(self.tmp, "data")
        port = free_port()
        env = dict(os.environ, TVBRO_PORT=str(port), TVBRO_DATA=self.data, PYTHONDONTWRITEBYTECODE="1",
                   TVBRO_ALLOW_LOOPBACK_SOURCES="1")
        env.pop("TVBRO_KEY", None)
        self.logf = open(os.path.join(self.tmp, "server.log"), "wb")
        self.proc = subprocess.Popen([sys.executable, "run.py"], cwd=os.path.join(HERE, "..", "server"),
                                     env=env, stdout=self.logf, stderr=subprocess.STDOUT)
        self.base = "http://127.0.0.1:%d" % port
        for _ in range(150):
            try:
                self.req("GET", "/api/health")
                break
            except Exception:                       # noqa: BLE001
                time.sleep(0.1)

    def tearDown(self):
        self.proc.terminate()
        try:
            self.proc.wait(10)
        except subprocess.TimeoutExpired:
            self.proc.kill()
        self.logf.close()
        self.seerr.stop()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def req(self, method, path, body=None, headers=None):
        h = {"Content-Type": "application/json"} if body is not None else {}
        h.update(headers or {})
        data = json.dumps(body).encode() if body is not None else None
        r = urllib.request.Request(self.base + path, data=data, method=method, headers=h)
        try:
            with urllib.request.urlopen(r, timeout=60) as f:
                return f.status, dict(f.headers), json.loads(f.read() or b"{}")
        except urllib.error.HTTPError as e:
            return e.code, dict(e.headers), json.loads(e.read() or b"{}")

    def test_wiring(self):
        with open(os.path.join(self.data, "orbit-setup-token"), encoding="utf-8") as f:
            body = {"token": f.read(), "username": "alex", "password": "correct horse 1"}
        code, hdr, me = self.req("POST", "/v1/setup", body, {"X-TvBro": "1"})
        self.assertEqual(code, 200, me)
        auth = {"Cookie": hdr["Set-Cookie"].split(";", 1)[0], "X-CSRF-Token": me["csrf"]}
        self.assertEqual(self.req("GET", "/v1/discover/status")[0], 401)
        code, _, j = self.req("POST", "/v1/discover/settings", {"baseUrl": self.seerr.base, "apiKey": KEY}, auth)
        self.assertEqual(code, 200, j)
        code, _, j = self.req("GET", "/v1/discover/search?q=Available%20Film", headers=auth)
        self.assertEqual((code, [t["tmdb"] for t in j["results"]]), (200, [603]), j)
        code, _, j = self.req("GET", "/v1/discover/title?type=movie&tmdb=550", headers=auth)
        self.assertEqual((code, j["title"]), (200, "Requestable Film"))
        self.assertNotIn(KEY, json.dumps(self.req("GET", "/v1/discover/status", headers=auth)[2]))
        self.proc.terminate()
        self.proc.wait(10)
        self.logf.flush()
        with open(os.path.join(self.tmp, "server.log"), "rb") as f:
            self.assertNotIn(KEY.encode(), f.read())


if __name__ == "__main__":
    unittest.main()
