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
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "server"))
import test_orbit_media as TM  # noqa: E402
from orbit import mediaplay as MP  # noqa: E402
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


def plex_play_routes(base):
    S = TM.PLEX_SERVER_TOKEN

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
    return {
        ("GET", "/library/metadata/10"): server(lambda q: TM.ok({"MediaContainer": {"Metadata": [PX_FILM]}})),
        ("GET", "/library/metadata/22"): server(lambda q: TM.ok({"MediaContainer": {"Metadata": [PX_EP]}})),
        ("GET", "/library/parts/501/1/file.mp4"): server(ranged),
        ("GET", "/library/parts/502/1/file.mkv"): server(lambda q: ranged(q, ctype="application/octet-stream")),
        ("GET", T + "/start.m3u8"): server(lambda q: TM.ok(master.encode(), "application/vnd.apple.mpegurl")),
        ("GET", T + "/session/fake/base/index.m3u8"): server(lambda q: TM.ok(index.encode(), "application/vnd.apple.mpegurl")),
        ("GET", T + "/session/fake/base/00000.ts"): server(lambda q: TM.ok(SEG, "video/mp2t")),
        ("GET", T + "/session/fake/base/00001.ts"): server(lambda q: TM.ok(SEG, "video/mp2t")),
        ("GET", T + "/stop"): server(lambda q: TM.ok(b"", "text/plain")),
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
        self.px.routes.update(plex_play_routes(self.px.url))
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
                    "/u/video/:/transcode/universal/session/fake/base/00000.ts%00", "/master.m3u8x", "/nothing"):
            self.assertEqual(self.get(f"/v1/media/stream/{sid}{bad}")[0], 404, bad)
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
        c = self.play(self.alex, cid, "22", "tv", caps={"direct": False})
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
        now[0] += MP.IDLE + 1
        self.assertEqual(self.get(f"/v1/media/stream/{sid}/file")[0], 404)
        sids = [self.play(self.alex, cid, "10", "tv")["session"] for _ in range(MP.MAX_PER_ACCOUNT + 2)]
        self.assertEqual(self.media.plays.count(), MP.MAX_PER_ACCOUNT)
        self.assertIsNone(self.media.plays.get(sids[0]))
        self.assertIsNotNone(self.media.plays.get(sids[-1]))

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
        self.assertTrue(direct_ok({"videoCodecs": ["h264"]}, "mp4", None))

    def test_safe_rel_and_clean_query(self):
        P = ("/video/:/transcode/universal/",)
        self.assertTrue(MP.safe_rel("/video/:/transcode/universal/session/a/base/0.ts", P))
        for bad in ("/library/x", "/video/:/transcode/universal/../x", "/video/:/transcode/universal/a//b",
                    "/video/:/transcode/universal/a@b", "/video/:/transcode/universal/a b", ""):
            self.assertFalse(MP.safe_rel(bad, P), bad)
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
        self.assertEqual(self.req("POST", "/v1/auth/logout", {}, h)[0], 200)
        self.assertEqual(self.req("GET", a["url"])[0], 404, "signed out: its playbacks end")
        self.assertEqual(self.req("GET", w["url"])[0], 404)
        self.log.flush()
        with open(os.path.join(self.tmp, "server.log"), "rb") as f:
            self.assertNotIn(TM.JF_PW_TOKEN.encode(), f.read())


if __name__ == "__main__":
    unittest.main()
