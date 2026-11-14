# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""The fictional demo provider (tools/demo/demo_provider.py): its Xtream answers parse with the server's own
parsers, the catalogue has the promised size, artwork is valid PNG in the right shapes, and (when ffmpeg is on
PATH) the test video streams."""
import json
import os
import shutil
import struct
import sys
import threading
import unittest
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "server"))
sys.path.insert(0, os.path.join(HERE, "..", "..", "tools", "demo"))
import demo_provider as D  # noqa: E402
from tvbro_server import parsers as P  # noqa: E402


class DemoProvider(unittest.TestCase):
    video = False

    @classmethod
    def setUpClass(cls):
        cls.srv = D.make_server("127.0.0.1", 0, video=cls.video)
        cls.base = "http://127.0.0.1:%d" % cls.srv.server_address[1]
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()
        cls.srv.server_close()
        v = cls.srv.video
        if v is not None:
            shutil.rmtree(v.dir, ignore_errors=True)

    def get(self, path, headers=None):
        r = urllib.request.Request(self.base + path, headers=headers or {})
        try:
            with urllib.request.urlopen(r, timeout=30) as f:
                return f.status, dict(f.headers), f.read()
        except urllib.error.HTTPError as e:
            return e.code, dict(e.headers), e.read()

    def api(self, action="", **kw):
        q = "&".join(f"{k}={v}" for k, v in kw.items())
        code, _, body = self.get(f"/player_api.php?username=demo&password=demo&action={action}&{q}")
        self.assertEqual(code, 200)
        return body


class Answers(DemoProvider):
    def test_login(self):
        info = json.loads(self.api())
        self.assertEqual(info["user_info"]["auth"], 1)
        self.assertEqual(info["user_info"]["status"], "Active")
        _, _, body = self.get("/player_api.php?username=demo&password=wrong")
        self.assertEqual(json.loads(body)["user_info"]["auth"], 0)
        self.assertEqual(self.get("/xmltv.php?username=x&password=y")[0], 401)

    def test_live(self):
        cats = P.parse_categories(self.api("get_live_categories"))
        names = [c.category_name for c in cats]
        self.assertLessEqual({"News", "Sports", "Entertainment", "Movies", "Kids", "Locals"}, set(names))
        # a big provider's shape: about 200 more "<prefix> | <group>" groups, "Sports | NHL Teams" among them
        self.assertGreaterEqual(len([n for n in names if " | " in n]), 200)
        self.assertIn("Sports | NHL Teams", names)
        self.assertEqual(len(set(names)), len(names))
        live = json.loads(self.api("get_live_streams"))
        self.assertGreaterEqual(len(live), 400)
        self.assertEqual(len({c["stream_id"] for c in live}), len(live))
        self.assertEqual(len(json.loads(self.api("get_live_streams", category_id="1"))), 10)
        epg = json.loads(self.api("get_short_epg", stream_id=live[0]["stream_id"], limit=3))["epg_listings"]
        self.assertTrue(1 <= len(epg) <= 3)
        self.assertTrue(all(int(e["stop_timestamp"]) > int(e["start_timestamp"]) for e in epg))

    def test_films_and_shows_parse_with_the_server_parsers(self):
        mc = P.parse_categories(self.api("get_vod_categories"))
        films = P.parse_movies(self.api("get_vod_streams"), "p0", mc)
        self.assertGreaterEqual(len(films), 140)
        self.assertEqual(len({f.name for f in films}), len(films), "titles are unique")
        self.assertTrue(all(f.year and f.genre and f.added and f.rating and f.poster for f in films))
        self.assertTrue(all(f.tmdb is None for f in films), "no tmdb ids")
        self.assertGreater(max(f.added for f in films) - min(f.added for f in films), 90 * 86400, "added over months")
        saga = [f for f in films if f.name.startswith("The Hollowmere Saga")]
        self.assertEqual(len(saga), 8)
        sc = P.parse_categories(self.api("get_series_categories"))
        shows = P.parse_series_list(self.api("get_series"), "p0", sc)
        self.assertGreaterEqual(len(shows), 35)
        self.assertTrue(all(s.backdrop and s.backdrop.startswith(self.base) for s in shows))
        d = P.parse_vod_info(self.api("get_vod_info", vod_id=films[0].id))
        self.assertTrue(d.plot and d.backdrop)
        si = P.parse_series_info(self.api("get_series_info", series_id=shows[0].id))
        self.assertGreaterEqual(len(si.episodes), 6)
        self.assertEqual(len({e.id for e in si.episodes}), len(si.episodes))

    def test_guide(self):
        code, _, body = self.get("/xmltv.php?username=demo&password=demo")
        self.assertEqual(code, 200)
        root = ET.fromstring(body)
        chans = {c.get("id") for c in root.findall("channel")}
        progs = root.findall("programme")
        self.assertGreaterEqual(len(chans), 55)
        self.assertTrue({p.get("channel") for p in progs} <= chans)
        self.assertGreater(len(progs), len(chans) * 15, "about a day for every channel")

    def test_overlapping_guide_entries(self):
        """The NHL channels send overlapping entries on purpose (the server normalises them)."""
        c = D.Catalogue(n_films=10)
        ch = next(x for x in c.channels if x["group"] == "Sports | NHL Teams")
        progs = sorted(c.programmes(ch, 1794409200, 1794409200 + 86400))
        self.assertTrue(any(b[0] < a[1] for a, b in zip(progs, progs[1:])), "overlaps")
        self.assertIn("Next Game Today", {p[2] for p in progs})
        other = next(x for x in c.channels if x["group"] == "UK | News")
        op = c.programmes(other, 1794409200, 1794409200 + 86400)
        self.assertTrue(all(b[0] >= a[1] for a, b in zip(op, op[1:])), "the other channels do not overlap")

    def test_artwork(self):
        films = json.loads(self.api("get_vod_streams"))
        for url, ratio in ((films[0]["stream_icon"], 2 / 3), (films[0]["backdrop_path"][0], 16 / 9)):
            code, hdr, png = self.get(url[len(self.base):])
            self.assertEqual(code, 200)
            self.assertEqual(hdr["Content-Type"], "image/png")
            self.assertEqual(png[:8], b"\x89PNG\r\n\x1a\n")
            w, h = struct.unpack(">II", png[16:24])
            self.assertAlmostEqual(w / h, ratio, places=2)
        a, b = D.artwork("poster", "ember-line"), D.artwork("poster", "wildglass")
        self.assertNotEqual(D.palette("ember-line")[:2], D.palette("wildglass")[:2], "a palette per title")
        self.assertNotEqual(a, b)

    def test_streams_without_video_answer_404(self):
        self.assertEqual(self.get("/live/demo/demo/101.ts")[0], 404)
        self.assertEqual(self.get("/live/x/y/101.ts")[0], 401)


@unittest.skipUnless(shutil.which("ffmpeg"), "needs ffmpeg")
class Video(DemoProvider):
    video = True

    def test_streams(self):
        self.assertTrue(self.srv.video.ok, self.srv.video.error)
        code, hdr, body = self.get("/movie/demo/demo/2001.mp4", {"Range": "bytes=0-99"})
        self.assertEqual(code, 206)
        self.assertEqual(len(body), 100)
        self.assertTrue(hdr["Content-Range"].startswith("bytes 0-99/"))
        code, _, pl = self.get("/live/demo/demo/101.m3u8")
        self.assertEqual(code, 200)
        seg = [line for line in pl.decode().splitlines() if line.startswith("http")][0]
        code, _, ts = self.get(seg[len(self.base):])
        self.assertEqual((code, ts[0]), (200, 0x47))
        with urllib.request.urlopen(self.base + "/live/demo/demo/101.ts", timeout=30) as f:
            head = f.read(188 * 10)
        self.assertEqual(head[0], 0x47, "MPEG-TS sync byte")


if __name__ == "__main__":
    unittest.main()
