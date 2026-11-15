# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""Plex / Jellyfin playback through the home server (docs/orbit/API-v1.md, "Media servers" > "Playback").

``POST /v1/media/play`` starts a play session: the connector says what to play (the original file for a TV or the
desktop app when it can, else the server's HLS transcode; always HLS for the web) and the device gets
``/v1/media/stream/<session>/...`` addresses on this server. The relay below fetches them from the media server with
the token in a request header, so no Plex / Jellyfin token reaches a device and pages stay same-origin.

- ``.../file``: the original file, ``Range`` passed on, piped in small chunks (backpressure from the device).
- ``.../master.m3u8``: the transcode's playlist; every URI in it (and in the playlists it points at) is rewritten to
  ``.../u/<path on the media server>?<query without tokens>``, which is relayed only for paths under the
  connector's ``RELAY_PREFIXES`` on that same server.
- ``.../sub/<id>.vtt``: a text subtitle stream as WebVTT (SRT is converted).

The session id (192 random bits) is the capability: players that send no cookie (ExoPlayer, mpv) can use the
addresses. A session ends on ``state: "stopped"``; when it is abandoned (no relay request and no progress report for
``IDLE_STOP``: a device that went to sleep or was switched off without stopping); when the same account starts the
same title again (another device continues it: "handoff"); when its connection is unlinked; when the signed-in
session that created it ends (checked on every request and while a relay runs); and after ``IDLE`` at the most.
An abandoned, handed-off or evicted session is reported "stopped" at its last known position before its transcode
is stopped, so the media server keeps the resume point and frees the transcoder.

When the media server refuses a request, its HTTP status and a short redacted body are logged with which request
it was (decision, playlist, segment, file, subtitles), and the client gets a plain-English reason
(``Connector.explain``). Nothing here logs a URL or a play session id."""
from __future__ import annotations

import json
import logging
import re
import secrets
import threading
import time
import urllib.error
import urllib.parse
from dataclasses import dataclass, field
from typing import Callable, Optional

from tvbro_server import quality as Q

from .accounts import ApiError
from .connectors.base import MIME, ConnectorError, canonical_path, error_body, redact, safe_id
from .facade import rewrite_hls

LOG = logging.getLogger("orbit.mediaplay")

PREFIX = "/v1/media/stream/"
IDLE = 3 * 3600                       # the outer cap: no session lives longer than this without requests
IDLE_STOP = 90                        # abandoned: no relay request and no progress for this long (players report
                                      # every 10 s, also while paused)
SWEEP = 15                            # how often abandoned sessions are looked for
MAX_PER_ACCOUNT = 8                   # older sessions of the account are ended beyond this
CHUNK = 64 * 1024
CHECK_EVERY = 32                      # chunks between "is the signed-in session still there?" checks
MAX_HLS = 8 * 1024 * 1024
MAX_SUB = 5 * 1024 * 1024
CLIENTS = ("web", "tv", "desktop")
SID_RE = re.compile(r"^[A-Za-z0-9_-]{32}$")
SUBID_RE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
TOKEN_KEYS = ("x-plex-token", "api_key", "apikey", "token", "x-emby-token", "x-mediabrowser-token")
MEDIA_CTYPES = ("video/", "audio/", "application/octet-stream", "binary/octet-stream", "application/mp4")
HLS_CTYPE = "application/vnd.apple.mpegurl"


@dataclass
class Play:
    id: str
    account: str
    sess_h: str
    conn: str
    source: str
    item: str
    kind: str                         # "hls" | "file"
    path: str                         # the file / master playlist on the media server (no token)
    prefixes: tuple
    subs: dict                        # subtitle id -> (path, fmt)
    connector: object = field(repr=False)
    ref: dict = field(default_factory=dict)
    duration: float = 0
    seen: float = 0
    reported: bool = False
    client: str = ""
    pos: float = 0                    # the last reported position
    state: str = ""                   # the last reported state
    pipes: int = 0                    # file relays running now (a paused player may hold one, blocked)
    lock: threading.Lock = field(default_factory=threading.Lock, repr=False)

    def label(self) -> str:
        """For the log: never the session id (it is the capability)."""
        return f"{self.source} {self.conn} item {self.item} ({self.client}, {self.kind})"


def srt_to_vtt(text: str) -> str:
    """SubRip as WebVTT: a header, '.' for the millisecond comma; cue numbers may stay."""
    body = text.lstrip("\ufeff").replace("\r\n", "\n").replace("\r", "\n")
    body = re.sub(r"(\d\d:\d\d:\d\d),(\d\d\d)", r"\1.\2", body)
    return "WEBVTT\n\n" + body.strip() + "\n"


def clean_query(raw: str) -> str:
    """A query string without any token parameter."""
    keep = [(k, v) for k, v in urllib.parse.parse_qsl(raw or "", keep_blank_values=True) if k.lower() not in TOKEN_KEYS]
    return urllib.parse.urlencode(keep)


def safe_rel(rel: str, prefixes: tuple) -> Optional[str]:
    """An upstream path the relay may fetch: under one of the prefixes, nothing that climbs out (also when
    percent-encoded) or names a host. Returns the canonical, re-encoded path to send upstream, or None."""
    if not rel or ".." in rel or "//" in rel or "\\" in rel:
        return None
    return canonical_path(rel, prefixes, ignore_case=True)


class Plays:
    """The play sessions of the whole server, in memory."""

    def __init__(self, clock: Callable[[], float] = time.time):
        self.clock = clock
        self.alive: Callable[[str], bool] = lambda h: True      # app.py: the creating session still exists
        self.on_stopped: Optional[Callable[[str], None]] = None  # account id: Continue watching may have changed
        self._d: dict[str, Play] = {}
        self._lock = threading.Lock()
        self._sweeper: Optional[threading.Thread] = None
        self.sweep_every = SWEEP

    # ---- the session table ----
    def add(self, p: Play) -> None:
        old = []
        with self._lock:
            now = self.clock()
            for k, v in list(self._d.items()):
                if self._abandoned(v, now):
                    old.append(self._d.pop(k))
            mine = sorted((v for v in self._d.values() if v.account == p.account), key=lambda v: v.seen)
            for v in mine[:max(0, len(mine) - MAX_PER_ACCOUNT + 1)]:
                old.append(self._d.pop(v.id))
            self._d[p.id] = p
            if self._sweeper is None or not self._sweeper.is_alive():
                self._sweeper = threading.Thread(target=self._sweep_loop, name="orbit-media-sweep", daemon=True)
                self._sweeper.start()
        self._finish(old, why="ended")

    def _abandoned(self, p: Play, now: float) -> bool:
        """No relay request and no progress for IDLE_STOP (IDLE at the most). A file relay that is running counts
        as activity (a paused TV holds its connection, blocked); so does a TV / desktop build that reports "paused"
        once and then nothing (older builds), up to IDLE."""
        quiet = now - p.seen
        if quiet > IDLE:
            return True
        if quiet <= IDLE_STOP or p.pipes > 0:
            return False
        return not (p.state == "paused" and p.client in ("tv", "desktop"))

    def sweep(self) -> int:
        """End abandoned sessions (reported "stopped" at their last position, transcode stopped)."""
        with self._lock:
            now = self.clock()
            gone = [self._d.pop(k) for k, v in list(self._d.items()) if self._abandoned(v, now)]
        for p in gone:
            LOG.info("media play %s: abandoned (no request or progress for %d s); stopped at %d s",
                     p.label(), int(self.clock() - p.seen), int(p.pos))
        self._finish(gone, why="abandoned")
        return len(gone)

    def _sweep_loop(self) -> None:
        while True:
            time.sleep(self.sweep_every)
            try:
                self.sweep()
            except Exception:                     # noqa: BLE001
                LOG.exception("media play: sweep")
            with self._lock:
                if not self._d:
                    self._sweeper = None
                    return

    def get(self, sid: str) -> Optional[Play]:
        if not isinstance(sid, str) or not SID_RE.match(sid):
            return None
        with self._lock:
            p = self._d.get(sid)
            if p is None:
                return None
            if self._abandoned(p, self.clock()):
                self._d.pop(sid, None)
                expired = p
            else:
                p.seen = self.clock()
                return p
        self._finish([expired], why="abandoned")
        return None

    def end(self, sid: str) -> Optional[Play]:
        with self._lock:
            p = self._d.pop(sid, None)
        if p is not None:
            self._stop_all([p])
        return p

    def end_where(self, fn: Callable[[Play], bool]) -> int:
        with self._lock:
            gone = [self._d.pop(k) for k, v in list(self._d.items()) if fn(v)]
        self._stop_all(gone)
        return len(gone)

    def count(self) -> int:
        with self._lock:
            return len(self._d)

    @staticmethod
    def _stop_all(plays: list) -> None:
        """Stop the servers' transcodes, off the request thread (a server that is gone must not hold anyone up)."""
        plays = [p for p in plays if p.kind == "hls"]
        if not plays:
            return

        def run():
            for p in plays:
                Plays._stop_one(p)
        threading.Thread(target=run, name="orbit-media-stop", daemon=True).start()

    @staticmethod
    def _stop_one(p: Play) -> None:
        if p.kind != "hls":
            return
        try:
            p.connector.play_stop(p.ref)
        except ConnectorError as e:
            LOG.info("media play %s: stopping the transcode failed (%s)", p.label(), e.status or e.msg)
        except Exception:                         # noqa: BLE001
            LOG.info("media play %s: stopping the transcode failed", p.label())

    def _final(self, p: Play) -> bool:
        """Report "stopped" at the last known position (when the player reported at all). True when it did."""
        if not p.reported:
            return False
        try:
            p.connector.play_report(p.ref, p.item, p.pos, p.duration, "stopped", False)
            return True
        except ConnectorError as e:
            LOG.info("media play %s: the last \"stopped\" report failed (%s)", p.label(), e.status or e.msg)
        except Exception:                         # noqa: BLE001
            LOG.info("media play %s: the last \"stopped\" report failed", p.label())
        return False

    def _finish(self, plays: list, why: str, sync: bool = False) -> None:
        """Sessions that end without the player saying "stopped": the media server is told "stopped" at the last
        position, then the transcode is stopped. ``sync``: before returning (a handoff: the new playback starts
        right after), else off the request thread."""
        plays = [p for p in plays if p is not None]
        if not plays:
            return

        def run():
            told = set()
            for p in plays:
                if self._final(p):
                    told.add(p.account)
                self._stop_one(p)
            for acc in told:
                if self.on_stopped:
                    try:
                        self.on_stopped(acc)
                    except Exception:             # noqa: BLE001
                        LOG.exception("media play: after stop")
        if sync:
            run()
        else:
            threading.Thread(target=run, name="orbit-media-" + why, daemon=True).start()

    def handoff(self, account: str, conn: str, item: str) -> int:
        """The account plays this title again (on another device, or the same one with another audio track): its
        older sessions of the same title end first, so the media server frees that transcode and keeps the
        position before the new one starts."""
        with self._lock:
            gone = [self._d.pop(k) for k, v in list(self._d.items())
                    if v.account == account and v.conn == conn and v.item == item]
        for p in gone:
            LOG.info("media play %s: handed off to a new playback; stopped at %d s", p.label(), int(p.pos))
        self._finish(gone, why="handoff", sync=True)
        return len(gone)

    def live_refs(self, conn: str) -> set:
        """The media-server session ids of this connection's playbacks."""
        with self._lock:
            return {str(v.ref.get("session")) for v in self._d.values() if v.conn == conn and v.ref.get("session")}

    # ---- the API side ----
    def start(self, sess: dict, row, connector, item_id, client, caps, audio, start_secs=None,
              quality=None, addr: str = "") -> dict:
        """``quality``: "auto" (default), "orig", "8", "4" or "2" (tvbro_server/quality.py); ``addr``: the client's
        address, which decides what "auto" means (a lower cap away from home)."""
        if client not in CLIENTS:
            raise ApiError(400, "client: web, tv or desktop")
        if caps is not None and not isinstance(caps, dict):
            raise ApiError(400, "caps: an object")
        if audio is not None and (not isinstance(audio, (str, int)) or not SUBID_RE.match(str(audio))):
            raise ApiError(400, "audio: a track id from an earlier answer")
        if quality is not None and (not isinstance(quality, str) or quality not in ("auto", *Q.LEVELS)):
            raise ApiError(400, "quality: auto, orig, 8, 4 or 2")
        qual = Q.choose(quality, addr)
        if start_secs is not None:
            if isinstance(start_secs, bool) or not isinstance(start_secs, (int, float)) or not 0 <= start_secs <= 10 * 86400:
                raise ApiError(400, "startSecs: a number of seconds")
        try:
            iid = safe_id(item_id)
        except ConnectorError as e:
            raise ApiError(e.code, e.msg)
        self.handoff(sess["account_id"], row[0], iid)
        try:
            info = connector.play_info(iid, client == "web", caps, str(audio) if audio is not None else None,
                                       start=start_secs, cap=qual["cap"])
        except ConnectorError as e:
            LOG.info("media play %s %s item %s (%s): %s", row[1], row[0], iid, client, e.msg)
            raise ApiError(e.code, e.msg)
        sid = secrets.token_urlsafe(24)
        subs = {s["id"]: (s["path"], s.get("fmt") or "vtt") for s in info.get("subtitles") or []
                if SUBID_RE.match(str(s.get("id") or ""))}
        p = Play(sid, sess["account_id"], sess.get("h") or "", row[0], row[1], iid, info["kind"], info["path"],
                 tuple(connector.RELAY_PREFIXES), subs, connector, info.get("ref") or {},
                 float(info.get("durationSecs") or 0), self.clock(), client=client,
                 pos=float((info.get("ref") or {}).get("offset") or 0))
        self.add(p)
        nxt = None
        if info.get("seriesId"):
            try:
                n = connector.next_episode(info["seriesId"], info.get("season"), info.get("episode"))
                if n:
                    nxt = {"id": n["id"], "title": n.get("title") or "",
                           "subtitle": f"S{n.get('season')} E{n.get('episode')} · {n.get('title') or ''}".strip(" ·")}
            except Exception:                     # noqa: BLE001  (Next episode is a nicety)
                nxt = None
        base = PREFIX + sid
        container = info.get("container")
        hls = info["kind"] == "hls"
        LOG.info("media play %s: started%s (%s%s)", p.label(),
                 f" at {p.ref.get('offset')} s" if p.ref.get("offset") is not None else "", qual["label"],
                 ", away" if qual["away"] else "")
        return {"session": sid, "url": base + ("/master.m3u8" if hls else "/file"), "kind": info["kind"],
                "container": container, "mime": HLS_CTYPE if hls else MIME.get(container or "", "application/octet-stream"),
                "durationSecs": info.get("durationSecs"), "resumeSecs": info.get("resumeSecs"),
                "title": info.get("title") or "", "subtitle": info.get("subtitle"),
                "audio": info.get("audio") or [],
                "subtitles": [{"id": s["id"], "label": s.get("label") or "", "lang": s.get("lang"),
                               "url": f"{base}/sub/{s['id']}.vtt"} for s in info.get("subtitles") or [] if s["id"] in subs],
                "next": nxt, "conn": row[0], "source": row[1], "id": iid, "quality": Q.public(qual)}

    def progress(self, sess: dict, sid, pos, state) -> dict:
        p = self.get(sid) if isinstance(sid, str) else None
        if p is None or p.account != sess["account_id"]:
            raise ApiError(404, "no such play session (it may have ended)")
        try:
            secs = float(pos)
        except (TypeError, ValueError):
            raise ApiError(400, "positionSecs: a number of seconds")
        if secs != secs or secs < 0 or secs > 10 * 86400:
            raise ApiError(400, "positionSecs: out of range")
        if state not in ("playing", "paused", "stopped"):
            raise ApiError(400, "state: playing, paused or stopped")
        with p.lock:
            first = not p.reported
            p.reported = True
            p.pos, p.state = secs, state
        try:
            # "stopped" before any other report (the player never started, or an HLS fallback replaced this
            # session): nothing is told to the media server, so the position it has is kept
            if not (state == "stopped" and first):
                p.connector.play_report(p.ref, p.item, secs, p.duration, state, first)
        except ConnectorError as e:
            LOG.info("media play progress %s: %s", p.label(), e.msg)
            if state != "stopped":
                raise ApiError(e.code, e.msg)
        if state == "stopped":
            self.end(p.id)
            if self.on_stopped:
                try:
                    self.on_stopped(p.account)
                except Exception:                 # noqa: BLE001
                    LOG.exception("media play: after stop")
        return {"ok": True}

    # ---- the HTTP side (tvbro_server.app.Handler, GET /v1/media/stream/...) ----
    def serve(self, h, path: str, raw_query: str):
        rest = path[len(PREFIX):]
        sid, _, tail = rest.partition("/")
        p = self.get(sid)
        if p is None or not self.alive(p.sess_h):
            if p is not None:
                self.end(p.id)
            return _err(h, 404, "no such play session (it may have ended)")
        if tail == "file" and p.kind == "file":
            return self._pipe_url(h, p, p.path, media=True)
        if tail == "master.m3u8" and p.kind == "hls":
            return self._relay(h, p, p.path, master=True)
        if tail.startswith("u/") and p.kind == "hls":
            rel = safe_rel("/" + tail[2:], p.prefixes)
            if rel is None:
                return _err(h, 404, "not found")
            q = clean_query(raw_query)
            return self._relay(h, p, rel + ("?" + q if q else ""))
        if tail.startswith("sub/") and tail.endswith(".vtt"):
            sub = p.subs.get(tail[4:-4])
            if sub is None:
                return _err(h, 404, "no such subtitle track")
            return self._sub(h, p, *sub)
        return _err(h, 404, "not found")

    def _open(self, h, p: Play, rel: str, extra: Optional[dict] = None):
        c = p.connector
        hdrs = {**c.auth_headers(), **(extra or {})}
        hdrs.pop("Accept", None)
        return c.http.opener(c.base + rel, timeout=30, headers=hdrs)

    @staticmethod
    def _what(rel: str, master: bool = False) -> str:
        if master:
            return "playlist"
        path_only = rel.split("?", 1)[0].lower()
        return "playlist" if path_only.endswith(".m3u8") else "segment"

    def _upstream_error(self, p: Play, e: BaseException, what: str):
        """(status, redacted body) of a failed upstream request, logged with which request it was."""
        if isinstance(e, urllib.error.HTTPError):
            body = redact(error_body(e), (getattr(p.connector, "token", "") or "",))
            try:
                e.close()
            except Exception:                     # noqa: BLE001
                pass
            LOG.warning("media play %s: %s %s answered %d: %s", p.label(), p.connector.label, what, e.code,
                        body or "(empty body)")
            return e.code, body
        LOG.warning("media play %s: %s %s: no answer (%s)", p.label(), p.connector.label, what, type(e).__name__)
        return 0, ""

    def _fail(self, h, p: Play, e: BaseException, what: str = "request", status=None):
        code, body = status if status is not None else self._upstream_error(p, e, what)
        if code:
            if code in (401, 403):
                return _err(h, 409, f"{p.connector.label}: the sign-in is no longer valid; link it again")
            if code == 404:
                return _err(h, 404, f"{p.connector.label}: not found on the server")
            try:
                why = p.connector.explain(code, body, what)
            except Exception:                     # noqa: BLE001
                why = None
            return _err(h, 502, why or f"{p.connector.label}: the server answered {code}")
        return _err(h, 502, f"{p.connector.label}: the server did not answer")

    def _relay(self, h, p: Play, rel: str, master: bool = False):
        """A playlist (rewritten) or a segment (piped). The master playlist starts the transcode: when the server
        refuses it, transcodes this connection left behind (not one of its live sessions) are stopped and it is
        asked once more."""
        what = self._what(rel, master)
        try:
            up = self._open(h, p, rel)
        except Exception as e:                    # noqa: BLE001
            st = self._upstream_error(p, e, what)
            if not (master and st[0] and st[0] not in (401, 403, 404)):
                return self._fail(h, p, e, what, st)
            try:
                n = p.connector.play_cleanup(self.live_refs(p.conn))
            except Exception:                     # noqa: BLE001
                n = 0
            if not n:
                return self._fail(h, p, e, what, st)
            LOG.info("media play %s: asking for the playlist again", p.label())
            try:
                up = self._open(h, p, rel)
            except Exception as e2:               # noqa: BLE001
                return self._fail(h, p, e2, what)
        try:
            ct = (up.headers.get("Content-Type") or "").split(";", 1)[0].strip().lower()
            path_only = rel.split("?", 1)[0].lower()
            if "mpegurl" in ct or path_only.endswith(".m3u8"):
                data = up.read(MAX_HLS + 1)
                if len(data) > MAX_HLS or b"#EXTM3U" not in data[:64]:
                    return _err(h, 502, f"{p.connector.label}: unexpected playlist")
                base = p.connector.base
                final = up.geturl() or (base + rel)
                if not final.startswith(base + "/"):
                    final = base + rel

                def link(u: str) -> str:
                    if not u.startswith(base + "/"):
                        return PREFIX + p.id + "/x"          # another host: never relayed
                    r, _, q = u[len(base):].partition("?")
                    q = clean_query(q)
                    return PREFIX + p.id + "/u" + r + ("?" + q if q else "")
                return _send(h, 200, rewrite_hls(data, final, link), HLS_CTYPE)
            return self._pipe(h, p, up, ct, media=True)
        finally:
            up.close()

    def _pipe_url(self, h, p: Play, rel: str, media: bool):
        extra = {}
        rng = h.headers.get("Range")
        if rng:
            extra["Range"] = rng[:100]
        try:
            up = self._open(h, p, rel, extra)
        except Exception as e:                    # noqa: BLE001
            if isinstance(e, urllib.error.HTTPError) and e.code == 416:
                cr = e.headers.get("Content-Range") if e.headers else None
                e.close()
                return _send(h, 416, b"", "text/plain", {"Content-Range": cr} if cr else None)
            return self._fail(h, p, e, "file")
        with p.lock:
            p.pipes += 1
        try:
            ct = (up.headers.get("Content-Type") or "").split(";", 1)[0].strip().lower()
            return self._pipe(h, p, up, ct, media)
        finally:
            with p.lock:
                p.pipes -= 1
            up.close()

    def _pipe(self, h, p: Play, up, ct: str, media: bool):
        ctype = ct if ct.startswith(MEDIA_CTYPES) else "application/octet-stream"
        if ctype == "application/octet-stream" and p.kind == "file":
            ctype = MIME.get(p.path.split("?", 1)[0].rsplit(".", 1)[-1].lower(), ctype)
        h.send_response(up.getcode() or 200)
        h.send_header("Content-Type", ctype)
        for k in ("Content-Length", "Content-Range", "Accept-Ranges"):
            v = up.headers.get(k)
            if v:
                h.send_header(k, v)
        if not up.headers.get("Accept-Ranges") and p.kind == "file":
            h.send_header("Accept-Ranges", "bytes")
        if not up.headers.get("Content-Length"):
            h.send_header("Connection", "close")
            h.close_connection = True
        h.send_header("Cache-Control", "no-store")
        h.end_headers()
        read = getattr(up, "read1", None) or up.read
        n = 0
        try:
            while True:
                chunk = read(CHUNK)
                if not chunk:
                    break
                h.wfile.write(chunk)              # blocks while the device is not reading: backpressure upstream
                n += 1
                if n % CHECK_EVERY == 0:
                    if not self.alive(p.sess_h) or self.get(p.id) is None:
                        h.close_connection = True
                        break
        except (BrokenPipeError, ConnectionResetError, TimeoutError, OSError):
            h.close_connection = True

    def _sub(self, h, p: Play, rel: str, fmt: str):
        try:
            up = self._open(h, p, rel)
        except Exception as e:                    # noqa: BLE001
            return self._fail(h, p, e, "subtitles")
        try:
            data = up.read(MAX_SUB + 1)
        finally:
            up.close()
        if len(data) > MAX_SUB:
            return _err(h, 502, "subtitles too large")
        text = data.decode("utf-8-sig", "replace")
        if fmt == "srt" or not text.lstrip().startswith("WEBVTT"):
            text = srt_to_vtt(text)
        return _send(h, 200, text.encode(), "text/vtt; charset=utf-8")


def _send(h, code: int, body: bytes, ctype: str, extra: Optional[dict] = None):
    h.send_response(code)
    h.send_header("Content-Type", ctype)
    for k, v in (extra or {}).items():
        h.send_header(k, v)
    h.send_header("Content-Length", str(len(body)))
    h.send_header("Cache-Control", "no-store")
    h.end_headers()
    if h.command != "HEAD":
        h.wfile.write(body)


def _err(h, code: int, msg: str):
    _send(h, code, json.dumps({"error": msg}).encode(), "application/json")
