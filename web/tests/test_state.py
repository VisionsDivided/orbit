# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
import os
import sys
import tempfile
import time
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "server"))
from tvbro_server.state import StateStore  # noqa: E402


class State(unittest.TestCase):
    def test_roundtrip_and_bounds(self):
        d = tempfile.mkdtemp()
        s = StateStore(os.path.join(d, "s.db"))
        self.assertTrue(s.put("mylist", "m|1", {"key": "m|1", "name": "A"}))
        s.put("resume", "m|1", {"secs": 10})
        s.put("prefs", "solid", True)
        for i in range(25):
            s.put("history", f"q{i}", {"q": f"q{i}"})
            time.sleep(0.001)
        a = s.all()
        self.assertEqual(a["mylist"][0]["name"], "A")
        self.assertEqual(a["resume"]["m|1"]["secs"], 10)
        self.assertIs(a["prefs"]["solid"], True)
        self.assertEqual(len(a["history"]), 20)
        self.assertEqual(a["history"][0]["q"], "q24")      # newest first
        self.assertFalse(s.put("nope", "x", 1))
        s.delete("mylist", "m|1")
        s.clear("history")
        a = s.all()
        self.assertEqual((a["mylist"], a["history"]), ([], []))


class StateOrder(unittest.TestCase):
    def test_newest_first_even_when_the_clock_does_not_move(self):
        from unittest import mock
        d = tempfile.mkdtemp()
        s = StateStore(os.path.join(d, "s.db"))
        with mock.patch("tvbro_server.state.time.time", return_value=1000.0):
            for i in range(5):
                s.put("mylist", f"k{i}", {"key": f"k{i}"})
        self.assertEqual([x["key"] for x in s.all()["mylist"]], ["k4", "k3", "k2", "k1", "k0"])
        # a reopened store keeps counting up from what is stored, even if the clock went back
        s2 = StateStore(os.path.join(d, "s.db"))
        with mock.patch("tvbro_server.state.time.time", return_value=10.0):
            s2.put("mylist", "k5", {"key": "k5"})
        self.assertEqual(s2.all()["mylist"][0]["key"], "k5")


if __name__ == "__main__":
    unittest.main()
