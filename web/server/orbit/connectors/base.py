# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""The connector contract every media-server kind implements, and the shared plumbing.

A connector instance speaks for one linked user on one server (base URL + that user's token). Sign-in is a
pair of class methods (start, poll) plus an optional one-step password sign-in; they return ``Linked``.

Rules for implementations:
- Every request goes through ``Http`` (tvbro_server.net.open_url: SSRF checks, the per-host LAN allowlist,
  no credential headers across redirects). No vendor SDKs.
- Tokens travel in request headers, never in the URL of an API call (so they cannot end up in a log or an
  error message). The only URL that carries a token is the direct-play answer of ``playback``.
- Error messages never contain URLs, tokens or upstream bodies.

Items are normalized to one shape (``item``); artwork is returned as a server-relative path that the
/v1 image route fetches with the token (``image``), so a token never reaches a page for a picture."""
from __future__ import annotations

import calendar
import json
import logging
import posixpath
import re
import socket
import time
import urllib.error
import urllib.parse
from dataclasses import dataclass, field
from typing import Optional

from tvbro_server import net as NET

PRODUCT = "Orbit"
VERSION = "1.0"
DEVICE_NAME = "Orbit home server"
ITEM_KINDS = ("movie", "series", "season", "episode")
LIST_KINDS = ("movie", "series")
STATES = ("playing", "paused", "stopped")
PAGE = 50
MAX_PAGE = 200
JSON_LIMIT = 8 * 1024 * 1024
IMAGE_LIMIT = 5 * 1024 * 1024
IMAGE_TYPES = ("image/jpeg", "image/png", "image/webp", "image/gif")     # never SVG (script) or HTML
_ID = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
LOG = logging.getLogger("orbit.media.http")
SNIPPET = 300                         # bytes of an upstream error body kept for the log (redacted)
_SECRET_RE = re.compile(r"(?i)(x-plex-token|x-emby-token|x-mediabrowser-token|api_key|apikey|access_?token|authtoken|"
                        r"token)(\"?\s*[=:]\s*\"?)[^&\s\"'<>,;]*")


def redact(text, secrets_: tuple = ()) -> str:
    """A short, single-line, token-free version of an upstream body for the log."""
    s = text.decode("utf-8", "replace") if isinstance(text, (bytes, bytearray)) else str(text or "")
    for t in secrets_:
        if t and len(t) >= 6:
            s = s.replace(t, "REDACTED")
    s = _SECRET_RE.sub(lambda m: m.group(1) + m.group(2) + "REDACTED", s)
    s = re.sub(r"[\x00-\x1f\x7f]+", " ", s).strip()
    return s[:SNIPPET]


def error_body(e) -> bytes:
    """Up to a few KB of an HTTPError's body (never raises)."""
    try:
        return e.read(4096) or b""
    except Exception:                         # noqa: BLE001
        return b""


class ConnectorError(Exception):
    """``code`` is the /v1 status to answer: 400 bad input, 404 not on the server, 409 the link needs a new
    sign-in (``relink``) or the server refuses the action, 502 the server could not be reached / answered oddly."""

    def __init__(self, code: int, msg: str, relink: bool = False, status: int = 0, body: str = ""):
        super().__init__(msg)
        self.code, self.msg, self.relink = code, msg, relink
        self.status, self.body = status, body          # the server's HTTP status and redacted body (for the log)


@dataclass
class Linked:
    """A finished sign-in: what is stored for one connection (the token goes to the vault, not the table)."""
    base_url: str
    server_name: str
    server_id: str
    user_id: str
    user_name: str
    token: str = field(repr=False)


def safe_id(v, what: str = "id") -> str:
    """Item / library ids are opaque but must be harmless in a URL path: letters, digits, '-' and '_'."""
    s = str(v if v is not None else "").strip()
    if not _ID.match(s):
        raise ConnectorError(400, f"{what}: not a valid id")
    return s


_PATH_SAFE = "/-._~:+,;=!$&'()*"           # left as they are when a canonical path is encoded again


def canonical_path(path, prefixes: tuple = ("/",), ignore_case: bool = False, relative: bool = False) -> Optional[str]:
    """The canonical, re-encoded form of an upstream path (no query), or None when it is not acceptable.

    The path is percent-decoded until it no longer changes (so ``%252e%252e`` is seen as ``..``, as a server that
    decodes it would); an encoded slash (``%2f``) or backslash, NUL / control characters, ``@``, ``#`` and any
    ``.`` or ``..`` segment are refused; the result is collapsed with posixpath, checked against ``prefixes``
    again and percent-encoded once, which is what is sent upstream. ``relative``: a path that follows a fixed
    prefix (no leading slash; ``prefixes`` is not used)."""
    p = path if isinstance(path, str) else ""
    if not p or len(p) > 1000 or any(ord(c) < 33 or ord(c) == 127 for c in p):
        return None
    cur = p
    for _ in range(6):
        if re.search(r"%(2f|5c|00)", cur, re.I):
            return None
        try:
            nxt = urllib.parse.unquote(cur, errors="strict") if "%" in cur else cur
        except UnicodeDecodeError:
            return None
        if nxt == cur:
            break
        cur = nxt
    else:
        return None                           # still changing: encoded more often than any real client does
    if ("\\" in cur or "@" in cur or "#" in cur or "?" in cur or "%" in cur
            or any(ord(c) < 32 or ord(c) == 127 for c in cur)):      # an encoded space is fine (sent as %20)
        return None
    segs = cur.split("/")
    if any(sg in (".", "..") for sg in segs):
        return None
    if relative:
        if cur.startswith("/") or "//" in cur:
            return None
        canon = posixpath.normpath(cur)
        if canon.startswith(("/", "..")) or canon == ".":
            return None
    else:
        if not cur.startswith("/") or "//" in cur:
            return None
        canon = posixpath.normpath(cur)
        if cur.endswith("/") and not canon.endswith("/"):
            canon += "/"
        low = canon.lower() if ignore_case else canon
        if not any(low.startswith(px.lower() if ignore_case else px) for px in prefixes):
            return None
    return urllib.parse.quote(canon, safe=_PATH_SAFE)


def safe_path(path, prefixes: tuple) -> str:
    """A server-relative artwork path the image route may fetch: one of the connector's prefixes, no host,
    no '..' (also percent-encoded), no control characters, no credentials in the query. Returns the canonical,
    re-encoded path (plus the query as given)."""
    p = path if isinstance(path, str) else ""
    low = p.lower()
    if (not p or len(p) > 600 or not p.startswith(prefixes) or "//" in p or ".." in p or "\\" in p or "@" in p
            or "#" in p or any(ord(c) < 33 or ord(c) == 127 for c in p)
            or any(k in low for k in ("token", "api_key", "apikey"))):
        raise ConnectorError(400, "path: not an artwork path of this server")
    base, sep, query = p.partition("?")
    canon = canonical_path(base, prefixes)
    if canon is None:
        raise ConnectorError(400, "path: not an artwork path of this server")
    return canon + sep + query


def normalize_base_url(url) -> str:
    """scheme://host[:port][/path] without a trailing slash; no login, query or fragment."""
    s = url.strip() if isinstance(url, str) else ""
    if s and "://" not in s:
        s = "http://" + s
    try:
        u = urllib.parse.urlsplit(s)
        port = u.port
    except ValueError:
        raise ConnectorError(400, "baseUrl: not a valid address")
    if (u.scheme.lower() not in ("http", "https") or not u.hostname or u.username or u.password or u.query
            or u.fragment or len(s) > 300):
        raise ConnectorError(400, "baseUrl: http(s)://host:port, without a login or query")
    host = u.hostname.lower()
    if ":" in host:
        host = f"[{host}]"
    netloc = host + (f":{port}" if port else "")
    out = urllib.parse.urlunsplit((u.scheme.lower(), netloc, u.path.rstrip("/"), "", ""))
    try:
        NET.check_url(out)
    except NET.Blocked as e:
        raise ConnectorError(400, f"baseUrl: {e.why}")
    return out


def host_port(base_url: str) -> Optional[tuple]:
    """(host, port) of a base URL as net.set_private_hosts expects it, or None."""
    try:
        u = urllib.parse.urlsplit(base_url)
        return ((u.hostname or "").lower(), u.port or (443 if u.scheme.lower() == "https" else 80)) if u.hostname else None
    except ValueError:
        return None


def ticks(v) -> float:
    try:
        return max(0.0, float(v or 0))
    except (TypeError, ValueError):
        return 0.0


def item(id: str, kind: str, title: str, **kw) -> dict:
    """The one item shape of the /v1 media API. ``poster`` / ``backdrop`` are server-relative artwork paths
    here; orbit/media.py turns them into /v1/media/image URLs. Times are seconds."""
    prog = kw.get("progress") or {}
    return {
        "id": id, "kind": kind, "title": title or "",
        "year": kw.get("year"), "overview": kw.get("overview") or "",
        "poster": kw.get("poster"), "backdrop": kw.get("backdrop"),
        "runtime": int(kw.get("runtime") or 0) or None, "rating": kw.get("rating"),
        "guids": {"tmdb": kw.get("tmdb"), "imdb": kw.get("imdb"), "tvdb": kw.get("tvdb")},
        "progress": {"secs": int(prog.get("secs") or 0), "dur": int(prog.get("dur") or 0),
                     "watched": bool(prog.get("watched"))},
        "seriesId": kw.get("seriesId"), "season": kw.get("season"), "episode": kw.get("episode"),
        # for the catalogue (orbit/catalog.py): genre names, when it was added (Unix seconds), release date
        # ("YYYY-MM-DD"), two backdrop colours ("#rrggbb", Plex UltraBlur) and when the user last played it
        "genres": [str(g)[:60] for g in (kw.get("genres") or []) if g][:10],
        "added": int(ticks(kw.get("added"))) or None, "released": _date(kw.get("released")),
        "colors": kw.get("colors"), "lastViewed": int(ticks(kw.get("lastViewed"))) or None,
    }


def _date(v) -> Optional[str]:
    s = str(v or "")[:10]
    return s if re.fullmatch(r"(19|20)\d\d-\d\d-\d\d", s) else None


def iso_time(v) -> Optional[int]:
    """Unix seconds of an ISO 8601 time ("2024-05-01T12:00:00.0000000Z"), or None."""
    s = str(v or "")
    try:
        return calendar.timegm(time.strptime(s[:19], "%Y-%m-%dT%H:%M:%S"))
    except ValueError:
        return None


_HEX = re.compile(r"^[0-9a-fA-F]{6}$")


def colors(c1, c2) -> Optional[list]:
    """["#rrggbb", "#rrggbb"] from two hex colours (with or without "#"), else None."""
    out = []
    for c in (c1, c2):
        s = str(c or "").lstrip("#")
        if not _HEX.match(s):
            return None
        out.append("#" + s.lower())
    return out


def direct_ok(caps, container, vcodec=None, acodec=None) -> bool:
    """May a device play the original file? ``caps`` (optional, from the device): {"directPlay": false} never
    ("direct": false is read the same way);
    "containers" / "videoCodecs" / "audioCodecs" lists, when given, must contain the file's. Without caps:
    yes (ExoPlayer and mpv play most files)."""
    if not isinstance(caps, dict):
        return True
    if caps.get("directPlay") is False or caps.get("direct") is False:
        return False
    for name, val in (("containers", container), ("videoCodecs", vcodec), ("audioCodecs", acodec)):
        lst = caps.get(name)
        if isinstance(lst, list) and lst and val:
            have = {str(x).strip().lower() for x in lst}
            if not any(v.strip() in have for v in str(val).lower().split(",")):
                return False
    return True


MIME = {"mp4": "video/mp4", "m4v": "video/mp4", "mov": "video/quicktime", "mkv": "video/x-matroska",
        "webm": "video/webm", "avi": "video/x-msvideo", "ts": "video/mp2t", "mpegts": "video/mp2t",
        "wmv": "video/x-ms-wmv", "flv": "video/x-flv"}


class Http:
    """JSON over net.open_url with error mapping; ``label`` names the server kind in messages."""

    def __init__(self, label: str, timeout: float = 15, opener=None):
        self.label, self.timeout = label, timeout
        self.opener = opener or NET.open_url
        self.secrets: tuple = ()             # the connector's token(s): removed from logged bodies

    def raw(self, method: str, url: str, headers: Optional[dict] = None, body=None, limit: int = JSON_LIMIT,
            timeout: Optional[float] = None) -> tuple:
        """(status, content type, bytes). ``body``: a dict / list is sent as JSON."""
        hdr = dict(headers or {})
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            hdr.setdefault("Content-Type", "application/json")
        elif method in ("POST", "PUT"):
            data = b""
        try:
            with self.opener(url, timeout=timeout or self.timeout, headers=hdr, data=data, method=method) as r:
                return r.status, r.headers.get("Content-Type") or "", NET.read_capped(r, limit)
        except urllib.error.HTTPError as e:
            body = redact(error_body(e), self.secrets)
            e.close()
            # which request (method and path, never the host or query) and what the server said
            LOG.warning("%s: %s %s answered %d: %s", self.label, method, urllib.parse.urlsplit(url).path[:120],
                        e.code, body or "(empty body)")
            if e.code in (401, 403):
                raise ConnectorError(409, f"{self.label}: the sign-in is no longer valid; link it again", relink=True,
                                     status=e.code, body=body)
            if e.code == 404:
                raise ConnectorError(404, f"{self.label}: not found on the server", status=e.code, body=body)
            raise ConnectorError(502, f"{self.label}: the server answered {e.code}", status=e.code, body=body)
        except ValueError:                    # read_capped: too large
            raise ConnectorError(502, f"{self.label}: the answer is too large")
        except (urllib.error.URLError, socket.timeout, OSError) as e:
            if NET.is_blocked(e):
                raise ConnectorError(502, f"{self.label}: that address is not allowed (local network, loopback or "
                                          "link-local); the owner can link the server first or allow local sources")
            raise ConnectorError(502, f"{self.label}: the server did not answer")

    def json(self, method: str, url: str, headers: Optional[dict] = None, body=None, **kw):
        _, _, data = self.raw(method, url, {"Accept": "application/json", **(headers or {})}, body, **kw)
        if not data.strip():
            return None
        try:
            return json.loads(data)
        except ValueError:
            raise ConnectorError(502, f"{self.label}: unexpected answer from the server")


class Connector:
    """One linked user on one server. Subclasses set ``kind``, ``label`` and ``IMAGE_PREFIXES``."""
    kind = ""
    label = ""
    IMAGE_PREFIXES: tuple = ()

    def __init__(self, base_url: str, token: str, user_id: str, device_id: str, http: Optional[Http] = None):
        self.base = base_url.rstrip("/")
        self.token = token
        self.user_id = user_id
        self.device_id = device_id
        self.http = http or Http(self.label)
        if token:
            self.http.secrets = tuple({*self.http.secrets, token})

    def __repr__(self):                       # never the token
        return f"<{type(self).__name__} {self.base}>"

    # ---- sign-in (class level: there is no token yet) ----
    @classmethod
    def sign_in_start(cls, http: Http, base_url: Optional[str], device_id: str) -> tuple:
        """(answer for the client, private state kept in memory for polling)."""
        raise NotImplementedError

    @classmethod
    def sign_in_poll(cls, http: Http, state: dict, device_id: str) -> Optional[list]:
        """None while waiting; else [Linked] (Plex can link several servers with one sign-in)."""
        raise NotImplementedError

    @classmethod
    def sign_in_password(cls, http: Http, base_url: str, username: str, password: str, device_id: str) -> Linked:
        raise ConnectorError(400, f"{cls.label} links with a code, not a password")

    def sign_out(self) -> None:
        """Best effort: end this link's token on the server (when the server has such a call)."""

    # ---- reading ----
    def health(self) -> dict:
        """{"ok": True, "name", "version"} or raises ConnectorError."""
        raise NotImplementedError

    def list_libraries(self) -> list:
        """[{"id", "title", "kind": "movie"|"series"|"other"}]"""
        raise NotImplementedError

    def list_items(self, library: str, cursor: Optional[str], kind: Optional[str], limit: int = PAGE) -> dict:
        """{"items": [item], "next": cursor or None, "total"}"""
        raise NotImplementedError

    def get_item(self, item_id: str) -> dict:
        raise NotImplementedError

    def get_seasons(self, series_id: str) -> list:
        raise NotImplementedError

    def get_episodes(self, season_id: str) -> list:
        raise NotImplementedError

    def children(self, item_id: str) -> list:
        """Seasons of a series, episodes of a season."""
        it = self.get_item(item_id)
        if it["kind"] == "series":
            return self.get_seasons(item_id)
        if it["kind"] == "season":
            return self.get_episodes(item_id)
        return []

    # ---- playing ----
    def playback(self, item_id: str) -> dict:
        """{"url", "headers": {}, "direct": bool, "container", "resumeSecs"}. The URL carries this user's token."""
        raise NotImplementedError

    def report_progress(self, item_id: str, secs: float, dur: float, state: str) -> None:
        raise NotImplementedError

    def mark_watched(self, item_id: str, watched: bool) -> None:
        raise NotImplementedError

    # ---- playing through the home server (orbit/mediaplay.py) ----
    # Paths are relative to ``self.base`` and never carry the token (the relay sends ``auth_headers``).
    RELAY_PREFIXES: tuple = ()          # upstream paths an HLS playlist of this server may point at

    def play_info(self, item_id: str, hls: bool, caps: Optional[dict], audio: Optional[str] = None,
                  start: Optional[float] = None) -> dict:
        """What to relay for one film / episode:
        {"kind": "hls"|"file", "path", "container", "durationSecs", "resumeSecs", "title", "subtitle",
         "audio": [{id, label, lang, default}], "subtitles": [{id, label, lang, path, fmt: "srt"|"vtt"}],
         "seriesId", "season", "episode", "ref": {...}}. ``hls``: the client asked for HLS (the web);
        else the original file when ``caps`` allow it. ``ref`` is kept for ``play_report`` / ``play_stop``.
        ``start``: where the player will start (seconds; None: the resume point), so a transcode starts there."""
        raise ConnectorError(400, f"{self.label}: playing through the home server is not supported")

    def play_report(self, ref: dict, item_id: str, secs: float, dur: float, state: str, first: bool) -> None:
        """Progress of a relayed playback (``first``: the first report of this playback)."""
        self.report_progress(item_id, secs, dur, state)

    def play_stop(self, ref: dict) -> None:
        """Best effort: end the server's transcode of a relayed playback."""

    def explain(self, status: int, body: str, what: str) -> Optional[str]:
        """A plain-English reason for a failed relay request (``what``: "playlist", "segment", "file",
        "subtitles"), from the server's status and redacted body, or None for the generic message."""
        return None

    def play_cleanup(self, live: set) -> int:
        """Best effort, after the server refused to start a transcode: stop this server's transcodes that were
        started by this connection (its client identifier) and are not in ``live`` (the ``ref["session"]`` of the
        playbacks Orbit still has, e.g. left over from before a restart). Returns how many were stopped."""
        return 0

    def next_episode(self, series_id: Optional[str], season, episode) -> Optional[dict]:
        """The episode after (season, episode) of a series, into the next season, or None."""
        if not series_id or season is None or episode is None:
            return None
        try:
            seasons = sorted((s for s in self.get_seasons(series_id) if s.get("season") is not None),
                             key=lambda s: s["season"])
        except ConnectorError:
            return None
        for s in seasons:
            if s["season"] < season:
                continue
            try:
                eps = sorted((e for e in self.get_episodes(s["id"]) if e.get("episode") is not None),
                             key=lambda e: e["episode"])
            except ConnectorError:
                return None
            for e in eps:
                if s["season"] > season or e["episode"] > episode:
                    return e
        return None

    # ---- the catalogue (orbit/catalog.py) ----
    def collections(self) -> list:
        """[{"id", "title", "kind": "movie"|"series"|None, "poster", "backdrop", "colors", "items": [item id]}]:
        the server's own collections (Plex collections, Jellyfin box sets)."""
        return []

    def continue_items(self) -> list:
        """[item]: films in progress and next episodes (with ``seriesId``) for this user, newest first."""
        return []

    def image(self, path: str) -> tuple:
        """(bytes, content type) of an artwork path, fetched with the token; images only, capped."""
        p = safe_path(path, self.IMAGE_PREFIXES)
        _, ctype, data = self.http.raw("GET", self.base + p, self.auth_headers(), limit=IMAGE_LIMIT)
        ct = ctype.split(";", 1)[0].strip().lower()
        if ct not in IMAGE_TYPES:
            raise ConnectorError(502, f"{self.label}: that is not an image")
        return data, ct

    def auth_headers(self) -> dict:
        raise NotImplementedError

    @staticmethod
    def page(cursor: Optional[str], limit) -> tuple:
        try:
            start = int(cursor or 0)
            n = int(limit or PAGE)
        except (TypeError, ValueError):
            raise ConnectorError(400, "cursor / limit: numbers")
        if start < 0 or start > 10_000_000 or not 1 <= n <= MAX_PAGE:
            raise ConnectorError(400, f"cursor / limit: out of range (limit 1 to {MAX_PAGE})")
        return start, n
