#!/usr/bin/env python3
# Copyright 2026 The Orbit authors
# SPDX-License-Identifier: Apache-2.0
"""A fictional Xtream Codes provider for Orbit demos and presentation screenshots.

Everything it serves is invented: channel names, programmes, films, shows, plots, artwork (gradient PNGs drawn
here with zlib + struct, no text) and video (an ffmpeg test pattern). Nothing refers to a real broadcaster, title
or person, and no real provider or account is involved.

    py -3 tools/demo/demo_provider.py [--port 8795] [--bind 127.0.0.1] [--user demo --password demo]

    Xtream server  http://127.0.0.1:8795   username demo   password demo   (a made-up fixture login)

Routes: player_api.php (user_info, get_live_categories, get_live_streams, get_vod_categories, get_vod_streams,
get_vod_info, get_series_categories, get_series, get_series_info, get_short_epg, get_simple_data_table),
xmltv.php (a 30-hour guide around now), get.php (M3U), /art/... (PNG artwork), /live/<u>/<p>/<id>.ts|.m3u8,
/movie/<u>/<p>/<id>.mp4, /series/<u>/<p>/<id>.mp4, /timeshift/....

Video needs ffmpeg on PATH: a 30-second test pattern (colour bars, a moving white bar, a quiet tone) is encoded
once into %TEMP%/orbit-demo-video (kept for the next start). Live .ts is that clip looped in real time by `ffmpeg -stream_loop -1 -c copy`
(one light process per open stream); live .m3u8 is a sliding window over its HLS segments; films and episodes get
the MP4 with byte ranges. Without ffmpeg every stream answers 404 and the rest still works.

Only the standard library is used. Ctrl+C stops it.
"""
from __future__ import annotations

import argparse
import base64
import colorsys
import datetime as dt
import functools
import hashlib
import json
import math
import os
import random
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import threading
import time
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse
from xml.sax.saxutils import escape, quoteattr

# --------------------------------------------------------------------------- the catalogue (all invented)

LIVE_GROUPS = [
    ("1", "News", ["Harbor News 24", "Meridian World", "Civic Desk", "Daybreak Live", "Northwind News",
                   "Headline One", "World Ledger", "Capital Wire", "Signal News", "Coastline News"]),
    ("2", "Sports", ["Arcadia Sports", "Sport Desk", "Pitchside", "Velocity Sport", "Courtline",
                     "Summit Outdoors", "Arena Plus", "Full Time", "Goalmouth", "Racing Line"]),
    ("3", "Entertainment", ["Harbor TV", "Lumen Drama", "Comedy Hour", "Nova One", "Atlas Travel",
                            "Kitchen Table", "Open House", "Tempo Music", "Vinyl Lounge", "Terra Docs"]),
    ("4", "Movies", ["Vista Cinema", "Starfall Films", "Silver Screen", "Midnight Reel", "Cinema Nova",
                     "Matinee", "Late Feature", "Reel Classics", "Premiere One", "Noir Night"]),
    ("5", "Kids", ["Kite Kids", "Tiny Planet", "Doodle TV", "Sprout Lane", "Bubble Bay",
                   "Puzzle Box", "Little Comets", "Cartoon Corner", "Moonbeam Jr", "Treehouse Club"]),
    ("6", "Locals", ["Harbor City Local", "Northgate 5", "Riverside Community", "Valley 12", "Lakeshore Local",
                     "Eastfield 3", "Westbrook Now", "Hillcrest TV", "Bayview 9", "Pinecrest Local"]),
]
PROGRAMMES = {
    "News": ["Headlines", "Morning Briefing", "The Midday Report", "World Tonight", "Market Close", "Weather Watch",
             "Late Edition", "Meridian Debate", "The Week Ahead", "Business Hour"],
    "Sports": ["Harbor City v Northgate", "Goals Review", "Full Time", "Matchday Live", "Court Report",
               "Mountain Trails", "The Final Whistle", "Track Weekly", "Racing Today", "Moonball Finals"],
    "Entertainment": ["Comedy Hour", "Saturday Evening", "Street Food Atlas", "Island Hopping", "Small Spaces",
                      "Live at the Hall", "Garden Rescue", "Market Day", "The Quiz Room", "Night Sky Guide"],
    "Movies": ["Late Feature: Night Ferry", "Matinee: Paper Kites", "Wildglass", "Ember Line", "Blue Hour Motel",
               "Dead Reckoning", "Tin Moon", "The Amber Hour", "Mirror Lake", "Coldharbour"],
    "Kids": ["Sleepy Stories", "Kite Club", "Pip and the Clouds", "Tiny Orbit", "Bramble Academy",
             "Paper Lanterns", "Sock Puppet Science", "The Busy Bees", "Moonbeam Tales", "Puzzle Pals"],
    "Locals": ["Local Headlines", "Council Live", "Community Calendar", "High School Sports", "Weather Now",
               "Neighbourhood Kitchen", "Town Hall", "Around the Valley", "Harbor Report", "Evening Local"],
}

# A big provider's shape: about 200 more groups named "<prefix> | <group>" (the owner's real list has 229 groups such
# as "Sports | NHL Teams" and "UK | News"), two channels each, so the Live TV quick tabs, the "All groups" side menu
# with its folders and its search can be tried. The channels of "Sports | NHL Teams" send overlapping guide entries
# (a long game with a short filler inside it, plus duplicates), which the server normalises.
SPORTS_GROUPS = ["NHL Teams", "NHL Replays", "NFL Replays", "NBA", "MLB", "MLS", "Soccer", "Golf", "Tennis", "Racing",
                 "Boxing", "Cricket", "Rugby", "Cycling", "Darts", "Snooker", "Wrestling", "College", "Extreme",
                 "Fishing", "Horse Racing", "Esports", "Outdoors", "Events"]
TOPIC_GROUPS = ["News", "Sports", "Entertainment", "Movies", "Kids", "Documentaries", "Music", "Locals", "Lifestyle",
                "Comedy", "Drama", "Reality", "Food", "Travel", "Science", "History", "Nature", "Faith", "Shopping",
                "Classics", "Weather", "Business", "Regional", "Plus 1", "4K", "Radio", "Events", "Westerns", "Anime",
                "Crime", "Game Shows"]
