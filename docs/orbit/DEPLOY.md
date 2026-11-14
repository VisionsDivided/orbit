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

## Rules

- LAN only: publish the port on the LAN address, never on the internet. Outside the house, use Tailscale.
- Without the key file the container refuses to start rather than making a new key (stored logins would be lost).
- Logs (`docker logs orbit`) never contain logins, stream addresses or query strings.
