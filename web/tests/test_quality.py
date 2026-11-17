# Copyright 2026 The Orbit authors. SPDX-License-Identifier: Apache-2.0
"""Films and episodes away from home: which addresses count as away, the level "auto" picks, the capped transcode
of an IPTV film (plan + ffmpeg command line) and the caps sent to Plex / Jellyfin."""
import os
import sys
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
        self.assertIn(f"expr:gte(t,n_forced*{R.HLS_TIME})", s)
        self.assertNotIn("-c:v copy", s)
        self.assertNotIn("hvc1", s, "no HEVC tag on H.264 output")
        self.assertEqual(a.count("-i"), 1)
        self.assertEqual(a[a.index("-i") + 1], URL, "the URL only goes into -i")
        self.assertIn("-c:v copy", " ".join(R.ffmpeg_args(URL, R.plan(probe(), CHROME), "/d", 0)))


if __name__ == "__main__":
    unittest.main()
