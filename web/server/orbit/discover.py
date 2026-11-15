# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""Discover and requests through Seerr (docs/orbit/API-v1.md, "Discover and requests (Phase 6)").

Routes are mounted into the /v1 route table by the app (``Api.routes.update(discover.routes())``), so they get the
same session, Host, Origin and CSRF checks as every other /v1 call.

- Discovery (rows, search, title) works for every signed-in account and is fetched with the admin key; answers are
  cached (rows and searches 10 minutes per page, titles 1 hour, title pages 1 minute, bounded). When Seerr cannot be reached, the last answer is served with
  ``"stale": true``; requests then answer 503.
- Requests are made *as the account's Seerr user* (``X-Api-User``), so Seerr's own permissions, auto-approval and
  quotas apply. An account without a linked Seerr user can browse but not request (409).
- ``availability_override`` is the hook for "Play instead of Request": Phase 5 merges local Plex / Jellyfin / IPTV
  matches (by TMDb id) through it."""
from __future__ import annotations

import logging
import re
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from typing import Callable, Optional

from .accounts import Accounts, ApiError
from .api import Call, Reply
from . import seerr as S

LOG = logging.getLogger("orbit.discover")

ROWS_TTL = 600
TITLE_TTL = 3600
DETAIL_TTL = 60            # the title page (requests and download progress change while someone looks at it)
SEARCH_TTL = 600
STATUS_TTL = 30
MAX_PAGE = 500             # TMDB (and so Seerr) pages discover lists and searches up to page 500
DOWN_BACKOFF = 15          # after Seerr failed to answer, do not try again for this long (answers stay fast)
ROWS = (("trending", "Trending", "/discover/trending", None),
        ("movies", "Popular movies", "/discover/movies", "movie"),
        ("tv", "Popular TV", "/discover/tv", "tv"))
UPCOMING = (("/discover/movies/upcoming", "movie"), ("/discover/tv/upcoming", "tv"))
MAX_REQUESTS = 50
LOOKUP_WORKERS = 4         # title lookups for a request list run in parallel, at most this many at once
MISSING_TTL = 600          # a title Seerr does not know (404) is not asked for again for this long
# GET /v1/discover/image: TMDB artwork through this server (the web page's CSP allows only its own images).
# Only image.tmdb.org, only these sizes, only a plain file name: the route can fetch nothing else.
IMAGE_SIZES = ("w92", "w154", "w185", "w300", "w342", "w500", "w780", "w1280", "original")
IMAGE_PATH = re.compile(r"^/(" + "|".join(IMAGE_SIZES) + r")/[A-Za-z0-9_-]{1,100}\.(?:jpg|jpeg|png|webp)$")
IMAGE_MAX = 5 * 1024 * 1024
IMAGE_HEADERS = {"Cache-Control": "private, max-age=604800", "Content-Disposition": "inline",
                 "Content-Security-Policy": "sandbox; default-src 'none'"}


def image_url(p: str) -> Optional[str]:
    """'/w500/abc.jpg' -> the TMDB CDN URL, or None when p is not an allowed size + file name."""
    return S.TMDB_IMG + p[1:] if isinstance(p, str) and IMAGE_PATH.match(p) else None


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


SEERR_ADMIN = 2                     # Seerr's Permission.ADMIN bit (implies every other permission, auto-approve too)


def seerr_admin(u: dict) -> bool:
    """A Seerr user with the ADMIN permission, or Seerr's first user (its owner, always an admin). Requests made as
    such a user are approved automatically, so only the Orbit owner may link an account to one."""
    try:
        perms = int(u.get("permissions") or 0)
    except (TypeError, ValueError):
        perms = 0
    return bool(perms & SEERR_ADMIN) or u.get("id") == 1


class Discover:
    def __init__(self, accounts: Accounts, config: S.SeerrConfig,
                 client_factory: Callable[[str, str], S.SeerrClient] = S.SeerrClient,
                 clock: Callable[[], float] = time.time,
                 fetch_image: Optional[Callable[[str], bytes]] = None, image_cache=None,
                 sniff: Optional[Callable[[bytes], Optional[str]]] = None):
        self.acc, self.cfg, self.clock = accounts, config, clock
        # TMDB artwork proxy: fetch_image(url) -> bytes (at most IMAGE_MAX + 1 read), image_cache with get / put
        # (the app's /img disk cache, size-capped), sniff(bytes) -> image content type or None
        self.fetch_image, self.image_cache, self.sniff = fetch_image, image_cache, sniff
        self._client_factory = client_factory
        self.cache = S.TTLCache(256, clock)
        self.availability_override: Callable[[int, str], Optional[dict]] = availability_override
        # "Play instead of Request" from the catalogue (app.py: Catalog.tmdb_finder): for a call, a function
        # (type, tmdb) -> the profile's catalogue Item for that TMDb id, or None
        self.local_finder: Optional[Callable[[Call], Callable[[str, int], Optional[dict]]]] = None
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
            ("GET", "/v1/discover/image"): self._image,
        }

    def prefix_routes(self) -> dict:
        """Routes with ids in the path (Api.prefix_routes): one row's further pages and the title page."""
        return {
            ("GET", "/v1/discover/rows/"): self._row,
            ("GET", "/v1/discover/title/"): self._title_page,
        }

    def _image(self, c: Call) -> Reply:
        """TMDB artwork for clients that may load images only from this server (the web page). Works whether or not
        Seerr is set up (the files are public); signed in like every /v1 route."""
        url = image_url(c.query.get("p", ""))
        if url is None:
            raise ApiError(400, "p must be /<size>/<file>.jpg|png|webp with size one of " + ", ".join(IMAGE_SIZES))
        if self.fetch_image is None or self.sniff is None:
            raise ApiError(503, "image proxy not available")
        key = "tmdb:" + url
        data = self.image_cache.get(key) if self.image_cache is not None else None
        if data is None:
            try:
                data = self.fetch_image(url)
            except Exception:                          # noqa: BLE001 (network, 404 from TMDB)
                raise ApiError(404, "image not found")
            if not data or len(data) > IMAGE_MAX:
                raise ApiError(413 if data else 404, "image too large" if data else "image not found")
            if self.sniff(data) is None:
                raise ApiError(415, "not an image")
            if self.image_cache is not None:
                self.image_cache.put(key, data)
        ctype = self.sniff(data)
        if ctype is None:
            raise ApiError(415, "not an image")
        return Reply({}, raw=data, ctype=ctype, headers=dict(IMAGE_HEADERS))

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

    def _finder(self, c: Call) -> Optional[Callable[[str, int], Optional[dict]]]:
        if self.local_finder is None:
            return None
        try:
            return self.local_finder(c)
        except Exception:                          # noqa: BLE001 (the catalogue must not break discovery)
            LOG.exception("catalogue lookup failed")
            return None

    def _merge(self, t: Optional[dict], find: Optional[Callable[[str, int], Optional[dict]]] = None) -> Optional[dict]:
        if t is None:
            return None
        try:
            local = self.availability_override(t["tmdb"], t["type"])
        except Exception:                          # noqa: BLE001 (a broken hook must not break discovery)
            LOG.exception("availability_override failed")
            local = None
        out = {**t, **local} if isinstance(local, dict) else t
        if find is not None:
            try:
                item = find(t["type"], t["tmdb"])
            except Exception:                      # noqa: BLE001
                LOG.exception("catalogue lookup failed")
                item = None
            if isinstance(item, dict):
                out = {**out, "status": "available", "play": item}
        return out

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

    def _results(self, client: S.SeerrClient, path: str, kind: Optional[str], params: Optional[dict] = None,
                 page: int = 1, people: bool = False) -> dict:
        """One page of a Seerr list -> {"items": [Title], "pages", "total"} (+ "people" when asked)."""
        data = client.get(path, {"page": page, **(params or {})})
        data = data if isinstance(data, dict) else {}
        out, folks = [], []
        for r in data.get("results") or []:
            t = S.normalize(r, kind)
            if t is not None:
                out.append(t)
            elif people:
                p = S.normalize_person(r)
                if p is not None:
                    folks.append(p)
        pages = S._int(data.get("totalPages"))
        pages = max(page if out else 0, min(MAX_PAGE, pages)) if pages is not None else page
        res = {"items": out, "pages": pages, "total": S._int(data.get("totalResults")) or None}
        if people:
            res["people"] = folks
        return res

    @staticmethod
    def _page(c: Call) -> int:
        v = c.query.get("page", "1") or "1"
        if not (isinstance(v, str) and v.isdigit() and 1 <= int(v) <= MAX_PAGE):
            raise ApiError(400, f"page: 1 to {MAX_PAGE}")
        return int(v)

    def _row_spec(self, rid: str) -> Optional[tuple]:
        """(title, [(Seerr path, kind)]) of a row id."""
        for r, title, path, kind in ROWS:
            if r == rid:
                return title, [(path, kind)]
        return ("Coming soon", list(UPCOMING)) if rid == "upcoming" else None

    def _row_page(self, cl, parts: list, page: int) -> dict:
        lists = [self._results(cl, p, k, page=page) for p, k in parts]
        merged = []
        for i in range(max((len(x["items"]) for x in lists), default=0)):     # interleave movies and TV
            merged.extend(x["items"][i] for x in lists if i < len(x["items"]))
        return {"items": merged, "pages": max((x["pages"] for x in lists), default=page)}

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
        find = self._finder(c)
        rows, stale, failed = [], False, 0
        specs = [(rid, title, [(path, kind)]) for rid, title, path, kind in ROWS]
        specs.append(("upcoming", "Coming soon", list(UPCOMING)))
        for rid, title, parts in specs:
            try:
                got, st = self._cached(("rows", rid, 1), ROWS_TTL, lambda parts=parts: self._row_page(cl, parts, 1))
                items, pages = got["items"], got["pages"]
            except S.SeerrDown:
                items, pages, st, failed = [], 0, True, failed + 1
            except S.SeerrError as e:
                if e.code in (401, 403):                    # discovery uses the admin key
                    raise ApiError(502, "Seerr refused the server's API key")
                if e.code == 0:
                    raise self._seerr_error(e)
                LOG.warning("Seerr row %s: %s", rid, e.code)  # e.g. an older Seerr without this list
                items, pages, st = [], 0, False
            stale = stale or st
            rows.append({"id": rid, "title": title, "items": [self._merge(t, find) for t in items],
                         "page": 1, "pages": pages})
        if failed == len(specs):
            raise ApiError(503, "Seerr cannot be reached right now")
        return Reply({"rows": rows, "stale": stale})

    def _row(self, c: Call) -> Reply:
        """GET /v1/discover/rows/<id>?page=N: a further page of one row (clients load it near the row's end)."""
        rid = c.path[len("/v1/discover/rows/"):]
        spec = self._row_spec(rid)
        if spec is None:
            raise ApiError(404, "no such row")
        page = self._page(c)
        cl = self._client()
        try:
            got, stale = self._cached(("rows", rid, page), ROWS_TTL, lambda: self._row_page(cl, spec[1], page))
        except S.SeerrDown as e:
            raise self._down(e)
        except S.SeerrError as e:
            raise self._seerr_error(e)
        find = self._finder(c)
        return Reply({"id": rid, "title": spec[0], "items": [self._merge(t, find) for t in got["items"]],
                      "page": page, "pages": got["pages"], "stale": stale})

    def _search(self, c: Call) -> Reply:
        q = self._query(c).get("q", "")
        q = q.strip() if isinstance(q, str) else ""
        if not 1 <= len(q) <= 100 or any(ord(ch) < 32 for ch in q):
            raise ApiError(400, "q: 1 to 100 characters")
        page = self._page(c)
        cl = self._client()
        try:
            got, stale = self._cached(("search", q.lower(), page), SEARCH_TTL,
                                      lambda: self._results(cl, "/search", None, {"query": q}, page=page, people=True))
        except S.SeerrDown as e:
            raise self._down(e)
        except S.SeerrError as e:
            raise self._seerr_error(e)
        find = self._finder(c)
        people = [{**p, "knownFor": [self._merge(t, find) for t in p["knownFor"]]} for p in got.get("people") or []]
        return Reply({"results": [self._merge(t, find) for t in got["items"]], "people": people,
                      "page": page, "pages": got["pages"], "total": got["total"], "stale": stale})

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

    def _lookup_many(self, cl: S.SeerrClient, keys: list) -> dict:
        """{(kind, tmdb): Title or None} for many titles: each asked for once, cached ones not at all, titles Seerr
        does not know remembered for MISSING_TTL, the rest fetched in parallel (at most LOOKUP_WORKERS at once)."""
        out, todo = {}, []
        for k in dict.fromkeys(keys):                        # each title once, in order
            hit = self.cache.get(("title",) + k, TITLE_TTL)
            if hit is not None:
                out[k] = hit
            elif self.cache.get(("missing",) + k, MISSING_TTL):
                out[k] = None
            else:
                todo.append(k)

        def one(k):
            try:
                return self._lookup(cl, *k)[0]
            except S.SeerrError as e:
                if e.code == 404:
                    self.cache.put(("missing",) + k, True)
                return None
            except S.SeerrDown:
                return None
        if len(todo) == 1:
            out[todo[0]] = one(todo[0])
        elif todo:
            with ThreadPoolExecutor(max_workers=min(LOOKUP_WORKERS, len(todo)), thread_name_prefix="seerr") as ex:
                for k, t in zip(todo, ex.map(one, todo)):
                    out[k] = t
        return out

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
        return Reply({**self._merge(t, self._finder(c)), "stale": stale})

    def _seerr_link(self, kind: str, tmdb: int) -> Optional[str]:
        base = self.cfg.get().get("baseUrl") or ""
        return f"{base}/{kind}/{tmdb}" if base else None

    def _title_page(self, c: Call) -> Reply:
        """GET /v1/discover/title/<movie|tv>/<tmdb>: everything a title page shows (details, cast, trailer, seasons
        with their status, the requests on it, download progress), cached DETAIL_TTL."""
        parts = c.path[len("/v1/discover/title/"):].split("/")
        if len(parts) != 2 or not parts[1].isdigit():
            raise ApiError(404, "use /v1/discover/title/<movie|tv>/<tmdb id>")
        kind, tmdb = self._kind_tmdb(parts[0], parts[1])
        cl = self._client()

        def fetch():
            t = S.normalize_details(cl.get(f"/{kind}/{tmdb}"), kind)
            if t is None:
                raise S.SeerrError(404, "not found")
            return t
        try:
            t, stale = self._cached(("detail", kind, tmdb), DETAIL_TTL, fetch)
        except S.SeerrDown as e:
            raise self._down(e)
        except S.SeerrError as e:
            raise self._seerr_error(e)
        return Reply({**self._merge(t, self._finder(c)), "seerrUrl": self._seerr_link(kind, tmdb), "stale": stale})

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
            out.append(r)
        titles = self._lookup_many(cl, [(r["type"], r["tmdb"]) for r in out])
        find = self._finder(c)
        for r in out:
            t = self._merge(titles.get((r["type"], r["tmdb"])), find)
            if t is not None:
                # the request list is fetched fresh each time, the title may be cached: its download progress and
                # media status are the list's
                t = {k: v for k, v in t.items() if k != "download"}
                if "download" in r:
                    t["download"] = r["download"]
                if "play" not in t and r["mediaStatus"] != "requestable":
                    t["status"] = r["mediaStatus"]
            r["title"] = t
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
                return Reply({"created": False, "title": self._merge(cur, self._finder(c))})
        else:
            open_ = [s["season"] for s in cur.get("seasons") or [] if s["status"] == "requestable"]
            want = open_ if seasons in (None, "all") else [n for n in sorted(set(seasons)) if n in open_]
            if isinstance(seasons, list):
                known = {s["season"] for s in cur.get("seasons") or []}
                if any(n not in known for n in seasons):
                    raise ApiError(400, "seasons: this show has no such season")
            if not want:
                return Reply({"created": False, "title": self._merge(cur, self._finder(c))})
            body["seasons"] = want
        try:
            raw = cl.post("/request", body, user=user)
        except S.SeerrDown as e:
            raise self._down(e)
        except S.SeerrError as e:
            if e.code == 409:                                # Seerr's own duplicate check
                return Reply({"created": False, "title": self._merge(cur, self._finder(c))})
            raise self._seerr_error(e)
        self.cache.drop(lambda k: k in (("title", kind, tmdb), ("missing", kind, tmdb), ("detail", kind, tmdb))
                        or k[0] in ("rows", "search"))
        try:
            cur = self._fetch_title(cl, kind, tmdb)
            self.cache.put(("title", kind, tmdb), cur)
        except (S.SeerrDown, S.SeerrError):
            pass
        LOG.info("Seerr request: %s %s by account %s", kind, tmdb, c.sess["account_id"])
        return Reply({"created": True, "request": S.normalize_request(raw),
                      "title": self._merge(cur, self._finder(c))}, 201)

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
                # the saved key is kept only for the same server: sending it to a new address needs it typed again
                # (an admin could otherwise point it at their own server and read the key, which nobody may see)
                if S.host_port(base) != S.host_port(old.get("baseUrl") or "") or \
                        base.split(":", 1)[0].lower() != (old.get("baseUrl") or "").split(":", 1)[0].lower():
                    raise ApiError(400, "enter the API key again for a new Seerr address")
                key = old["apiKey"]
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
        version = S._text(st.get("version"), 40) if isinstance(st, dict) else ""
        if b.get("test") is True:                            # "Test connection": the same checks, nothing saved
            return Reply({"ok": True, "baseUrl": base, "version": version})
        self.cfg.set_server(base, key)
        self.cache.clear()
        self._reach, self._down_until = (0.0, None), 0.0
        LOG.warning("Seerr settings changed by account %s (server %s)", c.sess["account_id"], base)
        return Reply({"configured": True, "baseUrl": base, "keySet": True, "version": version})

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
                            "jellyfinUsername": S._text(u.get("jellyfinUsername"), 100),
                            "admin": seerr_admin(u)})
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
        owner = c.sess.get("account_role") == "owner"
        if b.get("auto") is True:                            # link every unlinked account with one clear match
            users = self._seerr_users(self._client())
            admins = {u["id"] for u in users if u["admin"]}
            m, linked, skipped = self.cfg.mapping(), [], []
            for a in self.acc.accounts(c.sess):
                if a["id"] not in m:
                    uid = self._suggest(a, users)
                    if uid and uid in admins and not owner:  # a Seerr admin auto-approves: the owner decides
                        skipped.append({"accountId": a["id"], "seerrUserId": uid})
                    elif uid:
                        self.cfg.set_mapping(a["id"], uid)
                        linked.append({"accountId": a["id"], "seerrUserId": uid})
            return Reply({"linked": linked, "skipped": skipped})
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
            user = self._client().get(f"/user/{uid}")
        except S.SeerrDown as e:
            raise self._down(e)
        except S.SeerrError as e:
            if e.code == 404:
                raise ApiError(400, "Seerr has no user with this id")
            raise self._seerr_error(e)
        if seerr_admin(user if isinstance(user, dict) else {"id": uid}) and not owner:
            raise ApiError(403, "only the owner can link an account to a Seerr admin (their requests are approved "
                                "automatically)")
        self.cfg.set_mapping(aid, uid)
        return Reply({"accountId": aid, "seerrUserId": uid})
