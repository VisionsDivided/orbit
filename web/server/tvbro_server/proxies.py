# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""Who is really asking when the server runs behind reverse proxies (review L7).

``ORBIT_TRUSTED_PROXIES`` is a comma-separated list of addresses or CIDR ranges (``127.0.0.1/32, 192.0.2.0/24``).
The default trusts loopback only: the Orbit image runs its own nginx in front of Python on 127.0.0.1.

- When the direct peer is **not** trusted, every forwarding header is ignored: the peer is the client, and nobody
  can pick their own address by sending ``X-Forwarded-For``.
- When it is trusted, the client is the right-most address in ``X-Forwarded-For`` that is not trusted itself.
  Each proxy appends the address it saw, so everything right of the client was written by trusted proxies and
  anything left of it may be made up by the client. (When every address is trusted, the left-most one is the
  client; a malformed entry stops the walk and the last trusted address counts.)
- A trusted peer that sends no ``X-Forwarded-For`` but ``X-Real-IP`` (older tvBro nginx configs) is believed for
  that one hop.

The result is used for sign-in and pairing throttles, the pairing "address" shown before a TV is approved, and logs.
"""
from __future__ import annotations

import ipaddress
import logging
import os
from typing import Iterable, Optional

LOG = logging.getLogger("tvbro.proxies")

ENV = "ORBIT_TRUSTED_PROXIES"
DEFAULT = "127.0.0.1/32,::1/128"
MAX_HOPS = 20                       # never walk a huge header


def parse(spec: Optional[str]) -> tuple:
    """The networks in a comma-separated list. Bad entries are logged and skipped."""
    out = []
    for part in (spec or "").split(","):
        part = part.strip()
        if not part:
            continue
        try:
            out.append(ipaddress.ip_network(part, strict=False))
        except ValueError:
            LOG.warning("%s: %r is not an address or CIDR range; ignored", ENV, part[:60])
    return tuple(out)


_cache: tuple = (None, ())


def from_env() -> tuple:
    """The trusted networks from ``ORBIT_TRUSTED_PROXIES`` (parsed again only when the variable changes)."""
    global _cache
    spec = os.environ.get(ENV)
    spec = DEFAULT if spec is None else spec
    if _cache[0] != spec:
        _cache = (spec, parse(spec))
    return _cache[1]


def _ip(s: str):
    """An address from a header entry (``192.0.2.1``, ``[2001:db8::1]``, ``192.0.2.1:5000``), or None."""
    s = s.strip().strip('"')
    if not s or len(s) > 64:
        return None
    if s.startswith("["):
        s = s[1:].split("]", 1)[0]
    elif s.count(":") == 1:              # IPv4 with a port
        s = s.split(":", 1)[0]
    try:
        ip = ipaddress.ip_address(s.split("%", 1)[0])
    except ValueError:
        return None
    if ip.version == 6 and ip.ipv4_mapped:
        ip = ip.ipv4_mapped
    return ip


def trusted(ip, nets: Iterable) -> bool:
    return ip is not None and any(ip.version == n.version and ip in n for n in nets)


def client_ip(peer: str, forwarded_for: Iterable[str] = (), real_ip: str = "", nets: Optional[tuple] = None) -> str:
    """The client's address for a request from ``peer`` with these header values (each X-Forwarded-For header
    line may hold several comma-separated addresses; several lines count in order)."""
    nets = from_env() if nets is None else nets
    p = _ip(peer or "")
    if not trusted(p, nets):
        return str(p) if p is not None else (peer or "")[:64]
    hops = [h for line in forwarded_for if line for h in line.split(",")]
    if not hops:
        r = _ip(real_ip or "")
        return str(r) if r is not None else str(p)
    last = p
    for h in reversed(hops[-MAX_HOPS:]):
        ip = _ip(h)
        if ip is None:
            return str(last)
        if not trusted(ip, nets):
            return str(ip)
        last = ip
    return str(last)
