# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""Plex and Jellyfin for the /v1 API (docs/orbit/API-v1.md, "Media servers").

Each account links its own media-server user (``media_connections`` in orbit.db, migration 003); every route
looks the connection up by id *and* the caller's account, so another account's connection answers 404.
Tokens are sealed with the server key in ``media.enc`` (TokenVault) and only leave the server in one place:
the direct-play URL of ``POST /v1/media/playback``, which goes to the same person's device. Artwork is fetched
by the server (``GET /v1/media/image``) so pages never see a token.

LAN servers: net.py refuses private addresses unless "local network sources" is on. ``private_hosts`` gives
net.set_private_hosts the exact host:port of every saved connection. While an owner or admin signs in, the
address being linked is allowed only for that sign-in's own requests (``net.private_hosts_scope``, per thread),
never in the global list, until it is saved. Members cannot add new LAN addresses (that would let anyone signed
in probe the network); they can link their own user on a server the household already uses."""
from __future__ import annotations

import json
import logging
import secrets
import threading
import time
import urllib.parse
from dataclasses import dataclass, field
from typing import Callable, Optional

from tvbro_server import net as NET
from tvbro_server.auth import Throttle, Wait
from tvbro_server.secfile import write_secret

from .accounts import ApiError
from .connectors import KINDS, ConnectorError, Linked
from .connectors.base import Http, host_port, normalize_base_url
from .mediaplay import Plays

LOG = logging.getLogger("orbit.media")

PENDING_TTL = 600                      # a sign-in waits at most 10 minutes
MAX_PENDING = 5                        # per account
POLL_EVERY = 5
OK_EVERY = 300                         # write last_ok at most every 5 minutes per connection
MANAGERS = ("owner", "admin")
COLS = "id, kind, base_url, server_name, server_id, user_id, user_name, device_id, created, last_ok"


@dataclass
class Raw:
    """A non-JSON answer (artwork)."""
    body: bytes
    ctype: str
    headers: dict = field(default_factory=dict)


class TokenVault:
    """{connection id: token}, sealed as one file with the server's Fernet key (secretstore). It is a ``*.enc``
    file in the data folder, so startup_check and rotate_key treat it like the provider logins."""

    def __init__(self, path: str, seal: Callable[[bytes], bytes], open_: Callable[[bytes], Optional[bytes]]):
        self.path, self._seal, self._open = path, seal, open_
        self._lock = threading.Lock()
        self._d: Optional[dict] = None

    def _load(self) -> dict:
        if self._d is None:
            try:
                with open(self.path, "rb") as f:
                    raw = self._open(f.read())
                self._d = json.loads(raw.decode()) if raw else {}
                if raw is None:
                    LOG.warning("media.enc does not open with the server key: Plex / Jellyfin links must be made again")
            except FileNotFoundError:
                self._d = {}
            except (OSError, ValueError):
                LOG.warning("media.enc is unreadable: Plex / Jellyfin links must be made again")
                self._d = {}
        return self._d

    def _save(self):
        write_secret(self.path, self._seal(json.dumps(self._d).encode()))

    def get(self, cid: str) -> Optional[str]:
        with self._lock:
            return self._load().get(cid)

    def put(self, cid: str, token: str):
        with self._lock:
            self._load()[cid] = token
            self._save()

    def drop(self, cids) -> None:
        with self._lock:
            d = self._load()
            if any(c in d for c in cids):
                for c in cids:
                    d.pop(c, None)
                self._save()

    def keep_only(self, cids: set) -> None:
        """Forget tokens of connections that no longer exist (e.g. a deleted account's)."""
        with self._lock:
            d = self._load()
            gone = [c for c in d if c not in cids]
            if gone:
                for c in gone:
                    d.pop(c, None)
                self._save()


def _str(v, name: str, longest: int = 300) -> str:
    s = v.strip() if isinstance(v, str) else ""
    if not s or len(s) > longest:
        raise ApiError(400, f"{name}: required (at most {longest} characters)")
    return s


def _num(v, name: str) -> float:
    try:
        f = float(v)
    except (TypeError, ValueError):
        raise ApiError(400, f"{name}: a number of seconds")
    if f != f or f < 0 or f > 10 * 86400:
        raise ApiError(400, f"{name}: out of range")
    return f


class MediaServers:
    def __init__(self, db, vault: TokenVault, clock: Callable[[], float] = time.time,
                 kinds: Optional[dict] = None, http: Optional[Callable[[str], Http]] = None):
        self.db, self.vault, self.clock = db, vault, clock
        self.kinds = kinds or KINDS
        self.http = http or (lambda label: Http(label))
        self.throttle = Throttle(clock)       # wrong Jellyfin passwords, per account
        self._lock = threading.Lock()
        self._pending: dict[str, dict] = {}   # pending id -> {account, kind, state, public, exp, hosts, device}
        self._hosts: set = set()
        self.plays = Plays(clock)             # playback through this server (orbit/mediaplay.py)
        self._refresh_hosts()
        self.vault.keep_only({r[0] for r in self.db.q("SELECT id FROM media_connections")})

    # ---- the LAN allowlist (net.set_private_hosts) ----
    def _refresh_hosts(self):
        hs = set()
        for (b,) in self.db.q("SELECT DISTINCT base_url FROM media_connections"):
            hp = host_port(b)
            if hp:
                hs.add(hp)
        self._hosts = hs

    def private_hosts(self) -> set:
        """Saved connections' (host, port). Addresses being linked are not here: see ``_scope``."""
        return self._hosts

    def _scope(self, hosts: set):
        """The LAN addresses an owner / admin is linking, allowed for the requests made inside this block by this
        thread only (and any the sign-in adds on the way, through its ``allow``)."""
        with self._lock:                      # never held while a connector makes a request
            now = set(hosts)
        return NET.private_hosts_scope(now)

    # ---- connections ----
    @staticmethod
    def _public(r) -> dict:
        return {"id": r[0], "kind": r[1], "baseUrl": r[2], "serverName": r[3], "serverId": r[4], "userId": r[5],
                "userName": r[6], "created": r[8], "lastOk": r[9]}

    def connections(self, sess: dict) -> list:
        return [self._public(r) for r in self.db.q(
            f"SELECT {COLS} FROM media_connections WHERE account_id=? ORDER BY created, id", (sess["account_id"],))]

    def _row(self, sess: dict, cid):
        r = self.db.one(f"SELECT {COLS} FROM media_connections WHERE id=? AND account_id=?",
                        (cid if isinstance(cid, str) else "", sess["account_id"]))
        if r is None:
            raise ApiError(404, "no such media connection")
        return r

    def connector(self, sess: dict, cid):
        r = self._row(sess, cid)
        tok = self.vault.get(r[0])
        if not tok:
            raise ApiError(409, f"{self.kinds[r[1]].label}: link this server again (its sign-in is missing)")
        cls = self.kinds[r[1]]
        return r, cls(r[2], tok, r[5], r[7], self.http(cls.label))

    def _ok(self, r):
        now = int(self.clock())
        if not r[9] or now - r[9] > OK_EVERY:
            self.db.run("UPDATE media_connections SET last_ok=? WHERE id=?", (now, r[0]))

    def _use(self, sess: dict, cid, fn):
        """Run fn(connector) for one of the caller's connections, mapping connector errors to API errors."""
        r, c = self.connector(sess, cid)
        try:
            out = fn(c)
        except ConnectorError as e:
            LOG.info("media %s %s: %s", r[1], r[0], e.msg)
            raise ApiError(e.code, e.msg)
        self._ok(r)
        return r, out

    def _save(self, sess: dict, kind: str, linked: list, device_id: str) -> list:
        out = []
        now = int(self.clock())
        for ln in linked:
            with self.db.lock:
                old = self.db.one("SELECT id FROM media_connections WHERE account_id=? AND kind=? AND server_id=? AND user_id=?",
                                  (sess["account_id"], kind, ln.server_id, ln.user_id))
                if old:
                    cid = old[0]
                    self.db.run("UPDATE media_connections SET base_url=?, server_name=?, user_name=?, device_id=?, last_ok=? "
                                "WHERE id=?", (ln.base_url, ln.server_name, ln.user_name, device_id, now, cid))
                else:
                    cid = secrets.token_hex(8)
                    self.db.run("INSERT INTO media_connections(id, account_id, kind, base_url, server_name, server_id, user_id, "
                                "user_name, device_id, created, last_ok) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                                (cid, sess["account_id"], kind, ln.base_url, ln.server_name, ln.server_id, ln.user_id,
                                 ln.user_name, device_id, now, now))
            self.vault.put(cid, ln.token)
            out.append(self._public(self._row(sess, cid)))
        self._refresh_hosts()
        LOG.info("media: %d %s connection(s) linked", len(out), kind)
        return out

    def delete(self, sess: dict, cid) -> dict:
        r = self._row(sess, cid)
        tok = self.vault.get(r[0])
        if tok:
            cls = self.kinds[r[1]]
            try:
                cls(r[2], tok, r[5], r[7], self.http(cls.label)).sign_out()
            except ConnectorError:
                pass                          # best effort: the server may be gone
        self.plays.end_where(lambda p: p.conn == r[0])
        self.db.run("DELETE FROM media_connections WHERE id=? AND account_id=?", (r[0], sess["account_id"]))
        self.vault.drop([r[0]])
        self._refresh_hosts()
        return {"ok": True}

    # ---- sign-in ----
    def _kind(self, v) -> str:
        if v not in self.kinds:
            raise ApiError(400, "kind: plex or jellyfin")
        return v

    def _prune(self):
        now = self.clock()
        for k, p in list(self._pending.items()):
            if p["exp"] <= now:
                self._pending.pop(k, None)

    def _allow(self, sess: dict, hosts: set) -> Callable[[str], None]:
        """For owners / admins: let this sign-in reach the address being linked even when it is on the LAN."""
        def allow(base: str):
            hp = host_port(base)
            if hp and sess.get("account_role") in MANAGERS:
                with self._lock:
                    hosts.add(hp)
                try:                          # inside a sign-in step: its next requests may reach it
                    NET.allow_here(*hp)
                except RuntimeError:
                    pass                      # outside one: only remembered for its next step
        return allow

    def start(self, sess: dict, kind, base_url) -> dict:
        kind = self._kind(kind)
        cls = self.kinds[kind]
        with self._lock:
            self._prune()
            if sum(1 for p in self._pending.values() if p["account"] == sess["account_id"]) >= MAX_PENDING:
                raise ApiError(429, "too many sign-ins at once; try again in a few minutes", 60)
        device = "orbit-" + secrets.token_hex(12)
        hosts: set = set()
        allow = self._allow(sess, hosts)
        pid = secrets.token_urlsafe(24)
        entry = {"account": sess["account_id"], "kind": kind, "exp": self.clock() + PENDING_TTL, "hosts": hosts,
                 "device": device, "state": None}
        if kind == "jellyfin" or base_url:     # Plex: an address the owner typed is optional
            try:
                allow(normalize_base_url(base_url))
            except ConnectorError as e:
                raise ApiError(e.code, e.msg)
        with self._lock:
            self._pending[pid] = entry        # the host must be listed before the first request
        try:
            with self._scope(hosts):
                public, state = cls.sign_in_start(self.http(cls.label), base_url, device)
        except ConnectorError as e:
            with self._lock:
                self._pending.pop(pid, None)
            raise ApiError(e.code, e.msg)
        state["allow"] = allow
        entry["state"] = state
        return {"id": pid, "kind": kind, **public, "expires": int(entry["exp"]), "interval": POLL_EVERY}

    def poll(self, sess: dict, pid) -> dict:
        with self._lock:
            self._prune()
            p = self._pending.get(pid if isinstance(pid, str) else "")
            if p is None or p["account"] != sess["account_id"] or p["state"] is None:
                raise ApiError(404, "no such sign-in (it may have expired)")
        cls = self.kinds[p["kind"]]
        try:
            with self._scope(p["hosts"]):
                linked = cls.sign_in_poll(self.http(cls.label), p["state"], p["device"])
        except ConnectorError as e:
            with self._lock:
                self._pending.pop(pid, None)
            raise ApiError(e.code, e.msg)
        if linked is None:
            return {"status": "pending", "expires": int(p["exp"])}
        conns = self._save(sess, p["kind"], linked, p["device"])
        with self._lock:
            self._pending.pop(pid, None)
        return {"status": "linked", "connections": conns}

    def password(self, sess: dict, kind, base_url, username, password) -> dict:
        kind = self._kind(kind)
        cls = self.kinds[kind]
        user = _str(username, "username", 200)
        pw = password if isinstance(password, str) and len(password) <= 500 else None
        if pw is None:
            raise ApiError(400, "password: required")
        key = "acc:" + sess["account_id"]
        try:
            self.throttle.check(key)
        except Wait as e:
            raise ApiError(429, "too many wrong passwords", e.retry_after)
        try:
            base = normalize_base_url(base_url)
        except ConnectorError as e:
            raise ApiError(e.code, e.msg)
        device = "orbit-" + secrets.token_hex(12)
        hosts: set = set()
        self._allow(sess, hosts)(base)
        try:
            with self._scope(hosts):
                linked = cls.sign_in_password(self.http(cls.label), base, user, pw, device)
        except ConnectorError as e:
            if e.code == 403:
                self.throttle.fail(key)
            raise ApiError(e.code, e.msg)
        self.throttle.ok(key)
        return {"connections": self._save(sess, kind, [linked], device)}

    # ---- browsing ----
    def _img(self, cid: str, path: Optional[str]) -> Optional[str]:
        if not path:
            return None
        return "/v1/media/image?" + urllib.parse.urlencode({"conn": cid, "path": path})

    def _out(self, r, it: dict) -> dict:
        return {**it, "conn": r[0], "source": r[1], "poster": self._img(r[0], it.get("poster")),
                "backdrop": self._img(r[0], it.get("backdrop"))}

    def libraries(self, sess: dict, cid=None) -> dict:
        rows = [self._row(sess, cid)] if cid else self.db.q(
            f"SELECT {COLS} FROM media_connections WHERE account_id=? ORDER BY created, id", (sess["account_id"],))
        libs, errors = [], []
        for r in rows:
            try:
                _, ls = self._use(sess, r[0], lambda c: c.list_libraries())
            except ApiError as e:
                if cid:
                    raise
                errors.append({"conn": r[0], "error": e.msg})
                continue
            libs += [{**lib, "conn": r[0], "source": r[1], "serverName": r[3]} for lib in ls]
        return {"libraries": libs, "errors": errors}

    def items(self, sess: dict, q: dict) -> dict:
        lib = q.get("library")
        if not lib:
            raise ApiError(400, "library: required")
        r, ans = self._use(sess, q.get("conn"), lambda c: c.list_items(lib, q.get("cursor") or None,
                                                                      q.get("kind") or None, q.get("limit") or 50))
        return {**ans, "items": [self._out(r, i) for i in ans["items"]]}

    def item(self, sess: dict, q: dict) -> dict:
        r, it = self._use(sess, q.get("conn"), lambda c: c.get_item(q.get("id")))
        return self._out(r, it)

    def children(self, sess: dict, q: dict) -> dict:
        r, its = self._use(sess, q.get("conn"), lambda c: c.children(q.get("id")))
        return {"items": [self._out(r, i) for i in its]}

    def playback(self, sess: dict, b: dict) -> dict:
        r, ans = self._use(sess, b.get("conn"), lambda c: c.playback(b.get("id")))
        return {**ans, "conn": r[0], "source": r[1]}

    def play(self, sess: dict, b: dict) -> dict:
        """Start playing a film / episode through this server (orbit/mediaplay.py)."""
        r, c = self.connector(sess, b.get("conn"))
        out = self.plays.start(sess, r, c, b.get("id"), b.get("client"), b.get("caps"), b.get("audio"),
                               b.get("startSecs"))
        self._ok(r)
        return out

    def end_plays(self, sess_h: str) -> int:
        """A signed-in session ended (sign-out): its play sessions end too."""
        return self.plays.end_where(lambda p: p.sess_h == sess_h)

    def progress(self, sess: dict, b: dict) -> dict:
        if "session" in b:                    # a playback started with POST /v1/media/play
            return self.plays.progress(sess, b.get("session"), b.get("positionSecs"), b.get("state"))
        secs, dur = _num(b.get("secs"), "secs"), _num(b.get("dur") or 0, "dur")
        state = b.get("state")
        if state not in ("playing", "paused", "stopped"):
            raise ApiError(400, "state: playing, paused or stopped")
        self._use(sess, b.get("conn"), lambda c: c.report_progress(b.get("id"), secs, dur, state))
        return {"ok": True}

    def watched(self, sess: dict, b: dict) -> dict:
        w = b.get("watched")
        if not isinstance(w, bool):
            raise ApiError(400, "watched: true or false")
        self._use(sess, b.get("conn"), lambda c: c.mark_watched(b.get("id"), w))
        return {"ok": True}

    def image(self, sess: dict, q: dict) -> Raw:
        _, (data, ctype) = self._use(sess, q.get("conn"), lambda c: c.image(q.get("path")))
        return Raw(data, ctype, {"Cache-Control": "private, max-age=86400"})

    def check(self, sess: dict) -> list:
        out = []
        for c in self.connections(sess):
            try:
                self._use(sess, c["id"], lambda x: x.health())
                out.append({**c, "ok": True})
            except ApiError as e:
                out.append({**c, "ok": False, "error": e.msg})
        return out

    # ---- the /v1 routes (orbit/api.py adds them to its table) ----
    def routes(self) -> dict:
        """{(method, path): fn(sess, body, query) -> dict | Raw}"""
        return {
            ("GET", "/v1/media/connections"): lambda s, b, q: {
                "connections": self.check(s) if q.get("check") == "1" else self.connections(s)},
            ("POST", "/v1/media/connect/start"): lambda s, b, q: self.start(s, b.get("kind"), b.get("baseUrl")),
            ("POST", "/v1/media/connect/poll"): lambda s, b, q: self.poll(s, b.get("id")),
            ("POST", "/v1/media/connect/password"): lambda s, b, q: self.password(
                s, b.get("kind"), b.get("baseUrl"), b.get("username"), b.get("password")),
            ("POST", "/v1/media/connections/delete"): lambda s, b, q: self.delete(s, b.get("id")),
            ("GET", "/v1/media/libraries"): lambda s, b, q: self.libraries(s, q.get("conn")),
            ("GET", "/v1/media/items"): lambda s, b, q: self.items(s, q),
            ("GET", "/v1/media/item"): lambda s, b, q: self.item(s, q),
            ("GET", "/v1/media/children"): lambda s, b, q: self.children(s, q),
            ("GET", "/v1/media/image"): lambda s, b, q: self.image(s, q),
            ("POST", "/v1/media/playback"): lambda s, b, q: self.playback(s, b),
            ("POST", "/v1/media/play"): lambda s, b, q: self.play(s, b),
            ("POST", "/v1/media/progress"): lambda s, b, q: self.progress(s, b),
            ("POST", "/v1/media/watched"): lambda s, b, q: self.watched(s, b),
        }


__all__ = ["MediaServers", "TokenVault", "Raw", "Linked"]
