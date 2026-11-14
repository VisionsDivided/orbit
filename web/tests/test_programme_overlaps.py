# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""Overlapping guide entries (providers send them; a guide drew two titles over each other): parsers.
normalize_programmes and the guide database answering normalised windows. Run: python -m unittest discover -s web/tests"""
import os
import shutil
import sys
import tempfile
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


class Normalize(unittest.TestCase):
    def test_sorted_and_exact_duplicates_dropped(self):
        lst = [prog(60, 90, "B"), prog(0, 60, "A"), prog(60, 90, "B"), prog(0, 60, "A")]
        self.assertEqual(spans(P.normalize_programmes(lst)), [(0, 60, "A"), (60, 90, "B")])

    def test_inside_a_longer_one_with_the_same_title(self):
        lst = [prog(0, 300, "NHL Hockey"), prog(60, 120, "NHL Hockey"), prog(300, 360, "Post-game")]
        self.assertEqual(spans(P.normalize_programmes(lst)), [(0, 300, "NHL Hockey"), (300, 360, "Post-game")])

    def test_overlaps_trimmed_to_the_next_start(self):
        lst = [prog(0, 300, "NHL Hockey on Arena"), prog(120, 150, "Next Game Today"), prog(150, 200, "Analysis")]
        out = P.normalize_programmes(lst)
        self.assertEqual(spans(out), [(0, 120, "NHL Hockey on Arena"), (120, 150, "Next Game Today"), (150, 200, "Analysis")])
        self.assertTrue(all(b.start >= a.stop for a, b in zip(out, out[1:])))
        self.assertEqual(lst[0].stop, T0 + 300 * 60, "input not changed")

    def test_same_start_keeps_the_longer_one_and_zero_length_goes(self):
        lst = [prog(0, 30, "Next Game Today"), prog(0, 300, "NHL Hockey"), prog(400, 400, "Empty"), prog(500, 450, "Back")]
        self.assertEqual(spans(P.normalize_programmes(lst)), [(0, 300, "NHL Hockey")])
        self.assertEqual(P.normalize_programmes([]), [])

    def test_clean_list_unchanged(self):
        lst = [prog(0, 30, "A"), prog(30, 90, "B"), prog(120, 150, "C")]
        out = P.normalize_programmes(lst)
        self.assertEqual(out, lst)
        self.assertTrue(all(a is b for a, b in zip(out, lst)))

    def test_matches_the_web_rules_on_a_messy_day(self):
        lst = [prog(0, 300, "NHL Hockey on Arena"), prog(0, 300, "NHL Hockey on Arena"), prog(30, 60, "NHL Hockey on Arena"),
               prog(120, 180, "Next Game Today"), prog(170, 240, "Analysis"), prog(240, 240, "Zero")]
        self.assertEqual(spans(P.normalize_programmes(lst)),
                         [(0, 120, "NHL Hockey on Arena"), (120, 170, "Next Game Today"), (170, 240, "Analysis")])


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
                                      (150, 200, "Analysis"), (300, 360, "Late News")])


if __name__ == "__main__":
    unittest.main()
