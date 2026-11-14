# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""Device names ("Name this TV") and the linked-TV list (orbit/accounts.py, /v1/device/name, /v1/devices)."""
import os
import shutil
import sys
import tempfile
import unittest
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "server"))
from orbit import pairing as PA  # noqa: E402
from orbit.accounts import Accounts, ApiError, device_name  # noqa: E402
from orbit.api import Api, Call  # noqa: E402
from tvbro_server import auth as AU  # noqa: E402
from tvbro_server.streams import StreamRegistry  # noqa: E402


class Names(unittest.TestCase):
    def test_valid_names(self):
        for raw, want in (("Kitchen", "Kitchen"), ("  Kids'   room 2 ", "Kids' room 2"), ("Salón", "Salón"),
                          ("TV (Model-X)", "TV (Model-X)"), ("A&B #1+", "A&B #1+"), ("x" * 32, "x" * 32)):
            self.assertEqual(device_name(raw), want)

    def test_invalid_names(self):
        for bad in ("", "   ", "x" * 33, "<b>", "a\nb", "a\tb\x00", 'say "hi"', "tv‮", "\U0001F4FA", None, 5, ["a"]):
            with self.assertRaises(ApiError, msg=repr(bad)) as e:
                device_name(bad)
            self.assertEqual(e.exception.code, 400)


@mock.patch("tvbro_server.auth.ITERATIONS", 1000)
class Devices(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.d, True)
        self.auth = AU.Auth(self.d, b"k" * 32)
        self.acc = Accounts(self.d, self.auth)
        self.addCleanup(self.acc.close)
        with open(os.path.join(self.d, "orbit-setup-token"), encoding="utf-8") as f:
            self.alex = self.acc.gate(self.auth.session(self.acc.setup(f.read(), "alex", "correct horse 1", "Alex")))
        self.acc.create_account(self.alex, "bea", "another pass 2", "Bea", "admin")
        self.acc.create_account(self.alex, "sam", "another pass 3", "Sam")
        self.bea = self.acc.gate(self.auth.session(self.acc.login("bea", "another pass 2", "192.0.2.2")))
        self.sam = self.acc.gate(self.auth.session(self.acc.login("sam", "another pass 3", "192.0.2.3")))
        self.pr = PA.Pairings(self.acc)
        self.api = Api(self.acc, self.pr)

    def link(self, who, device):
        p = self.pr.start(device, "192.0.2.50")
        self.pr.approve(who, p["code"], None, "192.0.2.9")
        got = self.pr.poll(p["pairId"])
        return self.acc.gate(self.auth.session(got["session"]))

    def call(self, sess, method, path, body=None):
        r = self.api.handle(Call(method, path, sess, body or {}))
        return r.code, r.obj

    def test_tv_names_itself_and_the_name_is_listed(self):
        tv = self.link(self.alex, "TV (Model)")
        self.assertEqual(self.acc.device_name_of(tv["h"]), "")
        self.assertEqual(self.call(tv, "POST", "/v1/device/name", {"name": "Kitchen"}), (200, {"ok": True, "name": "Kitchen"}))
        self.assertEqual(self.acc.device_name_of(tv["h"]), "Kitchen")
        self.assertEqual(self.call(tv, "POST", "/v1/device/name", {"name": "x" * 40})[0], 400)
        mine = {s["device"]: s for s in self.acc.sessions(self.alex)}
        self.assertEqual((mine["TV (Model)"]["name"], mine["TV (Model)"]["kind"]), ("Kitchen", "tv"))
        code, j = self.call(self.alex, "GET", "/v1/devices")
        self.assertEqual(code, 200)
        self.assertEqual([(d["name"], d["device"], d["account"]["displayName"], d["profile"], d["canUnlink"]) for d in j["devices"]],
                         [("Kitchen", "TV (Model)", "Alex", "Alex", True)])
        self.assertEqual(self.call(None, "GET", "/v1/devices")[0], 401)

    def test_who_sees_renames_and_unlinks_which_tv(self):
        alex_tv = self.link(self.alex, "Alex TV")
        bea_tv = self.link(self.bea, "Bea TV")
        sam_tv = self.link(self.sam, "Sam TV")
        ids = {d["device"]: d["id"] for d in self.acc.devices(self.alex)}
        self.assertEqual(set(ids), {"Alex TV", "Bea TV", "Sam TV"})         # browsers are not listed
        self.assertEqual({d["device"] for d in self.acc.devices(self.bea)}, set(ids))
        self.assertEqual([d["device"] for d in self.acc.devices(self.sam)], ["Sam TV"])
        can = {d["device"]: d["canUnlink"] for d in self.acc.devices(self.bea)}
        self.assertEqual(can, {"Alex TV": False, "Bea TV": True, "Sam TV": True})
        # members: their own TVs only (others' answer 404, as if they did not exist)
        self.assertEqual(self.call(self.sam, "POST", "/v1/devices/rename", {"id": ids["Alex TV"], "name": "X"})[0], 404)
        self.assertEqual(self.call(self.sam, "POST", "/v1/devices/unlink", {"id": ids["Bea TV"]})[0], 404)
        self.assertEqual(self.call(self.sam, "POST", "/v1/devices/rename", {"id": ids["Sam TV"], "name": "Den"})[1]["name"], "Den")
        # admins rename any TV but unlink only their own and members'
        self.assertEqual(self.call(self.bea, "POST", "/v1/devices/rename", {"id": ids["Alex TV"], "name": "Living room"})[1]["name"],
                         "Living room")
        self.assertEqual(self.acc.device_name_of(alex_tv["h"]), "Living room")
        self.assertEqual(self.call(self.bea, "POST", "/v1/devices/unlink", {"id": ids["Alex TV"]})[0], 403)
        self.assertEqual(self.call(self.bea, "POST", "/v1/devices/unlink", {"id": ids["Sam TV"]}), (200, {"ok": True}))
        self.assertNotIn(sam_tv["h"], self.auth.sessions)                  # the TV is signed out
        # the owner unlinks anyone's; a browser session id is never a "device"
        self.assertEqual(self.call(self.alex, "POST", "/v1/devices/unlink", {"id": ids["Bea TV"]})[0], 200)
        self.assertNotIn(bea_tv["h"], self.auth.sessions)
        web = next(s["id"] for s in self.acc.sessions(self.sam))
        self.assertEqual(self.call(self.alex, "POST", "/v1/devices/unlink", {"id": web})[0], 404)
        self.assertEqual(self.call(self.alex, "POST", "/v1/devices/rename", {"id": ids["Alex TV"], "name": ""})[0], 400)


class StreamNames(unittest.TestCase):
    def test_device_name_then_profile_then_label(self):
        r = StreamRegistry(3)
        r.start("A" * 32, "TV (Model)", "News", profile="Kids", device="Kitchen")
        r.start("B" * 32, "Chrome on Mac", "Film", profile="Alex")
        r.start("C" * 32, "Chrome on Windows", "Match")
        act = r.status()["active"]
        self.assertEqual([(a["name"], a["device"]) for a in act],
                         [("Kitchen", "Kitchen"), ("Alex", ""), ("Chrome on Windows", "")])
        r.start("D" * 32, "Recorder", "Show", pinned=True)
        self.assertEqual(r.status()["active"][-1]["name"], "Recording")


if __name__ == "__main__":
    unittest.main()
