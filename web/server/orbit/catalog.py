# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""The catalogue for the redesign's Home, Movies and Shows screens (docs/orbit/API-v1.md, "Catalogue").

Sources:
- IPTV films and series of the household's playlists: the provider catalogues tvBro already keeps in memory
  (``Provider.vod``, refreshed and stored by its own sync jobs; nothing is downloaded here).
- The signed-in account's Plex / Jellyfin libraries (orbit/media.py connections), fetched in the background.
- The profile's own state: My list and film resume points (StateStore), show progress (SeriesStore).

Speed: an **index** (items, every sort order, per-genre top lists, collections) is built once per catalogue
version, in the background when a catalogue changes (``check`` runs every minute and on requests), and answers
are made from memory. Per account the IPTV index and that account's media-server items are merged once per
version. Per profile, answers are cached for VIEW_TTL seconds, keyed by the profile state's revision (orbit/sync.py),
so a change in My list or a resume point shows at once.

Kids profiles: IPTV items in VOD categories locked with the parental PIN are left out entirely (while a PIN is
set). Other profiles see them with ``"locked": true`` (playing still needs the PIN, as in the web app). Plex and
Jellyfin apply their own user's restrictions; there is no extra filter for them."""
from __future__ import annotations

import json
import logging
import re
import threading
import time
import urllib.parse
from collections import OrderedDict
from typing import Callable, Iterable, Optional

from tvbro_server.secfile import write_secret

from . import franchise as FR
from . import sync as SYNC
from .accounts import ApiError
from .api import Call, Reply

LOG = logging.getLogger("orbit.catalog")

KINDS = {"movies": "movie", "shows": "show"}
SORTS = ("added", "released", "title", "rating")
SOURCES = {"all": None, "local": ("plex", "jellyfin"), "iptv": ("iptv",), "plex": ("plex",), "jellyfin": ("jellyfin",)}
ROW = 24                  # items per row
HERO = 6
PAGE, MAX_PAGE = 60, 200
GENRE_ROWS = 3            # "Top in <genre>" rows on Movies / Shows (Home: 2)
VIEW_TTL = 30             # per-profile answers
VIEW_MAX = 512
LOCAL_TTL = 600           # media-server libraries are fetched again after this long
CONT_TTL = 60             # their "continue watching" after this long
LOCAL_WAIT = 3.0          # the first request of an account waits this long for its libraries
LOCAL_MAX = 20000         # items per media-server connection
_CID = re.compile(r"^[A-Za-z0-9._-]{1,100}$")
_SPLIT = re.compile(r"\s*[,/|;]\s*")


# --------------------------------------------------------------------------- items

class CItem:
    """One film or show of the catalogue. ``base`` is its JSON without the per-profile parts."""
    __slots__ = ("key", "kind", "title", "year", "rating", "gkeys", "source", "added", "rel", "id", "conn", "tmdb",
                 "cat", "sid", "sortt", "poster", "backdrop", "colors", "base", "rkeys")

    def __init__(self, key, kind, title, year, poster, backdrop, rating, genres, gkeys, source, added, released,
                 colors, id, conn=None, tmdb=None, cat="", sid="", rkeys=()):
        self.key, self.kind, self.title, self.year = key, kind, title, year
        self.poster, self.backdrop, self.colors = poster, backdrop, colors
        self.rating, self.gkeys, self.source, self.added = rating, gkeys, source, added
        self.rel = released or (str(year) if year else "")
        self.id, self.conn, self.tmdb, self.cat, self.sid, self.rkeys = id, conn, tmdb, cat, sid, rkeys
        self.sortt = FR.norm(FR.strip_tags(title)) or title.lower()
        self.base = {"key": key, "kind": kind, "title": title, "year": year, "poster": poster, "backdrop": backdrop,
                     "rating": rating, "genres": genres, "source": source, "added": added, "released": released,
                     "colors": colors, "id": id, "conn": conn}


class Genres:
    """One display name per genre key ("science fiction" -> "Science Fiction"), shared by every item."""

    def __init__(self):
        self.names: dict = {}

    def parse(self, raw) -> tuple:
        if isinstance(raw, str):
            parts = _SPLIT.split(raw)
        elif isinstance(raw, (list, tuple)):
            parts = [str(x) for x in raw]
        else:
            return (), ()
        names, keys = [], []
        for p in parts:
            p = re.sub(r"\s+", " ", p).strip(" .-")
            if not p or len(p) > 40 or p.isdigit():
                continue
            k = p.lower()
            if k in keys:
                continue
            if k not in self.names:
                self.names[k] = p.title() if p.islower() or p.isupper() else p
            names.append(self.names[k])
            keys.append(k)
            if len(keys) >= 6:
                break
        return names, tuple(keys)


def _rating(v) -> Optional[float]:
    try:
        f = float(v)
    except (TypeError, ValueError):
        return None
    return round(f, 1) if 0 < f <= 10 else None


