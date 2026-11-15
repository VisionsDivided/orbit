# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""Every outbound fetch the server makes for a playlist goes through here (playlist, guide,
provider API, images, streams, HLS segments, recordings).

- Only ``http`` and ``https``: the opener has no file, ftp or data handler, and every URL
  (also each redirect hop) is checked before it is opened.
- Ports 80, 443 and 1024-65535 only.
- The host is resolved here and every address is checked: loopback, link-local (incl. the
  169.254.169.254 metadata address), multicast, unspecified and reserved addresses are always
  refused; private ranges (RFC 1918, CGNAT 100.64/10, IPv6 ULA) only when the admin allowed LAN
  sources (Settings, or ``TVBRO_ALLOW_PRIVATE_SOURCES=1``). The connection then goes to the
  address that was checked, so a DNS answer that changes in between (DNS rebinding) cannot
  redirect it, and a redirect is checked again the same way.
- ``TVBRO_ALLOW_LOOPBACK_SOURCES=1`` exists only for the test suite's mock provider.
"""
from __future__ import annotations

import contextlib
import contextvars
import http.client
import ipaddress
import os
import socket
import urllib.error
import urllib.parse
import urllib.request
from typing import Callable, Optional

UA = "tvBro-web/1.0"
SCHEMES = ("http", "https")
_CGNAT = ipaddress.ip_network("100.64.0.0/10")
_NAT64 = ipaddress.ip_network("64:ff9b::/96")

_allow_private_setting: Callable[[], bool] = lambda: False
# Orbit: exact (host, port) pairs the admin configured as media servers (Plex, Jellyfin, Seerr on the LAN)
_private_hosts: Callable[[], set] = lambda: set()


class Blocked(urllib.error.URLError):
    """A URL or address the server will not fetch."""

    def __init__(self, why: str):
        super().__init__(why)
        self.why = why

    def __str__(self):
        return f"blocked: {self.why}"


def set_allow_private(fn: Callable[[], bool]):
    """The admin setting "Allow sources on the local network" (read at every fetch)."""
    global _allow_private_setting
    _allow_private_setting = fn


def set_private_hosts(fn: Callable[[], set]):
    """Orbit: the configured LAN media servers, read at every fetch. Only these may resolve to private (RFC 1918 /
    ULA / CGNAT) addresses when "local network sources" is off; loopback and link-local stay refused for them too.

    Each entry is ``(host, port)`` (any private address, e.g. the Seerr address an admin typed) or
    ``(host, port, ips)``: pinned, so only the addresses in ``ips`` (recorded when an owner or admin linked it)
    may be private. A pinned name that re-resolves to another LAN address (DNS rebinding) is refused."""
    global _private_hosts
    _private_hosts = fn


# Orbit: (host, port) pairs allowed on the LAN for the code running in this context only (one thread / request),
# e.g. a Seerr address being tested before it is saved. Other requests never see them.
_scoped_hosts: contextvars.ContextVar = contextvars.ContextVar("tvbro_scoped_private_hosts", default=frozenset())


@contextlib.contextmanager
def private_host_here(host: str, port: int):
    """Allow ``host:port`` to resolve to a private address, only for fetches made inside this ``with`` block by
    this thread (the global allowlist is not touched). Loopback and link-local stay refused."""
    key = (str(host).lower().strip("[]"), int(port))
    token = _scoped_hosts.set(_scoped_hosts.get() | {key})
    try:
        yield
    finally:
        _scoped_hosts.reset(token)


_in_scope: contextvars.ContextVar = contextvars.ContextVar("tvbro_private_scope", default=False)


@contextlib.contextmanager
def private_hosts_scope(hosts=()):
    """Like ``private_host_here`` for several (host, port) pairs, and ``allow_here`` may add more inside the block
    (a sign-in that discovers its server's address on the way). Everything is gone when the block ends."""
    token = _scoped_hosts.set(_scoped_hosts.get() | {(str(h).lower().strip("[]"), int(p)) for h, p in hosts})
    flag = _in_scope.set(True)
    try:
        yield
    finally:
        _in_scope.reset(flag)
        _scoped_hosts.reset(token)


def allow_here(host: str, port: int) -> None:
    """Inside ``private_hosts_scope`` only: allow one more (host, port) for the rest of that block."""
    if not _in_scope.get():
        raise RuntimeError("allow_here outside private_hosts_scope")
    _scoped_hosts.set(_scoped_hosts.get() | {(str(host).lower().strip("[]"), int(port))})


ANY = None                                 # _listed: any private address is allowed for this host


def norm_ip(addr: str) -> str:
    """An address in one spelling (compressed, no zone), for comparing pinned addresses; "" when not an address."""
    try:
        return ipaddress.ip_address(str(addr).split("%", 1)[0].strip("[]")).compressed
    except ValueError:
        return ""


def _listed(host: str, port: int):
    """False: not listed. ANY: listed without pinning (any private address). A frozenset: only these addresses."""
    try:
        key = (str(host).lower().strip("[]"), int(port))
        if key in _scoped_hosts.get():
            return ANY
        pinned: set = set()
        found = False
        for e in _private_hosts():
            if (str(e[0]).lower().strip("[]"), int(e[1])) != key:
                continue
            if len(e) < 3:
                return ANY
            found = True
            pinned |= {norm_ip(a) for a in e[2]}
        return frozenset(pinned - {""}) if found else False
    except Exception:                       # noqa: BLE001
        return False


def _host_listed(host: str, port: int) -> bool:
    """Listed in some form (scoped, or in the global allowlist, pinned or not)."""
    return _listed(host, port) is not False


def allow_private() -> bool:
    if os.environ.get("TVBRO_ALLOW_PRIVATE_SOURCES") == "1":
        return True
    try:
        return bool(_allow_private_setting())
    except Exception:                       # noqa: BLE001
        return False


def _allow_loopback() -> bool:
    return os.environ.get("TVBRO_ALLOW_LOOPBACK_SOURCES") == "1"


def is_blocked(e: BaseException) -> bool:
    """True for Blocked itself or a URLError that wraps one (urllib wraps connect errors)."""
    while e is not None:
        if isinstance(e, Blocked):
            return True
        e = getattr(e, "reason", None) if isinstance(e, urllib.error.URLError) else None
    return False


def ip_allowed(addr: str, private_ok: Optional[bool] = None) -> bool:
    try:
        ip = ipaddress.ip_address(addr.split("%", 1)[0])
    except ValueError:
        return False
    if ip.version == 6:
        if ip.ipv4_mapped:
            ip = ip.ipv4_mapped
        elif ip in _NAT64:
            ip = ipaddress.IPv4Address(int(ip) & 0xFFFFFFFF)
        elif ip.sixtofour:
            ip = ip.sixtofour
    if ip.is_loopback:
        return _allow_loopback()
    if ip.is_link_local or ip.is_multicast or ip.is_unspecified or ip.is_reserved:
        return False
    if ip.is_global and ip not in _CGNAT:
        return True
    private = ip.is_private or ip in _CGNAT
    if private_ok is None:
        private_ok = allow_private()
    return private and private_ok


def check_url(url: str) -> urllib.parse.ParseResult:
    """Scheme, host and port rules (no DNS). Raises Blocked."""
    if not isinstance(url, str) or not url or any(c in url for c in "\r\n\t\x00 "):
        raise Blocked("bad url")
    try:
        u = urllib.parse.urlparse(url)
        port = u.port
    except ValueError:
        raise Blocked("bad url")
    if u.scheme.lower() not in SCHEMES:
        raise Blocked("only http and https are allowed")
    if not u.hostname:
        raise Blocked("no host")
    if port is not None and not (port in (80, 443) or 1024 <= port <= 65535):
        raise Blocked("port not allowed")
    return u


def resolve(host: str, port: int) -> list[tuple]:
    """getaddrinfo, refusing the host when any of its addresses is not allowed."""
    try:
        infos = socket.getaddrinfo(host, port, 0, socket.SOCK_STREAM)
    except socket.gaierror as e:
        raise urllib.error.URLError(e)
    if not infos:
        raise Blocked("host does not resolve")
    listed = False if allow_private() else _listed(host, port)
    private_ok = allow_private() or listed is ANY
    for info in infos:
        addr = info[4][0]
        if ip_allowed(addr, private_ok):
            continue
        if isinstance(listed, frozenset) and norm_ip(addr) in listed and ip_allowed(addr, True):
            continue                         # a pinned LAN server at an address recorded when it was linked
        raise Blocked("address not allowed (local network, loopback or link-local)")
    return infos


def addresses(host: str, port: int) -> list[str]:
    """What ``host`` resolves to now (normalised), without any allow / refuse check; [] when it does not resolve."""
    try:
        infos = socket.getaddrinfo(host, port, 0, socket.SOCK_STREAM)
    except (socket.gaierror, UnicodeError, OSError):
        return []
    return sorted({norm_ip(i[4][0]) for i in infos} - {""})


def lan_address(addr: str) -> bool:
    """True for an address that is not public (private, CGNAT, ULA or loopback): one that only a pinned LAN
    allowance may reach."""
    try:
        ip = ipaddress.ip_address(str(addr).split("%", 1)[0])
    except ValueError:
        return False
    if ip.version == 6 and ip.ipv4_mapped:
        ip = ip.ipv4_mapped
    return ip.is_loopback or ((ip.is_private or ip in _CGNAT) and not ip.is_link_local)


def check_host(url: str) -> None:
    """check_url plus the address check (for ffmpeg / ffprobe, which connect by themselves)."""
    u = check_url(url)
    resolve(u.hostname, u.port or (443 if u.scheme.lower() == "https" else 80))


def _safe_create_connection(address, timeout=socket._GLOBAL_DEFAULT_TIMEOUT, source_address=None, *a, **k):
    host, port = address
    err = None
    for family, socktype, proto, _c, sa in resolve(host, port):
        sock = None
        try:
            sock = socket.socket(family, socktype, proto)
            if timeout is not socket._GLOBAL_DEFAULT_TIMEOUT:
                sock.settimeout(timeout)
            sock.connect(sa)                 # the checked address, not a second lookup
            return sock
        except OSError as e:
            err = e
            if sock is not None:
                sock.close()
    raise err or OSError("connect failed")


class _HTTPConnection(http.client.HTTPConnection):
    def __init__(self, *a, **k):
        super().__init__(*a, **k)
        self._create_connection = _safe_create_connection


class _HTTPSConnection(http.client.HTTPSConnection):
    def __init__(self, *a, **k):
        super().__init__(*a, **k)
        self._create_connection = _safe_create_connection


class _HTTPHandler(urllib.request.HTTPHandler):
    def http_open(self, req):
        return self.do_open(_HTTPConnection, req)


class _HTTPSHandler(urllib.request.HTTPSHandler):
    def https_open(self, req):
        return self.do_open(_HTTPSConnection, req, context=self._context)


class _RedirectHandler(urllib.request.HTTPRedirectHandler):
    max_redirections = 6

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        newurl = lower_host(newurl)
        check_url(newurl)                    # the address is checked again when it connects
        new = super().redirect_request(req, fp, code, msg, headers, newurl)
        if new is not None and _origin(newurl) != _origin(req.full_url):
            # a media-server token or login header must not follow a redirect to another host
            for k in list(new.headers):
                if k.lower() in SECRET_HEADERS:
                    del new.headers[k]
        return new


SECRET_HEADERS = {"authorization", "x-emby-authorization", "x-emby-token", "x-mediabrowser-token", "x-plex-token",
                  "cookie", "x-api-key"}


def _origin(url: str) -> tuple:
    try:
        u = urllib.parse.urlsplit(url)
        return (u.scheme.lower(), (u.hostname or "").lower(), u.port or (443 if u.scheme.lower() == "https" else 80))
    except ValueError:
        return ("", "", 0)


def _build():
    od = urllib.request.OpenerDirector()
    for h in (_HTTPHandler(), _HTTPSHandler(), _RedirectHandler(), urllib.request.HTTPDefaultErrorHandler(),
              urllib.request.HTTPErrorProcessor(), urllib.request.UnknownHandler()):
        od.add_handler(h)
    return od


_OPENER = _build()


def lower_host(url: str) -> str:
    """The URL with its host name in lower case (path, query and login unchanged). Host names are
    case-insensitive, but Python sends the TLS SNI name as typed, and some HTTPS servers fail the
    handshake ("tlsv1 alert internal error") when it has capitals, e.g. a provider address typed
    with a capital first letter on a phone. Android's HTTP stack lowercases it, so TVs worked."""
    try:
        p = urllib.parse.urlsplit(url)
    except ValueError:
        return url
    at = p.netloc.rfind("@")
    user, hostport = (p.netloc[:at + 1], p.netloc[at + 1:]) if at >= 0 else ("", p.netloc)
    if hostport == hostport.lower():
        return url
    return urllib.parse.urlunsplit((p.scheme, user + hostport.lower(), p.path, p.query, p.fragment))


def open_url(url: str, timeout: float = 30, headers: Optional[dict] = None, data: Optional[bytes] = None,
             method: Optional[str] = None):
    """urlopen for provider / playlist / image / media-server URLs, with the rules above. Raises Blocked
    (or a URLError wrapping it), urllib.error.HTTPError, or other URLErrors. ``data`` / ``method`` for the
    media-server APIs (POST / DELETE); credential headers are dropped on a redirect to another host."""
    url = lower_host(url)
    check_url(url)
    req = urllib.request.Request(url, data=data, method=method, headers={"User-Agent": UA, **(headers or {})})
    return _OPENER.open(req, timeout=timeout)


def read_capped(resp, limit: int) -> bytes:
    """The whole body, refusing more than ``limit`` bytes."""
    data = resp.read(limit + 1)
    if len(data) > limit:
        raise ValueError(f"the answer is larger than {limit // (1024 * 1024)} MB")
    return data