PREFIX_SIZES = [("UK", 18), ("US", 31), ("CA", 14), ("FR", 12), ("DE", 12), ("ES", 10), ("IT", 10), ("NL", 8),
                ("PT", 8), ("LATAM", 12), ("IN", 10), ("AR", 8), ("Ambiance", 6), ("Music", 8), ("PPV", 6),
                ("24/7", 8)]
EXTRA_PROGRAMMES = ["Live Coverage", "Highlights", "Replay", "The Magazine", "Studio Live", "Late Show",
                    "Morning Show", "Feature Presentation", "Special Edition", "Countdown"]
HOCKEY_TEAMS = ["Harbor City Mariners", "Northgate Lynx", "Valley Comets", "Lakeshore Pike", "Riverside Owls",
                "Eastfield Foxes"]


def extra_groups() -> list:
    """[(category id, group name, [channel names])] for the generated groups (deterministic)."""
    out, cid = [], 100
    named = [("Sports", g) for g in SPORTS_GROUPS] + [(pre, g) for pre, n in PREFIX_SIZES for g in TOPIC_GROUPS[:n]]
    for pre, g in named:
        cid += 1
        if (pre, g) == ("Sports", "NHL Teams"):
            names = [f"Hockey: {t}" for t in HOCKEY_TEAMS]
        else:
            names = [f"{pre} {g} {i}" for i in (1, 2)]
        out.append((str(cid), f"{pre} | {g}", names))
    return out


VOD_CATS = [("10", "Action & Adventure"), ("11", "Comedy"), ("12", "Drama"), ("13", "Sci-Fi & Fantasy"),
            ("14", "Thriller & Mystery"), ("15", "Horror"), ("16", "Kids & Family"), ("17", "Documentary")]
CAT_GENRES = {"10": ["Action", "Adventure"], "11": ["Comedy", "Romance"], "12": ["Drama", "Romance"],
              "13": ["Science Fiction", "Fantasy"], "14": ["Thriller", "Mystery", "Crime"], "15": ["Horror", "Mystery"],
              "16": ["Family", "Animation", "Adventure"], "17": ["Documentary"]}
SERIES_CATS = [("20", "Drama Series"), ("21", "Comedy Series"), ("22", "Crime & Mystery"), ("23", "Sci-Fi Series"),
               ("24", "Documentary Series"), ("25", "Kids Series")]
SERIES_GENRES = {"20": ["Drama"], "21": ["Comedy"], "22": ["Crime", "Mystery", "Thriller"],
                 "23": ["Science Fiction", "Fantasy"], "24": ["Documentary"], "25": ["Family", "Animation"]}

# (title, category, year) — franchises first, then hand-picked standalone titles; more are generated below.
FILMS_FIXED = [
    ("The Hollowmere Saga: The First Winter", "13", 2009), ("The Hollowmere Saga: Ashes of Hollowmere", "13", 2011),
    ("The Hollowmere Saga: The Hollowmere Key", "13", 2013), ("The Hollowmere Saga: Order of the Lantern", "13", 2015),
    ("The Hollowmere Saga: The Ninth Lantern", "13", 2017), ("The Hollowmere Saga: The Ninefold Gate", "13", 2019),
    ("The Hollowmere Saga: Winter at Hollowmere", "13", 2022), ("The Hollowmere Saga: The Last Lantern", "13", 2025),
    ("Copper Sky", "10", 2014), ("Copper Sky II", "10", 2017), ("Copper Sky III", "10", 2021),
    ("Neon Orchard", "14", 2018), ("Neon Orchard 2", "14", 2023),
    ("Fathom Station", "13", 2016), ("Fathom Station: Undertow", "13", 2020), ("Fathom Station: Signal from Below", "13", 2024),
    ("Starfall Ridge", "10", 2019), ("Starfall Ridge 2", "10", 2022),
    ("Ember Line", "14", 2024), ("Wildglass", "12", 2025), ("Arc of Embers", "10", 2023), ("Ballast", "14", 2021),
    ("Blue Hour Motel", "14", 2020), ("Cold Water", "12", 2019), ("Coldharbour", "14", 2022), ("Dead Reckoning", "10", 2018),
    ("Driftwood Saints", "12", 2021), ("Foxglove Hill", "15", 2023), ("Glow Season", "11", 2024), ("Gravity Lullaby", "13", 2022),
    ("Halcyon Drift", "13", 2025), ("Harborlight", "12", 2017), ("Hollow Pines", "15", 2020), ("Ironwood", "10", 2021),
    ("Lantern Season", "16", 2023), ("Low Tide Republic", "11", 2022), ("Mirror Lake", "15", 2024), ("Moonball", "16", 2021),
    ("Night Ferry", "14", 2023), ("Northbound Quiet", "12", 2020), ("Northern Static", "13", 2019), ("Orchid Protocol", "14", 2025),
    ("Paper Comets", "16", 2024), ("Pressure", "10", 2018), ("Quiet Machines", "13", 2023), ("Sable Coast", "12", 2022),
    ("Saffron Engine", "10", 2024), ("Saltwater Kings", "10", 2020), ("Second Sunrise", "12", 2025), ("Seventh Tide", "13", 2021),
    ("Small Hours", "11", 2019), ("Terminal Bloom", "15", 2025), ("The Amber Hour", "12", 2023), ("The Glass Orchard", "12", 2021),
    ("The Kindling", "15", 2022), ("The Last Cartographer", "10", 2024), ("The Late Room", "14", 2020), ("The Long Meridian", "12", 2018),
    ("The Pale Orchard", "15", 2019), ("The Sable Prince", "13", 2022), ("The Trench", "15", 2021), ("Tidewater", "14", 2024),
    ("Tin Moon", "16", 2020), ("Undertow Club", "11", 2023), ("Velvet Static", "14", 2025), ("A Map of Tuesdays", "11", 2022),
    ("Kite Club", "16", 2025), ("Engines of Earth", "17", 2023), ("Wild Plateaus", "17", 2024), ("The Salt Road", "17", 2021),
]
ADJ = ["Amber", "Silent", "Hidden", "Crimson", "Hollow", "Silver", "Broken", "Golden", "Distant", "Paper", "Iron",
       "Burning", "Frozen", "Velvet", "Restless", "Northern", "Glass", "Lonely", "Wandering", "Midnight", "Copper",
       "Scarlet", "Quiet", "Winter", "Bright", "Lost", "Salt", "Electric", "Gentle", "Faded"]
