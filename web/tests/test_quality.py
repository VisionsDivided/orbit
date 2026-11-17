# Copyright 2026 The Orbit authors. SPDX-License-Identifier: Apache-2.0
"""Films and episodes away from home: which addresses count as away, the level "auto" picks, the capped transcode
of an IPTV film (plan + ffmpeg command line) and the caps sent to Plex / Jellyfin."""
import os
import re
import shutil
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "server"))
from tvbro_server import quality as Q  # noqa: E402
from tvbro_server import remux as R  # noqa: E402
from test_remux import probe, CHROME, URL  # noqa: E402


class Away(unittest.TestCase):
    def test_home_and_away_addresses(self):
        with mock.patch.dict(os.environ, {Q.ENV_NETS: ""}):
            for home in ("192.168.1.20", "10.0.0.5", "172.16.3.4", "127.0.0.1", "::1", "fe80::1", "fd00::5",
                         "::ffff:192.168.1.20", "", "not an address"):
                self.assertFalse(Q.is_away(home), home)
            for away in ("100.101.102.103", "fd7a:115c:a1e0::12", "1.1.1.1", "8.8.8.8", "2606:4700::1",
                         "::ffff:100.64.0.9"):
                self.assertTrue(Q.is_away(away), away)

    def test_a_vpn_router_on_the_lan_can_be_listed(self):
        with mock.patch.dict(os.environ, {Q.ENV_NETS: "192.0.2.1, 198.51.100.0/24, junk"}):
            self.assertTrue(Q.is_away("192.0.2.1"))
            self.assertTrue(Q.is_away("198.51.100.77"))
            self.assertFalse(Q.is_away("192.0.2.2"))

    def test_auto_picks_original_at_home_and_the_away_level_away(self):
        with mock.patch.dict(os.environ, {Q.ENV_NETS: "", Q.ENV_LEVEL: ""}):
            self.assertEqual(Q.choose(None, "192.168.1.20"),
                             {"level": "orig", "cap": None, "away": False, "label": "Original"})
            self.assertEqual(Q.choose("auto", "100.100.1.1"),
                             {"level": "4", "cap": (4000, 720), "away": True, "label": "720p · 4 Mbps"})
            self.assertEqual(Q.choose("8", "192.168.1.20")["cap"], (8000, 1080), "a chosen level applies at home too")
            self.assertEqual(Q.choose("orig", "100.100.1.1")["cap"], None, "and Original applies away")
            self.assertEqual(Q.choose("nonsense", "100.100.1.1")["level"], "4")
        with mock.patch.dict(os.environ, {Q.ENV_LEVEL: "2"}):
            self.assertEqual(Q.choose(None, "100.100.1.1")["level"], "2")
        with mock.patch.dict(os.environ, {Q.ENV_LEVEL: "huge"}):
            self.assertEqual(Q.choose(None, "100.100.1.1")["level"], "4")
        with mock.patch.dict(os.environ, {Q.ENV_NETS: "", Q.ENV_LEVEL: "", Q.ENV_ORIG: ""}):
            away = "100.100.1.1"
            for kbps, level in ((40000, "orig"), (28572, "orig"), (28000, "8"), (12000, "8"), (11000, "4"),
                                (6000, "4"), (5000, "2"), (500, "2")):
                self.assertEqual(Q.choose("auto", away, kbps)["level"], level, kbps)
            self.assertEqual(Q.choose(None, away, "12000")["speed"], 12000)
            self.assertEqual(Q.choose(None, "192.168.1.20", 500)["level"], "orig", "home is always Original")
            self.assertNotIn("speed", Q.choose(None, "192.168.1.20", 500))
            self.assertEqual(Q.choose("8", away, 500)["level"], "8", "a chosen level wins over the measurement")
            for junk in (0, -5, True, "fast", float("nan"), 10 ** 9):
                self.assertEqual(Q.choose(None, away, junk)["level"], "4", junk)
        with mock.patch.dict(os.environ, {Q.ENV_ORIG: "50000"}):
            self.assertEqual(Q.choose(None, "100.100.1.1", 40000)["level"], "8")
        self.assertEqual(len(Q.test_bytes("1")), 256 * 1024)
        self.assertEqual(len(Q.test_bytes(None)), 2 * 1024 * 1024)
        self.assertEqual(len(Q.test_bytes(10 ** 12)), Q.TEST_MAX)
        self.assertEqual(Q.public(Q.choose(None, "100.100.1.1", 6000))["speedKbps"], 6000)
        self.assertEqual(Q.resolution(720), "1280x720")
        self.assertEqual(Q.resolution(480), "854x480")