def _year(v) -> Optional[int]:
    try:
        y = int(str(v)[:4])
    except (TypeError, ValueError):
        return None
    return y if 1900 <= y <= 2100 else None


_YEAR_TAIL = re.compile(r"\s*[(\[]\s*(?:19|20)\d\d\s*[)\]]\s*$")


def iptv_item(v, genres: Genres) -> CItem:
    """A catalogue item from a tvBro VodItem (parsers.py)."""
    movie = v.kind == "movie"
    names, keys = genres.parse(v.genre)
    title = _YEAR_TAIL.sub("", v.name).strip() or v.name
    year = _year(v.year)
    rkeys = (v.key, "m|" + v.id) if movie else ()
    return CItem(v.key, "movie" if movie else "show", title, year, v.poster or None, getattr(v, "backdrop", None),
                 _rating(v.rating), names, keys, "iptv", getattr(v, "added", None), getattr(v, "released", None), None,
                 v.id, tmdb=getattr(v, "tmdb", None), cat=v.category, sid=v.source_id, rkeys=rkeys)


def media_img(cid: str, path) -> Optional[str]:
    return "/v1/media/image?" + urllib.parse.urlencode({"conn": cid, "path": path}) if path else None


def local_item(cid: str, source: str, d: dict, genres: Genres) -> Optional[CItem]:
    """A catalogue item from a connector item (connectors/base.py ``item``)."""
    if d.get("kind") not in ("movie", "series") or not d.get("id"):
        return None
    movie = d["kind"] == "movie"
    names, keys = genres.parse(d.get("genres"))
    key = f"{'m' if movie else 's'}|{cid}|{d['id']}"
    return CItem(key, "movie" if movie else "show", str(d.get("title") or "")[:300] or "?", _year(d.get("year")),
                 media_img(cid, d.get("poster")), media_img(cid, d.get("backdrop")), _rating(d.get("rating")), names,
                 keys, source, d.get("added"), d.get("released"), d.get("colors"), str(d["id"]), conn=cid,
                 tmdb=(d.get("guids") or {}).get("tmdb"))


# --------------------------------------------------------------------------- indexes

class Collection:
    __slots__ = ("id", "name", "kind", "source", "members", "poster", "backdrop", "colors", "ordered")

    def __init__(self, id, name, kind, source, members, poster=None, backdrop=None, colors=None, ordered=False):
        self.id, self.name, self.kind, self.source, self.members = id, name, kind, source, members
        self.poster, self.backdrop, self.colors, self.ordered = poster, backdrop, colors, ordered


def _sorted(items: list) -> dict:
    """{sort: [items]} for one list: newest added / released, best rated (ties newest first), title A-Z."""
    by_title = sorted(items, key=lambda i: (i.sortt, i.key))
    return {
        "title": by_title,
        "added": sorted(by_title, key=lambda i: i.added or 0, reverse=True),
        "released": sorted(by_title, key=lambda i: i.rel, reverse=True),
        "rating": sorted(sorted(by_title, key=lambda i: i.added or 0, reverse=True),
                         key=lambda i: i.rating or 0, reverse=True),
    }


class Index:
    """Everything the routes read, built once per catalogue version. Never changed after it is built."""

    def __init__(self, items: list, collections: list, version: str):
        self.version = version
        self.items = items
        self.by_key = {}
        self.by_rkey = {}            # film resume keys ("m|p0|7", "m|7", "m|p1~7") -> item
        self.by_series = {}          # SeriesStore show id ("7", "p1~7") -> item
        for it in items:
            self.by_key.setdefault(it.key, it)
            for k in it.rkeys:
                self.by_rkey.setdefault(k, it)
            if it.kind == "show" and it.source == "iptv":
                self.by_series.setdefault(it.id, it)
        groups = {"movie": [i for i in items if i.kind == "movie"], "show": [i for i in items if i.kind == "show"],
                  "all": items}
        self.sorted = {k: _sorted(v) for k, v in groups.items()}
        self.genre_counts = {}       # kind -> [(count, genre key)] largest first
        self.genre_top = {}          # (kind, genre key) -> rated items, best first
        for kind in groups:
            counts: dict = {}
            for it in groups[kind]:
                for g in it.gkeys:
                    counts[g] = counts.get(g, 0) + 1
            self.genre_counts[kind] = sorted(((n, g) for g, n in counts.items()), key=lambda x: (-x[0], x[1]))
            for it in self.sorted[kind]["rating"]:
                if it.rating is None:
                    break
                for g in it.gkeys:
                    self.genre_top.setdefault((kind, g), []).append(it)
        self.collections = {c.id: c for c in collections}


