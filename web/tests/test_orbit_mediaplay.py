# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""Plex / Jellyfin playback through the home server (orbit/mediaplay.py, POST /v1/media/play, the relay).

The mock Plex and Jellyfin servers of test_orbit_media.py get a media file (synthetic bytes, Range aware), HLS
playlists and segments, subtitles and the progress / stop calls. A small HTTP server stands in for app.py's handler
and serves /v1/media/stream/... with Plays.serve, so the relay is tested over real sockets: start, playlist rewrite
(no token anywhere a device sees), segments, Range, subtitles (SRT converted), progress, scrobble, stop, sign-out,
unlink, ownership and the paths the relay refuses."""
import http.server
import json
import os
import sys
import threading
import time
import unittest
from unittest import mock
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "server"))
import test_orbit_media as TM  # noqa: E402
from orbit import mediaplay as MP  # noqa: E402
from orbit.connectors import plex as PX  # noqa: E402
from orbit.connectors.base import direct_ok  # noqa: E402

FILE = bytes(range(256)) * 400                       # stands in for a film
SEG = b"\x47" + b"segment-bytes" * 50                 # an MPEG-TS segment
SRT = "1\r\n00:00:01,000 --> 00:00:02,500\r\nHello there\r\n\r\n2\r\n00:00:03,000 --> 00:00:04,000\r\nBye\r\n"
VTT = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHallo\n"


def ranged(req, data=FILE, ctype="video/mp4"):
    rng = req["headers"].get("range")
    if rng and rng.startswith("bytes="):
        a, _, b = rng[6:].partition("-")
        a = int(a)
        b = int(b) if b else len(data) - 1
        if a >= len(data):
            return (416, "text/plain", b"", {"Content-Range": f"bytes */{len(data)}"})
        return (206, ctype, data[a:b + 1], {"Content-Range": f"bytes {a}-{b}/{len(data)}", "Accept-Ranges": "bytes"})
    return (200, ctype, data, {"Accept-Ranges": "bytes"})


def px_streams():
    return [{"id": 700, "streamType": 1, "codec": "h264"},
            {"id": 701, "streamType": 2, "codec": "aac", "displayTitle": "English (AAC Stereo)", "languageCode": "eng", "selected": True},
            {"id": 702, "streamType": 2, "codec": "ac3", "displayTitle": "Français (AC3 5.1)", "languageCode": "fra"},
            {"id": 601, "streamType": 3, "codec": "srt", "displayTitle": "English (SRT External)", "languageCode": "eng",
             "key": "/library/streams/601"},
            {"id": 602, "streamType": 3, "codec": "ass", "displayTitle": "Signs (ASS)"}]


PX_FILM = TM.px_meta("10", "movie", "Example Plex Film 0", viewOffset=120_000, duration=600_000,
                     Media=[{"container": "mp4", "videoCodec": "h264", "audioCodec": "aac", "Part": [
                         {"id": 501, "key": "/library/parts/501/1/file.mp4", "container": "mp4", "Stream": px_streams()}]}])
PX_EP = TM.px_meta("22", "episode", "Pilot", grandparentRatingKey="20", grandparentTitle="Example Plex Show",
                   parentRatingKey="21", parentIndex=1, index=1, duration=1_200_000,
                   Media=[{"container": "mkv", "videoCodec": "hevc", "audioCodec": "eac3", "Part": [
                       {"id": 502, "key": "/library/parts/502/1/file.mkv", "container": "mkv", "Stream": []}]}])


def plex_play_routes(base, tx=None):
    """``tx``: the fake Plex's transcoder, like a server that runs one transcode at a time: start.m3u8 for another
    session while one runs answers 400 until that one is stopped (``tx["active"]``: session -> client identifier;
    ``tx["decision"]``: the decision answer to give)."""
    S = TM.PLEX_SERVER_TOKEN
    tx = {} if tx is None else tx
    tx.setdefault("active", {})

    def server(fn):
        def w(req):
            if req["headers"].get("x-plex-token") != S:
                return (401, "text/plain", b"")
            return fn(req)
        return w
    master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1920x1080\nsession/fake/base/index.m3u8\n"
    index = ("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:4,\n00000.ts\n#EXTINF:4,\n"
             f"{base}/video/:/transcode/universal/session/fake/base/00001.ts?X-Plex-Token=leaked\n"
             "#EXTINF:4,\nhttp://elsewhere.example.com/x.ts\n#EXT-X-ENDLIST\n")
    T = "/video/:/transcode/universal"

    def decision(q):
        tx.setdefault("decisions", []).append(q["query"].get("session"))
        return TM.ok(tx.get("decision") or {"MediaContainer": {"generalDecisionCode": 1001,
                                                                "generalDecisionText": "Direct play not available; Conversion OK."}})

    def start(q):
        sid = q["query"].get("session")
        busy = [s for s in tx["active"] if s != sid]
        if busy:
            tx.setdefault("refused", []).append(sid)
            return (400, "text/html", f"<html><body>Bad Request X-Plex-Token={S}</body></html>".encode())
        tx["active"][sid] = q["headers"].get("x-plex-client-identifier")
        return TM.ok(master.encode(), "application/vnd.apple.mpegurl")

    def stop(q):
        tx["active"].pop(q["query"].get("session"), None)
        return TM.ok(b"", "text/plain")

    def sessions(q):
        return TM.ok({"MediaContainer": {"size": len(tx["active"]), "Metadata": [
            {"type": "movie", "ratingKey": "10", "Player": {"machineIdentifier": cid, "product": "Orbit"},
             "TranscodeSession": {"key": f"/transcode/sessions/{s}"}} for s, cid in tx["active"].items()]}})
    return {
        ("GET", T + "/decision"): server(decision),
        ("GET", "/status/sessions"): server(sessions),
        ("GET", "/library/metadata/10"): server(lambda q: TM.ok({"MediaContainer": {"Metadata": [PX_FILM]}})),
        ("GET", "/library/metadata/22"): server(lambda q: TM.ok({"MediaContainer": {"Metadata": [PX_EP]}})),
        ("GET", "/library/parts/501/1/file.mp4"): server(ranged),
        ("GET", "/library/parts/502/1/file.mkv"): server(lambda q: ranged(q, ctype="application/octet-stream")),
        ("GET", T + "/start.m3u8"): server(start),
        ("GET", T + "/session/fake/base/index.m3u8"): server(lambda q: TM.ok(index.encode(), "application/vnd.apple.mpegurl")),
        ("GET", T + "/session/fake/base/00000.ts"): server(lambda q: TM.ok(SEG, "video/mp2t")),
        ("GET", T + "/session/fake/base/00001.ts"): server(lambda q: TM.ok(SEG, "video/mp2t")),
        ("GET", T + "/stop"): server(stop),
        ("PUT", "/library/parts/501"): server(lambda q: TM.ok(b"", "text/plain")),
        ("GET", "/library/streams/601"): server(lambda q: TM.ok(SRT.encode(), "application/x-subrip")),
        ("GET", "/library/sections/1/all"): server(lambda q: TM.ok({"MediaContainer": {}})),
    }


def jf_play_routes(state):
    toks = (TM.JF_QC_TOKEN, TM.JF_PW_TOKEN)

    def authed(fn):
        def w(req):
            h = req["headers"].get("authorization", "")
            if not any(f'Token="{t}"' in h for t in toks):
                return (401, "text/plain", b"")
            return fn(req)
        return w

    def info(req):
        b = req["body"] or {}
        state.setdefault("infos", []).append(b)
        src = {"Id": "src1", "Container": "mkv,webm", "SupportsDirectPlay": True, "RunTimeTicks": 6000 * 10_000_000,
               "DefaultAudioStreamIndex": 1,
               "MediaStreams": [{"Index": 0, "Type": "Video", "Codec": "h264"},
                                {"Index": 1, "Type": "Audio", "Codec": "aac", "Language": "eng", "DisplayTitle": "English", "IsDefault": True},
                                {"Index": 2, "Type": "Audio", "Codec": "dts", "Language": "ger", "DisplayTitle": "Deutsch"},
                                {"Index": 3, "Type": "Subtitle", "Codec": "subrip", "Language": "eng", "DisplayTitle": "English",
                                 "IsTextSubtitleStream": True},
                                {"Index": 4, "Type": "Subtitle", "Codec": "PGSSUB", "IsTextSubtitleStream": False}]}
        if not b.get("EnableDirectPlay", True):
            src["TranscodingUrl"] = f"/videos/m1/master.m3u8?MediaSourceId=src1&PlaySessionId=ps1&api_key={TM.JF_QC_TOKEN}"
        return TM.ok({"MediaSources": [src], "PlaySessionId": "ps1"})
    master = f"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nmain.m3u8?PlaySessionId=ps1&api_key={TM.JF_QC_TOKEN}\n"
    main = "#EXTM3U\n#EXT-X-TARGETDURATION:3\n#EXTINF:3,\nhls1/main/0.ts?runtimeTicks=0\n#EXT-X-ENDLIST\n"
    return {
        ("POST", "/Items/m1/PlaybackInfo"): authed(info),
        ("GET", "/videos/m1/master.m3u8"): authed(lambda q: TM.ok(master.encode(), "application/x-mpegURL")),
        ("GET", "/videos/m1/main.m3u8"): authed(lambda q: TM.ok(main.encode(), "application/x-mpegURL")),
        ("GET", "/videos/m1/hls1/main/0.ts"): authed(lambda q: TM.ok(SEG, "video/mp2t")),
        ("GET", "/Videos/m1/stream"): authed(lambda q: ranged(q, ctype="video/x-matroska")),
        ("GET", "/Videos/m1/src1/Subtitles/3/0/Stream.vtt"): authed(lambda q: TM.ok(VTT.encode(), "text/vtt")),
        ("POST", "/Sessions/Playing"): authed(lambda q: (204, "text/plain", b"")),
        ("DELETE", "/Videos/ActiveEncodings"): authed(lambda q: (204, "text/plain", b"")),
    }


class Relay(http.server.BaseHTTPRequestHandler):
    """app.py's part: GET /v1/media/stream/... goes to Plays.serve."""
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        u = urllib.parse.urlsplit(self.path)
        if u.path.startswith(MP.PREFIX):
            return self.server.plays.serve(self, u.path, u.query)
        self.send_error(404)

    def log_message(self, *a):
        pass


