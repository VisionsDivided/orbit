# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""Orbit Plex / Jellyfin connectors and the /v1/media routes (orbit/media.py, orbit/connectors/).

Mock Jellyfin and Plex servers (and a mock plex.tv) run on 127.0.0.1 with synthetic fixtures; the server
code talks to them through net.open_url with loopback allowed for the test only. Covers Quick Connect,
password sign-in, the Plex PIN, libraries / items / children paging, playback, progress and watched calls
(checked on the mock), artwork through the server, ownership (another account gets 404), tokens never in
logs, list answers or orbit.db, and the orbit.db upgrade from version 2 to 3."""
import http.server
import json
import logging
import os
import shutil
import socket
import sqlite3
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

from cryptography.fernet import Fernet, InvalidToken

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "server"))
from orbit import api as V1  # noqa: E402
from orbit import db as ODB  # noqa: E402
from orbit.accounts import Accounts  # noqa: E402
from orbit.connectors import plex as PLEX  # noqa: E402
from orbit.connectors.base import ConnectorError, normalize_base_url, safe_path  # noqa: E402
from orbit.media import MediaServers, TokenVault  # noqa: E402
from tvbro_server import auth as AU  # noqa: E402
from tvbro_server import net as NET  # noqa: E402

JF_QC_TOKEN = "jf-token-quickconnect-0001"
JF_PW_TOKEN = "jf-token-password-0002"
PLEX_ACCOUNT_TOKEN = "plex-account-token-0003"
PLEX_SERVER_TOKEN = "plex-server-token-0004"
TOKENS = (JF_QC_TOKEN, JF_PW_TOKEN, PLEX_ACCOUNT_TOKEN, PLEX_SERVER_TOKEN)
JPEG = b"\xff\xd8\xff\xe0synthetic-jpeg"


# --------------------------------------------------------------------------- a small mock HTTP server

class Mock(http.server.BaseHTTPRequestHandler):
    def _go(self):
        u = urllib.parse.urlsplit(self.path)
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n) if n else b""
        req = {"method": self.command, "path": u.path, "query": {k: v[0] for k, v in urllib.parse.parse_qs(u.query).items()},
               "headers": {k.lower(): v for k, v in self.headers.items()}, "body": json.loads(raw) if raw else None}
        self.server.seen.append(req)
        fn = self.server.routes.get((self.command, u.path))
        ans = fn(req) if fn else (404, "text/plain", b"no")
        code, ctype, body = ans[:3]
        extra = ans[3] if len(ans) > 3 else {}        # (code, ctype, body, {header: value}) for media answers
        if isinstance(body, (dict, list, bool)):
            body, ctype = json.dumps(body).encode(), "application/json"
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        for k, v in extra.items():
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    do_GET = do_POST = do_DELETE = do_PUT = _go

    def log_message(self, *a):
        pass


def serve(routes: dict):
    s = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Mock)
    s.routes, s.seen = routes, []
    threading.Thread(target=s.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True).start()
    s.url = f"http://127.0.0.1:{s.server_port}"
    return s


def ok(body, ctype="application/json"):
    return (200, ctype, body)


# --------------------------------------------------------------------------- Jellyfin fixtures

def jf_item(i, typ, name, **kw):
    d = {"Id": i, "Type": typ, "Name": name, "ProductionYear": 2001, "Overview": f"About {name}.",
         "RunTimeTicks": 6000 * 10_000_000, "CommunityRating": 7.5,
         "ProviderIds": {"Tmdb": "100" + i[-1], "Imdb": "tt000000" + i[-1]},
         "UserData": {"PlaybackPositionTicks": 600 * 10_000_000, "Played": False},
         "ImageTags": {"Primary": "tag" + i}, "BackdropImageTags": []}
    d.update(kw)
    return d


JF_MOVIES = [jf_item(f"m{n}", "Movie", f"Example Film {n}") for n in range(1, 4)]
JF_SERIES = jf_item("s1", "Series", "Example Show")
JF_SEASON = jf_item("se1", "Season", "Season 1", SeriesId="s1", IndexNumber=1)
JF_EPS = [jf_item(f"e{n}", "Episode", f"Episode {n}", SeriesId="s1", ParentIndexNumber=1, IndexNumber=n) for n in (1, 2)]
JF_ALL = {d["Id"]: d for d in JF_MOVIES + [JF_SERIES, JF_SEASON] + JF_EPS}


def jellyfin_routes(state):
    def authed(fn):
        def w(req):
            h = req["headers"].get("authorization", "")
            if not h.startswith("MediaBrowser ") or not any(f'Token="{t}"' in h for t in (JF_QC_TOKEN, JF_PW_TOKEN)):
                return (401, "text/plain", b"")
            return fn(req)
        return w

    def items(req):
        q = req["query"]
        pool = [d for d in JF_ALL.values() if d["Type"] in q.get("IncludeItemTypes", "").split(",")]
        start, n = int(q.get("StartIndex", 0)), int(q.get("Limit", 50))
        return ok({"Items": pool[start:start + n], "TotalRecordCount": len(pool)})

    def one(req):
        iid = req["path"].rsplit("/", 1)[1]
        return ok(JF_ALL[iid]) if iid in JF_ALL else (404, "text/plain", b"")

    def auth_by_name(req):
        b = req["body"] or {}
        if b.get("Username") == "alex" and b.get("Pw") == "right pass":
            return ok({"User": {"Id": "u1", "Name": "alex"}, "AccessToken": JF_PW_TOKEN, "ServerId": "srv1"})
        return (401, "text/plain", b"")

    r = {
        ("GET", "/System/Info/Public"): lambda q: ok({"ServerName": "Example Jellyfin", "Id": "srv1", "Version": "10.10.0"}),
        ("GET", "/QuickConnect/Enabled"): lambda q: ok(state.get("qc", True)),
        ("POST", "/QuickConnect/Initiate"): lambda q: ok({"Secret": "secret-1", "Code": "123456", "Authenticated": False}),
        ("GET", "/QuickConnect/Connect"): lambda q: ok({"Authenticated": state.get("approved", False)}),
        ("POST", "/Users/AuthenticateWithQuickConnect"): lambda q: ok(
            {"User": {"Id": "u1", "Name": "alex"}, "AccessToken": JF_QC_TOKEN, "ServerId": "srv1"})
            if (q["body"] or {}).get("Secret") == "secret-1" and state.get("approved") else (401, "text/plain", b""),
        ("POST", "/Users/AuthenticateByName"): auth_by_name,
        ("GET", "/System/Info"): authed(lambda q: ok({"ServerName": "Example Jellyfin", "Version": "10.10.0"})),
        ("GET", "/Users/u1/Views"): authed(lambda q: ok({"Items": [
            {"Id": "lib1", "Name": "Films", "CollectionType": "movies"},
            {"Id": "lib2", "Name": "Shows", "CollectionType": "tvshows"}]})),
        ("GET", "/Users/u1/Items"): authed(items),
        ("GET", "/Shows/s1/Seasons"): authed(lambda q: ok({"Items": [JF_SEASON]})),
        ("GET", "/Shows/s1/Episodes"): authed(lambda q: ok({"Items": JF_EPS} if q["query"].get("seasonId") == "se1" else {"Items": []})),
        ("POST", "/Items/m1/PlaybackInfo"): authed(lambda q: ok({"MediaSources": [
            {"Id": "src1", "Container": "mkv", "SupportsDirectPlay": True}]})),
        ("POST", "/Sessions/Playing/Progress"): authed(lambda q: (204, "text/plain", b"")),
        ("POST", "/Sessions/Playing/Stopped"): authed(lambda q: (204, "text/plain", b"")),
        ("POST", "/Users/u1/PlayedItems/m1"): authed(lambda q: ok({"Played": True})),
        ("DELETE", "/Users/u1/PlayedItems/m1"): authed(lambda q: ok({"Played": False})),
        ("GET", "/Items/m1/Images/Primary"): authed(lambda q: ok(JPEG, "image/jpeg")),
        ("GET", "/Items/m2/Images/Primary"): authed(lambda q: ok(b"<html>no</html>", "text/html")),
        ("POST", "/Sessions/Logout"): authed(lambda q: (204, "text/plain", b"")),
    }
    for d in JF_ALL.values():
        r[("GET", f"/Users/u1/Items/{d['Id']}")] = authed(one)
    return r


# --------------------------------------------------------------------------- Plex fixtures (server and plex.tv)

def px_meta(key, typ, title, **kw):
    d = {"ratingKey": key, "type": typ, "title": title, "year": 1999, "summary": f"About {title}.",
         "thumb": f"/library/metadata/{key}/thumb/1", "art": f"/library/metadata/{key}/art/1", "duration": 5_400_000,
         "audienceRating": 8.1, "Guid": [{"id": "imdb://tt0000010"}, {"id": "tmdb://110"}, {"id": "tvdb://210"}]}
    d.update(kw)
    return d


PX_MOVIES = [px_meta(str(10 + n), "movie", f"Example Plex Film {n}", viewOffset=120_000 if n == 0 else 0,
                     Media=[{"container": "mp4", "Part": [{"key": f"/library/parts/{90 + n}/1/file.mp4"}]}])
             for n in range(3)]
PX_SHOW = px_meta("20", "show", "Example Plex Show")
PX_SEASON = px_meta("21", "season", "Season 1", parentRatingKey="20", index=1)
PX_EPS = [px_meta(str(22 + n), "episode", f"Plex Episode {n + 1}", grandparentRatingKey="20", parentRatingKey="21",
                  parentIndex=1, index=n + 1, viewCount=1 if n == 0 else 0,
                  Media=[{"container": "mkv", "Part": [{"key": f"/library/parts/{80 + n}/1/file.mkv"}]}]) for n in range(2)]
PX_ALL = {d["ratingKey"]: d for d in PX_MOVIES + [PX_SHOW, PX_SEASON] + PX_EPS}


def plex_routes(state, base_url):
    def server(fn):
        def w(req):
            if req["headers"].get("x-plex-token") != PLEX_SERVER_TOKEN:
                return (401, "text/plain", b"")
            return fn(req)
        return w

    def account(fn):
        def w(req):
            if req["headers"].get("x-plex-token") != PLEX_ACCOUNT_TOKEN:
                return (401, "text/plain", b"")
            return fn(req)
        return w

    def section_all(req):
        h = req["headers"]
        start, n = int(h.get("x-plex-container-start", 0)), int(h.get("x-plex-container-size", 50))
        pool = [d for d in PX_ALL.values() if d["type"] == {"1": "movie", "2": "show"}.get(req["query"].get("type"), d["type"])
                and d["type"] in ("movie", "show")]
        return ok({"MediaContainer": {"size": len(pool[start:start + n]), "totalSize": len(pool),
                                      "Metadata": pool[start:start + n]}})

    def mc(md):
        return ok({"MediaContainer": {"Metadata": md}})

    r = {
        ("POST", "/api/v2/pins"): lambda q: ok({"id": 4242, "code": "ABCD", "authToken": None}),
        ("GET", "/api/v2/pins/4242"): lambda q: ok({"id": 4242, "code": "ABCD",
                                                    "authToken": PLEX_ACCOUNT_TOKEN if state.get("approved") else None}),
        ("GET", "/api/v2/user"): account(lambda q: ok({"id": 777, "uuid": "u-777", "username": "alex"})),
        ("GET", "/api/v2/resources"): account(lambda q: ok([
            {"name": "Example Player", "provides": "client,player", "clientIdentifier": "p1", "connections": []},
            {"name": "Example Plex", "provides": "server", "clientIdentifier": "mach1", "accessToken": PLEX_SERVER_TOKEN,
             "owned": True, "connections": [
                 {"protocol": "https", "uri": "https://relay.example.com:8443", "local": False, "relay": True},
                 {"protocol": "http", "uri": base_url, "local": True, "relay": False}]}])),
        ("GET", "/identity"): server(lambda q: ok({"MediaContainer": {"machineIdentifier": "mach1", "version": "1.40.0"}})),
        ("GET", "/library/sections"): server(lambda q: ok({"MediaContainer": {"Directory": [
            {"key": "1", "title": "Films", "type": "movie"}, {"key": "2", "title": "Shows", "type": "show"}]}})),
        ("GET", "/library/sections/1/all"): server(section_all),
        ("GET", "/library/metadata/20/children"): server(lambda q: mc([PX_SEASON])),
        ("GET", "/library/metadata/21/children"): server(lambda q: mc(PX_EPS)),
        ("GET", "/:/timeline"): server(lambda q: ok(b"", "text/plain")),
        ("GET", "/:/scrobble"): server(lambda q: ok(b"", "text/plain")),
        ("GET", "/:/unscrobble"): server(lambda q: ok(b"", "text/plain")),
        ("GET", "/library/metadata/10/thumb/1"): server(lambda q: ok(JPEG, "image/jpeg")),
    }
    for k, d in PX_ALL.items():
        r[("GET", f"/library/metadata/{k}")] = server(lambda q, d=d: mc([d]))
    return r


# --------------------------------------------------------------------------- the tests

class Captured(logging.Handler):
    def __init__(self):
        super().__init__(logging.DEBUG)
        self.lines = []

    def emit(self, rec):
        self.lines.append(self.format(rec))


class Media(unittest.TestCase):
    def setUp(self):
        fast = mock.patch("tvbro_server.auth.ITERATIONS", 1000)       # fast password hashes for the tests only
        fast.start()
        self.addCleanup(fast.stop)
        self.d = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.d, True)
        env = mock.patch.dict(os.environ, {"TVBRO_ALLOW_LOOPBACK_SOURCES": "1", "TVBRO_ALLOW_PRIVATE_SOURCES": ""})
        env.start()
        self.addCleanup(env.stop)
        self.jstate, self.pstate = {}, {}
        self.jf = serve(jellyfin_routes(self.jstate))
        self.px = serve({})
        self.px.routes.update(plex_routes(self.pstate, self.px.url))
        for s in (self.jf, self.px):
            self.addCleanup(s.server_close)
            self.addCleanup(s.shutdown)
        p = mock.patch.object(PLEX, "PLEX_TV", self.px.url)          # plex.tv is the mock too
        p.start()
        self.addCleanup(p.stop)
        self.log = Captured()
        logging.getLogger().addHandler(self.log)
        old_level = logging.getLogger().level
        logging.getLogger().setLevel(logging.DEBUG)
        self.addCleanup(logging.getLogger().setLevel, old_level)
        self.addCleanup(logging.getLogger().removeHandler, self.log)

        self.auth = AU.Auth(self.d, b"k" * 32)
        self.acc = Accounts(self.d, self.auth)
        self.addCleanup(self.acc.close)
        with open(os.path.join(self.d, "orbit-setup-token"), encoding="utf-8") as f:
            tok = f.read()
        self.alex = self.acc.gate(self.auth.session(self.acc.setup(tok, "alex", "correct horse 1", "Alex")))
        self.acc.create_account(self.alex, "sam", "another pass 2")
        self.sam = self.acc.gate(self.auth.session(self.acc.login("sam", "another pass 2", "192.0.2.12")))
        self.key = Fernet(Fernet.generate_key())
        self.media = self.make_media()
        self.api = V1.Api(self.acc, media=self.media)

    def make_media(self):
        def open_(b):
            try:
                return self.key.decrypt(b)
            except InvalidToken:
                return None
        return MediaServers(self.acc.db, TokenVault(os.path.join(self.d, "media.enc"), self.key.encrypt, open_))

    def call(self, method, route, sess, body=None, **query):
        rep = self.api.handle(V1.Call(method, route, sess, body or {}, "192.0.2.10", {k: str(v) for k, v in query.items()}))
        return rep.code, rep

    def ok(self, method, route, sess, body=None, **query):
        code, rep = self.call(method, route, sess, body, **query)
        self.assertEqual(code, 200, rep.obj)
        return rep.obj

    def seen(self, server, method, path):
        return [r for r in server.seen if r["method"] == method and r["path"] == path]

    def link_jellyfin_qc(self, sess=None):
        sess = sess or self.alex
        st = self.ok("POST", "/v1/media/connect/start", sess, {"kind": "jellyfin", "baseUrl": self.jf.url + "/"})
        self.assertEqual((st["method"], st["code"], st["server"]["name"]), ("quickconnect", "123456", "Example Jellyfin"))
        self.assertNotIn("secret", json.dumps(st).lower())
        self.assertEqual(self.ok("POST", "/v1/media/connect/poll", sess, {"id": st["id"]})["status"], "pending")
        self.jstate["approved"] = True
        done = self.ok("POST", "/v1/media/connect/poll", sess, {"id": st["id"]})
        self.assertEqual(done["status"], "linked")
        return done["connections"][0]

    def link_plex(self):
        st = self.ok("POST", "/v1/media/connect/start", self.alex, {"kind": "plex"})
        self.assertEqual((st["method"], st["code"], st["linkUrl"]), ("pin", "ABCD", "https://plex.tv/link"))
        self.assertEqual(self.ok("POST", "/v1/media/connect/poll", self.alex, {"id": st["id"]})["status"], "pending")
        self.pstate["approved"] = True
        done = self.ok("POST", "/v1/media/connect/poll", self.alex, {"id": st["id"]})
        self.assertEqual(len(done["connections"]), 1, "only servers, not players")
        return done["connections"][0]

    # ---- Jellyfin ----
    def test_jellyfin_quick_connect_flow(self):
        conn = self.link_jellyfin_qc()
        self.assertEqual((conn["kind"], conn["serverName"], conn["serverId"], conn["userId"], conn["userName"], conn["baseUrl"]),
                         ("jellyfin", "Example Jellyfin", "srv1", "u1", "alex", self.jf.url))
        init = self.seen(self.jf, "POST", "/QuickConnect/Initiate")[0]
        hdr = init["headers"]["authorization"]
        self.assertTrue(hdr.startswith('MediaBrowser Client="Orbit", Device="Orbit home server", DeviceId="orbit-'), hdr)
        self.assertEqual(init["headers"]["x-emby-authorization"], hdr)
        self.assertEqual(self.seen(self.jf, "GET", "/QuickConnect/Connect")[0]["query"], {"secret": "secret-1"})
        self.assertEqual(self.seen(self.jf, "POST", "/Users/AuthenticateWithQuickConnect")[0]["body"], {"Secret": "secret-1"})
        lst = self.ok("GET", "/v1/media/connections", self.alex)["connections"]
        self.assertEqual([c["id"] for c in lst], [conn["id"]])
        self.assertTrue(self.ok("GET", "/v1/media/connections", self.alex, check=1)["connections"][0]["ok"])
        # the same server and user again updates the link instead of adding a second one
        self.jstate["approved"] = False
        self.link_jellyfin_qc()
        self.assertEqual(len(self.ok("GET", "/v1/media/connections", self.alex)["connections"]), 1)

    def test_jellyfin_quick_connect_off(self):
        self.jstate["qc"] = False
        code, rep = self.call("POST", "/v1/media/connect/start", self.alex, {"kind": "jellyfin", "baseUrl": self.jf.url})
        self.assertEqual(code, 409)
        self.assertIn("username and password", rep.obj["error"])

    def test_jellyfin_password_flow(self):
        code, rep = self.call("POST", "/v1/media/connect/password", self.alex,
                              {"kind": "jellyfin", "baseUrl": self.jf.url, "username": "alex", "password": "wrong"})
        self.assertEqual((code, rep.obj["error"]), (403, "Jellyfin: wrong username or password"))
        out = self.ok("POST", "/v1/media/connect/password", self.alex,
                      {"kind": "jellyfin", "baseUrl": self.jf.url, "username": "alex", "password": "right pass"})
        self.assertEqual(out["connections"][0]["userId"], "u1")
        self.assertEqual(self.seen(self.jf, "POST", "/Users/AuthenticateByName")[-1]["body"],
                         {"Username": "alex", "Pw": "right pass"})
        # the password is not kept anywhere on the server
        for name in os.listdir(self.d):
            p = os.path.join(self.d, name)
            if os.path.isfile(p):
                with open(p, "rb") as fh:
                    self.assertNotIn(b"right pass", fh.read(), name)
        code, _ = self.call("POST", "/v1/media/connect/password", self.alex,
                            {"kind": "plex", "baseUrl": self.jf.url, "username": "alex", "password": "x"})
        self.assertEqual(code, 400, "Plex links with a PIN only")

    def test_jellyfin_browse_play_progress_watched(self):
        cid = self.link_jellyfin_qc()["id"]
        libs = self.ok("GET", "/v1/media/libraries", self.alex)
        self.assertEqual([(lb["id"], lb["kind"], lb["conn"], lb["source"]) for lb in libs["libraries"]],
                         [("lib1", "movie", cid, "jellyfin"), ("lib2", "series", cid, "jellyfin")])
        p1 = self.ok("GET", "/v1/media/items", self.alex, conn=cid, library="lib1", kind="movie", limit=2)
        self.assertEqual(([i["id"] for i in p1["items"]], p1["next"], p1["total"]), (["m1", "m2"], "2", 3))
        q = self.seen(self.jf, "GET", "/Users/u1/Items")[-1]["query"]
        self.assertEqual((q["ParentId"], q["IncludeItemTypes"], q["StartIndex"], q["Limit"]), ("lib1", "Movie", "0", "2"))
        p2 = self.ok("GET", "/v1/media/items", self.alex, conn=cid, library="lib1", kind="movie", limit=2, cursor=p1["next"])
        self.assertEqual(([i["id"] for i in p2["items"]], p2["next"]), (["m3"], None))
        m1 = p1["items"][0]
        self.assertEqual({k: m1[k] for k in ("id", "conn", "source", "kind", "title", "year", "runtime", "rating")},
                         {"id": "m1", "conn": cid, "source": "jellyfin", "kind": "movie", "title": "Example Film 1",
                          "year": 2001, "runtime": 6000, "rating": 7.5})
        self.assertEqual(m1["guids"], {"tmdb": "1001", "imdb": "tt0000001", "tvdb": None})
        self.assertEqual(m1["progress"], {"secs": 600, "dur": 6000, "watched": False})
        self.assertTrue(m1["poster"].startswith("/v1/media/image?conn=" + cid + "&path="), m1["poster"])
        self.assertIsNone(m1["backdrop"])
        # series -> seasons -> episodes
        seasons = self.ok("GET", "/v1/media/children", self.alex, conn=cid, id="s1")["items"]
        self.assertEqual([(s["id"], s["kind"], s["season"], s["seriesId"]) for s in seasons], [("se1", "season", 1, "s1")])
        eps = self.ok("GET", "/v1/media/children", self.alex, conn=cid, id="se1")["items"]
        self.assertEqual([(e["id"], e["kind"], e["season"], e["episode"], e["seriesId"]) for e in eps],
                         [("e1", "episode", 1, 1, "s1"), ("e2", "episode", 1, 2, "s1")])
        self.assertEqual(self.ok("GET", "/v1/media/item", self.alex, conn=cid, id="e2")["title"], "Episode 2")
        # playback: a direct URL with this user's token (only in this answer)
        pb = self.ok("POST", "/v1/media/playback", self.alex, {"conn": cid, "id": "m1"})
        u = urllib.parse.urlsplit(pb["url"])
        self.assertEqual((u.path, pb["direct"], pb["container"], pb["resumeSecs"]), ("/Videos/m1/stream", True, "mkv", 600))
        self.assertEqual(urllib.parse.parse_qs(u.query), {"static": ["true"], "api_key": [JF_QC_TOKEN], "mediaSourceId": ["src1"]})
        code, _ = self.call("POST", "/v1/media/playback", self.alex, {"conn": cid, "id": "s1"})
        self.assertEqual(code, 400, "a series is not playable")
        # progress and watched reach the server with the right values
        self.ok("POST", "/v1/media/progress", self.alex, {"conn": cid, "id": "m1", "secs": 90.5, "dur": 6000, "state": "paused"})
        self.assertEqual(self.seen(self.jf, "POST", "/Sessions/Playing/Progress")[-1]["body"],
                         {"ItemId": "m1", "PositionTicks": 905_000_000, "IsPaused": True, "PlayMethod": "DirectPlay",
                          "CanSeek": True})
        self.ok("POST", "/v1/media/progress", self.alex, {"conn": cid, "id": "m1", "secs": 100, "dur": 6000, "state": "stopped"})
        self.assertEqual(self.seen(self.jf, "POST", "/Sessions/Playing/Stopped")[-1]["body"]["PositionTicks"], 1_000_000_000)
        code, _ = self.call("POST", "/v1/media/progress", self.alex, {"conn": cid, "id": "m1", "secs": 1, "state": "rewinding"})
        self.assertEqual(code, 400)
        self.ok("POST", "/v1/media/watched", self.alex, {"conn": cid, "id": "m1", "watched": True})
        self.ok("POST", "/v1/media/watched", self.alex, {"conn": cid, "id": "m1", "watched": False})
        self.assertEqual(len(self.seen(self.jf, "POST", "/Users/u1/PlayedItems/m1")), 1)
        self.assertEqual(len(self.seen(self.jf, "DELETE", "/Users/u1/PlayedItems/m1")), 1)
        # artwork through the server: image types only, artwork paths only
        path = urllib.parse.parse_qs(urllib.parse.urlsplit(m1["poster"]).query)["path"][0]
        code, rep = self.call("GET", "/v1/media/image", self.alex, conn=cid, path=path)
        self.assertEqual((code, rep.raw, rep.ctype, rep.headers["Cache-Control"]), (200, JPEG, "image/jpeg", "private, max-age=86400"))
        code, _ = self.call("GET", "/v1/media/image", self.alex, conn=cid, path="/Items/m2/Images/Primary?tag=x")
        self.assertEqual(code, 502, "an HTML answer is not passed on")
        for bad in ("/System/Info", "/Items/../System/Info", "//evil.example.com/x", "http://evil.example.com/x",
                    "/Items/m1/Images/Primary?api_key=x"):
            code, _ = self.call("GET", "/v1/media/image", self.alex, conn=cid, path=bad)
            self.assertEqual(code, 400, bad)

    def test_expired_token_asks_to_link_again(self):
        cid = self.link_jellyfin_qc()["id"]
        self.media.vault.put(cid, "a-revoked-token")
        code, rep = self.call("GET", "/v1/media/libraries", self.alex, conn=cid)
        self.assertEqual(code, 409)
        self.assertIn("link it again", rep.obj["error"])
        self.assertEqual(self.ok("GET", "/v1/media/libraries", self.alex)["errors"][0]["conn"], cid)

    # ---- Plex ----
    def test_plex_pin_flow(self):
        conn = self.link_plex()
        self.assertEqual((conn["kind"], conn["serverName"], conn["serverId"], conn["userId"], conn["userName"], conn["baseUrl"]),
                         ("plex", "Example Plex", "mach1", "777", "alex", self.px.url))
        pin = self.seen(self.px, "POST", "/api/v2/pins")[0]["headers"]
        self.assertEqual((pin["x-plex-product"], pin["accept"]), ("Orbit", "application/json"))
        self.assertTrue(pin["x-plex-client-identifier"].startswith("orbit-"))
        res = self.seen(self.px, "GET", "/api/v2/resources")[0]
        self.assertEqual(res["query"], {"includeHttps": "1", "includeRelay": "0"})
        self.assertEqual(self.seen(self.px, "GET", "/identity")[0]["headers"]["x-plex-token"], PLEX_SERVER_TOKEN,
                         "the server's own access token, not the account token")
        self.assertIn(("127.0.0.1", self.px.server_port, frozenset({"127.0.0.1"})), self.media.private_hosts(),
                      "an owner's LAN link is pinned to the address it resolved to")

    def test_plex_browse_play_progress_watched(self):
        cid = self.link_plex()["id"]
        dev = self.acc.db.one("SELECT device_id FROM media_connections WHERE id=?", (cid,))[0]
        libs = self.ok("GET", "/v1/media/libraries", self.alex, conn=cid)["libraries"]
        self.assertEqual([(lb["id"], lb["kind"]) for lb in libs], [("1", "movie"), ("2", "series")])
        p1 = self.ok("GET", "/v1/media/items", self.alex, conn=cid, library="1", kind="movie", limit=2)
        self.assertEqual(([i["id"] for i in p1["items"]], p1["next"], p1["total"]), (["10", "11"], "2", 3))
        req = self.seen(self.px, "GET", "/library/sections/1/all")[-1]
        self.assertEqual((req["headers"]["x-plex-container-start"], req["headers"]["x-plex-container-size"], req["query"]["type"]),
                         ("0", "2", "1"))
        p2 = self.ok("GET", "/v1/media/items", self.alex, conn=cid, library="1", kind="movie", limit=2, cursor="2")
        self.assertEqual(([i["id"] for i in p2["items"]], p2["next"]), (["12"], None))
        f = p1["items"][0]
        self.assertEqual((f["kind"], f["source"], f["runtime"], f["rating"], f["progress"]),
                         ("movie", "plex", 5400, 8.1, {"secs": 120, "dur": 5400, "watched": False}))
        self.assertEqual(f["guids"], {"tmdb": "110", "imdb": "tt0000010", "tvdb": "210"})
        seasons = self.ok("GET", "/v1/media/children", self.alex, conn=cid, id="20")["items"]
        self.assertEqual([(s["id"], s["kind"], s["season"], s["seriesId"]) for s in seasons], [("21", "season", 1, "20")])
        eps = self.ok("GET", "/v1/media/children", self.alex, conn=cid, id="21")["items"]
        self.assertEqual([(e["id"], e["season"], e["episode"], e["seriesId"], e["progress"]["watched"]) for e in eps],
                         [("22", 1, 1, "20", True), ("23", 1, 2, "20", False)])
        pb = self.ok("POST", "/v1/media/playback", self.alex, {"conn": cid, "id": "10"})
        self.assertEqual((pb["url"], pb["container"], pb["resumeSecs"], pb["direct"]),
                         (self.px.url + "/library/parts/90/1/file.mp4?X-Plex-Token=" + PLEX_SERVER_TOKEN, "mp4", 120, True))
        self.ok("POST", "/v1/media/progress", self.alex, {"conn": cid, "id": "23", "secs": 61.2, "dur": 5400, "state": "playing"})
        tl = self.seen(self.px, "GET", "/:/timeline")[-1]
        self.assertEqual(tl["query"], {"ratingKey": "23", "key": "/library/metadata/23", "identifier": "com.plexapp.plugins.library",
                                       "state": "playing", "time": "61200", "duration": "5400000"})
        self.assertEqual(tl["headers"]["x-plex-client-identifier"], dev)
        self.ok("POST", "/v1/media/watched", self.alex, {"conn": cid, "id": "23", "watched": True})
        self.ok("POST", "/v1/media/watched", self.alex, {"conn": cid, "id": "22", "watched": False})
        self.assertEqual(self.seen(self.px, "GET", "/:/scrobble")[-1]["query"], {"key": "23", "identifier": "com.plexapp.plugins.library"})
        self.assertEqual(self.seen(self.px, "GET", "/:/unscrobble")[-1]["query"]["key"], "22")
        code, rep = self.call("GET", "/v1/media/image", self.alex, conn=cid, path=f["poster"].split("path=", 1)[1].replace("%2F", "/"))
        self.assertEqual((code, rep.raw), (200, JPEG))

    def test_plex_connection_order(self):
        order = PLEX.Plex.candidates([
            {"uri": "https://remote.example.com:32400", "protocol": "https", "local": False},
            {"uri": "http://192.0.2.5:32400", "protocol": "http", "local": True},
            {"uri": "https://192-0-2-5.abc.plex.direct:32400", "protocol": "https", "local": True},
            {"uri": "https://relay.example.com:8443", "protocol": "https", "local": False, "relay": True},
            {"uri": "ftp://192.0.2.5", "local": True}])
        self.assertEqual(order, ["https://192-0-2-5.abc.plex.direct:32400", "http://192.0.2.5:32400",
                                 "https://remote.example.com:32400"])

    # ---- rules shared by both ----
    def test_another_account_gets_404(self):
        cid = self.link_jellyfin_qc()["id"]
        st = self.ok("POST", "/v1/media/connect/start", self.alex, {"kind": "plex"})
        self.assertEqual(self.ok("GET", "/v1/media/connections", self.sam)["connections"], [])
        self.assertEqual(self.ok("GET", "/v1/media/libraries", self.sam), {"libraries": [], "errors": []})
        for method, path, body, query in (
                ("GET", "/v1/media/libraries", None, {"conn": cid}),
                ("GET", "/v1/media/items", None, {"conn": cid, "library": "lib1"}),
                ("GET", "/v1/media/item", None, {"conn": cid, "id": "m1"}),
                ("GET", "/v1/media/children", None, {"conn": cid, "id": "s1"}),
                ("GET", "/v1/media/image", None, {"conn": cid, "path": "/Items/m1/Images/Primary"}),
                ("POST", "/v1/media/playback", {"conn": cid, "id": "m1"}, {}),
                ("POST", "/v1/media/progress", {"conn": cid, "id": "m1", "secs": 1, "dur": 2, "state": "playing"}, {}),
                ("POST", "/v1/media/watched", {"conn": cid, "id": "m1", "watched": True}, {}),
                ("POST", "/v1/media/connections/delete", {"id": cid}, {}),
                ("POST", "/v1/media/connect/poll", {"id": st["id"]}, {})):
            code, _ = self.call(method, path, self.sam, body, **query)
            self.assertEqual(code, 404, path)
        code, _ = self.call("GET", "/v1/media/connections", None)
        self.assertEqual(code, 401)
        # the owner can delete their own; the token goes too and the server is told
        self.ok("POST", "/v1/media/connections/delete", self.alex, {"id": cid})
        self.assertEqual(len(self.seen(self.jf, "POST", "/Sessions/Logout")), 1)
        self.assertIsNone(self.media.vault.get(cid))
        self.assertEqual(self.ok("GET", "/v1/media/connections", self.alex)["connections"], [])
        self.assertNotIn(("127.0.0.1", self.jf.server_port), self.media.private_hosts())

    def test_tokens_never_in_logs_lists_or_the_database(self):
        jcid = self.link_jellyfin_qc()["id"]
        self.ok("POST", "/v1/media/connect/password", self.alex,
                {"kind": "jellyfin", "baseUrl": self.jf.url, "username": "alex", "password": "right pass"})
        pcid = self.link_plex()["id"]
        answers = [self.ok("GET", "/v1/media/connections", self.alex), self.ok("GET", "/v1/media/libraries", self.alex),
                   self.ok("GET", "/v1/media/items", self.alex, conn=jcid, library="lib1"),
                   self.ok("GET", "/v1/media/items", self.alex, conn=pcid, library="1"),
                   self.ok("GET", "/v1/media/children", self.alex, conn=pcid, id="21")]
        self.call("GET", "/v1/media/image", self.alex, conn=jcid, path="/Items/m2/Images/Primary?tag=x")   # an error path
        self.media.vault.put(jcid, JF_QC_TOKEN + "-revoked")
        self.call("GET", "/v1/media/libraries", self.alex, conn=jcid)                                   # another one
        blob = json.dumps(answers) + "\n".join(self.log.lines)
        self.assertTrue(self.log.lines, "something was logged")
        for t in TOKENS:
            self.assertNotIn(t, blob)
        self.acc.db.db.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        for name in os.listdir(self.d):
            p = os.path.join(self.d, name)
            if os.path.isfile(p):
                with open(p, "rb") as fh:
                    data = fh.read()
                for t in TOKENS:
                    self.assertNotIn(t.encode(), data, name)
        # the vault opens with the key after a restart and loses tokens of connections that are gone
        self.acc.db.run("DELETE FROM media_connections WHERE id=?", (pcid,))
        again = self.make_media()
        self.assertEqual(again.vault.get(jcid), JF_QC_TOKEN + "-revoked")
        self.assertIsNone(again.vault.get(pcid))

    def test_members_cannot_open_new_lan_addresses(self):
        with mock.patch.object(NET, "allow_private", return_value=False):
            code, rep = self.call("POST", "/v1/media/connect/start", self.sam, {"kind": "jellyfin", "baseUrl": "http://192.0.2.60:8096"})
        self.assertEqual(code, 502)
        self.assertIn("not allowed", rep.obj["error"])
        hs = set()
        self.media._allow(self.sam, hs)("http://192.0.2.60:8096")
        self.assertEqual(hs, set())
        self.media._allow(self.alex, hs)("http://192.0.2.60:8096")
        self.assertEqual(hs, {("192.0.2.60", 8096)})

    def test_an_address_being_linked_is_allowed_only_for_that_sign_in(self):
        """An owner signing in to a LAN server: only that sign-in's own requests may reach it until it is saved;
        the global allowlist (every other request of the server) never lists it."""
        seen = []
        others = []

        def peek(stage):
            seen.append((stage, NET._host_listed("192.0.2.60", 8096), NET._host_listed("192.0.2.61", 32400)))
            t = threading.Thread(target=lambda: others.append(NET._host_listed("192.0.2.60", 8096)))
            t.start()
            t.join()                                   # another request (thread) at the same moment

        class Fake:
            label = "Fake"

            @classmethod
            def sign_in_start(cls, http, base_url, device):
                peek("start")
                return {"method": "pin"}, {"base": base_url}

            @classmethod
            def sign_in_poll(cls, http, state, device):
                peek("poll-before")
                state["allow"]("http://192.0.2.61:32400")      # a server found on the way (Plex resources)
                peek("poll-after")
                return None

        self.media.kinds = {**self.media.kinds, "jellyfin": Fake}
        st = self.ok("POST", "/v1/media/connect/start", self.alex, {"kind": "jellyfin", "baseUrl": "http://192.0.2.60:8096"})
        self.assertNotIn(("192.0.2.60", 8096), self.media.private_hosts())
        self.assertFalse(NET._host_listed("192.0.2.60", 8096))
        self.assertEqual(self.ok("POST", "/v1/media/connect/poll", self.alex, {"id": st["id"]})["status"], "pending")
        self.assertEqual(seen, [("start", True, False), ("poll-before", True, False), ("poll-after", True, True)])
        self.assertEqual(others, [False, False, False])
        self.assertFalse(NET._host_listed("192.0.2.61", 32400))
        self.assertEqual(self.media.private_hosts(), set())
        # a member's sign-in never gets the address, even inside its own requests
        seen.clear()
        self.ok("POST", "/v1/media/connect/start", self.sam, {"kind": "jellyfin", "baseUrl": "http://192.0.2.60:8096"})
        self.assertEqual(seen, [("start", False, False)])

    def _fake_password_kind(self, base_url):
        from orbit.connectors.base import Linked

        class Fake:
            label = "Fake"

            def __init__(self, base, tok, uid, dev, http):
                self.base = base

            @classmethod
            def sign_in_password(cls, http, base, user, pw, device):
                return Linked(base_url, "Example", "srv-" + user, "u-" + user, user, "tok-" + user)

            def health(self):
                hp = (urllib.parse.urlsplit(self.base).hostname, urllib.parse.urlsplit(self.base).port)
                try:
                    NET.resolve(*hp)          # the same check every connector request makes
                except NET.Blocked as e:
                    raise ConnectorError(502, str(e))
                return True

        self.media.kinds = {**self.media.kinds, "jellyfin": Fake}

    def test_member_link_is_never_a_lan_allowance(self):
        """H1: a member links a public name; it later re-resolves to a LAN address (DNS rebinding): refused. An
        owner-linked LAN server keeps working, but only at the address recorded when it was linked."""
        dns = {"media.example.com": "198.51.100.7", "jf.example.lan": "192.0.2.40"}

        def fake_dns(host, port, *a, **k):
            if host not in dns:
                raise socket.gaierror("no such host")
            return [(socket.AF_INET, socket.SOCK_STREAM, 6, "", (dns[host], port))]

        with mock.patch.object(NET, "allow_private", return_value=False),                 mock.patch.object(NET.socket, "getaddrinfo", fake_dns),                 mock.patch.object(NET, "_private_hosts", lambda: self.media.private_hosts()):
            self._fake_password_kind("http://media.example.com:8096")
            self.ok("POST", "/v1/media/connect/password", self.sam,
                    {"kind": "jellyfin", "baseUrl": "http://media.example.com:8096", "username": "sam", "password": "x"})
            row = self.acc.db.one("SELECT lan_ok, lan_ips FROM media_connections WHERE account_id=?",
                                  (self.sam["account_id"],))
            self.assertEqual(tuple(row), (0, ""))
            self.assertEqual(self.media.private_hosts(), set())
            dns["media.example.com"] = "192.0.2.77"                    # the member's name now points at the LAN
            with self.assertRaises(NET.Blocked):
                NET.resolve("media.example.com", 8096)
            self.assertFalse(self.ok("GET", "/v1/media/connections", self.sam, check=1)["connections"][0]["ok"])

            # an owner links a LAN server: allowed, pinned to 192.0.2.40
            self._fake_password_kind("http://jf.example.lan:8096")
            self.media._allow(self.alex, set())                       # (managers only)
            self.ok("POST", "/v1/media/connect/password", self.alex,
                    {"kind": "jellyfin", "baseUrl": "http://jf.example.lan:8096", "username": "alex", "password": "x"})
            self.assertEqual(self.media.private_hosts(), {("jf.example.lan", 8096, frozenset({"192.0.2.40"}))})
            self.assertTrue(NET.resolve("jf.example.lan", 8096))
            self.assertTrue(self.ok("GET", "/v1/media/connections", self.alex, check=1)["connections"][0]["ok"])
            dns["jf.example.lan"] = "192.0.2.41"                       # the same name moved: not the pinned address
            with self.assertRaises(NET.Blocked):
                NET.resolve("jf.example.lan", 8096)
            # the member's public name still cannot reach the LAN, even at the owner's pinned address
            dns["media.example.com"] = "192.0.2.40"
            with self.assertRaises(NET.Blocked):
                NET.resolve("media.example.com", 8096)

    def test_pending_sign_ins_expire_and_are_capped(self):
        now = [1000.0]
        self.media.clock = lambda: now[0]
        st = self.ok("POST", "/v1/media/connect/start", self.alex, {"kind": "plex"})
        now[0] += 601
        code, _ = self.call("POST", "/v1/media/connect/poll", self.alex, {"id": st["id"]})
        self.assertEqual(code, 404)
        for _ in range(5):
            self.ok("POST", "/v1/media/connect/start", self.alex, {"kind": "plex"})
        code, _ = self.call("POST", "/v1/media/connect/start", self.alex, {"kind": "plex"})
        self.assertEqual(code, 429)
        self.ok("POST", "/v1/media/connect/start", self.sam, {"kind": "plex"})       # per account

    def test_bad_input(self):
        for body in ({"kind": "emby"}, {"kind": "jellyfin"}, {"kind": "jellyfin", "baseUrl": "ftp://192.0.2.1"},
                     {"kind": "jellyfin", "baseUrl": "http://user:pw@jf.example.com"}, {"kind": "jellyfin", "baseUrl": "http://jf.example.com:22"}):
            code, _ = self.call("POST", "/v1/media/connect/start", self.alex, body)
            self.assertEqual(code, 400, body)
        cid = self.link_jellyfin_qc()["id"]
        for q in ({"conn": cid, "id": "../System"}, {"conn": cid, "id": ""}, {"conn": cid, "id": "a/b"}):
            code, _ = self.call("GET", "/v1/media/item", self.alex, **q)
            self.assertEqual(code, 400, q)
        for q in ({"conn": cid, "library": "lib1", "limit": "1000"}, {"conn": cid, "library": "lib1", "cursor": "-1"},
                  {"conn": cid, "library": "lib1", "kind": "episode"}, {"conn": cid}):
            code, _ = self.call("GET", "/v1/media/items", self.alex, **q)
            self.assertEqual(code, 400, q)


class Helpers(unittest.TestCase):
    def test_base_url(self):
        self.assertEqual(normalize_base_url("JF.Example.com:8096/jellyfin/"), "http://jf.example.com:8096/jellyfin")
        self.assertEqual(normalize_base_url("https://[2001:db8::1]:8920"), "https://[2001:db8::1]:8920")
        for bad in ("", "file:///etc/passwd", "http://jf.example.com/?x=1", "http://a@jf.example.com"):
            with self.assertRaises(ConnectorError):
                normalize_base_url(bad)

    def test_safe_path(self):
        self.assertEqual(safe_path("/Items/a/Images/Primary?tag=1", ("/Items/",)), "/Items/a/Images/Primary?tag=1")
        for bad in ("/Items/a b", "/Items/a\r\nX: y", "/Items/a#x", "/Items/a?X-Plex-Token=1", None):
            with self.assertRaises(ConnectorError):
                safe_path(bad, ("/Items/",))


class Migration(unittest.TestCase):
    def test_upgrade_from_version_2(self):
        d = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, d, True)
        m = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, m, True)
        for n in ("001_accounts.sql", "002_session_kind.sql"):
            shutil.copy(os.path.join(ODB.MIGRATIONS, n), m)
        old = ODB.Database(os.path.join(d, "orbit.db"), m)
        self.assertEqual(old.version(), 2)
        old.run("INSERT INTO accounts VALUES ('a1','alex','alex','Alex','owner','h',0,1)")
        old.close()
        new = ODB.Database(os.path.join(d, "orbit.db"))
        self.addCleanup(new.close)
        self.assertEqual(new.version(), len(ODB.migrations()))
        self.assertTrue(os.path.exists(os.path.join(d, "orbit.db.bak-v2")))
        new.run("INSERT INTO media_connections(id, account_id, kind, base_url, device_id, created) "
                "VALUES ('c1','a1','jellyfin','http://jf.example.com:8096','orbit-x',1)")
        with self.assertRaises(sqlite3.IntegrityError):
            new.run("INSERT INTO media_connections(id, account_id, kind, base_url, device_id, created) "
                    "VALUES ('c2','a1','emby','http://jf.example.com','orbit-y',1)")
        cols = {r[1] for r in new.q("PRAGMA table_info(media_connections)")}
        self.assertFalse({c for c in cols if "token" in c}, "no token column: tokens live sealed in media.enc")
        new.run("DELETE FROM accounts WHERE id='a1'")
        self.assertEqual(new.one("SELECT COUNT(*) FROM media_connections")[0], 0, "removed with the account")

    def test_upgrade_to_version_5_keeps_owner_lan_links_only(self):
        d = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, d, True)
        m = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, m, True)
        for n, p in ODB.migrations()[:4]:
            shutil.copy(p, m)
        old = ODB.Database(os.path.join(d, "orbit.db"), m)
        self.assertEqual(old.version(), 4)
        old.run("INSERT INTO accounts VALUES ('a1','alex','alex','Alex','owner','h',0,1)")
        old.run("INSERT INTO accounts VALUES ('a2','sam','sam','Sam','member','h',0,1)")
        for cid, acc in (("c1", "a1"), ("c2", "a2")):
            old.run("INSERT INTO media_connections(id, account_id, kind, base_url, device_id, created) "
                    "VALUES (?,?,'jellyfin','http://jf.example.lan:8096','orbit-x',1)", (cid, acc))
        old.close()
        new = ODB.Database(os.path.join(d, "orbit.db"))
        self.addCleanup(new.close)
        self.assertEqual(dict(new.q("SELECT id, lan_ok FROM media_connections")), {"c1": 1, "c2": 0})


class Server(unittest.TestCase):
    """The real server (run.py): query parameters reach the routes, artwork comes back as an image, tokens stay
    out of the server log, and media.enc is written in the data folder."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp()
        cls.data = os.path.join(cls.tmp, "data")
        with socket.socket() as s:
            s.bind(("127.0.0.1", 0))
            cls.port = s.getsockname()[1]
        cls.jf = serve(jellyfin_routes({}))
        env = dict(os.environ, TVBRO_PORT=str(cls.port), TVBRO_DATA=cls.data, PYTHONDONTWRITEBYTECODE="1",
                   TVBRO_ALLOW_LOOPBACK_SOURCES="1")
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
        cls.jf.shutdown()
        cls.jf.server_close()
        shutil.rmtree(cls.tmp, ignore_errors=True)

    @classmethod
    def req(cls, method, path, body=None, headers=None):
        h = {"Content-Type": "application/json"} if body is not None else {}
        h.update(headers or {})
        data = json.dumps(body).encode() if body is not None else None
        r = urllib.request.Request(cls.base + path, data=data, method=method, headers=h)
        try:
            with urllib.request.urlopen(r, timeout=60) as f:
                return f.status, dict(f.headers), f.read()
        except urllib.error.HTTPError as e:
            return e.code, dict(e.headers), e.read()

    def test_through_http(self):
        with open(os.path.join(self.data, "orbit-setup-token"), encoding="utf-8") as f:
            body = {"token": f.read(), "username": "alex", "password": "correct horse 1"}
        code, hdr, raw = self.req("POST", "/v1/setup", body, {"X-TvBro": "1"})
        self.assertEqual(code, 200, raw)
        h = {"Cookie": hdr["Set-Cookie"].split(";", 1)[0], "X-CSRF-Token": json.loads(raw)["csrf"]}
        code, _, raw = self.req("POST", "/v1/media/connect/password",
                                {"kind": "jellyfin", "baseUrl": self.jf.url, "username": "alex", "password": "right pass"}, h)
        self.assertEqual(code, 200, raw)
        cid = json.loads(raw)["connections"][0]["id"]
        code, _, raw = self.req("GET", f"/v1/media/items?conn={cid}&library=lib1&kind=movie&limit=2", headers=h)
        self.assertEqual(code, 200, raw)
        items = json.loads(raw)
        self.assertEqual(([i["id"] for i in items["items"]], items["next"]), (["m1", "m2"], "2"))
        code, hdr, raw = self.req("GET", items["items"][0]["poster"], headers=h)
        self.assertEqual((code, hdr["Content-Type"], raw, hdr["X-Content-Type-Options"]), (200, "image/jpeg", JPEG, "nosniff"))
        code, _, _ = self.req("GET", f"/v1/media/items?conn={cid}&library=lib1", headers={"Cookie": h["Cookie"] + "x"})
        self.assertEqual(code, 401)
        self.assertTrue(os.path.exists(os.path.join(self.data, "media.enc")))
        self.log.flush()
        with open(os.path.join(self.tmp, "server.log"), "rb") as f:
            log = f.read()
        self.assertIn(b"/v1/media/items", log)
        self.assertNotIn(JF_PW_TOKEN.encode(), log)
        self.assertNotIn(b"right pass", log)


