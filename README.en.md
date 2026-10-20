# NAS Monitor — Source Release (v238)

[中文说明](README.md) · English

This repository contains the real backend, Web frontend, and Android app source of a self-hosted NAS monitoring dashboard, plus build configuration. It is meant to be handed to an AI assistant that adapts it to your own environment: start with the [AI Onboarding Guide](docs/AI接手指南.md) (docs are written in Chinese), survey the services you already run, decide which ones to integrate, and only then configure.

See [CHANGELOG.md](CHANGELOG.md) for release changes (written in Chinese).

GitHub workflows are **manual-trigger only**; pushing source will not build an APK or publish an image. For a real release, configure your own signing secrets and run the workflow yourself.

> **A quick note**: this started as a small tool the author built for personal use. Both the code and the deployment were done entirely with AI, and every design decision targets the author's own NAS setup rather than general-purpose use. It is not meant to be a universal product, so it may not fit every scenario. If you happen to have a similar need, feel free to reference it and adapt it as you like; if your setup differs, that is completely understandable — hopefully it is still of some help. 🙂

## Screenshots

| Servers | Gateway overview | Gateway logs |
| --- | --- | --- |
| ![Servers](docs/screenshots/01-服务器总览.jpg) | ![Gateway overview](docs/screenshots/02-网关概览.jpg) | ![Gateway logs](docs/screenshots/03-网关日志.jpg) |

| Log detail | Usage report | Tools |
| --- | --- | --- |
| ![Log detail](docs/screenshots/04-日志详情.jpg) | ![Usage report](docs/screenshots/05-使用日报.jpg) | ![Tools](docs/screenshots/06-工具.jpg) |

| NAS detail | | |
| --- | --- | --- |
| ![NAS detail](docs/screenshots/07-NAS详情.jpg) | | |

> Screenshots show the author's own demo environment with device metadata removed. Host addresses, channel names, and figures describe that demo only.

## New Features

| Downloaders | qBittorrent tasks |
| --- | --- |
| ![Downloaders](docs/screenshots/08-下载器概览.jpg) | ![qBittorrent tasks](docs/screenshots/09-qBittorrent任务.jpg) |

| Airport dashboard | Proxy overview | Proxy settings |
| --- | --- | --- |
| ![Airport dashboard](docs/screenshots/10-机场看板.jpg) | ![Proxy overview](docs/screenshots/11-代理概览.jpg) | ![Proxy settings](docs/screenshots/12-代理设置.jpg) |

- **Downloaders**: inspect qBittorrent and Transmission speeds, task status, cumulative traffic, error counts, and disk space, then open an individual downloader's task list.
- **Airport dashboard**: compare primary and standby proxy instances, connection counts, subscription balances, usage ratios, and live traffic trends.
- **Mihomo proxy**: view instance version, proxy mode, connection and traffic state, and manage subscription groups with update and speed-test actions.

### Usage page

| Android usage (30 days) | Android usage trend (30 days) |
| --- | --- |
| ![Android usage](docs/screenshots/13-用量页-Android-30天.png) | ![Android usage trend](docs/screenshots/14-用量趋势-Android-30天.png) |

| Web usage (desktop, 30 days) | Web usage (mobile, 30 days) |
| --- | --- |
| ![Web desktop usage](docs/screenshots/15-用量页-Web桌面-30天.png) | ![Web mobile usage](docs/screenshots/16-用量页-Web移动端-30天.png) |

- **Usage**: the former Models tab is now Usage, with 1-day, 7-day, and 30-day windows showing models that received calls, request counts, and tokens.
- **Distribution and trend**: the ring-shaped model distribution and request ranking follow sub2api's information structure. The chart uses Fritsch-Carlson monotone cubic interpolation, two series with 8% area fills for requests and tokens, and edge-aligned first and last points.
- **Read-only and resilient**: the backend aggregates through read-only SQLite queries and maintains a 300-second snapshot with single-flight refresh and a persisted stale fallback, so a database interruption does not blank the page.
- **No cost display**: the Web and Android usage pages remove fees, monetary amounts, quota, and dollar metrics entirely, showing only requests and tokens. Screenshots preserve the user-provided framing and dimensions and only have image metadata removed; model names, request counts, and token totals are accepted for this release.

## Repository layout

