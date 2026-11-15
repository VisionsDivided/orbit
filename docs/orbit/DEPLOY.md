# Deploying the Orbit home server (Docker Compose)

One container (`infra/docker/Dockerfile`): nginx serves the web app and proxies the API to the Python server, both
inside the container, running as uid 1000 with a read-only root file system. Data lives in one folder; the
encryption key lives in another.

## Folders (example layout; use your own)

| Host path | In the container | What |
|---|---|---|
| `<stack>/src` | build context | a checkout of the Orbit repository (`git clone <your repo> src`) |
| `<stack>/docker-compose.yml` | | copied from `infra/compose.example.yaml`, with your LAN address and paths |
| `<data>` | `/data` | accounts, profiles, playlists (encrypted), guide, recordings, image cache |
| `<secrets>/orbit.key` | `/run/secrets/orbit_key` | the encryption key for stored logins; **back it up separately** |

## First install

```sh
mkdir -p <data> <secrets>
chmod 700 <secrets>
( umask 077; python3 -c "import base64,os; print(base64.urlsafe_b64encode(os.urandom(32)).decode())" > <secrets>/orbit.key )
cd <stack>
ORBIT_VERSION=$(git -C src rev-parse --short HEAD) docker compose build
docker compose up -d
docker compose ps              # healthy after ~40 s
```

Open `http://<lan-address>:8750`, add the playlist under Settings, then **Settings > Accounts > Set up accounts**.
The one-time setup code is in `<data>/orbit-setup-token` (readable by the host user that owns the data folder).

`TVBRO_ALLOWED_HOSTS` must list every name or address devices use (the server refuses other Host headers):
for example the LAN address, the host name, and later a Tailscale name.

## Update

```sh
cd <stack>
tar -czf <backups>/orbit_$(date +%Y%m%d).tar.gz -C <data> .    # data backup first (orbit.db also keeps
                                                               # orbit.db.bak-v<n> before each migration)
git -C src pull
ORBIT_VERSION=$(git -C src rev-parse --short HEAD) docker compose build
docker compose up -d
```

Roll back: check out the previous commit in `src`, build and start again; if a migration ran, restore the data
backup (or `orbit.db.bak-v<n>`).

## Client addresses and a TLS reverse proxy in front

The server uses each client's address for the sign-in and pairing throttles, for the address shown before a TV is
approved ("Link a TV"), and in its logs. Inside the container the chain is already two hops:

```
device ──> nginx in the container (port 8080) ──> Python server (127.0.0.1:8750)
```

nginx passes `X-Forwarded-For` (what came in plus the address it saw). The Python server believes that header only
when the connection comes from a **trusted proxy**, and then takes the **right-most address that is not trusted**
(everything to the right of it was written by trusted proxies; anything to the left may be made up by the client).
From anyone else the header is ignored, so a device cannot pick its own address to dodge the throttles.

`ORBIT_TRUSTED_PROXIES` sets the trusted proxies: a comma-separated list of addresses or CIDR ranges. The default,
`127.0.0.1/32,::1/128`, trusts only the container's own nginx, which is all a LAN-only setup needs. Setting the
variable **replaces** the default, so always keep the loopback entries in it. An empty value trusts nobody.

With a TLS reverse proxy (nginx, Caddy, Traefik ...) in front of the container:

```
device ──> TLS proxy ──> nginx in the container ──> Python server
```

1. Have the proxy pass `Host` unchanged, add `X-Forwarded-For` and set `X-Forwarded-Proto https`. For nginx:
   ```nginx
   proxy_set_header Host $http_host;
   proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
   proxy_set_header X-Forwarded-Proto $scheme;
   ```
   (`proxy_buffering off` and long read timeouts, as in `infra/docker/nginx.conf`, keep streams flowing.)
2. Find the proxy's address **as the container's nginx sees it**: make one request through the proxy and read the
   first field of that line in `docker logs orbit` (for example `192.0.2.2`, or a Docker network gateway when the
   proxy runs on the same host).
3. Add it, keeping loopback, in the compose file:
   `ORBIT_TRUSTED_PROXIES: "127.0.0.1/32,::1/128,192.0.2.2/32"`.
4. Add the proxy's public name to `TVBRO_ALLOWED_HOSTS`.

If the proxy's address is not listed, everything still works, but every client looks like the proxy: one shared
throttle for all of them, and the proxy's address on the "Link a TV" screen. Only list proxies you run: a trusted
address can claim to forward for anyone. Session cookies get `Secure` when a trusted proxy says the page was https.

## Films and shows away from home