class CappedFilm(unittest.TestCase):
    def test_plan_transcodes_any_video_and_makes_audio_stereo_aac(self):
        p = R.plan(probe("hevc", audio=(("eac3", 6, "eng", True),)), CHROME, cap=(4000, 720))
        self.assertTrue(p["ok"], "HEVC plays in Chrome once it is re-encoded")
        self.assertEqual(p["video"]["cap"], (4000, 720))
        self.assertEqual((p["a"]["copy"], p["a"]["out_channels"]), (False, 2))
        self.assertIn("converted to 720p at 4 Mbps", p["notes"])
        self.assertTrue(R.plan(probe(audio=(("aac", 2, "eng", True),)), CHROME, cap=(4000, 720))["a"]["copy"])
        self.assertFalse(R.plan(probe(audio=(("aac", 6, "eng", True),)), CHROME, cap=(4000, 720))["a"]["copy"])
        self.assertIsNone(R.plan(probe(), CHROME)["video"]["cap"], "no cap: copied as before")

    def test_ffmpeg_encodes_under_the_cap(self):
        a = R.ffmpeg_args(URL, R.plan(probe("hevc"), CHROME, cap=(4000, 720)), "/d", 30)
        s = " ".join(a)
        self.assertIn("-c:v libx264", s)
        self.assertIn("-b:v 4000k -maxrate 4000k -bufsize 8000k", s)
        self.assertIn("scale=-2:'min(720,ih)'", s)
        self.assertIn("-g 9999 -sc_threshold 0", s, "keyframes only on the 6-second marks")
        self.assertIn(f"gte(t,(floor(prev_forced_t/{R.HLS_TIME}+0.001)+1)*{R.HLS_TIME})", s)
        self.assertEqual(a[a.index("-hls_time") + 1], R.SEEK_HLS_TIME)
        b = R.ffmpeg_args(URL, R.plan(probe("hevc"), CHROME, cap=(4000, 720)), "/d", 42, start_number=7)
        self.assertEqual((b[b.index("-ss") + 1], b[b.index("-start_number") + 1]), ("42.000", "7"))
        self.assertNotIn("-c:v copy", s)
        self.assertNotIn("hvc1", s, "no HEVC tag on H.264 output")
        self.assertEqual(a.count("-i"), 1)
        self.assertEqual(a[a.index("-i") + 1], URL, "the URL only goes into -i")
        self.assertIn("-c:v copy", " ".join(R.ffmpeg_args(URL, R.plan(probe(), CHROME), "/d", 0)))



class SeekableFilm(unittest.TestCase):
    """An app's converted film: the whole film as a VOD playlist, ffmpeg restarted where the viewer jumps."""

    def test_vod_playlist_lists_every_segment(self):
        t = R.vod_playlist(20.5, "tvabc")
        self.assertIn("#EXT-X-PLAYLIST-TYPE:VOD", t)
        self.assertIn('#EXT-X-MAP:URI="init.mp4?sid=tvabc"', t)
        self.assertEqual(re.findall(r"#EXTINF:([\d.]+),", t), ["6.000", "6.000", "6.000", "2.500"])
        self.assertEqual(re.findall(r"^s\d{5}\.m4s\?sid=tvabc$", t, re.M)[-1], "s00003.m4s?sid=tvabc")
        self.assertTrue(t.rstrip().endswith("#EXT-X-ENDLIST"))
        self.assertTrue(R.NAME_RE.match("full.m3u8"))

    def test_a_jump_restarts_ffmpeg_at_that_segment(self):
        d = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, d, True)
        procs = []

        class P:
            def __init__(self, args, **kw):
                self.args, self.stderr, self.rc = args, iter(()), None
                procs.append(self)
            def poll(self): return self.rc
            def wait(self, *a): return self.rc          # a running ffmpeg stays running
            def terminate(self): self.rc = -15
            def send_signal(self, s): pass

        with mock.patch.object(R.subprocess, "Popen", P), mock.patch.object(R.NET, "check_host", lambda u: None):
            m = R.RemuxManager(d, lambda: 2, lambda sid: False)
            j = m.start_seekable("tvsid0001", "xt:p1:movie:1.mkv:q4", URL,
                                 R.plan(probe("hevc"), CHROME, cap=(4000, 720)), 600.0)
            self.assertEqual(len(procs), 1)
            open(os.path.join(j.dir, "s00000.m4s"), "wb").close()
            m.ensure(j, 0)                                     # there
            m.ensure(j, 3)                                     # ffmpeg is at 1: coming soon, wait
            self.assertEqual(len(procs), 1)
            m.ensure(j, 50)                                    # a jump: restart at 5 minutes
            self.assertEqual(len(procs), 2)
            a = procs[-1].args
            self.assertEqual((a[a.index("-ss") + 1], a[a.index("-start_number") + 1]), ("300.000", "50"))
            self.assertEqual(procs[0].rc, -15, "the old ffmpeg is stopped")
            self.assertFalse(os.path.exists(os.path.join(j.dir, "s00000.m4s")), "old segments go")
            self.assertEqual((j.first_seg, j.produced()), (50, 50))
            m.ensure(j, 52)
            self.assertEqual(len(procs), 2)
            m.ensure(j, 10)                                    # back before the restart point
            self.assertEqual(len(procs), 3)
            m.ensure(j, 100)                                   # past the end: nothing
            self.assertEqual(len(procs), 3)


if __name__ == "__main__":
    unittest.main()
