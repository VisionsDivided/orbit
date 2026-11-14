# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""Provider addresses typed with capitals (net.lower_host): the host is fetched in lower case, because some
HTTPS servers fail the TLS handshake when the SNI name has capitals. Path, query and login keep their case."""
import os
import sys
import unittest
from unittest import mock

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "server"))
from tvbro_server import net as NET  # noqa: E402


class LowerHost(unittest.TestCase):
    def test_rules(self):
        L = NET.lower_host
        self.assertEqual(L("https://TV.Example.com/player_api.php?username=Alice&password=PaSS"),
                         "https://tv.example.com/player_api.php?username=Alice&password=PaSS")
        self.assertEqual(L("http://Tv.Example.COM:8080/Live/User/Pass/1.ts"), "http://tv.example.com:8080/Live/User/Pass/1.ts")
        self.assertEqual(L("http://User:PaSS@Tv.Example.com/get.php"), "http://User:PaSS@tv.example.com/get.php")
        self.assertEqual(L("http://[2001:DB8::1]:8080/x"), "http://[2001:db8::1]:8080/x")
        for same in ("https://tv.example.com/a?b=C", "http://192.0.2.10:8750/", "not a url"):
            self.assertEqual(L(same), same)

    def test_open_url_fetches_the_lower_case_host(self):
        seen = []
        with mock.patch.object(NET, "check_url"), mock.patch.object(NET._OPENER, "open", side_effect=lambda req, timeout: seen.append(req.full_url)):
            NET.open_url("https://TV.Example.com/player_api.php?username=Alice")
        self.assertEqual(seen, ["https://tv.example.com/player_api.php?username=Alice"])


if __name__ == "__main__":
    unittest.main()
