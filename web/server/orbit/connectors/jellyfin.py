# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""Jellyfin, through its public REST API (written from the API documentation; no SDK).

Sign-in: Quick Connect (the user approves a 6-digit code in Jellyfin: profile > Quick Connect) or, once,
username + password (sent to Jellyfin, never stored). Every request carries
``Authorization: MediaBrowser Client="Orbit", Device=..., DeviceId=..., Version=..., Token=...``; each link has
its own DeviceId, because Jellyfin ends older tokens of a device when that device signs in again."""
from __future__ import annotations

import secrets
import urllib.parse
from typing import Optional

from .base import (DEVICE_NAME, LIST_KINDS, PAGE, PRODUCT, STATES, VERSION, Connector, ConnectorError, Http, Linked,
                   direct_ok, iso_time, item, normalize_base_url, safe_id, ticks)

TICKS = 10_000_000                     # Jellyfin times are 100 ns ticks
FIELDS = ("Overview,ProviderIds,ProductionYear,CommunityRating,RunTimeTicks,UserData,ImageTags,BackdropImageTags,"
          "Genres,DateCreated,PremiereDate")
MAX_COLLECTIONS = 300                 # box sets, for the catalogue
KIND = {"Movie": "movie", "Series": "series", "Season": "season", "Episode": "episode"}
LIST_TYPES = {"movie": "Movie", "series": "Series"}
LIB_KIND = {"movies": "movie", "tvshows": "series"}


def _q(v: str) -> str:
    return '"' + str(v).replace('"', "") + '"'


def auth_header(device_id: str, token: Optional[str] = None) -> str:
    parts = [f"Client={_q(PRODUCT)}", f"Device={_q(DEVICE_NAME)}", f"DeviceId={_q(device_id)}", f"Version={_q(VERSION)}"]
    if token:
        parts.append(f"Token={_q(token)}")
    return "MediaBrowser " + ", ".join(parts)


def _headers(device_id: str, token: Optional[str] = None) -> dict:
    h = auth_header(device_id, token)
    return {"Authorization": h, "X-Emby-Authorization": h}      # older servers read the second one


def device_profile(direct: bool) -> dict:
    """What Orbit plays: any file directly (TV / desktop, when allowed), else HLS (MPEG-TS, H.264, AAC stereo)."""
    return {"Name": "Orbit", "MaxStreamingBitrate": 120_000_000,
            "DirectPlayProfiles": [{"Type": "Video"}] if direct else [],
            "TranscodingProfiles": [{"Container": "ts", "Type": "Video", "VideoCodec": "h264", "AudioCodec": "aac",
                                     "Context": "Streaming", "Protocol": "hls", "MaxAudioChannels": "2",
                                     "MinSegments": "1", "BreakOnNonKeyFrames": True}],
            "SubtitleProfiles": [{"Format": "vtt", "Method": "External"}, {"Format": "srt", "Method": "External"}],
            "ContainerProfiles": [], "CodecProfiles": []}


def strip_token(url: str) -> str:
    """A server URL without any api_key / token parameter (the relay sends the token as a header)."""
    path, _, q = url.partition("?")
    keep = [(k, v) for k, v in urllib.parse.parse_qsl(q, keep_blank_values=True)
            if k.lower() not in ("api_key", "apikey", "token", "x-emby-token", "x-mediabrowser-token")]
    return path + ("?" + urllib.parse.urlencode(keep) if keep else "")


def _linked(base: str, info: dict, auth: dict) -> Linked:
    user = (auth or {}).get("User") or {}
    token = (auth or {}).get("AccessToken")
    if not token or not user.get("Id"):
        raise ConnectorError(502, "Jellyfin: unexpected sign-in answer")
    return Linked(base, str(info.get("ServerName") or "Jellyfin")[:100], str(auth.get("ServerId") or info.get("Id") or ""),
                  str(user["Id"]), str(user.get("Name") or "")[:100], str(token))


class Jellyfin(Connector):
    kind = "jellyfin"
    label = "Jellyfin"
    IMAGE_PREFIXES = ("/Items/",)

    # ---- sign-in ----
    @staticmethod
    def public_info(http: Http, base: str) -> dict:
        info = http.json("GET", base + "/System/Info/Public")
        if not isinstance(info, dict) or not info.get("Id"):
            raise ConnectorError(502, "Jellyfin: that address is not a Jellyfin server")
        return info

    @classmethod
    def sign_in_start(cls, http: Http, base_url: Optional[str], device_id: str) -> tuple:
        base = normalize_base_url(base_url)
        info = cls.public_info(http, base)
        if http.json("GET", base + "/QuickConnect/Enabled") is not True:
            raise ConnectorError(409, "Jellyfin: Quick Connect is off on this server; sign in with username and password")
        qc = http.json("POST", base + "/QuickConnect/Initiate", _headers(device_id))
        if not isinstance(qc, dict) or not qc.get("Secret") or not qc.get("Code"):
            raise ConnectorError(502, "Jellyfin: unexpected Quick Connect answer")
        public = {"method": "quickconnect", "code": str(qc["Code"]),
                  "server": {"name": str(info.get("ServerName") or "")[:100], "version": str(info.get("Version") or "")},
                  "hint": "In Jellyfin, open your profile, choose Quick Connect and enter this code."}
        return public, {"base": base, "secret": str(qc["Secret"]), "info": info}

    @classmethod
    def sign_in_poll(cls, http: Http, state: dict, device_id: str) -> Optional[list]:
        base, secret = state["base"], state["secret"]
        st = http.json("GET", base + "/QuickConnect/Connect?" + urllib.parse.urlencode({"secret": secret}),
                       _headers(device_id))
        if not isinstance(st, dict) or not st.get("Authenticated"):
            return None
        auth = http.json("POST", base + "/Users/AuthenticateWithQuickConnect", _headers(device_id), {"Secret": secret})
        return [_linked(base, state["info"], auth)]

    @classmethod
    def sign_in_password(cls, http: Http, base_url: str, username: str, password: str, device_id: str) -> Linked:
        base = normalize_base_url(base_url)
        info = cls.public_info(http, base)
        try:
            auth = http.json("POST", base + "/Users/AuthenticateByName", _headers(device_id),
                             {"Username": username, "Pw": password})
        except ConnectorError as e:
            if e.relink:                      # 401 here just means a wrong password
                raise ConnectorError(403, "Jellyfin: wrong username or password")
            raise
        return _linked(base, info, auth)

    def sign_out(self) -> None:
        self.http.raw("POST", self.base + "/Sessions/Logout", self.auth_headers(), timeout=5)

    # ---- helpers ----
    def auth_headers(self) -> dict:
        return _headers(self.device_id, self.token)

    def _get(self, path: str, params: Optional[dict] = None):
        url = self.base + path + ("?" + urllib.parse.urlencode(params) if params else "")
        return self.http.json("GET", url, self.auth_headers())

    def _user(self) -> str:
        return urllib.parse.quote(self.user_id, safe="")

    def _norm(self, d: dict) -> dict:
        iid = str(d.get("Id") or "")
        kind = KIND.get(d.get("Type"), "other")
        ud = d.get("UserData") or {}
        prov = {str(k).lower(): v for k, v in (d.get("ProviderIds") or {}).items()}
        tags = d.get("ImageTags") or {}
        backs = d.get("BackdropImageTags") or []
        dur = ticks(d.get("RunTimeTicks")) / TICKS
        poster = (f"/Items/{iid}/Images/Primary?" + urllib.parse.urlencode({"tag": tags["Primary"], "maxWidth": 500})
                  if tags.get("Primary") else None)
        backdrop = (f"/Items/{iid}/Images/Backdrop/0?" + urllib.parse.urlencode({"tag": backs[0], "maxWidth": 1280})
                    if backs else None)
        series = d.get("SeriesId") if kind in ("season", "episode") else None
        return item(iid, kind, d.get("Name"), year=d.get("ProductionYear"), overview=d.get("Overview"), poster=poster,
                    backdrop=backdrop, runtime=dur, rating=d.get("CommunityRating"),
                    tmdb=prov.get("tmdb"), imdb=prov.get("imdb"), tvdb=prov.get("tvdb"),
                    progress={"secs": ticks(ud.get("PlaybackPositionTicks")) / TICKS, "dur": dur,
                              "watched": ud.get("Played")},
                    seriesId=series,
                    season=d.get("ParentIndexNumber") if kind == "episode" else (d.get("IndexNumber") if kind == "season" else None),
                    episode=d.get("IndexNumber") if kind == "episode" else None,
                    genres=d.get("Genres") if isinstance(d.get("Genres"), list) else None,
                    added=iso_time(d.get("DateCreated")), released=d.get("PremiereDate"),
                    lastViewed=iso_time(ud.get("LastPlayedDate")))

    def _items(self, ans) -> list:
        return [self._norm(d) for d in ((ans or {}).get("Items") or []) if isinstance(d, dict) and d.get("Id")]

    # ---- reading ----
    def health(self) -> dict:
        info = self._get("/System/Info")
        return {"ok": True, "name": (info or {}).get("ServerName") or "", "version": (info or {}).get("Version") or ""}

    def list_libraries(self) -> list:
        ans = self._get(f"/Users/{self._user()}/Views")
        return [{"id": str(v["Id"]), "title": v.get("Name") or "", "kind": LIB_KIND.get(v.get("CollectionType"), "other")}
                for v in (ans or {}).get("Items") or [] if isinstance(v, dict) and v.get("Id")]

    def list_items(self, library: str, cursor: Optional[str], kind: Optional[str], limit: int = PAGE) -> dict:
        start, n = self.page(cursor, limit)
        if kind and kind not in LIST_KINDS:
            raise ConnectorError(400, "kind: movie or series")
        ans = self._get(f"/Users/{self._user()}/Items", {
            "ParentId": safe_id(library, "library"), "Recursive": "true", "SortBy": "SortName", "SortOrder": "Ascending",
            "IncludeItemTypes": LIST_TYPES[kind] if kind else "Movie,Series", "Fields": FIELDS,
            "StartIndex": start, "Limit": n, "EnableTotalRecordCount": "true"})
        items = self._items(ans)
        total = int((ans or {}).get("TotalRecordCount") or 0)
        return {"items": items, "next": str(start + len(items)) if items and start + len(items) < total else None,
                "total": total}

    def get_item(self, item_id: str) -> dict:
        d = self._get(f"/Users/{self._user()}/Items/{safe_id(item_id)}")
        if not isinstance(d, dict) or not d.get("Id"):
            raise ConnectorError(404, "Jellyfin: not found on the server")
        return self._norm(d)

    def get_seasons(self, series_id: str) -> list:
        return self._items(self._get(f"/Shows/{safe_id(series_id)}/Seasons", {"userId": self.user_id, "Fields": FIELDS}))

    def get_episodes(self, season_id: str) -> list:
        season = self.get_item(season_id)
        if not season.get("seriesId"):
            raise ConnectorError(404, "Jellyfin: that season has no series")
        return self._items(self._get(f"/Shows/{safe_id(season['seriesId'])}/Episodes",
                                     {"seasonId": season_id, "userId": self.user_id, "Fields": FIELDS}))

    # ---- the catalogue ----
    def collections(self) -> list:
        ans = self._get(f"/Users/{self._user()}/Items", {"IncludeItemTypes": "BoxSet", "Recursive": "true",
                                                         "Fields": FIELDS, "Limit": MAX_COLLECTIONS})
        out = []
        for d in (ans or {}).get("Items") or []:
            if not isinstance(d, dict) or not d.get("Id"):
                continue
            box = self._norm(d)
            kids = self._items(self._get(f"/Users/{self._user()}/Items", {"ParentId": safe_id(d["Id"]), "Fields": FIELDS}))
            kinds = {i["kind"] for i in kids}
            out.append({"id": box["id"], "title": box["title"][:200],
                        "kind": "movie" if kinds == {"movie"} else ("series" if kinds == {"series"} else None),
                        "poster": box["poster"], "backdrop": box["backdrop"], "colors": None,
                        "items": [i["id"] for i in kids if i["kind"] in ("movie", "series")]})
        return out

    def continue_items(self) -> list:
        """Films and episodes in progress (Resume), then the next episode of each show being watched (NextUp)."""
        res = self._items(self._get(f"/Users/{self._user()}/Items/Resume", {"Fields": FIELDS, "Limit": 50,
                                                                           "MediaTypes": "Video"}))
        nxt = self._items(self._get("/Shows/NextUp", {"userId": self.user_id, "Fields": FIELDS, "Limit": 50}))
        return [i for i in res + nxt if i["kind"] in ("movie", "episode")]

    # ---- playing ----
    def playback(self, item_id: str) -> dict:
        it = self.get_item(item_id)
        if it["kind"] not in ("movie", "episode"):
            raise ConnectorError(400, "only films and episodes can be played")
        info = self.http.json("POST", self.base + f"/Items/{safe_id(item_id)}/PlaybackInfo?" +
                              urllib.parse.urlencode({"userId": self.user_id}), self.auth_headers(), {})
        src = ((info or {}).get("MediaSources") or [{}])[0] or {}
        q = {"static": "true", "api_key": self.token}
        if src.get("Id"):
            q["mediaSourceId"] = str(src["Id"])
        container = str(src.get("Container") or "").split(",", 1)[0] or None
        return {"url": f"{self.base}/Videos/{it['id']}/stream?" + urllib.parse.urlencode(q), "headers": {},
                "direct": src.get("SupportsDirectPlay", True) is not False, "container": container,
                "resumeSecs": it["progress"]["secs"]}

    def report_progress(self, item_id: str, secs: float, dur: float, state: str) -> None:
        if state not in STATES:
            raise ConnectorError(400, "state: playing, paused or stopped")
        body = {"ItemId": safe_id(item_id), "PositionTicks": int(secs * TICKS), "IsPaused": state == "paused",
                "PlayMethod": "DirectPlay", "CanSeek": True}
        path = "/Sessions/Playing/Stopped" if state == "stopped" else "/Sessions/Playing/Progress"
        self.http.raw("POST", self.base + path, self.auth_headers(), body)

    # ---- playing through the home server ----
    RELAY_PREFIXES = ("/videos/",)        # compared in lower case (Jellyfin answers "/videos/...")

    def play_info(self, item_id: str, hls: bool, caps, audio=None, start=None, cap=None) -> dict:
        iid = safe_id(item_id)
        d = self._get(f"/Users/{self._user()}/Items/{iid}")
        if not isinstance(d, dict) or not d.get("Id"):
            raise ConnectorError(404, "Jellyfin: not found on the server")
        if d.get("Type") not in ("Movie", "Episode"):
            raise ConnectorError(400, "only films and episodes can be played")
        a_idx = int(audio) if isinstance(audio, (str, int)) and str(audio).isdigit() else None
        hls = hls or bool(cap)                # a cap (away from home) always means a transcode
        body = {"UserId": self.user_id, "DeviceProfile": device_profile(not hls),
                "MaxStreamingBitrate": cap[0] * 1000 if cap else 120_000_000,
                "EnableDirectPlay": not hls, "EnableDirectStream": not hls, "EnableTranscoding": True,
                "AutoOpenLiveStream": False}
        if cap:
            body["DeviceProfile"]["MaxStreamingBitrate"] = cap[0] * 1000
            body["DeviceProfile"]["CodecProfiles"] = [{"Type": "Video", "Conditions": [
                {"Condition": "LessThanEqual", "Property": "Height", "Value": str(cap[1]), "IsRequired": False}]}]
        if a_idx is not None:
            body["AudioStreamIndex"] = a_idx
        info = self.http.json("POST", self.base + f"/Items/{iid}/PlaybackInfo?" +
                              urllib.parse.urlencode({"userId": self.user_id}), self.auth_headers(), body) or {}
        srcs = [s for s in info.get("MediaSources") or [] if isinstance(s, dict)]
        if not srcs:
            raise ConnectorError(409, "Jellyfin: this title has no playable file")
        src = srcs[0]
        msid = safe_id(src.get("Id") or iid, "media source")
        psid = str(info.get("PlaySessionId") or secrets.token_hex(16))[:64]
        streams = [s for s in src.get("MediaStreams") or [] if isinstance(s, dict) and isinstance(s.get("Index"), int)]
        vid = next((s for s in streams if s.get("Type") == "Video"), {})
        auds = [s for s in streams if s.get("Type") == "Audio"]
        dflt = a_idx if a_idx is not None else src.get("DefaultAudioStreamIndex")
        audio_out = [{"id": str(s["Index"]), "label": str(s.get("DisplayTitle") or s.get("Language") or f"Track {i + 1}")[:120],
                      "lang": s.get("Language") or None,
                      "default": s["Index"] == dflt if dflt is not None else bool(s.get("IsDefault"))}
                     for i, s in enumerate(auds)]
        subs = [{"id": str(s["Index"]), "label": str(s.get("DisplayTitle") or s.get("Language") or "Subtitles")[:120],
                 "lang": s.get("Language") or None,
                 "path": f"/Videos/{iid}/{msid}/Subtitles/{s['Index']}/0/Stream.vtt", "fmt": "vtt"}
                for s in streams if s.get("Type") == "Subtitle" and s.get("IsTextSubtitleStream")]
        container = str(src.get("Container") or "").split(",", 1)[0].lower() or None
        acodec = next((s.get("Codec") for s in auds if s["Index"] == dflt), None) or (auds[0].get("Codec") if auds else None)
        direct = (not hls and src.get("SupportsDirectPlay", True) is not False
                  and direct_ok(caps, container, vid.get("Codec"), acodec))
        if direct:
            path, kind = f"/Videos/{iid}/stream?" + urllib.parse.urlencode({"static": "true", "mediaSourceId": msid}), "file"
        else:
            turl = str(src.get("TranscodingUrl") or "")
            if turl.lower().startswith("/videos/") and ".m3u8" in turl.split("?", 1)[0]:
                path = strip_token(turl)
            else:                             # an older server, or none offered: ask for it ourselves
                q = {"MediaSourceId": msid, "PlaySessionId": psid, "DeviceId": self.device_id, "VideoCodec": "h264",
                     "AudioCodec": "aac", "TranscodingMaxAudioChannels": 2, "SegmentContainer": "ts", "MinSegments": 1,
                     "BreakOnNonKeyFrames": "True"}
                if cap:
                    q.update({"VideoBitrate": cap[0] * 1000, "MaxHeight": cap[1]})
                if a_idx is not None:
                    q["AudioStreamIndex"] = a_idx
                path = f"/Videos/{iid}/master.m3u8?" + urllib.parse.urlencode(q)
            kind = "hls"
        ud = d.get("UserData") or {}
        ep = d.get("Type") == "Episode"
        return {"kind": kind, "path": path, "container": container if kind == "file" else "ts",
                "durationSecs": int(ticks(src.get("RunTimeTicks") or d.get("RunTimeTicks")) / TICKS) or None,
                "resumeSecs": int(ticks(ud.get("PlaybackPositionTicks")) / TICKS) or None,
                "title": str((d.get("SeriesName") if ep else None) or d.get("Name") or "")[:300],
                "subtitle": (f"S{d.get('ParentIndexNumber')} E{d.get('IndexNumber')} · {d.get('Name') or ''}".strip(" ·")
                             if ep else None),
                "audio": audio_out, "subtitles": subs,
                "seriesId": d.get("SeriesId") if ep else None,
                "season": d.get("ParentIndexNumber") if ep else None, "episode": d.get("IndexNumber") if ep else None,
                "ref": {"item": iid, "msid": msid, "psid": psid, "method": "DirectPlay" if direct else "Transcode",
                        "audio": a_idx}}

    def play_report(self, ref: dict, item_id: str, secs: float, dur: float, state: str, first: bool) -> None:
        if state not in STATES:
            raise ConnectorError(400, "state: playing, paused or stopped")
        body = {"ItemId": safe_id(item_id), "MediaSourceId": ref.get("msid"), "PlaySessionId": ref.get("psid"),
                "PositionTicks": int(secs * TICKS), "IsPaused": state == "paused", "PlayMethod": ref.get("method"),
                "CanSeek": True}
        if ref.get("audio") is not None:
            body["AudioStreamIndex"] = ref["audio"]
        if state == "stopped":
            path = "/Sessions/Playing/Stopped"
        else:
            path = "/Sessions/Playing" if first else "/Sessions/Playing/Progress"
        self.http.raw("POST", self.base + path, self.auth_headers(), body)

    def play_stop(self, ref: dict) -> None:
        if ref.get("method") == "Transcode" and ref.get("psid"):
            self.http.raw("DELETE", self.base + "/Videos/ActiveEncodings?" + urllib.parse.urlencode(
                {"deviceId": self.device_id, "playSessionId": ref["psid"]}), self.auth_headers(), timeout=5)

    def mark_watched(self, item_id: str, watched: bool) -> None:
        self.http.raw("POST" if watched else "DELETE",
                      self.base + f"/Users/{self._user()}/PlayedItems/{safe_id(item_id)}", self.auth_headers())
