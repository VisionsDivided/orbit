# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""Link a TV (or any device without a keyboard) to an account with a short code.

1. The TV asks for a pairing: it gets a long secret ``pairId`` (only the TV knows it) and a short
   ``code`` it shows on screen, with a QR code of the approval page.
2. A person who is signed in on a phone or computer approves the code for their own account and
   one of their profiles.
3. The TV, polling with its ``pairId``, receives its own account session once (then the pairing
   is gone). It signs in like any other device and appears in "Your devices".

Pairings live in memory for 10 minutes. A session approved but never collected is ended."""
from __future__ import annotations

import hashlib
import secrets
import threading
import time
from typing import Callable

from tvbro_server.auth import Throttle, Wait

from .accounts import Accounts, ApiError

ALPHABET = "BCDFGHJKLMNPQRSTVWXZ23456789"   # no vowels (no words), no 0/O or 1/I look-alikes
CODE_LEN = 8                                # 28^8 ≈ 3.8e11 codes
TTL = 600
POLL_EVERY = 3
MAX_PER_CLIENT = 5
MAX_PENDING = 50


def _key(pair_id: str) -> str:
    return hashlib.sha256(pair_id.encode()).hexdigest()


def normalize(code) -> str:
    return "".join(c for c in str(code or "").upper() if c in ALPHABET)[:CODE_LEN]


def pretty(code: str) -> str:
    return code[:4] + "-" + code[4:]


class Pairings:
    def __init__(self, accounts: Accounts, clock: Callable[[], float] = time.time):
        self.acc = accounts
        self.clock = clock
        self.throttle = Throttle(clock)       # wrong codes, per client address
        self._lock = threading.Lock()
        self._p: dict[str, dict] = {}         # sha256(pairId) -> {code, device, client, exp, sid?}

    def _prune(self):
        now = self.clock()
        for k, p in list(self._p.items()):
            if p["exp"] <= now:
                if p.get("sid"):              # approved but never collected: end that session
                    self.acc.auth.logout(self.acc.auth._h(p["sid"]))
                self._p.pop(k, None)

    def start(self, device, client: str) -> dict:
        if not self.acc.enabled():
            raise ApiError(409, "this server has no accounts yet")
        dev = device.strip()[:60] if isinstance(device, str) and device.strip() else "TV"
        with self._lock:
            self._prune()
            if len(self._p) >= MAX_PENDING or sum(1 for p in self._p.values() if p["client"] == client) >= MAX_PER_CLIENT:
                raise ApiError(429, "too many pairings at once; try again in a few minutes", 60)
            codes = {p["code"] for p in self._p.values()}
            code = "".join(secrets.choice(ALPHABET) for _ in range(CODE_LEN))
            while code in codes:
                code = "".join(secrets.choice(ALPHABET) for _ in range(CODE_LEN))
            pair_id = secrets.token_urlsafe(32)
            exp = self.clock() + TTL
            self._p[_key(pair_id)] = {"code": code, "device": dev, "client": client, "exp": exp}
        return {"pairId": pair_id, "code": pretty(code), "expires": int(exp), "interval": POLL_EVERY}

    def tick(self):
        """Called on every API request: expired pairings (and sessions approved but never collected) end on time."""
        if self._p:
            with self._lock:
                self._prune()

    def peek(self, sess: dict, code, client: str) -> dict:
        """What is waiting behind a code (device name, address, age), so the person approving sees which TV it is
        before it gets their account. Wrong codes count like wrong approvals."""
        try:
            self.throttle.check(client)
        except Wait as e:
            raise ApiError(429, "too many wrong codes", e.retry_after)
        want = normalize(code)
        with self._lock:
            self._prune()
            hit = next((p for p in self._p.values() if p["code"] == want and not p.get("sid")), None) if len(want) == CODE_LEN else None
        if hit is None:
            self.throttle.fail(client)
            raise ApiError(404, "no TV is waiting with that code (it may have expired)")
        return {"device": hit["device"], "address": hit["client"], "waitingSecs": int(self.clock() - (hit["exp"] - TTL))}

    def poll(self, pair_id) -> dict:
        """{"status": "pending"} or {"status": "approved", "session", "csrf", "me"} (once)."""
        if not isinstance(pair_id, str) or not pair_id:
            raise ApiError(404, "no such pairing (it may have expired)")
        with self._lock:
            self._prune()
            k = _key(pair_id)
            p = self._p.get(k)
            if p is None:
                raise ApiError(404, "no such pairing (it may have expired)")
            if not p.get("sid"):
                return {"status": "pending", "expires": int(p["exp"])}
            self._p.pop(k, None)
        sid = p["sid"]
        sess = self.acc.gate(self.acc.auth.session(sid))
        if sess is None:                      # revoked or account disabled in between
            raise ApiError(404, "the pairing was cancelled")
        return {"status": "approved", "session": sid, "csrf": self.acc.auth.csrf(sess["h"]), "me": self.acc.me(sess)}

    def approve(self, sess: dict, code, profile_id, client: str) -> dict:
        try:
            self.throttle.check(client)
        except Wait as e:
            raise ApiError(429, "too many wrong codes", e.retry_after)
        want = normalize(code)
        pid = profile_id or sess.get("profile_id")
        self.acc._own_profile(sess["account_id"], pid)          # 404 for anyone else's profile
        with self._lock:
            self._prune()
            hit = next((p for p in self._p.values() if p["code"] == want and not p.get("sid")), None) if len(want) == CODE_LEN else None
            if hit is None:
                self.throttle.fail(client)
                raise ApiError(404, "no TV is waiting with that code (it may have expired)")
            self.throttle.ok(client)
            acc = self.acc.account(sess["account_id"])
            hit["sid"] = self.acc._issue(acc, hit["device"], pid, kind="tv")
            device = hit["device"]
        return {"ok": True, "device": device}
