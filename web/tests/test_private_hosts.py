# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""net.set_private_hosts: only the configured LAN media servers (exact host and port) may resolve to private
addresses; everything else keeps the strict rule; loopback and link-local stay refused even for listed hosts."""
import os
import socket
import sys
import unittest
from unittest import mock

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "server"))
from tvbro_server import net as NET  # noqa: E402


def fake_dns(addr):
    return lambda host, port, *a, **k: [(socket.AF_INET, socket.SOCK_STREAM, 6, "", (addr, port))]


class PrivateHosts(unittest.TestCase):
    def tearDown(self):
        NET.set_private_hosts(lambda: set())

    def test_only_listed_host_and_port(self):
        NET.set_private_hosts(lambda: {("jellyfin.example.lan", 8096)})
        with mock.patch.object(NET, "allow_private", return_value=False), mock.patch.object(NET.socket, "getaddrinfo", fake_dns("10.1.2.3")):
            self.assertTrue(NET.resolve("Jellyfin.Example.lan", 8096))
            with self.assertRaises(NET.Blocked):
                NET.resolve("jellyfin.example.lan", 22)                     # another port
            with self.assertRaises(NET.Blocked):
                NET.resolve("router.example.lan", 8096)                     # another host

    def test_loopback_and_link_local_stay_refused(self):
        NET.set_private_hosts(lambda: {("plex.example.lan", 32400)})
        for addr in ("127.0.0.1", "169.254.169.254"):
            with mock.patch.object(NET.socket, "getaddrinfo", fake_dns(addr)), mock.patch.dict(os.environ, {"TVBRO_ALLOW_LOOPBACK_SOURCES": ""}):
                with self.assertRaises(NET.Blocked):
                    NET.resolve("plex.example.lan", 32400)


if __name__ == "__main__":
    unittest.main()