NOUN = ["Harbor", "Lantern", "Orchard", "Meridian", "Tide", "Compass", "Lighthouse", "Ferry", "Signal", "Garden",
        "Frontier", "Canyon", "Atlas", "Kingdom", "Station", "Summer", "Engine", "Archive", "Comet", "Forest",
        "Ridge", "Island", "Horizon", "Machine", "Letter", "Voyage", "Valley", "Mirror", "Circus", "Monsoon"]
PLACE = ["Alder Bay", "Wrenfield", "Marrow Point", "Castellan", "Osprey Hill", "Brindlewood", "Kestrel Lake",
         "Larkspur", "Thornbury", "Calder Falls", "Vantmoor", "Ashgrove"]

SHOWS_FIXED = [
    ("The Quiet Bureau", "22", 2022), ("Glass Harbor", "20", 2024), ("Hinterland Radio", "20", 2021),
    ("Marrow Bay", "22", 2023), ("The Cinder Files", "22", 2020), ("Lowland", "20", 2019), ("Brightwater", "20", 2025),
    ("Signal & Noise", "23", 2024), ("The Orchard House", "20", 2022), ("Parallel Lines", "23", 2021),
    ("Copperhead County", "22", 2023), ("Static Bloom", "23", 2025), ("North of Nowhere", "21", 2020),
    ("The Long Field", "20", 2018), ("Riverlight", "20", 2024), ("Afterglow Diner", "21", 2022), ("Wren & Pike", "22", 2025),
    ("The Salt Office", "21", 2023), ("Deepwell", "23", 2022), ("Moth Season", "20", 2021), ("Kestrel", "22", 2024),
    ("The Understudies", "21", 2019), ("The Ninth Floor", "21", 2025), ("Ferryman's Rest", "20", 2023),
    ("Quarry Lane", "22", 2021), ("Tallow & Thread", "20", 2024), ("The Weather House", "21", 2022),
    ("Dune Riders", "23", 2023), ("Silverline", "23", 2020), ("Orbit Nine", "23", 2025),
    ("Street Food Atlas", "24", 2023), ("Island Hopping", "24", 2022), ("Night Sky Guide", "24", 2024),
    ("Secrets of the Reef", "24", 2021), ("Sleepy Stories", "25", 2022), ("Pip and the Clouds", "25", 2021),
    ("Tiny Orbit", "25", 2024), ("Bramble Academy", "25", 2023), ("Paper Lanterns", "25", 2025),
    ("Sock Puppet Science", "25", 2020),
]
EP_WORDS = ["Arrival", "The Map", "Low Tide", "Crossings", "The Long Night", "Static", "Homecoming", "Lanterns",
            "The Signal", "Undertow", "First Light", "Ashes", "The Key", "Northbound", "Paper Trails", "Glass",
            "The Bureau", "Driftwood", "Echoes", "Thaw", "Embers", "The Quiet", "Harbor Lights", "Last Ferry"]

PLOT_A = ["A retired cartographer", "Two estranged siblings", "A night-shift radio host", "A young lighthouse keeper",
          "A disgraced investigator", "An unlikely crew of misfits", "A small-town mechanic", "A gifted violinist",
          "A pair of rival chefs", "A grieving astronomer", "An ageing ferry captain", "A runaway apprentice"]
PLOT_B = ["uncovers a map that should not exist", "must cross a frozen sea before the thaw",
          "receives a signal from a station abandoned years ago", "inherits a crumbling hotel full of secrets",
          "is pulled into a conspiracy that reaches the top", "races to save the last orchard in the valley",
          "finds a door in the lighthouse that was never on the plans", "gets one week to win back the town",
          "chases a rumour across a string of forgotten islands", "wakes up to a city where every clock has stopped"]
PLOT_C = ["in {place}.", "while a storm closes in on {place}.", "before the festival in {place}.",
          "and nothing in {place} will be the same.", "with the help of an old friend from {place}."]
PEOPLE = ["Ada Merrow", "Teo Lindqvist", "Rosa Calloway", "Imre Vance", "Juno Halsey", "Pell Okafor", "Maren Szabo",
          "Cass Whitlow", "Noor Faraday", "Elio Brandt", "Ines Corwin", "Wes Abernathy", "Lia Thorne", "Otto Renard"]


