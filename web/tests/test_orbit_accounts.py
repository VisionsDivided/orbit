# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""Orbit accounts and profiles (docs/orbit/API-v1.md).

Unit tests for the migration runner and the accounts store, then the real server (run.py) on a
free port with a temp data dir: first owner with the setup token, a second account, and every
endpoint checked as both accounts (nobody sees or changes the other's profiles or sessions)."""
import json
import os
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.error
import urllib.request
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "server"))
from orbit import db as ODB  # noqa: E402
from orbit.accounts import Accounts, ApiError  # noqa: E402
from tvbro_server import auth as AU  # noqa: E402


class Migrations(unittest.TestCase):
    def folder(self, files: dict) -> str:
        d = tempfile.mkdtemp()
        for name, sql in files.items():
            with open(os.path.join(d, name), "w", encoding="utf-8") as f:
                f.write(sql)
        return d

    def test_applies_in_order_and_keeps_a_copy_before_upgrading(self):
        d = tempfile.mkdtemp()
        m = self.folder({"001_a.sql": "CREATE TABLE a (x INTEGER);"})
        db = ODB.Database(os.path.join(d, "o.db"), m)
        self.assertEqual(db.version(), 1)
        db.run("INSERT INTO a VALUES (7)")
        db.db.close()
        with open(os.path.join(m, "002_b.sql"), "w", encoding="utf-8") as f:
            f.write("-- comment\nCREATE TABLE b (y TEXT);\nINSERT INTO b VALUES ('ok');")
        db2 = ODB.Database(os.path.join(d, "o.db"), m)
        self.assertEqual(db2.version(), 2)
        self.assertEqual(db2.one("SELECT x FROM a")[0], 7)
        self.assertTrue(os.path.exists(os.path.join(d, "o.db.bak-v1")))
        bak = sqlite3.connect(os.path.join(d, "o.db.bak-v1"))
        self.assertEqual(bak.execute("PRAGMA user_version").fetchone()[0], 1)

    def test_a_failed_migration_changes_nothing(self):
        d = tempfile.mkdtemp()
        m = self.folder({"001_a.sql": "CREATE TABLE a (x INTEGER); CREATE TABLE a (x INTEGER);"})
        with self.assertRaises(sqlite3.OperationalError):
            ODB.Database(os.path.join(d, "o.db"), m)
        c = sqlite3.connect(os.path.join(d, "o.db"))
        self.assertEqual(c.execute("PRAGMA user_version").fetchone()[0], 0)
        self.assertEqual(c.execute("SELECT COUNT(*) FROM sqlite_master WHERE name='a'").fetchone()[0], 0)

    def test_refuses_gaps_and_newer_databases(self):
        with self.assertRaises(RuntimeError):
            ODB.migrations(self.folder({"001_a.sql": "SELECT 1;", "003_c.sql": "SELECT 1;"}))
        d = tempfile.mkdtemp()
        c = sqlite3.connect(os.path.join(d, "o.db"))
        c.execute("PRAGMA user_version = 5")
        c.close()
        with self.assertRaises(ODB.TooNew):
            ODB.Database(os.path.join(d, "o.db"), self.folder({"001_a.sql": "SELECT 1;"}))

    def test_upgrading_from_the_first_version(self):
        d = tempfile.mkdtemp()
        only1 = self.folder({"001_accounts.sql": open(os.path.join(ODB.MIGRATIONS, "001_accounts.sql"), encoding="utf-8").read()})
        old = ODB.Database(os.path.join(d, "orbit.db"), only1)
        old.run("INSERT INTO accounts VALUES ('a1','alex','alex','Alex','owner','h',0,1)")
        old.run("INSERT INTO account_sessions(sess_hash, id, account_id, device, created, last_seen) VALUES ('h1','s1','a1','',1,1)")
        old.close()
        new = ODB.Database(os.path.join(d, "orbit.db"))
        self.assertEqual(new.version(), len(ODB.migrations()))
        self.assertEqual(new.one("SELECT kind FROM account_sessions WHERE id='s1'")[0], "web", "old sessions count as browsers")
        self.assertTrue(os.path.exists(os.path.join(d, "orbit.db.bak-v1")))

    def test_the_real_migrations_apply(self):
        db = ODB.Database(os.path.join(tempfile.mkdtemp(), "orbit.db"))
        names = {r[0] for r in db.q("SELECT name FROM sqlite_master WHERE type='table'")}
        self.assertTrue({"accounts", "profiles", "account_sessions"} <= names)


