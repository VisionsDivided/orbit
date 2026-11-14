# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""Accounts, profiles and account sessions (docs/orbit/API-v1.md).

An Orbit sign-in creates an ordinary server session (tvBro's auth.py: cookie, CSRF, signed media
URLs, expiry all unchanged) and links it to an account and profile in ``account_sessions``. Once
the first account exists ("Orbit mode"), a session without that link counts as signed out.

Every method that reads or changes per-person data takes the caller's account id and filters by
it; another account's ids are answered as "not found" (404), so they cannot be probed."""
from __future__ import annotations

import hmac
import logging
import os
import re
import secrets
import time
from typing import Callable, Optional

from tvbro_server.auth import (MAX_LEN, Throttle, Wait, check_new_password, hash_password,  # noqa: F401
                               verify_password)
from tvbro_server.secfile import write_secret

from .db import Database

LOG = logging.getLogger("orbit.accounts")

USERNAME = re.compile(r"^[A-Za-z0-9._-]{3,32}$")
MAX_PROFILES = 8
SEEN_EVERY = 300                       # write last_seen at most every 5 minutes per session
# account role -> the server session role that sets what the existing /api routes allow
SESSION_ROLE = {"owner": "admin", "admin": "admin", "member": "viewer"}


class ApiError(Exception):
    def __init__(self, code: int, msg: str, retry_after: int = 0):
        super().__init__(msg)
        self.code, self.msg, self.retry_after = code, msg, retry_after


def _new_id() -> str:
    return secrets.token_hex(8)


def _name(v, field: str, longest: int) -> str:
    s = v.strip() if isinstance(v, str) else ""
    if not 1 <= len(s) <= longest or any(ord(c) < 32 for c in s):
        raise ApiError(400, f"{field}: 1 to {longest} characters")
    return s


def _password(v) -> str:
    try:
        return check_new_password(v)
    except ValueError as e:
        raise ApiError(400, str(e))


