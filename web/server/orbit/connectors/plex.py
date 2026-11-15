# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""Plex, through its public web API (written from the API documentation; no SDK).

Sign-in: a PIN on plex.tv. The user opens plex.tv/link (TV friendly) and enters the 4-character code; the
server polls the PIN until it carries a token, then asks plex.tv for the user's servers and picks, per server,
the first connection that answers (local ones first, relays never). Each server's own ``accessToken`` is
stored, so the link works with that server only. Requests send the token as the ``X-Plex-Token`` header and
ask for JSON (``Accept: application/json``)."""
from __future__ import annotations

import json
import logging
import re
import secrets
import urllib.parse
from typing import Optional

from tvbro_server import quality as Q

from .base import (DEVICE_NAME, LIST_KINDS, PAGE, PRODUCT, STATES, VERSION, Connector, ConnectorError, Http, Linked,
                   colors, direct_ok, item, normalize_base_url, safe_id, ticks)

PLEX_TV = "https://plex.tv"            # tests point this at a mock
LINK_URL = "https://plex.tv/link"
LIB = "com.plexapp.plugins.library"
KIND = {"movie": "movie", "show": "series", "season": "season", "episode": "episode"}
TYPE_NUM = {"movie": 1, "series": 2}
LIB_KIND = {"movie": "movie", "show": "series"}
MAX_SERVERS = 10
MAX_COLLECTIONS = 300                 # per server, for the catalogue
TRANSCODE = "/video/:/transcode/universal"
# what a browser plays: HLS in MPEG-TS with H.264 video and AAC audio (Plex copies streams that already are)
PROFILE_EXTRA = ("add-transcode-target(type=videoProfile&context=streaming&protocol=hls&container=mpegts"
                 "&videoCodec=h264&audioCodec=aac,mp3)"
                 "+add-limitation(scope=videoCodec&scopeName=h264&type=upperBound&name=video.level&value=41)"
                 "+add-limitation(scope=videoAudioCodec&scopeName=aac&type=upperBound&name=audio.channels&value=2)")
SUB_FMT = {"srt": "srt", "subrip": "srt", "vtt": "vtt", "webvtt": "vtt"}
SCROBBLE_AT = 0.9                     # "watched" from 90 % on
LOG = logging.getLogger("orbit.media.plex")
# plain-English reasons for the client when Plex refuses a playback
TRANSCODE_LIMIT = ("Plex is already transcoding as much as it's allowed; stop another stream or raise the limit in "
                   "Plex \u25b8 Settings \u25b8 Transcoder")
STREAM_LIMIT = "Plex says this account has reached its number of allowed streams; stop another playback and try again"
_DECISION_KEYS = ("generalDecisionCode", "generalDecisionText", "directPlayDecisionCode", "directPlayDecisionText",
                  "transcodeDecisionCode", "transcodeDecisionText")
_DECISION_XML = re.compile(r'(' + "|".join(_DECISION_KEYS) + r')="([^"]{0,300})"')


def _int(v) -> int:
    try:
        return int(v)
    except (TypeError, ValueError):
        return 0


def decision_of(body) -> dict:
    """The decision fields of a Plex answer (a JSON MediaContainer, or XML attributes), else {}."""
    if isinstance(body, dict):
        mc = _mc(body)
        return {k: mc[k] for k in _DECISION_KEYS if k in mc}
    text = body.decode("utf-8", "replace") if isinstance(body, (bytes, bytearray)) else str(body or "")
    try:
        d = decision_of(json.loads(text))
        if d:
            return d
    except (ValueError, AttributeError):
        pass
    return {k: v for k, v in _DECISION_XML.findall(text)}


def decision_refusal(d: dict) -> Optional[str]:
    """A plain-English reason when a decision says Plex will not play (general code 2000 and up), else None."""
    gen = _int(d.get("generalDecisionCode"))
    if gen < 2000:
        return None
    texts = [str(d.get(k) or "").strip() for k in ("generalDecisionText", "transcodeDecisionText", "directPlayDecisionText")]
    return reason_text(" ".join(t for t in texts if t), gen)


def reason_text(text: str, code: int = 0) -> str:
    low = text.lower()
    if "allowed streams" in low or "number of allowed" in low:
        return STREAM_LIMIT
    if re.search(r"transcod|conver", low) and re.search(r"limit|maximum|too many|busy|capacity", low):
        return TRANSCODE_LIMIT
    clean = re.sub(r"[^\w .,;:'()/-]+", " ", text).strip()[:160]
    if clean:
        return f"Plex will not play this title: {clean}"
    return f"Plex will not play this title (decision {code})" if code else "Plex will not play this title"


def client_headers(device_id: str, token: Optional[str] = None) -> dict:
    h = {"Accept": "application/json", "X-Plex-Product": PRODUCT, "X-Plex-Version": VERSION,
         "X-Plex-Client-Identifier": device_id, "X-Plex-Device-Name": DEVICE_NAME, "X-Plex-Platform": "Orbit"}
    if token:
        h["X-Plex-Token"] = token
    return h


def _mc(ans) -> dict:
    return (ans or {}).get("MediaContainer") or {} if isinstance(ans, dict) else {}


class Plex(Connector):
    kind = "plex"
    label = "Plex"
    IMAGE_PREFIXES = ("/library/metadata/",)

    # ---- sign-in ----
    @classmethod
    def sign_in_start(cls, http: Http, base_url: Optional[str], device_id: str) -> tuple:
        pin = http.json("POST", PLEX_TV + "/api/v2/pins", client_headers(device_id))
        if not isinstance(pin, dict) or not pin.get("id") or not pin.get("code"):
            raise ConnectorError(502, "Plex: unexpected PIN answer from plex.tv")
        public = {"method": "pin", "code": str(pin["code"]), "linkUrl": LINK_URL,
                  "hint": "Open plex.tv/link, sign in to Plex and enter this code."}
        # an address the owner typed (optional): tried first, for a server plex.tv lists under another address
        base = normalize_base_url(base_url) if base_url else None
        return public, {"pin": str(pin["id"]), "base": base}

    @classmethod
    def sign_in_poll(cls, http: Http, state: dict, device_id: str) -> Optional[list]:
        pin = http.json("GET", PLEX_TV + f"/api/v2/pins/{safe_id(state['pin'], 'pin')}", client_headers(device_id))
        token = (pin or {}).get("authToken") if isinstance(pin, dict) else None
        if not token:
            return None
        user = http.json("GET", PLEX_TV + "/api/v2/user", client_headers(device_id, token)) or {}
        res = http.json("GET", PLEX_TV + "/api/v2/resources?" + urllib.parse.urlencode(
            {"includeHttps": 1, "includeRelay": 0}), client_headers(device_id, token)) or []
        servers = [r for r in res if isinstance(r, dict) and "server" in str(r.get("provides") or "").split(",")]
        out, tried = [], []
        typed = state.get("base")
        if typed:
            ln = cls.link_typed(http, typed, servers, token, device_id, user, state.get("allow"), tried)
            if ln:
                out.append(ln)
        for r in servers[:MAX_SERVERS]:
            if any(o.server_id and o.server_id == str(r.get("clientIdentifier") or "") for o in out):
                continue
            tok = str(r.get("accessToken") or token)
            base = cls.pick_connection(http, r.get("connections") or [], tok, device_id, state.get("allow"), tried)
            if base:
                out.append(Linked(base, str(r.get("name") or "Plex")[:100], str(r.get("clientIdentifier") or ""),
                                  str(user.get("id") or user.get("uuid") or ""),
                                  str(user.get("username") or user.get("title") or "")[:100], tok))
        if not out:
            raise ConnectorError(502, cls.unreachable_message(len(servers), tried))
        return out

    @staticmethod
    def candidates(conns: list) -> list:
        """Connection URIs in the order to try: local first, then remote; relays never; https before http."""
        ok = [c for c in conns if isinstance(c, dict) and c.get("uri") and not c.get("relay")]
        ok.sort(key=lambda c: (not c.get("local"), str(c.get("protocol") or "") != "https"))
        out = []
        for c in ok:
            try:
                out.append(normalize_base_url(str(c["uri"])))
            except ConnectorError:
                pass
        return out

    @classmethod
    def pick_connection(cls, http: Http, conns: list, token: str, device_id: str, allow=None,
                        tried: Optional[list] = None) -> Optional[str]:
        """The first candidate whose /identity answers. ``allow(base)`` lets the caller permit a LAN address
        for this probe (orbit/media.py decides who may). Failures are added to ``tried`` as (address, reason)."""
        for base in cls.candidates(conns):
            if allow:
                allow(base)
            try:
                http.json("GET", base + "/identity", client_headers(device_id, token), timeout=4)
                return base
            except ConnectorError as e:
                if tried is not None:
                    tried.append((base, e.msg.split(": ", 1)[-1]))
                continue
        return None

    @classmethod
    def link_typed(cls, http: Http, base: str, servers: list, token: str, device_id: str, user: dict, allow,
                   tried: list) -> Optional[Linked]:
        """The server at an address the owner typed: its /identity says which of the account's servers it is
        (so its own access token is used); one plex.tv does not list must accept the account's token."""
        if allow:
            allow(base)
        try:
            ident = http.json("GET", base + "/identity", client_headers(device_id), timeout=4)
        except ConnectorError as e:
            tried.append((base, e.msg.split(": ", 1)[-1]))
            return None
        mid = str(((ident or {}).get("MediaContainer") or {}).get("machineIdentifier") or "")
        match = next((r for r in servers if mid and str(r.get("clientIdentifier") or "") == mid), None)
        tok = str((match or {}).get("accessToken") or token)
        try:
            http.json("GET", base + "/library/sections", client_headers(device_id, tok), timeout=6)
        except ConnectorError as e:
            tried.append((base, "this Plex account has no access to that server" if e.relink
                          else e.msg.split(": ", 1)[-1]))
            return None
        name = str((match or {}).get("name") or "Plex")[:100]
        return Linked(base, name, mid, str(user.get("id") or user.get("uuid") or ""),
                      str(user.get("username") or user.get("title") or "")[:100], tok)

    @staticmethod
    def unreachable_message(servers: int, tried: list) -> str:
        """Why linking failed, with the addresses tried (the owner sees this, to fix a firewall or route)."""
        if not servers:
            return "Plex: this Plex account has no servers"
        if not tried:
            return "Plex: plex.tv lists no direct address for your Plex server (only relays, which Orbit does not use)"
        shown = "; ".join(f"{urllib.parse.urlsplit(b).netloc} ({why})" for b, why in tried[:4])
        more = f" and {len(tried) - 4} more" if len(tried) > 4 else ""
        hint = (" If Plex runs on the same machine as Orbit, that machine's firewall may block the Orbit container"
                " from reaching it.") if any("did not answer" in why for _, why in tried) else ""
        return f"Plex: none of your Plex servers could be reached from this server. Tried {shown}{more}.{hint}"

    # ---- helpers ----
    def auth_headers(self) -> dict:
        return client_headers(self.device_id, self.token)

    def _get(self, path: str, params: Optional[dict] = None, headers: Optional[dict] = None):
        url = self.base + path + ("?" + urllib.parse.urlencode(params) if params else "")
        return self.http.json("GET", url, {**self.auth_headers(), **(headers or {})})

    def _send(self, path: str, params: dict) -> None:
        """A call whose answer does not matter (it may be empty or XML on some versions)."""
        self.http.raw("GET", self.base + path + "?" + urllib.parse.urlencode(params), self.auth_headers())

    def _norm(self, d: dict) -> dict:
        kind = KIND.get(d.get("type"), "other")
        guids = {}
        for g in d.get("Guid") or []:
            s = str((g or {}).get("id") or "")
            if "://" in s:
                k, v = s.split("://", 1)
                guids[k.lower()] = v
        dur = ticks(d.get("duration")) / 1000
        series = d.get("grandparentRatingKey") if kind == "episode" else (d.get("parentRatingKey") if kind == "season" else None)
        rating = d.get("audienceRating", d.get("rating"))
        return item(str(d.get("ratingKey") or ""), kind, d.get("title"), year=d.get("year"), overview=d.get("summary"),
                    poster=d.get("thumb") or None, backdrop=d.get("art") or None, runtime=dur, rating=rating,
                    tmdb=guids.get("tmdb"), imdb=guids.get("imdb"), tvdb=guids.get("tvdb"),
                    progress={"secs": ticks(d.get("viewOffset")) / 1000, "dur": dur,
                              "watched": int(d.get("viewCount") or 0) > 0 or
                              (kind in ("series", "season") and d.get("leafCount") and d.get("leafCount") == d.get("viewedLeafCount"))},
                    seriesId=str(series) if series else None,
                    season=d.get("parentIndex") if kind == "episode" else (d.get("index") if kind == "season" else None),
                    episode=d.get("index") if kind == "episode" else None,
                    genres=[(g or {}).get("tag") for g in d.get("Genre") or [] if isinstance(g, dict)],
                    added=d.get("addedAt"), released=d.get("originallyAvailableAt"), colors=self._colors(d),
                    lastViewed=d.get("lastViewedAt"))

    @staticmethod
    def _colors(d: dict):
        """Plex's UltraBlur backdrop colours (top left, bottom right), when the server sends them."""
        u = d.get("UltraBlurColors")
        if isinstance(u, list):
            u = u[0] if u else None
        return colors(u.get("topLeft"), u.get("bottomRight")) if isinstance(u, dict) else None

    def _items(self, ans) -> list:
        return [self._norm(d) for d in _mc(ans).get("Metadata") or [] if isinstance(d, dict) and d.get("ratingKey")]

    def _meta(self, item_id: str) -> dict:
        md = _mc(self._get(f"/library/metadata/{safe_id(item_id)}", {"includeGuids": 1})).get("Metadata") or []
        if not md or not isinstance(md[0], dict):
            raise ConnectorError(404, "Plex: not found on the server")
        return md[0]

    # ---- reading ----
    def health(self) -> dict:
        mc = _mc(self._get("/identity"))
        return {"ok": True, "name": "", "version": mc.get("version") or ""}

    def list_libraries(self) -> list:
        return [{"id": str(d["key"]), "title": d.get("title") or "", "kind": LIB_KIND.get(d.get("type"), "other")}
                for d in _mc(self._get("/library/sections")).get("Directory") or [] if isinstance(d, dict) and d.get("key")]

    def list_items(self, library: str, cursor: Optional[str], kind: Optional[str], limit: int = PAGE) -> dict:
        start, n = self.page(cursor, limit)
        if kind and kind not in LIST_KINDS:
            raise ConnectorError(400, "kind: movie or series")
        params = {"includeGuids": 1, "sort": "titleSort"}
        if kind:
            params["type"] = TYPE_NUM[kind]
        ans = self._get(f"/library/sections/{safe_id(library, 'library')}/all", params,
                        {"X-Plex-Container-Start": str(start), "X-Plex-Container-Size": str(n)})
        mc = _mc(ans)
        items = self._items(ans)
        total = int(mc.get("totalSize") or mc.get("size") or 0)
        return {"items": items, "next": str(start + len(items)) if items and start + len(items) < total else None,
                "total": total}

    def get_item(self, item_id: str) -> dict:
        return self._norm(self._meta(item_id))

    def _children(self, item_id: str) -> list:
        return self._items(self._get(f"/library/metadata/{safe_id(item_id)}/children", {"includeGuids": 1}))

    def get_seasons(self, series_id: str) -> list:
        return [i for i in self._children(series_id) if i["kind"] == "season"]

    def get_episodes(self, season_id: str) -> list:
        return [i for i in self._children(season_id) if i["kind"] == "episode"]

    def children(self, item_id: str) -> list:
        return [i for i in self._children(item_id) if i["kind"] in ("season", "episode")]

    # ---- the catalogue ----
    def collections(self) -> list:
        out = []
        for lib in self.list_libraries():
            if lib["kind"] not in ("movie", "series"):
                continue
            ans = self._get(f"/library/sections/{safe_id(lib['id'], 'library')}/collections")
            for d in _mc(ans).get("Metadata") or []:
                if len(out) >= MAX_COLLECTIONS:
                    return out
                if not isinstance(d, dict) or not d.get("ratingKey"):
                    continue
                cid = safe_id(d["ratingKey"])
                kids = self._items(self._get(f"/library/collections/{cid}/children"))
                out.append({"id": cid, "title": str(d.get("title") or "")[:200], "kind": lib["kind"],
                            "poster": d.get("thumb") or None, "backdrop": d.get("art") or None, "colors": self._colors(d),
                            "items": [i["id"] for i in kids if i["kind"] in ("movie", "series")]})
        return out

    def continue_items(self) -> list:
        """On Deck: films in progress and the next episode of shows being watched."""
        return [i for i in self._items(self._get("/library/onDeck", {"includeGuids": 1}))
                if i["kind"] in ("movie", "episode")]

    # ---- playing ----
    def playback(self, item_id: str) -> dict:
        d = self._meta(item_id)
        if d.get("type") not in ("movie", "episode"):
            raise ConnectorError(400, "only films and episodes can be played")
        media = (d.get("Media") or [{}])[0] or {}
        part = (media.get("Part") or [{}])[0] or {}
        key = str(part.get("key") or "")
        if not key.startswith("/library/parts/") or ".." in key:
            raise ConnectorError(502, "Plex: this item has no playable file")
        return {"url": self.base + key + "?" + urllib.parse.urlencode({"X-Plex-Token": self.token}), "headers": {},
                "direct": True, "container": media.get("container") or part.get("container") or None,
                "resumeSecs": int(ticks(d.get("viewOffset")) / 1000)}

    def report_progress(self, item_id: str, secs: float, dur: float, state: str) -> None:
        if state not in STATES:
            raise ConnectorError(400, "state: playing, paused or stopped")
        iid = safe_id(item_id)
        self._send("/:/timeline", {"ratingKey": iid, "key": f"/library/metadata/{iid}", "identifier": LIB,
                                  "state": state, "time": int(secs * 1000), "duration": int(dur * 1000)})

    def mark_watched(self, item_id: str, watched: bool) -> None:
        self._send("/:/scrobble" if watched else "/:/unscrobble", {"key": safe_id(item_id), "identifier": LIB})

    # ---- playing through the home server ----
    RELAY_PREFIXES = (TRANSCODE + "/",)

    def play_info(self, item_id: str, hls: bool, caps, audio=None, start=None, cap=None) -> dict:
        iid = safe_id(item_id)
        d = self._meta(iid)
        if d.get("type") not in ("movie", "episode"):
            raise ConnectorError(400, "only films and episodes can be played")
        media = (d.get("Media") or [{}])[0] or {}
        part = (media.get("Part") or [{}])[0] or {}
        key = str(part.get("key") or "")
        if not key.startswith("/library/parts/") or ".." in key or "?" in key:
            raise ConnectorError(409, "Plex: this title has no playable file")
        streams = [s for s in part.get("Stream") or [] if isinstance(s, dict) and s.get("id") is not None]
        auds = [s for s in streams if s.get("streamType") == 2]
        audio_out = [{"id": str(s["id"]), "label": str(s.get("extendedDisplayTitle") or s.get("displayTitle") or
                                                         s.get("language") or f"Track {i + 1}")[:120],
                      "lang": s.get("languageCode") or s.get("languageTag") or None,
                      "default": bool(s.get("selected") or s.get("default"))} for i, s in enumerate(auds)]
        subs = []
        for s in streams:
            fmt = SUB_FMT.get(str(s.get("codec") or "").lower())
            skey = str(s.get("key") or "")
            if s.get("streamType") == 3 and fmt and skey.startswith("/library/streams/") and ".." not in skey:
                subs.append({"id": str(s["id"]), "label": str(s.get("extendedDisplayTitle") or s.get("displayTitle") or
                                                             s.get("language") or "Subtitles")[:120],
                             "lang": s.get("languageCode") or s.get("languageTag") or None,
                             "path": skey.split("?", 1)[0], "fmt": fmt})
        container = str(media.get("container") or part.get("container") or "").lower() or None
        direct = not hls and not cap and direct_ok(caps, container, media.get("videoCodec"), media.get("audioCodec"))
        ref = {"session": secrets.token_hex(12), "rated": False, "partId": str(part.get("id") or "")}
        dur = int(ticks(d.get("duration") or media.get("duration")) / 1000)
        resume = int(ticks(d.get("viewOffset")) / 1000)
        if direct:
            path, kind = key, "file"
        else:
            if audio and part.get("id") is not None and any(a["id"] == str(audio) and not a["default"] for a in audio_out):
                # the Plex way to pick the audio of a transcode: the part's selected stream (as Plex's own apps do)
                self.http.raw("PUT", self.base + f"/library/parts/{safe_id(part['id'])}?" + urllib.parse.urlencode(
                    {"audioStreamID": safe_id(audio, "audio"), "allParts": 1}), self.auth_headers())
                for a in audio_out:
                    a["default"] = a["id"] == str(audio)
            q = {"path": f"/library/metadata/{iid}", "mediaIndex": 0, "partIndex": 0, "protocol": "hls",
                 "fastSeek": 1, "directPlay": 0, "directStream": 1, "directStreamAudio": 1, "copyts": 1,
                 "subtitles": "none", "subtitleSize": 100, "audioBoost": 100, "location": "lan",
                 "addDebugOverlay": 0, "autoAdjustQuality": 0, "mediaBufferSize": 102400,
                 "maxVideoBitrate": cap[0] if cap else 20000, "videoQuality": 100,
                 "videoResolution": Q.resolution(cap[1]) if cap else "1920x1080",
                 "session": ref["session"], "X-Plex-Session-Identifier": ref["session"],
                 "X-Plex-Client-Profile-Name": "Generic", "X-Plex-Client-Profile-Extra": PROFILE_EXTRA}
            # where the player will start: the transcode starts there (Plex's own clients send "offset" in seconds)
            # instead of at 0 with a seek right after it; the playlist still covers the whole title
            at = start if start is not None else (resume if resume and (not dur or resume < dur - 30) else 0)
            try:
                at = max(0, int(float(at)))
            except (TypeError, ValueError):
                at = 0
            if dur and at >= dur:
                at = 0
            q["offset"] = at
            ref["offset"] = at
            self._decide(q)
            path, kind = TRANSCODE + "/start.m3u8?" + urllib.parse.urlencode(q), "hls"
        ep = d.get("type") == "episode"
        return {"kind": kind, "path": path, "container": container if kind == "file" else "ts",
                "durationSecs": dur or None,
                "resumeSecs": resume or None,
                "title": str((d.get("grandparentTitle") if ep else None) or d.get("title") or "")[:300],
                "subtitle": (f"S{d.get('parentIndex')} E{d.get('index')} · {d.get('title') or ''}".strip(" ·")
                             if ep else None),
                "audio": audio_out, "subtitles": subs,
                "seriesId": (str(d.get("grandparentRatingKey") or "") or None) if ep else None,
                "season": d.get("parentIndex") if ep else None, "episode": d.get("index") if ep else None,
                "ref": ref}

    def _decide(self, q: dict) -> None:
        """The universal transcoder's decision with the start parameters, as Plex's own web client asks before
        start.m3u8. A refusal is raised in plain English; a server that does not answer it is logged (status and
        body, by Http) and the start is tried anyway."""
        try:
            ans = self.http.json("GET", self.base + TRANSCODE + "/decision?" + urllib.parse.urlencode(q),
                                 self.auth_headers())
        except ConnectorError as e:
            if e.relink:
                raise
            LOG.warning("Plex decision failed (%s); starting the transcode anyway", e.status or e.msg)
            return
        d = decision_of(ans) if isinstance(ans, dict) else {}
        why = decision_refusal(d)
        LOG.info("Plex decision: general %s %r, transcode %s %r, offset %s s", d.get("generalDecisionCode"),
                 str(d.get("generalDecisionText") or "")[:120], d.get("transcodeDecisionCode"),
                 str(d.get("transcodeDecisionText") or "")[:120], q.get("offset"))
        if why:
            raise ConnectorError(409, why)

    def explain(self, status: int, body: str, what: str) -> Optional[str]:
        why = decision_refusal(decision_of(body))
        if why:
            return why
        if what == "playlist" and status not in (401, 403, 404):
            return (f"Plex refused to start transcoding this title (it answered {status}). Try again; if it keeps "
                    "failing, Orbit's server log has Plex's exact answer")
        if what == "segment" and status not in (401, 403, 404):
            return f"Plex stopped sending this title (it answered {status}); try again"
        return None

    def play_cleanup(self, live: set) -> int:
        """Transcodes Plex lists for this connection's client identifier that Orbit no longer knows about."""
        try:
            ans = self._get("/status/sessions")
        except ConnectorError as e:
            LOG.info("Plex sessions could not be listed (%s)", e.status or e.msg)
            return 0
        gone = 0
        for m in _mc(ans).get("Metadata") or []:
            if not isinstance(m, dict):
                continue
            player = m.get("Player") or {}
            ts = m.get("TranscodeSession") or {}
            sid = str(ts.get("key") or "").rsplit("/", 1)[-1]
            if str(player.get("machineIdentifier") or "") != self.device_id or not sid or sid in live:
                continue
            try:
                self.play_stop({"session": safe_id(sid)})
                gone += 1
            except ConnectorError:
                pass
        LOG.warning("Plex: stopped %d leftover transcode(s) of this Orbit connection", gone)
        return gone

    def play_report(self, ref: dict, item_id: str, secs: float, dur: float, state: str, first: bool) -> None:
        if state not in STATES:
            raise ConnectorError(400, "state: playing, paused or stopped")
        iid = safe_id(item_id)
        self.http.raw("GET", self.base + "/:/timeline?" + urllib.parse.urlencode(
            {"ratingKey": iid, "key": f"/library/metadata/{iid}", "identifier": LIB, "state": state,
             "time": int(secs * 1000), "duration": int(dur * 1000)}),
            {**self.auth_headers(), "X-Plex-Session-Identifier": str(ref.get("session") or "")})
        if dur and secs >= dur * SCROBBLE_AT and not ref.get("rated"):
            ref["rated"] = True               # once per playback
            self.mark_watched(iid, True)

    def play_stop(self, ref: dict) -> None:
        if ref.get("session"):
            self.http.raw("GET", self.base + TRANSCODE + "/stop?" + urllib.parse.urlencode(
                {"session": ref["session"]}), self.auth_headers(), timeout=5)
