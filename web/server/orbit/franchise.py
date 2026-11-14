# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""Franchise grouping of IPTV films for the catalogue's Collections (orbit/catalog.py).

Providers send no collections, so films are grouped by title, conservatively: a wrong merge ("Super" with
"Super 8") is worse than a missed one. Only these title shapes group, and only when at least two *different*
titles end up together:

- ``<Base> and the <rest>`` / ``<Base> & the <rest>`` ("Harry Potter and the ...", "Indiana Jones and the ...")
- ``<Base>: <subtitle>`` ("Pirates of the Caribbean: ...", "Star Trek: ...")
- numbered sequels ``<Base> 2`` .. ``<Base> 9``, ``<Base> II`` .. ``<Base> IX``, ``<Base> Part 2`` / ``Part II`` /
  ``Chapter 2``, also after a colon or dash ("<Base>: Part II", "<Base> - Part 2")
- a film titled exactly ``<Base>`` joins a group that one of the shapes above made ("Toy Story" + "Toy Story 2")

Guards: the base needs two significant words ("the", "a", "of" ... do not count), except numbered series, which
may have a one-word base only when the series has the 2 *and* the 3 ("Rocky", "Rocky II", "Rocky III"). Numbered
series must contain number 2. Generic bases ("The Movie", "Live", "Christmas" ...) never group. Provider tags
("EN - ", "|4K|", "[MULTI]") and a trailing year are ignored for matching.

