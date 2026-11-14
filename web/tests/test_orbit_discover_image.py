# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""GET /v1/discover/image: TMDB artwork through the server (orbit/discover.py), so the web page keeps a CSP that
allows only its own images. Only image.tmdb.org, an allowlisted size and a plain file name; cached on disk in the
/img cache (size-capped); only real images are served."""
import os
import shutil
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "server"))
from orbit import discover as D  # noqa: E402
from orbit import seerr as S  # noqa: E402
from orbit.accounts import Accounts  # noqa: E402
from orbit.api import Api, Call  # noqa: E402
from tvbro_server import auth as AU  # noqa: E402
from tvbro_server.app import ImageCache, _img_ctype  # noqa: E402

JPEG = b"\xff\xd8\xff\xe0" + b"\0" * 64
PNG = b"\x89PNG\r\n\x1a\n" + b"\0" * 64


class ImageProxy(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()
        self.auth = AU.Auth(self.d, b"k" * 32)
        self.acc = Accounts(self.d, self.auth)
        with open(os.path.join(self.d, "orbit-setup-token"), encoding="utf-8") as f:
            sid = self.acc.setup(f.read(), "alex", "correct horse 1", "Alex")
        self.owner = self.acc.gate(self.auth.session(sid))
        self.fetched = []
        self.answer = JPEG
        self.cache = ImageCache(os.path.join(self.d, "imgcache"))

        def fetch(url):
            self.fetched.append(url)
            if isinstance(self.answer, Exception):
                raise self.answer
            return self.answer
        self.disc = D.Discover(self.acc, S.SeerrConfig(self.d), fetch_image=fetch, image_cache=self.cache, sniff=_img_ctype)
        self.api = Api(self.acc)
        self.api.routes.update(self.disc.routes())

    def tearDown(self):
        self.acc.close()
        shutil.rmtree(self.d, ignore_errors=True)

    def get(self, p, who="owner"):
        return self.api.handle(Call("GET", "/v1/discover/image", self.owner if who == "owner" else None, {}, "192.0.2.10",
                                    {"p": p} if p is not None else {}))

    def test_serves_and_caches(self):
        r = self.get("/w500/abc_DEF-1.jpg")
        self.assertEqual((r.code, r.ctype, r.raw), (200, "image/jpeg", JPEG))
        self.assertEqual(self.fetched, ["https://image.tmdb.org/t/p/w500/abc_DEF-1.jpg"])
        self.assertIn("max-age", r.headers["Cache-Control"])
        self.assertIn("sandbox", r.headers["Content-Security-Policy"])
        r = self.get("/w500/abc_DEF-1.jpg")                 # second time: from the disk cache
        self.assertEqual((r.code, r.raw), (200, JPEG))
        self.assertEqual(len(self.fetched), 1)
        self.answer = PNG
        r = self.get("/original/xyz.png")
        self.assertEqual((r.code, r.ctype), (200, "image/png"))

    def test_only_allowlisted_sizes_and_plain_file_names(self):
        bad = [None, "", "/w500/", "/w501/a.jpg", "/w500/a.svg", "/w500/a.jpg?x=1", "/w500/../a.jpg", "/w500/a/b.jpg",
               "//evil.example.com/a.jpg", "/w500/a.jpg/", "https://image.tmdb.org/t/p/w500/a.jpg", "/w500/%2e%2e.jpg",
               "/w500/" + "a" * 101 + ".jpg", "/W500/a.jpg", "/w500/a.html"]
        for p in bad:
            self.assertEqual(self.get(p).code, 400, p)
        self.assertEqual(self.fetched, [])
        self.assertIsNone(D.image_url("/w500/a b.jpg"))
        self.assertEqual(D.image_url("/w92/x.webp"), "https://image.tmdb.org/t/p/w92/x.webp")

    def test_not_an_image_or_too_big_is_refused_and_not_cached(self):
        self.answer = b"<html>nope</html>"
        self.assertEqual(self.get("/w342/a.jpg").code, 415)
        self.answer = JPEG + b"\0" * D.IMAGE_MAX
        self.assertEqual(self.get("/w342/b.jpg").code, 413)
        self.answer = OSError("down")
        self.assertEqual(self.get("/w342/c.jpg").code, 404)
        self.answer = JPEG
        self.assertEqual(self.get("/w342/a.jpg").code, 200)  # the refused answer was not kept
        self.assertEqual(self.fetched.count("https://image.tmdb.org/t/p/w342/a.jpg"), 2)

    def test_signed_in_only(self):
        self.assertEqual(self.get("/w500/a.jpg", who=None).code, 401)
        self.assertEqual(self.fetched, [])

    def test_without_a_fetcher_answers_503(self):
        d = D.Discover(self.acc, S.SeerrConfig(self.d))
        api = Api(self.acc)
        api.routes.update(d.routes())
        r = api.handle(Call("GET", "/v1/discover/image", self.owner, {}, "192.0.2.10", {"p": "/w500/a.jpg"}))
        self.assertEqual(r.code, 503)


if __name__ == "__main__":
    unittest.main()