class Play(unittest.TestCase):
    setUp_media = TM.Media.setUp
    make_media = TM.Media.make_media
    call = TM.Media.call
    ok = TM.Media.ok
    seen = TM.Media.seen
    link_plex = TM.Media.link_plex
    link_jellyfin_qc = TM.Media.link_jellyfin_qc

    def setUp(self):
        self.setUp_media()
        self.tx = {}
        self.px.routes.update(plex_play_routes(self.px.url, self.tx))
        self.jf.routes.update(jf_play_routes(self.jstate))
        self.alive = {"ok": True}
        self.media.plays.alive = lambda h: self.alive["ok"] and bool(h)
        self.stopped = []
        self.media.plays.on_stopped = self.stopped.append
        self.relay = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Relay)
        self.relay.plays = self.media.plays
        threading.Thread(target=self.relay.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True).start()
        self.addCleanup(self.relay.server_close)
        self.addCleanup(self.relay.shutdown)
        self.rbase = f"http://127.0.0.1:{self.relay.server_port}"

    def get(self, path, headers=None):
        r = urllib.request.Request(self.rbase + path, headers=headers or {})
        try:
            with urllib.request.urlopen(r, timeout=20) as f:
                return f.status, dict(f.headers), f.read()
        except urllib.error.HTTPError as e:
            return e.code, dict(e.headers), e.read()

    def play(self, sess, conn, iid, client, **kw):
        return self.ok("POST", "/v1/media/play", sess, {"conn": conn, "id": iid, "client": client, **kw})

    def no_token(self, text):
        for t in TM.TOKENS + ("leaked",):
            self.assertNotIn(t, text)

    def wait_for(self, fn, secs=3):
        end = time.time() + secs
        while time.time() < end:
            if fn():
                return True
            time.sleep(0.02)
        return False

    # ---- Plex ----
    def test_plex_web_plays_hls_through_the_relay(self):
        cid = self.link_plex()["id"]
        a = self.play(self.alex, cid, "10", "web")
        sid = a["session"]
        self.assertEqual((a["kind"], a["url"], a["mime"], a["container"]),
                         ("hls", f"/v1/media/stream/{sid}/master.m3u8", "application/vnd.apple.mpegurl", "ts"))
        self.assertEqual((a["durationSecs"], a["resumeSecs"], a["title"], a["subtitle"], a["next"]),
                         (600, 120, "Example Plex Film 0", None, None))
        self.assertEqual([(t["id"], t["lang"], t["default"]) for t in a["audio"]], [("701", "eng", True), ("702", "fra", False)])
        self.assertEqual(a["subtitles"], [{"id": "601", "label": "English (SRT External)", "lang": "eng",
                                           "url": f"/v1/media/stream/{sid}/sub/601.vtt"}], "only subtitles a device can fetch")
        self.no_token(json.dumps(a))
        code, hdr, body = self.get(a["url"])
        self.assertEqual((code, hdr["Content-Type"]), (200, "application/vnd.apple.mpegurl"))
        start = self.seen(self.px, "GET", "/video/:/transcode/universal/start.m3u8")[-1]
        q = start["query"]
        self.assertEqual((q["path"], q["protocol"], q["directStream"], q["directPlay"]), ("/library/metadata/10", "hls", "1", "0"))
        self.assertIn("videoCodec=h264", q["X-Plex-Client-Profile-Extra"])
        self.assertEqual(start["headers"]["x-plex-token"], TM.PLEX_SERVER_TOKEN)
        self.no_token(json.dumps(q))
        text = body.decode()
        self.assertIn(f"/v1/media/stream/{sid}/u/video/:/transcode/universal/session/fake/base/index.m3u8", text)
        code, _, body = self.get(f"/v1/media/stream/{sid}/u/video/:/transcode/universal/session/fake/base/index.m3u8")
        text = body.decode()
        self.assertEqual(code, 200)
        self.no_token(text)
        lines = [ln for ln in text.splitlines() if ln and not ln.startswith("#")]
        self.assertEqual(lines, [f"/v1/media/stream/{sid}/u/video/:/transcode/universal/session/fake/base/00000.ts",
                                 f"/v1/media/stream/{sid}/u/video/:/transcode/universal/session/fake/base/00001.ts",
                                 f"/v1/media/stream/{sid}/x"])
        for ln in lines[:2]:
            code, hdr, body = self.get(ln)
            self.assertEqual((code, hdr["Content-Type"], body), (200, "video/mp2t", SEG))
        self.assertEqual(self.get(lines[2])[0], 404, "a URI on another host is never relayed")
        self.assertEqual(self.seen(self.px, "GET", "/video/:/transcode/universal/session/fake/base/00001.ts")[-1]["query"], {},
                         "a token in a playlist URI is dropped")
        code, hdr, body = self.get(f"/v1/media/stream/{sid}/sub/601.vtt")
        self.assertEqual((code, hdr["Content-Type"]), (200, "text/vtt; charset=utf-8"))
        self.assertTrue(body.decode().startswith("WEBVTT\n\n1\n00:00:01.000 --> 00:00:02.500\nHello there"), body)
        self.assertEqual(self.get(f"/v1/media/stream/{sid}/sub/602.vtt")[0], 404)
        self.assertEqual(self.get(f"/v1/media/stream/{sid}/file")[0], 404, "an HLS session has no file")

    def test_the_relay_only_fetches_the_transcoder(self):
        cid = self.link_plex()["id"]
        sid = self.play(self.alex, cid, "10", "web")["session"]
        for bad in ("/u/library/sections/1/all", "/u/library/parts/501/1/file.mp4",
                    "/u/video/:/transcode/universal/../../library/sections", "/u/video/:/transcode/universal//x",
                    "/u/video/:/transcode/universal/session/fake/base/00000.ts%00", "/master.m3u8x", "/nothing",
                    "/u/video/:/transcode/universal/%2e%2e/%2e%2e/library/sections",
                    "/u/video/:/transcode/universal/%252e%252e/%252e%252e/library/sections",
                    "/u/video/:/transcode/universal/..%2f..%2flibrary", "/u/video/:/transcode/universal/a%5c..%5cx"):
            self.assertEqual(self.get(f"/v1/media/stream/{sid}{bad}")[0], 404, bad)
        self.assertFalse([r for r in self.px.seen if "/library/sections" in r["path"] or ".." in r["path"] or "%2e" in r["path"].lower()],
                         "nothing outside the transcoder (also percent-encoded) reached the server")
        self.assertEqual(self.get("/v1/media/stream/" + "A" * 32 + "/master.m3u8")[0], 404)
        self.assertEqual(self.get("/v1/media/stream/short/master.m3u8")[0], 404)
        self.get(f"/v1/media/stream/{sid}/u/video/:/transcode/universal/session/fake/base/00000.ts?X-Plex-Token=x&api_key=y&ok=1")
        self.assertEqual(self.seen(self.px, "GET", "/video/:/transcode/universal/session/fake/base/00000.ts")[-1]["query"], {"ok": "1"})

    def test_plex_tv_direct_plays_the_file_with_range(self):
        cid = self.link_plex()["id"]
        a = self.play(self.alex, cid, "10", "tv")
        sid = a["session"]
        self.assertEqual((a["kind"], a["url"], a["container"], a["mime"]), ("file", f"/v1/media/stream/{sid}/file", "mp4", "video/mp4"))
        self.assertFalse(self.seen(self.px, "GET", "/video/:/transcode/universal/start.m3u8"), "no transcode for direct play")
        code, hdr, body = self.get(a["url"])
        self.assertEqual((code, len(body), body == FILE, hdr["Accept-Ranges"]), (200, len(FILE), True, "bytes"))
        code, hdr, body = self.get(a["url"], {"Range": "bytes=1000-1999"})
        self.assertEqual((code, hdr["Content-Range"], body), (206, f"bytes 1000-1999/{len(FILE)}", FILE[1000:2000]))
        self.assertEqual(self.seen(self.px, "GET", "/library/parts/501/1/file.mp4")[-1]["headers"]["range"], "bytes=1000-1999")
        code, hdr, _ = self.get(a["url"], {"Range": f"bytes={len(FILE) + 5}-"})
        self.assertEqual((code, hdr.get("Content-Range")), (416, f"bytes */{len(FILE)}"))
        self.assertEqual(self.get(f"/v1/media/stream/{sid}/master.m3u8")[0], 404, "a file session has no playlist")

    def test_plex_episode_caps_next_and_audio_choice(self):
        cid = self.link_plex()["id"]
        a = self.play(self.alex, cid, "22", "desktop")
        self.assertEqual((a["kind"], a["container"], a["mime"], a["title"], a["subtitle"]),
                         ("file", "mkv", "video/x-matroska", "Example Plex Show", "S1 E1 · Pilot"))
        self.assertEqual(a["next"], {"id": "23", "title": "Plex Episode 2", "subtitle": "S1 E2 · Plex Episode 2"})
        code, hdr, _ = self.get(a["url"], {"Range": "bytes=0-9"})
        self.assertEqual((code, hdr["Content-Type"]), (206, "video/x-matroska"), "the type follows the file when the server is vague")
        b = self.play(self.alex, cid, "22", "tv", caps={"containers": ["mp4", "mkv"], "videoCodecs": ["h264"]})
        self.assertEqual(b["kind"], "hls", "HEVC is not in the device's list: transcode")
        c = self.play(self.alex, cid, "22", "tv", caps={"directPlay": False})
        self.assertEqual(c["kind"], "hls")
        d = self.play(self.alex, cid, "10", "web", audio="702")
        put = self.seen(self.px, "PUT", "/library/parts/501")[-1]
        self.assertEqual(put["query"], {"audioStreamID": "702", "allParts": "1"})
        self.assertEqual([t["id"] for t in d["audio"] if t["default"]], ["702"])
        self.assertEqual(self.call("POST", "/v1/media/play", self.alex, {"conn": cid, "id": "10", "client": "phone"})[0], 400)
        self.assertEqual(self.call("POST", "/v1/media/play", self.alex, {"conn": cid, "id": "20", "client": "web"})[0], 400,
                         "a show is not playable")
        self.assertEqual(self.call("POST", "/v1/media/play", self.alex, {"conn": cid, "id": "../x", "client": "web"})[0], 400)
        self.assertEqual(self.call("POST", "/v1/media/play", self.alex, {"conn": cid, "id": "10", "client": "web", "audio": "a/b"})[0], 400)

    def test_plex_progress_scrobble_and_stop(self):
        cid = self.link_plex()["id"]
        a = self.play(self.alex, cid, "10", "web")
        sid = a["session"]
        self.get(a["url"])
        self.ok("POST", "/v1/media/progress", self.alex, {"session": sid, "positionSecs": 61.5, "state": "playing"})
        tl = self.seen(self.px, "GET", "/:/timeline")[-1]
        self.assertEqual((tl["query"]["ratingKey"], tl["query"]["state"], tl["query"]["time"], tl["query"]["duration"]),
                         ("10", "playing", "61500", "600000"))
        self.assertTrue(tl["headers"]["x-plex-session-identifier"])
        self.ok("POST", "/v1/media/progress", self.alex, {"session": sid, "positionSecs": 100, "state": "paused"})
        self.assertFalse(self.seen(self.px, "GET", "/:/scrobble"))
        self.ok("POST", "/v1/media/progress", self.alex, {"session": sid, "positionSecs": 570, "state": "playing"})
        self.ok("POST", "/v1/media/progress", self.alex, {"session": sid, "positionSecs": 580, "state": "playing"})
        self.assertEqual(len(self.seen(self.px, "GET", "/:/scrobble")), 1, "scrobbled once, near the end")
        self.assertEqual(self.call("POST", "/v1/media/progress", self.sam, {"session": sid, "positionSecs": 1, "state": "playing"})[0],
                         404, "another account's session")
        for bad in ({"positionSecs": -1, "state": "playing"}, {"positionSecs": "x", "state": "playing"},
                    {"positionSecs": 1, "state": "rewinding"}):
            self.assertEqual(self.call("POST", "/v1/media/progress", self.alex, {"session": sid, **bad})[0], 400, bad)
        self.ok("POST", "/v1/media/progress", self.alex, {"session": sid, "positionSecs": 590, "state": "stopped"})
        self.assertEqual(self.seen(self.px, "GET", "/:/timeline")[-1]["query"]["state"], "stopped")
        self.assertTrue(self.wait_for(lambda: self.seen(self.px, "GET", "/video/:/transcode/universal/stop")),
                        "the transcode is stopped")
        self.assertEqual(self.seen(self.px, "GET", "/video/:/transcode/universal/stop")[-1]["query"]["session"],
                         self.seen(self.px, "GET", "/video/:/transcode/universal/start.m3u8")[-1]["query"]["session"])
        self.assertEqual(self.stopped, [self.alex["account_id"]], "Continue watching is told")
        self.assertEqual(self.get(f"/v1/media/stream/{sid}/master.m3u8")[0], 404, "the session has ended")
        self.assertEqual(self.call("POST", "/v1/media/progress", self.alex, {"session": sid, "positionSecs": 1, "state": "playing"})[0], 404)
        # the older direct-URL progress call still works
        self.ok("POST", "/v1/media/progress", self.alex, {"conn": cid, "id": "10", "secs": 5, "dur": 600, "state": "playing"})

    def test_sessions_end_with_the_sign_in_unlink_and_idle(self):
        cid = self.link_plex()["id"]
        sid = self.play(self.alex, cid, "10", "tv")["session"]
        self.assertEqual(self.get(f"/v1/media/stream/{sid}/file", {"Range": "bytes=0-0"})[0], 206)
        self.alive["ok"] = False                 # signed out elsewhere / session expired
        self.assertEqual(self.get(f"/v1/media/stream/{sid}/file")[0], 404)
        self.assertEqual(self.media.plays.count(), 0)
        self.alive["ok"] = True
        sid = self.play(self.alex, cid, "10", "web")["session"]
        self.ok("POST", "/v1/media/connections/delete", self.alex, {"id": cid})
        self.assertEqual(self.get(f"/v1/media/stream/{sid}/master.m3u8")[0], 404, "unlinked")
        self.pstate["approved"] = False
        cid = self.link_plex()["id"]
        sid = self.play(self.alex, cid, "10", "web")["session"]
        self.assertEqual(self.call("POST", "/v1/auth/logout", self.alex)[0], 200)
        self.assertEqual(self.media.plays.count(), 0, "signing out ends its playbacks")
        self.assertTrue(self.wait_for(lambda: len(self.seen(self.px, "GET", "/video/:/transcode/universal/stop")) >= 2))

    def test_idle_sessions_expire_and_an_account_has_a_few(self):
        cid = self.link_plex()["id"]
        now = [1000.0]
        self.media.plays.clock = lambda: now[0]
        sid = self.play(self.alex, cid, "10", "tv")["session"]
        now[0] += MP.IDLE_STOP + 1
        self.assertEqual(self.get(f"/v1/media/stream/{sid}/file")[0], 404)
        plays = self.media.plays
        conn = plays.get(self.play(self.alex, cid, "10", "tv")["session"]).connector
        sids = []
        for n in range(MP.MAX_PER_ACCOUNT + 2):           # different titles (the same one again is a handoff)
            now[0] += 1
            p = MP.Play(f"{n:032d}", self.alex["account_id"], "h", cid, "plex", f"i{n}", "file", "/x", (), {}, conn,
                        seen=now[0])
            plays.add(p)
            sids.append(p.id)
        self.assertEqual(plays.count(), MP.MAX_PER_ACCOUNT)
        self.assertIsNone(plays.get(sids[0]))
        self.assertIsNotNone(plays.get(sids[-1]))

    def test_stopped_before_playing_keeps_the_position(self):
        cid = self.link_plex()["id"]
        sid = self.play(self.alex, cid, "10", "tv")["session"]
        n = len(self.seen(self.px, "GET", "/:/timeline"))
        self.ok("POST", "/v1/media/progress", self.alex, {"session": sid, "positionSecs": 0, "state": "stopped"})
        self.assertEqual(len(self.seen(self.px, "GET", "/:/timeline")), n, "no timeline at 0 s over the saved position")
        self.assertIsNone(self.media.plays.get(sid))

    # ---- abandoned sessions, handoff, refusals ----
    def other_browser(self):
        """Alex signed in on a second device."""
        return self.acc.gate(self.auth.session(self.acc.login("alex", "correct horse 1", "192.0.2.11")))

    def stops(self):
        return [r["query"]["session"] for r in self.seen(self.px, "GET", "/video/:/transcode/universal/stop")]

    def test_continue_on_another_device_stops_the_old_transcode_first(self):
        cid = self.link_plex()["id"]
        a = self.play(self.alex, cid, "10", "web")
        self.assertEqual(self.get(a["url"])[0], 200)
        old = self.seen(self.px, "GET", "/video/:/transcode/universal/start.m3u8")[-1]["query"]["session"]
        self.ok("POST", "/v1/media/progress", self.alex, {"session": a["session"], "positionSecs": 1610, "state": "playing"})
        # the Mac is closed without stopping; the desktop continues the same film
        mac_stop = len(self.seen(self.px, "GET", "/:/timeline"))
        b = self.play(self.other_browser(), cid, "10", "web")
        self.assertEqual(self.stops(), [old], "the old transcode was stopped before the new one started")
        tl = self.seen(self.px, "GET", "/:/timeline")[mac_stop:]
        self.assertEqual([(t["query"]["state"], t["query"]["time"]) for t in tl], [("stopped", "1610000")],
                         "the old playback is reported stopped at its last position")
        self.assertEqual(tl[0]["headers"]["x-plex-session-identifier"], old)
        code, _, body = self.get(b["url"])
        self.assertEqual(code, 200, body)
        self.assertFalse(self.tx.get("refused"), "Plex was never asked for a second transcode")
        self.assertEqual(self.get(a["url"])[0], 404, "the old session has ended")
        self.assertEqual(self.call("POST", "/v1/media/progress", self.alex,
                                   {"session": a["session"], "positionSecs": 1620, "state": "stopped"})[0], 404)
        self.assertEqual(self.stopped, [self.alex["account_id"]])

    def test_the_fake_plex_runs_one_transcode_and_leftovers_are_cleaned_up(self):
        cid = self.link_plex()["id"]
        a = self.play(self.alex, cid, "10", "web")
        self.assertEqual(self.get(a["url"])[0], 200)
        left = self.seen(self.px, "GET", "/video/:/transcode/universal/start.m3u8")[-1]["query"]["session"]
        self.media.plays._d.clear()              # Orbit restarted: the transcode is still running on Plex
        b = self.play(self.alex, cid, "10", "web")
        code, hdr, body = self.get(b["url"])
        self.assertEqual((code, hdr["Content-Type"]), (200, "application/vnd.apple.mpegurl"), body)
        self.assertEqual(len(self.tx["refused"]), 1, "refused once, while the leftover ran")
        self.assertEqual(self.stops(), [left], "only the leftover of this connection was stopped")
        log = "\n".join(self.log.lines)
        self.assertIn("Plex playlist answered 400: <html><body>Bad Request X-Plex-Token=REDACTED", log)
        self.assertIn("stopped 1 leftover transcode", log)
        self.no_token(log)

    def test_a_refused_start_shows_plain_english_and_logs_what_plex_said(self):
        cid = self.link_plex()["id"]
        self.tx["active"]["someone-else"] = "another-plex-app"        # not this connection: never stopped by Orbit
        a = self.play(self.alex, cid, "10", "web")
        code, _, body = self.get(a["url"])
        self.assertEqual(code, 502)
        err = json.loads(body)["error"]
        self.assertIn("Plex refused to start transcoding this title (it answered 400)", err)
        self.no_token(err)
        self.assertEqual(self.stops(), [], "another app's transcode is left alone")
        log = "\n".join(self.log.lines)
        self.assertIn("item 10 (web, hls): Plex playlist answered 400", log)
        self.assertNotIn(a["session"], log, "the play session id (a capability) is never logged")
        self.no_token(log)
        # a decision that refuses: POST /v1/media/play says why
        self.tx["decision"] = {"MediaContainer": {"generalDecisionCode": 2000, "generalDecisionText": "Cannot play.",
                                                  "transcodeDecisionCode": 4005,
                                                  "transcodeDecisionText": "Maximum simultaneous transcodes reached"}}
        code, rep = self.call("POST", "/v1/media/play", self.alex, {"conn": cid, "id": "10", "client": "web"})
        self.assertEqual((code, rep.obj["error"]), (409, PX.TRANSCODE_LIMIT))
        self.tx["decision"] = {"MediaContainer": {"generalDecisionCode": 2003, "generalDecisionText": "File is unplayable."}}
        code, rep = self.call("POST", "/v1/media/play", self.alex, {"conn": cid, "id": "10", "client": "web"})
        self.assertEqual((code, rep.obj["error"]), (409, "Plex will not play this title: File is unplayable."))
        self.px.routes[("GET", "/video/:/transcode/universal/decision")] = lambda q: (404, "text/plain", b"")
        self.tx["active"].clear()
        b = self.play(self.alex, cid, "10", "web")
        self.assertEqual(self.get(b["url"])[0], 200, "an old server without the decision call still plays")

    def test_decision_then_start_with_the_resume_offset(self):
        cid = self.link_plex()["id"]
        a = self.play(self.alex, cid, "10", "web")
        self.get(a["url"])
        start = self.seen(self.px, "GET", "/video/:/transcode/universal/start.m3u8")[-1]["query"]
        dec = self.seen(self.px, "GET", "/video/:/transcode/universal/decision")[-1]["query"]
        self.assertEqual(dec, start, "the decision is asked with the same parameters")
        self.assertEqual(start["offset"], "120", "the transcode starts at the resume point")
        self.assertEqual(a["resumeSecs"], 120)
        b = self.play(self.alex, cid, "10", "web", startSecs=0)
        self.get(b["url"])
        self.assertEqual(self.seen(self.px, "GET", "/video/:/transcode/universal/start.m3u8")[-1]["query"]["offset"], "0",
                         "Play from start")
        self.play(self.alex, cid, "10", "web", startSecs=331.7)
        self.assertEqual(self.seen(self.px, "GET", "/video/:/transcode/universal/decision")[-1]["query"]["offset"], "331")
        for bad in (-1, "x", True):
            self.assertEqual(self.call("POST", "/v1/media/play", self.alex,
                                       {"conn": cid, "id": "10", "client": "web", "startSecs": bad})[0], 400, bad)

    def test_abandoned_sessions_are_stopped_at_their_last_position(self):
        cid = self.link_plex()["id"]
        now = [5000.0]
        plays = self.media.plays
        plays.clock = lambda: now[0]
        a = self.play(self.alex, cid, "10", "web")
        self.get(a["url"])
        tx = self.seen(self.px, "GET", "/video/:/transcode/universal/start.m3u8")[-1]["query"]["session"]
        self.ok("POST", "/v1/media/progress", self.alex, {"session": a["session"], "positionSecs": 2000, "state": "playing"})
        for _ in range(12):                                 # paused, still reporting every 10 s: kept
            now[0] += 10
            self.ok("POST", "/v1/media/progress", self.alex, {"session": a["session"], "positionSecs": 2004, "state": "paused"})
            plays.sweep()
        self.assertIsNotNone(plays._d.get(a["session"]))
        n = len(self.seen(self.px, "GET", "/:/timeline"))
        now[0] += MP.IDLE_STOP - 1
        self.assertEqual(plays.sweep(), 0)
        now[0] += 2                                         # the laptop went to sleep
        self.assertEqual(plays.sweep(), 1)
        self.assertTrue(self.wait_for(lambda: self.stops() == [tx]), "the transcode is stopped")
        tl = self.seen(self.px, "GET", "/:/timeline")[n:]
        self.assertEqual([(t["query"]["state"], t["query"]["time"]) for t in tl], [("stopped", "2004000")])
        self.assertTrue(self.wait_for(lambda: self.stopped == [self.alex["account_id"]]))
        self.assertEqual(self.get(a["url"])[0], 404)
        self.assertIn("abandoned", "\n".join(self.log.lines))

        # never reported (the player never started): the transcode stops, the saved position is kept
        b = self.play(self.alex, cid, "10", "web")
        self.get(b["url"])
        n = len(self.seen(self.px, "GET", "/:/timeline"))
        now[0] += MP.IDLE_STOP + 1
        plays.sweep()
        self.assertTrue(self.wait_for(lambda: len(self.stops()) == 2))
        self.assertEqual(len(self.seen(self.px, "GET", "/:/timeline")), n)

        # a TV that reports "paused" once (older builds) keeps its session up to the outer cap
        c = self.play(self.alex, cid, "22", "tv", caps={"directPlay": False})
        self.ok("POST", "/v1/media/progress", self.alex, {"session": c["session"], "positionSecs": 50, "state": "paused"})
        now[0] += 3600
        self.assertEqual(plays.sweep(), 0)
        now[0] += MP.IDLE
        self.assertEqual(plays.sweep(), 1)
        # a TV playing the file over one long request counts as active while it runs
        d = self.play(self.alex, cid, "10", "tv")
        p = plays._d[d["session"]]
        p.pipes = 1
        now[0] += MP.IDLE_STOP * 3
        self.assertEqual(plays.sweep(), 0)
        p.pipes = 0
        self.assertEqual(plays.sweep(), 1)

    def test_the_sweeper_runs_by_itself(self):
        cid = self.link_plex()["id"]
        plays = self.media.plays
        plays.sweep_every = 0.05
        now = [100.0]
        plays.clock = lambda: now[0]
        a = self.play(self.alex, cid, "10", "web")
        self.get(a["url"])
        now[0] += MP.IDLE_STOP + 1
        self.assertTrue(self.wait_for(lambda: plays.count() == 0 and len(self.stops()) == 1))

    def test_another_account_cannot_play_my_connection(self):
        cid = self.link_plex()["id"]
        self.assertEqual(self.call("POST", "/v1/media/play", self.sam, {"conn": cid, "id": "10", "client": "web"})[0], 404)

    def test_server_down_and_expired_sign_in(self):
        cid = self.link_plex()["id"]
        self.px.routes[("GET", "/library/metadata/10")] = lambda q: (401, "text/plain", b"")
        code, rep = self.call("POST", "/v1/media/play", self.alex, {"conn": cid, "id": "10", "client": "web"})
        self.assertEqual(code, 409)
        self.assertIn("link it again", rep.obj["error"])
        self.px.routes[("GET", "/library/metadata/10")] = lambda q: (500, "text/plain", b"")
        self.assertEqual(self.call("POST", "/v1/media/play", self.alex, {"conn": cid, "id": "10", "client": "web"})[0], 502)

    # ---- Jellyfin ----
    def play_from(self, addr, conn, iid, client, **kw):
        """POST /v1/media/play from a client at ``addr`` (the other tests come from a home address)."""
        rep = self.api.handle(TM.V1.Call("POST", "/v1/media/play", self.alex,
                                         {"conn": conn, "id": iid, "client": client, **kw}, addr, {}))
        return rep.code, rep.obj

    def test_away_from_home_plex_gets_a_capped_transcode(self):
        cid = self.link_plex()["id"]
        with mock.patch.dict(os.environ, {"ORBIT_AWAY_NETS": "", "ORBIT_AWAY_QUALITY": ""}):
            code, a = self.play_from("100.101.102.103", cid, "10", "tv")       # Tailscale
            self.assertEqual(code, 200, a)
            self.assertEqual((a["kind"], a["quality"]), ("hls", {"level": "4", "away": True, "label": "720p · 4 Mbps"}),
                             "a TV that could play the file gets a transcode under the cap away from home")
            q = self.seen(self.px, "GET", "/video/:/transcode/universal/decision")[-1]["query"]
            self.assertEqual((q["maxVideoBitrate"], q["videoResolution"]), ("4000", "1280x720"))
            code, a = self.play_from("100.101.102.103", cid, "10", "tv", quality="orig")
            self.assertEqual((a["kind"], a["quality"]["level"]), ("file", "orig"), "Original away: the file")
            code, a = self.play_from("192.168.1.20", cid, "10", "web", quality="2")
            q = self.seen(self.px, "GET", "/video/:/transcode/universal/decision")[-1]["query"]
            self.assertEqual((a["quality"], q["maxVideoBitrate"], q["videoResolution"]),
                             ({"level": "2", "away": False, "label": "480p · 2 Mbps"}, "2000", "854x480"))
            a = self.play(self.alex, cid, "10", "web")                       # home, auto: as before
            q = self.seen(self.px, "GET", "/video/:/transcode/universal/decision")[-1]["query"]
            self.assertEqual((a["quality"]["level"], q["maxVideoBitrate"]), ("orig", "20000"))
            self.assertEqual(self.play_from("192.168.1.20", cid, "10", "web", quality="9000")[0], 400)

    def test_away_from_home_jellyfin_gets_a_capped_transcode(self):
        cid = self.link_jellyfin_qc()["id"]
        with mock.patch.dict(os.environ, {"ORBIT_AWAY_NETS": "192.0.2.1", "ORBIT_AWAY_QUALITY": ""}):
            code, a = self.play_from("192.0.2.1", cid, "m1", "tv")             # the VPN router, listed
            self.assertEqual((code, a["kind"], a["quality"]["away"]), (200, "hls", True), a)
            info = self.jstate["infos"][-1]
            self.assertEqual((info["EnableDirectPlay"], info["MaxStreamingBitrate"], info["DeviceProfile"]["MaxStreamingBitrate"]),
                             (False, 4_000_000, 4_000_000))
            self.assertEqual(info["DeviceProfile"]["CodecProfiles"][0]["Conditions"][0]["Value"], "720")

    def test_jellyfin_web_hls_and_tv_file(self):
        cid = self.link_jellyfin_qc()["id"]
        a = self.play(self.alex, cid, "m1", "web")
        sid = a["session"]
        self.assertEqual((a["kind"], a["durationSecs"], a["resumeSecs"], a["title"]), ("hls", 6000, 600, "Example Film 1"))
        self.assertEqual([(t["id"], t["default"]) for t in a["audio"]], [("1", True), ("2", False)])
        self.assertEqual([s["id"] for s in a["subtitles"]], ["3"], "text subtitles only")
        info = self.jstate["infos"][-1]
        self.assertEqual((info["EnableDirectPlay"], info["DeviceProfile"]["TranscodingProfiles"][0]["VideoCodec"]), (False, "h264"))
        code, _, body = self.get(a["url"])
        text = body.decode()
        self.assertEqual(code, 200)
        self.no_token(text)
        self.assertIn(f"/v1/media/stream/{sid}/u/videos/m1/main.m3u8?PlaySessionId=ps1", text)
        m = self.seen(self.jf, "GET", "/videos/m1/master.m3u8")[-1]
        self.assertNotIn("api_key", m["query"], "the token goes in the header, not the URL")
        self.assertIn(f'Token="{TM.JF_QC_TOKEN}"', m["headers"]["authorization"])
        code, _, body = self.get(f"/v1/media/stream/{sid}/u/videos/m1/main.m3u8?PlaySessionId=ps1")
        seg = [ln for ln in body.decode().splitlines() if ln and not ln.startswith("#")][0]
        self.assertEqual(seg, f"/v1/media/stream/{sid}/u/videos/m1/hls1/main/0.ts?runtimeTicks=0")
        self.assertEqual(self.get(seg)[2], SEG)
        code, _, body = self.get(f"/v1/media/stream/{sid}/sub/3.vtt")
        self.assertEqual((code, body.decode()), (200, VTT))
        self.ok("POST", "/v1/media/progress", self.alex, {"session": sid, "positionSecs": 10, "state": "playing"})
        self.ok("POST", "/v1/media/progress", self.alex, {"session": sid, "positionSecs": 20, "state": "paused"})
        self.ok("POST", "/v1/media/progress", self.alex, {"session": sid, "positionSecs": 30, "state": "stopped"})
        first = self.seen(self.jf, "POST", "/Sessions/Playing")[-1]["body"]
        self.assertEqual((first["PlaySessionId"], first["MediaSourceId"], first["PlayMethod"], first["PositionTicks"]),
                         ("ps1", "src1", "Transcode", 100_000_000))
        self.assertTrue(self.seen(self.jf, "POST", "/Sessions/Playing/Progress")[-1]["body"]["IsPaused"])
        self.assertEqual(self.seen(self.jf, "POST", "/Sessions/Playing/Stopped")[-1]["body"]["PositionTicks"], 300_000_000)
        self.assertTrue(self.wait_for(lambda: self.seen(self.jf, "DELETE", "/Videos/ActiveEncodings")))
        self.assertEqual(self.seen(self.jf, "DELETE", "/Videos/ActiveEncodings")[-1]["query"]["playSessionId"], "ps1")

        b = self.play(self.alex, cid, "m1", "tv")
        self.assertEqual((b["kind"], b["container"], b["mime"]), ("file", "mkv", "video/x-matroska"))
        code, hdr, body = self.get(b["url"], {"Range": "bytes=5-9"})
        self.assertEqual((code, body), (206, FILE[5:10]))
        st = self.seen(self.jf, "GET", "/Videos/m1/stream")[-1]
        self.assertEqual(st["query"], {"static": "true", "mediaSourceId": "src1"})
        c = self.play(self.alex, cid, "m1", "tv", caps={"audioCodecs": ["aac", "ac3"]}, audio="2")
        self.assertEqual(c["kind"], "hls", "DTS chosen, and the device has no DTS")
        self.assertEqual(self.jstate["infos"][-1]["AudioStreamIndex"], 2)


