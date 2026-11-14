# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""The /v1/catalog routes (orbit/catalog.py), the franchise heuristic (orbit/franchise.py) and the catalogue
fields added to the provider parser and the media connectors.

Synthetic data only: IPTV catalogues are tvBro VodItems, the media servers are a fake MediaServers with fake
connectors (the real connectors' normalisation is tested on fixture dicts). Includes a benchmark with a synthetic
20 000-title catalogue."""
import json
import os
import random
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "server"))
sys.path.insert(0, HERE)
from orbit import api as V1  # noqa: E402
from orbit import catalog as CAT  # noqa: E402
from orbit import franchise as FR  # noqa: E402
from orbit.accounts import Accounts, ApiError  # noqa: E402
from orbit.connectors import plex as PLEX, jellyfin as JF  # noqa: E402
from orbit.connectors.base import Http  # noqa: E402
from orbit.profilestate import ProfileStores  # noqa: E402
from tvbro_server import auth as AU  # noqa: E402
from tvbro_server import catcache  # noqa: E402
from tvbro_server import parsers as P  # noqa: E402
from tvbro_server import series as SER  # noqa: E402
from tvbro_server.state import StateStore  # noqa: E402
from test_orbit_accounts import free_port  # noqa: E402

PA, PB, PK, PX = "a" * 16, "b" * 16, "c" * 16, "d" * 16          # profiles: A, B (same account), kids, other account
ACC1, ACC2 = "acct1", "acct2"
EPS = {"7": [{"id": "701", "season": 1, "num": 1, "title": "Pilot", "duration": "00:40:00"},
             {"id": "702", "season": 1, "num": 2, "title": "Second", "duration": "00:40:00"}]}


def mv(i, name, year=None, genre=None, rating=None, added=None, cat="Films", sid="p0", tmdb=None):
    vid = str(i) if sid == "p0" else f"{sid}~{i}"
    return P.VodItem(f"m|{sid}|{i}", sid, "movie", vid, name, f"https://img.example.com/m{i}.jpg", cat,
                     None if rating is None else str(rating), "mp4", year=None if year is None else str(year),
                     genre=genre, added=added, tmdb=tmdb)


def sh(i, name, year=None, genre=None, rating=None, added=None, cat="Shows", released=None):
    return P.VodItem(f"s|p0|{i}", "p0", "series", str(i), name, f"https://img.example.com/s{i}.jpg", cat,
                     None if rating is None else str(rating), "", year=None if year is None else str(year),
                     genre=genre, added=added, backdrop=f"https://img.example.com/b{i}.jpg", released=released)


MOVIES = [
    mv(1, "Harry Potter and the Philosopher's Stone (2001)", 2001, "Fantasy, Adventure", 7.6, 1_700_000_100),
    mv(2, "Harry Potter and the Chamber of Secrets", 2002, "Fantasy, Adventure", 7.4, 1_700_000_200),
    mv(3, "Harry Potter and the Prisoner of Azkaban", 2004, "Fantasy", 7.9, 1_700_000_300),
    mv(4, "Super", 2010, "Comedy", 6.7, 1_700_000_400),
    mv(5, "Super 8", 2011, "Science Fiction", 7.0, 1_700_000_500),
    mv(6, "Night Film", 2020, "Horror", 5.1, 1_700_000_600, cat="Adults only"),
    mv(7, "Quiet Drama", 2023, "Drama", 8.8, 1_700_000_700),
    mv(8, "Old Classic", 1950, "Drama", 8.1, None),
]
SHOWS = [
    sh(7, "Example Show", 2019, "Drama", 8.0, 1_700_000_050, released="2019-04-01"),
    sh(8, "Late Show", 2024, "Comedy", 6.0, 1_700_000_900),
    sh(9, "Scary Show", 2022, "Horror", 7.0, 1_700_000_950, cat="Adults only"),
]


# --------------------------------------------------------------------------- fake media servers

class FakeConn:
    """A connector: two films, one show, one collection, one film in progress, one next episode."""

    def __init__(self, cid):
        self.cid = cid

    def list_libraries(self):
        return [{"id": "1", "title": "Films", "kind": "movie"}, {"id": "2", "title": "TV", "kind": "series"},
                {"id": "3", "title": "Music", "kind": "other"}]

    def list_items(self, lib, cursor, kind, limit):
        def it(i, k, title, **kw):
            d = {"id": i, "kind": k, "title": title, "year": 2015, "poster": f"/library/metadata/{i}/thumb",
                 "backdrop": f"/library/metadata/{i}/art", "rating": 7.2, "genres": ["Drama"], "added": 1_700_001_000,
                 "released": "2015-06-01", "colors": None, "guids": {"tmdb": None},
                 "progress": {"secs": 0, "dur": 6000, "watched": False}}
            d.update(kw)
            return d
        if lib == "1":
            pages = [[it("101", "movie", "Local Film", colors=["#112233", "#445566"])],
                     [it("102", "movie", "Local Watched", progress={"secs": 0, "dur": 6000, "watched": True})]]
            n = int(cursor or 0)
            return {"items": pages[n], "next": str(n + 1) if n + 1 < len(pages) else None, "total": 2}
        return {"items": [it("201", "series", "Local Show", genres=["Comedy"])], "next": None, "total": 1}

    def collections(self):
        return [{"id": "900", "title": "Local Saga", "kind": "movie", "poster": "/library/metadata/900/thumb",
                 "backdrop": None, "colors": None, "items": ["101", "102"]}]

    def continue_items(self):
        return [{"id": "101", "kind": "movie", "title": "Local Film", "progress": {"secs": 1200, "dur": 6000},
                 "lastViewed": 1_700_002_000},
                {"id": "301", "kind": "episode", "title": "Ep 3", "seriesId": "201", "season": 1, "episode": 3,
                 "progress": {"secs": 0, "dur": 1500}, "lastViewed": 1_700_003_000}]


class FakeMedia:
    def __init__(self):
        self.conns = {ACC1: [{"id": "c0ffee0000000001", "kind": "plex"}], ACC2: []}
        self.calls = 0

    def connections(self, sess):
        return list(self.conns.get(sess["account_id"], []))

    def _use(self, sess, cid, fn):
        if not any(c["id"] == cid for c in self.conns.get(sess["account_id"], [])):
            raise ApiError(404, "no such media connection")
        self.calls += 1
        return None, fn(FakeConn(cid))


class FakeTmdb:
    version = 0

    def __init__(self, films=None):
        self.films = films or {}

    def get(self, t):
        return self.films.get(str(t))

    def info(self, cid):
        return {"poster": "https://image.example.com/coll.jpg", "backdrop": None}

    def want(self, ids):
        pass


# --------------------------------------------------------------------------- harness

class Base(unittest.TestCase):
    media = True

    def setUp(self):
        self.d = tempfile.mkdtemp()
        self.house = StateStore(os.path.join(self.d, "state.db"))
        self.house_se = SER.SeriesStore(self.house, lambda sid: ("Show", None, [dict(e) for e in EPS.get(sid, [])]))
        self.profiles = ProfileStores(self.d, self.house, self.house_se,
                                      lambda sid: ("Show", None, [dict(e) for e in EPS.get(sid, [])]), lambda: True)
        self.vod = {"movies": list(MOVIES), "series": list(SHOWS), "movie_cats": [], "series_cats": []}
        self.locks = frozenset()
        self.fm = FakeMedia() if self.media else None
        self.cat = CAT.Catalog(lambda: [("p0", self.vod)], self.profiles.for_session, media=self.fm,
                               kids=lambda pid: pid == PK, locked=lambda: self.locks, tmdb=self.tmdb(), local_wait=5)
        self.acc = Accounts(self.d, AU.Auth(self.d, b"k" * 32))
        self.api = V1.Api(self.acc)
        self.api.routes.update(self.cat.routes())
        self.api.prefix_routes.update(self.cat.prefix_routes())

    def tmdb(self):
        return None

    def tearDown(self):
        for t in threading.enumerate():
            if t.name in ("series-recompute", "orbit-catalog-media", "orbit-catalog-index"):
                t.join(10)
        self.profiles.close()
        with self.house._lock:
            self.house.db.close()
        shutil.rmtree(self.d, ignore_errors=True)

    @staticmethod
    def sess(pid=PA, acc=ACC1):
        return {"account_id": acc, "profile_id": pid, "account_role": "member", "session_kind": "web"}

    def get(self, path, sess="default", **query):
        s = self.sess() if sess == "default" else sess
        r = self.api.handle(V1.Call("GET", path, s, query={k: str(v) for k, v in query.items()}))
        return r

    def ok(self, path, sess="default", **query):
        r = self.get(path, sess, **query)
        self.assertEqual(r.code, 200, r.obj)
        return r.obj

    def st(self, pid=PA):
        return self.profiles.for_profile(pid)

    @staticmethod
    def row(ans, rid):
        return next((r for r in ans["rows"] if r["id"] == rid), None)

    @staticmethod
    def keys(items):
        return [i["key"] for i in items]


# --------------------------------------------------------------------------- routes

class Routes(Base):
    def test_home_rows_mix_films_and_shows_with_profile_state(self):
        st, se = self.st()
        st.put("resume", "m|p0|1", {"secs": 600, "dur": 9000, "at": 1_700_000_000_000})     # the TV's key
        st.put("resume", "m|7", {"secs": 300, "dur": 6000, "at": 1_700_000_500_000})        # the web app's key
        st.put("resume", "m|p0|3", {"secs": 9000, "dur": 9000, "at": 1, "done": True})       # finished
        st.put("mylist", "s|p0|8", {"key": "s|p0|8", "kind": "series", "name": "Late Show"})
        st.put("mylist", "p0|55", {"key": "p0|55", "kind": "live", "name": "A channel"})     # channels are left out
        se.record("7", "701", 2400, 2400, ended=True, name="Example Show")
        home = self.ok("/v1/catalog/home")
        ids = [r["id"] for r in home["rows"]]
        self.assertEqual(ids[:2], ["continue", "mylist"])
        self.assertTrue({"recent", "released", "unwatched"} <= set(ids), ids)
        cont = self.row(home, "continue")["items"]
        ck = self.keys(cont)
        self.assertIn("m|p0|1", ck)
        self.assertIn("m|p0|7", ck)
        self.assertIn("s|p0|7", ck)
        self.assertIn("m|c0ffee0000000001|101", ck)                 # Plex On Deck film
        self.assertIn("s|c0ffee0000000001|201", ck)                 # Plex next episode
        self.assertNotIn("m|p0|3", ck)
        show = next(i for i in cont if i["key"] == "s|p0|7")
        self.assertEqual(show["next"]["id"], "702")
        self.assertEqual(show["progress"]["secs"], 0, "the next episode has not been started")
        film = next(i for i in cont if i["key"] == "m|p0|1")
        self.assertEqual(film["progress"], {"secs": 600, "dur": 9000})
        self.assertEqual(self.keys(self.row(home, "mylist")["items"]), ["s|p0|8"])
        recent = self.row(home, "recent")["items"]
        self.assertEqual({i["kind"] for i in recent}, {"movie", "show"})
        added = [i["added"] for i in recent]
        self.assertEqual(added, sorted(added, reverse=True))
        unw = self.keys(self.row(home, "unwatched")["items"])
        for k in ("m|p0|1", "m|p0|3", "s|p0|7", "m|c0ffee0000000001|102"):
            self.assertNotIn(k, unw)
        self.assertTrue(home["hero"])
        item = cont[0]
        self.assertEqual(set(item) - {"next"}, {"key", "kind", "title", "year", "poster", "backdrop", "rating", "genres",
                                                "source", "added", "released", "colors", "progress", "id", "conn",
                                                "locked"})

    def test_recommended_movies_and_shows(self):
        st, se = self.st()
        st.put("resume", "m|p0|7", {"secs": 100, "dur": 6000, "at": 5})
        rec = self.ok("/v1/catalog/movies/recommended")
        self.assertTrue(all(i["kind"] == "movie" for r in rec["rows"] for i in r["items"]))
        self.assertTrue(all(i["kind"] == "movie" for i in rec["hero"]))
        ids = [r["id"] for r in rec["rows"]]
        self.assertEqual(ids[0], "continue")
        self.assertIn("recent", ids)
        self.assertIn("released", ids)
        rel = self.row(rec, "released")["items"]
        self.assertEqual(rel[0]["key"], "m|p0|7")                    # 2023
        genre_rows = [r for r in rec["rows"] if r["id"].startswith("genre:")]
        self.assertTrue(genre_rows)
        self.assertEqual(genre_rows[0]["id"], "genre:drama", "the profile watches dramas")
        self.assertEqual(genre_rows[0]["title"], "Top in Drama")
        ratings = [i["rating"] for i in genre_rows[0]["items"]]
        self.assertEqual(ratings, sorted(ratings, reverse=True))
        shows = self.ok("/v1/catalog/shows/recommended")
        self.assertTrue(all(i["kind"] == "show" for r in shows["rows"] for i in r["items"]))
        self.assertEqual(self.row(shows, "released")["items"][0]["key"], "s|p0|8")

    def test_top_in_genre_falls_back_to_the_largest_genres(self):
        rec = self.ok("/v1/catalog/movies/recommended")
        genre_rows = [r["id"] for r in rec["rows"] if r["id"].startswith("genre:")]
        self.assertEqual(genre_rows[0], "genre:drama", "largest genre (3 films incl. the local one) when nothing is watched")

    def test_browse_filters_sorts_and_pages(self):
        b = self.ok("/v1/catalog/movies/browse")
        self.assertEqual(b["total"], 10)                              # 8 IPTV + 2 Plex
        self.assertIn("Fantasy", b["genres"])
        self.assertEqual(b["genres"], sorted(b["genres"], key=str.lower))
        iptv = self.ok("/v1/catalog/movies/browse", source="iptv", sort="title", limit=3, offset=0)
        self.assertEqual(iptv["total"], 8)
        self.assertEqual(len(iptv["items"]), 3)
        titles = [i["title"] for i in self.ok("/v1/catalog/movies/browse", source="iptv", sort="title", limit=50)["items"]]
        self.assertEqual(titles, sorted(titles, key=lambda t: FR.norm(t)))
        self.assertEqual(titles[0], "Harry Potter and the Chamber of Secrets")
        self.assertNotIn("(2001)", " ".join(titles), "a trailing year is dropped from the title")
        page2 = self.ok("/v1/catalog/movies/browse", source="iptv", sort="title", limit=3, offset=3)
        self.assertEqual(self.keys(page2["items"]), self.keys(
            self.ok("/v1/catalog/movies/browse", source="iptv", sort="title", limit=6)["items"])[3:])
        local = self.ok("/v1/catalog/movies/browse", source="local")
        self.assertEqual({i["source"] for i in local["items"]}, {"plex"})
        self.assertEqual(local["total"], 2)
        fan = self.ok("/v1/catalog/movies/browse", genre="fantasy", sort="rating")
        self.assertEqual(self.keys(fan["items"]), ["m|p0|3", "m|p0|1", "m|p0|2"])
        added = self.ok("/v1/catalog/movies/browse", source="iptv")["items"]
        self.assertEqual(added[-1]["key"], "m|p0|8", "unknown added date last")
        rel = self.ok("/v1/catalog/shows/browse", sort="released")["items"]
        self.assertEqual(rel[0]["key"], "s|p0|8")
        for bad in ({"source": "x"}, {"sort": "x"}, {"limit": "0"}, {"limit": "500"}, {"offset": "-1"}, {"offset": "a"}):
            self.assertEqual(self.get("/v1/catalog/movies/browse", **bad).code, 400, bad)

    def test_collections_heuristic_and_media_server(self):
        cols = self.ok("/v1/catalog/movies/collections")["collections"]
        names = {c["name"]: c for c in cols}
        self.assertIn("Harry Potter", names)
        self.assertEqual(names["Harry Potter"]["count"], 3)
        self.assertEqual(names["Harry Potter"]["source"], "iptv")
        self.assertIn("Local Saga", names)
        self.assertNotIn("Super", names, "Super and Super 8 are not a series")
        self.assertTrue(names["Local Saga"]["poster"].startswith("/v1/media/image?"))
        hp = self.ok("/v1/catalog/collections/" + names["Harry Potter"]["id"])
        self.assertEqual(self.keys(hp["items"]), ["m|p0|1", "m|p0|2", "m|p0|3"], "in release order")
        self.assertEqual(hp["collection"]["name"], "Harry Potter")
        loc = self.ok("/v1/catalog/collections/" + names["Local Saga"]["id"])
        self.assertEqual(len(loc["items"]), 2)
        self.assertEqual(self.get("/v1/catalog/collections/nope").code, 404)
        self.assertEqual(self.get("/v1/catalog/collections/../etc").code, 404)
        self.assertEqual(self.ok("/v1/catalog/shows/collections")["collections"], [])
        # another account does not have that Plex link: its collection is "not found" there
        self.assertEqual(self.get("/v1/catalog/collections/" + names["Local Saga"]["id"], self.sess(PX, ACC2)).code, 404)
        self.assertEqual(self.get("/v1/catalog/collections/" + names["Harry Potter"]["id"], self.sess(PX, ACC2)).code, 200)

    def test_plex_colors_and_null_for_iptv(self):
        b = self.ok("/v1/catalog/movies/browse", source="local", sort="title")["items"]
        film = next(i for i in b if i["key"] == "m|c0ffee0000000001|101")
        self.assertEqual(film["colors"], ["#112233", "#445566"])
        self.assertEqual(film["conn"], "c0ffee0000000001")
        self.assertEqual(film["id"], "101")
        self.assertTrue(all(i["colors"] is None for i in self.ok("/v1/catalog/movies/browse", source="iptv")["items"]))

    def test_local_libraries_are_fetched_once_and_cached(self):
        self.ok("/v1/catalog/home")
        n = self.fm.calls
        self.ok("/v1/catalog/movies/browse")
        self.ok("/v1/catalog/shows/recommended")
        self.assertEqual(self.fm.calls, n, "answered from memory")

    def test_a_state_change_shows_at_once(self):
        self.assertIsNone(self.row(self.ok("/v1/catalog/home"), "mylist"))
        st, _ = self.st()
        st.put("mylist", "m|p0|5", {"key": "m|p0|5", "kind": "movie"})
        self.assertEqual(self.keys(self.row(self.ok("/v1/catalog/home"), "mylist")["items"]), ["m|p0|5"])

    def test_a_new_catalogue_is_indexed(self):
        self.ok("/v1/catalog/home")
        self.vod = dict(self.vod, movies=self.vod["movies"] + [mv(99, "Brand New", 2025, "Drama", 7.7, 1_800_000_000)])
        self.cat.check(wait=True)
        self.ok("/v1/catalog/movies/browse", source="iptv")     # served from the previous index while it merges
        for t in threading.enumerate():
            if t.name == "orbit-catalog-index":
                t.join(10)
        b = self.ok("/v1/catalog/movies/browse", source="iptv", limit=5)
        self.assertEqual(b["items"][0]["key"], "m|p0|99")


class Permissions(Base):
    def test_signed_out_and_no_profile(self):
        for path in ("/v1/catalog/home", "/v1/catalog/movies/recommended", "/v1/catalog/shows/browse",
                     "/v1/catalog/movies/collections", "/v1/catalog/collections/t.abc"):
            self.assertEqual(self.get(path, None).code, 401, path)
        self.assertEqual(self.get("/v1/catalog/home", {"account_id": ACC1, "profile_id": None}).code, 409)

    def test_profiles_are_kept_apart(self):
        st, _ = self.st(PA)
        st.put("mylist", "m|p0|7", {"key": "m|p0|7"})
        st.put("resume", "m|p0|1", {"secs": 600, "dur": 9000, "at": 5})
        a = self.ok("/v1/catalog/home", self.sess(PA))
        b = self.ok("/v1/catalog/home", self.sess(PB))
        self.assertIsNotNone(self.row(a, "mylist"))
        self.assertIsNone(self.row(b, "mylist"))
        self.assertNotIn("m|p0|1", self.keys((self.row(b, "continue") or {"items": []})["items"]))
        ib = next(i for i in self.ok("/v1/catalog/movies/browse", self.sess(PB), source="iptv")["items"]
                  if i["key"] == "m|p0|1")
        self.assertIsNone(ib["progress"])
        # the other account has no Plex link: no local items, no local continue
        x = self.ok("/v1/catalog/movies/browse", self.sess(PX, ACC2))
        self.assertEqual({i["source"] for i in x["items"]}, {"iptv"})

    def test_kids_profile_never_sees_locked_categories(self):
        self.locks = frozenset({("movie", "p0", "Adults only"), ("show", "p0", "Adults only")})
        kid = self.sess(PK)
        st, _ = self.st(PK)
        st.put("mylist", "m|p0|6", {"key": "m|p0|6"})
        st.put("resume", "m|p0|6", {"secs": 60, "dur": 6000, "at": 9})
        every = []
        for path in ("/v1/catalog/home", "/v1/catalog/movies/recommended", "/v1/catalog/shows/recommended"):
            ans = self.ok(path, kid)
            every += [i for r in ans["rows"] for i in r["items"]] + ans["hero"]
        for kind in ("movies", "shows"):
            b = self.ok(f"/v1/catalog/{kind}/browse", kid, limit=200)
            every += b["items"]
            self.assertNotIn("Horror", b["genres"])
        keys = {i["key"] for i in every}
        self.assertNotIn("m|p0|6", keys)
        self.assertNotIn("s|p0|9", keys)
        self.assertEqual(self.ok("/v1/catalog/movies/browse", kid)["total"], 9)
        # adults see them, marked
        adult = self.ok("/v1/catalog/movies/browse", self.sess(PA), source="iptv", limit=200)["items"]
        night = next(i for i in adult if i["key"] == "m|p0|6")
        self.assertTrue(night["locked"])
        self.assertFalse(next(i for i in adult if i["key"] == "m|p0|7")["locked"])
        # no PIN / no locks: nothing is hidden
        self.locks = frozenset()
        self.assertEqual(self.ok("/v1/catalog/movies/browse", kid)["total"], 10)

    def test_kids_lose_a_collection_that_falls_under_two_titles(self):
        self.locks = frozenset({("movie", "p0", "Films")})
        cols = self.ok("/v1/catalog/movies/collections", self.sess(PK))["collections"]
        self.assertNotIn("Harry Potter", {c["name"] for c in cols})
        hp = FR.gid("movie", "harry potter")
        self.assertEqual(self.get("/v1/catalog/collections/" + hp, self.sess(PK)).code, 404)
        self.assertEqual(self.get("/v1/catalog/collections/" + hp, self.sess(PA)).code, 200)


class TmdbFinder(Base):
    """Discover's "Play instead of Request": Catalog.tmdb_finder (wired in app.py as discover.local_finder)."""
    media = False

    def test_finds_the_profiles_title_by_tmdb_id(self):
        self.vod["movies"] = self.vod["movies"] + [mv(20, "A New Hope", 1977, tmdb="11"),
                                                   mv(21, "Night Two", 2021, cat="Adults only", tmdb=12)]
        find = self.cat.tmdb_finder(V1.Call("GET", "/v1/discover/rows", self.sess()))
        self.assertEqual(find("movie", 11)["key"], "m|p0|20")
        self.assertEqual(find("movie", 12)["key"], "m|p0|21", "an int id in the provider data matches too")
        self.assertTrue({"key", "kind", "title", "progress", "locked"} <= set(find("movie", 11)))
        self.assertIsNone(find("tv", 11), "a show never matches a film")
        self.assertIsNone(find("movie", 999))
        # a kids profile does not get titles of locked categories
        self.locks = frozenset({("movie", "p0", "Adults only")})
        kid = self.cat.tmdb_finder(V1.Call("GET", "/v1/discover/rows", self.sess(PK)))
        self.assertIsNone(kid("movie", 12))
        self.assertEqual(kid("movie", 11)["key"], "m|p0|20")
        # no profile selected: no finder (Discover then shows Request as before)
        self.assertIsNone(self.cat.tmdb_finder(V1.Call("GET", "/v1/discover/rows", {"account_id": ACC1, "profile_id": None})))


class TmdbGrouping(Base):
    media = False

    def tmdb(self):
        return FakeTmdb({"11": ("10", "Star Saga Collection"), "12": ("10", "Star Saga Collection")})

    def test_a_shared_tmdb_collection_wins(self):
        self.vod["movies"] = self.vod["movies"] + [mv(20, "A New Hope", 1977, "Science Fiction", 8.6, tmdb="11"),
                                                   mv(21, "The Empire Strikes Back", 1980, "Science Fiction", 8.7, tmdb="12")]
        cols = {c["name"]: c for c in self.ok("/v1/catalog/movies/collections")["collections"]}
        self.assertIn("Star Saga Collection", cols)
        self.assertEqual(cols["Star Saga Collection"]["id"], "tmdb.10")
        self.assertEqual(cols["Star Saga Collection"]["poster"], "https://image.example.com/coll.jpg")
        items = self.ok("/v1/catalog/collections/tmdb.10")["items"]
        self.assertEqual(self.keys(items), ["m|p0|20", "m|p0|21"])


class TmdbResolver(unittest.TestCase):
    def test_collections_come_from_seerr_movie_details_and_are_kept(self):
        d = tempfile.mkdtemp()
        try:
            asked = []

            class Client:
                def get(self, path, params=None, user=None):
                    asked.append(path)
                    if path == "/movie/11":
                        return {"id": 11, "collection": {"id": 10, "name": "Star Saga Collection",
                                                         "posterPath": "/p.jpg", "backdropPath": None}}
                    return {"id": 12}
            path = os.path.join(d, "catalog-tmdb.json")
            t = CAT.TmdbCollections(path, lambda: Client(), pause=0)
            t.want(["11", "12", None])
            for th in threading.enumerate():
                if th.name == "orbit-tmdb-collections":
                    th.join(10)
            self.assertEqual(t.get("11"), ("10", "Star Saga Collection"))
            self.assertIsNone(t.get("12"))
            self.assertTrue(t.info("10")["poster"].startswith("https://image.tmdb.org/"))
            self.assertGreater(t.version, 0)
            again = CAT.TmdbCollections(path, lambda: Client(), pause=0)
            again.want(["11", "12"])
            self.assertEqual(sorted(asked), ["/movie/11", "/movie/12"], "known films are not asked for again")
            self.assertEqual(again.get("11"), ("10", "Star Saga Collection"))
            none = CAT.TmdbCollections(os.path.join(d, "x.json"), lambda: None)
            none.want(["5"])                       # Seerr not set up: nothing happens
            self.assertIsNone(none.get("5"))
        finally:
            shutil.rmtree(d, ignore_errors=True)


# --------------------------------------------------------------------------- franchise heuristic

class Franchise(unittest.TestCase):
    def groups(self, titles):
        return sorted(sorted(g["members"]) for g in FR.group(titles, lambda t: t).values())

    def test_real_series_group(self):
        g = self.groups(["Harry Potter and the Philosopher's Stone (2001)", "EN - Harry Potter and the Chamber of Secrets",
                         "Harry Potter and the Prisoner of Azkaban", "Toy Story", "Toy Story 2", "Toy Story 3 [4K]",
                         "Rocky", "Rocky II", "Rocky III", "Kill Bill: Vol. 1", "Kill Bill: Vol. 2",
                         "The Hunger Games", "The Hunger Games: Catching Fire", "The Hunger Games: Mockingjay - Part 1",
                         "The Hunger Games: Mockingjay - Part 2", "Pirates of the Caribbean: The Curse of the Black Pearl",
                         "Pirates of the Caribbean: Dead Man's Chest", "The Hangover", "The Hangover Part II",
                         "The Hangover Part III"])
        self.assertEqual(len(g), 7, g)
        self.assertIn(["The Hangover", "The Hangover Part II", "The Hangover Part III"], g)
        self.assertIn(["Kill Bill: Vol. 1", "Kill Bill: Vol. 2"], g)

    def test_no_false_merges(self):
        g = self.groups([
            "Super", "Super 8",                                  # one-word base, one "sequel"
            "Apollo 13", "Apollo 11",                            # numbers in titles are not sequels
            "Snow White and the Huntsman", "Snow White and the Seven Dwarfs",   # "and the" needs 3 titles
            "Beauty and the Beast", "Beauty and the Beast (2017)",              # one word, and a remake
            "Mission: Impossible", "Mission: Impossible - Fallout",             # one-word base before the colon
            "Blade Runner", "Blade Runner 2049", "2012", "2010", "Se7en", "Kiev",
            "The Godfather", "The Godfather: Part II",           # one-word base needs the 2 and the 3
            "Scream", "Scream 2", "District 9", "Fantastic 4",
            "The Movie: Part 2", "The Movie", "Live: Tonight", "Live: Tomorrow",  # generic bases
            "Toy Story", "Toy Story [4K]",                       # the same film twice is not a series
        ])
        self.assertEqual(g, [])

    def test_shapes(self):
        self.assertEqual(FR.shape("Iron Man 2")[0:3], ("num", "Iron Man", "iron man"))
        self.assertEqual(FR.shape("|EN| Iron Man 3 (2013)")[2], "iron man")
        self.assertEqual(FR.shape("Harry Potter and the Deathly Hallows: Part 2")[2], "harry potter")
        self.assertEqual(FR.shape("Apollo 13")[0], "plain")

    def test_tmdb_ids_beat_titles(self):
        films = ["Star Trek", "Star Trek: Into Darkness", "Star Trek Beyond", "Star Trek: Picard Special"]
        tm = {"Star Trek": ("1", "Kelvin"), "Star Trek: Into Darkness": ("1", "Kelvin"), "Star Trek Beyond": ("1", "Kelvin")}
        g = FR.group(films, lambda t: t, lambda t: tm.get(t))
        self.assertEqual(sorted(g), ["tmdb.1"], "the leftover one-title colon group does not count")
        self.assertEqual(len(g["tmdb.1"]["members"]), 3)


# --------------------------------------------------------------------------- parser and connector fields

class Fields(unittest.TestCase):
    def test_provider_fields(self):
        raw = json.dumps([{"stream_id": 5, "name": "A Film", "added": "1700000000", "tmdb": "603", "category_id": "1",
                           "releasedate": "1999-03-31"}, {"stream_id": 6, "name": "B", "added": "nonsense", "tmdb": 0}])
        m = P.parse_movies(raw.encode(), "p0", [])
        self.assertEqual((m[0].added, m[0].tmdb, m[0].released), (1700000000, "603", "1999-03-31"))
        self.assertEqual((m[1].added, m[1].tmdb, m[1].released), (None, None, None))
        self.assertNotIn("added", m[0].to_json(), "the web app's answers do not change")
        s = P.parse_series_list(json.dumps([{"series_id": 7, "name": "S", "last_modified": "1700000500",
                                             "backdrop_path": ["", "https://img.example.com/b.jpg"],
                                             "releaseDate": "2019-04-01"}]).encode(), "p0", [])
        self.assertEqual((s[0].added, s[0].backdrop, s[0].released), (1700000500, "https://img.example.com/b.jpg", "2019-04-01"))

    def test_catcache_reads_catalogues_stored_before_these_fields(self):
        vod = {"movie_cats": [], "series_cats": [], "series": [],
               "movies": [P.VodItem("m|p0|1", "p0", "movie", "1", "X", None, "C", None, "mp4", added=5, tmdb="9")]}
        back = catcache.decode(catcache.encode(vod=vod))["vod"]["movies"][0]
        self.assertEqual((back.added, back.tmdb), (5, "9"))
        import gzip
        old = {"v": 1, "vod": {"movie_cats": [], "series_cats": [], "series": [],
                               "movies": [["m|p0|1", "p0", "movie", "1", "X", None, "C", None, "mp4", None, "2001", None]]}}
        back = catcache.decode(gzip.compress(json.dumps(old).encode()))["vod"]["movies"][0]
        self.assertEqual((back.year, back.added, back.tmdb), ("2001", None, None))

    def test_plex_ultrablur_genres_and_dates(self):
        c = PLEX.Plex("http://192.0.2.10:32400", "t", "u", "d", Http("Plex"))
        it = c._norm({"ratingKey": "5", "type": "movie", "title": "F", "Genre": [{"tag": "Drama"}, {"tag": "Crime"}],
                      "addedAt": 1700000000, "originallyAvailableAt": "1999-03-31", "lastViewedAt": 1700000100,
                      "UltraBlurColors": {"topLeft": "1a2b3c", "topRight": "000000", "bottomRight": "4D5E6F",
                                          "bottomLeft": "ffffff"}})
        self.assertEqual(it["colors"], ["#1a2b3c", "#4d5e6f"])
        self.assertEqual(it["genres"], ["Drama", "Crime"])
        self.assertEqual((it["added"], it["released"], it["lastViewed"]), (1700000000, "1999-03-31", 1700000100))
        self.assertIsNone(c._norm({"ratingKey": "6", "type": "movie", "UltraBlurColors": {"topLeft": "zz"}})["colors"])

    def test_jellyfin_genres_and_dates(self):
        c = JF.Jellyfin("http://192.0.2.10:8096", "t", "u", "d", Http("Jellyfin"))
        it = c._norm({"Id": "x1", "Type": "Movie", "Name": "F", "Genres": ["Drama"],
                      "DateCreated": "2023-11-14T22:13:20.0000000Z", "PremiereDate": "1999-03-31T00:00:00.0000000Z",
                      "UserData": {"LastPlayedDate": "2023-11-14T22:15:00Z"}})
        self.assertEqual(it["genres"], ["Drama"])
        self.assertEqual((it["added"], it["released"], it["lastViewed"]), (1700000000, "1999-03-31", 1700000100))
        self.assertIsNone(it["colors"])


# --------------------------------------------------------------------------- benchmark

class Benchmark(Base):
    """A synthetic 20 000-title catalogue (15 000 films, 5 000 shows): index build, then requests answered from
    the warm index. Prints the timings; fails when a warm request takes 100 ms or more."""
    media = False

    def setUp(self):
        super().setUp()
        rnd = random.Random(7)
        genres = ["Drama", "Comedy", "Action", "Thriller", "Horror", "Science Fiction", "Romance", "Animation",
                  "Documentary", "Crime", "Fantasy", "Family"]
        words = ["Night", "River", "Last", "Silent", "Golden", "Broken", "Hidden", "Iron", "Paper", "Winter",
                 "Lost", "City", "Star", "Ghost", "Empire", "Shadow", "Storm", "Glass", "Wild", "Echo"]
        films, shows = [], []
        for i in range(15000):
            name = f"{rnd.choice(words)} {rnd.choice(words)} {i}"
            k = i // 50
            base = f"{words[k % 20]} {words[k // 20 % 20]} Quest " + "".join(chr(97 + int(x)) for x in str(k))
            if i % 50 == 0:
                name = base                                             # franchise: base, 2, 3
            elif i % 50 in (1, 2):
                name = f"{base} {i % 50 + 1}"
            films.append(mv(i, name, 1960 + i % 65, ", ".join(rnd.sample(genres, 2)), round(rnd.uniform(3, 9.5), 1),
                            1_600_000_000 + rnd.randint(0, 10 ** 8), cat=f"Cat {i % 40}"))
        for i in range(5000):
            shows.append(sh(i, f"{rnd.choice(words)} Show {i}", 1990 + i % 35, rnd.choice(genres),
                            round(rnd.uniform(3, 9.5), 1), 1_600_000_000 + rnd.randint(0, 10 ** 8)))
        self.vod = {"movies": films, "series": shows, "movie_cats": [], "series_cats": []}
        st, _ = self.st()
        for i in range(0, 300, 3):
            st.put("resume", f"m|p0|{i}", {"secs": 100 + i, "dur": 6000, "at": i})
        for i in range(0, 100, 7):
            st.put("mylist", f"m|p0|{i}", {"key": f"m|p0|{i}"})

    def test_20k_titles(self):
        t0 = time.perf_counter()
        self.cat.check(wait=True)
        build = (time.perf_counter() - t0) * 1000
        calls = [("home", "/v1/catalog/home", {}), ("movies/recommended", "/v1/catalog/movies/recommended", {}),
                 ("shows/recommended", "/v1/catalog/shows/recommended", {}),
                 ("movies/browse", "/v1/catalog/movies/browse", {"sort": "rating", "offset": 120, "limit": 60}),
                 ("movies/browse genre", "/v1/catalog/movies/browse", {"genre": "comedy", "sort": "title"}),
                 ("movies/collections", "/v1/catalog/movies/collections", {})]
        times = {}
        for pid in (PA, PB):                                    # PB: a second profile, nothing cached for it
            for name, path, q in calls:
                t = time.perf_counter()
                ans = self.ok(path, self.sess(pid), **q)
                first = (time.perf_counter() - t) * 1000
                t = time.perf_counter()
                self.ok(path, self.sess(pid), **q)
                again = (time.perf_counter() - t) * 1000
                times[(pid, name)] = (first, again)
                self.assertTrue(ans)
        cols = self.ok("/v1/catalog/movies/collections")["collections"]
        self.assertGreater(len(cols), 100)
        lines = [f"index build (20k titles): {build:.0f} ms"]
        for (pid, name), (first, again) in times.items():
            lines.append(f"  profile {'A' if pid == PA else 'B'} {name:22s} first {first:6.1f} ms, cached {again:5.2f} ms")
        print("\n" + "\n".join(lines))
        worst = max(f for f, _ in times.values())
        self.assertLess(worst, 100, "\n".join(lines))
        self.assertLess(max(a for _, a in times.values()), 10)


class Server(unittest.TestCase):
    """The real server: the routes are mounted, need a session, and answer an empty household catalogue."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp()
        cls.data = os.path.join(cls.tmp, "data")
        cls.port = free_port()
        env = dict(os.environ, TVBRO_PORT=str(cls.port), TVBRO_DATA=cls.data, PYTHONDONTWRITEBYTECODE="1")
        env.pop("TVBRO_KEY", None)
        cls.log = open(os.path.join(cls.tmp, "server.log"), "wb")
        cls.proc = subprocess.Popen([sys.executable, "run.py"], cwd=os.path.join(HERE, "..", "server"),
                                    env=env, stdout=cls.log, stderr=subprocess.STDOUT)
        cls.base = "http://127.0.0.1:%d" % cls.port
        for _ in range(150):
            try:
                cls.req("GET", "/api/health")
                break
            except Exception:                       # noqa: BLE001
                time.sleep(0.1)

    @classmethod
    def tearDownClass(cls):
        cls.proc.terminate()
        try:
            cls.proc.wait(10)
        except subprocess.TimeoutExpired:
            cls.proc.kill()
        cls.log.close()
        shutil.rmtree(cls.tmp, ignore_errors=True)

    @classmethod
    def req(cls, method, path, headers=None):
        r = urllib.request.Request(cls.base + path, method=method, headers=headers or {})
        try:
            with urllib.request.urlopen(r, timeout=60) as f:
                return f.status, json.loads(f.read() or b"{}")
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read() or b"{}")

    def test_routes(self):
        with open(os.path.join(self.data, "orbit-setup-token"), encoding="utf-8") as f:
            token = f.read()
        r = urllib.request.Request(self.base + "/v1/setup", method="POST",
                                   data=json.dumps({"token": token, "username": "alex", "password": "correct horse 1"}).encode(),
                                   headers={"Content-Type": "application/json", "X-TvBro": "1"})
        with urllib.request.urlopen(r, timeout=60) as f:
            cookie = f.headers.get("Set-Cookie", "").split(";", 1)[0]
        me = {"Cookie": cookie}
        self.assertEqual(self.req("GET", "/v1/catalog/home")[0], 401)
        self.assertEqual(self.req("GET", "/v1/catalog/home", me), (200, {"hero": [], "rows": []}))
        self.assertEqual(self.req("GET", "/v1/catalog/movies/browse?sort=title", me), (200, {"total": 0, "genres": [], "items": []}))
        self.assertEqual(self.req("GET", "/v1/catalog/shows/collections", me), (200, {"collections": []}))
        self.assertEqual(self.req("GET", "/v1/catalog/collections/t.0123456789ab", me)[0], 404)
        self.assertEqual(self.req("GET", "/v1/catalog/movies/browse?sort=nope", me)[0], 400)


if __name__ == "__main__":
    unittest.main()
