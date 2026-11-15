# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""Stream-format choice (HLS for clients without Media Source Extensions) and sealed
segment URLs. Run: python -m unittest discover -s web/tests"""
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "server"))
from tvbro_server import parsers as P  # noqa: E402

LIVE = "http://tv.example:8080/live/alice/s3cret/101.ts"


class FormatChoice(unittest.TestCase):
    def test_xtream_live_has_an_hls_form(self):
        self.assertEqual(P.choose_format(LIVE, "hls"), ("http://tv.example:8080/live/alice/s3cret/101.m3u8", "hls"))
        self.assertEqual(P.choose_format(LIVE, "ts"), (LIVE, "ts"))
        self.assertEqual(P.choose_format(LIVE, ""), (LIVE, "ts"))          # old clients: unchanged

    def test_xtream_timeshift_has_an_hls_form(self):
        chans = P.to_channels("p0", "tv.example:8080", "alice", "s3cret", [],
                              [{"stream_id": 101, "name": "One", "tv_archive": 1, "tv_archive_duration": 2}])
        url = P.catchup_url(chans[0], 1_700_000_000, 1_700_003_600, 1_700_010_000)
        self.assertTrue(url.endswith("/60/2023-11-14:22-13/101.ts"))
        got, fmt = P.choose_format(url, "hls")
        self.assertEqual(fmt, "hls")
        self.assertTrue(got.endswith("/timeshift/alice/s3cret/60/2023-11-14:22-13/101.m3u8"))

    def test_a_playlist_source_stays_hls(self):
        u = "https://cdn.example/ch/index.m3u8?token=1"
        self.assertEqual(P.choose_format(u, "ts"), (u, "hls"))
        self.assertEqual(P.choose_format(u, "hls"), (u, "hls"))

    def test_plain_ts_source_cannot_become_hls(self):
        for u in ("http://tv.example/alice/s3cret/101", "http://other.example/stream/5.ts", "udp://239.0.0.1:1234"):
            self.assertEqual(P.choose_format(u, "hls"), (u, "ts"), u)
            self.assertIsNone(P.hls_variant(u))
        self.assertEqual(P.choose_format(None, "hls"), (None, "ts"))


class ByteRangePlaylist(unittest.TestCase):
    def test_slices_cover_the_file_in_whole_ts_packets(self):
        total, dur = 1_145_257_984, 1800
        pl = P.byterange_playlist("/stream?key=k&fmt=ts&sig=s", total, dur)
        lines = pl.splitlines()
        self.assertEqual(lines[0], "#EXTM3U")
        self.assertEqual(lines[-1], "#EXT-X-ENDLIST")
        self.assertIn("#EXT-X-PLAYLIST-TYPE:VOD", lines)
        ranges = [tuple(map(int, x.split(":")[1].split("@"))) for x in lines if x.startswith("#EXT-X-BYTERANGE")]
        durs = [float(x.split(":")[1].rstrip(",")) for x in lines if x.startswith("#EXTINF")]
        self.assertEqual(len(ranges), 300)                         # ~6 s each
        off = 0
        for ln, at in ranges:
            self.assertEqual(at, off)                               # contiguous, no gaps
            off += ln
        self.assertEqual(off, total)                                # nothing lost at the end
        self.assertTrue(all(ln % 188 == 0 for ln, _ in ranges[:-1]))
        self.assertAlmostEqual(sum(durs), dur, delta=0.5)
        target = int([x for x in lines if x.startswith("#EXT-X-TARGETDURATION")][0].split(":")[1])
        self.assertGreaterEqual(target, max(durs))
        self.assertEqual(sum(1 for x in lines if x.startswith("/stream?")), 300)

    def test_file_that_begins_mid_packet(self):
        pkt = bytes([0x47]) + bytes(187)
        self.assertEqual(P.ts_phase(pkt * 8), 0)
        self.assertEqual(P.ts_phase(b"\x00" * 12 + pkt * 8), 12)
        self.assertEqual(P.ts_phase(b"not a transport stream at all" * 40), 0)
        pl = P.byterange_playlist("u", 12 + 188 * 4000, 12, first=12)
        ranges = [tuple(map(int, x.split(":")[1].split("@"))) for x in pl.splitlines() if x.startswith("#EXT-X-BYTERANGE")]
        self.assertEqual(ranges[0][1], 12)
        self.assertTrue(all((at - 12) % 188 == 0 for _, at in ranges))
        self.assertEqual(ranges[-1][0] + ranges[-1][1], 12 + 188 * 4000)

    def test_tiny_and_odd_inputs(self):
        pl = P.byterange_playlist("u", 188 * 10, 2)
        self.assertEqual(pl.count("#EXTINF"), 1)
        self.assertIn("#EXT-X-BYTERANGE:1880@0", pl)


class SealedSegments(unittest.TestCase):
    def test_seal_hides_the_login_and_round_trips(self):
        with tempfile.TemporaryDirectory() as d:
            os.environ["TVBRO_DATA"] = d
            from tvbro_server import app as A
            A.DATA_DIR = d
            a = A.App()
            seg = "http://tv.example:8080/hls/alice/s3cret/101_55.ts"
            tok = a.seal(seg)
            self.assertNotIn("alice", tok)
            self.assertNotIn("s3cret", tok)
            import base64
            raw = base64.urlsafe_b64decode(tok + "=" * (-len(tok) % 4))
            self.assertNotIn(b"s3cret", raw)
            self.assertEqual(a.seal(seg), tok)                 # deterministic: stable across reloads
            self.assertEqual(a.unseal(tok), seg)
            self.assertIsNone(a.unseal(tok[:-2] + ("AA" if not tok.endswith("AA") else "BB")))
            self.assertIsNone(a.unseal("nonsense"))
            a.state.db.close()
            a.orbit.close()

    def test_seal_and_facade_keys_come_from_the_key_outside_the_data_folder(self):
        """L2: sign.key (in the data folder) no longer opens sealed addresses or gives facade logins; the keys are
        derived from the encryption key (TVBRO_KEY_FILE), one per purpose."""
        import base64
        from unittest import mock
        from cryptography.fernet import Fernet
        from cryptography.hazmat.primitives.ciphers.aead import AESSIV
        from hashlib import sha512
        with tempfile.TemporaryDirectory() as d, tempfile.TemporaryDirectory() as sec:
            data = os.path.join(d, "data")
            os.makedirs(data)
            kf = os.path.join(sec, "orbit.key")
            with open(kf, "wb") as f:
                f.write(Fernet.generate_key())
            from tvbro_server import app as A
            from tvbro_server.secretstore import SecretStore
            with mock.patch.dict(os.environ, {"TVBRO_DATA": data, "TVBRO_KEY_FILE": kf, "TVBRO_KEY": ""}), \
                    mock.patch.object(A, "DATA_DIR", data):
                a = A.App()
                try:
                    st = SecretStore(data)
                    self.assertEqual(a.facade_key, st.derive(b"orbit/facade-device-secret/v1"))
                    self.assertEqual(a.facade.keys.key, a.facade_key)
                    self.assertNotEqual(a.facade_key, a.sign_key)
                    self.assertNotEqual(a.seal_key[:32], a.facade_key, "one key per purpose")
                    seg = "http://tv.example:8080/live/alice/s3cret/101.ts"
                    old_tok = base64.urlsafe_b64encode(AESSIV(sha512(b"tvbro-seg" + a.sign_key).digest())
                                                       .encrypt(seg.encode(), None)).decode().rstrip("=")
                    self.assertNotEqual(a.seal(seg), old_tok, "sign.key alone no longer makes sealed addresses")
                    self.assertEqual(a.unseal(old_tok), seg, "during the upgrade grace an old address still works")
                    a.legacy_until = 0
                    self.assertIsNone(a.unseal(old_tok), "after it, a token forged with sign.key is refused")
                    self.assertEqual(a.unseal(a.seal(seg)), seg)
                    # the grace starts once, at the first start with derived keys
                    with open(os.path.join(data, "keys-derived.json"), encoding="utf-8") as f:
                        self.assertIn("since", f.read())
                    with open(kf, "wb") as f:                  # another encryption key: other derived keys
                        f.write(Fernet.generate_key())
                    self.assertNotEqual(SecretStore(data).derive(b"orbit/facade-device-secret/v1"), a.facade_key)
                finally:
                    a.state.db.close()
                    a.orbit.close()
            a.orbit.close()                                    # Orbit: orbit.db is open too (Windows cannot delete open files)


if __name__ == "__main__":
    unittest.main()