class Helpers(unittest.TestCase):
    def test_srt_to_vtt(self):
        self.assertEqual(MP.srt_to_vtt("﻿" + SRT),
                         "WEBVTT\n\n1\n00:00:01.000 --> 00:00:02.500\nHello there\n\n2\n00:00:03.000 --> 00:00:04.000\nBye\n")

    def test_direct_ok(self):
        self.assertTrue(direct_ok(None, "mkv", "hevc", "dts"))
        self.assertTrue(direct_ok({"containers": ["mkv", "mp4"]}, "mkv", "hevc", "dts"))
        self.assertFalse(direct_ok({"containers": ["mp4"]}, "mkv"))
        self.assertTrue(direct_ok({"containers": ["webm"]}, "mkv,webm"))
        self.assertFalse(direct_ok({"direct": False}, "mp4"))
        self.assertFalse(direct_ok({"directPlay": False}, "mp4"))
        self.assertTrue(direct_ok({"directPlay": True, "containers": ["mp4", "mkv"]}, "mkv", "h264", "aac"))
        self.assertTrue(direct_ok({"videoCodecs": ["h264"]}, "mp4", None))

    def test_safe_rel_and_clean_query(self):
        P = ("/video/:/transcode/universal/",)
        self.assertTrue(MP.safe_rel("/video/:/transcode/universal/session/a/base/0.ts", P))
        for bad in ("/library/x", "/video/:/transcode/universal/../x", "/video/:/transcode/universal/a//b",
                    "/video/:/transcode/universal/a@b", "/video/:/transcode/universal/a b", ""):
            self.assertFalse(MP.safe_rel(bad, P), bad)
        J = ("/videos/",)
        for bad in ("/videos/%2e%2e/System/Info", "/videos/%252e%252e/System/Info", "/videos/..%2fSystem",
                    "/videos/a%2fb", "/videos/a%5cb", "/videos/%2E%2E/x", "/videos/a/%2e/b", "/videos/a%00"):
            self.assertIsNone(MP.safe_rel(bad, J), bad)
        self.assertEqual(MP.safe_rel("/videos/m1/hls1/main/0.ts", J), "/videos/m1/hls1/main/0.ts")
        self.assertEqual(MP.safe_rel("/videos/m1/a%20b%2Ets", J), "/videos/m1/a%20b.ts", "sent upstream re-encoded")
        self.assertEqual(MP.clean_query("a=1&X-Plex-Token=t&API_KEY=k&b=2"), "a=1&b=2")