def slug(s: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")


def seed_of(*parts) -> int:
    return int.from_bytes(hashlib.sha1("|".join(map(str, parts)).encode()).digest()[:8], "big")


def plot(rnd: random.Random) -> str:
    return f"{rnd.choice(PLOT_A)} {rnd.choice(PLOT_B)} {rnd.choice(PLOT_C).format(place=rnd.choice(PLACE))}"


def day_anchor() -> int:
    """Today's midnight (UTC), so generated 'added' times are stable for a day and always recent."""
    now = dt.datetime.now(dt.timezone.utc)
    return int(dt.datetime(now.year, now.month, now.day, tzinfo=dt.timezone.utc).timestamp())


class Catalogue:
    """The whole fictional catalogue, built deterministically (the same titles, ids and dates on every run)."""

    def __init__(self, n_films: int = 150):
        anchor = day_anchor()
        self.channels = []
        n = 0
        self.live_groups = LIVE_GROUPS + extra_groups()
        for cid, group, names in self.live_groups:
            for name in names:
                n += 1
                self.channels.append({"num": n, "name": name, "id": 100 + n, "group": group, "cat": cid,
                                      "epg": slug(name) + ".demo", "archive": 1 if n % 3 == 0 else 0,
                                      "overlap": group == "Sports | NHL Teams"})
        self.films = []
        seen = set()
        rnd = random.Random(7)
        titles = list(FILMS_FIXED)
        while len(titles) < n_films:
            pat = rnd.randrange(4)
            a, b = rnd.choice(ADJ), rnd.choice(NOUN)
            t = (f"The {a} {b}", f"{a} {b}", f"The {b} of {rnd.choice(PLACE)}", f"{b} at {rnd.choice(PLACE)}")[pat]
            if t.lower() not in seen and not any(t == x[0] for x in titles):
                seen.add(t.lower())
                titles.append((t, rnd.choice(VOD_CATS)[0], rnd.randint(1994, 2025)))
        for i, (title, cat, year) in enumerate(titles):
            r = random.Random(seed_of("film", title))
            g = list(CAT_GENRES[cat])
            r.shuffle(g)
            genres = g[:r.randint(1, min(2, len(g)))]
            # added: the newest films today, the rest spread over about eight months (recent releases newer)
            age_days = r.randint(0, 240) if year < 2024 else r.randint(0, 45)
            self.films.append({
                "id": 2001 + i, "name": title, "cat": cat, "year": year, "genre": ", ".join(genres),
                "rating": round(r.uniform(5.4, 8.9), 1), "added": anchor - age_days * 86400 - r.randint(0, 86000),
                "released": f"{year}-{r.randint(1, 12):02d}-{r.randint(1, 28):02d}", "plot": plot(r),
                "mins": r.randint(84, 152), "cast": ", ".join(r.sample(PEOPLE, 3)), "director": r.choice(PEOPLE)})
        self.shows = []
        ep_id = 50001
        for i, (title, cat, year) in enumerate(SHOWS_FIXED):
            r = random.Random(seed_of("show", title))
            g = list(SERIES_GENRES[cat])
            r.shuffle(g)
            seasons = r.randint(1, 4)
            eps = {}
            for s in range(1, seasons + 1):
                count = r.randint(6, 10)
                names = r.sample(EP_WORDS, count)
                eps[s] = []
                for e in range(1, count + 1):
                    eps[s].append({"id": ep_id, "num": e, "title": names[e - 1], "plot": plot(r),
                                   "mins": r.randint(22, 30) if cat in ("21", "25") else r.randint(42, 58),
                                   "aired": f"{year + s - 1}-{r.randint(1, 12):02d}-{r.randint(1, 28):02d}"})
                    ep_id += 1
            age_days = r.randint(0, 200) if year < 2025 else r.randint(0, 20)
            self.shows.append({
                "id": 3001 + i, "name": title, "cat": cat, "year": year, "genre": ", ".join(g[:2]),
                "rating": round(r.uniform(6.0, 9.1), 1), "added": anchor - age_days * 86400 - r.randint(0, 86000),
                "released": f"{year}-{r.randint(1, 12):02d}-{r.randint(1, 28):02d}", "plot": plot(r),
                "cast": ", ".join(r.sample(PEOPLE, 3)), "seasons": eps})
        self.film_by_id = {f["id"]: f for f in self.films}
        self.show_by_id = {s["id"]: s for s in self.shows}
        self.chan_by_id = {c["id"]: c for c in self.channels}

    # ---- the guide

    def programmes(self, ch: dict, start: int, end: int) -> list:
        """[(start, stop, title, desc)] for one channel; the schedule is fixed per channel and day."""
        out = []
        day0 = start - start % 86400
        t = day0
        names = PROGRAMMES.get(ch["group"], EXTRA_PROGRAMMES)
        while t < end:
            day = t - t % 86400
            r = random.Random(seed_of("epg", ch["id"], day, t))
            if ch.get("overlap") and (t - day) % 21600 == 0:
                # a provider that overlaps: a 5-hour game, a filler inside it, the game listed twice and a
                # same-title piece inside it (every 6 hours)
                game = f"NHL Hockey on {ch['name'].split(': ', 1)[-1]}"
                stop = t + 6 * 3600
                if stop > start:
                    out += [(t, t + 5 * 3600, game, f"{game}. {plot(r)}"), (t, t + 5 * 3600, game, f"{game}. {plot(r)}"),
                            (t + 2 * 3600, t + 2 * 3600 + 1800, "Next Game Today", "What is on next."),
                            (t + 3600, t + 7200, game, "Second period."),
                            (t + 5 * 3600, stop, "Hockey Tonight", "Highlights and analysis.")]
                t = stop
                continue
            mins = r.choice([30, 30, 60, 60, 60, 90, 120]) if ch["group"] != "Movies" else r.choice([90, 105, 120])
            stop = min(t + mins * 60, day + 86400)
            if stop > start:
                title = r.choice(names)
                out.append((t, stop, title, f"{title} on {ch['name']}. {plot(r)}"))
            t = stop
        return out


# --------------------------------------------------------------------------- artwork (PNG, stdlib only)

def _png(w: int, h: int, rows) -> bytes:
    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    raw = b"".join(b"\x00" + bytes(r) for r in rows)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 6)) + chunk(b"IEND", b""))


def _rgb(h, s, v):
    return tuple(int(round(c * 255)) for c in colorsys.hsv_to_rgb(h % 1.0, max(0, min(1, s)), max(0, min(1, v))))


def palette(key: str):
    """A distinct, pleasant palette per title: a bright colour, a dark partner and a light accent."""
    r = random.Random(seed_of("palette", key))
    h = r.random()
    c1 = _rgb(h, r.uniform(0.55, 0.85), r.uniform(0.62, 0.9))
    c2 = _rgb(h + r.choice([-1, 1]) * r.uniform(0.07, 0.3), r.uniform(0.5, 0.85), r.uniform(0.12, 0.28))
    accent = _rgb(h + r.uniform(0.35, 0.65), r.uniform(0.25, 0.5), 1.0)
    return c1, c2, accent, r


_DARK = [bytes(int(v * (1 - k / 80)) for v in range(256)) for k in range(81)]    # brightness tables, 0..100 % darker


