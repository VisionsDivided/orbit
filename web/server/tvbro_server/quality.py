# Copyright 2026 The Orbit authors. SPDX-License-Identifier: Apache-2.0
"""Playback quality for films and episodes, with a lower default away from home.

At home a film is sent as it is (often 8-20 Mbps). Over Tailscale or the internet that is usually more than the
link carries, so the player waits for a long time before the picture starts. A device that is away gets a
transcode capped at ``ORBIT_AWAY_QUALITY`` (default 4 Mbps, 720p) unless it chose a level itself.

"Away": the client's address is public, in a VPN range (Tailscale's 100.64.0.0/10 and fd7a:115c:a1e0::/48 by
default), or in ``ORBIT_AWAY_NETS`` (comma-separated addresses or CIDR ranges). The last one is for a VPN that
arrives through a router doing NAT, so remote devices look like the router's LAN address: list that address.

Levels: "orig" (no cap), "8" (8 Mbps, 1080p), "4" (4 Mbps, 720p), "2" (2 Mbps, 480p); "auto" (or nothing) picks
"orig" at home and the away level otherwise."""
from __future__ import annotations

import ipaddress
import os
from typing import Optional

# level -> (video kbps, height); "orig" is no cap
LEVELS: dict[str, Optional[tuple[int, int]]] = {"orig": None, "8": (8000, 1080), "4": (4000, 720), "2": (2000, 480)}
LABELS = {"orig": "Original", "8": "1080p · 8 Mbps", "4": "720p · 4 Mbps", "2": "480p · 2 Mbps"}
WIDTH = {1080: 1920, 720: 1280, 480: 854}
VPN_NETS = "100.64.0.0/10, fd7a:115c:a1e0::/48"
ENV_NETS, ENV_LEVEL = "ORBIT_AWAY_NETS", "ORBIT_AWAY_QUALITY"
_cache: dict = {}


def _nets(spec: str) -> tuple:
    out = []
    for part in (spec or "").replace(";", ",").split(","):
        part = part.strip()
        if not part:
            continue
        try:
            out.append(ipaddress.ip_network(part, strict=False))
        except ValueError:
            continue
    return tuple(out)


def away_nets() -> tuple:
    spec = VPN_NETS + "," + os.environ.get(ENV_NETS, "")
    if _cache.get("spec") != spec:
        _cache["spec"], _cache["nets"] = spec, _nets(spec)
    return _cache["nets"]


def is_away(addr: str) -> bool:
    """True when ``addr`` is not on the home network (see the module comment). Unknown: home."""
    try:
        ip = ipaddress.ip_address((addr or "").split("%", 1)[0].strip("[]"))
    except ValueError:
        return False
    if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped:
        ip = ip.ipv4_mapped
    if any(ip in n for n in away_nets() if n.version == ip.version):
        return True
    return not (ip.is_private or ip.is_loopback or ip.is_link_local)


def away_level() -> str:
    v = (os.environ.get(ENV_LEVEL) or "4").strip().lower()
    return v if v in LEVELS else "4"


def choose(choice, addr: str) -> dict:
    """{"level", "cap": (kbps, height) | None, "away": bool, "label"} for a requested level ("auto" / None / a
    level; anything else counts as "auto") and the client's address."""
    away = is_away(addr)
    c = str(choice or "auto").strip().lower()
    level = c if c in LEVELS else (away_level() if away else "orig")
    return {"level": level, "cap": LEVELS[level], "away": away, "label": LABELS[level]}


def resolution(height: int) -> str:
    """"1280x720" for 720 (the size a capped transcode fits in)."""
    return f"{WIDTH.get(height, round(height * 16 / 9))}x{height}"


def public(q: dict) -> dict:
    """The part of choose()'s answer a client sees."""
    return {"level": q["level"], "away": q["away"], "label": q["label"]}
