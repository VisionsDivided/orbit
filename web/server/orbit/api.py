# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""The /v1 API (docs/orbit/API-v1.md): a route table instead of if/elif chains.

The HTTP handler in tvbro_server/app.py does the Host / Origin / CSRF checks and reads the
session first (as for every /api route), then calls ``handle``. Handlers get a ``Call`` and
return a ``Reply``; they never see the socket."""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Callable, Optional

from .accounts import Accounts, ApiError
from .pairing import Pairings
from . import progress as PROG

# paths that work without a session (setup / sign-in / TV pairing send X-TvBro: 1 instead of a CSRF token)
PUBLIC_GET = {"/v1/status"}
PUBLIC_POST = {"/v1/setup", "/v1/auth/login", "/v1/pair/start", "/v1/pair/poll"}


@dataclass
class Call:
    method: str
    path: str
    sess: Optional[dict]                 # the gated session (account_id, profile_id, ...) or None
    body: dict = field(default_factory=dict)
    client: str = ""                     # client address, for sign-in throttling


@dataclass
class Reply:
    obj: dict
    code: int = 200
    session: Optional[str] = None        # a new session id to set as the cookie; "" clears it
    headers: dict = field(default_factory=dict)


class Api:
    def __init__(self, accounts: Accounts, pairings: Optional[Pairings] = None,
                 playlists: Optional[Callable[[], list]] = None,
                 stores: Optional[Callable[[dict], tuple]] = None):
        self.acc = accounts
        self.stores = stores                              # (StateStore, SeriesStore) of a session's profile
        self.pair = pairings or Pairings(accounts)
        self.playlists = playlists or (lambda: [])      # the household's enabled playlists, with their logins
        A, PR = self.acc, self.pair
        r: dict[tuple[str, str], Callable[[Call], Reply]] = {
            ("GET", "/v1/status"): lambda c: Reply({"accounts": A.enabled(), "setup": not A.enabled()}),
            ("POST", "/v1/setup"): self._setup,
            ("POST", "/v1/auth/login"): self._login,
            ("POST", "/v1/auth/logout"): self._logout,
            ("GET", "/v1/me"): lambda c: Reply(A.me(c.sess)),
            ("GET", "/v1/profiles"): lambda c: Reply({"profiles": A.profiles(c.sess["account_id"])}),
            ("POST", "/v1/profiles"): lambda c: Reply(A.add_profile(c.sess["account_id"], c.body.get("name"),
                                                                   c.body.get("kids"))),
            ("POST", "/v1/profiles/select"): lambda c: Reply(A.me(A.select_profile(c.sess, c.body.get("id")))),
            ("POST", "/v1/profiles/delete"): self._delete_profile,
            ("GET", "/v1/sessions"): lambda c: Reply({"sessions": A.sessions(c.sess)}),
            ("POST", "/v1/sessions/revoke"): self._revoke,
            ("GET", "/v1/accounts"): lambda c: Reply({"accounts": A.accounts(c.sess)}),
            ("POST", "/v1/accounts"): lambda c: Reply(A.create_account(
                c.sess, c.body.get("username"), c.body.get("password"), c.body.get("displayName"), c.body.get("role"))),
            ("POST", "/v1/accounts/disable"): lambda c: Reply(A.set_disabled(c.sess, c.body.get("id"),
                                                                            c.body.get("disabled"))),
            ("GET", "/v1/device/playlists"): self._device_playlists,
            ("GET", "/v1/progress/shows"): lambda c: Reply(PROG.shows_state(self._series(c))),
            ("POST", "/v1/progress/episodes"): lambda c: Reply(PROG.push_episodes(self._series(c), c.body.get("items"))),
            ("POST", "/v1/pair/start"): lambda c: Reply(PR.start(c.body.get("device"), c.client)),
            ("POST", "/v1/pair/poll"): lambda c: Reply(PR.poll(c.body.get("pairId"))),
            ("POST", "/v1/pair/approve"): lambda c: Reply(PR.approve(c.sess, c.body.get("code"), c.body.get("profileId"),
                                                                    c.client)),
        }
        self.routes = r

    def handle(self, call: Call) -> Reply:
        fn = self.routes.get((call.method, call.path))
        if fn is None:
            return Reply({"error": "not found"}, 404)
        public = call.path in (PUBLIC_GET if call.method == "GET" else PUBLIC_POST)
        if not public and not (call.sess and call.sess.get("account_id")):
            return Reply({"error": "sign in", "auth": "login"}, 401)
        try:
            return fn(call)
        except ApiError as e:
            hdr = {"Retry-After": str(e.retry_after)} if e.retry_after else {}
            return Reply({"error": e.msg}, e.code, headers=hdr)

    # ---- handlers with more than one step ----
    def _signed_in(self, sid: str) -> Reply:
        sess = self.acc.gate(self.acc.auth.session(sid))
        return Reply(self.acc.me(sess), session=sid)

    def _setup(self, c: Call) -> Reply:
        b = c.body
        sid = self.acc.setup(b.get("token"), b.get("username"), b.get("password"), b.get("displayName"),
                             b.get("device") or "")
        return self._signed_in(sid)

    def _login(self, c: Call) -> Reply:
        if not self.acc.enabled():
            return Reply({"error": "no accounts yet: create the owner with /v1/setup"}, 409)
        b = c.body
        sid = self.acc.login(b.get("username"), b.get("password"), c.client, b.get("device") or "")
        return self._signed_in(sid)

    def _logout(self, c: Call) -> Reply:
        self.acc.logout(c.sess)
        return Reply({"ok": True}, session="")

    def _delete_profile(self, c: Call) -> Reply:
        self.acc.delete_profile(c.sess["account_id"], c.body.get("id"))
        return Reply({"ok": True})

    def _series(self, c: Call):
        if self.stores is None:
            raise ApiError(503, "show progress is not available")
        try:
            return self.stores(c.sess)[1]
        except PermissionError:
            raise ApiError(409, "no profile selected")

    def _device_playlists(self, c: Call) -> Reply:
        """Linked TVs only (never a browser): the playlists to play directly from the provider."""
        if c.sess.get("session_kind") != "tv":
            return Reply({"error": "only a linked TV can fetch the playlists"}, 403)
        return Reply({"playlists": self.playlists()})

    def _revoke(self, c: Call) -> Reply:
        self.acc.revoke(c.sess, c.body.get("id"))
        return Reply({"ok": True})