- `backend/api_server.py` — Python 3.11 backend entry point.
- `backend/web/` — the actual single-page Web UI, served same-origin by the backend; no Node build step.
- `backend/collectors/` — upstream adapters and optional collection jobs.
- `HADashboard/` — Android v238/238.0, package `io.github.nasmonitor.app`, JDK 17 / SDK 34 / Gradle 8.7.
- `docs/需求规格.md` and `docs/assets/` — functional spec and architecture diagrams. Pseudocode in the spec is not an entry point.
- [Configuration & Credentials](docs/配置与凭据获取说明.md) — what each setting does, how to obtain it, and where it may be stored.
- [Environment Survey Sheet](docs/环境勘察表.example.md) — a template for recording your own topology.

## Minimal deployment: backend + Web + existing Glances v4

First verify Docker, ports, volume space, and which host actually runs the Glances container/process and how it is reached. Deploy into a directory the user has approved. The base template does not mount the Docker socket and leaves optional schedulers off.

```bash
cp backend/.env.example .env
chmod 600 .env
```

Generate a random token of at least 24 bytes locally (for example `openssl rand -hex 24`) and put it in `.env`. Do not paste generated values into a chat or commit them. At minimum:

```dotenv
APP_API_TOKEN=CHANGE_ME_RANDOM_TOKEN
WEB_API_TOKEN=CHANGE_ME_ANOTHER_RANDOM_TOKEN
NAS_HOST=CHANGE_ME_HOST
NAS_GLANCES=http://<your confirmed metrics source>:<actual port>
NAS_MONITOR_SCHEDULER=0
```

These are placeholders and must be replaced. The default binds to host loopback. To reach it from another machine, set `MONITOR_BIND_ADDRESS=<host LAN or VPN address>` only after the user has explicitly chosen LAN/VPN; keep `127.0.0.1` when reverse-proxying on the same host. Use TLS or a VPN over the public internet; never expose Glances or the management API directly.

```bash
docker compose -f docker-compose.example.yml config --quiet
docker compose -f docker-compose.example.yml up -d --build
docker compose -f docker-compose.example.yml exec -T nas-monitor-api python3 -c "import urllib.request; print(urllib.request.urlopen('http://127.0.0.1:8766/health').status)"
```

Open the entry point you chose, sign in with `WEB_API_TOKEN` (`APP_API_TOKEN` also works), and confirm the overview shows CPU, memory, disk, and network metrics coming from real Glances. Static pages load anonymously; data endpoints require a Bearer token. The business port is 8765; the unauthenticated health probe is reachable only on container loopback 8766.

If Glances runs in another container, `127.0.0.1` inside the monitor container refers to the monitor itself — use the verified host LAN address or a shared Docker network service name instead. If no collector exists, the user decides whether to create one; never fabricate metrics.

## Optional Docker capability

Enable only when container list/logs/control is needed **and** the user has authorized it:

```bash
stat -c '%g' /var/run/docker.sock
docker compose -f docker-compose.example.yml -f docker-compose.docker.yml config --quiet
docker compose -f docker-compose.example.yml -f docker-compose.docker.yml up -d --build
```

Put the number from the first command into `DOCKER_GID` in `.env`. Socket access can control the host, and `:ro` does not restrict write operations on the Docker API. For a non-standard socket path, update the host mount source in the override file too.

HA, iKuai/OpenWrt, New API, WorkBuddy, the knowledge base, and upstream usage are all optional; pick them from the configuration guide. Do not install or log in to a service just because a module name mentions it. Dynamic remote hosts can be added from the management page and do not need vendor-specific names.

## Verifying the Android source

Set your own Android SDK path locally (the author's `local.properties`, signing keys, and caches are not included):

```bash
cd HADashboard
bash ./gradlew --no-daemon testDebugUnitTest
```

On first launch the user configures their own backend URL and token; SSH host, username, password, and key are also configured locally by the user. Build your own signing keystore if you need a production APK — no APK ships in this package. Without a keystore the release build falls back to debug signing, which is for development only.

## Privacy boundary and tests

This package excludes production `.env` files, credential files, runtime data, Git history, backups, logs, APKs, signing keystores, and build caches. Account nicknames, user IDs, personal URLs, and log samples have been removed or turned into user configuration; framework/SDK dependency domains and RFC-reserved example domains are retained.

Backend verification runs in an isolated environment against fake upstreams: `python3 -m unittest discover -s backend`. Do not load the author's or any other project's `.env`, and do not write test data into production volumes. See `docs/源码交付审查报告.md` for the full review scope and verification results.

## License

MIT — see [LICENSE](LICENSE).
