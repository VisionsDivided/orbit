# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""net.set_private_hosts: only the configured LAN media servers (exact host and port) may resolve to private
addresses; everything else keeps the strict rule; loopback and link-local stay refused even for listed hosts."""
import http.server
import os
import socket
import sys
import threading
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


class _Seen(http.server.BaseHTTPRequestHandler):
    seen: list = []

    def do_GET(self):
        type(self).seen.append((self.server.server_port, self.path, self.headers.get("Authorization"),
                                self.headers.get("X-Plex-Token")))
        if self.path == "/go":
            self.send_response(302)
            self.send_header("Location", self.server.target)
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("Content-Length", "2")
        self.end_headers()
        self.wfile.write(b"ok")

    def log_message(self, *a):
        pass


class Redirects(unittest.TestCase):
    """A token header must not follow a redirect to another host / port; POST bodies and methods go through."""

    def serve(self):
        s = http.server.HTTPServer(("127.0.0.1", 0), _Seen)
        threading.Thread(target=s.serve_forever, daemon=True).start()
        self.addCleanup(s.shutdown)
        self.addCleanup(s.server_close)
        return s

    def test_credentials_dropped_on_cross_host_redirect(self):
        a, b = self.serve(), self.serve()
        _Seen.seen = []
        with mock.patch.dict(os.environ, {"TVBRO_ALLOW_LOOPBACK_SOURCES": "1"}):
            a.target = f"http://127.0.0.1:{a.server_port}/same"
            NET.open_url(f"http://127.0.0.1:{a.server_port}/go", headers={"Authorization": "Bearer t1"}).read()
            a.target = f"http://127.0.0.1:{b.server_port}/other"
            NET.open_url(f"http://127.0.0.1:{a.server_port}/go", headers={"Authorization": "Bearer t1",
                                                                         "X-Plex-Token": "t2"}).read()
        same, other = _Seen.seen[1], _Seen.seen[3]
        self.assertEqual((same[1], same[2]), ("/same", "Bearer t1"), "same origin keeps it")
        self.assertEqual((other[0], other[1], other[2], other[3]), (b.server_port, "/other", None, None))


if __name__ == "__main__":
    unittest.main()