class RealServer(TM.Server):
    """run.py: /v1/media/play and the relay through the real handler (Host check, routing, no cookie needed)."""

    @classmethod
    def setUpClass(cls):
        super().setUpClass()
        cls.jstate = {}
        cls.jf.routes.update(jf_play_routes(cls.jstate))

    def test_through_http(self):
        with open(os.path.join(self.data, "orbit-setup-token"), encoding="utf-8") as f:
            body = {"token": f.read(), "username": "alex", "password": "correct horse 1"}
        code, hdr, raw = self.req("POST", "/v1/setup", body, {"X-TvBro": "1"})
        self.assertEqual(code, 200, raw)
        h = {"Cookie": hdr["Set-Cookie"].split(";", 1)[0], "X-CSRF-Token": json.loads(raw)["csrf"]}
        code, _, raw = self.req("POST", "/v1/media/connect/password",
                                {"kind": "jellyfin", "baseUrl": self.jf.url, "username": "alex", "password": "right pass"}, h)
        cid = json.loads(raw)["connections"][0]["id"]
        code, _, raw = self.req("POST", "/v1/media/play", {"conn": cid, "id": "m1", "client": "tv"}, h)
        self.assertEqual(code, 200, raw)
        a = json.loads(raw)
        code, hdr, data = self.req("GET", a["url"], headers={"Range": "bytes=100-199"})
        self.assertEqual((code, data, hdr["Content-Range"]), (206, FILE[100:200], f"bytes 100-199/{len(FILE)}"),
                         "a player without the cookie can use the session address")
        code, _, raw = self.req("POST", "/v1/media/play", {"conn": cid, "id": "m1", "client": "web"}, h)
        w = json.loads(raw)
        code, hdr, data = self.req("GET", w["url"])
        self.assertEqual((code, hdr["Content-Type"]), (200, "application/vnd.apple.mpegurl"))
        self.assertNotIn(TM.JF_PW_TOKEN.encode(), data)
        self.assertEqual(self.req("POST", "/v1/media/progress", {"session": w["session"], "positionSecs": 3, "state": "playing"}, h)[0], 200)
        # the web player's sendBeacon "stopped" on leaving the page: text/plain, the cookie, no CSRF header
        code, _, raw = self.req("POST", "/v1/media/play", {"conn": cid, "id": "m1", "client": "web"}, h)
        sid = json.loads(raw)["session"]
        self.assertEqual(self.req("GET", w["url"])[0], 404, "playing the same title again ended the older session")
        cookie = {"Cookie": h["Cookie"]}
        beacon = {**cookie, "Content-Type": "text/plain;charset=UTF-8", "Origin": self.base}
        stop = {"session": sid, "positionSecs": 42.5, "state": "stopped"}
        for body, hdr, why in (
                (stop, {**beacon, "Origin": "http://evil.example.com"}, "another site"),
                (stop, {**cookie, "Content-Type": "text/plain"}, "nothing says it is this site's page"),
                ({**stop, "state": "playing"}, beacon, "only stopped is accepted without the CSRF token"),
                ({**stop, "extra": 1}, beacon, "only the stop fields"),
                ({**stop, "session": "x"}, beacon, "a play session id")):
            self.assertEqual(self.req("POST", "/v1/media/progress", body, hdr)[0], 403, why)
        self.assertEqual(self.req("POST", "/v1/media/progress", stop, {"Content-Type": "text/plain", "Origin": self.base})[0],
                         401, "the cookie is needed")
        code, _, raw = self.req("POST", "/v1/media/progress", stop, {**cookie, "Content-Type": "text/plain",
                                                                    "Sec-Fetch-Site": "same-origin"})
        self.assertEqual(code, 200, raw)
        self.assertEqual(self.req("GET", f"/v1/media/stream/{sid}/master.m3u8")[0], 404, "the beacon ended the session")
        self.assertEqual(self.req("POST", "/v1/media/progress", stop, beacon)[0], 404, "once")
        self.assertEqual(self.req("POST", "/v1/auth/logout", {}, h)[0], 200)
        self.assertEqual(self.req("GET", a["url"])[0], 404, "signed out: its playbacks end")
        self.assertEqual(self.req("GET", w["url"])[0], 404)
        self.log.flush()
        with open(os.path.join(self.tmp, "server.log"), "rb") as f:
            log = f.read()
        self.assertNotIn(TM.JF_PW_TOKEN.encode(), log)
        self.assertIn(b"/v1/media/stream/-/", log, "stream requests are logged without their session id")
        for s in (a["session"], w["session"], sid):
            self.assertNotIn(s.encode(), log, "a play-session id is a capability: never in the log")


if __name__ == "__main__":
    unittest.main()
