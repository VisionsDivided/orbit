# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""A redirect to another host must not carry credential headers (urllib copies all headers by default)."""
import email.message
import os
import sys
import unittest
import urllib.request

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "server"))
from tvbro_server import net as NET  # noqa: E402


def follow(src: str, dst: str):
    req = urllib.request.Request(src, headers={"X-Api-Key": "k", "X-Plex-Token": "t", "Authorization": "Bearer b",
                                               "Cookie": "c=1", "Accept": "application/json"})
    return NET._RedirectHandler().redirect_request(req, None, 302, "Found", email.message.Message(), dst)


class RedirectHeaders(unittest.TestCase):
    def test_other_host_drops_credentials(self):
        new = follow("http://seerr.example.com:5055/api/v1/x", "http://evil.example.net/x")
        names = {k.lower() for k in new.headers}
        self.assertFalse(names & {"x-api-key", "x-plex-token", "authorization", "cookie"})
        self.assertIn("accept", names)

    def test_same_origin_keeps_them(self):
        new = follow("http://seerr.example.com:5055/api/v1/x", "http://Seerr.example.com:5055/api/v1/y")
        self.assertIn("x-api-key", {k.lower() for k in new.headers})

    def test_other_port_counts_as_another_server(self):
        new = follow("http://seerr.example.com:5055/a", "http://seerr.example.com:8080/a")
        self.assertNotIn("x-api-key", {k.lower() for k in new.headers})


if __name__ == "__main__":
    unittest.main()