A shared TMDb collection id beats all of this (``group`` takes it as ``tmdb_of``)."""
from __future__ import annotations

import hashlib
import re
import unicodedata
from typing import Callable, Iterable, Optional

ROMAN = {"i": 1, "one": 1, "ii": 2, "iii": 3, "iv": 4, "v": 5, "vi": 6, "vii": 7, "viii": 8, "ix": 9}
SMALL = {"the", "a", "an", "of", "and", "&", "in", "on", "to", "for", "at", "by", "with", "la", "le", "el", "der",
         "die", "das"}
GENERIC = {"the movie", "movie", "film", "the film", "live", "concert", "documentary", "special", "christmas",
           "a christmas", "untitled", "episode", "part", "chapter", "volume", "vol", "the best of", "best of",
           "making of", "the making of", "behind the scenes", "trailer"}

# provider tags in front: "EN - ", "EN: ", "|EN| ", "[4K] ", "4K - ", "MULTI - ", "NL | "
_TAG = re.compile(r"^\s*(?:[\[|(]\s*[A-Za-z0-9+]{1,8}\s*[\]|)]\s*[-:|]?\s*|"
                  r"(?:[A-Z]{2,3}|4K|UHD|FHD|HD|SD|MULTI|VOSTFR|3D)\s*(?:-|\||:)\s+)")
_TAIL = re.compile(r"\s*(?:[(\[]\s*(?:19|20)\d\d\s*[)\]]|[(\[]\s*(?:4K|UHD|FHD|HD|SD|MULTI|3D|HDR|DV)\s*[)\]]"
                   r"|-\s*(?:19|20)\d\d)\s*$", re.I)
_QUALITY_WORD = re.compile(r"\s+(?:4K|UHD|FHD|HDR|1080p|720p|2160p)\s*$", re.I)
_NUM = re.compile(r"^(?P<base>.+?)(?:\s*[:\-]\s*|\s+)(?:(?P<word>part|chapter|pt\.?|vol\.?|volume)\s+)?"
                  r"(?P<n>[1-9]|i|ii|iii|iv|v|vi|vii|viii|ix|one)$", re.I)
_AND_THE = re.compile(r"^(?P<base>.+?)\s+(?:and|&)\s+the\s+\S.*$", re.I)
_COLON = re.compile(r"^(?P<base>[^:]+?)\s*:\s+\S.*$")


def strip_tags(title: str) -> str:
    """The title without provider tags in front and a year / quality tag at the end."""
    s = unicodedata.normalize("NFKC", str(title or "")).replace("’", "'").replace("‘", "'").strip()
    for _ in range(3):
        t = _TAG.sub("", s, count=1)
        if t == s or not t.strip():
            break
        s = t.strip()
    for _ in range(3):
        t = _QUALITY_WORD.sub("", _TAIL.sub("", s)).strip()
        if t == s or not t:
            break
        s = t
    return s


def norm(s: str) -> str:
    """Lower case, accents and punctuation folded, single spaces (for comparing titles and bases)."""
    s = unicodedata.normalize("NFKD", s.lower())
    s = "".join(c for c in s if not unicodedata.combining(c))
    s = re.sub(r"[^\w&' ]+", " ", s).replace("'", "")
    return re.sub(r"\s+", " ", s).strip()


def _words(base_norm: str) -> int:
    return sum(1 for w in base_norm.split() if w not in SMALL)


def _num(token: str) -> int:
    t = token.lower()
    return int(t) if t.isdigit() else ROMAN.get(t, 0)


def shape(title: str) -> tuple:
    """(kind, base display, base key, number) of a title: kind "and" / "colon" / "num" / "plain"."""
    s = strip_tags(title)
    m = _AND_THE.match(s)
    if m:
        base = m.group("base").strip()
        return "and", base, norm(base), 0
    m = _NUM.match(s)
    if m and m.group("base").strip(" :-"):
        base = m.group("base").strip(" :-")
        n = _num(m.group("n"))
        # "Toy Story 2", "Rocky II", "Kill Bill: Vol. 2"; never "Apollo 13" (the base would end in a digit).
        # Number 1 counts only with a word in front ("Part 1", "Vol. 1"): "Rocky I" alone is too rare to trust.
        if not re.search(r"\d$", base) and (n >= 2 or m.group("word")):
            c = _COLON.match(base)
            if c:                      # "The Hunger Games: Mockingjay - Part 2" belongs to "The Hunger Games"
                b = c.group("base").strip()
                return "colon", b, norm(b), 0
            return "num", base, norm(base), n
    m = _COLON.match(s)
    if m:
        base = m.group("base").strip()
        return "colon", base, norm(base), 0
    return "plain", s, norm(s), 0


def gid(kind: str, base_key: str) -> str:
    return "t." + hashlib.sha1(f"{kind}|{base_key}".encode()).hexdigest()[:12]


def _titles(members: list, title_of: Callable) -> int:
    return len({norm(strip_tags(title_of(f))) for f in members})


def group(films: Iterable, title_of: Callable, tmdb_of: Optional[Callable] = None) -> dict:
    """{group id: {"name", "members": [film], "tmdb": collection id or None}} for groups of 2+ different titles.

    ``films``: any objects; ``title_of(f)`` their title; ``tmdb_of(f)`` -> (collection id, name) or None.
    Films with a TMDb collection are grouped by it and never by title."""
    out: dict = {}
    rest = []
    for f in films:
        t = tmdb_of(f) if tmdb_of else None
        if t:
            g = out.setdefault(f"tmdb.{t[0]}", {"name": t[1] or "", "members": [], "tmdb": t[0]})
            g["members"].append(f)
        else:
            rest.append(f)
    out = {k: v for k, v in out.items() if _titles(v["members"], title_of) >= 2}
    cands: dict = {}       # base key -> {"name", "num": {n: [films]}, "and": [..], "colon": [..], "plain": [..]}
    for f in rest:
        kind, base, key, n = shape(title_of(f))
        if not key or key in GENERIC or len(key) < 3:
            continue
        c = cands.setdefault(key, {"name": base, "num": {}, "and": [], "colon": [], "plain": []})
        if kind == "num":
            c["num"].setdefault(n, []).append(f)
        else:
            c[kind].append(f)
    for key, c in cands.items():
        words = _words(key)
        nums = c["num"]
        members, need = [], 2
        if nums and 2 in nums and (words >= 2 or 3 in nums):
            for v in nums.values():
                members += v
        if words >= 2:
            members += c["colon"]
            if c["and"]:
                members += c["and"]
                if not nums and not c["colon"]:
                    need = 3           # "Snow White and the Huntsman" + "... and the Seven Dwarfs" is not a series
        if not members:
            continue
        members += c["plain"]
        if _titles(members, title_of) >= need:
            out[gid("movie", key)] = {"name": c["name"], "members": members, "tmdb": None}
    return out