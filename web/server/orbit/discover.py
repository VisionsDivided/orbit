# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""Discover and requests through Seerr (docs/orbit/API-v1.md, "Discover and requests (Phase 6)").

Routes are mounted into the /v1 route table by the app (``Api.routes.update(discover.routes())``), so they get the
same session, Host, Origin and CSRF checks as every other /v1 call.

- Discovery (rows, search, title) works for every signed-in account and is fetched with the admin key; answers are
  cached (rows 10 minutes, titles 1 hour, bounded). When Seerr cannot be reached, the last answer is served with
  ``"stale": true``; requests then answer 503.
- Requests are made *as the account's Seerr user* (``X-Api-User``), so Seerr's own permissions, auto-approval and
  quotas apply. An account without a linked Seerr user can browse but not request (409).
- ``availability_override`` is the hook for "Play instead of Request": Phase 5 merges local Plex / Jellyfin / IPTV
  matches (by TMDb id) through it."""
from __future__ import annotations

import logging
import threading
import time
from typing import Callable, Optional

from .accounts import Accounts, ApiError
from .api import Call, Reply
from . import seerr as S

LOG = logging.getLogger("orbit.discover")

ROWS_TTL = 600
TITLE_TTL = 3600
SEARCH_TTL = 600
STATUS_TTL = 30
DOWN_BACKOFF = 15          # after Seerr failed to answer, do not try again for this long (answers stay fast)
ROWS = (("trending", "Trending", "/discover/trending", None),
        ("movies", "Popular movies", "/discover/movies", "movie"),
        ("tv", "Popular TV", "/discover/tv", "tv"))
UPCOMING = (("/discover/movies/upcoming", "movie"), ("/discover/tv/upcoming", "tv"))
MAX_REQUESTS = 50
NOT_LINKED = "your account is not linked to a Seerr user: ask the owner to link your Seerr user"


def availability_override(tmdb: int, kind: str) -> Optional[dict]:
    """Local availability of a title, merged over Seerr's answer: e.g. {"status": "available", "play": {...}}.

    TODO(Phase 5, catalogue merge): look the TMDb id up in the merged Plex / Jellyfin / IPTV catalogue and return
    where it can be played, so clients show "Play" instead of "Request". No-op until then."""
    return None


class _Guarded:
    """A SeerrClient that fails fast for DOWN_BACKOFF seconds after Seerr could not be reached."""

    def __init__(self, client: S.SeerrClient, owner: "Discover"):
        self._c, self._o = client, owner

    def _run(self, fn, *a, **k):
        o = self._o
        if o.clock() < o._down_until:
            raise S.SeerrDown("Seerr was unreachable a moment ago")
        try:
            return fn(*a, **k)
        except S.SeerrDown:
            o._down_until = o.clock() + DOWN_BACKOFF
            raise

    def get(self, *a, **k):
        return self._run(self._c.get, *a, **k)

    def post(self, *a, **k):
        return self._run(self._c.post, *a, **k)


class Discover:
    def __init__(self, accounts: Accounts, config: S.SeerrConfig,
                 client_factory: Callable[[str, str], S.SeerrClient] = S.SeerrClient,
                 clock: Callable[[], float] = time.time):
        self.acc, self.cfg, self.clock = accounts, config, clock
        self._client_factory = client_factory
        self.cache = S.TTLCache(256, clock)
        self.availability_override: Callable[[int, str], Optional[dict]] = availability_override
        self._lock = threading.Lock()
        self._reach: tuple = (0.0, None)          # (when, status dict or None when down)
        self._down_until = 0.0

    def private_hosts(self) -> set:
        """For net.set_private_hosts (compose it with the other configured media servers)."""
        return self.cfg.private_hosts()

    def routes(self) -> dict:
        return {
            ("GET", "/v1/discover/status"): self._status,
            ("GET", "/v1/discover/rows"): self._rows,
            ("GET", "/v1/discover/search"): self._search,
            ("GET", "/v1/discover/title"): self._title,
            ("GET", "/v1/requests"): self._requests,
            ("POST", "/v1/requests"): self._request,
            ("POST", "/v1/discover/settings"): self._settings,
            ("POST", "/v1/discover/map"): self._map,
            ("GET", "/v1/discover/users"): self._users,
        }

    # ---- helpers ----
    @staticmethod
    def _admin(c: Call):
        if c.sess.get("account_role") not in ("owner", "admin"):
            raise ApiError(403, "owner or admin only")

    def _client(self) -> _Guarded:
        d = self.cfg.get()
        if not (d.get("baseUrl") and d.get("apiKey")):
            raise ApiError(409, "Seerr is not set up (Settings > Discover)")
        return _Guarded(self._client_factory(d["baseUrl"], d["apiKey"]), self)

    @staticmethod
    def _query(c: Call) -> dict:
        return c.query

    def _cached(self, key, ttl: float, fetch: Callable[[], object]) -> tuple:
        """(value, stale). Raises S.SeerrDown when Seerr is down and nothing is cached."""
        v = self.cache.get(key, ttl)
        if v is not None:
            return v, False
        try:
            v = fetch()
        except S.SeerrDown:
            old = self.cache.stale(key)
            if old is None:
                raise
            return old, True
        self.cache.put(key, v)
        return v, False

    def _merge(self, t: Optional[dict]) -> Optional[dict]:
        if t is None:
            return None
        try:
            local = self.availability_override(t["tmdb"], t["type"])
        except Exception:                          # noqa: BLE001 (a broken hook must not break discovery)
            LOG.exception("availability_override failed")
            local = None
        return {**t, **local} if isinstance(local, dict) else t

    @staticmethod
    def _down(e: Exception) -> ApiError:
        return ApiError(503, "Seerr cannot be reached right now")

    @staticmethod
    def _seerr_error(e: S.SeerrError) -> ApiError:
        if e.code == 401 or (e.code == 403 and "api key" in e.msg.lower()):
            return ApiError(502, "Seerr refused the server's API key")
        if e.code == 0:
            return ApiError(502, e.msg)
        if e.code == 404:
            return ApiError(404, "Seerr does not know this title")
        return ApiError(e.code if e.code in (400, 403, 409) else 502, f"Seerr: {e.msg}")

    def _results(self, client: S.SeerrClient, path: str, kind: Optional[str], params: Optional[dict] = None) -> list:
        data = client.get(path, {"page": 1, **(params or {})})
        out = []
        for r in (data.get("results") if isinstance(data, dict) else None) or []:
            t = S.normalize(r, kind)
            if t is not None:
                out.append(t)
        return out

    # ---- discovery (any signed-in account) ----
    def _status(self, c: Call) -> Reply:
        d = self.cfg.get()
        out = {"configured": bool(d.get("baseUrl") and d.get("apiKey")), "reachable": False,
               "linked": self.cfg.seerr_user(c.sess["account_id"]) is not None}
        if c.sess.get("account_role") in ("owner", "admin"):
            out["baseUrl"] = d.get("baseUrl") or ""
            out["keySet"] = bool(d.get("apiKey"))
        if not out["configured"]:
            return Reply(out)
        when, st = self._reach
        if self.clock() - when > STATUS_TTL:
            try:
                raw = self._client().get("/status")
                st = {"version": S._text(raw.get("version"), 40) if isinstance(raw, dict) else ""}
            except (S.SeerrDown, S.SeerrError):
                st = None
            self._reach = (self.clock(), st)
        out["reachable"] = st is not None
        if st:
            out["version"] = st.get("version") or ""
        return Reply(out)

    def _rows(self, c: Call) -> Reply:
        cl = self._client()
        rows, stale, failed = [], False, 0
        specs = [(rid, title, [(path, kind)]) for rid, title, path, kind in ROWS]
        specs.append(("upcoming", "Coming soon", list(UPCOMING)))
        for rid, title, parts in specs:
            def fetch(parts=parts):
                lists = [self._results(cl, p, k) for p, k in parts]
                merged = []
                for i in range(max((len(x) for x in lists), default=0)):     # interleave movies and TV
                    merged.extend(x[i] for x in lists if i < len(x))
                return merged
            try:
                items, st = self._cached(("rows", rid), ROWS_TTL, fetch)
            except S.SeerrDown:
                items, st, failed = [], True, failed + 1
            except S.SeerrError as e:
                if e.code in (401, 403):                    # discovery uses the admin key
                    raise ApiError(502, "Seerr refused the server's API key")
                if e.code == 0:
                    raise self._seerr_error(e)
                LOG.warning("Seerr row %s: %s", rid, e.code)  # e.g. an older Seerr without this list
                items, st = [], False
            stale = stale or st
            rows.append({"id": rid, "title": title, "items": [self._merge(t) for t in items]})
        if failed == len(specs):
            raise ApiError(503, "Seerr cannot be reached right now")
        return Reply({"rows": rows, "stale": stale})

    def _search(self, c: Call) -> Reply:
        q = self._query(c).get("q", "")
        q = q.strip() if isinstance(q, str) else ""
        if not 1 <= len(q) <= 100 or any(ord(ch) < 32 for ch in q):
            raise ApiError(400, "q: 1 to 100 characters")
        cl = self._client()
        try:
            items, stale = self._cached(("search", q.lower()), SEARCH_TTL,
                                        lambda: self._results(cl, "/search", None, {"query": q}))
        except S.SeerrDown as e:
            raise self._down(e)
        except S.SeerrError as e:
            raise self._seerr_error(e)
        return Reply({"results": [self._merge(t) for t in items], "stale": stale})

    @staticmethod
    def _kind_tmdb(kind, tmdb) -> tuple:
        if kind not in S.TYPES:
            raise ApiError(400, "type: movie or tv")
        try:
            n = int(tmdb)
        except (TypeError, ValueError):
            n = 0
        if isinstance(tmdb, bool) or not 0 < n < 10 ** 9:
            raise ApiError(400, "tmdb: the TMDb id (a positive number)")
        return kind, n

    def _fetch_title(self, cl: S.SeerrClient, kind: str, tmdb: int) -> dict:
        t = S.normalize(cl.get(f"/{kind}/{tmdb}"), kind, details=True)
        if t is None:
            raise S.SeerrError(404, "not found")
        return t

    def _lookup(self, cl: S.SeerrClient, kind: str, tmdb: int) -> tuple:
        return self._cached(("title", kind, tmdb), TITLE_TTL, lambda: self._fetch_title(cl, kind, tmdb))

    def _title(self, c: Call) -> Reply:
        q = self._query(c)
        kind, tmdb = self._kind_tmdb(q.get("type"), q.get("tmdb"))
        cl = self._client()
        try:
            t, stale = self._lookup(cl, kind, tmdb)
        except S.SeerrDown as e:
            raise self._down(e)
        except S.SeerrError as e:
            raise self._seerr_error(e)
        return Reply({**self._merge(t), "stale": stale})

    # ---- requests ----
    def _requests(self, c: Call) -> Reply:
        cl = self._client()
        every = str(self._query(c).get("all") or "") in ("1", "true")
        params = {"take": MAX_REQUESTS, "skip": 0, "filter": "all", "sort": "added"}
        user = None
        if every:
            self._admin(c)
        else:
            user = self.cfg.seerr_user(c.sess["account_id"])
            if user is None:
                return Reply({"requests": [], "linked": False})
            params["requestedBy"] = user
        try:
            data = cl.get("/request", params, user=user)
        except S.SeerrDown as e:
            raise self._down(e)
        except S.SeerrError as e:
            raise self._seerr_error(e)
        out = []
        for raw in (data.get("results") if isinstance(data, dict) else None) or []:
            r = S.normalize_request(raw)
            if r is None or (user is not None and r["requestedBy"]["seerrUserId"] not in (None, user)):
                continue
            try:
                r["title"] = self._merge(self._lookup(cl, r["type"], r["tmdb"])[0])
            except (S.SeerrDown, S.SeerrError):
                r["title"] = None
            out.append(r)
        if every:
            back = {v: k for k, v in self.cfg.mapping().items()}
            for r in out:
                r["requestedBy"]["accountId"] = back.get(r["requestedBy"]["seerrUserId"])
        return Reply({"requests": out, "linked": every or user is not None})

    def _request(self, c: Call) -> Reply:
        kind, tmdb = self._kind_tmdb(c.body.get("type"), c.body.get("tmdb"))
        seasons = c.body.get("seasons")
        if kind == "movie" and seasons not in (None, [], "all"):
            raise ApiError(400, "seasons: only for TV")
        if kind == "tv" and seasons not in (None, "all") and not (
                isinstance(seasons, list) and 0 < len(seasons) <= 100
                and all(isinstance(n, int) and not isinstance(n, bool) and 0 < n < 1000 for n in seasons)):
            raise ApiError(400, "seasons: a list of season numbers, or leave it out for every season")
        cl = self._client()
        user = self.cfg.seerr_user(c.sess["account_id"])
        if user is None:
            raise ApiError(409, NOT_LINKED)
        try:
            cur = self._fetch_title(cl, kind, tmdb)          # always fresh: duplicates are judged on Seerr's state
        except S.SeerrDown as e:
            raise self._down(e)
        except S.SeerrError as e:
            raise self._seerr_error(e)
        self.cache.put(("title", kind, tmdb), cur)
        body = {"mediaType": kind, "mediaId": tmdb}
        if kind == "movie":
            if cur["status"] != "requestable" or cur.get("requestStatus") in ("pending", "approved"):
                return Reply({"created": False, "title": self._merge(cur)})
        else:
            open_ = [s["season"] for s in cur.get("seasons") or [] if s["status"] == "requestable"]
            want = open_ if seasons in (None, "all") else [n for n in sorted(set(seasons)) if n in open_]
            if isinstance(seasons, list):
                known = {s["season"] for s in cur.get("seasons") or []}
                if any(n not in known for n in seasons):
                    raise ApiError(400, "seasons: this show has no such season")
            if not want:
                return Reply({"created": False, "title": self._merge(cur)})
            body["seasons"] = want
        try:
            raw = cl.post("/request", body, user=user)
        except S.SeerrDown as e:
            raise self._down(e)
        except S.SeerrError as e:
            if e.code == 409:                                # Seerr's own duplicate check
                return Reply({"created": False, "title": self._merge(cur)})
            raise self._seerr_error(e)
        self.cache.drop(lambda k: k == ("title", kind, tmdb) or k[0] == "rows")
        try:
            cur = self._fetch_title(cl, kind, tmdb)
            self.cache.put(("title", kind, tmdb), cur)
        except (S.SeerrDown, S.SeerrError):
            pass
        LOG.info("Seerr request: %s %s by account %s", kind, tmdb, c.sess["account_id"])
        return Reply({"created": True, "request": S.normalize_request(raw), "title": self._merge(cur)}, 201)

    # ---- admin ----
    def _settings(self, c: Call) -> Reply:
        self._admin(c)
        b = c.body
        if b.get("remove") is True:
            self.cfg.clear_server()
            self.cache.clear()
            self._reach = (0.0, None)
            return Reply({"configured": False})
        old = self.cfg.get()
        try:
            base = S.clean_base_url(b.get("baseUrl"))
            key_in = b.get("apiKey")
            if (key_in is None or key_in == "") and old.get("apiKey"):
                key = old["apiKey"]                          # keep the saved key when only the address changes
            else:
                key = S.clean_api_key(key_in)
        except ValueError as e:
            raise ApiError(400, str(e))
        cl = self._client_factory(base, key)
        with self.cfg.probing(base):
            try:
                st = cl.get("/status")
                me = cl.get("/auth/me")                      # checks the key (the key's own Seerr user)
            except S.SeerrDown:
                raise ApiError(502, "Seerr cannot be reached at this address")
            except S.SeerrError as e:
                if e.code in (401, 403):
                    raise ApiError(400, "Seerr refused this API key")
                raise ApiError(502, f"Seerr: {e.msg}" if e.code else e.msg)
        if not isinstance(me, dict) or not S._int(me.get("id")):
            raise ApiError(502, "this address does not answer like Seerr")
        self.cfg.set_server(base, key)
        self.cache.clear()
        self._reach, self._down_until = (0.0, None), 0.0
        LOG.warning("Seerr settings changed by account %s (server %s)", c.sess["account_id"], base)
        return Reply({"configured": True, "baseUrl": base, "keySet": True,
                      "version": S._text(st.get("version"), 40) if isinstance(st, dict) else ""})

    def _seerr_users(self, cl: S.SeerrClient) -> list:
        try:
            data = cl.get("/user", {"take": 500, "skip": 0})
        except S.SeerrDown as e:
            raise self._down(e)
        except S.SeerrError as e:
            raise self._seerr_error(e)
        out = []
        for u in (data.get("results") if isinstance(data, dict) else None) or []:
            if isinstance(u, dict) and S._int(u.get("id")):
                out.append({"id": u["id"], "name": S._text(u.get("displayName") or u.get("username")
                                                           or u.get("plexUsername") or u.get("jellyfinUsername")
                                                           or u.get("email"), 100),
                            "email": S._text(u.get("email"), 200), "username": S._text(u.get("username"), 100),
                            "plexUsername": S._text(u.get("plexUsername"), 100),
                            "jellyfinUsername": S._text(u.get("jellyfinUsername"), 100)})
        return out

    @staticmethod
    def _suggest(account: dict, users: list) -> Optional[int]:
        """The one Seerr user whose username, Plex / Jellyfin name, display name or e-mail name equals the
        account's username or display name (case-insensitive). None when there is no match or more than one."""
        names = {str(account.get("username") or "").lower(), str(account.get("displayName") or "").lower()} - {""}
        hits = set()
        for u in users:
            theirs = {u["username"].lower(), u["plexUsername"].lower(), u["jellyfinUsername"].lower(),
                      u["name"].lower(), u["email"].lower(), u["email"].split("@")[0].lower()} - {""}
            if names & theirs:
                hits.add(u["id"])
        return hits.pop() if len(hits) == 1 else None

    def _users(self, c: Call) -> Reply:
        self._admin(c)
        users = self._seerr_users(self._client())
        m = self.cfg.mapping()
        accounts = [{"id": a["id"], "username": a["username"], "displayName": a["displayName"],
                     "seerrUserId": m.get(a["id"]), "suggested": self._suggest(a, users)}
                    for a in self.acc.accounts(c.sess)]
        return Reply({"users": users, "accounts": accounts})

    def _map(self, c: Call) -> Reply:
        self._admin(c)
        b = c.body
        if b.get("auto") is True:                            # link every unlinked account with one clear match
            users = self._seerr_users(self._client())
            m, linked = self.cfg.mapping(), []
            for a in self.acc.accounts(c.sess):
                if a["id"] not in m:
                    uid = self._suggest(a, users)
                    if uid:
                        self.cfg.set_mapping(a["id"], uid)
                        linked.append({"accountId": a["id"], "seerrUserId": uid})
            return Reply({"linked": linked})
        aid = b.get("accountId")
        if not isinstance(aid, str):
            raise ApiError(400, "accountId")
        self.acc.account(aid)                                # 404 for an unknown account
        uid = b.get("seerrUserId")
        if uid is None:
            self.cfg.set_mapping(aid, None)
            return Reply({"accountId": aid, "seerrUserId": None})
        if isinstance(uid, bool) or not isinstance(uid, int) or uid <= 0:
            raise ApiError(400, "seerrUserId: a Seerr user id, or null to unlink")
        try:
            self._client().get(f"/user/{uid}")
        except S.SeerrDown as e:
            raise self._down(e)
        except S.SeerrError as e:
            if e.code == 404:
                raise ApiError(400, "Seerr has no user with this id")
            raise self._seerr_error(e)
        self.cfg.set_mapping(aid, uid)
        return Reply({"accountId": aid, "seerrUserId": uid})
