"""Read-mostly Mihomo control-plane bridge for the NAS Monitor app.

Two independently running Mihomo instances are exposed:

* ``core`` - the primary instance, external-controller on 0.0.0.0:9090.
* ``v2``   - the secondary instance, external-controller on 127.0.0.1:9092,
             reached through the host-side socat forwarder on docker0:19092.

Secrets are read from the read-only mounted config files and never returned.
"""

import json
import mimetypes
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import deque
from threading import Lock

INSTANCES = {
    # key: (display name, api base url, config path, airport yaml file map)
    "core": {
        "name": "core",
        "label": "主代理",
        "api": os.environ.get("MIHOMO_CORE_API_URL", "http://host.docker.internal:9090"),
        "config": os.environ.get("MIHOMO_CORE_CONFIG", "/mihomo/config.yaml"),
        "airports": {
            "airport1": os.environ.get("MIHOMO_CORE_AIRPORT1", "/mihomo/airport1.yaml"),
            "airport2": os.environ.get("MIHOMO_CORE_AIRPORT2", "/mihomo/airport2.yaml"),
        },
        "panel": os.environ.get("MIHOMO_CORE_PANEL", "http://host.docker.internal:9093"),
    },
    "v2": {
        "name": "v2",
        "label": "备代理",
        "api": os.environ.get("MIHOMO_V2_API_URL", "http://172.17.0.1:19092"),
        "config": os.environ.get("MIHOMO_V2_CONFIG", "/mihomo-v2/config.yaml"),
        "airports": {
            "airport1": os.environ.get("MIHOMO_V2_AIRPORT1", "/mihomo-v2/airport1.yaml"),
            "airport2": os.environ.get("MIHOMO_V2_AIRPORT2", "/mihomo-v2/airport2.yaml"),
        },
        "panel": os.environ.get("MIHOMO_V2_PANEL", "http://host.docker.internal:9093"),
    },
}

AIRPORT_NAMES = {"airport1": "良心云", "airport2": "宝可梦"}
GROUP_TYPES = {"Selector", "URLTest", "Fallback", "LoadBalance", "Relay"}
HISTORY_POINTS = 120
SAMPLE_SECONDS = 5
AIRPORT_TTL = 60.0
_airport_cache = {}
_airport_lock = Lock()
_secret_cache = {}


class MihomoError(Exception):
    """Upstream failure with a message that is safe to show to the client."""


def _secret(instance_key):
    cfg = INSTANCES[instance_key]
    path = cfg["config"]
    try:
        mtime = os.path.getmtime(path)
    except OSError as exc:
        raise MihomoError(f"读取 {cfg['label']} 配置失败") from exc
    cached = _secret_cache.get(path)
    if cached and cached[0] == mtime:
        return cached[1]
    try:
        with open(path, encoding="utf-8", errors="ignore") as stream:
            raw = stream.read()
    except OSError as exc:
        raise MihomoError(f"读取 {cfg['label']} 配置失败") from exc
    match = re.search(r"^secret:\s*[\"']?([^\"'\s]+)", raw, re.M)
    value = match.group(1) if match else ""
    _secret_cache[path] = (mtime, value)
    return value


