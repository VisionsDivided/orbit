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

## Rules

- LAN only: publish the port on the LAN address, never on the internet. Outside the house, use Tailscale.
- Without the key file the container refuses to start rather than making a new key (stored logins would be lost).
- Logs (`docker logs orbit`) never contain logins, stream addresses or query strings.