if __name__ == "__main__":
    unittest.main()


class PlexUnreachableMessage(unittest.TestCase):
    def test_lists_what_was_tried_and_hints_at_a_firewall(self):
        from orbit.connectors.plex import Plex
        m = Plex.unreachable_message(1, [("http://192.0.2.5:32400", "the server did not answer"),
                                         ("https://192-0-2-5.abc.plex.direct:32400", "the server did not answer")])
        self.assertIn("192.0.2.5:32400 (the server did not answer)", m)
        self.assertIn("firewall", m)
        self.assertIn("no servers", Plex.unreachable_message(0, []))
        self.assertIn("relays", Plex.unreachable_message(1, []))


class PlexTypedAddress(unittest.TestCase):
    """A Plex server plex.tv lists under other addresses can be linked by the address the owner typed."""

    class FakeHttp:
        def __init__(self, answers):
            self.answers, self.calls = answers, []

        def json(self, method, url, headers=None, body=None, **kw):
            from orbit.connectors.base import ConnectorError
            self.calls.append((url, (headers or {}).get("X-Plex-Token")))
            for suffix, ans in self.answers.items():
                if url.endswith(suffix):
                    if isinstance(ans, Exception):
                        raise ans
                    return ans
            raise ConnectorError(502, "Plex: the server did not answer")

    def test_matches_the_account_server_and_uses_its_token(self):
        from orbit.connectors.plex import Plex
        http = self.FakeHttp({"/identity": {"MediaContainer": {"machineIdentifier": "m1"}}, "/library/sections": {}})
        servers = [{"clientIdentifier": "m1", "name": "Home", "accessToken": "srv-tok"}]
        allowed, tried = [], []
        ln = Plex.link_typed(http, "http://192.0.2.20:32400", servers, "acct-tok", "dev", {"id": 7, "username": "u"},
                             allowed.append, tried)
        self.assertEqual((ln.base_url, ln.server_name, ln.server_id, ln.token), ("http://192.0.2.20:32400", "Home", "m1", "srv-tok"))
        self.assertEqual(allowed, ["http://192.0.2.20:32400"])
        self.assertEqual(http.calls[-1], ("http://192.0.2.20:32400/library/sections", "srv-tok"))

    def test_unreachable_or_refused_is_reported(self):
        from orbit.connectors.base import ConnectorError
        from orbit.connectors.plex import Plex
        tried = []
        self.assertIsNone(Plex.link_typed(self.FakeHttp({}), "http://192.0.2.20:32400", [], "t", "d", {}, None, tried))
        self.assertIn("did not answer", tried[0][1])
        refused = self.FakeHttp({"/identity": {"MediaContainer": {"machineIdentifier": "zz"}},
                                 "/library/sections": ConnectorError(409, "Plex: no", relink=True)})
        tried = []
        self.assertIsNone(Plex.link_typed(refused, "http://192.0.2.20:32400", [], "t", "d", {}, None, tried))
        self.assertIn("no access", tried[0][1])
