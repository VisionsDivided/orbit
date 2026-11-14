# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""Seerr (the successor of Overseerr / Jellyseerr): settings, REST client, cache and title normalization.

Written from Seerr's public API documentation (REST under ``/api/v1``, ``X-Api-Key`` header; the admin key acts
for another Seerr user with ``X-Api-User: <user id>``, so that user's permissions, approval rules and quotas apply).

Settings (base URL, API key) and the Orbit account -> Seerr user mapping live in one Fernet-encrypted file in the
data folder, ``seerr.enc``, sealed with the server key like the provider logins (so ``rotate_key`` and the
startup key check cover it). Nothing here is in ``orbit.db``: no migration number is needed. The API key is never
returned to a client, never put in a URL and never logged.

Outbound calls go through tvBro's ``net`` rules (http/https, checked addresses, no DNS rebinding). The configured
Seerr host is the only extra host allowed to resolve to a LAN address: ``SeerrConfig.private_hosts()`` is meant
to be composed into ``net.set_private_hosts`` by the app together with the other configured media servers."""
from __future__ import annotations

import contextlib
import json
import logging
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import OrderedDict
from typing import Any, Callable, Optional

from tvbro_server import net as NET
from tvbro_server.secfile import write_secret
from tvbro_server.secretstore import SecretStore

LOG = logging.getLogger("orbit.seerr")

FILE = "seerr.enc"
TIMEOUT = 8
MAX_BYTES = 4 * 1024 * 1024
TMDB_IMG = "https://image.tmdb.org/t/p/"          # public CDN: clients load posters from TMDB directly
_IMG_PATH = re.compile(r"^/[A-Za-z0-9_-]{1,100}\.(?:jpg|jpeg|png|webp|svg)$")

# mediaInfo.status (Seerr MediaStatus) -> Orbit status. Anything else (none, 1 unknown, newer codes such as
# "blacklisted" / "deleted") counts as requestable; Seerr itself refuses a request it does not allow.
MEDIA_STATUS = {2: "pending", 3: "processing", 4: "partial", 5: "available"}
# MediaRequest.status -> Orbit request status
REQUEST_STATUS = {1: "pending", 2: "approved", 3: "declined", 4: "failed", 5: "completed"}
TYPES = ("movie", "tv")


class SeerrError(Exception):
    """Seerr answered with an error (``code`` is Seerr's HTTP status)."""

    def __init__(self, code: int, msg: str):
        super().__init__(msg)
        self.code, self.msg = code, msg


class SeerrDown(Exception):
    """Seerr could not be reached (connection refused, timeout, 5xx, garbage)."""


# ---------------------------------------------------------------- settings
def clean_base_url(v) -> str:
    """The Seerr address as typed, without a trailing ``/`` or ``/api/v1``. Raises ValueError."""
    s = v.strip() if isinstance(v, str) else ""
    if not s or len(s) > 300:
        raise ValueError("baseUrl: the Seerr address, for example http://192.0.2.20:5055")
    s = NET.lower_host(s.rstrip("/"))
    if s.lower().endswith("/api/v1"):
        s = s[:-7].rstrip("/")
    try:
        u = NET.check_url(s)
    except NET.Blocked as e:
        raise ValueError(f"baseUrl: {e.why}")
    if u.query or u.fragment or u.username or u.password:
        raise ValueError("baseUrl: no login, query or fragment in the address")
    return s


def clean_api_key(v) -> str:
    s = v.strip() if isinstance(v, str) else ""
    if not 8 <= len(s) <= 200 or any(c.isspace() or ord(c) < 33 or ord(c) > 126 for c in s):
        raise ValueError("apiKey: Seerr's API key (Settings > General in Seerr)")
    return s


def host_port(base_url: str) -> Optional[tuple]:
    try:
        u = urllib.parse.urlparse(base_url)
        return (u.hostname.lower(), u.port or (443 if u.scheme.lower() == "https" else 80))
    except (ValueError, AttributeError):
        return None


class SeerrConfig:
    """``seerr.enc``: {"baseUrl", "apiKey", "map": {orbit account id: seerr user id}}."""

    def __init__(self, data_dir: str, store_factory: Callable[[str, str], Any] = SecretStore):
        self._factory, self._dir = store_factory, data_dir
        self._store = None
        self._lock = threading.Lock()
        self._data: Optional[dict] = None

    def _st(self):
        if self._store is None:
            self._store = self._factory(self._dir, FILE)
        return self._store

    def _load(self) -> dict:
        if self._data is None:
            st = self._st()
            raw = None
            try:
                with open(st.enc_path, "rb") as f:
                    raw = st.open_bytes(f.read())
            except OSError:
                pass
            try:
                d = json.loads(raw.decode()) if raw else {}
            except ValueError:
                d = {}
            self._data = d if isinstance(d, dict) else {}
        return self._data

    def _save(self, d: dict) -> None:
        st = self._st()
        write_secret(st.enc_path, st.seal_bytes(json.dumps(d).encode()))
        self._data = d

    def get(self) -> dict:
        with self._lock:
            return json.loads(json.dumps(self._load()))      # a copy

    def configured(self) -> bool:
        d = self.get()
        return bool(d.get("baseUrl") and d.get("apiKey"))

    def set_server(self, base_url: str, api_key: str) -> None:
        with self._lock:
            d = dict(self._load())
            d["baseUrl"], d["apiKey"] = base_url, api_key
            self._save(d)

    def clear_server(self) -> None:
        with self._lock:
            d = dict(self._load())
            d.pop("baseUrl", None)
            d.pop("apiKey", None)
            self._save(d)

    def mapping(self) -> dict:
        return dict(self.get().get("map") or {})

    def seerr_user(self, account_id: str) -> Optional[int]:
        v = self.mapping().get(account_id)
        return v if isinstance(v, int) and v > 0 else None

    def set_mapping(self, account_id: str, seerr_user: Optional[int]) -> None:
        with self._lock:
            d = dict(self._load())
            m = dict(d.get("map") or {})
            if seerr_user:
                m[account_id] = int(seerr_user)
            else:
                m.pop(account_id, None)
            d["map"] = m
            self._save(d)

    # the LAN allowlist (net.set_private_hosts): the saved Seerr host only
    def private_hosts(self) -> set:
        try:
            with self._lock:
                base = self._load().get("baseUrl") or ""
            hp = host_port(base)
        except Exception:                    # noqa: BLE001 (never break a fetch because of this)
            hp = None
        return {hp} if hp else set()

    @staticmethod
    @contextlib.contextmanager
    def probing(base_url: str):
        """Allow ``base_url``'s host on the LAN while it is being tested (before it is saved): only for the calls
        made inside this block by this request's thread (``net.private_host_here``), never for other requests."""
        hp = host_port(base_url)
        if hp is None:
            yield
            return
        with NET.private_host_here(*hp):
            yield


# ---------------------------------------------------------------- client
def _open(url: str, headers: dict, data: Optional[bytes] = None, method: str = "GET"):
    """net.open_url with a method and a body (net.open_url itself is GET-only): same URL and address rules."""
    url = NET.lower_host(url)
    NET.check_url(url)
    req = urllib.request.Request(url, data=data, method=method, headers={"User-Agent": NET.UA, **headers})
    return NET._OPENER.open(req, timeout=TIMEOUT)


class SeerrClient:
    def __init__(self, base_url: str, api_key: str):
        self.base = base_url.rstrip("/") + "/api/v1"
        self._key = api_key

    def __repr__(self):                      # never show the key
        return f"SeerrClient({self.base!r})"

    def call(self, method: str, path: str, params: Optional[dict] = None, body: Optional[dict] = None,
             user: Optional[int] = None) -> Any:
        q = ""
        if params:
            # Seerr wants %20 for spaces (not +) in the search query
            q = "?" + "&".join(f"{urllib.parse.quote(str(k))}={urllib.parse.quote(str(v), safe='')}"
                               for k, v in params.items() if v is not None)
        hdr = {"X-Api-Key": self._key, "Accept": "application/json"}
        if user:
            hdr["X-Api-User"] = str(int(user))
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            hdr["Content-Type"] = "application/json"
        try:
            with _open(self.base + path + q, hdr, data, method) as r:
                raw = NET.read_capped(r, MAX_BYTES)
        except urllib.error.HTTPError as e:
            msg = ""
            try:
                msg = str(json.loads(NET.read_capped(e, 65536).decode("utf-8", "replace")).get("message") or "")
            except Exception:                # noqa: BLE001
                pass
            if e.code >= 500:
                raise SeerrDown(f"Seerr answered {e.code}")
            msg = "".join(c for c in msg if c.isprintable())[:200]
            raise SeerrError(e.code, msg or f"Seerr answered {e.code}")
        except NET.Blocked as e:
            raise SeerrError(0, f"the Seerr address is not allowed ({e.why})")
        except (urllib.error.URLError, OSError, ValueError) as e:
            if NET.is_blocked(e):
                raise SeerrError(0, "the Seerr address is not allowed (local network, loopback or link-local)")
            raise SeerrDown(type(e).__name__)
        if not raw.strip():
            return {}
        try:
            return json.loads(raw.decode("utf-8"))
        except ValueError:
            raise SeerrDown("Seerr did not answer with JSON")

    def get(self, path, params=None, user=None):
        return self.call("GET", path, params, None, user)

    def post(self, path, body, user=None):
        return self.call("POST", path, None, body, user)


# ---------------------------------------------------------------- cache
class TTLCache:
    """Bounded LRU of (time, value). ``get`` gives a fresh value, ``stale`` any value still kept."""

    def __init__(self, size: int = 256, clock: Callable[[], float] = time.time):
        self.size, self.clock = size, clock
        self._d: OrderedDict = OrderedDict()
        self._lock = threading.Lock()

    def get(self, key, max_age: float):
        with self._lock:
            hit = self._d.get(key)
            if hit is None or self.clock() - hit[0] > max_age:
                return None
            self._d.move_to_end(key)
            return hit[1]

    def stale(self, key):
        with self._lock:
            hit = self._d.get(key)
            return None if hit is None else hit[1]

    def put(self, key, value) -> None:
        with self._lock:
            self._d[key] = (self.clock(), value)
            self._d.move_to_end(key)
            while len(self._d) > self.size:
                self._d.popitem(last=False)

    def drop(self, pred: Callable[[Any], bool]) -> None:
        with self._lock:
            for k in [k for k in self._d if pred(k)]:
                del self._d[k]

    def clear(self) -> None:
        with self._lock:
            self._d.clear()


# ---------------------------------------------------------------- normalization
def image(path, size: str) -> Optional[str]:
    return TMDB_IMG + size + path if isinstance(path, str) and _IMG_PATH.match(path) else None


def _int(v) -> Optional[int]:
    return v if isinstance(v, int) and not isinstance(v, bool) else None


def _year(d) -> Optional[int]:
    return int(d[:4]) if isinstance(d, str) and len(d) >= 4 and d[:4].isdigit() else None


def _text(v, n: int) -> str:
    return v[:n] if isinstance(v, str) else ""


def media_status(info) -> str:
    st = _int((info or {}).get("status")) if isinstance(info, dict) else None
    return MEDIA_STATUS.get(st, "requestable")


def latest_request(info) -> Optional[dict]:
    reqs = (info or {}).get("requests") if isinstance(info, dict) else None
    reqs = [r for r in reqs if isinstance(r, dict)] if isinstance(reqs, list) else []
    if not reqs:
        return None
    return max(reqs, key=lambda r: (str(r.get("createdAt") or ""), _int(r.get("id")) or 0))


def _seasons(raw: dict, info) -> list:
    """TV seasons (specials left out): [{season, episodes, name, status}] with each season's own status."""
    info = info if isinstance(info, dict) else {}
    have = {}
    for s in info.get("seasons") or []:
        if isinstance(s, dict) and _int(s.get("seasonNumber")) is not None:
            have[s["seasonNumber"]] = MEDIA_STATUS.get(_int(s.get("status")), "requestable")
    asked = set()
    for r in info.get("requests") or []:
        if isinstance(r, dict) and _int(r.get("status")) in (1, 2):
            for s in r.get("seasons") or []:
                if isinstance(s, dict) and _int(s.get("seasonNumber")) is not None:
                    asked.add(s["seasonNumber"])
    out = []
    for s in raw.get("seasons") or []:
        n = _int(s.get("seasonNumber")) if isinstance(s, dict) else None
        if n is None or n == 0:
            continue
        st = have.get(n, "requestable")
        if st == "requestable" and n in asked:
            st = "pending"
        out.append({"season": n, "episodes": _int(s.get("episodeCount")) or 0, "name": _text(s.get("name"), 100),
                    "status": st})
    return out


def normalize(raw, kind: Optional[str] = None, details: bool = False) -> Optional[dict]:
    """A Seerr search / discover result or movie / tv details -> Orbit's title object (None for people etc.)."""
    if not isinstance(raw, dict):
        return None
    t = raw.get("mediaType") or kind
    tmdb = _int(raw.get("id"))
    if t not in TYPES or not tmdb or tmdb < 0:
        return None
    info = raw.get("mediaInfo")
    title = raw.get("title") if t == "movie" else raw.get("name")
    date = raw.get("releaseDate") if t == "movie" else raw.get("firstAirDate")
    va = raw.get("voteAverage")
    out = {"type": t, "tmdb": tmdb, "title": _text(title, 300) or _text(raw.get("originalTitle") or
                                                                         raw.get("originalName"), 300),
           "year": _year(date), "overview": _text(raw.get("overview"), 2000),
           "poster": image(raw.get("posterPath"), "w342"), "backdrop": image(raw.get("backdropPath"), "w1280"),
           "rating": round(float(va), 1) if isinstance(va, (int, float)) and not isinstance(va, bool) else None,
           "status": media_status(info)}
    req = latest_request(info)
    if req is not None and _int(req.get("status")) in REQUEST_STATUS:
        out["requestStatus"] = REQUEST_STATUS[req["status"]]
    if t == "tv" and details:
        out["seasons"] = _seasons(raw, info)
    return out


def normalize_request(raw) -> Optional[dict]:
    if not isinstance(raw, dict):
        return None
    media = raw.get("media") if isinstance(raw.get("media"), dict) else {}
    t = raw.get("type") or media.get("mediaType")
    tmdb = _int(media.get("tmdbId"))
    if t not in TYPES or not tmdb:
        return None
    by = raw.get("requestedBy") if isinstance(raw.get("requestedBy"), dict) else {}
    seasons = sorted(s["seasonNumber"] for s in raw.get("seasons") or []
                     if isinstance(s, dict) and _int(s.get("seasonNumber")) is not None)
    out = {"id": _int(raw.get("id")), "type": t, "tmdb": tmdb,
           "status": REQUEST_STATUS.get(_int(raw.get("status")), "unknown"),
           "mediaStatus": MEDIA_STATUS.get(_int(media.get("status")), "requestable"),
           "created": _text(raw.get("createdAt"), 40),
           "requestedBy": {"seerrUserId": _int(by.get("id")), "name": _text(by.get("displayName"), 100)}}
    if t == "tv":
        out["seasons"] = seasons
    return out
