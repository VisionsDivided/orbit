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
from .media import MediaServers, Raw

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
    query: dict = field(default_factory=dict)   # the query string of a GET, first value of each name
    origin: str = ""                     # "http://<Host>" as the client addressed this server (facade addresses)


@dataclass
class Reply:
    obj: dict
    code: int = 200
    session: Optional[str] = None        # a new session id to set as the cookie; "" clears it
    headers: dict = field(default_factory=dict)
    raw: Optional[bytes] = None          # a non-JSON body (media artwork) sent with ``ctype`` instead of ``obj``
    ctype: str = ""


class Api:
    def __init__(self, accounts: Accounts, pairings: Optional[Pairings] = None,
                 playlists: Optional[Callable[[], list]] = None,
                 stores: Optional[Callable[[dict], tuple]] = None,
                 streams: Optional[Callable[[], dict]] = None, media: Optional[MediaServers] = None,
                 device_view: Optional[Callable[[list, Call], list]] = None):
        self.acc = accounts
        self.streams = streams or (lambda: {"max": 1, "count": 0, "active": []})   # tvBro's StreamRegistry.status()
        self.stores = stores                              # (StateStore, SeriesStore) of a session's profile
        self.pair = pairings or Pairings(accounts)
        self.playlists = playlists or (lambda: [])      # the household's enabled playlists, with their logins
        # orbit/facade.py: what a linked TV gets instead of the logins (None: the logins themselves)
        self.device_view = device_view
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
            ("POST", "/v1/device/name"): lambda c: Reply(A.set_device_name(c.sess, c.body.get("name"))),
            ("GET", "/v1/devices"): lambda c: Reply({"devices": A.devices(c.sess)}),
            ("POST", "/v1/devices/rename"): lambda c: Reply(A.rename_device(c.sess, c.body.get("id"), c.body.get("name"))),
            ("POST", "/v1/devices/unlink"): self._unlink_device,
            ("GET", "/v1/streams"): lambda c: Reply(self.streams()),
            ("GET", "/v1/progress/shows"): lambda c: Reply(PROG.shows_state(self._series(c))),
            ("POST", "/v1/progress/episodes"): lambda c: Reply(PROG.push_episodes(self._series(c), c.body.get("items"))),
            ("POST", "/v1/pair/start"): lambda c: Reply(PR.start(c.body.get("device"), c.client)),
            ("POST", "/v1/pair/poll"): lambda c: Reply(PR.poll(c.body.get("pairId"))),
            ("POST", "/v1/pair/peek"): lambda c: Reply(PR.peek(c.sess, c.body.get("code"), c.client)),
            ("POST", "/v1/auth/password"): self._change_password,
            ("POST", "/v1/accounts/password"): lambda c: Reply(A.reset_password(c.sess, c.body.get("id"), c.body.get("password"))),
            ("POST", "/v1/pair/approve"): lambda c: Reply(PR.approve(c.sess, c.body.get("code"), c.body.get("profileId"),
                                                                    c.client)),
        }
        if media is not None:                             # Plex / Jellyfin (orbit/media.py)
            for key, fn in media.routes().items():
                r[key] = lambda c, fn=fn: self._media(fn(c.sess, c.body, c.query))
        self.media = media
        self.routes = r

    def handle(self, call: Call) -> Reply:
        self.pair.tick()
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
        """Linked TVs only (never a browser): the household's playlists.

        Every linked TV gets them, whatever its account's role (the owner wants every household TV to watch IPTV).
        With hidden provider logins (``device_view``, orbit/facade.py; the default) they point at this server's
        Xtream facade with a login of the TV's own. Without it they carry the provider logins, and any account can
        read those by linking a "TV" (or a script) with its own approval. Browser sessions never get them."""
        if c.sess.get("session_kind") != "tv":
            return Reply({"error": "only a linked TV can fetch the playlists"}, 403)
        pl = self.playlists()
        if self.device_view is not None:
            pl = self.device_view(pl, c)
        return Reply({"playlists": pl})

    @staticmethod
    def _media(out) -> Reply:
        if isinstance(out, Raw):
            return Reply({}, raw=out.body, ctype=out.ctype, headers=out.headers)
        return Reply(out)

    def _change_password(self, c: Call) -> Reply:
        self.acc.change_password(c.sess, c.body.get("current"), c.body.get("password"))
        return Reply({"ok": True})

    def _unlink_device(self, c: Call) -> Reply:
        self.acc.unlink_device(c.sess, c.body.get("id"))
        return Reply({"ok": True})

    def _revoke(self, c: Call) -> Reply:
        self.acc.revoke(c.sess, c.body.get("id"))
        return Reply({"ok": True})