@functools.lru_cache(maxsize=600)
def artwork(kind: str, key: str) -> bytes:
    """A PNG: kind 'poster' (2:3), 'backdrop' (16:9), 'still' (16:9, small) or 'logo' (square)."""
    w, h = {"poster": (400, 600), "backdrop": (1280, 720), "still": (480, 270), "logo": (256, 256)}[kind]
    c1, c2, accent, r = palette(key)
    if r.random() < 0.5:
        c1, c2 = c2, c1
    k = 2 if kind == "poster" else 1             # gradient direction: colour at (x, y) is ramp[x + k*y]
    n = w + k * h
    ramp = bytearray(n * 3)
    for i in range(n):
        f = i / (n - 1)
        f = f * f * (3 - 2 * f)
        ramp[i * 3:i * 3 + 3] = bytes(int(c1[j] + (c2[j] - c1[j]) * f) for j in range(3))

    def tinted(col, a):
        return bytes(int(ramp[i] * (1 - a) + col[i % 3] * a) for i in range(len(ramp)))

    shapes = []                                  # (kind, params, tinted ramp)
    if kind == "logo":
        shapes.append(("disc", (w / 2, h / 2, w * 0.36), tinted(accent, 0.55)))
        shapes.append(("disc", (w / 2, h / 2, w * 0.2), tinted(c1, 0.6)))
    else:
        if r.random() < 0.6:                     # a horizon band
            y0 = r.uniform(0.45, 0.75) * h
            shapes.append(("band", (y0, y0 + r.uniform(0.04, 0.12) * h), tinted(accent, r.uniform(0.08, 0.18))))
        for _ in range(r.randint(2, 5)):         # soft discs, some concentric
            cx, cy = r.uniform(-0.1, 1.1) * w, r.uniform(0.05, 0.85) * h
            rad = r.uniform(0.1, 0.45) * min(w, h) * (1.4 if kind != "poster" else 1)
            shapes.append(("disc", (cx, cy, rad), tinted(accent if r.random() < 0.6 else c1, r.uniform(0.08, 0.24))))
            if r.random() < 0.4:
                shapes.append(("disc", (cx, cy, rad * r.uniform(0.45, 0.7)), tinted(accent, r.uniform(0.25, 0.4))))
    rows = []
    for y in range(h):
        o = k * y
        row = bytearray(ramp[o * 3:(o + w) * 3])
        for sk, p, tr in shapes:
            if sk == "band":
                if p[0] <= y < p[1]:
                    row[:] = tr[o * 3:(o + w) * 3]
            else:
                cx, cy, rad = p
                dy = y + 0.5 - cy
                if abs(dy) < rad:
                    dx = math.sqrt(rad * rad - dy * dy)
                    x0, x1 = max(0, int(cx - dx)), min(w, int(cx + dx))
                    if x1 > x0:
                        row[x0 * 3:x1 * 3] = tr[(o + x0) * 3:(o + x1) * 3]
        if kind in ("poster", "still", "backdrop"):  # darker towards the bottom, where clients put text
            f = y / h
            lvl = int(max(0.0, (f - 0.55) / 0.45) * (36 if kind == "poster" else 26))
            if lvl:
                row = row.translate(_DARK[lvl])
        rows.append(row)
    return _png(w, h, rows)


# --------------------------------------------------------------------------- test video (ffmpeg)

class Video:
    """The 30-second test clip as MPEG-TS, MP4 and 2-second HLS segments, made once per start-up."""
    SECS = 30

    def __init__(self, folder: str | None = None):
        self.ffmpeg = shutil.which("ffmpeg")
        self.dir = folder or tempfile.mkdtemp(prefix="orbit-demo-video-")
        os.makedirs(self.dir, exist_ok=True)
        self.ts = os.path.join(self.dir, "loop.ts")
        self.mp4 = os.path.join(self.dir, "loop.mp4")
        self.segments: list = []                 # [(file name, seconds)]
        self.ok = False
        self.error = "ffmpeg is not on PATH" if not self.ffmpeg else ""

    def build(self) -> bool:
        if not self.ffmpeg:
            return False
        s = self.SECS
        try:
            if not os.path.exists(self.ts):
                self._run(["-f", "lavfi", "-i", f"smptehdbars=s=1280x720:r=25:d={s}",
                           "-f", "lavfi", "-i", f"color=c=white:s=40x720:r=25:d={s}",
                           "-f", "lavfi", "-i", f"sine=frequency=440:sample_rate=48000:d={s}",
                           "-filter_complex", "[0][1]overlay=x='mod(t*256,W+40)-40':y=0:shortest=1[v];[2]volume=0.12[a]",
                           "-map", "[v]", "-map", "[a]", "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p",
                           "-g", "50", "-keyint_min", "50", "-sc_threshold", "0", "-b:v", "1500k",
                           "-c:a", "aac", "-b:a", "96k", "-f", "mpegts", self.ts + ".part"])
                os.replace(self.ts + ".part", self.ts)       # never leave a half-made clip for the next start
            if not os.path.exists(self.mp4):
                self._run(["-i", self.ts, "-c", "copy", "-movflags", "+faststart", "-f", "mp4", self.mp4 + ".part"])
                os.replace(self.mp4 + ".part", self.mp4)
            pl = os.path.join(self.dir, "loop.m3u8")
            if not os.path.exists(pl):
                self._run(["-i", self.ts, "-c", "copy", "-f", "hls", "-hls_time", "2", "-hls_list_size", "0",
                           "-hls_segment_filename", os.path.join(self.dir, "seg%03d.ts"), pl + ".part.m3u8"])
                os.replace(pl + ".part.m3u8", pl)
            dur = None
            with open(pl, encoding="utf-8") as fh:
                text = fh.read()
            for line in text.splitlines():
                if line.startswith("#EXTINF:"):
                    dur = float(line[8:].split(",")[0])
                elif line and not line.startswith("#") and dur:
                    self.segments.append((line.strip(), dur))
                    dur = None
            self.ok = bool(self.segments)
        except Exception as e:                   # noqa: BLE001
            self.error = f"ffmpeg failed: {e}"
            self.ok = False
        return self.ok

    def _run(self, args):
        r = subprocess.run([self.ffmpeg, "-hide_banner", "-loglevel", "error", "-y", *args],
                           capture_output=True, timeout=300)
        if r.returncode:
            raise RuntimeError(r.stderr.decode(errors="replace")[-400:])

    def live_playlist(self, now: float, prefix: str) -> str:
        """A live HLS window of 5 segments over the looped clip, with a discontinuity at each wrap."""
        total = sum(d for _, d in self.segments)
        n = len(self.segments)
        t = now % (total * 1000)                 # position inside a long cycle
        loop, pos = divmod(t, total)
        idx, acc = 0, 0.0
        while idx < n - 1 and acc + self.segments[idx][1] <= pos:
            acc += self.segments[idx][1]
            idx += 1
        first = int(loop) * n + idx              # absolute segment number
        start = max(0, first - 4)
        lines = ["#EXTM3U", "#EXT-X-VERSION:3", f"#EXT-X-TARGETDURATION:{math.ceil(max(d for _, d in self.segments))}",
                 f"#EXT-X-MEDIA-SEQUENCE:{start}", f"#EXT-X-DISCONTINUITY-SEQUENCE:{start // n}"]
        for a in range(start, first + 1):
            name, d = self.segments[a % n]
            if a % n == 0 and a != start:
                lines.append("#EXT-X-DISCONTINUITY")
            lines += [f"#EXTINF:{d:.3f},", prefix + name]
        return "\n".join(lines) + "\n"