def _franchises(films: list, tmdb) -> list:
    """IPTV film collections: a shared TMDb collection, else the title heuristic (orbit/franchise.py)."""
    look = (lambda f: tmdb.get(f.tmdb) if f.tmdb else None) if tmdb else None
    out = []
    for cid, g in FR.group(films, lambda f: f.title, look).items():
        members = sorted(g["members"], key=lambda f: (f.year or 9999, f.sortt))
        poster = backdrop = None
        if g["tmdb"] and tmdb:
            info = tmdb.info(g["tmdb"]) or {}
            poster, backdrop = info.get("poster"), info.get("backdrop")
        out.append(Collection(cid, g["name"], "movie", "iptv", [m.key for m in members], poster, backdrop, None, True))
    return out


# --------------------------------------------------------------------------- TMDb collections through Seerr

class TmdbCollections:
    """TMDb id of a film -> its TMDb collection, asked from Seerr's movie details (``collection``) in the
    background, one film at a time, and kept in ``catalog-tmdb.json`` (no secrets: ids and names only)."""
    MAX_RUN = 3000            # lookups per run
    BACKOFF = 600             # after Seerr failed, wait this long

    def __init__(self, path: str, client: Callable[[], Optional[object]], clock: Callable[[], float] = time.time,
                 pause: float = 0.05):
        self.path, self.client, self.clock, self.pause = path, client, clock, pause
        self._lock = threading.Lock()
        self._want: set = set()
        self._running = False
        self._down_until = 0.0
        self.version = 0
        self.films: dict = {}       # tmdb id -> collection id or 0 (none)
        self.colls: dict = {}       # collection id -> {"name", "poster", "backdrop"}
        try:
            with open(path, encoding="utf-8") as f:
                d = json.load(f)
            self.films = {str(k): int(v) for k, v in (d.get("films") or {}).items()}
            self.colls = {str(k): v for k, v in (d.get("collections") or {}).items() if isinstance(v, dict)}
        except (OSError, ValueError, TypeError, AttributeError):
            pass

    def get(self, tmdb) -> Optional[tuple]:
        c = self.films.get(str(tmdb))
        if not c:
            return None
        return str(c), (self.colls.get(str(c)) or {}).get("name") or ""

    def info(self, cid) -> Optional[dict]:
        return self.colls.get(str(cid))

    def want(self, tmdbs: Iterable) -> None:
        new = {str(t) for t in tmdbs if t and str(t) not in self.films}
        if not new:
            return
        with self._lock:
            self._want |= new
            if self._running or self.clock() < self._down_until or self.client() is None:
                return
            self._running = True
        threading.Thread(target=self._run, name="orbit-tmdb-collections", daemon=True).start()

    def _run(self):
        from . import seerr as S
        done = changed = 0
        try:
            cl = self.client()
            while cl is not None and done < self.MAX_RUN:
                with self._lock:
                    if not self._want:
                        break
                    t = self._want.pop()
                try:
                    raw = cl.get(f"/movie/{int(t)}")
                except S.SeerrDown:
                    self._down_until = self.clock() + self.BACKOFF
                    break
                except S.SeerrError:
                    raw = None
                except (ValueError, TypeError):
                    continue
                c = (raw or {}).get("collection") if isinstance(raw, dict) else None
                if isinstance(c, dict) and isinstance(c.get("id"), int) and c["id"] > 0:
                    self.colls[str(c["id"])] = {"name": str(c.get("name") or "")[:200],
                                                "poster": S.image(c.get("posterPath"), "w500"),
                                                "backdrop": S.image(c.get("backdropPath"), "w1280")}
                    self.films[t] = c["id"]
                    changed += 1
                else:
                    self.films[t] = 0
                done += 1
                if done % 200 == 0:
                    self._save(changed)
                    changed = 0
                if self.pause:
                    time.sleep(self.pause)
        except Exception:                           # noqa: BLE001
            LOG.exception("TMDb collections")
        finally:
            self._save(changed)
            with self._lock:
                self._running = False

    def _save(self, changed: int):
        try:
            write_secret(self.path, json.dumps({"films": self.films, "collections": self.colls}).encode())
        except OSError as e:
            LOG.warning("catalog-tmdb.json: %s", e)
        if changed:
            self.version += 1       # the IPTV index regroups its collections


# --------------------------------------------------------------------------- media-server data of an account

class Local:
    """One account's Plex / Jellyfin items, collections and "continue watching", as last fetched."""

    def __init__(self, sig: tuple):
        self.sig = sig
        self.at = 0.0
        self.cont_at = 0.0
        self.version = 0
        self.items: dict = {}           # conn id -> [CItem]
        self.colls: dict = {}           # conn id -> [Collection]
        self.prog: dict = {}            # item key -> (secs, dur, watched)
        self.cont: list = []            # [(key, secs, dur, at, next | None)]
        self.ready = threading.Event()


