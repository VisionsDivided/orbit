# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""Fixes from the security review (2026-11-14): who gets the provider logins, TV session role, pairing peek,
login throttling per username, per-account session cap, passwords, gate cache invalidation, setup ending old
sessions."""
import os
import sys
import tempfile
import unittest
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "server"))
from orbit import accounts as AC  # noqa: E402
from orbit import pairing as PA  # noqa: E402
from orbit.accounts import Accounts, ApiError  # noqa: E402
from orbit.api import Api, Call  # noqa: E402
from tvbro_server import auth as AU  # noqa: E402


class Clock:
    def __init__(self):
        self.t = 2_000_000.0

    def __call__(self):
        return self.t


@mock.patch("tvbro_server.auth.ITERATIONS", 1000)
class Security(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()
        self.clock = Clock()
        self.auth = AU.Auth(self.d, b"k" * 32, self.clock)
        self.old = self.auth.setup("tvbro admin pass")                 # a tvBro-era admin session
        self.acc = Accounts(self.d, self.auth, self.clock)
        with open(os.path.join(self.d, "orbit-setup-token"), encoding="utf-8") as f:
            self.alex = self.acc.gate(self.auth.session(self.acc.setup(f.read(), "alex", "correct horse 1")))
        self.acc.create_account(self.alex, "sam", "another pass 2")
        self.sam = self.acc.gate(self.auth.session(self.acc.login("sam", "another pass 2", "192.0.2.20")))
        self.pr = PA.Pairings(self.acc, self.clock)
        self.api = Api(self.acc, self.pr, playlists=lambda: [{"id": "p0", "user": "u", "pass": "secret"}])

    def link_tv(self, who, client="192.0.2.30"):
        p = self.pr.start("Bedroom TV", client)
        self.pr.approve(who, p["code"], None, "192.0.2.9")
        return self.acc.gate(self.auth.session(self.pr.poll(p["pairId"])["session"]))

    def test_setup_ended_tvbro_sessions(self):
        self.assertIsNone(self.auth.session(self.old))

    def test_member_tv_does_not_get_the_provider_login(self):
        tv = self.link_tv(self.sam)
        r = self.api.handle(Call("GET", "/v1/device/playlists", tv))
        self.assertEqual(r.code, 403)
        self.assertNotIn("secret", str(r.obj))
        self.assertEqual(self.api.handle(Call("GET", "/v1/device/playlists", self.link_tv(self.alex))).code, 200)

    def test_tv_sessions_never_get_the_admin_role(self):
        tv = self.link_tv(self.alex)
        self.assertEqual(tv["role"], "viewer")
        self.assertEqual(self.alex["role"], "admin")

    def test_peek_shows_the_waiting_device_first(self):
        p = self.pr.start("Kitchen TV", "192.0.2.44")
        info = self.pr.peek(self.alex, p["code"], "192.0.2.9")
        self.assertEqual((info["device"], info["address"]), ("Kitchen TV", "192.0.2.44"))
        with self.assertRaises(ApiError):
            self.pr.peek(self.alex, "BBBB-BBBB", "192.0.2.9")

    def test_a_success_elsewhere_does_not_reset_guessing_a_username(self):
        for i in range(PA.CODE_LEN):                                # well past the free tries
            with self.assertRaises(ApiError):
                self.acc.login("alex", "wrong guess %d" % i, "192.0.2.%d" % (60 + i))   # many addresses
            self.acc.login("sam", "another pass 2", "192.0.2.99")    # the attacker's own account
        with self.assertRaises(ApiError) as e:
            self.acc.login("alex", "correct horse 1", "192.0.2.77")
        self.assertEqual(e.exception.code, 429)

    def test_one_account_cannot_push_out_the_others(self):
        for _ in range(AC.MAX_SESSIONS_PER_ACCOUNT + 5):
            self.clock.t += 1
            self.acc.login("sam", "another pass 2", "192.0.2.20")
        self.assertIn(self.alex["h"], self.auth.sessions, "the owner's session survives")
        self.assertLessEqual(len(self.acc.sessions(self.sam)), AC.MAX_SESSIONS_PER_ACCOUNT)

    def test_passwords(self):
        other = self.acc.gate(self.auth.session(self.acc.login("sam", "another pass 2", "192.0.2.21")))
        with self.assertRaises(ApiError):
            self.acc.change_password(self.sam, "nope nope nope", "a brand new pass")
        self.acc.change_password(self.sam, "another pass 2", "a brand new pass")
        self.assertIn(self.sam["h"], self.auth.sessions, "this session stays")
        self.assertNotIn(other["h"], self.auth.sessions, "the others end")
        self.acc.login("sam", "a brand new pass", "192.0.2.22")
        sam_id = self.sam["account_id"]
        with self.assertRaises(ApiError):
            self.acc.reset_password(self.sam, self.alex["account_id"], "x" * 12)     # a member resets nobody
        self.acc.reset_password(self.alex, sam_id, "set by the owner")
        self.assertNotIn(self.sam["h"], self.auth.sessions)
        self.acc.login("sam", "set by the owner", "192.0.2.23")
        with self.assertRaises(ApiError):
            self.acc.reset_password(self.alex, self.alex["account_id"], "x" * 12)    # not your own this way

    def test_revoked_session_ends_at_once_despite_the_cache(self):
        tv = self.link_tv(self.alex)
        self.assertIsNotNone(self.acc.gate({"h": tv["h"], "role": "viewer", "renew": False}))   # cached as valid
        sid_pub = next(s["id"] for s in self.acc.sessions(self.alex) if s["device"] == "Bedroom TV")
        self.acc.revoke(self.alex, sid_pub)
        self.assertNotIn(tv["h"], self.auth.sessions)
        self.assertIsNone(self.acc.gate({"h": tv["h"], "role": "viewer", "renew": False}))

    def test_expired_pairings_are_tidied_on_any_request(self):
        p = self.pr.start("TV", "192.0.2.5")
        self.pr.approve(self.alex, p["code"], None, "192.0.2.9")
        before = len(self.auth.sessions)
        self.clock.t += PA.TTL + 1
        self.api.handle(Call("GET", "/v1/status", None))                # any API call
        self.assertEqual(len(self.auth.sessions), before - 1)


if __name__ == "__main__":
    unittest.main()