def _request(instance_key, method, path, body=None, timeout=8):
    cfg = INSTANCES[instance_key]
    headers = {}
    secret = _secret(instance_key)
    if secret:
        headers["Authorization"] = f"Bearer {secret}"
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(cfg["api"] + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
    except urllib.error.HTTPError as exc:
        raise MihomoError(f"{cfg['label']} 返回 HTTP {exc.code}") from exc
    except Exception as exc:
        raise MihomoError(f"{cfg['label']} 暂时不可达") from exc
    if not raw:
        return {}
    try:
        return json.loads(raw.decode())
    except ValueError as exc:
        raise MihomoError(f"{cfg['label']} 返回了无法解析的数据") from exc


def _reachable(instance_key):
    try:
        return _request(instance_key, "GET", "/version", timeout=4)
    except MihomoError:
        return None


def _groups(instance_key):
    payload = _request(instance_key, "GET", "/proxies")
    proxies = payload.get("proxies") or {}
    groups = []
    for name, item in proxies.items():
        if item.get("type") not in GROUP_TYPES:
            continue
        groups.append({
            "name": name,
            "type": item.get("type"),
            "now": item.get("now"),
            "all": item.get("all") or [],
            "hidden": bool(item.get("hidden")),
        })
    groups.sort(key=lambda g: (g["type"] != "Selector", g["name"]))
    return groups


def _connections(payload):
    return payload.get("connections") or []


def _provider_node_names(instance_key, provider):
    """Map every node name owned by a proxy provider.

    /connections reports concrete node names (e.g. "新加坡高速01") and policy
    group names, not the provider key. Matching only the provider key therefore
    always yields zero connections for airports routed through a group.
    """
    try:
        payload = _request(instance_key, "GET", f"/providers/proxies/{urllib.parse.quote(provider)}")
    except MihomoError:
        return set()
    names = set()
    proxies = payload.get("proxies") or {}
    if isinstance(proxies, dict):
        proxies = proxies.values()
    for item in proxies:
        if not isinstance(item, dict):
            continue
        name = item.get("name")
        owner = item.get("provider-name") or item.get("provider_name")
        if name and (not owner or owner == provider):
            names.add(name)
    return names


def airport_connection_attribution(instance_key):
    """Live connections grouped by airport provider for one instance.

    /connections reports concrete node names and policy group names, so the
    provider key alone never matches. Build a node -> provider map from each
    provider's proxy list, then attribute every connection to the first airport
    node on its chain.
    """
    cfg = INSTANCES[instance_key]
    conn_payload = _request(instance_key, "GET", "/connections")
    conns = _connections(conn_payload)
    node_owner = {}
    for provider in cfg["airports"]:
        for name in _provider_node_names(instance_key, provider):
            node_owner[name] = provider
    by_provider = {}
    for conn in conns:
        chains = conn.get("chains") or []
        owner = None
        for chain in chains:
            owner = node_owner.get(chain)
            if owner:
                break
            for provider in cfg["airports"]:
                if chain == provider or chain.startswith(provider + "-"):
                    owner = provider
                    break
            if owner:
                break
        if owner:
            bucket = by_provider.setdefault(owner, {"connections": 0, "upload": 0, "download": 0})
            bucket["connections"] += 1
            bucket["upload"] += int(conn.get("upload") or 0)
            bucket["download"] += int(conn.get("download") or 0)
    return by_provider


def _airport_traffic(instance_key, by_provider=None):
    """Subscription counters plus live connection attribution for one instance."""
    cfg = INSTANCES[instance_key]
    if by_provider is None:
        by_provider = airport_connection_attribution(instance_key)
    items = []
    for provider, file_path in cfg["airports"].items():
        info = {}
        try:
            provider_payload = _request(instance_key, "GET", f"/providers/proxies/{urllib.parse.quote(provider)}")
            info = provider_payload.get("subscriptionInfo") or provider_payload.get("subscription_info") or {}
        except MihomoError:
            info = {}
        total_bytes = float(info.get("Total") or 0)
        upload_bytes = float(info.get("Upload") or 0)
        download_bytes = float(info.get("Download") or 0)
        gib = 1024 ** 3
        used_gb = (upload_bytes + download_bytes) / gib
        total_gb = total_bytes / gib
        remaining_gb = max(0.0, total_gb - used_gb)
        reset_days = None
        expire = None
        try:
            with open(file_path, encoding="utf-8", errors="ignore") as stream:
                raw = stream.read()
            reset_m = re.search(r"重置剩余：(\d+)\s*天", raw)
            expire_m = re.search(r"套餐到期：([0-9]{4}-[0-9]{2}-[0-9]{2}|长期有效)", raw)
            reset_days = int(reset_m.group(1)) if reset_m else None
            expire = expire_m.group(1) if expire_m else None
        except OSError:
            pass
        level = "critical" if remaining_gb < 20 else "warning" if remaining_gb < 50 else "normal"
        bucket = by_provider.get(provider, {"connections": 0, "upload": 0, "download": 0})
        items.append({
            "id": provider,
            "name": AIRPORT_NAMES.get(provider, provider),
            "connections": bucket["connections"],
            "remaining_gb": round(remaining_gb, 2),
            "total_gb": round(total_gb, 2),
            "used_gb": round(used_gb, 2),
            "used_percent": round(used_gb / total_gb * 100, 1) if total_gb else 0.0,
            "reset_days": reset_days,
            "expire": expire,
            "level": level,
        })
    return items


def _airport_traffic_cached(instance_key):
    """Cache subscription counters only; connection attribution stays live.

    Provider subscription totals change slowly and are safe to cache for a
    minute. Connection counts change every few seconds, so they must not be
    frozen with the subscription snapshot.
    """
    now = time.time()
    with _airport_lock:
        cached = _airport_cache.get(instance_key)
        if cached and now - cached[0] < AIRPORT_TTL:
            snapshot = cached[1]
        else:
            snapshot = None
    if snapshot is None:
        snapshot = _airport_traffic(instance_key, by_provider={})
        with _airport_lock:
            _airport_cache[instance_key] = (now, snapshot)
    try:
        by_provider = airport_connection_attribution(instance_key)
    except MihomoError:
        by_provider = {}
    items = []
    for item in snapshot:
        bucket = by_provider.get(item["id"]) or {}
        items.append({**item, "connections": int(bucket.get("connections") or 0)})
    return items


class TrafficHistory:
    """In-memory ring buffer shared by the sampler and the HTTP handler."""

    def __init__(self):
        self._lock = Lock()
        self._data = {key: deque(maxlen=HISTORY_POINTS) for key in INSTANCES}

    def record(self, instance_key, upload_speed, download_speed, connections):
        with self._lock:
            self._data[instance_key].append({
                "ts": int(time.time()),
                "up": int(upload_speed),
                "down": int(download_speed),
                "connections": int(connections),
            })

    def snapshot(self, instance_key):
        with self._lock:
            return list(self._data[instance_key])


history = TrafficHistory()


def sample_speeds(instance_key):
    """Use Mihomo's own /traffic counters.

    /connections only reports live connections, so summing their byte counters
    drops to zero whenever a connection closes. /traffic is the core's global
    rate plus monotonic totals and is the correct source for both.
    """
    traffic = traffic_sample(instance_key)
    up_speed = int(traffic.get("up") or 0)
    down_speed = int(traffic.get("down") or 0)
    total_up = int(traffic.get("upTotal") or 0)
    total_down = int(traffic.get("downTotal") or 0)
    current = _request(instance_key, "GET", "/connections", timeout=6)
    connections = len(_connections(current))
    history.record(instance_key, up_speed, down_speed, connections)
    return {
        "upload_speed": up_speed,
        "download_speed": down_speed,
        "upload_total": total_up,
        "download_total": total_down,
        "connections": connections,
    }


def overview(instance_key):
    version = _request(instance_key, "GET", "/version")
    groups = _groups(instance_key)
    settings = _request(instance_key, "GET", "/configs")
    traffic = traffic_sample(instance_key)
    connections_payload = _request(instance_key, "GET", "/connections")
    conns = _connections(connections_payload)
    latest = history.snapshot(instance_key)[-1:] or [{"up": 0, "down": 0}]
    return {
        "id": instance_key,
        "name": INSTANCES[instance_key]["label"],
        "version": version.get("version") or "",
        "meta": bool(version.get("meta")),
        "mode": settings.get("mode") or "",
        "mixed_port": settings.get("mixed-port"),
        "connections": len(conns),
        "upload_total": int(traffic.get("upTotal") or 0),
        "download_total": int(traffic.get("downTotal") or 0),
        "upload_speed": int(traffic.get("up") or latest[-1].get("up", 0)),
        "download_speed": int(traffic.get("down") or latest[-1].get("down", 0)),
        "groups": groups,
        "panel": "/panel/index.html",
    }


def all_instances():
    result = []
    for key in INSTANCES:
        try:
            result.append(overview(key))
        except MihomoError as exc:
            result.append({
                "id": key,
                "name": INSTANCES[key]["label"],
                "available": False,
                "error": str(exc),
                "groups": [],
            })
    return result


def airports():
    result = []
    for key in INSTANCES:
        try:
            items = _airport_traffic_cached(key)
            points = history.snapshot(key)
            result.append({
                "id": key,
                "name": INSTANCES[key]["label"],
                "items": items,
                "history": points,
                "history_seconds": SAMPLE_SECONDS,
                "timestamp": int(time.time()),
            })
        except MihomoError as exc:
            result.append({
                "id": key,
                "name": INSTANCES[key]["label"],
                "items": [],
                "history": [],
                "error": str(exc),
            })
    return result


def select_group(instance_key, group_name, target):
    if instance_key not in INSTANCES:
        raise MihomoError("未知的代理实例")
    group_name = urllib.parse.unquote(group_name)
    target = str(target or "").strip()
    if not target:
        raise MihomoError("请选择要切换的节点")
    groups = {g["name"]: g for g in _groups(instance_key)}
    group = groups.get(group_name)
    if group is None:
        raise MihomoError("策略组不存在")
    if target not in group["all"]:
        raise MihomoError("该节点不属于此策略组")
    _request(instance_key, "PUT", f"/proxies/{urllib.parse.quote(group_name, safe='')}", {"name": target})
    now = None
    # Mihomo 的策略组状态有短暂更新延迟，回读最多重试 3 次再判定失败。
    for _ in range(3):
        refreshed = {g["name"]: g for g in _groups(instance_key)}
        now = refreshed.get(group_name, {}).get("now")
        if now == target:
            break
        time.sleep(0.4)
    if now != target:
        raise MihomoError(f"切换未生效，当前为 {now or '未知'}")
    return {"group": group_name, "now": now, "applied": True}


def test_node(instance_key, proxy_name):
    if instance_key not in INSTANCES:
        raise MihomoError("未知的代理实例")
    proxy_name = urllib.parse.unquote(proxy_name)
    payload = _request(
        instance_key, "GET",
        f"/proxies/{urllib.parse.quote(proxy_name, safe='')}/delay?timeout=5000&url="
        + urllib.parse.quote("http://www.gstatic.com/generate_204", safe=""),
        timeout=10,
    )
    return {"name": proxy_name, "delay": payload.get("delay")}


def connections(instance_key, limit=100):
    """Live connection list with only the fields the app renders."""
    payload = _request(instance_key, "GET", "/connections", timeout=8)
    rows = []
    for item in (payload.get("connections") or [])[:max(1, min(limit, 200))]:
        meta = item.get("metadata") or {}
        rows.append({
            "id": item.get("id") or "",
            "host": meta.get("host") or meta.get("destinationIP") or "",
            "destination": meta.get("destinationIP") or "",
            "port": meta.get("destinationPort") or "",
            "network": meta.get("network") or "",
            "process": meta.get("process") or "",
            "rule": item.get("rule") or "",
            "chains": item.get("chains") or [],
            "upload": int(item.get("upload") or 0),
            "download": int(item.get("download") or 0),
            "start": item.get("start") or "",
        })
    return {
        "connections": rows,
        "upload_total": int(payload.get("uploadTotal") or 0),
        "download_total": int(payload.get("downloadTotal") or 0),
    }


def close_connection(instance_key, connection_id):
    _request(instance_key, "DELETE", f"/connections/{urllib.parse.quote(connection_id, safe='')}")
    return {"closed": connection_id}


def close_all_connections(instance_key):
    _request(instance_key, "DELETE", "/connections")
    return {"closed": True}


def rules(instance_key):
    """Rule list. Mihomo returns an array; older builds used an index object."""
    payload = _request(instance_key, "GET", "/rules", timeout=8)
    raw = payload.get("rules") or {}
    rows = []
    items = list(raw.values()) if isinstance(raw, dict) else list(raw)
    for position, item in enumerate(items):
        rows.append({
            "index": item.get("index", position),
            "type": item.get("type") or "",
            "payload": item.get("payload") or "",
            "proxy": item.get("proxy") or "",
            "size": item.get("size"),
            "disabled": bool((item.get("extra") or {}).get("disabled")),
        })
    return {"rules": rows}


def set_rule_disabled(instance_key, rule_index, disabled):
    """Mihomo toggles rules with a {index: disabled} patch body."""
    _request(instance_key, "PATCH", "/rules/disable", {str(int(rule_index)): bool(disabled)})
    return {"index": int(rule_index), "disabled": bool(disabled)}


def configs(instance_key):
    payload = _request(instance_key, "GET", "/configs", timeout=8)
    keys = ("mode", "mixed-port", "socks-port", "port", "allow-lan", "log-level",
            "ipv6", "unified-delay", "tcp-concurrent")
    result = {key: payload.get(key) for key in keys if key in payload}
    result["mode"] = payload.get("mode") or ""
    return result


def set_config(instance_key, key, value):
    allowed = {"mode", "mixed-port", "allow-lan", "log-level", "ipv6"}
    if key not in allowed:
        raise MihomoError("该配置项不支持在 App 内修改")
    # secret 之外的配置变更不需要重载内核；但非法值仍应由 Mihomo 自己校验。
    if key == "mode" and value not in {"rule", "global", "direct"}:
        raise MihomoError("代理模式只能是 rule/global/direct")
    if key == "log-level" and value not in {"debug", "info", "warning", "error", "silent"}:
        raise MihomoError("日志级别无效")
    _request(instance_key, "PATCH", "/configs", {key: value})
    return {"key": key, "value": value}


def providers(instance_key):
    payload = _request(instance_key, "GET", "/providers/proxies", timeout=10)
    rows = []
    for name, item in (payload.get("providers") or {}).items():
        rows.append({
            "name": name,
            "vehicle_type": item.get("vehicleType") or "",
            "updated_at": item.get("updatedAt") or "",
            "node_count": len(item.get("proxies") or []),
            "has_subscription": bool(item.get("subscriptionInfo")),
        })
    return {"providers": rows}


def provider_action(instance_key, name, action):
    """Refresh or health-check one proxy provider."""
    quoted = urllib.parse.quote(name, safe="")
    if action == "update":
        _request(instance_key, "PUT", f"/providers/proxies/{quoted}", timeout=25)
        return {"provider": name, "updated": True}
    if action == "healthcheck":
        _request(instance_key, "GET", f"/providers/proxies/{quoted}/healthcheck", timeout=25)
        return {"provider": name, "checked": True}
    raise MihomoError("不支持的操作")


def panel_upstream():
    """Base URL of the MetaCubeXD web UI (always the primary instance's panel)."""
    return INSTANCES["core"]["panel"]


def panel_backend_url(host=None):
    """Same-origin base the browser uses for control calls.

    MetaCubeXD runs inside the WebView at /panel/; pointing it at Mihomo :9090
    would expose the secret to the browser and break on CORS. The backend
    proxy adds the secret server-side, so the panel only needs this origin.
    """
    configured = os.environ.get("MIHOMO_PANEL_BACKEND_URL", "").strip()
    if configured:
        return configured
    if host:
        # Host 头可能带端口（8765），必须原样保留，否则面板会探测 80 端口。
        return f"http://{host}"
    return ""


def panel_config_js(original, host=None):
    """Point MetaCubeXD at the LAN-visible Mihomo control API on first open."""
    backend = panel_backend_url(host)
    if not backend:
        return original
    replacement = "defaultBackendURL: " + json.dumps(backend)
    patched = re.sub(r"defaultBackendURL\s*:\s*(['\"])[^'\"]*\1", replacement, original, count=1)
    # 首屏内联的 __NUXT__ 配置优先级高于 config.js，必须一并改写。
    return re.sub(r'"defaultBackendURL"\s*:\s*["\']{2}', '"defaultBackendURL":"' + backend + '"',
                  patched)


def panel_bootstrap_html(html, host=None):
    """Patch the inline Nuxt config so the SPA opens with the right backend."""
    backend = panel_backend_url(host)
    if backend:
        if 'defaultBackendURL:""' in html:
            html = html.replace('defaultBackendURL:""', 'defaultBackendURL:' + json.dumps(backend), 1)
        html = re.sub(r'"defaultBackendURL"\s*:\s*""', '"defaultBackendURL":' + json.dumps(backend), html)
    # 旧 WebView 需要的 ES2022 API 垫片必须先于模块脚本执行。
    shim = '<script src="/polyfill.js"></script>'
    if shim not in html:
        html = html.replace('<head>', '<head>' + shim, 1)
    return html


def panel_instance(instance_key):
    """Validate a panel instance key; unknown values fall back to core."""
    return instance_key if instance_key in INSTANCES else "core"


_panel_cache = {}
_panel_cache_lock = Lock()
PANEL_CACHE_SECONDS = 3.0
PANEL_COMPAT_DIR = os.environ.get("MIHOMO_PANEL_COMPAT_DIR", "/app/panel_compat")


def panel_compat_file(path):
    """Return a pre-transpiled asset for old WebViews, or None.

    MetaCubeXD ships ES2021 syntax that Chrome 75 cannot parse; the compat
    mirror keeps upstream filenames so its relative imports still resolve.
    """
    if not path.startswith(("/_nuxt/", "/_fonts/")) and path not in ("/index.html", "/polyfill.js",
                                                                     "/favicon.svg",
                                                                     "/manifest.webmanifest",
                                                                     "/apple-touch-icon-180x180.png"):
        return None
    root = os.path.realpath(PANEL_COMPAT_DIR)
    target = os.path.realpath(os.path.join(root, path.lstrip("/")))
    if not target.startswith(root + os.sep) or not os.path.isfile(target):
        return None
    return target


def panel_fetch(path, method="GET", body=None, timeout=15):
    """Fetch a MetaCubeXD asset. Auth and path safety are the caller's job."""
    if method == "GET":
        compat = panel_compat_file(path)
        if compat:
            with open(compat, "rb") as stream:
                data = stream.read()
            ctype = mimetypes.guess_type(compat)[0] or "application/octet-stream"
            if compat.endswith(".js"):
                ctype = "text/javascript; charset=utf-8"
            return 200, {"Content-Type": ctype, "Content-Length": str(len(data))}, data
    req = urllib.request.Request(panel_upstream() + path, data=body, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, dict(resp.headers), resp.read()
    except urllib.error.HTTPError as exc:
        return exc.code, dict(exc.headers or {}), exc.read()
    except Exception as exc:
        raise MihomoError("管理面板暂时不可用") from exc


def panel_control(instance_key, method, path, body=None):
    """Proxy MetaCubeXD's control calls to Mihomo with the server-side secret.

    The panel polls /traffic and /connections every second; a short cache keeps
    two open panels from multiplying load on the core.
    """
    cache_key = (instance_key, method, path)
    if method == "GET":
        with _panel_cache_lock:
            cached = _panel_cache.get(cache_key)
            if cached and time.time() - cached[0] < PANEL_CACHE_SECONDS:
                return cached[1]
    result = _request(instance_key, method, path, body)
    if method == "GET":
        with _panel_cache_lock:
            _panel_cache[cache_key] = (time.time(), result)
    return result


def traffic_sample(instance_key):
    """Read one frame from Mihomo's streaming /traffic endpoint.

    /traffic never ends; a normal urlopen().read() blocks until timeout.
    The panel only needs the newest frame, so stop at the first newline.
    """
    cfg = INSTANCES[instance_key]
    headers = {}
    secret = _secret(instance_key)
    if secret:
        headers["Authorization"] = f"Bearer {secret}"
    req = urllib.request.Request(cfg["api"] + "/traffic", headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=6) as resp:
            line = resp.readline()
    except Exception as exc:
        raise MihomoError(f"{cfg['label']} 暂时不可达") from exc
    try:
        return json.loads(line.decode() or "{}")
    except ValueError as exc:
        raise MihomoError(f"{cfg['label']} 返回了无法解析的数据") from exc