class TTL:
    def __init__(self, size: int, ttl: float, clock: Callable[[], float]):
        self.size, self.ttl, self.clock = size, ttl, clock
        self._d: OrderedDict = OrderedDict()
        self._lock = threading.Lock()

    def get(self, key):
        with self._lock:
            hit = self._d.get(key)
            if hit is None or hit[0] < self.clock():
                return None
            self._d.move_to_end(key)
            return hit[1]

    def put(self, key, v):
        with self._lock:
            self._d[key] = (self.clock() + self.ttl, v)
            self._d.move_to_end(key)
            while len(self._d) > self.size:
                self._d.popitem(last=False)


# --------------------------------------------------------------------------- per-profile view

class View:
    """What one profile has watched and saved, mapped onto an index."""

    def __init__(self, idx: Index, st, se, local: Optional[Local], kids: bool, locked: frozenset):
        self.idx, self.kids, self.locked = idx, kids, locked
        self.progress: dict = {}        # item key -> {"secs", "dur"}
        self.next: dict = {}            # show key -> next episode
        self.watched: set = set()       # finished films, shows with any watched episode, local watched
        self.cont: list = []            # [(at, item)]
        self.genre_votes: dict = {}
        s = st.all()
        self.mylist = [x.get("key") for x in s["mylist"] if isinstance(x, dict)]
        for rk, r in s["resume"].items():
            it = idx.by_rkey.get(rk)
            if it is None or not isinstance(r, dict):
                continue
            self._vote(it)
            if r.get("done"):
                self.watched.add(it.key)
                continue
            secs = int(r.get("secs") or 0)
            if secs > 0 and it.key not in self.progress:
                self.progress[it.key] = {"secs": secs, "dur": int(r.get("dur") or 0)}
                self.cont.append(((r.get("at") or 0) / 1000.0, it))
        with se._lock:
            rows = se.db.execute("SELECT id, next, finished, hidden, watched_at FROM series").fetchall()
        for sid, nx, fin, hid, at in rows:
            it = idx.by_series.get(sid)
            if it is None:
                continue
            self.watched.add(it.key)
            self._vote(it)
            n = json.loads(nx) if nx else None
            if n and not fin:
                self.next[it.key] = {k: n.get(k) for k in ("id", "season", "num", "title")}
                self.progress[it.key] = {"secs": int(n.get("secs") or 0), "dur": int(n.get("dur") or 0)}
                if not hid:
                    self.cont.append((at or 0, it))
        if local is not None:
            for key, (secs, dur, w) in local.prog.items():
                it = idx.by_key.get(key)
                if it is None:
                    continue
                if w:
                    self.watched.add(key)
                    self._vote(it)
                elif secs > 0 and key not in self.progress:
                    self.progress[key] = {"secs": secs, "dur": dur}
            for key, secs, dur, at, nxt in local.cont:
                it = idx.by_key.get(key)
                if it is None:
                    continue
                self.progress[key] = {"secs": secs, "dur": dur}
                if nxt:
                    self.next[key] = nxt
                self.cont.append((at or 0, it))
                self._vote(it)
        seen = set()
        self.cont = [x for x in sorted(self.cont, key=lambda x: -x[0]) if not (x[1].key in seen or seen.add(x[1].key))]

    def _vote(self, it: CItem):
        for g in it.gkeys:
            v = self.genre_votes.setdefault(it.kind, {})
            v[g] = v.get(g, 0) + 1

    def is_locked(self, it: CItem) -> bool:
        return it.source == "iptv" and bool(self.locked) and (it.kind, it.sid, it.cat) in self.locked

    def visible(self, it: CItem) -> bool:
        return not (self.kids and self.is_locked(it))

    def out(self, it: CItem) -> dict:
        d = dict(it.base)
        d["progress"] = self.progress.get(it.key)
        d["locked"] = self.is_locked(it)
        if it.kind == "show":
            d["next"] = self.next.get(it.key)
        return d

    def take(self, items: Iterable, n: int, pred: Optional[Callable] = None) -> list:
        out = []
        for it in items:
            if self.visible(it) and (pred is None or pred(it)):
                out.append(it)
                if len(out) >= n:
                    break
        return out

    def favourite_genres(self, kind: str, n: int) -> list:
        """The profile's most watched genres of this kind ("all": both), filled up with the largest ones."""
        votes: dict = {}
        for k in (("movie", "show") if kind == "all" else (kind,)):
            for g, c in self.genre_votes.get(k, {}).items():
                votes[g] = votes.get(g, 0) + c
        out = [g for g, _ in sorted(votes.items(), key=lambda x: (-x[1], x[0]))]
        for _, g in self.idx.genre_counts.get(kind, []):
            if g not in out:
                out.append(g)
        return out[:n * 3]           # spare ones: a genre may have too few rated items


# --------------------------------------------------------------------------- the catalogue

