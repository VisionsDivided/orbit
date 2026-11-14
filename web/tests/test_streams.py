# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "server"))
from tvbro_server.streams import ACTIVE_SEC, StreamChanged, StreamRegistry, stream_id  # noqa: E402


class Clock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t


class ReplaceOldest(unittest.TestCase):
    def setUp(self):
        self.c = Clock()
        self.r = StreamRegistry(1, self.c)

    def test_second_tab_replaces_first(self):
        self.assertEqual(self.r.start("A", "tab A", "Ch 1"), [])
        g = self.r.touch("A")
        self.c.t += 5
        self.assertEqual(self.r.start("B", "tab B", "Ch 2"), ["A"])
        self.assertIsNone(self.r.touch("A"))          # A's media requests -> 409
        self.assertFalse(self.r.alive("A", g))        # A's open relay closes
        self.assertIsNotNone(self.r.touch("B"))
        # "Play here" in tab A takes it back and stops B
        self.c.t += 5
        self.assertEqual(self.r.start("A"), ["B"])
        self.assertIsNone(self.r.touch("B"))

    def test_oldest_of_several_is_stopped(self):
        self.r.set_max(2)
        self.r.start("A"); self.c.t += 1
        self.r.start("B"); self.c.t += 1
        self.assertEqual(self.r.start("C"), ["A"])
        self.assertEqual(self.r.status()["count"], 2)

    def test_same_tab_restart_does_not_evict_itself(self):
        self.r.start("A", what="preview")
        self.assertEqual(self.r.start("A", what="Ch 5 full screen"), [])
        self.assertEqual(self.r.status()["active"][0]["what"], "Ch 5 full screen")

    def test_idle_sessions_do_not_count(self):
        self.r.start("A")
        self.c.t += ACTIVE_SEC + 1
        self.assertEqual(self.r.start("B"), [])
        self.assertIsNotNone(self.r.touch("A"))       # idle, not replaced

    def test_released_session_frees_the_slot(self):
        self.r.start("A"); self.r.stop("A")
        self.assertEqual(self.r.start("B"), [])

    def test_unknown_sid_starts_implicitly(self):
        self.assertIsNotNone(self.r.touch("X"))
        self.assertEqual(self.r.status()["count"], 1)
        self.assertEqual(self.r.touch(None), 0)


class ReplaceChosen(unittest.TestCase):
    """Orbit: the person starting a stream picks which one to stop ("Stop Kitchen and watch")."""

    def setUp(self):
        self.c = Clock()
        self.r = StreamRegistry(2, self.c)
        self.r.start("KITCHEN", "Kitchen TV", "News", profile="Kitchen"); self.c.t += 1
        self.r.start("BEDROOM", "Bedroom TV", "Film", profile="Bedroom"); self.c.t += 1

    def ids(self):
        return {a["label"]: a["id"] for a in self.r.status()["active"]}

    def test_status_names_every_stream_with_a_public_id(self):
        act = self.r.status()["active"]
        self.assertEqual([(a["label"], a["profile"], a["what"]) for a in act],
                         [("Kitchen TV", "Kitchen", "News"), ("Bedroom TV", "Bedroom", "Film")])
        self.assertEqual(act[0]["id"], stream_id("KITCHEN"))
        self.assertNotIn("KITCHEN", act[0]["id"])                 # a hash, not the session id itself
        self.assertEqual(len({a["id"] for a in act}), 2)

    def test_the_chosen_stream_is_stopped_not_the_oldest(self):
        self.assertEqual(self.r.start("LIVING", "Living room", "Match", replace=self.ids()["Bedroom TV"]), ["BEDROOM"])
        self.assertIsNone(self.r.touch("BEDROOM"))
        self.assertIsNotNone(self.r.touch("KITCHEN"))
        self.assertEqual(self.r.why("BEDROOM"), "replaced")

    def test_without_a_choice_the_oldest_goes(self):
        self.assertEqual(self.r.start("LIVING"), ["KITCHEN"])

    def test_chosen_stream_ended_and_slot_free_just_starts(self):
        gone = self.ids()["Bedroom TV"]
        self.r.stop("BEDROOM")
        self.assertEqual(self.r.start("LIVING", replace=gone), [])
        self.assertIsNotNone(self.r.touch("KITCHEN"))

    def test_chosen_stream_ended_but_all_slots_taken_changes_nothing(self):
        gone = self.ids()["Bedroom TV"]
        self.r.stop("BEDROOM")
        self.r.start("OFFICE", "Office", "Talk")                 # someone else took the free slot meanwhile
        with self.assertRaises(StreamChanged):
            self.r.start("LIVING", replace=gone)
        self.assertIsNotNone(self.r.touch("KITCHEN"))
        self.assertIsNotNone(self.r.touch("OFFICE"))
        self.assertEqual(self.r.status()["count"], 2)

    def test_a_recording_cannot_be_chosen(self):
        self.r.stop("BEDROOM")
        self.r.start("REC", "Recording", "Show", pinned=True)
        with self.assertRaises(StreamChanged):
            self.r.start("LIVING", replace=self.ids()["Recording"])   # the viewer is not silently stopped instead
        self.assertIsNotNone(self.r.touch("KITCHEN"))
        # every slot held by recordings: refused as "recording", as before, whatever was chosen
        self.r.set_max(1); self.r.stop("KITCHEN")
        self.assertIsNone(self.r.start("LIVING", replace=self.ids()["Recording"]))

    def test_unknown_id_while_full_changes_nothing(self):
        with self.assertRaises(StreamChanged):
            self.r.start("LIVING", replace="000000000000")
        self.assertEqual(self.r.status()["count"], 2)


if __name__ == "__main__":
    unittest.main()
