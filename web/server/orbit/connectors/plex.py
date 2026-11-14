# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""Plex, through its public web API (written from the API documentation; no SDK).

Sign-in: a PIN on plex.tv. The user opens plex.tv/link (TV friendly) and enters the 4-character code; the
server polls the PIN until it carries a token, then asks plex.tv for the user's servers and picks, per server,
the first connection that answers (local ones first, relays never). Each server's own ``accessToken`` is
stored, so the link works with that server only. Requests send the token as the ``X-Plex-Token`` header and
ask for JSON (``Accept: application/json``)."""
from __future__ import annotations

import urllib.parse
from typing import Optional

from .base import (DEVICE_NAME, LIST_KINDS, PAGE, PRODUCT, STATES, VERSION, Connector, ConnectorError, Http, Linked,
                   colors, item, normalize_base_url, safe_id, ticks)

PLEX_TV = "https://plex.tv"            # tests point this at a mock
LINK_URL = "https://plex.tv/link"
LIB = "com.plexapp.plugins.library"
KIND = {"movie": "movie", "show": "series", "season": "season", "episode": "episode"}
TYPE_NUM = {"movie": 1, "series": 2}
LIB_KIND = {"movie": "movie", "show": "series"}
MAX_SERVERS = 10
MAX_COLLECTIONS = 300                 # per server, for the catalogue


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
        return public, {"pin": str(pin["id"])}

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
        for r in servers[:MAX_SERVERS]:
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
