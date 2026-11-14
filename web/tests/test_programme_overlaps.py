# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""Overlapping guide entries (providers send them; a guide drew two titles over each other): parsers.
normalize_programmes and the guide database answering normalised windows (the timeline overlay: an inner entry ends, the enclosing one resumes). Run: python -m unittest discover -s web/tests"""
import os
import random
import shutil
import sys
import tempfile
import time
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "server"))
from tvbro_server import parsers as P  # noqa: E402
from tvbro_server.epgdb import EpgWriter, EpgDb  # noqa: E402

T0 = 1794409200
K = "p0|arena.demo"


def prog(s, e, title, key=K):
    return P.Programme(key, T0 + s * 60, T0 + e * 60, title, None)


def spans(lst):
    return [((p.start - T0) // 60, (p.stop - T0) // 60, p.title) for p in lst]


# The same table is in web/tests/guide.test.mjs and shared/src/test/.../EpgNormalizeTest.kt: (input, expected), in minutes
CASES = [
    ("owner's hockey: two 10:00-15:00 entries and a duplicate; post-game inside post-game",
     [(600, 900, "NHL Hockey on Arena"), (600, 900, "Next Game Today"), (600, 900, "NHL Hockey on Arena"),
      (900, 1020, "Post-game"), (930, 960, "Post-game")],
     [(600, 900, "NHL Hockey on Arena"), (900, 1020, "Post-game")]),
    ("owner's hockey: a filler inside the game, the game resumes",
     [(600, 900, "NHL Hockey on Arena"), (720, 750, "Next Game Today"), (900, 1020, "Post-game")],
     [(600, 720, "NHL Hockey on Arena"), (720, 750, "Next Game Today"), (750, 900, "NHL Hockey on Arena"),
      (900, 1020, "Post-game")]),
    ("Block / Match: the block resumes",
     [(60, 300, "Block"), (120, 180, "Match")],
     [(60, 120, "Block"), (120, 180, "Match"), (180, 300, "Block")]),
    ("partial overlap: the later one wins",
     [(120, 240, "B"), (60, 180, "A")],
     [(60, 120, "A"), (120, 240, "B")]),
    ("nested three levels",
     [(0, 600, "Outer"), (100, 400, "Mid"), (200, 300, "Inner")],
     [(0, 100, "Outer"), (100, 200, "Mid"), (200, 300, "Inner"), (300, 400, "Mid"), (400, 600, "Outer")]),
    ("same start: the longest, then the first in input order",
     [(0, 30, "Short"), (0, 300, "Long"), (0, 300, "Twin"), (300, 360, "X"), (300, 360, "Y")],
     [(0, 300, "Long"), (300, 360, "X")]),
    ("same title inside (and an exact duplicate) dropped",
     [(60, 300, "Marathon"), (120, 180, "Marathon"), (60, 300, "Marathon"), (300, 360, "Late")],
     [(60, 300, "Marathon"), (300, 360, "Late")]),
    ("zero-length and backwards entries dropped",
     [(60, 60, "Empty"), (60, 120, "Real"), (180, 150, "Backwards")],
     [(60, 120, "Real")]),
    ("a messy day",
     [(0, 300, "NHL Hockey on Arena"), (0, 300, "NHL Hockey on Arena"), (30, 60, "NHL Hockey on Arena"),
      (120, 180, "Next Game Today"), (170, 240, "Analysis"), (240, 240, "Zero")],
     [(0, 120, "NHL Hockey on Arena"), (120, 170, "Next Game Today"), (170, 240, "Analysis"),
      (240, 300, "NHL Hockey on Arena")]),
    ("a gap stays a gap",
     [(0, 60, "A"), (120, 180, "B"), (130, 140, "C")],
     [(0, 60, "A"), (120, 130, "B"), (130, 140, "C"), (140, 180, "B")]),
]


def reference(lst):
    """The rules written the slow, obvious way: kept entries, then minute by minute the one with the latest start."""
    ps = sorted((p for p in lst if p.stop > p.start), key=lambda p: (p.start, p.start - p.stop))
    kept = []
    for p in ps:
        if any(k.start == p.start for k in kept):
            continue
        if any(k.title == p.title and k.start <= p.start and k.stop >= p.stop for k in kept):
            continue
        kept.append(p)
    out = {}
    for k in kept:
        for m in range((k.start - T0) // 60, (k.stop - T0) // 60):
            if m not in out or out[m].start < k.start:
                out[m] = k
    return out


class Normalize(unittest.TestCase):
    def test_case_table(self):
        for name, inp, want in CASES:
            with self.subTest(name):
                self.assertEqual(spans(P.normalize_programmes([prog(*x) for x in inp])), want)
        self.assertEqual(P.normalize_programmes([]), [])

    def test_resumed_piece_is_a_copy_of_the_original(self):
        block = P.Programme(K, T0, T0 + 300 * 60, "Block", "desc", "sub", "S1 E2")
        lst = [block, prog(120, 180, "Match")]
        out = P.normalize_programmes(lst)
        self.assertEqual([(p.title, p.desc, p.sub, p.ep) for p in (out[0], out[2])], [("Block", "desc", "sub", "S1 E2")] * 2)
        self.assertEqual((lst[0].start, lst[0].stop), (T0, T0 + 300 * 60), "input not changed")

    def test_clean_list_unchanged(self):
        lst = [prog(0, 30, "A"), prog(30, 90, "B"), prog(120, 150, "C")]
        out = P.normalize_programmes(lst)
        self.assertEqual(out, lst)
        self.assertTrue(all(a is b for a, b in zip(out, lst)))

    def test_random_property(self):
        rnd = random.Random(7)
        for _ in range(300):
            lst = []
            for _ in range(rnd.randint(0, 30)):
                s = rnd.randint(0, 96) * 15
                lst.append(prog(s, s + rnd.randint(-1, 16) * 15, "t%d" % rnd.randint(0, 4)))
            out = P.normalize_programmes(lst)
            for a, b in zip(out, out[1:]):
                self.assertLessEqual(a.stop, b.start, "sorted, no overlap")
            self.assertTrue(all(p.stop > p.start for p in out))
            covered_in = {m for p in lst for m in range((p.start - T0) // 60, (p.stop - T0) // 60)}
            got = {m: p for p in out for m in range((p.start - T0) // 60, (p.stop - T0) // 60)}
            self.assertEqual(set(got), covered_in, "every covered minute still covered, no time invented")
            ref = reference(lst)
            self.assertEqual({m: p.title for m, p in got.items()}, {m: p.title for m, p in ref.items()})
            for p, q in zip(out, out[1:]):
                if p.stop == q.start:
                    self.assertFalse(ref[(p.start - T0) // 60] is ref[(q.start - T0) // 60], "adjacent pieces merged")

    def test_large_channel_is_fast(self):
        lst = [prog(i * 30, i * 30 + 30 + (i % 7) * 45, "t%d" % (i % 11)) for i in range(20000)]
        t = time.perf_counter()
        out = P.normalize_programmes(lst)
        self.assertLess(time.perf_counter() - t, 2.0)
        self.assertTrue(all(a.stop <= b.start for a, b in zip(out, out[1:])))


class GuideWindow(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def test_window_is_normalised(self):
        path = os.path.join(self.dir, "epg.db")
        w = EpgWriter(path)
        for p in (prog(0, 300, "NHL Hockey on Arena"), prog(120, 150, "Next Game Today"), prog(120, 150, "Next Game Today"),
                  prog(150, 200, "Analysis"), prog(300, 360, "Late News")):
            w.add(p)
        info = w.finish()
        db = EpgDb(path)
        db.keys = info["keys"]
        got = db.window(K, T0, T0 + 6 * 3600)
        self.assertEqual(spans(got), [(0, 120, "NHL Hockey on Arena"), (120, 150, "Next Game Today"),
                                      (150, 200, "Analysis"), (200, 300, "NHL Hockey on Arena"), (300, 360, "Late News")])


if __name__ == "__main__":
    unittest.main()