@mock.patch("tvbro_server.auth.ITERATIONS", 1000)       # fast hashes for the unit tests only
class Store(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()
        self.auth = AU.Auth(self.d, b"k" * 32)
        self.acc = Accounts(self.d, self.auth)

    def token(self):
        with open(os.path.join(self.d, "orbit-setup-token"), encoding="utf-8") as f:
            return f.read()

    def owner(self):
        sid = self.acc.setup(self.token(), "alex", "correct horse 1", "Alex")
        return self.acc.gate(self.auth.session(sid))

    def test_tvbro_mode_until_the_first_account(self):
        self.assertFalse(self.acc.enabled())
        open_sid = self.auth.open_session()
        s = self.auth.session(open_sid)
        self.assertIs(self.acc.gate(s), s)                  # sessions pass unchanged in tvBro mode
        with self.assertRaises(ApiError) as e:
            self.acc.setup("wrong", "alex", "correct horse 1")
        self.assertEqual(e.exception.code, 403)
        me = self.owner()
        self.assertTrue(self.acc.enabled())
        self.assertFalse(os.path.exists(os.path.join(self.d, "orbit-setup-token")), "token used up")
        self.assertIsNone(self.acc.gate(self.auth.session(open_sid)), "open sessions end at setup")
        self.assertEqual(me["account_role"], "owner")
        with self.assertRaises(ApiError) as e:
            self.acc.setup("anything", "eve", "correct horse 1")
        self.assertEqual(e.exception.code, 409)

    def test_login_wrong_and_unknown_look_the_same(self):
        self.owner()
        msgs = set()
        for user, pw in (("alex", "nope nope nope"), ("nobody", "nope nope nope")):
            with self.assertRaises(ApiError) as e:
                self.acc.login(user, pw, "192.0.2.10")
            msgs.add((e.exception.code, e.exception.msg))
        self.assertEqual(len(msgs), 1)
        sid = self.acc.login("ALEX", "correct horse 1", "192.0.2.11", "Living room TV")
        self.assertEqual(self.acc.gate(self.auth.session(sid))["account_role"], "owner")

    def test_two_accounts_cannot_touch_each_other(self):
        alex = self.owner()
        sam_acc = self.acc.create_account(alex, "sam", "another pass 2")
        sam = self.acc.gate(self.auth.session(self.acc.login("sam", "another pass 2", "192.0.2.12")))
        alex_profile = self.acc.profiles(alex["account_id"])[0]["id"]
        sam_profiles = self.acc.profiles(sam["account_id"])
        self.assertEqual([p["name"] for p in sam_profiles], ["sam"])
        self.assertNotIn(alex_profile, [p["id"] for p in sam_profiles])
        for fn in (lambda: self.acc.select_profile(sam, alex_profile),
                   lambda: self.acc.delete_profile(sam["account_id"], alex_profile),
                   lambda: self.acc.revoke(sam, self.acc.sessions(alex)[0]["id"])):
            with self.assertRaises(ApiError) as e:
                fn()
            self.assertEqual(e.exception.code, 404)
        self.assertEqual(len(self.acc.sessions(sam)), 1)
        with self.assertRaises(ApiError) as e:
            self.acc.accounts(sam)
        self.assertEqual(e.exception.code, 403)
        with self.assertRaises(ApiError):
            self.acc.create_account(sam, "mallory", "another pass 3")
        # disabling ends Sam's sessions at once
        self.acc.set_disabled(alex, sam_acc["id"], True)
        self.assertNotIn(sam["h"], self.auth.sessions)
        with self.assertRaises(ApiError):
            self.acc.login("sam", "another pass 2", "192.0.2.12")

    def test_role_rules(self):
        alex = self.owner()
        self.acc.create_account(alex, "ada", "admin pass 123", role="admin")
        ada = self.acc.gate(self.auth.session(self.acc.login("ada", "admin pass 123", "192.0.2.13")))
        with self.assertRaises(ApiError) as e:
            self.acc.create_account(ada, "bob", "bob pass 1234", role="admin")
        self.assertEqual(e.exception.code, 403)
        bob = self.acc.create_account(ada, "bob", "bob pass 1234")
        self.assertEqual(bob["role"], "member")
        for target in (alex["account_id"], ada["account_id"]):
            with self.assertRaises(ApiError):
                self.acc.set_disabled(ada, target, True)          # not the owner, not yourself
        with self.assertRaises(ApiError):
            self.acc.create_account(alex, "Bob", "bob pass 1234")  # usernames are case-insensitive
        with self.assertRaises(ApiError):
            self.acc.create_account(alex, "a b", "bob pass 1234")  # bad characters

    def test_profiles_limits(self):
        alex = self.owner()
        aid = alex["account_id"]
        for i in range(7):
            self.acc.add_profile(aid, f"P{i}", kids=(i == 0))
        with self.assertRaises(ApiError) as e:
            self.acc.add_profile(aid, "one too many")
        self.assertEqual(e.exception.code, 409)
        ps = self.acc.profiles(aid)
        for p in ps[1:]:
            self.acc.delete_profile(aid, p["id"])
        with self.assertRaises(ApiError):
            self.acc.delete_profile(aid, ps[0]["id"])              # the last one stays


def free_port() -> int:
    import socket
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


class Server(unittest.TestCase):
    """The real server: everything through HTTP, as two accounts."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp()
        cls.data = os.path.join(cls.tmp, "data")
        cls.port = free_port()
        env = dict(os.environ, TVBRO_PORT=str(cls.port), TVBRO_DATA=cls.data, PYTHONDONTWRITEBYTECODE="1")
        env.pop("TVBRO_KEY", None)
        cls.log = open(os.path.join(cls.tmp, "server.log"), "wb")
        cls.proc = subprocess.Popen([sys.executable, "run.py"], cwd=os.path.join(HERE, "..", "server"),
                                    env=env, stdout=cls.log, stderr=subprocess.STDOUT)
        cls.base = "http://127.0.0.1:%d" % cls.port
        for _ in range(150):
            try:
                cls.req("GET", "/api/health")
                break
            except Exception:                       # noqa: BLE001
                time.sleep(0.1)

    @classmethod
    def tearDownClass(cls):
        cls.proc.terminate()
        try:
            cls.proc.wait(10)
        except subprocess.TimeoutExpired:
            cls.proc.kill()
        cls.log.close()
        shutil.rmtree(cls.tmp, ignore_errors=True)

    @classmethod
    def req(cls, method, path, body=None, headers=None):
        h = {"Content-Type": "application/json"} if body is not None else {}
        h.update(headers or {})
        data = json.dumps(body).encode() if body is not None else None
        r = urllib.request.Request(cls.base + path, data=data, method=method, headers=h)
        try:
            with urllib.request.urlopen(r, timeout=60) as f:
                return f.status, dict(f.headers), json.loads(f.read() or b"{}")
        except urllib.error.HTTPError as e:
            return e.code, dict(e.headers), json.loads(e.read() or b"{}")

    @staticmethod
    def cookie(hdr) -> str:
        return hdr.get("Set-Cookie", "").split(";", 1)[0]

    def as_(self, who, method, path, body=None, csrf=True):
        cookie, token = who
        h = {"Cookie": cookie}
        if csrf:
            h["X-CSRF-Token"] = token
        return self.req(method, path, body, h)

    def sign_in(self, path, body):
        code, hdr, j = self.req("POST", path, body, {"X-TvBro": "1"})
        self.assertEqual(code, 200, j)
        return (self.cookie(hdr), j["csrf"]), j

    def test_flow(self):
        # tvBro mode: an open browser session works as before
        code, hdr, j = self.req("GET", "/api/session")
        self.assertEqual((code, j["auth"]), (200, "ok"))
        old = (self.cookie(hdr), j["csrf"])
        self.assertEqual(self.as_(old, "GET", "/api/state")[0], 200)
        self.assertEqual(self.req("GET", "/v1/status")[2], {"accounts": False, "setup": True})
        self.assertEqual(self.req("GET", "/v1/me")[0], 401)

        # setup: wrong token, no X-TvBro header, then the real one
        body = {"token": "wrong", "username": "alex", "password": "correct horse 1", "displayName": "Alex"}
        self.assertEqual(self.req("POST", "/v1/setup", body, {"X-TvBro": "1"})[0], 403)
        with open(os.path.join(self.data, "orbit-setup-token"), encoding="utf-8") as f:
            body["token"] = f.read()
        self.assertEqual(self.req("POST", "/v1/setup", body)[0], 403, "sign-in calls need X-TvBro")
        alex, me = self.sign_in("/v1/setup", body)
        self.assertEqual((me["account"]["role"], me["profile"]["name"]), ("owner", "Alex"))
        self.assertEqual(self.req("GET", "/v1/status")[2]["accounts"], True)
        self.assertEqual(self.req("POST", "/v1/setup", body, {"X-TvBro": "1"})[0], 409)

        # the browser session from before setup has ended; the shared-password login is gone
        self.assertEqual(self.as_(old, "GET", "/api/state")[0], 401)
        self.assertEqual(self.req("POST", "/api/auth/login", {"password": "x" * 9}, {"X-TvBro": "1"})[0], 410)
        self.assertEqual(self.as_(alex, "GET", "/api/state")[0], 200, "an account session uses the existing API")

        # CSRF, Origin and Host still apply to /v1
        self.assertEqual(self.as_(alex, "POST", "/v1/profiles", {"name": "Kids"}, csrf=False)[0], 403)
        c, h = alex
        self.assertEqual(self.req("POST", "/v1/profiles", {"name": "Kids"},
                                  {"Cookie": c, "X-CSRF-Token": h, "Origin": "http://evil.example.com"})[0], 403)
        self.assertEqual(self.req("GET", "/v1/me", headers={"Cookie": c, "Host": "evil.example.com"})[0], 421)

        # a second account
        code, _, sam_acc = self.as_(alex, "POST", "/v1/accounts", {"username": "sam", "password": "another pass 2"})
        self.assertEqual(code, 200, sam_acc)
        sam, sme = self.sign_in("/v1/auth/login", {"username": "sam", "password": "another pass 2", "device": "Phone"})
        self.assertEqual(sme["account"]["role"], "member")
        code, _, kids = self.as_(alex, "POST", "/v1/profiles", {"name": "Kids", "kids": True})
        self.assertEqual((code, kids["kids"]), (200, True))

        # isolation: Sam sees only Sam; Alex's ids are "not found" for Sam
        sam_profiles = self.as_(sam, "GET", "/v1/profiles")[2]["profiles"]
        self.assertEqual([p["name"] for p in sam_profiles], ["sam"])
        alex_sessions = self.as_(alex, "GET", "/v1/sessions")[2]["sessions"]
        self.assertEqual(len(alex_sessions), 1)
        self.assertEqual(self.as_(sam, "POST", "/v1/profiles/select", {"id": kids["id"]})[0], 404)
        self.assertEqual(self.as_(sam, "POST", "/v1/profiles/delete", {"id": kids["id"]})[0], 404)
        self.assertEqual(self.as_(sam, "POST", "/v1/sessions/revoke", {"id": alex_sessions[0]["id"]})[0], 404)
        self.assertEqual(self.as_(sam, "GET", "/v1/accounts")[0], 403)
        self.assertEqual(self.as_(sam, "POST", "/v1/accounts", {"username": "eve", "password": "eve pass 1234"})[0], 403)
        self.assertEqual(self.as_(sam, "POST", "/v1/accounts/disable", {"id": me["account"]["id"], "disabled": True})[0], 403)
        self.assertEqual(self.as_(sam, "POST", "/api/settings", {})[0], 403, "members get the viewer role")

        # Alex picks the Kids profile; it is part of /v1/me for that session only
        code, _, j = self.as_(alex, "POST", "/v1/profiles/select", {"id": kids["id"]})
        self.assertEqual((code, j["profile"]["id"]), (200, kids["id"]))
        self.assertEqual(self.as_(sam, "GET", "/v1/me")[2]["profile"]["name"], "sam")

        # revoking and disabling end sessions at once
        alex2, _ = self.sign_in("/v1/auth/login", {"username": "alex", "password": "correct horse 1", "device": "TV"})
        tv = next(s for s in self.as_(alex, "GET", "/v1/sessions")[2]["sessions"] if s["device"] == "TV")
        self.assertEqual(self.as_(alex, "POST", "/v1/sessions/revoke", {"id": tv["id"]})[0], 200)
        self.assertEqual(self.as_(alex2, "GET", "/v1/me")[0], 401)
        self.assertEqual(self.as_(alex, "POST", "/v1/accounts/disable", {"id": sam_acc["id"], "disabled": True})[0], 200)
        self.assertEqual(self.as_(sam, "GET", "/v1/me")[0], 401)
        self.assertEqual(self.req("POST", "/v1/auth/login", {"username": "sam", "password": "another pass 2"},
                                  {"X-TvBro": "1"})[0], 403)

        # logout
        code, hdr, _ = self.as_(alex, "POST", "/v1/auth/logout", {})
        self.assertEqual(code, 200)
        self.assertIn("Max-Age=0", hdr.get("Set-Cookie", ""))
        self.assertEqual(self.as_(alex, "GET", "/v1/me")[0], 401)

        # nothing secret in what the server keeps in its log
        self.log.flush()
        with open(os.path.join(self.tmp, "server.log"), encoding="utf-8", errors="replace") as f:
            text = f.read()
        for secret in ("correct horse 1", "another pass 2", body["token"]):
            self.assertNotIn(secret, text)


if __name__ == "__main__":
    unittest.main()