At home a film or episode is sent as it is, often 8 to 20 Mbit/s. Over Tailscale or the internet that is usually
more than the link carries, and the player waits a long time before the picture starts. So a device that is away
gets a transcode instead: 720p at 4 Mbit/s by default (`ORBIT_AWAY_QUALITY`: `8` for 1080p at 8 Mbit/s, `4`, `2`
for 480p at 2 Mbit/s, or `orig` for no limit). The web app's Settings ▸ This browser ▸ Films and shows quality
overrides it per browser. Plex and Jellyfin do the transcode themselves; IPTV films and episodes are converted by
this server's ffmpeg (about one CPU core per film at 720p).

"Away" means a public address or a Tailscale address (100.64.0.0/10, fd7a:115c:a1e0::/48). If your VPN arrives
through a router that does NAT (a Tailscale subnet router on the router), remote devices look like the router's LAN
address: add it to `ORBIT_AWAY_NETS` (comma-separated addresses or CIDR ranges), for example
`ORBIT_AWAY_NETS: "192.0.2.1"`. Devices at home must not share that address.

## Hidden provider logins: TV video flows through the server

By default linked TVs never receive the IPTV provider's login (API-v1.md, "Hidden provider logins"). They get
facade addresses on this server (`http://<lan-address>:8750/xt/<playlist>/...`) and a login of their own, and the
server relays everything from the provider: catalogue, guide and **every byte of video**.

- **Bandwidth.** Each stream a TV watches now crosses the server's network twice (provider to server, server to
  TV). An HD channel is roughly 3 to 8 Mbit/s, 4K 15 to 25 Mbit/s; three TVs at once need about three times that in
  each direction on the server's link, plus the provider leg on the internet connection, as before. A VM with a
  virtual NIC on a busy or 100 Mbit/s host link, or a server on Wi-Fi, is where this shows first.
- **CPU and memory.** A relay is a copy loop in its own thread, 64 KiB at a time: a few percent of one core per
  stream on a small VM, no transcoding. Catalogue answers are read whole before they are passed on (a large VOD list
  can be tens of MB in memory for a moment). Each TV fetches one catalogue call at a time, at most 2 are fetched at
  once server-wide (others wait), and a TV's scrubbed answer is reused for 60 s (at most 256 MB cached in all).
- **Connections.** The provider sees the server's address for every stream, not the TV's. The provider's connection
  limit is unchanged (one connection per stream either way). The server handles up to 256 connections at once.
- **Names.** TVs use the same address for the facade as for the API, so whatever they link with must be in
  `TVBRO_ALLOWED_HOSTS` (it already is when linking works). The TV app allows plain http to the server.
- **Switching off.** Settings > Security > "Provider login on linked TVs" > "Sent to TVs", or
  `ORBIT_HIDE_PROVIDER_LOGIN: "0"` in the compose file (which then overrides the setting; `"1"` pins it on). TVs
  switch at their next playlist sync (within 30 minutes, or when the app starts). Unlinking a TV, signing it out from
  another device or disabling its account ends its facade login at once, including a stream it is playing.
- **Keys.** The TVs' facade logins and the sealed stream addresses use keys derived from `orbit.key` (outside the
  data folder), not from `sign.key` in it, so a copy of `/data` alone gives no facade login. The first start of a
  server with this change writes `keys-derived.json` and, for one hour after that only, still accepts the old
  logins and sealed addresses, for TVs that have not synced yet; each TV switches at its next playlist sync (when
  the app starts and every 30 minutes), after which its old login no longer works. A TV that was off for longer
  than that hour gets the new login as soon as it starts. Rotating `orbit.key` changes the facade logins too: TVs
  pick up the new ones at their next playlist sync (restart the TV app to make it immediate).
- **A provider on the LAN** (e.g. a tvheadend box) needs "Playlists and streams on your local network" allowed, as
  for browsers: the relay follows the same address rules (`net.py`).
- The container's nginx proxies `/xt/` without buffering (`infra/docker/nginx.conf`); a TLS proxy in front needs
  `proxy_buffering off` and long timeouts too (see above). Logs show facade addresses as `/xt/<id>/<kind>/-/-/…` and Plex / Jellyfin play addresses as `/v1/media/stream/-/…` (the play-session id is the capability, so it is left out).

## Rules

- LAN only: publish the port on the LAN address, never on the internet. Outside the house, use Tailscale.
- Without the key file the container refuses to start rather than making a new key (stored logins would be lost).
- Logs (`docker logs orbit`) never contain logins, stream addresses or query strings.