class Catalog:
    def __init__(self, iptv: Callable[[], list], stores: Callable[[dict], tuple], media=None,
                 kids: Callable[[str], bool] = lambda pid: False, locked: Callable[[], frozenset] = frozenset,
                 tmdb: Optional[TmdbCollections] = None, ensure_iptv: Optional[Callable[[], None]] = None,
                 clock: Callable[[], float] = time.time, local_wait: float = LOCAL_WAIT):
        """``iptv()`` -> [(source id, Provider.vod dict)] of the enabled playlists; ``stores(sess)`` -> (StateStore,
        SeriesStore) of the session's profile; ``media``: orbit.media.MediaServers; ``kids(profile id)``;
        ``locked()`` -> {("movie"|"show", source id, category name)} locked with the parental PIN (empty: none)."""
        self.iptv, self.stores, self.media = iptv, stores, media
        self.kids, self.locked, self.tmdb = kids, locked, tmdb
        self.ensure_iptv = ensure_iptv
        self.clock, self.local_wait = clock, local_wait
        self._lock = threading.Lock()
        self._idx: Optional[Index] = None       # the IPTV index
        self._sig = None
        self._building = False
        self._ensured = False
        self._locals: dict = {}                 # account id -> Local
        self._local_busy: set = set()
        self._merged: dict = {}                 # account id -> Index (IPTV + that account's media servers)
        self._views = TTL(VIEW_MAX, VIEW_TTL, clock)
        self._answers = TTL(VIEW_MAX, VIEW_TTL, clock)
        self.genres = Genres()

    # ---- the IPTV index ----
    def _signature(self, lists: list) -> tuple:
        return tuple((sid, id(v)) for sid, v in lists) + ((self.tmdb.version,) if self.tmdb else ())

    def build(self) -> Index:
        """Build the IPTV index now (from whatever the playlists hold) and use it."""
        lists = self.iptv()
        sig = self._signature(lists)
        items, seen = [], set()
        for sid, vod in lists:
            for k in ("movies", "series"):
                for v in vod.get(k, ()) if isinstance(vod, dict) else ():
                    if v.key in seen:
                        continue
                    seen.add(v.key)
                    items.append(iptv_item(v, self.genres))
        films = [i for i in items if i.kind == "movie"]
        if self.tmdb:
            self.tmdb.want(i.tmdb for i in films if i.tmdb)
        idx = Index(items, _franchises(films, self.tmdb), f"i{abs(hash(sig)) % 10**8}.{len(items)}")
        with self._lock:
            self._idx, self._sig = idx, sig
        return idx

    def _build_bg(self):
        try:
            self.build()
        except Exception:                       # noqa: BLE001
            LOG.exception("catalogue index")
        finally:
            with self._lock:
                self._building = False

    def check(self, wait: bool = False) -> None:
        """Rebuild the IPTV index when a catalogue changed (in the background unless ``wait``)."""
        lists = self.iptv()
        if not self._ensured and self.ensure_iptv and not any(v for _, v in lists):
            self._ensured = True                # first run: let the playlists load their catalogues
            threading.Thread(target=self.ensure_iptv, name="orbit-catalog-vod", daemon=True).start()
        if self._signature(lists) == self._sig and self._idx is not None:
            return
        if wait or self._idx is None:
            self.build()
            return
        with self._lock:
            if self._building:
                return
            self._building = True
        threading.Thread(target=self._build_bg, name="orbit-catalog-index", daemon=True).start()

    def index(self) -> Index:
        self.check()
        return self._idx

    # ---- media servers of an account ----
    def _local(self, sess: dict) -> Optional[Local]:
        if self.media is None:
            return None
        conns = self.media.connections(sess)
        if not conns:
            return None
        acc = sess["account_id"]
        sig = tuple(sorted((c["id"], c["kind"]) for c in conns))
        loc = self._locals.get(acc)
        now = self.clock()
        if loc is None or loc.sig != sig:
            old = loc
            loc = Local(sig)
            if old is not None:                 # keep what is known of connections that are still linked
                ids = {c for c, _ in sig}
                loc.items = {k: v for k, v in old.items.items() if k in ids}
                loc.colls = {k: v for k, v in old.colls.items() if k in ids}
            self._locals[acc] = loc
            self._refresh_local(sess, loc, full=True)
            loc.ready.wait(self.local_wait)
        elif now - loc.at > LOCAL_TTL:
            self._refresh_local(sess, loc, full=True)
        elif now - loc.cont_at > CONT_TTL:
            self._refresh_local(sess, loc, full=False)
        return loc

    def media_played(self, account_id: str) -> None:
        """A Plex / Jellyfin playback through this server ended: fetch its Continue watching again on the next
        request (the media server has the new position)."""
        loc = self._locals.get(account_id)
        if loc is not None:
            loc.cont_at = 0.0

    def _refresh_local(self, sess: dict, loc: Local, full: bool):
        acc = sess["account_id"]
        with self._lock:
            if acc in self._local_busy:
                return
            self._local_busy.add(acc)
        s = {"account_id": acc}
        threading.Thread(target=self._fetch_local, args=(s, loc, full), name="orbit-catalog-media", daemon=True).start()

    def _fetch_local(self, sess: dict, loc: Local, full: bool):
        try:
            prog, cont = {}, []
            items, colls = dict(loc.items), dict(loc.colls)
            for cid, kind in loc.sig:
                if full:
                    got = self._fetch_conn(sess, cid, kind)
                    if got is not None:
                        items[cid], colls[cid] = got
                cont += self._fetch_cont(sess, cid)
            for cid, lst in items.items():
                for it, p in lst:
                    prog[it.key] = p
            cont.sort(key=lambda x: -(x[3] or 0))
            with self._lock:
                if full:
                    loc.items, loc.colls = items, colls
                    loc.at = self.clock()
                    loc.version += 1
                loc.prog, loc.cont = prog, cont
                loc.cont_at = self.clock()
            if full and self._idx is not None and sess["account_id"] in self._merged:
                self._merge(sess["account_id"], self._idx, loc)       # here, not in a request
        except Exception:                       # noqa: BLE001
            LOG.exception("catalogue: media servers")
        finally:
            loc.ready.set()
            with self._lock:
                self._local_busy.discard(sess["account_id"])

    def _fetch_conn(self, sess: dict, cid: str, kind: str):
        """([(CItem, (secs, dur, watched))], [Collection]) of one connection, or None when it failed."""
        try:
            _, libs = self.media._use(sess, cid, lambda c: c.list_libraries())
            out = []
            for lib in libs:
                if lib.get("kind") not in ("movie", "series"):
                    continue
                cursor = None
                while len(out) < LOCAL_MAX:
                    _, ans = self.media._use(sess, cid, lambda c: c.list_items(lib["id"], cursor, lib["kind"], 200))
                    for d in ans.get("items") or []:
                        it = local_item(cid, kind, d, self.genres)
                        if it is not None:
                            p = d.get("progress") or {}
                            out.append((it, (int(p.get("secs") or 0), int(p.get("dur") or 0), bool(p.get("watched")))))
                    cursor = ans.get("next")
                    if not cursor:
                        break
        except ApiError as e:
            LOG.info("catalogue: media connection %s: %s", cid, e.msg)
            return None
        colls = []
        try:
            _, raw = self.media._use(sess, cid, lambda c: c.collections())
            for c in raw or []:
                pre = "plex" if kind == "plex" else "jf"
                ck = "show" if c.get("kind") == "series" else ("movie" if c.get("kind") == "movie" else None)
                members = []
                by_id = {it.id: it for it, _ in out}
                for i in c.get("items") or []:
                    it = by_id.get(str(i))
                    if it is not None:
                        members.append(it.key)
                        ck = ck or it.kind
                if members and ck:
                    colls.append(Collection(f"{pre}.{cid}.{c['id']}", c.get("title") or "", ck, kind, members,
                                            media_img(cid, c.get("poster")), media_img(cid, c.get("backdrop")),
                                            c.get("colors")))
        except ApiError as e:
            LOG.info("catalogue: collections of %s: %s", cid, e.msg)
        return out, colls

    def _fetch_cont(self, sess: dict, cid: str) -> list:
        try:
            _, raw = self.media._use(sess, cid, lambda c: c.continue_items())
        except ApiError:
            return []
        out = []
        for d in raw or []:
            p = d.get("progress") or {}
            if d.get("kind") == "movie" and int(p.get("secs") or 0) > 0 and not p.get("watched"):
                out.append((f"m|{cid}|{d['id']}", int(p["secs"]), int(p.get("dur") or 0), d.get("lastViewed"), None))
            elif d.get("kind") == "episode" and d.get("seriesId"):
                out.append((f"s|{cid}|{d['seriesId']}", int(p.get("secs") or 0), int(p.get("dur") or 0),
                            d.get("lastViewed"), {"id": d["id"], "season": d.get("season"), "num": d.get("episode"),
                                                  "title": d.get("title")}))
        return out

    def _merge(self, acc: str, idx: Index, loc: Local) -> Index:
        """The IPTV index plus one account's media-server items (and their collections)."""
        ver = f"{idx.version}+{acc}.{loc.version}"
        m = self._merged.get(acc)
        if m is not None and m.version == ver:
            return m
        items = list(idx.items)
        colls = list(idx.collections.values())
        with self._lock:
            lists = list(loc.items.items())
        for cid, lst in lists:
            items += [it for it, _ in lst]
            colls += loc.colls.get(cid, [])
        m = Index(items, colls, ver)
        self._merged[acc] = m
        return m

    def _merge_bg(self, acc: str, loc: Local):
        try:
            self._merge(acc, self._idx, loc)
        except Exception:                       # noqa: BLE001
            LOG.exception("catalogue: merge")
        finally:
            with self._lock:
                self._local_busy.discard("merge:" + acc)

    def _merged_index(self, sess: dict) -> tuple:
        """(Index, Local | None) for this account: IPTV plus its media servers. The first time it is built here;
        after a change (new IPTV index, new media-server fetch) the previous one answers while a new one is built."""
        idx = self.index()
        if idx is None:
            raise ApiError(503, "the catalogue is not loaded yet")
        loc = self._local(sess)
        if loc is None or not loc.items:
            return idx, loc
        acc = sess["account_id"]
        m = self._merged.get(acc)
        if m is None:
            return self._merge(acc, idx, loc), loc
        if m.version != f"{idx.version}+{acc}.{loc.version}":
            with self._lock:
                start = "merge:" + acc not in self._local_busy
                self._local_busy.add("merge:" + acc)
            if start:
                threading.Thread(target=self._merge_bg, args=(acc, loc), name="orbit-catalog-index", daemon=True).start()
        return m, loc
    # ---- per profile ----
    def _view(self, c: Call) -> View:
        try:
            st, se = self.stores(c.sess)
        except PermissionError:
            raise ApiError(409, "no profile selected")
        idx, loc = self._merged_index(c.sess)
        pid = c.sess.get("profile_id") or ""
        kids = bool(self.kids(pid)) if pid else False
        locked = frozenset(self.locked() or ())
        key = (pid, c.sess.get("account_id"), SYNC.rev(st), idx.version, loc.cont_at if loc else 0, kids, hash(locked))
        v = self._views.get(key)
        if v is None:
            v = View(idx, st, se, loc, kids, locked)
            self._views.put(key, v)
        v.cache_key = key
        return v

    def _cached(self, v: View, what: tuple, fn: Callable[[], dict]) -> dict:
        k = (v.cache_key, what)
        out = self._answers.get(k)
        if out is None:
            out = fn()
            self._answers.put(k, out)
        return out

    # ---- rows ----
    @staticmethod
    def _row(v: View, rid: str, title: str, items: list) -> Optional[dict]:
        return {"id": rid, "title": title, "items": [v.out(i) for i in items]} if items else None

    def _rows(self, v: View, kind: str) -> tuple:
        """(hero, rows) for Movies ("movie"), Shows ("show") or Home ("all")."""
        idx = v.idx
        s = idx.sorted[kind]
        want = (lambda i: True) if kind == "all" else (lambda i: i.kind == kind)
        rows = []
        cont = v.take((it for _, it in v.cont), ROW, want)
        rows.append(self._row(v, "continue", "Continue watching", cont))
        if kind == "all":
            ml = [idx.by_key[k] for k in v.mylist if k in idx.by_key]
            rows.append(self._row(v, "mylist", "My list", v.take(ml, ROW)))
        recent = v.take(s["added"], ROW, lambda i: bool(i.added))
        rows.append(self._row(v, "recent", "Recently added", recent))
        rows.append(self._row(v, "released", "Recently released", v.take(s["released"], ROW, lambda i: bool(i.rel))))
        n = GENRE_ROWS if kind != "all" else 2
        got = 0
        for g in v.favourite_genres(kind, n):
            if got >= n:
                break
            top = v.take(idx.genre_top.get((kind, g), ()), ROW)
            if len(top) >= 3:
                rows.append(self._row(v, "genre:" + g, f"Top in {self.genres.names.get(g, g)}", top))
                got += 1
        rows.append(self._row(v, "unwatched", "Top unwatched",
                              v.take(s["rating"], ROW, lambda i: i.rating is not None and i.key not in v.watched
                                     and i.key not in v.progress)))
        pool = sorted(v.take(s["added"], 40, lambda i: bool(i.backdrop)), key=lambda i: -(i.rating or 0))
        if len(pool) < HERO:
            pool += [i for i in v.take(s["rating"], HERO * 2, lambda i: bool(i.backdrop or i.poster)) if i not in pool]
        hero = [v.out(i) for i in pool[:HERO]]
        return hero, [r for r in rows if r]

    # ---- routes ----
    def routes(self) -> dict:
        r = {("GET", "/v1/catalog/home"): self._home}
        for name, kind in KINDS.items():
            r[("GET", f"/v1/catalog/{name}/recommended")] = lambda c, k=kind: self._recommended(c, k)
            r[("GET", f"/v1/catalog/{name}/browse")] = lambda c, k=kind: self._browse(c, k)
            r[("GET", f"/v1/catalog/{name}/collections")] = lambda c, k=kind: self._collections(c, k)
        return r

    def prefix_routes(self) -> dict:
        return {("GET", "/v1/catalog/collections/"): self._collection}

    # ---- Discover: "Play instead of Request" ----
    def tmdb_finder(self, c: Call) -> Optional[Callable[[str, int], Optional[dict]]]:
        """For orbit/discover.py: a function (Seerr type "movie" | "tv", TMDb id) -> this profile's catalogue Item
        with that TMDb id (IPTV first, then the account's Plex / Jellyfin; titles a kids profile may not see are left
        out), or None. None while the catalogue is not loaded or no profile is selected."""
        try:
            v = self._view(c)
        except ApiError:
            return None
        idx = v.idx
        m = getattr(idx, "by_tmdb", None)
        if m is None:
            m = {}
            for it in idx.items:
                t = str(it.tmdb).strip() if it.tmdb is not None else ""
                if t.isdigit():
                    m.setdefault((it.kind, int(t)), []).append(it)
            for lst in m.values():
                lst.sort(key=lambda i: i.source != "iptv")
            idx.by_tmdb = m                   # built once per index (an Index never changes once built)

        def find(kind: str, tmdb: int) -> Optional[dict]:
            for it in m.get(("movie" if kind == "movie" else "show", tmdb), ()):
                if v.visible(it):
                    return v.out(it)
            return None
        return find

    def _home(self, c: Call) -> Reply:
        v = self._view(c)

        def make():
            hero, rows = self._rows(v, "all")
            return {"hero": hero, "rows": rows}
        return Reply(self._cached(v, ("home",), make))

    def _recommended(self, c: Call, kind: str) -> Reply:
        v = self._view(c)

        def make():
            hero, rows = self._rows(v, kind)
            return {"hero": hero, "rows": rows}
        return Reply(self._cached(v, ("rec", kind), make))

    def _browse(self, c: Call, kind: str) -> Reply:
        q = c.query
        source = q.get("source") or "all"
        if source not in SOURCES:
            raise ApiError(400, "source: all, local or iptv")
        sort = q.get("sort") or "added"
        if sort not in SORTS:
            raise ApiError(400, "sort: added, released, title or rating")
        try:
            offset = int(q.get("offset") or 0)
            limit = int(q.get("limit") or PAGE)
        except ValueError:
            raise ApiError(400, "offset / limit: numbers")
        if offset < 0 or not 1 <= limit <= MAX_PAGE:
            raise ApiError(400, f"offset / limit: out of range (limit 1 to {MAX_PAGE})")
        genre = (q.get("genre") or "").strip().lower()[:40]
        v = self._view(c)
        srcs = SOURCES[source]

        def match():
            ok = [i for i in v.idx.sorted[kind][sort] if (srcs is None or i.source in srcs) and v.visible(i)]
            names = sorted({g for i in ok for g in i.gkeys}, key=lambda g: self.genres.names.get(g, g).lower())
            if genre:
                ok = [i for i in ok if genre in i.gkeys]
            return {"items": ok, "genres": [self.genres.names.get(g, g) for g in names]}
        m = self._cached(v, ("browse", kind, source, sort, genre), match)
        return Reply({"total": len(m["items"]), "genres": m["genres"],
                      "items": [v.out(i) for i in m["items"][offset:offset + limit]]})

    def _coll_out(self, v: View, col: Collection) -> Optional[dict]:
        items = [v.idx.by_key[k] for k in col.members if k in v.idx.by_key]
        items = [i for i in items if v.visible(i)]
        if len(items) < (2 if col.source == "iptv" else 1):
            return None
        first = lambda attr: next((getattr(i, attr) for i in items if getattr(i, attr)), None)  # noqa: E731
        return {"id": col.id, "name": col.name, "count": len(items), "poster": col.poster or first("poster"),
                "backdrop": col.backdrop or first("backdrop"), "colors": col.colors or first("colors"),
                "source": col.source, "kind": col.kind}, items

    def _collections(self, c: Call, kind: str) -> Reply:
        v = self._view(c)

        def make():
            out = []
            for col in v.idx.collections.values():
                if col.kind != kind:
                    continue
                got = self._coll_out(v, col)
                if got:
                    out.append(got[0])
            out.sort(key=lambda x: (x["name"].lower(), x["id"]))
            return {"collections": out}
        return Reply(self._cached(v, ("colls", kind), make))

    def _collection(self, c: Call) -> Reply:
        cid = c.path[len("/v1/catalog/collections/"):]
        if not _CID.match(cid):
            raise ApiError(404, "no such collection")
        v = self._view(c)
        col = v.idx.collections.get(cid)
        got = self._coll_out(v, col) if col else None
        if not got:
            raise ApiError(404, "no such collection")
        info, items = got
        if col.source != "iptv":
            items = sorted(items, key=lambda i: (i.year or 9999, i.sortt))
        return Reply({"collection": info, "items": [v.out(i) for i in items]})


__all__ = ["Catalog", "TmdbCollections", "CItem", "Index", "iptv_item", "local_item"]
