# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""Client addresses behind reverse proxies (tvbro_server/proxies.py, review L7)."""
import os
import sys
import unittest
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "server"))
from tvbro_server import proxies as PX  # noqa: E402

LOOP = PX.parse(PX.DEFAULT)
OUTER = PX.parse("127.0.0.1/32, ::1/128, 192.0.2.2/32")       # the image's nginx + a TLS proxy at 192.0.2.2


class ClientIp(unittest.TestCase):
    def test_untrusted_peer_ignores_every_header(self):
        for nets in (LOOP, OUTER, ()):
            self.assertEqual(PX.client_ip("192.0.2.50", ["198.51.100.1"], "198.51.100.2", nets), "192.0.2.50")
        self.assertEqual(PX.client_ip("127.0.0.1", ["198.51.100.1"], "", ()), "127.0.0.1", "nothing trusted")

    def test_bundled_nginx_alone(self):
        # nginx appends the address it saw; whatever the client sent before that is ignored
        self.assertEqual(PX.client_ip("127.0.0.1", ["192.0.2.50"], "192.0.2.50", LOOP), "192.0.2.50")
        self.assertEqual(PX.client_ip("127.0.0.1", ["203.0.113.66, 192.0.2.50"], "", LOOP), "192.0.2.50")
        self.assertEqual(PX.client_ip("::1", ["192.0.2.50"], "", LOOP), "192.0.2.50")

    def test_outer_proxy_must_be_trusted(self):
        chain = ["203.0.113.66, 198.51.100.7, 192.0.2.2"]           # spoofed, real client, the TLS proxy
        self.assertEqual(PX.client_ip("127.0.0.1", chain, "", OUTER), "198.51.100.7")
        self.assertEqual(PX.client_ip("127.0.0.1", chain, "", LOOP), "192.0.2.2", "proxy not listed: it is the client")

    def test_several_header_lines_count_in_order(self):
        self.assertEqual(PX.client_ip("127.0.0.1", ["203.0.113.66", "198.51.100.7, 192.0.2.2"], "", OUTER),
                         "198.51.100.7")

    def test_all_trusted_and_malformed(self):
        self.assertEqual(PX.client_ip("127.0.0.1", ["192.0.2.2"], "", OUTER), "192.0.2.2")
        # garbage left of trusted hops: the last trusted address counts, never the garbage
        self.assertEqual(PX.client_ip("127.0.0.1", ["not-an-ip, 192.0.2.2"], "", OUTER), "192.0.2.2")
        self.assertEqual(PX.client_ip("127.0.0.1", ["unknown"], "", OUTER), "127.0.0.1")
        self.assertEqual(PX.client_ip("127.0.0.1", ["x" * 500], "", OUTER), "127.0.0.1")

    def test_address_forms(self):
        self.assertEqual(PX.client_ip("127.0.0.1", ["[2001:db8::5]:4711"], "", LOOP), "2001:db8::5")
        self.assertEqual(PX.client_ip("127.0.0.1", ["192.0.2.50:4711"], "", LOOP), "192.0.2.50")
        self.assertEqual(PX.client_ip("::ffff:127.0.0.1", ["::ffff:192.0.2.50"], "", LOOP), "192.0.2.50")

    def test_x_real_ip_only_without_x_forwarded_for(self):
        self.assertEqual(PX.client_ip("127.0.0.1", [], "192.0.2.60", LOOP), "192.0.2.60")
        self.assertEqual(PX.client_ip("127.0.0.1", [], "junk", LOOP), "127.0.0.1")
        self.assertEqual(PX.client_ip("192.0.2.9", [], "192.0.2.60", LOOP), "192.0.2.9", "untrusted peer")

    def test_settings(self):
        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop(PX.ENV, None)
            self.assertEqual(PX.from_env(), LOOP)
        with mock.patch.dict(os.environ, {PX.ENV: " 198.18.0.0/15 , bad, 192.0.2.2"}):
            with self.assertLogs("tvbro.proxies", "WARNING"):
                nets = PX.from_env()
            self.assertEqual([str(n) for n in nets], ["198.18.0.0/15", "192.0.2.2/32"])
        with mock.patch.dict(os.environ, {PX.ENV: ""}):
            self.assertEqual(PX.from_env(), ())


if __name__ == "__main__":
    unittest.main()