class Accounts:
    def __init__(self, data_dir: str, auth, clock: Callable[[], float] = time.time):
        self.auth = auth
        self.clock = clock
        self.db = Database(os.path.join(data_dir, "orbit.db"))
        self.token_path = os.path.join(data_dir, "orbit-setup-token")
        self.throttle = Throttle(clock)
        # set by the app (orbit/profilestate.py): the owner's first profile at setup, a deleted profile
        self.on_setup: Callable[[str], None] = lambda pid: None
        self.on_profile_deleted: Callable[[str], None] = lambda pid: None
        self._dummy = hash_password(secrets.token_hex(16))     # unknown usernames cost the same time
        self._enabled = self.db.one("SELECT 1 FROM accounts LIMIT 1") is not None
        if self._enabled:
            self._remove_token()
        else:
            self._ensure_token()

    def close(self):
        self.db.close()

    # ---- mode and first owner ----
    def enabled(self) -> bool:
        """Orbit mode: at least one account exists."""
        return self._enabled

    def _ensure_token(self):
        if not os.path.exists(self.token_path):
            write_secret(self.token_path, secrets.token_urlsafe(24).encode())
        LOG.warning("Orbit has no accounts yet: create the owner with the one-time setup token in %s", self.token_path)

    def _remove_token(self):
        try:
            os.remove(self.token_path)
        except OSError:
            pass

    def setup(self, token, username, password, display_name=None, device: str = "") -> str:
        """Create the owner (only while no account exists). Returns the new session id."""
        with self.db.lock:                    # two setups at once must not both create an owner
            if self._enabled:
                raise ApiError(409, "Orbit is already set up")
            try:
                with open(self.token_path, encoding="utf-8") as f:
                    want = f.read().strip()
            except OSError:
                raise ApiError(403, "setup token missing on the server")
            if not isinstance(token, str) or not want or not hmac.compare_digest(token.strip(), want):
                raise ApiError(403, "wrong setup token")
            acc = self._create(username, password, display_name, "owner")
            self.on_setup(self.profiles(acc["id"])[0]["id"])   # the household's data so far goes to the owner
            self._enabled = True
            self._remove_token()
            # from now on every device must sign in; tvBro's shared password is replaced by an unknown one
            self.auth.set_required(True, secrets.token_urlsafe(32))
        LOG.warning("Orbit owner account created; signed-out sessions from before have ended")
        return self._issue(acc, device)

    # ---- sign in / out ----
    def login(self, username, password, client: str, device: str = "") -> str:
        try:
            self.throttle.check(client)
        except Wait as e:
            raise ApiError(429, "too many wrong passwords", e.retry_after)
        key = username.strip().lower() if isinstance(username, str) else ""
        row = self.db.one("SELECT id, username, display_name, role, pw_hash, disabled, created FROM accounts "
                          "WHERE username_key=?", (key,)) if key else None
        pw = password if isinstance(password, str) and len(password) <= MAX_LEN else ""
        ok = verify_password(pw, row[4] if row else self._dummy) and row is not None and not row[5]
        if not ok:
            self.throttle.fail(client)
            raise ApiError(403, "wrong username or password")
        self.throttle.ok(client)
        self._prune()
        return self._issue(self._account(row), device)

    def _issue(self, acc: dict, device: str, profile_id: Optional[str] = None, kind: str = "web") -> str:
        """A new account session (profile_id: one of the account's profiles, else its first;
        kind: 'web' for a browser sign-in, 'tv' for a device linked with a pairing code)."""
        sid = self.auth.issue_session(SESSION_ROLE[acc["role"]])
        h = self.auth._h(sid)
        first = (profile_id,) if profile_id else \
            self.db.one("SELECT id FROM profiles WHERE account_id=? ORDER BY created, id LIMIT 1", (acc["id"],))
        now = int(self.clock())
        dev = device.strip()[:60] if isinstance(device, str) else ""
        self.db.run("INSERT INTO account_sessions(sess_hash, id, account_id, profile_id, device, created, last_seen, kind) "
                    "VALUES (?,?,?,?,?,?,?,?)", (h, _new_id(), acc["id"], first[0] if first else None, dev, now, now,
                                                 "tv" if kind == "tv" else "web"))
        return sid

    def logout(self, sess: dict):
        self.db.run("DELETE FROM account_sessions WHERE sess_hash=?", (sess["h"],))
        self.auth.logout(sess["h"])

    def _prune(self):
        """Forget links to sessions the server no longer has (expired or evicted)."""
        live = set(self.auth.sessions)
        for (h,) in self.db.q("SELECT sess_hash FROM account_sessions"):
            if h not in live:
                self.db.run("DELETE FROM account_sessions WHERE sess_hash=?", (h,))

    # ---- the gate every request passes ----
    def gate(self, sess: Optional[dict]) -> Optional[dict]:
        """tvBro mode: the session as it is. Orbit mode: the session with ``account_id``,
        ``profile_id`` and ``account_role`` added, or None when it belongs to no (enabled) account."""
        if not self._enabled or not sess:
            return sess
        row = self.db.one("SELECT s.account_id, s.profile_id, s.last_seen, a.role, a.disabled, s.kind FROM account_sessions s "
                          "JOIN accounts a ON a.id = s.account_id WHERE s.sess_hash=?", (sess["h"],))
        if row is None:
            return None
        if row[4]:
            self.logout(sess)
            return None
        now = int(self.clock())
        pid = row[1]
        if pid is None:                        # its profile was deleted: use the account's first one
            first = self.db.one("SELECT id FROM profiles WHERE account_id=? ORDER BY created, id LIMIT 1", (row[0],))
            pid = first[0] if first else None
            self.db.run("UPDATE account_sessions SET profile_id=? WHERE sess_hash=?", (pid, sess["h"]))
        if now - row[2] > SEEN_EVERY:
            self.db.run("UPDATE account_sessions SET last_seen=? WHERE sess_hash=?", (now, sess["h"]))
        return {**sess, "account_id": row[0], "profile_id": pid, "account_role": row[3], "session_kind": row[5]}

    # ---- me ----
    def _account(self, row) -> dict:
        return {"id": row[0], "username": row[1], "displayName": row[2], "role": row[3],
                "disabled": bool(row[5]), "created": row[6]}

    def account(self, account_id: str) -> dict:
        row = self.db.one("SELECT id, username, display_name, role, pw_hash, disabled, created FROM accounts WHERE id=?",
                          (account_id,))
        if row is None:
            raise ApiError(404, "no such account")
        return self._account(row)

    def me(self, sess: dict) -> dict:
        profiles = self.profiles(sess["account_id"])
        cur = next((p for p in profiles if p["id"] == sess.get("profile_id")), None)
        return {"account": self.account(sess["account_id"]), "profiles": profiles, "profile": cur,
                "csrf": self.auth.csrf(sess["h"])}

    # ---- profiles (always the caller's own account) ----
    @staticmethod
    def _profile(row) -> dict:
        return {"id": row[0], "name": row[1], "kids": bool(row[2]), "created": row[3]}

    def profiles(self, account_id: str) -> list[dict]:
        return [self._profile(r) for r in self.db.q(
            "SELECT id, name, kids, created FROM profiles WHERE account_id=? ORDER BY created, id", (account_id,))]

    def add_profile(self, account_id: str, name, kids=False) -> dict:
        name = _name(name, "name", 40)
        with self.db.lock:
            n = self.db.one("SELECT COUNT(*) FROM profiles WHERE account_id=?", (account_id,))[0]
            if n >= MAX_PROFILES:
                raise ApiError(409, f"at most {MAX_PROFILES} profiles per account")
            pid, now = _new_id(), int(self.clock())
            self.db.run("INSERT INTO profiles(id, account_id, name, kids, created) VALUES (?,?,?,?,?)",
                        (pid, account_id, name, 1 if kids is True else 0, now))
        return {"id": pid, "name": name, "kids": kids is True, "created": now}

    def _own_profile(self, account_id: str, pid) -> None:
        if not isinstance(pid, str) or self.db.one("SELECT 1 FROM profiles WHERE id=? AND account_id=?",
                                                   (pid, account_id)) is None:
            raise ApiError(404, "no such profile")

    def select_profile(self, sess: dict, pid) -> dict:
        self._own_profile(sess["account_id"], pid)
        self.db.run("UPDATE account_sessions SET profile_id=? WHERE sess_hash=? AND account_id=?",
                    (pid, sess["h"], sess["account_id"]))
        return {**sess, "profile_id": pid}

    def delete_profile(self, account_id: str, pid):
        with self.db.lock:
            self._own_profile(account_id, pid)
            if self.db.one("SELECT COUNT(*) FROM profiles WHERE account_id=?", (account_id,))[0] <= 1:
                raise ApiError(409, "an account keeps at least one profile")
            self.db.run("DELETE FROM profiles WHERE id=? AND account_id=?", (pid, account_id))
        self.on_profile_deleted(pid)

    # ---- sessions (own account) ----
    def sessions(self, sess: dict) -> list[dict]:
        rows = self.db.q("SELECT id, device, created, last_seen, sess_hash FROM account_sessions WHERE account_id=? "
                         "ORDER BY last_seen DESC", (sess["account_id"],))
        return [{"id": r[0], "device": r[1], "created": r[2], "lastSeen": r[3], "current": r[4] == sess["h"]}
                for r in rows]

    def revoke(self, sess: dict, sid_pub) -> None:
        row = self.db.one("SELECT sess_hash FROM account_sessions WHERE id=? AND account_id=?",
                          (sid_pub if isinstance(sid_pub, str) else "", sess["account_id"]))
        if row is None:
            raise ApiError(404, "no such session")
        self.db.run("DELETE FROM account_sessions WHERE sess_hash=?", (row[0],))
        self.auth.logout(row[0])

    # ---- accounts (owner / admin) ----
    def _manager(self, sess: dict):
        if sess.get("account_role") not in ("owner", "admin"):
            raise ApiError(403, "owner or admin only")

    def accounts(self, sess: dict) -> list[dict]:
        self._manager(sess)
        return [self._account(r) for r in self.db.q(
            "SELECT id, username, display_name, role, pw_hash, disabled, created FROM accounts ORDER BY created, id")]

    def create_account(self, sess: dict, username, password, display_name=None, role="member") -> dict:
        self._manager(sess)
        role = role or "member"
        if role not in ("member", "admin"):
            raise ApiError(400, "role: member or admin")
        if role == "admin" and sess.get("account_role") != "owner":
            raise ApiError(403, "only the owner can create admins")
        return self._create(username, password, display_name, role)

    def _create(self, username, password, display_name, role: str) -> dict:
        if not isinstance(username, str) or not USERNAME.match(username):
            raise ApiError(400, "username: 3 to 32 letters, digits, '.', '_' or '-'")
        pw = _password(password)
        disp = _name(display_name if display_name else username, "displayName", 60)
        aid, now = _new_id(), int(self.clock())
        h = hash_password(pw)
        with self.db.lock:
            if self.db.one("SELECT 1 FROM accounts WHERE username_key=?", (username.lower(),)):
                raise ApiError(409, "that username is taken")
            self.db.run("INSERT INTO accounts(id, username, username_key, display_name, role, pw_hash, disabled, created) "
                        "VALUES (?,?,?,?,?,?,0,?)", (aid, username, username.lower(), disp, role, h, now))
            self.db.run("INSERT INTO profiles(id, account_id, name, kids, created) VALUES (?,?,?,0,?)",
                        (_new_id(), aid, disp[:40], now))
        return {"id": aid, "username": username, "displayName": disp, "role": role, "disabled": False, "created": now}

    def set_disabled(self, sess: dict, account_id, disabled) -> dict:
        self._manager(sess)
        target = self.account(account_id if isinstance(account_id, str) else "")
        if target["role"] == "owner":
            raise ApiError(403, "the owner cannot be disabled")
        if target["id"] == sess["account_id"]:
            raise ApiError(403, "you cannot disable yourself")
        if target["role"] == "admin" and sess.get("account_role") != "owner":
            raise ApiError(403, "only the owner can disable admins")
        on = disabled is True
        self.db.run("UPDATE accounts SET disabled=? WHERE id=?", (1 if on else 0, target["id"]))
        if on:
            for (h,) in self.db.q("SELECT sess_hash FROM account_sessions WHERE account_id=?", (target["id"],)):
                self.db.run("DELETE FROM account_sessions WHERE sess_hash=?", (h,))
                self.auth.logout(h)
        return {**target, "disabled": on}