# --------------------------------------------------------------------------- HTTP

def b64(s: str) -> str:
    return base64.b64encode(s.encode()).decode()


class Provider:
    def __init__(self, user="demo", password="demo", video: Video | None = None):
        self.user, self.password = user, password
        self.cat = Catalogue()
        self.video = video

    # ---- player_api.php

    def api(self, q: dict, base: str):
        c = self.cat
        a = q.get("action", [""])[0]
        cat_filter = q.get("category_id", [""])[0]
        art = lambda kind, key: f"{base}/art/{kind}/{slug(key)}.png"   # noqa: E731
        if not a:
            now = int(time.time())
            host, _, port = base.split("//", 1)[1].partition(":")
            return {"user_info": {"username": self.user, "password": self.password, "message": "Orbit demo provider",
                                  "auth": 1, "status": "Active", "exp_date": str(now + 365 * 86400), "is_trial": "0",
                                  "active_cons": "0", "created_at": str(now - 400 * 86400), "max_connections": "4",
                                  "allowed_output_formats": ["m3u8", "ts"]},
                    "server_info": {"url": host, "port": port or "80", "https_port": "", "server_protocol": "http",
                                    "rtmp_port": "", "timezone": "UTC", "timestamp_now": now,
                                    "time_now": time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime(now))}}
        if a == "get_live_categories":
            return [{"category_id": cid, "category_name": g, "parent_id": 0} for cid, g, _ in c.live_groups]
        if a == "get_live_streams":
            return [{"num": ch["num"], "name": ch["name"], "stream_type": "live", "stream_id": ch["id"],
                     "stream_icon": art("logo", ch["name"]), "epg_channel_id": ch["epg"], "added": "1700000000",
                     "category_id": ch["cat"], "custom_sid": "", "tv_archive": ch["archive"], "direct_source": "",
                     "tv_archive_duration": 3 if ch["archive"] else 0}
                    for ch in c.channels if not cat_filter or ch["cat"] == cat_filter]
        if a == "get_vod_categories":
            return [{"category_id": i, "category_name": n, "parent_id": 0} for i, n in VOD_CATS]
        if a == "get_vod_streams":
            return [{"num": n + 1, "name": f["name"], "title": f["name"], "year": str(f["year"]), "stream_type": "movie",
                     "stream_id": f["id"], "stream_icon": art("poster", f["name"]), "rating": str(f["rating"]),
                     "rating_5based": round(f["rating"] / 2, 1), "added": str(f["added"]), "category_id": f["cat"],
                     "genre": f["genre"], "releaseDate": f["released"], "plot": f["plot"],
                     "backdrop_path": [art("backdrop", f["name"])], "container_extension": "mp4",
                     "custom_sid": "", "direct_source": "", "tmdb": ""}
                    for n, f in enumerate(c.films) if not cat_filter or f["cat"] == cat_filter]
        if a == "get_vod_info":
            f = c.film_by_id.get(_int(q.get("vod_id", [""])[0]))
            if not f:
                return {"info": [], "movie_data": {}}
            secs = f["mins"] * 60
            return {"info": {"name": f["name"], "o_name": f["name"], "tmdb_id": "", "plot": f["plot"],
                             "description": f["plot"], "releasedate": f["released"], "year": str(f["year"]),
                             "rating": str(f["rating"]), "genre": f["genre"], "cast": f["cast"], "actors": f["cast"],
                             "director": f["director"], "duration_secs": secs,
                             "duration": f"{secs // 3600:02d}:{secs % 3600 // 60:02d}:00", "country": "Demoland",
                             "movie_image": art("poster", f["name"]), "cover_big": art("poster", f["name"]),
                             "backdrop_path": [art("backdrop", f["name"])], "youtube_trailer": ""},
                    "movie_data": {"stream_id": f["id"], "name": f["name"], "added": str(f["added"]),
                                   "category_id": f["cat"], "container_extension": "mp4", "custom_sid": "",
                                   "direct_source": ""}}
        if a == "get_series_categories":
            return [{"category_id": i, "category_name": n, "parent_id": 0} for i, n in SERIES_CATS]
        if a == "get_series":
            return [{"num": n + 1, "name": s["name"], "title": s["name"], "year": str(s["year"]), "series_id": s["id"],
                     "cover": art("poster", s["name"]), "plot": s["plot"], "cast": s["cast"], "director": "",
                     "genre": s["genre"], "releaseDate": s["released"], "last_modified": str(s["added"]),
                     "rating": str(s["rating"]), "rating_5based": round(s["rating"] / 2, 1),
                     "backdrop_path": [art("backdrop", s["name"])], "youtube_trailer": "", "episode_run_time": "",
                     "category_id": s["cat"], "tmdb": ""}
                    for n, s in enumerate(c.shows) if not cat_filter or s["cat"] == cat_filter]
        if a == "get_series_info":
            s = c.show_by_id.get(_int(q.get("series_id", [""])[0]))
            if not s:
                return {"seasons": [], "info": {}, "episodes": {}}
            episodes = {}
            for sn, eps in s["seasons"].items():
                episodes[str(sn)] = [{
                    "id": str(e["id"]), "episode_num": e["num"], "season": sn, "title": f"{s['name']} - S{sn:02d}E{e['num']:02d} - {e['title']}",
                    "container_extension": "mp4", "added": str(s["added"]), "custom_sid": "", "direct_source": "",
                    "info": {"name": e["title"], "plot": e["plot"], "releasedate": e["aired"], "air_date": e["aired"],
                             "duration_secs": e["mins"] * 60, "duration": f"00:{e['mins']:02d}:00",
                             "movie_image": art("still", f"{s['name']} {sn} {e['num']}"), "rating": str(s["rating"]),
                             "season": sn, "tmdb_id": ""}} for e in eps]
            return {"seasons": [{"season_number": sn, "name": f"Season {sn}", "episode_count": len(eps),
                                 "air_date": eps[0]["aired"], "cover": art("poster", s["name"]),
                                 "cover_big": art("poster", s["name"])} for sn, eps in s["seasons"].items()],
                    "info": {"name": s["name"], "title": s["name"], "year": str(s["year"]), "plot": s["plot"],
                             "cast": s["cast"], "director": "", "genre": s["genre"], "releaseDate": s["released"],
                             "last_modified": str(s["added"]), "rating": str(s["rating"]),
                             "cover": art("poster", s["name"]), "backdrop_path": [art("backdrop", s["name"])],
                             "episode_run_time": str(next(iter(s["seasons"].values()))[0]["mins"]), "category_id": s["cat"],
                             "tmdb": ""},
                    "episodes": episodes}
        if a in ("get_short_epg", "get_simple_data_table"):
            ch = c.chan_by_id.get(_int(q.get("stream_id", [""])[0]))
            if not ch:
                return {"epg_listings": []}
            now = int(time.time())
            if a == "get_short_epg":
                progs = [p for p in c.programmes(ch, now, now + 12 * 3600) if p[1] > now][:max(1, _int(q.get("limit", ["4"])[0]) or 4)]
            else:
                progs = c.programmes(ch, now - 6 * 3600, now + 24 * 3600)
            fmt = lambda t: time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime(t))   # noqa: E731
            return {"epg_listings": [{"id": str(seed_of(ch["id"], p[0]) % 10**9), "epg_id": "1", "title": b64(p[2]),
                                      "lang": "en", "start": fmt(p[0]), "end": fmt(p[1]), "description": b64(p[3]),
                                      "channel_id": ch["epg"], "start_timestamp": str(p[0]), "stop_timestamp": str(p[1]),
                                      "now_playing": 1 if p[0] <= now < p[1] else 0, "has_archive": ch["archive"]}
                                     for p in progs]}
        return []

    def xmltv(self, base: str) -> bytes:
        now = int(time.time())
        start = now - now % 3600 - 6 * 3600
        f = lambda t: time.strftime("%Y%m%d%H%M%S +0000", time.gmtime(t))   # noqa: E731
        out = ['<?xml version="1.0" encoding="UTF-8"?>', '<tv generator-info-name="Orbit demo provider">']
        for ch in self.cat.channels:
            logo = quoteattr(f"{base}/art/logo/{slug(ch['name'])}.png")
            out.append(f'<channel id={quoteattr(ch["epg"])}><display-name>{escape(ch["name"])}</display-name>'
                       f'<icon src={logo}/></channel>')
        for ch in self.cat.channels:
            for a, b, title, desc in self.cat.programmes(ch, start, start + 30 * 3600):
                out.append(f'<programme start="{f(a)}" stop="{f(b)}" channel={quoteattr(ch["epg"])}>'
                           f'<title lang="en">{escape(title)}</title><desc lang="en">{escape(desc)}</desc>'
                           f'<category lang="en">{escape(ch["group"])}</category></programme>')
        out.append("</tv>")
        return "\n".join(out).encode()

    def m3u(self, base: str) -> bytes:
        u, p = self.user, self.password
        out = [f'#EXTM3U url-tvg="{base}/xmltv.php?username={u}&password={p}"']
        for ch in self.cat.channels:
            logo = f"{base}/art/logo/{slug(ch['name'])}.png"
            out.append(f'#EXTINF:-1 tvg-id="{ch["epg"]}" tvg-name="{ch["name"]}" tvg-logo="{logo}" '
                       f'tvg-chno="{ch["num"]}" group-title="{ch["group"]}",{ch["name"]}')
            out.append(f"{base}/live/{u}/{p}/{ch['id']}.ts")
        return ("\n".join(out) + "\n").encode()

    def art_key(self, kind: str, name: str) -> str | None:
        """The title an artwork slug belongs to (any slug works, so stills of every episode draw)."""
        return name if kind in ("poster", "backdrop", "still", "logo") and re.fullmatch(r"[a-z0-9-]{1,120}", name) else None


