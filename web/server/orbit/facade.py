# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""Hidden provider logins: an Xtream-compatible facade for linked TVs (docs/orbit/API-v1.md).

With the facade on (the default; ``ORBIT_HIDE_PROVIDER_LOGIN`` or Settings), ``/v1/device/playlists`` no longer hands a
linked TV the provider's server, user name and password (or M3U address). Each playlist becomes

    {"server": "http://<home server>/xt/<playlist id>", "user": "tv<device id>", "pass": <device secret>}

and the TV's existing Xtream client talks to this server instead of the provider:

- ``player_api.php``, ``get.php`` and ``xmltv.php`` are answered from the real provider; logins in the answers are
  replaced (``user_info``, ``server_info``, and any address that carries the login is sealed, see below).
- ``live|movie|series|timeshift/<user>/<pass>/...`` streams are relayed: bytes are piped through in small chunks
  (no buffering beyond one chunk, backpressure from the TV's socket), ``Range`` is passed for films, redirects are
  followed here, and an HLS playlist is rewritten so its segments come through here too.
- An M3U playlist is served rewritten: every stream address, guide address and catch-up template points here.
- Addresses the server rewrites are sealed (authenticated encryption with the server key, ``App.seal``): the TV can
  replay them but never read them. ``s/<user>/<pass>/<token>`` relays one; ``t/...?v0=..`` fills a catch-up template.

The device user is the TV session's public id, the secret an HMAC of its session (so it never needs storing). Every
request checks that the session still exists: unlinking the TV, signing it out from another device, disabling the
account or the session expiring ends its facade logins at once, including relays already running.

Nothing here logs a URL: errors name the exception type only.
"""
from __future__ import annotations

import gzip
import hmac
import json
import logging
import os
import re
import threading
import time
import urllib.error
import urllib.parse
from hashlib import sha256
from typing import Callable, Optional

LOG = logging.getLogger("orbit.facade")

ENV = "ORBIT_HIDE_PROVIDER_LOGIN"
PREFIX = "/xt/"
CHUNK = 64 * 1024                   # one read from the provider, one write to the TV
CHECK_EVERY = 16                    # chunks between "is this TV still linked?" checks on a long relay
MAX_API = 300 * 1024 * 1024         # one player_api.php answer (VOD lists), as tvbro_server.app.MAX_API_BYTES
MAX_M3U = 200 * 1024 * 1024         # an M3U playlist
MAX_HLS = 8 * 1024 * 1024           # an HLS playlist
ACTIVE_SEC = 20                     # a TV counts as streaming for this long after its last media bytes
USER_RE = re.compile(r"^tv[0-9a-f]{16}$")
PASS_RE = re.compile(r"^[0-9a-f]{32}$")
PID_RE = re.compile(r"^p[0-9]{1,4}$")
REST_RE = re.compile(r"^[A-Za-z0-9._:%+\-]+(/[A-Za-z0-9._:%+\-]+){0,4}$")   # what follows <user>/<pass>/
TOKEN_RE = re.compile(r"^[A-Za-z0-9_\-]{8,4096}$")
PLACEHOLDER = re.compile(r"\$\{[^}]*\}|\{[^}{]*\}")
STREAM_KINDS = ("live", "movie", "series", "timeshift")
MEDIA_CTYPES = ("video/", "audio/", "application/vnd.apple.mpegurl", "application/x-mpegurl",
                "application/octet-stream", "binary/octet-stream", "application/mp4")
XML_CTYPES = ("text/xml", "application/xml", "application/gzip", "application/x-gzip", "application/octet-stream")


def hide_logins(settings) -> bool:
    """Facade (True) or the provider's own logins (False): ``ORBIT_HIDE_PROVIDER_LOGIN`` wins when set, else the
    owner's setting, on by default."""
    v = os.environ.get(ENV)
    if v is not None and v.strip():
        return v.strip().lower() not in ("0", "false", "no", "off")
    return bool((settings or {}).get("hideProviderLogin", True))


def forced() -> bool:
    """The environment decides (the web setting is shown but cannot change it)."""
    v = os.environ.get(ENV)
    return v is not None and bool(v.strip())


_LOGIN_IN_PATH = re.compile(r"(/xt/[^/\s?]+/[a-z]+/)[^/\s?]+/[^/\s?]+")


def redact(text: str) -> str:
    """A request path (or log line) with any facade device login in it replaced by '-/-'."""
    return _LOGIN_IN_PATH.sub(r"\1-/-", text or "")


def safe_err(e: BaseException) -> str:
    """An exception for the log without any address (which could carry a login)."""
    return re.sub(r"\w+://\S+", "<url>", f"{type(e).__name__}: {e}")[:160]


# --------------------------------------------------------------------------- device logins


class DeviceKeys:
    """The facade login of each linked TV session: user ``tv<public session id>``, secret = HMAC(server key,
    session hash). Valid exactly as long as the session is (auth.py) and its account link is (accounts.py)."""

    def __init__(self, accounts, key: bytes, clock: Callable[[], float] = time.time):
        self.acc, self.key, self.clock = accounts, key, clock
        self._users: dict[str, tuple[float, Optional[str]]] = {}     # user -> (until, session hash or None)
        self._lock = threading.Lock()

    def _secret(self, h: str) -> str:
        return hmac.new(self.key, ("orbit-xt|" + h).encode(), sha256).hexdigest()[:32]

    def creds(self, sess: dict) -> Optional[tuple[str, str]]:
        """(user, secret) for a linked TV or desktop app session, else None."""
        row = self.acc.db.one("SELECT id FROM account_sessions WHERE sess_hash=? AND kind IN ('tv','desktop')", (sess["h"],))
        if row is None:
            return None
        return "tv" + row[0], self._secret(sess["h"])

    def _hash_of(self, user: str) -> Optional[str]:
        now = self.clock()
        with self._lock:
            hit = self._users.get(user)
            if hit and hit[0] > now:
                return hit[1]
        row = self.acc.db.one("SELECT sess_hash FROM account_sessions WHERE id=? AND kind IN ('tv','desktop')", (user[2:],))
        h = row[0] if row else None
        with self._lock:
            if len(self._users) > 1000:
                self._users.clear()
            self._users[user] = (now + (60 if h else 5), h)
        return h

    def check(self, user, secret) -> Optional[str]:
        """The session hash behind a facade login, or None (unknown, wrong secret, unlinked, expired, disabled)."""
        if not isinstance(user, str) or not isinstance(secret, str) or not USER_RE.match(user) or not PASS_RE.match(secret):
            return None
        h = self._hash_of(user)
        if h is None or not hmac.compare_digest(self._secret(h), secret):
            return None
        return h if self.alive(h) else None

    def alive(self, h: str) -> bool:
        """The session still exists and belongs to an enabled account (cheap: cached by accounts.gate)."""
        auth = self.acc.auth
        with auth._lock:
            s = auth.sessions.get(h)
            if not s or s.get("exp", 0) <= self.clock():
                return False
            role = s.get("role", "viewer")
        return self.acc.gate({"h": h, "role": role}) is not None

    def device_name(self, h: str) -> str:
        row = self.acc.db.one("SELECT device FROM account_sessions WHERE sess_hash=?", (h,))
        return (row[0] if row and row[0] else "TV")[:60]


def device_playlists(pl: list, origin: str, user: str, secret: str) -> list:
    """The playlists as a linked TV sees them with the facade on: no provider address or login anywhere."""
    q = urllib.parse.urlencode({"username": user, "password": secret})
    out = []
    for p in pl:
        kind, pid = p.get("kind"), p.get("id", "")
        base = f"{origin}/xt/{pid}"
        if kind == "xtream":
            # (the TV takes an Xtream guide from <server>/xmltv.php, as the server does; epgUrl is unused there)
            out.append({**p, "server": base, "user": user, "pass": secret, "url": "", "epgUrl": ""})
        elif kind == "m3u":
            n = len(split_epg(p.get("epgUrl") or ""))
            out.append({**p, "server": "", "user": "", "pass": "", "url": f"{base}/get.php?{q}",
                        "epgUrl": ",".join(f"{base}/xmltv.php?{q}&n={i}" for i in range(n))})
        else:
            out.append(p)                       # demo: a public test stream, nothing to hide
    return out


def split_epg(s: str) -> list[str]:
    return [x for x in re.split(r"[,\s]+", s or "") if x]


# --------------------------------------------------------------------------- rewriting (pure)


def _ext(url: str) -> str:
    """'.ts', '.m3u8' … of an address (players pick the format by it), or ''."""
    try:
        m = re.search(r"\.[A-Za-z0-9]{1,5}$", urllib.parse.urlsplit(url).path)
    except ValueError:
        return ""
    return m.group(0).lower() if m else ""


def rewrite_hls(data: bytes, base_url: str, link: Callable[[str], str]) -> bytes:
    """An HLS playlist with every URI (segment lines and URI="..." in tags) replaced by ``link(absolute URL)``."""
    out = []
    for line in data.decode("utf-8", "replace").splitlines():
        s = line.strip()
        if not s:
            out.append(line)
        elif s.startswith("#"):
            if 'URI="' in s:
                s = re.sub(r'URI="([^"]+)"', lambda m: 'URI="' + link(urllib.parse.urljoin(base_url, m.group(1))) + '"', s)
            out.append(s)
        else:
            out.append(link(urllib.parse.urljoin(base_url, s)))
    return ("\n".join(out) + "\n").encode()


def _attr_sub(line: str, names: tuple, fn: Callable[[str], str]) -> str:
    def repl(m):
        return f'{m.group(1)}="{fn(m.group(2))}"'
    return re.sub(r'(?i)\b(' + "|".join(re.escape(n) for n in names) + r')="([^"]*)"', repl, line)


def rewrite_m3u(text: str, stream: Callable[[str], str], template: Callable[[str], str],
                plain: Callable[[str], str]) -> str:
    """An M3U playlist with every address pointing at the facade: stream lines (``stream``), guide addresses in the
    header (``plain``) and absolute catch-up templates (``template``; relative ones are appended by the TV to the
    stream address it got, which the facade passes on)."""
    out = []
    for line in text.splitlines():
        s = line.strip()
        if s.upper().startswith("#EXTM3U"):
            line = _attr_sub(line, ("url-tvg", "x-tvg-url"),
                             lambda v: ",".join(plain(u) if u.lower().startswith(("http://", "https://")) else u
                                                for u in split_epg(v)))
        elif s.upper().startswith("#EXTINF"):
            line = _attr_sub(line, ("catchup-source",),
                             lambda v: template(v) if v.lower().startswith(("http://", "https://")) else v)
        elif s and not s.startswith("#") and s.lower().startswith(("http://", "https://")):
            line = stream(s)
        out.append(line)
    return "\n".join(out) + ("\n" if text.endswith("\n") else "")


def template_link(tpl: str, seal: Callable[[str], str], prefix: str) -> str:
    """A catch-up template as a facade address: the sealed template, plus its placeholders in order as v0, v1 … so
    the TV still fills them in (``fill_template`` puts the values back)."""
    ph = PLACEHOLDER.findall(tpl)
    q = "&".join(f"v{i}={p}" for i, p in enumerate(ph))
    return prefix + seal(tpl) + _ext(PLACEHOLDER.sub("", tpl)) + ("?" + q if q else "")


def fill_template(tpl: str, values: dict) -> str:
    it = iter(range(10_000))
    return PLACEHOLDER.sub(lambda m: values.get(f"v{next(it)}", ""), tpl)


class Scrub:
    """Replaces the provider login in player_api.php answers."""

    def __init__(self, server: str, user: str, pw: str, fake_user: str, fake_pw: str, origin: str,
                 link: Callable[[str], str]):
        self.user, self.pw, self.fu, self.fp, self.origin, self.link = user, pw, fake_user, fake_pw, origin, link
        q = urllib.parse.quote
        self.pats = [p for p in {f"/{user}/{pw}/", f"/{q(user, safe='')}/{q(pw, safe='')}/",
                                 f"password={pw}", f"password={q(pw, safe='')}", f"password={urllib.parse.quote_plus(pw)}"}
                     if pw]

    def needed(self, body: bytes) -> bool:
        if b'"user_info"' in body[:4096]:
            return True
        for p in self.pats:
            for form in (p, p.replace("/", "\\/")):
                if form.encode() in body:
                    return True
        return False

    def _str(self, s: str) -> str:
        if not any(p in s for p in self.pats):
            return s
        if s.lower().startswith(("http://", "https://")):
            return self.link(s)                 # a stream or image address with the login: sealed, through here
        for p in self.pats:
            s = s.replace(p, p.replace(self.user, self.fu).replace(self.pw, self.fp))
        return s

    def _walk(self, v):
        if isinstance(v, str):
            return self._str(v)
        if isinstance(v, list):
            return [self._walk(x) for x in v]
        if isinstance(v, dict):
            return {k: self._walk(x) for k, x in v.items()}
        return v

    def apply(self, body: bytes) -> bytes:
        try:
            d = json.loads(body)
        except ValueError:
            return body.replace(self.pw.encode(), self.fp.encode()) if self.pw else body
        if isinstance(d, dict):
            ui = d.get("user_info")
            if isinstance(ui, dict):
                ui = dict(ui)
                if "username" in ui:
                    ui["username"] = self.fu
                if "password" in ui:
                    ui["password"] = self.fp
                d = {**d, "user_info": ui}
            si = d.get("server_info")
            if isinstance(si, dict):
                o = urllib.parse.urlsplit(self.origin)
                https = o.scheme == "https"
                port = str(o.port or (443 if https else 80))
                d = {**d, "server_info": {**si, "url": o.hostname or "", "port": "" if https else port,
                                          "https_port": port if https else "", "server_protocol": o.scheme,
                                          "rtmp_port": ""}}
        return json.dumps(self._walk(d), separators=(",", ":"), ensure_ascii=False).encode()


# --------------------------------------------------------------------------- active relays (stream count)


class Relays:
    """Which linked TVs are pulling media through the facade (exact: every byte passes here)."""

    def __init__(self, clock: Callable[[], float] = time.time):
        self.clock = clock
        self._lock = threading.Lock()
        self._d: dict[str, dict] = {}           # session hash -> {device, playlist, what, since, seen, open}

    def seen(self, h: str, device: str, pid: str, what: Optional[str], opened: int = 0):
        now = self.clock()
        with self._lock:
            e = self._d.get(h)
            if e is None or (not e["open"] and now - e["seen"] >= ACTIVE_SEC):      # a new viewing session
                e = self._d[h] = {"device": device, "playlist": pid, "what": what or "", "since": now, "seen": now,
                                  "open": 0}
            e["seen"] = now
            e["open"] = max(0, e["open"] + opened)
            if what:
                e["what"], e["playlist"] = what, pid
            for k in [k for k, v in self._d.items() if now - v["seen"] > 3600]:
                del self._d[k]

    def status(self) -> list[dict]:
        now = self.clock()
        with self._lock:
            act = [v for v in self._d.values() if v["open"] or now - v["seen"] < ACTIVE_SEC]
            return [{"device": v["device"], "playlist": v["playlist"], "what": v["what"], "since": int(v["since"]),
                     "idleSec": 0 if v["open"] else int(now - v["seen"])} for v in sorted(act, key=lambda x: x["since"])]


# --------------------------------------------------------------------------- the HTTP side


class Facade:
    """Serves ``/xt/...`` for tvbro_server.app.Handler. ``provider(pid)`` returns an enabled playlist's stored login
    ({kind, server, user, pass, url, epgUrl}) or None; ``seal`` / ``unseal`` are App.seal / App.unseal; ``open_url``
    is net.open_url (http(s) only, no local addresses unless allowed)."""

    def __init__(self, keys: DeviceKeys, provider: Callable[[str], Optional[dict]], seal: Callable[[str], str],
                 unseal: Callable[[str], Optional[str]], open_url: Callable, is_blocked: Callable[[BaseException], bool],
                 clock: Callable[[], float] = time.time):
        self.keys, self.provider, self._seal, self._unseal = keys, provider, seal, unseal
        self.open_url, self.is_blocked = open_url, is_blocked
        self.relays = Relays(clock)

    def seal(self, url: str) -> str:
        return self._seal("xt|" + url)

    def unseal(self, tok: str) -> Optional[str]:
        s = self._unseal(tok) if TOKEN_RE.match(tok or "") else None
        return s[3:] if s and s.startswith("xt|") else None

    # ---- answers ----
    @staticmethod
    def _send(h, code: int, body: bytes, ctype: str, extra: Optional[dict] = None, gz: bool = False):
        if gz and len(body) > 1400 and "gzip" in (h.headers.get("Accept-Encoding") or ""):
            body = gzip.compress(body, 1)
            extra = {**(extra or {}), "Content-Encoding": "gzip"}
        h.send_response(code)
        h.send_header("Content-Type", ctype)
        for k, v in (extra or {}).items():
            h.send_header(k, v)
        h.send_header("Content-Length", str(len(body)))
        h.send_header("Cache-Control", "no-store")
        h.end_headers()
        if h.command != "HEAD":
            h.wfile.write(body)

    def _err(self, h, code: int, msg: str):
        self._send(h, code, json.dumps({"error": msg}).encode(), "application/json")

    def _denied(self, h):
        self._send(h, 401, json.dumps({"user_info": {"auth": 0}, "error": "unknown or unlinked device"}).encode(),
                   "application/json")

    # ---- routing ----
    def serve(self, h, path: str, query: dict, raw_query: str):
        """``path`` starts with /xt/. Host was checked by the handler; the device login is checked here."""
        parts = path[len(PREFIX):].split("/")
        pid = parts[0] if parts else ""
        if not PID_RE.match(pid):
            return self._err(h, 404, "not found")
        rest = parts[1:]
        if len(rest) == 1 and rest[0] in ("player_api.php", "get.php", "xmltv.php"):
            dev = self.keys.check(query.get("username", ""), query.get("password", ""))
            if dev is None:
                return self._denied(h)
            prov = self.provider(pid)
            if prov is None:
                return self._err(h, 404, "no such playlist")
            origin = self._origin(h)
            fn = {"player_api.php": self._api, "get.php": self._m3u, "xmltv.php": self._xmltv}[rest[0]]
            return fn(h, pid, prov, dev, query, raw_query, origin)
        if len(rest) >= 4 and rest[0] in STREAM_KINDS + ("s", "t"):
            kind, user, secret = rest[0], rest[1], rest[2]
            dev = self.keys.check(user, secret)
            if dev is None:
                return self._denied(h)
            prov = self.provider(pid)
            if prov is None:
                return self._err(h, 404, "no such playlist")
            tail = "/".join(rest[3:])
            link = self._linker(pid, user, secret)
            if kind in STREAM_KINDS:
                if prov.get("kind") != "xtream" or not REST_RE.match(tail) or ".." in tail:
                    return self._err(h, 404, "not found")
                from tvbro_server import parsers as P            # the server's own URL rules
                url = f"{P.normalize_server(prov['server'])}/{kind}/{prov['user']}/{prov['pass']}/{tail}"
                what = f"{kind} {tail.rsplit('/', 1)[-1].split('.', 1)[0]}"
                return self.relay(h, url, dev, pid, what, link)
            tok = rest[3].split(".", 1)[0] if len(rest) == 4 else ""
            url = self.unseal(tok)
            if not url or not url.lower().startswith(("http://", "https://")):
                return self._err(h, 403, "bad token")
            if kind == "t":
                url = fill_template(url, query)
            elif raw_query:                                   # catch-up "append": the TV added ?utc=… to the address
                url += ("&" if "?" in url else "?") + raw_query
            return self.relay(h, url, dev, pid, None, link)
        return self._err(h, 404, "not found")

    def _origin(self, h) -> str:
        host = h.headers.get("Host") or "localhost"
        https = getattr(h, "_https", lambda: False)()
        return ("https" if https else "http") + "://" + host

    def _linker(self, pid: str, user: str, secret: str) -> Callable[[str], str]:
        prefix = f"/xt/{pid}/s/{user}/{secret}/"
        return lambda u: prefix + self.seal(u) + _ext(u)

    # ---- catalogue ----
    def _fetch(self, url: str, cap: int, headers: Optional[dict] = None):
        with self.open_url(url, timeout=120, headers=headers) as r:
            data = r.read(cap + 1)
            if len(data) > cap:
                raise ValueError("answer too large")
            return data, r.headers.get("Content-Type") or ""

    def _upstream_fail(self, h, e: BaseException):
        if isinstance(e, urllib.error.HTTPError):
            code = e.code if 400 <= e.code < 600 else 502
            try:
                e.close()
            except Exception:                   # noqa: BLE001
                pass
            return self._err(h, code, "provider answered %d" % e.code)
        if self.is_blocked(e):
            return self._err(h, 403, "provider address not allowed")
        LOG.info("facade: provider request failed (%s)", safe_err(e))
        return self._err(h, 502, "provider not reachable")

    def _api(self, h, pid, prov, dev, query, raw_query, origin):
        if prov.get("kind") != "xtream":
            return self._err(h, 404, "not an Xtream playlist")
        from tvbro_server import parsers as P
        extra = {k: v for k, v in query.items() if k not in ("username", "password")}
        action = extra.pop("action", None)
        url = f"{P.normalize_server(prov['server'])}/player_api.php?" + urllib.parse.urlencode(
            {"username": prov["user"], "password": prov["pass"], **({"action": action} if action else {}), **extra})
        try:
            body, _ = self._fetch(url, MAX_API)
        except Exception as e:                  # noqa: BLE001
            return self._upstream_fail(h, e)
        sc = Scrub(prov["server"], prov["user"], prov["pass"], query["username"], query["password"], origin,
                   self._linker(pid, query["username"], query["password"]))
        if sc.needed(body):
            body = sc.apply(body)
        self._send(h, 200, body, "application/json", gz=True)

    def _m3u(self, h, pid, prov, dev, query, raw_query, origin):
        if prov.get("kind") == "xtream":
            from tvbro_server import parsers as P
            q = {k: v for k, v in query.items() if k not in ("username", "password")}
            url = f"{P.normalize_server(prov['server'])}/get.php?" + urllib.parse.urlencode(
                {"username": prov["user"], "password": prov["pass"], **(q or {"type": "m3u_plus", "output": "ts"})})
        else:
            url = prov.get("url") or ""
        if not url:
            return self._err(h, 404, "no playlist address")
        try:
            body, _ = self._fetch(url, MAX_M3U)
        except Exception as e:                  # noqa: BLE001
            return self._upstream_fail(h, e)
        if body[:2] == b"\x1f\x8b":
            try:
                body = gzip.decompress(body)[:MAX_M3U]
            except OSError:
                return self._err(h, 502, "provider sent a broken playlist")
        user, secret = query["username"], query["password"]
        link = self._linker(pid, user, secret)
        tprefix = f"/xt/{pid}/t/{user}/{secret}/"
        absol = lambda p: origin + p            # noqa: E731  (the TV stores these: absolute addresses)
        text = rewrite_m3u(body.decode("utf-8", "replace"), lambda u: absol(link(u)),
                           lambda t: absol(template_link(t, self.seal, tprefix)), lambda u: absol(link(u)))
        self._send(h, 200, text.encode(), "audio/x-mpegurl; charset=utf-8", gz=True)

    def _xmltv(self, h, pid, prov, dev, query, raw_query, origin):
        if prov.get("kind") == "xtream" and "n" not in query:
            from tvbro_server import parsers as P
            url = f"{P.normalize_server(prov['server'])}/xmltv.php?" + urllib.parse.urlencode(
                {"username": prov["user"], "password": prov["pass"]})
        else:
            urls = split_epg(prov.get("epgUrl") or "")
            try:
                url = urls[int(query.get("n", "0"))]
            except (ValueError, IndexError):
                return self._err(h, 404, "no guide address")
        return self.relay(h, url, dev, pid, None, None, guide=True)

    # ---- media relay ----
    def relay(self, h, url: str, dev: str, pid: str, what: Optional[str], link: Optional[Callable[[str], str]],
              guide: bool = False):
        """Pipes the provider's answer to the TV: one chunk at a time, Range passed on, HLS playlists rewritten."""
        hdrs = {}
        if h.headers.get("Range") and not guide:
            hdrs["Range"] = h.headers["Range"][:100]
        ua = h.headers.get("User-Agent")
        if ua and not ua.startswith("Orbit-TV"):
            hdrs["User-Agent"] = ua[:200]       # the TV's player, as when it talked to the provider itself
        if guide and "gzip" in (h.headers.get("Accept-Encoding") or ""):
            hdrs["Accept-Encoding"] = "gzip"
        try:
            up = self.open_url(url, timeout=30, headers=hdrs)
        except Exception as e:                  # noqa: BLE001
            if isinstance(e, urllib.error.HTTPError) and e.code == 416:
                cr = e.headers.get("Content-Range") if e.headers else None
                e.close()
                return self._send(h, 416, b"", "text/plain", {"Content-Range": cr} if cr else None)
            return self._upstream_fail(h, e)
        name = self.keys.device_name(dev)
        try:
            ct = (up.headers.get("Content-Type") or "").split(";", 1)[0].strip().lower()
            final = up.geturl() or url
            looks_hls = "mpegurl" in ct or _ext(final) == ".m3u8" or _ext(url) == ".m3u8"
            if link is not None and looks_hls:
                data = up.read(MAX_HLS)
                if b"#EXTM3U" in data[:64]:
                    self.relays.seen(dev, name, pid, what)
                    return self._send(h, 200, rewrite_hls(data, final, link), "application/vnd.apple.mpegurl")
                return self._send(h, up.getcode() or 200, data, ct if ct.startswith(MEDIA_CTYPES) else "application/octet-stream")
            if guide:
                ctype = ct if ct.startswith(XML_CTYPES) else "application/octet-stream"
            else:
                ctype = ct if ct.startswith(MEDIA_CTYPES) else ("video/mp2t" if not ct else "application/octet-stream")
            h.send_response(up.getcode() or 200)
            h.send_header("Content-Type", ctype)
            for k in ("Content-Length", "Content-Range", "Accept-Ranges") + (("Content-Encoding",) if guide else ()):
                v = up.headers.get(k)
                if v:
                    h.send_header(k, v)
            if not up.headers.get("Content-Length"):
                h.send_header("Connection", "close")       # a live stream ends when the connection does
                h.close_connection = True
            h.send_header("Cache-Control", "no-store")
            h.end_headers()
            if h.command == "HEAD":
                return
            self._pipe(h, up, dev, name, pid, what, media=not guide)
        finally:
            up.close()

    def _pipe(self, h, up, dev, name, pid, what, media: bool):
        read = getattr(up, "read1", None) or up.read      # read1: whatever has arrived, so no added latency
        if media:
            self.relays.seen(dev, name, pid, what, opened=1)
        n = 0
        try:
            while True:
                chunk = read(CHUNK)
                if not chunk:
                    break
                h.wfile.write(chunk)            # blocks while the TV is not reading: backpressure to the provider
                n += 1
                if n % CHECK_EVERY == 0:
                    if not self.keys.alive(dev):
                        h.close_connection = True
                        break                   # unlinked meanwhile: the relay ends
                    if media:
                        self.relays.seen(dev, name, pid, None)
        except (BrokenPipeError, ConnectionResetError, TimeoutError, OSError):
            h.close_connection = True
        finally:
            if media:
                self.relays.seen(dev, name, pid, None, opened=-1)