def _int(v) -> int:
    try:
        return int(str(v).split(".")[0])
    except (TypeError, ValueError):
        return 0


def make_handler(prov: Provider):
    class H(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        server_version = "OrbitDemoProvider/1"

        def log_message(self, fmt, *args):
            if os.environ.get("ORBIT_DEMO_VERBOSE"):
                sys.stderr.write("demo-provider: " + (fmt % args) + "\n")

        def base(self) -> str:
            host = self.headers.get("Host") or f"127.0.0.1:{self.server.server_address[1]}"
            return "http://" + host if re.fullmatch(r"[A-Za-z0-9.\-\[\]:]{1,100}", host) else "http://127.0.0.1"

        def send(self, code: int, body: bytes, ctype: str, extra: dict | None = None):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            for k, v in (extra or {}).items():
                self.send_header(k, v)
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(body)

        def js(self, obj):
            self.send(200, json.dumps(obj).encode(), "application/json")

        def not_found(self, why=b"not found"):
            self.send(404, why, "text/plain")

        def authed(self, q) -> bool:
            return q.get("username", [""])[0] == prov.user and q.get("password", [""])[0] == prov.password

        def do_HEAD(self):
            self.do_GET()

        def do_GET(self):
            try:
                self.route()
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                pass

        def route(self):
            u = urlparse(self.path)
            q, p = parse_qs(u.query), u.path
            base = self.base()
            if p == "/player_api.php":
                if not self.authed(q):
                    return self.js({"user_info": {"auth": 0}})
                return self.js(prov.api(q, base))
            if p == "/xmltv.php":
                if not self.authed(q):
                    return self.send(401, b"unauthorised", "text/plain")
                return self.send(200, prov.xmltv(base), "application/xml; charset=utf-8")
            if p == "/get.php":
                if not self.authed(q):
                    return self.send(401, b"unauthorised", "text/plain")
                return self.send(200, prov.m3u(base), "audio/x-mpegurl")
            m = re.fullmatch(r"/art/(poster|backdrop|still|logo)/([^/]+)\.png", p)
            if m:
                key = prov.art_key(m.group(1), m.group(2))
                if not key:
                    return self.not_found()
                return self.send(200, artwork(m.group(1), key), "image/png", {"Cache-Control": "public, max-age=86400"})
            m = re.fullmatch(r"/(live|movie|series|timeshift)/([^/]+)/([^/]+)/(.+)", p)
            if m:
                if m.group(2) != prov.user or m.group(3) != prov.password:
                    return self.send(401, b"unauthorised", "text/plain")
                return self.media(m.group(1), m.group(4))
            m = re.fullmatch(r"/hls/(seg\d{3}\.ts)", p)
            if m:
                return self.file(m.group(1), "video/mp2t")
            if p == "/":
                return self.send(200, b"Orbit demo provider (everything here is fictional).\n", "text/plain")
            return self.not_found()

        def media(self, kind: str, rest: str):
            v = prov.video
            if not v or not v.ok:
                return self.not_found(b"no test video (ffmpeg is not available)")
            if kind == "live":
                sid = _int(rest.split(".")[0])
                if sid not in prov.cat.chan_by_id:
                    return self.not_found()
                if rest.endswith(".m3u8"):
                    body = v.live_playlist(time.time(), f"{self.base()}/hls/").encode()
                    return self.send(200, body, "application/vnd.apple.mpegurl", {"Cache-Control": "no-store"})
                return self.live_ts()
            if kind == "timeshift":
                return self.file(v.ts, "video/mp2t")
            return self.file(v.mp4, "video/mp4")

        def live_ts(self):
            """The clip looped in real time with continuous timestamps, until the client goes away."""
            v = prov.video
            proc = subprocess.Popen([v.ffmpeg, "-hide_banner", "-loglevel", "error", "-re", "-stream_loop", "-1",
                                     "-i", v.ts, "-c", "copy", "-f", "mpegts", "pipe:1"],
                                    stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, stdin=subprocess.DEVNULL)
            try:
                self.send_response(200)
                self.send_header("Content-Type", "video/mp2t")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Connection", "close")
                self.end_headers()
                self.close_connection = True
                if self.command == "HEAD":
                    return
                while True:
                    chunk = proc.stdout.read(188 * 64)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
            finally:
                proc.kill()
                proc.wait()
                proc.stdout.close()

        def file(self, path: str, ctype: str):
            path = path if os.path.isabs(path) else os.path.join(prov.video.dir, path)
            if not os.path.exists(path):
                return self.not_found()
            size = os.path.getsize(path)
            start, end, code = 0, size - 1, 200
            rng = re.fullmatch(r"bytes=(\d*)-(\d*)", (self.headers.get("Range") or "").strip())
            if rng and (rng.group(1) or rng.group(2)):
                if rng.group(1):
                    start = int(rng.group(1))
                    end = min(int(rng.group(2)), size - 1) if rng.group(2) else size - 1
                else:
                    start = max(0, size - int(rng.group(2)))
                if start > end or start >= size:
                    return self.send(416, b"", "text/plain", {"Content-Range": f"bytes */{size}"})
                code = 206
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Length", str(end - start + 1))
            if code == 206:
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            self.end_headers()
            if self.command == "HEAD":
                return
            with open(path, "rb") as f:
                f.seek(start)
                left = end - start + 1
                while left > 0:
                    chunk = f.read(min(left, 256 * 1024))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    left -= len(chunk)
    return H


def make_server(bind="127.0.0.1", port=8795, user="demo", password="demo", video=True) -> ThreadingHTTPServer:
    """The provider as a server object (not started). ``video``: True builds the test clip (needs ffmpeg),
    False skips it (streams answer 404), or a ready Video."""
    vid = video if isinstance(video, Video) else (Video() if video else None)
    if vid is not None and not vid.ok:
        vid.build()
    srv = ThreadingHTTPServer((bind, port), make_handler(Provider(user, password, vid)))
    srv.daemon_threads = True
    srv.video = vid
    return srv


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, default=8795)
    ap.add_argument("--bind", default="127.0.0.1", help="address to listen on (default loopback only)")
    ap.add_argument("--user", default="demo", help="the fictional provider username (default demo)")
    ap.add_argument("--password", default="demo", help="the fictional provider password (default demo)")
    ap.add_argument("--no-video", action="store_true", help="skip the ffmpeg test video (streams answer 404)")
    ap.add_argument("--video-dir", default=os.path.join(tempfile.gettempdir(), "orbit-demo-video"),
                    help="where the test clip is made and kept between runs (default %%TEMP%%/orbit-demo-video)")
    a = ap.parse_args()
    srv = make_server(a.bind, a.port, a.user, a.password, video=False if a.no_video else Video(a.video_dir))
    v = srv.video
    print(f"Orbit demo provider on http://{a.bind}:{a.port}  (Xtream login {a.user} / {a.password})", flush=True)
    if v is None:
        print("  video: off (--no-video); streams answer 404", flush=True)
    elif v.ok:
        print(f"  video: {Video.SECS}s test pattern in {v.dir}", flush=True)
    else:
        print(f"  video: unavailable ({v.error}); streams answer 404", flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        srv.server_close()


if __name__ == "__main__":
    main()
