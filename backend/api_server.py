#!/usr/bin/env python3
"""
NAS Monitor API - 聚合 NAS 和阿里云的监控数据
跑在 NAS 上，供 App 调用
"""

import io
import json
import os
import re
import hashlib
import hmac
import select
import signal
import http.client
import socket
import ssl
import secrets
import subprocess
import tempfile
import time
import urllib.request
import urllib.parse
import mimetypes
import uuid
import fcntl
import gateway_api
import mihomo_api
from airport_daily import SHANGHAI, update_usage as update_airport_daily_usage
from collections import deque
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime
from pathlib import Path
from http.server import HTTPServer, BaseHTTPRequestHandler, ThreadingHTTPServer
from socketserver import ThreadingMixIn
from threading import BoundedSemaphore, Event, Lock, RLock, Thread
from backend_scheduler import health_state, start_scheduler
from collectors import speedtest as speedtest_collector
from collectors import workbuddy_status as workbuddy_collector
from collectors import workbuddy_activity

# 趋势数据：最多保留 720 点（1h@5s 或 2h@10s）
TREND_MAX = 17280  # 24h @ 5s
_trends = {"nas": deque(maxlen=TREND_MAX), "aliyun": deque(maxlen=TREND_MAX), "tencent": deque(maxlen=TREND_MAX)}
# 温度趋势按传感器独立保存，和资源趋势共享同一把锁保证快照一致。
TREND_TEMP_MAX = 17280
_trends_temp = {"nas": {}, "aliyun": {}, "tencent": {}}
_trend_lock = Lock()
# 概览缓存：single-flight stale-while-revalidate。
# TTL 用 time.monotonic() 判断，展示时间用墙钟；后台刷新失败保留旧数据并按退避重试。
_overview_lock = Lock()
_overview_built = Event()
_overview_cache = {
    "data": None,
    "fetched_mono": 0.0,
    "refreshing": False,
    "refresh_started": 0.0,
    "failures": 0,
    "last_error": None,
    "next_retry_mono": 0.0,
    "generation": 0,
}
_OVERVIEW_TTL = 10.0
_REFRESH_STUCK = 60.0
_BACKOFF = [5, 10, 30, 60]
# log-tokens 快照：进程内存 + 落盘持久化，接口只读快照。
DATA_DIR = Path(__file__).resolve().parent / "data"
LOG_TOKENS_SNAPSHOT_FILE = DATA_DIR / "log_tokens_snapshot.json"
_log_tokens_lock = Lock()
_log_tokens_built = Event()
_log_tokens_snap = {
    "data": None,
    "fetched_mono": 0.0,
    "refreshing": False,
    "refresh_started": 0.0,
    "failures": 0,
    "last_error": None,
    "next_retry_mono": 0.0,
    "generation": 0,
}
_LOG_TOKENS_INTERVAL = 60.0
_LOG_TOKENS_MAX_STALE = 300.0
# 全局停止事件：SIGTERM 后置位，后台线程用 stop_event.wait() 替代 sleep 以便快速退出。
stop_event = Event()
_nas_cache = {'ts': 0.0, 'data': None}
_NAS_TTL = 10.0

# 可管理的远程 Glances 主机。NAS 本机保持内置，云主机从此注册表读取。
SERVER_REGISTRY_FILE = Path(__file__).resolve().parent / "data" / "server_registry.json"
_server_registry_lock = RLock()


def _default_server_registry():
    """首次启动（无 data/server_registry.json）时的初始远程主机。

    只在对应主机环境变量已配置时才生成条目：开源用户默认没有任何远程主机，
    概览里不会出现连不上的幽灵卡片；个人部署配置 ALIYUN_HOST 等即自动出现。
    """
    servers = {}
    if ALIYUN_HOST:
        servers["aliyun"] = {
            "id": "aliyun", "name": os.environ.get("ALIYUN_NAME", "阿里云 ECS"),
            "glances_url": ALIYUN_GLANCES,
            "mesh_ip": ALIYUN_HOST, "public_ip": ALIYUN_PUBLIC_IP, "deletable": True,
            "security_url": os.environ.get("ALIYUN_SECURITY_URL", f"http://{ALIYUN_HOST}:8767"),
            "security_token": os.environ.get("ALIYUN_SECURITY_TOKEN", ""),
        }
    if TENCENT_HOST:
        servers["tencent"] = {
            "id": "tencent", "name": os.environ.get("TENCENT_NAME", "腾讯云服务器"),
            "glances_url": TENCENT_GLANCES,
            "mesh_ip": TENCENT_HOST, "public_ip": TENCENT_PUBLIC_IP, "deletable": True,
            "security_url": os.environ.get("TENCENT_SECURITY_URL", f"http://{TENCENT_HOST}:8767"),
            "security_token": os.environ.get("TENCENT_SECURITY_TOKEN", ""),
        }
    return servers


def _load_server_registry():
    with _server_registry_lock:
        try:
            raw = json.loads(SERVER_REGISTRY_FILE.read_text(encoding="utf-8"))
            servers = raw.get("servers") if isinstance(raw, dict) else None
            if isinstance(servers, dict):
                return {str(k): dict(v) for k, v in servers.items() if isinstance(v, dict)}
            raise ValueError("服务器注册表格式错误")
        except FileNotFoundError:
            servers = _default_server_registry()
            _save_server_registry_locked(servers)
            return servers


def _save_server_registry_locked(servers):
    SERVER_REGISTRY_FILE.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_name = tempfile.mkstemp(prefix=".server_registry.", suffix=".tmp", dir=str(SERVER_REGISTRY_FILE.parent))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump({"version": 1, "servers": servers}, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(tmp_name, SERVER_REGISTRY_FILE)
    except Exception:
        try:
            os.unlink(tmp_name)
        except OSError:
            pass
        raise


def _save_server_registry(servers):
    with _server_registry_lock:
        _save_server_registry_locked(servers)


def _remote_glances_map():
    return {server_id: str(item.get("glances_url"))
            for server_id, item in _load_server_registry().items()
            if item.get("glances_url")}


def _server_is_valid_id(server_id):
    return bool(re.fullmatch(r"[a-z0-9][a-z0-9_-]{1,31}", server_id or ""))


def server_entries():
    return [{"id": "nas", "name": "NAS", "deletable": False, "mesh_ip": NAS_MESH_IP or NAS_HOST},
            *[{key: value for key, value in entry.items() if not key.startswith("security_")}
              for entry in _load_server_registry().values()]]


def validate_server(body):
    if not isinstance(body, dict):
        raise ValueError("请求格式错误")
    name, url = body.get("name"), body.get("glances_url")
    if not isinstance(name, str) or not 1 <= len(name.strip()) <= 40:
        raise ValueError("服务器名称须为 1–40 个字符")
    if not isinstance(url, str) or len(url) > 512:
        raise ValueError("请输入 Glances 地址")
    parsed = urllib.parse.urlsplit(url.strip())
    if (parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username
            or parsed.password or parsed.query or parsed.fragment or any(c.isspace() for c in url)):
        raise ValueError("地址须为 http(s)://主机:端口，不支持内嵌凭据、参数或片段")
    port = parsed.port
    host = parsed.hostname.lower()
    authority = f"[{host}]" if ":" in host else host
    if port and port != (443 if parsed.scheme == "https" else 80):
        authority += f":{port}"
    url = urllib.parse.urlunsplit((parsed.scheme, authority, parsed.path.rstrip("/"), "", ""))
    return {"name": name.strip(), "glances_url": url, "mesh_ip": host, "deletable": True}


def check_server(config):
    try:
        with urllib.request.urlopen(config["glances_url"] + "/api/4/cpu", timeout=5) as response:
            cpu = json.loads(response.read(65537))
        value = cpu.get("total") if isinstance(cpu, dict) else None
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not 0 <= value <= 100:
            raise ValueError()
    except Exception:
        raise ValueError("无法连接 Glances v4，请检查地址、端口与网络可达性") from None


def add_server(body):
    config = validate_server(body)
    check_server(config)
    with _server_registry_lock:
        servers = _load_server_registry()
        if len(servers) >= 20:
            raise ValueError("最多添加 20 台远程服务器")
        if config["glances_url"] in {NAS_GLANCES.rstrip("/"), *(v["glances_url"] for v in servers.values())}:
            raise ValueError("该监控地址已存在")
        config["id"] = "srv_" + uuid.uuid4().hex[:16]
        servers[config["id"]] = config
        _save_server_registry_locked(servers)
        _clear_server_runtime(config["id"])
    return config


def delete_server(server_id):
    if server_id == "nas":
        raise ValueError("NAS 是本机监控入口，不可删除")
    with _server_registry_lock:
        servers = _load_server_registry()
        if server_id not in servers:
            raise KeyError(server_id)
        del servers[server_id]
        _save_server_registry_locked(servers)
        _clear_server_runtime(server_id)
        _nas_cache.update(ts=0.0, data=None)


def _clear_server_runtime(server_id):
    with _trend_lock:
        _trends.pop(server_id, None)
        _trends_temp.pop(server_id, None)
    first_seen = getattr(build_alerts, "_first_seen", {})
    for key in list(first_seen):
        if key[0] == server_id:
            del first_seen[key]
    with _overview_lock:
        _overview_cache["data"] = None
        _overview_cache["fetched_mono"] = 0.0
        _overview_cache["generation"] += 1

def record_trend(host, cpu, memory, disk, rx_bps, tx_bps):
    """写入趋势快照"""
    with _server_registry_lock, _trend_lock:
        if host != "nas" and host not in _load_server_registry():
            return
        _trends.setdefault(host, deque(maxlen=TREND_MAX)).append({
            "t": datetime.now().astimezone().isoformat(),
            "cpu": round(cpu, 1),
            "mem": round(memory, 1),
            "disk": round(disk, 1),
            "rx": round(rx_bps, 1),
            "tx": round(tx_bps, 1),
        })

def _backoff_delay(failures):
    return _BACKOFF[min(max(failures - 1, 0), len(_BACKOFF) - 1)]


def _overview_set_state(**updates):
    with _overview_lock:
        _overview_cache.update(updates)


def _overview_start_build(generation):
    """后台构建线程入口：不持锁执行采集，结束时无论成败都复位 refreshing。"""
    try:
        data = _build_overview()
    except Exception as e:
        with _overview_lock:
            _overview_cache["failures"] += 1
            _overview_cache["last_error"] = str(e)[:200]
            _overview_cache["next_retry_mono"] = time.monotonic() + _backoff_delay(_overview_cache["failures"])
            _overview_cache["refreshing"] = False
        _overview_built.set()
        print(f"overview refresh failed: {e}")
        return
    with _overview_lock:
        if generation == _overview_cache["generation"]:
            _overview_cache["data"] = data
            _overview_cache["fetched_mono"] = time.monotonic()
            _overview_cache["failures"] = 0
            _overview_cache["last_error"] = None
        _overview_cache["refreshing"] = False
    _overview_built.set()


def _overview_request_refresh():
    """若当前无刷新在进行且已过退避期，则标记并启动后台构建。返回是否新触发。"""
    now = time.monotonic()
    with _overview_lock:
        stuck = _overview_cache["refreshing"] and now - _overview_cache["refresh_started"] > _REFRESH_STUCK
        if _overview_cache["refreshing"] and not stuck:
            return False
        if now < _overview_cache["next_retry_mono"]:
            return False
        _overview_cache["refreshing"] = True
        _overview_cache["refresh_started"] = now
        _overview_cache["generation"] += 1
        generation = _overview_cache["generation"]
    try:
        Thread(target=_overview_start_build, args=(generation,), daemon=True).start()
    except Exception:
        _overview_set_state(refreshing=False)
        return False
    return True


def _log_tokens_snapshot_path_ok():
    return DATA_DIR.is_dir() and os.access(DATA_DIR, os.W_OK)


def _persist_log_tokens_snapshot(payload):
    """同目录临时文件 + os.replace 原子落盘；写盘失败不影响内存快照。"""
    try:
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        fd, tmp_name = tempfile.mkstemp(dir=str(DATA_DIR), prefix=".log_tokens_", suffix=".tmp")
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp_name, LOG_TOKENS_SNAPSHOT_FILE)
        os.chmod(LOG_TOKENS_SNAPSHOT_FILE, 0o600)
    except Exception as e:
        print(f"log_tokens snapshot persist failed: {e}")


def _load_log_tokens_snapshot():
    """启动时加载落盘快照；损坏或非 dict 一律忽略，按无快照处理。"""
    try:
        raw = json.loads(LOG_TOKENS_SNAPSHOT_FILE.read_text(encoding="utf-8"))
        if isinstance(raw, dict) and isinstance(raw.get("data"), dict):
            with _log_tokens_lock:
                _log_tokens_snap["data"] = raw["data"]
                _log_tokens_snap["fetched_mono"] = time.monotonic()
            _log_tokens_built.set()
            print("log_tokens snapshot loaded from disk")
    except FileNotFoundError:
        pass
    except Exception as e:
        print(f"log_tokens snapshot load ignored: {e}")


def _log_tokens_start_build(generation):
    try:
        data = gateway_api.log_tokens()
    except Exception as e:
        with _log_tokens_lock:
            _log_tokens_snap["failures"] += 1
            _log_tokens_snap["last_error"] = str(e)[:200]
            _log_tokens_snap["next_retry_mono"] = time.monotonic() + _backoff_delay(_log_tokens_snap["failures"])
            _log_tokens_snap["refreshing"] = False
        _log_tokens_built.set()
        print(f"log_tokens refresh failed: {e}")
        return
    snapshot_at = datetime.now().isoformat()
    with _log_tokens_lock:
        if generation == _log_tokens_snap["generation"]:
            _log_tokens_snap["data"] = data
            _log_tokens_snap["fetched_mono"] = time.monotonic()
            _log_tokens_snap["failures"] = 0
            _log_tokens_snap["last_error"] = None
            payload = {"snapshot_at": snapshot_at, "data": data}
        else:
            payload = None
        _log_tokens_snap["refreshing"] = False
    _log_tokens_built.set()
    if payload:
        _persist_log_tokens_snapshot(payload)


def _log_tokens_request_refresh():
    now = time.monotonic()
    with _log_tokens_lock:
        stuck = _log_tokens_snap["refreshing"] and now - _log_tokens_snap["refresh_started"] > _REFRESH_STUCK
        if _log_tokens_snap["refreshing"] and not stuck:
            return False
        if now < _log_tokens_snap["next_retry_mono"]:
            return False
        _log_tokens_snap["refreshing"] = True
        _log_tokens_snap["refresh_started"] = now
        _log_tokens_snap["generation"] += 1
        generation = _log_tokens_snap["generation"]
    try:
        Thread(target=_log_tokens_start_build, args=(generation,), daemon=True).start()
    except Exception:
        with _log_tokens_lock:
            _log_tokens_snap["refreshing"] = False
        return False
    return True


def _log_tokens_refresher():
    """后台周期刷新 log-tokens 快照；stop_event.wait 便于快速关停。"""
    stop_event.wait(5)
    while not stop_event.is_set():
        _log_tokens_request_refresh()
        stop_event.wait(_LOG_TOKENS_INTERVAL)


def _overview_warmer():
    """启动预热：延迟 2s 错开趋势采样器，与冷请求共用 single-flight 入口天然去重。"""
    stop_event.wait(2)
    if not stop_event.is_set():
        _overview_request_refresh()


def get_trends(host, minutes=60):
    """返回最近 N 分钟的趋势数据"""
    if host not in _trends:
        return []
    with _trend_lock:
        cutoff = time.time() - minutes * 60
        raw = list(_trends.get(host, []))
        return [p for p in raw if datetime.fromisoformat(p["t"]).timestamp() >= cutoff]


def extract_temperatures(payload):
    """兼容 Glances sensors 的多种返回结构，提取有效温度读数。"""
    temperatures = {}

    def visit(item, fallback_name=""):
        if isinstance(item, list):
            for child in item:
                visit(child, fallback_name)
            return
        if not isinstance(item, dict):
            return
        name = str(item.get("label") or item.get("name") or item.get("key") or fallback_name)
        value = item.get("value", item.get("temperature", item.get("temp")))
        if value is not None:
            try:
                numeric = float(value)
                if -20 <= numeric <= 150 and name:
                    temperatures[name] = round(numeric, 1)
            except (TypeError, ValueError):
                pass
        for key, child in item.items():
            if key not in ("value", "temperature", "temp"):
                visit(child, str(key))

    visit(payload)
    return temperatures


def get_glances_temperatures(glances_url):
    """从 Glances v4 sensors 读取温度；失败时返回空字典。"""
    try:
        payload = fetch_json(f"{glances_url}/api/4/sensors")
        return extract_temperatures(payload)
    except Exception:
        return {}


def hardware_attributes(entity):
    attributes = entity.get("attributes") or {}
    if entity.get("state") not in ("ok", "partial"):
        return {}
    try:
        age = time.time() - float(attributes.get("updated_at", 0))
    except (ValueError, TypeError):
        return {}
    return attributes if 0 <= age <= 120 else {}


def merge_nas_hardware(sensors, entity):
    attributes = hardware_attributes(entity)
    if not attributes:
        return
    sensors["hardware_source"] = attributes.get("source")
    sensors["hdd_temps"] = attributes.get("disks", {})
    sensors["disk_details"] = attributes.get("disk_details", {})
    sensors["temperatures"] = attributes.get("temperatures", {})
    sensors["voltages"] = attributes.get("voltages", {})
    fans = attributes.get("fans") or {}
    if fans:
        sensors["fans"] = {f"fan{index}": {"label": label, "rpm": reading.get("value"),
                           "mode": "bmc", "pwm": None, "status": reading.get("status")}
                           for index, (label, reading) in enumerate(fans.items(), 1)}


def get_nas_hardware_temperatures():
    if not HA_TOKEN:
        return {}
    try:
        request = urllib.request.Request(HA_URL + "/sensor.nas_hardware",
                                         headers={"Authorization": "Bearer " + HA_TOKEN})
        with urllib.request.urlopen(request, timeout=5) as response:
            entity = json.load(response)
        return hardware_attributes(entity).get("temperatures", {})
    except Exception:
        return {}


def merge_temperature_sources(glances, hardware):
    if not glances:
        return dict(hardware)
    result = dict(glances)
    result.update({label: temperature for label, temperature in hardware.items()
                   if label.startswith(("BMC · ", "disk · "))})
    return result


def record_temperature_trends(host, temperatures):
    timestamp = datetime.now().isoformat()
    with _server_registry_lock, _trend_lock:
        if host != "nas" and host not in _load_server_registry():
            return
        _trends_temp.setdefault(host, {})
        for name, temperature in temperatures.items():
            series = _trends_temp[host].setdefault(name, deque(maxlen=TREND_TEMP_MAX))
            series.append({"t": timestamp, "temp": round(float(temperature), 1)})


def get_temperature_trends(host, minutes=60):
    if host not in _trends_temp:
        return {}
    cutoff = time.time() - minutes * 60
    with _trend_lock:
        return {
            name: [point for point in points if datetime.fromisoformat(point["t"]).timestamp() >= cutoff]
            for name, points in _trends_temp.get(host, {}).items()
        }

# 
# ── 部署配置（开源默认值为中性占位符；生产环境由 .env 覆盖为真实地址）──
# 本机 NAS 地址：Glances / Home Assistant / New API 等本地服务默认基于此主机。
NAS_HOST = os.environ.get("NAS_HOST", "nas.local").strip()
# NAS 在本机组网中的地址（用于概览 latency 字段）；留空则不展示。
NAS_MESH_IP = os.environ.get("NAS_MESH_IP", "").strip()
# 路由器（爱快）管理地址；留空则不采集路由器数据。
ROUTER_HOST = os.environ.get("ROUTER_HOST", "").strip()
ROUTER_BASE = os.environ.get("ROUTER_BASE", f"http://{ROUTER_HOST}" if ROUTER_HOST else "").rstrip("/")
# 局域网主网段前缀：同 MAC 多 IP 时优先保留该网段地址。
LAN_PREFIX = os.environ.get("LAN_PREFIX", "192.168.").strip()

HA_URL = os.environ.get("HA_URL", f"http://{NAS_HOST}:8123/api/states")
HA_TOKEN = os.environ.get("HA_TOKEN", "")
NEW_API_KEY = os.environ.get("NEW_API_KEY", "")
APP_API_TOKEN = os.environ.get("APP_API_TOKEN", "")
# App 扫码导入用的对外后端地址（含协议与端口）。留空则按请求 Host 推断，
# 供纯内网访问时生成内网二维码；公网/反代场景建议显式配置。
PUBLIC_BASE_URL = os.environ.get("PUBLIC_BASE_URL", "").rstrip("/")
# Web 端可使用独立令牌，避免忘记 Web 口令时影响已登录的 App。
WEB_API_TOKEN = os.environ.get("WEB_API_TOKEN", "")
# 一次性 Web 登录码：仅在 URL fragment 里传递，后端只接受一次 POST 兑换。
WEB_BOOTSTRAP_CODE = os.environ.get("WEB_BOOTSTRAP_CODE", "")
try:
    WEB_BOOTSTRAP_EXPIRES_AT = int(os.environ.get("WEB_BOOTSTRAP_EXPIRES_AT", "0") or 0)
except ValueError:
    WEB_BOOTSTRAP_EXPIRES_AT = 0
_WEB_BOOTSTRAP_USED = False
_WEB_BOOTSTRAP_LOCK = Lock()
# 内网 Web 端静态资源目录（与 api_server.py 同级的 web/）
WEB_DIR = Path(__file__).resolve().parent / "web"
ANYTHINGLLM_URL = os.environ.get("ANYTHINGLLM_URL", "").rstrip("/")
ANYTHINGLLM_API_KEY = os.environ.get("ANYTHINGLLM_API_KEY", "")
ANYTHINGLLM_WORKSPACE = os.environ.get("ANYTHINGLLM_WORKSPACE", "nas-docker")
# 归档 raw-text 时 AnythingLLM 会同步做分片+向量化，长文档可能耗时数分钟。
ARCHIVE_EMBED_TIMEOUT = int(os.environ.get("ARCHIVE_EMBED_TIMEOUT", "600"))
_ARCHIVE_TASKS = {}
_ARCHIVE_LOCK = Lock()
_rate_lock = Lock()
_rate_windows = {}
_RATE_LIMIT = 120
_RATE_WINDOW = 60.0
NEW_API_URL = os.environ.get("NEW_API_URL", f"http://{NAS_HOST}:3002").rstrip("/")

# 可选远程主机：留空则不在概览中显示（开源默认无远程主机）。
ALIYUN_HOST = os.environ.get("ALIYUN_HOST", "").strip()
ALIYUN_PUBLIC_IP = os.environ.get("ALIYUN_PUBLIC_IP", "").strip()
ALIYUN_GLANCES = os.environ.get("ALIYUN_GLANCES", f"http://{ALIYUN_HOST}:61208" if ALIYUN_HOST else "").rstrip("/")
NAS_GLANCES = os.environ.get("NAS_GLANCES", f"http://{NAS_HOST}:61208").rstrip("/")
# 腾讯云服务器：与阿里云一致，依赖该主机主动提供 Glances HTTP API。
TENCENT_HOST = os.environ.get("TENCENT_HOST", "").strip()
# 组网地址与公网地址是两个不同字段，禁止用 TENCENT_HOST 代替公网地址。
TENCENT_PUBLIC_IP = os.environ.get("TENCENT_PUBLIC_IP", "").strip()
TENCENT_GLANCES = os.environ.get("TENCENT_GLANCES", f"http://{TENCENT_HOST}:61208" if TENCENT_HOST else "").rstrip("/")
# 阿里云主机安全采集器：只读、只走组网，不与 NAS 雷池数据混用。
# 腾讯云主机安全采集器：只读、只走组网，不与 NAS 或阿里云数据混用。

# 服务探活清单：全部可由环境变量覆盖，未配置的条目自动跳过。
# 开源默认只探测本机常见服务；个人附加服务通过 *_SERVICE_* 环境变量开启。
def _service_url(env_key, default=""):
    return (os.environ.get(env_key, default) or "").strip().rstrip("/")


def _build_health_checks():
    nas = {}
    for name, env_key, default in (
        ("new-api", "NAS_SERVICE_NEW_API", f"http://{NAS_HOST}:3002/v1/models"),
        ("kuma", "NAS_SERVICE_KUMA", f"http://{NAS_HOST}:3004"),
        ("ha", "NAS_SERVICE_HA", f"http://{NAS_HOST}:8123"),
        ("gitea", "NAS_SERVICE_GITEA", ""),
        ("mihomo-proxy", "NAS_SERVICE_MIHOMO", ""),
        ("safeline", "NAS_SERVICE_SAFELINE", ""),
        ("lucky", "NAS_SERVICE_LUCKY", ""),
        ("narratoai", "NAS_SERVICE_NARRATOAI", ""),
        ("smanga", "NAS_SERVICE_SMANGA", ""),
        ("fan-dashboard", "NAS_SERVICE_FAN_DASHBOARD", ""),
    ):
        url = _service_url(env_key, default)
        if url:
            nas[name] = url
    checks = {"nas": nas}
    router_url = _service_url("ROUTER_SERVICE_URL", f"http://{ROUTER_HOST}:80" if ROUTER_HOST else "")
    if router_url:
        checks["router"] = {"router": router_url}
    if ALIYUN_HOST:
        aliyun = {}
        for name, env_key, default in (
            ("new-api", "ALIYUN_SERVICE_NEW_API", f"http://{ALIYUN_HOST}:3001/v1/models"),
            ("glances", "ALIYUN_SERVICE_GLANCES", ALIYUN_GLANCES),
            ("mihomo", "ALIYUN_SERVICE_MIHOMO", ""),
            ("coraza-waf", "ALIYUN_SERVICE_CORAZA", ""),
        ):
            url = _service_url(env_key, default)
            if url:
                aliyun[name] = url
        checks["aliyun"] = aliyun
    return checks


HEALTH_CHECKS = _build_health_checks()

# ── 工具函数 ──────────────────────────────────────


# ── 爱快路由器状态 ──
IKUAI_PASS = os.environ.get("IKUAI_PASS", "")

_router_cache = {'ts': 0.0, 'data': None}
_ROUTER_TTL = 15.0
_speedtest_lock = {'running': False}

# 可选：某些 WAN 是 DHCP 二级路由上联，真实业务流量走上级 OpenWrt 出口，
# 爱快拿不到该 WAN 的实时速率。这里配置上级 OpenWrt 的 SSH 信息，读取其
# 出口接口（默认 pppoe-wan）的实时速率并回填到对应 WAN 口。
_OPENWRT_RATE_CFG = {
    "wan3": {
        "host": os.environ.get("OPENWRT_WAN3_HOST", ""),
        "port": int(os.environ.get("OPENWRT_WAN3_PORT", "22")),
        "user": os.environ.get("OPENWRT_WAN3_USER", "root"),
        "password": os.environ.get("OPENWRT_WAN3_PASSWORD", ""),
        "iface": os.environ.get("OPENWRT_WAN3_IFACE", "pppoe-wan"),
    },
}
_openwrt_rate_state = {"ts": 0.0, "prev": {}}
_OPENWRT_RATE_TTL = 2.0


def _openwrt_iface_rate(cfg):
    """读取 OpenWrt 出口接口实时速率（字节/秒），失败返回 None。

    每次调用取两个时刻的累计字节并差分；前一次无基线时返回 0。
    """
    import paramiko
    host, port, user, password, iface = cfg["host"], cfg["port"], cfg["user"], cfg["password"], cfg["iface"]
    if not password:
        return None
    now = time.time()
    try:
        client = paramiko.SSHClient()
        client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        client.connect(host, port=port, username=user, password=password,
                       timeout=6, look_for_keys=False, allow_agent=False, banner_timeout=6, auth_timeout=6)
        try:
            stdin, stdout, _ = client.exec_command(
                "cat /sys/class/net/%s/statistics/rx_bytes; cat /sys/class/net/%s/statistics/tx_bytes" % (iface, iface),
                timeout=6)
            out = stdout.read().decode("utf-8", "replace").strip()
        finally:
            client.close()
        parts = out.split()
        if len(parts) < 2:
            return None
        rx = int(parts[0]); tx = int(parts[1])
    except Exception:
        return None
    key = "%s:%s" % (host, iface)
    prev = _openwrt_rate_state["prev"].get(key)
    if prev is None:
        _openwrt_rate_state["prev"][key] = {"rx": rx, "tx": tx, "ts": now}
        return {"download": 0, "upload": 0}
    dt = now - prev["ts"]
    if dt <= 0:
        return None
    download = max(0, (rx - prev["rx"]) // dt)
    upload = max(0, (tx - prev["tx"]) // dt)
    _openwrt_rate_state["prev"][key] = {"rx": rx, "tx": tx, "ts": now}
    return {"download": download, "upload": upload}


def _apply_openwrt_rates(result):
    """把 OpenWrt 出口速率回填到对应 WAN 口（失败保持原值）。"""
    state = _openwrt_rate_state
    for wan_name, cfg in _OPENWRT_RATE_CFG.items():
        if now := time.time() - state["ts"] < _OPENWRT_RATE_TTL:
            continue
        rate = _openwrt_iface_rate(cfg)
        if rate is None:
            continue
        for w in result.get("wans", []):
            if w.get("name") == wan_name:
                w["upload_speed"] = rate["upload"]
                w["download_speed"] = rate["download"]
                break
    state["ts"] = time.time()


def get_public_ip():
    import hashlib
    try:
        ctx = __import__("ssl")._create_unverified_context()
        md5pw = hashlib.md5(IKUAI_PASS.encode()).hexdigest()
        data = json.dumps({"username": "admin", "passwd": md5pw}).encode()
        req = urllib.request.Request(ROUTER_BASE + "/Action/login", data=data, method="POST")
        req.add_header("Content-Type", "application/json; charset=utf-8")
        with urllib.request.urlopen(req, timeout=5, context=ctx) as r:
            cookies = r.headers.get_all("Set-Cookie")
            if not cookies: return ""
            cookie = "; ".join(c.split(";")[0] for c in cookies)
        p = json.dumps({"action": "show", "func_name": "wan", "param": {}}).encode()
        r = urllib.request.Request(ROUTER_BASE + "/Action/call", data=p, method="POST")
        r.add_header("Content-Type", "application/json; charset=utf-8")
        r.add_header("Cookie", cookie)
        with urllib.request.urlopen(r, timeout=5, context=ctx) as resp:
            wan = json.loads(resp.read().decode())
        for w in (wan.get("Data") or {}).get("data") or []:
            if w.get("name") == "wan2" and w.get("pppoe_ip_addr"):
                return w["pppoe_ip_addr"]
        return ""
    except:
        return ""

def get_router_status():
    """获取爱快路由器状态（WAN IP、在线设备、测速）。

    未配置 ROUTER_HOST 或 IKUAI_PASS 时返回 unconfigured，不发起请求，
    便于开源用户在未接入爱快时正常使用其余功能。
    """
    if not ROUTER_HOST or not IKUAI_PASS:
        return {"status": "unconfigured"}
    now = time.time()
    if _router_cache['data'] is not None and now - _router_cache['ts'] < _ROUTER_TTL:
        return _router_cache['data']
    import hashlib, json, urllib.request
    ctx = __import__('ssl')._create_unverified_context()
    md5pw = hashlib.md5(IKUAI_PASS.encode()).hexdigest()
    data = json.dumps({"username": "admin", "passwd": md5pw}).encode()
    req = urllib.request.Request(ROUTER_BASE + "/Action/login", data=data, method="POST")
    req.add_header("Content-Type", "application/json; charset=utf-8")
    try:
        with urllib.request.urlopen(req, timeout=5, context=ctx) as r:
            cookies = r.headers.get_all("Set-Cookie")
            if not cookies: return {"status": "down", "error": "login failed"}
            cookie = "; ".join(c.split(";")[0] for c in cookies)
    except Exception as e:
        return {"status": "down", "error": str(e)[:60]}

    def call_api(func_name, action="show", params=None):
        if params is None: params = {}
        p = json.dumps({"action": action, "func_name": func_name, "param": params}).encode()
        r = urllib.request.Request(ROUTER_BASE + "/Action/call", data=p, method="POST")
        r.add_header("Content-Type", "application/json; charset=utf-8")
        r.add_header("Cookie", cookie)
        with urllib.request.urlopen(r, timeout=5, context=ctx) as resp:
            return json.loads(resp.read().decode("utf-8", errors="replace"))

    result = {"status": "up", "address": ROUTER_HOST or "router"}

    # 系统信息（CPU、内存、温度、实时流量）
    try:
        ss = call_api("sysstat", "show", {"TYPE": "all"})
        if ss.get("Result") == 30000:
            d = ss.get("Data") or {}
            cpu_list = d.get("cpu") or []
            if cpu_list:
                result["cpu_usage"] = float(cpu_list[-1].replace("%", ""))
            cputemp = d.get("cputemp") or []
            if cputemp:
                result["cpu_temp"] = int(cputemp[0])
            mem = d.get("memory") or {}
            if mem:
                result["mem_usage"] = float(mem.get("used", "0%").replace("%", ""))
                result["mem_total"] = round(mem.get("total", 0) / 1024 / 1024, 1)
                result["mem_available"] = round(mem.get("available", 0) / 1024 / 1024, 1)
            result["uptime"] = d.get("uptime", 0)
            ou = d.get("online_user") or {}
            result["online_user_count"] = ou.get("count", 0)
            stream = d.get("stream") or {}
            if stream:
                result["upload_speed"] = stream.get("upload", 0)
                result["download_speed"] = stream.get("download", 0)
                result["connections"] = stream.get("connect_num", 0)
            ver = d.get("verinfo") or {}
            result["version"] = ver.get("verstring", "")
    except: pass

    # WAN 状态
    try:
        wan = call_api("wan")
        if wan.get("Result") == 30000:
            wans = (wan.get("Data") or {}).get("data") or []
            result["wans"] = []
            for w in wans:
                # 兼容 PPPoE 与 DHCP：IP/网关优先取拨号，空则取 DHCP 分配
                ip = (w.get("pppoe_ip_addr") or "").strip() or (w.get("dhcp_ip_addr") or "").strip()
                gateway = (w.get("pppoe_gateway") or "").strip() or (w.get("dhcp_gateway") or "").strip()
                result["wans"].append({
                    "name": w.get("name", ""),
                    "ip": ip,
                    "speed": w.get("speed", 0),
                    "internet": "up" if ip else "down",
                    "gateway": gateway,
                })
            # 兼容旧字段
            if wans:
                w = wans[0]
                first_ip = (w.get("pppoe_ip_addr") or "").strip() or (w.get("dhcp_ip_addr") or "").strip()
                result["internet"] = "up" if first_ip else "down"
                result["wan_speed"] = w.get("speed", 0)
                result["wan_ip"] = first_ip
                result["wan_name"] = w.get("name", "wan1")
                result["wan_gateway"] = (w.get("pppoe_gateway") or "").strip() or (w.get("dhcp_gateway") or "").strip()
    except: result["internet"] = "unknown"

    # 动态 WAN 口名集合（爱快后台新增/删除 WAN 后自动跟随）
    active_wan_names = [w.get("name", "") for w in result.get("wans", []) if w.get("name")]

    # 在线设备：数量优先用爱快 online_user；列表按 MAC 去重，避免同设备多 IP 虚高
    try:
        arp = call_api("arp")
        if arp.get("Result") == 30000:
            entries = (arp.get("Data") or {}).get("data") or []
            devices_by_mac = {}
            for e in entries:
                ip = (e.get("ip_addr") or "").strip()
                if not ip:
                    continue
                mac = (e.get("mac") or "").strip().lower()
                name = e.get("comment", "") or ""
                key = mac or f"ip:{ip}"
                # 优先保留局域网主网段 IP；同 MAC 多 IP 时不重复计数
                prev = devices_by_mac.get(key)
                if prev is None:
                    devices_by_mac[key] = {"name": name, "ip": ip, "mac": e.get("mac", "")}
                else:
                    prev_ip = prev.get("ip", "")
                    if prev_ip.startswith(LAN_PREFIX) and not ip.startswith(LAN_PREFIX):
                        pass
                    elif ip.startswith(LAN_PREFIX) and not prev_ip.startswith(LAN_PREFIX):
                        devices_by_mac[key] = {"name": name or prev.get("name", ""), "ip": ip, "mac": e.get("mac", "") or prev.get("mac", "")}
                    elif name and not prev.get("name"):
                        prev["name"] = name
            devices = sorted(devices_by_mac.values(), key=lambda x: x.get("ip", ""))
            result["devices"] = devices
            # 数量优先对齐爱快面板 online_user；没有时回退到 MAC 去重后的 ARP 数
            if "online_user_count" in result and result.get("online_user_count") is not None:
                result["online_devices"] = int(result.get("online_user_count") or 0)
            else:
                result["online_devices"] = len(devices)
    except: pass
    try:
        dhcp = call_api("dhcp_lease")
        if dhcp.get("Result") == 30000:
            leases = (dhcp.get("Data") or {}).get("data") or []
            result["total_devices"] = len(leases)
    except: pass

    # 各 WAN 口实时流量
    try:
        mi = call_api("monitor_iface", "show", {"TYPE": "iface_check,iface_stream"})
        if mi.get("Result") == 30000:
            streams = (mi.get("Data") or {}).get("iface_stream") or []
            for st in streams:
                name = st.get("interface", "")
                if name in active_wan_names:
                    for w in result.get("wans", []):
                        if w["name"] == name:
                            w["upload_speed"] = st.get("upload", 0)
                            w["download_speed"] = st.get("download", 0)
                            break
    except: pass

    # 测速状态
    try:
        st = call_api("speedtest")
        if st.get("Result") == 30000:
            sd = (st.get("Data") or {}).get("data") or {}
            # 遍历当前实际存在的 WAN 口，取第一个有效测速结果
            for wname in active_wan_names:
                if wname in sd and sd[wname].get("download", "--") != "--":
                    dw = float(sd[wname].get("download", 0) or 0)
                    up = float(sd[wname].get("upload", 0) or 0)
                    result["speedtest_download"] = round(dw / 1000, 1)  # Kbps -> Mbps
                    result["speedtest_upload"] = round(up / 1000, 1)
                    result["speedtest_status"] = sd[wname].get("status_msg", "未测速")
                    break
            if "speedtest_download" not in result:
                result["speedtest_download"] = 0
                result["speedtest_upload"] = 0
                result["speedtest_status"] = st.get("Data", {}).get("global_status_msg", "未测速")
            result["speedtest_running"] = st.get("Data", {}).get("global_status", 0) == 1
    except: pass

    _apply_openwrt_rates(result)

    _router_cache['data'] = result
    _router_cache['ts'] = time.time()
    return result


def fetch_json(url, timeout=5):
    """GET 请求返回 JSON"""
    try:
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode())
    except Exception:
        return None


def fetch_json_with_bearer(url, token, timeout=5):
    """请求主机侧只读采集器；失败返回 None，不回退到其他主机。"""
    try:
        req = urllib.request.Request(url)
        if token:
            req.add_header("Authorization", f"Bearer {token}")
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode())
    except Exception:
        return None


def get_aliyun_security():
    """返回阿里云专属安全数据，采集器不可达时明确返回 unavailable。"""
    config = _load_server_registry().get("aliyun", {})
    data = fetch_json_with_bearer(config.get("security_url", "").rstrip("/") + "/api/security", config.get("security_token", ""), timeout=5) if config.get("security_url") else None
    if isinstance(data, dict):
        data.setdefault("host", "aliyun")
        data.setdefault("source", "aliyun-host-security")
        return data
    return {
        "host": "aliyun",
        "source": "aliyun-host-security",
        "status": "unavailable",
        "message": "阿里云安全数据源未接入",
        "firewalld": {}, "fail2ban": {}, "ssh": {}, "aegis": {}, "coraza": {},
    }


def get_tencent_security():
    """返回腾讯云专属安全数据，采集器不可达时明确返回 unavailable。"""
    config = _load_server_registry().get("tencent", {})
    data = fetch_json_with_bearer(config.get("security_url", "").rstrip("/") + "/api/security", config.get("security_token", ""), timeout=5) if config.get("security_url") else None
    if isinstance(data, dict):
        data.setdefault("host", "tencent")
        data.setdefault("source", "tencent-host-security")
        return data
    return {
        "host": "tencent",
        "source": "tencent-host-security",
        "status": "unavailable",
        "message": "腾讯云安全数据源未接入",
        "firewalld": {}, "fail2ban": {}, "ssh": {}, "aegis": {}, "coraza": {},
    }


def fetch_text(url, timeout=5):
    """GET 请求返回文本"""
    try:
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.read().decode()[:200]
    except Exception:
        return None


def ping_host(host, timeout=3):
    """ping 测试"""
    try:
        result = subprocess.run(
            ["ping", "-c", "1", "-W", str(timeout), host],
            capture_output=True, text=True, timeout=timeout+2
        )
        for line in result.stdout.split('\n'):
            if 'time=' in line:
                ms = line.split('time=')[1].split()[0].replace('ms', '')
                return float(ms)
        return None
    except Exception:
        return None


def check_tcp(host, port, timeout=3):
    import socket
    start = time.time()
    try:
        s = socket.socket()
        s.settimeout(timeout)
        s.connect((host, port))
        s.close()
        ms = (time.time() - start) * 1000
        return {"status": "up", "code": 0, "ms": round(ms, 1)}
    except Exception:
        return {"status": "down", "code": None, "ms": None}


def check_ssl(url, timeout=3):
    import ssl
    start = time.time()
    try:
        ctx = ssl._create_unverified_context()
        req = urllib.request.Request(url, method="HEAD")
        with urllib.request.urlopen(req, timeout=timeout, context=ctx) as r:
            ms = (time.time() - start) * 1000
            status = "up" if 200 <= r.status < 400 else "degraded"
            return {"status": status, "code": r.status, "ms": round(ms, 1)}
    except urllib.error.HTTPError as e:
        ms = (time.time() - start) * 1000
        status = "up" if e.code in (401,403,404,405) else "degraded"
        return {"status": status, "code": e.code, "ms": round(ms, 1)}
    except Exception:
        return {"status": "down", "code": None, "ms": None}


def check_https_sni(ip, hostname, port=443, path="/health", timeout=5):
    """HTTPS probe to a fixed private/overlay IP while preserving hostname SNI.
    Coraza is routed by TLS SNI on :23245, so a raw IP HTTPS probe is invalid.
    200-399 is healthy; a completed TLS+HTTP error is at least degraded."""
    start = time.time()
    raw = None
    try:
        raw = socket.create_connection((ip, port), timeout=timeout)
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        conn = ctx.wrap_socket(raw, server_hostname=hostname)
        req = ("HEAD %s HTTP/1.1\r\nHost: %s\r\nConnection: close\r\nUser-Agent: HADashboard/1\r\n\r\n" % (path, hostname)).encode()
        conn.sendall(req)
        head = conn.recv(512).decode("iso-8859-1", errors="replace")
        status_line = head.split("\r\n", 1)[0]
        code = int(status_line.split()[1])
        ms = (time.time() - start) * 1000
        conn.close()
        return {"status": "up" if 200 <= code < 400 else "degraded", "code": code, "ms": round(ms, 1)}
    except Exception:
        try:
            if raw: raw.close()
        except Exception:
            pass
        return {"status": "down", "code": None, "ms": None}


def check_service(url, timeout=3, token=None):
    """探活：2xx/3xx=up，401/404 等可达但异常=degraded，网络失败=down"""
    start = time.time()
    try:
        req = urllib.request.Request(url, method='GET' if token else 'HEAD')
        if token:
            req.add_header("Authorization", f"Bearer {token}")
        with urllib.request.urlopen(req, timeout=timeout) as r:
            ms = (time.time() - start) * 1000
            code = r.status
            status = "up" if 200 <= code < 400 else "degraded"
            return {"status": status, "code": code, "ms": round(ms, 1)}
    except urllib.error.HTTPError as e:
        ms = (time.time() - start) * 1000
        # 服务可达但返回错误
        if e.code in (401, 403, 404, 405):
            # 很多探活目标不支持 HEAD/无鉴权，仍视为可达
            status = "up"
        elif 400 <= e.code < 600:
            status = "degraded"
        else:
            status = "down"
        return {"status": status, "code": e.code, "ms": round(ms, 1)}
    except Exception:
        return {"status": "down", "code": None, "ms": None}

def get_glances(glances_url, timeout=5):
    """从 Glances 获取系统指标；远程失败必须有短上限，避免拖慢整个概览。"""
    data = {}
    # CPU
    cpu = fetch_json(f"{glances_url}/api/4/cpu", timeout=timeout)
    # CPU endpoint is the first mandatory Glances reachability probe. If it fails,
    # do not serially wait for five more endpoints on an unreachable remote host.
    if not cpu:
        return data
    if cpu:
        data["cpu"] = {
            "percent": cpu.get("total", 0),
            "user": cpu.get("user", 0),
            "iowait": cpu.get("iowait", 0),
            "cores": cpu.get("cpucore", 0),
        }
    # 内存
    mem = fetch_json(f"{glances_url}/api/4/mem", timeout=timeout)
    if mem:
        data["memory"] = {
            "percent": mem.get("percent", 0),
            "total_gb": round(mem.get("total", 0) / (1024**3), 1),
            "used_gb": round(mem.get("used", 0) / (1024**3), 1),
            "available_gb": round(mem.get("available", 0) / (1024**3), 1),
        }
    # 磁盘
    fs = fetch_json(f"{glances_url}/api/4/fs", timeout=timeout)
    if fs:
        disks = []
        seen = set()
        for d in fs if isinstance(fs, list) else fs.get('fs', []):
            if not isinstance(d, dict):
                continue
            mount = d.get("mnt_point", "")
            if glances_url == NAS_GLANCES:
                # /host-root/volN 是新增卷在 Glances 容器内的镜像挂载（新卷未单独 bind mount）；
                # 归一到 /volN，避免新盘加卷后看不到。
                mirror = re.fullmatch(r"/host-root(/vol\d+)", mount)
                if mirror:
                    mount = mirror.group(1)
                elif mount not in ("/", "/host-root") and not re.fullmatch(r"/vol\d+", mount):
                    continue
            if not mount.startswith('/') or mount in seen:
                continue
            seen.add(mount)
            disks.append({
                "mount": mount,
                "device": d.get('device_name', ''),
                "percent": d.get('percent', 0),
                "total_gb": round(d.get('size', 0) / (1024**3), 1),
                "used_gb": round(d.get('used', 0) / (1024**3), 1),
            })
        data["disks"] = disks
    # 负载
    load = fetch_json(f"{glances_url}/api/4/load", timeout=timeout)
    if load:
        data["load"] = {
            "min1": load.get("min1", 0),
            "min5": load.get("min5", 0),
            "min15": load.get("min15", 0),
        }
    # 网络 + 首页摘要：一次请求，优先物理网卡；速率取 Glances 的 rate 字段
    net = fetch_json(f"{glances_url}/api/4/network", timeout=timeout)
    if net:
        interfaces = net if isinstance(net, list) else net.get("network", [])
        ifaces = []
        for n in interfaces:
            if not isinstance(n, dict):
                continue
            name = n.get("interface_name", "")
            if name in ("lo",):
                continue
            rx = float(n.get("bytes_recv_rate_per_sec") or n.get("rx") or 0)
            tx = float(n.get("bytes_sent_rate_per_sec") or n.get("tx") or 0)
            ifaces.append({
                "name": name,
                "rx_bps": round(rx, 1),
                "tx_bps": round(tx, 1),
                "rx_mb": round(rx / (1024**2), 2),
                "tx_mb": round(tx / (1024**2), 2),
            })
        data["network"] = ifaces
        # 物理网卡优先，避免把 VPN/网桥/veth 等虚拟口当作 NAS 网速。
        # 换主板后物理口会改名（enp1s0-ovs -> enp3s0-ovs），所以按命名规则匹配，
        # 不再写死单个网卡名；存在多个物理口时取流量最大的那个。
        def _is_virtual_nic(name):
            low = name.lower()
            if low == "lo" or low.startswith(("br-", "veth", "docker", "safeline",
                                              "virbr", "tun", "tap", "wg", "ovs-system")):
                return True
            return "vpn" in low
        def _total_rate(item):
            return (float(item.get("bytes_recv_rate_per_sec") or item.get("rx") or 0)
                    + float(item.get("bytes_sent_rate_per_sec") or item.get("tx") or 0))
        candidates = [n for n in interfaces
                      if isinstance(n, dict) and not _is_virtual_nic(str(n.get("interface_name") or ""))]
        physical = [n for n in candidates
                    if re.match(r"^(enp|ens|eno|eth|enx|bond|em\d|p\d+p\d+)",
                                str(n.get("interface_name") or ""))]
        selected = (max(physical, key=_total_rate) if physical
                    else (max(candidates, key=_total_rate) if candidates else None))
        if selected:
            rx = float(selected.get("bytes_recv_rate_per_sec") or selected.get("rx") or 0)
            tx = float(selected.get("bytes_sent_rate_per_sec") or selected.get("tx") or 0)
            data["network_summary"] = {
                "interface": selected.get("interface_name", ""),
                "rx_bps": round(rx, 1),
                "tx_bps": round(tx, 1),
            }
    # 运行时间
    uptime = fetch_json(f"{glances_url}/api/4/uptime", timeout=timeout)
    if uptime:
        if isinstance(uptime, dict):
            data["uptime"] = uptime.get("uptime", "")
        else:
            data["uptime"] = str(uptime)
    return data


def get_core_services(group):
    """首页只展示关键服务状态。"""
    result = {}
    if group == "nas":
        result["new-api"] = check_service(HEALTH_CHECKS["nas"]["new-api"], token=NEW_API_KEY)
        result["kuma"] = check_service(HEALTH_CHECKS["nas"]["kuma"])
        result["mihomo-proxy"] = check_tcp(NAS_HOST, 8890)
        result["lucky"] = check_service(HEALTH_CHECKS["nas"]["lucky"])
    elif group == "aliyun":
        checks = HEALTH_CHECKS.get("aliyun") or {}
        if "new-api" in checks:
            result["new-api"] = check_service(checks["new-api"], token=NEW_API_KEY)
        if "glances" in checks:
            result["glances"] = check_service(checks["glances"])
        if ALIYUN_HOST:
            result["mihomo"] = check_tcp(ALIYUN_HOST, 8890)
    return result


def docker_api_request(path, timeout=10):
    """通过已挂载的 Docker Unix socket 请求 API。"""
    import http.client
    conn = http.client.HTTPConnection("localhost", timeout=timeout)
    conn.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    conn.sock.settimeout(timeout)
    conn.sock.connect("/var/run/docker.sock")
    conn.request("GET", path)
    response = conn.getresponse()
    payload = response.read()
    status = response.status
    conn.close()
    if status >= 400:
        raise RuntimeError(f"Docker API {status}: {payload.decode('utf-8', errors='replace')[:200]}")
    return json.loads(payload.decode() or "{}")


_CONTAINER_ACTIONS = {"start": "start", "stop": "stop", "restart": "restart"}
_CONTAINER_NAME_PATTERN = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$")


def docker_container_action(name, action, timeout=20):
    """仅对 NAS 本机容器执行白名单动作，避免把 Docker API 全量暴露给 App。"""
    if action not in _CONTAINER_ACTIONS:
        raise ValueError("不支持的容器操作")
    if not name or not _CONTAINER_NAME_PATTERN.fullmatch(name):
        raise ValueError("容器名称格式无效")
    import http.client
    conn = http.client.HTTPConnection("localhost", timeout=timeout)
    try:
        conn.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        conn.sock.settimeout(timeout)
        conn.sock.connect("/var/run/docker.sock")
        conn.request("POST", f"/containers/{urllib.parse.quote(name, safe='')}/{_CONTAINER_ACTIONS[action]}")
        response = conn.getresponse()
        payload = response.read()
        status = response.status
    finally:
        conn.close()
    if status == 404:
        raise LookupError("容器不存在或已删除")
    if status >= 400:
        detail = payload.decode("utf-8", errors="replace").strip()
        raise RuntimeError(detail[:200] or f"Docker API {status}")
    return {"ok": True, "host": "nas", "name": name, "action": action}


def get_container_stats():
    """获取本机 Docker 容器的实时 CPU、内存与网络累计字节。"""
    try:
        containers_data = docker_api_request("/containers/json?all=true")
        def build_container(container):
            container_id = container.get("Id", "")
            name = container.get("Names", [""])[0].lstrip("/") if container.get("Names") else ""
            state = container.get("State", "unknown")
            status = container.get("Status", "")
            image = container.get("Image", "")
            item = {
                "name": name, "status": state, "image": image,
                "cpu_percent": 0.0, "memory_usage_mb": 0.0, "memory_limit_mb": 0.0,
                "memory_percent": 0.0, "network_rx_bytes": 0, "network_tx_bytes": 0,
                "uptime": status,
            }
            if state != "running" or not container_id:
                return item
            stats = docker_api_request(f"/containers/{container_id}/stats?stream=false", timeout=5)
            cpu = stats.get("cpu_stats", {})
            previous_cpu = stats.get("precpu_stats", {})
            cpu_delta = float(cpu.get("cpu_usage", {}).get("total_usage", 0)) - float(previous_cpu.get("cpu_usage", {}).get("total_usage", 0))
            system_delta = float(cpu.get("system_cpu_usage", 0)) - float(previous_cpu.get("system_cpu_usage", 0))
            online_cpus = int(cpu.get("online_cpus") or len(cpu.get("cpu_usage", {}).get("percpu_usage") or []) or 1)
            if cpu_delta > 0 and system_delta > 0:
                item["cpu_percent"] = round(cpu_delta / system_delta * online_cpus * 100, 2)
            memory = stats.get("memory_stats", {})
            usage = max(0, float(memory.get("usage", 0)) - float(memory.get("stats", {}).get("cache", 0)))
            limit = float(memory.get("limit", 0))
            item["memory_usage_mb"] = round(usage / 1024 / 1024, 2)
            item["memory_limit_mb"] = round(limit / 1024 / 1024, 2)
            item["memory_percent"] = round(usage / limit * 100, 2) if limit else 0.0
            for network in stats.get("networks", {}).values():
                item["network_rx_bytes"] += int(network.get("rx_bytes", 0) or 0)
                item["network_tx_bytes"] += int(network.get("tx_bytes", 0) or 0)
            return item

        with ThreadPoolExecutor(max_workers=8) as pool:
            containers = [future.result() for future in as_completed([pool.submit(build_container, c) for c in containers_data])]
        return {"host": "nas", "containers": containers}
    except Exception as exc:
        return {"host": "nas", "containers": [], "error": str(exc)}


def get_remote_container_stats(host):
    """Glances 容器端点的字段随版本不同，保留可用实时字段并提供统一响应。"""
    glances_url = _remote_glances_map().get(host)
    if not glances_url:
        return {"host": host, "containers": [], "error": "unknown host"}
    try:
        raw = fetch_json(f"{glances_url}/api/4/containers") or []
        containers = []
        for container in raw:
            memory_usage = float(container.get("memory_usage", container.get("memory", 0)) or 0)
            memory_limit = float(container.get("memory_limit", 0) or 0)
            containers.append({
                "name": container.get("name", ""),
                "status": container.get("status", container.get("state", "unknown")),
                "image": str(container.get("image", "")),
                "cpu_percent": round(float(container.get("cpu_percent", container.get("cpu", 0)) or 0), 2),
                "memory_usage_mb": round(memory_usage / 1024 / 1024, 2) if memory_usage > 1024 * 1024 else round(memory_usage, 2),
                "memory_limit_mb": round(memory_limit / 1024 / 1024, 2) if memory_limit > 1024 * 1024 else round(memory_limit, 2),
                "memory_percent": round(float(container.get("memory_percent", 0) or 0), 2),
                "network_rx_bytes": int(container.get("network_rx", container.get("rx_bytes", 0)) or 0),
                "network_tx_bytes": int(container.get("network_tx", container.get("tx_bytes", 0)) or 0),
                "uptime": str(container.get("uptime", "")),
            })
        return {"host": host, "containers": containers}
    except Exception as exc:
        return {"host": host, "containers": [], "error": str(exc)}


def get_docker_stats():
    """通过 Docker API 获取容器状态"""
    import http.client
    try:
        conn = http.client.HTTPConnection("localhost", timeout=5)
        conn.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        conn.sock.connect("/var/run/docker.sock")
        conn.request("GET", "/containers/json?all=true")
        resp = conn.getresponse()
        containers_data = json.loads(resp.read().decode())
        conn.close()

        containers = []
        total = running = healthy = 0
        for c in containers_data:
            name = c.get("Names", [""])[0].lstrip("/") if c.get("Names") else ""
            image = c.get("Image", "").split("/")[-1].split(":")[0]
            state = c.get("State", "unknown")
            status = c.get("Status", "")
            ports = c.get("Ports", [])
            port_str = ",".join([f"{p.get('PublicPort','')}" for p in ports if p.get('PublicPort')]) if ports else ""
            total += 1
            if state == "running":
                running += 1
            if "(healthy)" in status.lower() or "health: healthy" in status.lower():
                healthy += 1
            containers.append({
                "name": name,
                "image": image,
                "status": state,
                "ports": port_str,
                "healthy": "(healthy)" in status.lower() or "health: healthy" in status.lower(),
            })
        return {
            "total": total,
            "running": running,
            "stopped": total - running,
            "healthy": healthy,
            "containers": containers,
        }
    except Exception as e:
        return {"error": str(e)}


def get_ha_sensors():
    """从 HA 获取 NAS/风扇/UPS/卷/阿里云/Kuma 传感器"""
    if not HA_TOKEN:
        return {"error": "HA_TOKEN not configured"}
    try:
        req = urllib.request.Request(HA_URL)
        req.add_header("Authorization", f"Bearer {HA_TOKEN}")
        req.add_header("Content-Type", "application/json")
        with urllib.request.urlopen(req, timeout=10) as r:
            states = json.loads(r.read().decode())
    except Exception as e:
        return {"error": f"HA unreachable: {e}"}

    by_id = {s.get("entity_id", ""): s for s in states}
    sensors = {
        "volumes": [],
        "fans": {},
        "ups": {},
        "router": {"router": (ROUTER_BASE + ":80") if ROUTER_HOST else ""},
    "aliyun": {},
        "kuma": [],
        "cabinet_energy": None,
    }

    # 卷：优先 percent + rong_liang 文案。卷号从 HA 实体动态发现，新增/删除卷自动跟随。
    vol_indexes = set()
    for entity_id in by_id:
        matched = re.fullmatch(r"sensor\.nas_vol(\d+)_(?:percent|bar|rong_liang|used)", entity_id)
        if matched:
            vol_indexes.add(int(matched.group(1)))
    for i in sorted(vol_indexes):
        pct_e = by_id.get(f"sensor.nas_vol{i}_percent") or by_id.get(f"sensor.nas_vol{i}_bar")
        cap_e = by_id.get(f"sensor.nas_vol{i}_rong_liang")
        used_e = by_id.get(f"sensor.nas_vol{i}_used")
        if not pct_e and not cap_e:
            continue
        try:
            pct = float(pct_e.get("state", 0)) if pct_e else 0.0
        except (TypeError, ValueError):
            pct = 0.0
        sensors["volumes"].append({
            "name": f"vol{i}",
            "percent": pct,
            "capacity": (cap_e or {}).get("state", ""),
            "used": (used_e or {}).get("state", ""),
            "friendly": (cap_e or pct_e or {}).get("attributes", {}).get("friendly_name", f"vol{i}"),
        })

    # 硬件数据均以 HA 传感器为准：风扇、UPS、机柜环境
    for n in (1, 2):
        pwm = by_id.get(f"input_number.nas_fan{n}_pwm") or by_id.get(f"sensor.nas_fan{n}_pwm")
        mode = by_id.get(f"input_select.nas_fan{n}_mode")
        rpm = by_id.get(f"sensor.nas_fan{n}_rpm")
        if pwm or mode or rpm:
            sensors["fans"][f"fan{n}"] = {
                "pwm": (pwm or {}).get("state"),
                "mode": (mode or {}).get("state"),
                "rpm": (rpm or {}).get("state"),
            }

    def _state(eid, default=None):
        e = by_id.get(eid)
        return e.get("state", default) if e else default

    sensors["ups"] = {
        "status": _state("sensor.nas_ups_status"),
        "charge": _state("sensor.nas_ups_battery_charge"),
        "load": _state("sensor.nas_ups_load"),
        "battery_voltage": _state("sensor.nas_ups_battery_voltage"),
        "input_voltage": _state("sensor.nas_ups_input_voltage"),
        "output_voltage": _state("sensor.nas_ups_output_voltage"),
    }
    # Delete absent entries so UI can identify no-data safely
    sensors["ups"] = {k:v for k,v in sensors["ups"].items() if v not in (None, "unknown", "unavailable")}

    sensors["cabinet"] = {
        "temperature": _state("sensor.miaomiaoce_t2_45dd_temperature"),
        "humidity": _state("sensor.miaomiaoce_t2_45dd_relative_humidity"),
        "power": _state("sensor.yutai_fsov8w_e459_electric_power"),
        "energy_today": _state("sensor.cabinet_socket_energy_daily"),
        "energy_month": _state("sensor.cabinet_socket_energy_monthly"),
        "energy_total": _state("sensor.cabinet_socket_energy"),
    }
    sensors["cabinet"] = {k:v for k,v in sensors["cabinet"].items() if v not in (None, "unknown", "unavailable")}
    sensors["cabinet_energy"] = sensors["cabinet"].get("energy_total")

    # 阿里云 HA 实体
    mapping = {
        "sensor.aliyun_cpu_percent": "cpu",
        "sensor.aliyun_memory_percent": "memory",
        "sensor.aliyun_disk_percent": "disk",
        "sensor.aliyun_load_1m": "load_1m",
        "sensor.aliyun_memory_used": "memory_used",
        "sensor.aliyun_net_recv": "net_recv",
        "sensor.aliyun_net_sent": "net_sent",
    }
    for eid, key in mapping.items():
        if eid in by_id:
            sensors["aliyun"][key] = by_id[eid].get("state")

    # Kuma 经 HA 传感器：成对 status + ping
    kuma_status = {}
    kuma_ping = {}
    for eid, s in by_id.items():
        if not eid.startswith("sensor.kuma_"):
            continue
        name = eid.replace("sensor.kuma_", "")
        if name.endswith("_status"):
            kuma_status[name[:-7]] = s.get("state", "unknown")
        elif name.endswith("_ping"):
            try:
                kuma_ping[name[:-5]] = float(s.get("state", 0))
            except (TypeError, ValueError):
                kuma_ping[name[:-5]] = None
    for key in sorted(set(kuma_status) | set(kuma_ping)):
        st = str(kuma_status.get(key, "unknown")).lower()
        sensors["kuma"].append({
            "name": key.replace("_", " "),
            "status": "up" if st in ("up", "1", "true", "on") else ("down" if st in ("down", "0", "false", "off") else st),
            "latency_ms": kuma_ping.get(key),
        })

    # 雷池安全数据
    try:
        safeline = {}
        for key, eid in [("today_requests","sensor.lei_chi_jin_ri_gong_wang_qing_qiu"),("today_ips","sensor.lei_chi_jin_ri_gong_wang_ip"),("today_internal","sensor.lei_chi_jin_ri_nei_wang_qing_qiu"),("today_anomaly","sensor.lei_chi_jin_ri_yi_chang_qing_qiu"),("status","sensor.lei_chi_jin_ri_fang_wen_zhuang_tai"),("week_ips","sensor.lei_chi_7tian_gong_wang_ip"),("week_requests","sensor.lei_chi_7tian_gong_wang_qing_qiu"),("top_ip_count","sensor.lei_chi_jin_ri_ipfang_wen_top1")]:
            e = by_id.get(eid)
            if e: safeline[key] = e.get("state", "N/A")
        # HA stores structured Top IP data as the top_ips attribute.
        _top = by_id.get("sensor.lei_chi_jin_ri_ipfang_wen_top1", {}).get("attributes", {}).get("top_ips", [])
        if isinstance(_top, list):
            safeline["top_ips"] = sorted(_top, key=lambda x: float(x.get("count", 0) or 0), reverse=True)[:5]
        sensors["safeline"] = safeline
    except: pass

    # 硬盘温度
    try:
        hdd_temps = {}
        for key, eid in [("sda","sensor.nas_hdd_sda_temp"),("sdb","sensor.nas_hdd_sdb_temp"),("sdc","sensor.nas_hdd_sdc_temp"),("sdd","sensor.nas_ssd_sdd_temp"),("sde","sensor.nas_ssd_sde_temp"),("sdf","sensor.nas_hdd_sdf_temp"),("nvme0n1","sensor.nas_nvme_nvme0n1_temp")]:
            e = by_id.get(eid)
            if e:
                try: hdd_temps[key] = int(float(e.get("state", 0)))
                except: pass
        sensors["hdd_temps"] = hdd_temps
    except: pass

    merge_nas_hardware(sensors, by_id.get("sensor.nas_hardware", {}))
    return sensors

def _volume_capacity_text(used_gb, total_gb):
    """与 HA 模板一致的容量文案：用于 Glances 发现的卷缺少 HA 传感器时兜底。"""
    try:
        used = float(used_gb)
        total = float(total_gb)
    except (TypeError, ValueError):
        return ""
    if used > 1024:
        return "%.1fT / %.1fT" % (used / 1024, total / 1024)
    return "%.1fG / %.1fG" % (used, total)


def merge_dynamic_volumes(volumes, disks):
    """卷列表以 Glances 实际挂载的 /volN 为准，HA 传感器仅补充容量文案。

    新增硬盘并建卷后立即可见，移除卷自动消失，无需改 HA 模板或重建容器。
    Glances 数据不可用时保留原 HA 列表，避免整段消失。
    """
    mounted = {}
    for disk in disks or []:
        matched = re.fullmatch(r"/vol(\d+)", disk.get("mount") or "")
        if matched:
            mounted[int(matched.group(1))] = disk
    if not mounted:
        return volumes

    ha_by_index = {}
    for volume in volumes or []:
        matched = re.fullmatch(r"vol(\d+)", volume.get("name") or "")
        if matched:
            ha_by_index[int(matched.group(1))] = volume

    merged = []
    for index in sorted(mounted):
        disk = mounted[index]
        entry = dict(ha_by_index.get(index, {}))
        entry["name"] = f"vol{index}"
        entry["percent"] = round(float(disk.get("percent") or 0), 1)
        entry["device"] = disk.get("device", "")
        entry["used"] = str(disk.get("used_gb", ""))
        if not entry.get("capacity"):
            entry["capacity"] = _volume_capacity_text(disk.get("used_gb"), disk.get("total_gb"))
        entry.setdefault("friendly", f"NAS vol{index} 容量")
        merged.append(entry)
    return merged


def get_kuma_status(ha_sensors=None):
    """Kuma：优先 HA 传感器；公开 status-page API 通常不可用"""
    if isinstance(ha_sensors, dict) and ha_sensors.get("kuma"):
        return ha_sensors["kuma"]
    try:
        req = urllib.request.Request(f"http://{NAS_HOST}:3004/api/status-page/heartbeat")
        with urllib.request.urlopen(req, timeout=5) as r:
            data = json.loads(r.read().decode())
            monitors = []
            for m in data.get("heartbeatList", []) or []:
                monitors.append({
                    "name": m.get("name", ""),
                    "status": "up" if m.get("status") == 1 else "down",
                    "uptime": round(float(m.get("uptime", 0) or 0) * 100, 1),
                    "latency_ms": m.get("latency", 0),
                })
            return monitors[:20] if monitors else None
    except Exception:
        return None


def build_alerts(nas, aliyun, hosts=None):
    """依据当前快照构建只读告警；不执行任何修复动作。"""
    alerts = []
    for host, data in (hosts.items() if hosts is not None else (("nas", nas), ("aliyun", aliyun))):
        memory = float(data.get("memory", 0) or 0)
        if memory >= 90:
            alerts.append({"host": host, "level": "critical", "kind": "memory", "message": f"内存占用 {memory:.0f}%"})
        elif memory >= 80:
            alerts.append({"host": host, "level": "warning", "kind": "memory", "message": f"内存占用 {memory:.0f}%"})
        for disk in data.get("disks", []) or []:
            mount = disk.get("mount", "/")
            # NAS Glances 会暴露 host-root / Docker bind mount；告警只保留真实卷和 ECS 根分区。
            if mount != "/" and not (mount.startswith("/vol") and mount[4:].isdigit()):
                continue
            pct = float(disk.get("percent", 0) or 0)
            if pct >= 90:
                alerts.append({"host": host, "level": "critical", "kind": "disk", "message": f"{mount} 磁盘占用 {pct:.0f}%"})
            elif pct >= 80:
                alerts.append({"host": host, "level": "warning", "kind": "disk", "message": f"{mount} 磁盘占用 {pct:.0f}%"})
        for name, service in (data.get("core_services", {}) or {}).items():
            if service.get("status") == "down":
                alerts.append({"host": host, "level": "critical", "kind": "service", "message": f"{name} 服务不可用"})
            elif service.get("status") == "degraded":
                alerts.append({"host": host, "level": "warning", "kind": "service", "message": f"{name} 响应异常"})
    # 追踪首次出现时间
    _alert_first_seen = getattr(build_alerts, "_first_seen", {})
    now = datetime.now().isoformat()
    for a in alerts:
        key = (a["host"], a["kind"], a["message"])
        if key not in _alert_first_seen:
            _alert_first_seen[key] = now
        a["first_seen"] = _alert_first_seen[key]
    active = {(a["host"], a["kind"], a["message"]) for a in alerts}
    for k in list(_alert_first_seen.keys()):
        if k not in active:
            del _alert_first_seen[k]
    build_alerts._first_seen = _alert_first_seen
    return alerts


# ── 机场套餐余量（只读 Mihomo 订阅快照） ────────────────────
_AIRPORT_CACHE = {"ts": 0.0, "data": None}
_AIRPORT_LOCK = Lock()
_AIRPORT_TTL = 60.0
_AIRPORT_TOTAL_GB = {"airport1": 100.0, "airport2": 203.0}
_AIRPORT_NAMES = {"airport1": "良心云 A", "airport2": "宝可梦"}
_AIRPORT_FILES = {"airport1": "/mihomo/airport1.yaml", "airport2": "/mihomo/airport2.yaml"}

def _mihomo_secret():
    """从已挂载的 Mihomo config 获取本地控制 API 密钥；不返回给客户端。"""
    try:
        with open("/mihomo/config.yaml", encoding="utf-8", errors="ignore") as stream:
            raw = stream.read()
        match = re.search(r"^secret:\s*[\"']?([^\"'\s]+)", raw, re.M)
        return match.group(1) if match else ""
    except Exception:
        return ""

def _provider_subscription_info(provider, secret):
    """优先读取 Mihomo 服务端下发的 Upload/Download/Total 字节计数。"""
    if not secret:
        return None
    try:
        req = urllib.request.Request(
            f"http://host.docker.internal:9090/providers/proxies/{provider}",
            headers={"Authorization": f"Bearer {secret}"},
        )
        with urllib.request.urlopen(req, timeout=8) as resp:
            payload = json.loads(resp.read().decode())
        return payload.get("subscriptionInfo") or payload.get("subscription_info")
    except Exception:
        return None

def get_airport_traffic():
    # 后台采样与 App 请求共用缓存和锁，避免重复计数或乱序写入。
    with _AIRPORT_LOCK:
        return _get_airport_traffic_locked()


def _airport_traffic_sampler():
    while not stop_event.is_set():
        try:
            get_airport_traffic()
            for instance_key in mihomo_api.INSTANCES:
                try:
                    mihomo_api.sample_speeds(instance_key)
                except mihomo_api.MihomoError:
                    pass
        except Exception as exc:
            print(f"airport traffic sampling failed: {type(exc).__name__}")
        # 机场订阅自身仍按 _AIRPORT_TTL 缓存；这里 5 秒一轮只用于速率曲线采样。
        stop_event.wait(mihomo_api.SAMPLE_SECONDS)


def _get_airport_traffic_locked():
    now = time.time()
    # 跨北京时间日期不能继续返回昨日的今日用量。
    day = datetime.now(SHANGHAI).date().isoformat()
    if (_AIRPORT_CACHE["data"] is not None and now - _AIRPORT_CACHE["ts"] < _AIRPORT_TTL
            and _AIRPORT_CACHE.get("day") == day):
        return _AIRPORT_CACHE["data"]
    items = []
    secret = _mihomo_secret()
    gib = 1024 ** 3
    # 连接归属复用 mihomo_api 的节点 -> provider 映射，避免两条链路口径不一致。
    try:
        attribution = mihomo_api.airport_connection_attribution("core")
    except Exception:
        attribution = {}
    for key, file_path in _AIRPORT_FILES.items():
        try:
            with open(file_path, encoding="utf-8", errors="ignore") as stream:
                raw = stream.read()
            traffic_m = re.search(r"剩余流量：([\d.]+)\s*GB", raw)
            reset_m = re.search(r"重置剩余：(\d+)\s*天", raw)
            expire_m = re.search(r"套餐到期：([0-9]{4}-[0-9]{2}-[0-9]{2}|长期有效)", raw)
            if not traffic_m:
                continue
            # Provider API gives authoritative byte counters; YAML name is a fallback only.
            info = _provider_subscription_info(key, secret) or {}
            total_bytes = float(info.get("Total") or info.get("total") or 0)
            upload_bytes = float(info.get("Upload") or info.get("upload") or 0)
            download_bytes = float(info.get("Download") or info.get("download") or 0)
            if total_bytes > 0:
                total = total_bytes / gib
                used = (upload_bytes + download_bytes) / gib
                remaining = max(0.0, total - used)
                data_source = "mihomo_subscription_info"
            else:
                total = _AIRPORT_TOTAL_GB[key]
                remaining = float(traffic_m.group(1))
                used = max(0.0, total - remaining)
                data_source = "mihomo_subscription_yaml_fallback"
            used_percent = round(max(0.0, min(100.0, used / total * 100 if total else 0)), 1)
            level = "critical" if remaining < 20 else "warning" if remaining < 50 else "normal"
            daily = {"today_used_gb": None, "today_usage_partial": True, "today_usage_date": day}
            # YAML 仅有舍入余量，不能与精确累计计数混用来计算日增量。
            if total_bytes > 0 and any(k in info for k in ("Upload", "upload")) and any(k in info for k in ("Download", "download")):
                try:
                    daily = update_airport_daily_usage(key, upload_bytes + download_bytes, total_bytes)
                except Exception as exc:
                    print(f"airport daily state unavailable ({key}): {type(exc).__name__}")
            bucket = attribution.get(key, {"connections": 0})
            items.append({
                "id": key, "name": _AIRPORT_NAMES[key], "connections": int(bucket.get("connections") or 0),
                "remaining_gb": round(remaining, 2),
                "total_gb": round(total, 2), "used_gb": round(used, 2), "used_percent": used_percent,
                "reset_days": int(reset_m.group(1)) if reset_m else None,
                "expire": expire_m.group(1) if expire_m else None, "level": level, "data_source": data_source,
                **daily,
            })
        except Exception:
            continue
    data = {"items": items, "timestamp": datetime.now().isoformat(), "source": "mihomo_subscription"}
    _AIRPORT_CACHE["data"] = data
    _AIRPORT_CACHE["ts"] = time.time()
    _AIRPORT_CACHE["day"] = day
    return data

# AnythingLLM archive helpers
def _archive_request(path, body, timeout=60):
    if not ANYTHINGLLM_API_KEY: raise RuntimeError("ANYTHINGLLM_API_KEY is not configured")
    req=urllib.request.Request(ANYTHINGLLM_URL+path, data=body, method="POST")
    req.add_header("Authorization", "Bearer "+ANYTHINGLLM_API_KEY)
    req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=timeout) as r: return json.loads(r.read().decode("utf-8", "replace") or "{}")

def _archive_request_raw(path, body, content_type):
    """POST a non-JSON payload to AnythingLLM, such as multipart uploads."""
    if not ANYTHINGLLM_API_KEY: raise RuntimeError("ANYTHINGLLM_API_KEY is not configured")
    req=urllib.request.Request(ANYTHINGLLM_URL+path, data=body, method="POST")
    req.add_header("Authorization", "Bearer "+ANYTHINGLLM_API_KEY)
    req.add_header("Content-Type", content_type)
    with urllib.request.urlopen(req, timeout=60) as r: return json.loads(r.read().decode("utf-8", "replace") or "{}")

def _github_readme_fallbacks(link):
    """Return stable raw README URLs for a public GitHub repository URL."""
    parsed = urllib.parse.urlparse(link)
    if parsed.netloc.lower() not in ("github.com", "www.github.com"):
        return []
    parts = [part for part in parsed.path.split("/") if part]
    if len(parts) < 2:
        return []
    owner, repo = parts[0], parts[1]
    if repo.endswith(".git"):
        repo = repo[:-4]
    if not owner or not repo:
        return []
    return [
        f"https://raw.githubusercontent.com/{owner}/{repo}/main/README.md",
        f"https://raw.githubusercontent.com/{owner}/{repo}/master/README.md",
    ]


def _is_github_repo_root(link):
    """True only for https://github.com/{owner}/{repo} (optional .git / trailing slash).

    Deeper paths (issues, PRs, single files) mean something different, so they must
    not be silently replaced by the repository README.
    """
    parsed = urllib.parse.urlparse(link)
    if parsed.netloc.lower() not in ("github.com", "www.github.com"):
        return False
    parts = [part for part in parsed.path.split("/") if part]
    if len(parts) != 2:
        return False
    repo = parts[1][:-4] if parts[1].endswith(".git") else parts[1]
    return bool(parts[0] and repo)


def _archive_upload_raw_text(text, metadata, source):
    """AnythingLLM URL collector unstable; upload fetched text directly."""
    payload = {
        "textContent": text,
        "addToWorkspaces": ANYTHINGLLM_WORKSPACE,
        "metadata": {**(metadata or {}), "title": (metadata or {}).get("title") or source},
    }
    # AnythingLLM embeds synchronously here; large docs can exceed the default 60s.
    result = _archive_request("/api/v1/document/raw-text", json.dumps(payload).encode(), timeout=ARCHIVE_EMBED_TIMEOUT)
    if result.get("success"):
        result["fallback_source"] = source
        result["fallback_mode"] = "raw-text"
        return result
    raise RuntimeError("AnythingLLM raw-text returned success=false")


class _PinnedHTTPSConnection(http.client.HTTPSConnection):
    """HTTPS connection to a fixed IP while keeping SNI/hostname verification."""

    def __init__(self, hostname, address, timeout=20):
        super().__init__(hostname, timeout=timeout)
        self._address = address

    def connect(self):
        sock = socket.create_connection((self._address, self.port), self.timeout)
        self.sock = self._context.wrap_socket(sock, server_hostname=self.host)


def _archive_fetch_github_readme(link):
    """Fetch raw GitHub text; try each IPv4 because some CDN edges are unreachable."""
    parsed = urllib.parse.urlparse(link)
    hostname = parsed.hostname
    addresses = []
    try:
        addresses = [entry[4][0] for entry in socket.getaddrinfo(
            hostname, 443, socket.AF_INET, socket.SOCK_STREAM
        )]
    except socket.gaierror:
        pass

    path = parsed.path or "/"
    if parsed.query:
        path += "?" + parsed.query

    for address in dict.fromkeys(addresses):
        connection = _PinnedHTTPSConnection(hostname, address, timeout=20)
        try:
            connection.request("GET", path, headers={"User-Agent": "NAS-Monitor-Archive/1"})
            response = connection.getresponse()
            body = response.read()
            if response.status != 200:
                continue
            return body.decode("utf-8", "replace")
        except (OSError, http.client.HTTPException, ssl.SSLError):
            continue
        finally:
            connection.close()

    request = urllib.request.Request(link, headers={"User-Agent": "NAS-Monitor-Archive/1"})
    with urllib.request.urlopen(request, timeout=20) as response:
        if response.status != 200:
            raise RuntimeError(f"HTTP {response.status}")
        return response.read().decode("utf-8", "replace")


def _archive_upload_link(link, metadata):
    """Archive a URL.

    GitHub URLs rarely work through AnythingLLM's upload-link collector (its
    content-type probe times out and puppeteer cannot start). For those we fetch
    the raw README ourselves and post it as raw-text, which is reliable.
    """
    payload = {"link": link, "addToWorkspaces": ANYTHINGLLM_WORKSPACE, "metadata": metadata or {}}
    errors = []
    fallbacks = _github_readme_fallbacks(link)

    # 只有仓库根地址才直接走 raw-text；带子路径的 GitHub 链接含义不同，
    # 仍交给 collector，失败时才退到 README。
    if _is_github_repo_root(link):
        for fallback in fallbacks:
            try:
                text = _archive_fetch_github_readme(fallback)
                if text.strip():
                    result = _archive_upload_raw_text(text, metadata, fallback)
                    result["original_link"] = link
                    return result
                errors.append(f"raw-text {fallback}: empty content")
            except Exception as exc:
                errors.append(f"raw-text {fallback}: {str(exc)[:180]}")

    for attempt in range(2):
        try:
            result = _archive_request("/api/v1/document/upload-link", json.dumps(payload).encode())
            if result.get("success"):
                return result
            errors.append("AnythingLLM returned success=false")
        except Exception as exc:
            errors.append(str(exc)[:240])
        if attempt == 0:
            time.sleep(2)

    for fallback in fallbacks:
        try:
            result = _archive_request(
                "/api/v1/document/upload-link",
                json.dumps({**payload, "link": fallback}).encode(),
            )
            if result.get("success"):
                result["fallback_source"] = fallback
                return result
            errors.append(f"fallback {fallback}: success=false")
        except Exception as exc:
            errors.append(f"fallback {fallback}: {str(exc)[:180]}")

    raise RuntimeError("AnythingLLM archive failed after retry/fallback: " + " | ".join(errors))


def _archive_worker(tid, link, metadata):
    try:
        with _ARCHIVE_LOCK:
            if tid in _ARCHIVE_TASKS:
                _ARCHIVE_TASKS[tid]["status"] = "processing"
        result = _archive_upload_link(link, metadata)
        with _ARCHIVE_LOCK:
            _ARCHIVE_TASKS[tid].update({
                "status": "completed" if result.get("success") else "failed",
                "result": result,
            })
    except Exception as e:
        with _ARCHIVE_LOCK:
            _ARCHIVE_TASKS[tid].update({"status": "failed", "error": str(e)[:500]})

_SETUP_HOST_RE = re.compile(r"^[A-Za-z0-9.\-]+(:\d{1,5})?$")


def _setup_base_url(host="", forwarded_proto="", is_tls=False):
    """Resolve the backend address to embed in the App setup QR code.

    PUBLIC_BASE_URL wins when configured (public/reverse-proxy deployments);
    otherwise fall back to the request Host so a LAN-only deployment still
    produces a scannable QR code.
    """
    if PUBLIC_BASE_URL:
        return PUBLIC_BASE_URL
    host = (host or "").strip()
    if not _SETUP_HOST_RE.match(host):
        return ""
    proto = (forwarded_proto or "").split(",")[0].strip().lower()
    if proto not in ("http", "https"):
        proto = "https" if is_tls else "http"
    return proto + "://" + host


def app_setup_payload(base_url):
    """Build the `nasmonitor://setup` URI the App scans.

    Kept separate from rendering so the payload can be asserted without the
    optional qrcode dependency.
    """
    if not base_url:
        raise RuntimeError("无法确定后端地址，请配置 PUBLIC_BASE_URL")
    if not APP_API_TOKEN:
        raise RuntimeError("APP_API_TOKEN 未配置，无法生成扫码配置")
    return "nasmonitor://setup?url=%s&token=%s" % (
        urllib.parse.quote(base_url, safe=""), urllib.parse.quote(APP_API_TOKEN, safe=""))


def app_setup_qr(host="", forwarded_proto="", is_tls=False):
    """Render the App setup QR code as inline SVG.

    The token is only ever embedded inside the QR image; it is never returned
    as plain text, so it does not land in the page DOM or browser devtools.
    """
    base_url = _setup_base_url(host, forwarded_proto, is_tls)
    payload = app_setup_payload(base_url)
    try:
        import qrcode
        import qrcode.image.svg
    except ImportError as exc:  # pragma: no cover - 依赖缺失时的明确降级
        raise RuntimeError("缺少 qrcode 依赖，无法生成二维码") from exc
    image = qrcode.make(payload, image_factory=qrcode.image.svg.SvgPathImage, box_size=10, border=2)
    buffer = io.BytesIO()
    image.save(buffer)
    return {"url": base_url, "svg": buffer.getvalue().decode("utf-8"),
            "generated_at": datetime.now().isoformat()}


def _consume_web_bootstrap_code(code):
    """Exchange a short-lived one-time code for the separate Web bearer token."""
    global _WEB_BOOTSTRAP_USED
    if not (WEB_API_TOKEN and WEB_BOOTSTRAP_CODE and WEB_BOOTSTRAP_EXPIRES_AT):
        return False
    if time.time() >= WEB_BOOTSTRAP_EXPIRES_AT:
        return False
    with _WEB_BOOTSTRAP_LOCK:
        if _WEB_BOOTSTRAP_USED or time.time() >= WEB_BOOTSTRAP_EXPIRES_AT:
            return False
        if not secrets.compare_digest(code or "", WEB_BOOTSTRAP_CODE):
            return False
        _WEB_BOOTSTRAP_USED = True
        return True


def _build_overview():
    registry = _load_server_registry()
    urls = {"nas": NAS_GLANCES, **{k: v["glances_url"] for k, v in registry.items()}}
    with ThreadPoolExecutor(max_workers=9) as pool:
        tasks = {host: pool.submit(get_glances, url) for host, url in urls.items()}
        services = {host: pool.submit(get_core_services, host) for host in ("nas", "aliyun") if host in urls}
        router_task = pool.submit(get_router_status)
        ip_task = pool.submit(get_public_ip)
        latency_tasks = {host: pool.submit(ping_host, config["mesh_ip"]) for host, config in registry.items()}
        def take(task, default=None):
            try:
                return task.result()
            except Exception:
                return default
        statuses = {}
        for host, task in tasks.items():
            data = take(task) or {}
            config = registry.get(host, {"mesh_ip": NAS_MESH_IP or NAS_HOST})
            statuses[host] = {
                "status": "up" if data else "unknown",
                "mesh_ip": config.get("mesh_ip"),
                "public_ip": take(ip_task) if host == "nas" else config.get("public_ip"),
                "cpu": data.get("cpu", {}).get("percent", 0),
                "memory": data.get("memory", {}).get("percent", 0),
                "disks": data.get("disks", []), "load": data.get("load", {}),
                "uptime": data.get("uptime", ""), "network": data.get("network_summary", {}),
                "core_services": take(services[host], {}) if host in services else {},
            }
        router = take(router_task) or {"status": "unknown"}
        latency = {host: take(task) for host, task in latency_tasks.items()}
    with _server_registry_lock:
        current = _load_server_registry()
        statuses = {k: v for k, v in statuses.items() if k == "nas" or k in current}
        result = {**statuses, "timestamp": datetime.now().isoformat(), "router": router,
                  "servers": [{**entry, "metrics": statuses.get(entry["id"], {"status": "unknown"})}
                              for entry in server_entries()],
                  "latency": {"nas": 0.1, **{k: v for k, v in latency.items() if k in current}},
                  "alerts": build_alerts({}, {}, statuses)}
        for host, data in statuses.items():
            if data["status"] != "up":
                continue
            network = data["network"]
            record_trend(host, data["cpu"], data["memory"],
                         data["disks"][0].get("percent", 0) if data["disks"] else 0,
                         network.get("rx_bps", 0), network.get("tx_bps", 0))
        return result


class APIHandler(BaseHTTPRequestHandler):
    def _authorized(self):
        """Require a configured Bearer token for every monitor endpoint."""
        auth = self.headers.get("Authorization", "")
        prefix = "Bearer "
        if not auth.startswith(prefix):
            return False
        supplied = auth[len(prefix):].strip()
        # 常量时间比较；Web 令牌与 App 令牌可独立轮换。
        configured = (APP_API_TOKEN, WEB_API_TOKEN)
        return any(value and secrets.compare_digest(supplied, value) for value in configured)

    def _static(self, path):
        """Serve the bundled web UI (no auth: it is a shell that then asks for the token).

        Only a fixed allowlist of files next to api_server.py is reachable, and the
        resolved path must stay inside WEB_DIR so '..' traversal cannot escape.
        """
        name = {
            "": "index.html",
            "/": "index.html",
            "/index.html": "index.html",
            "/bootstrap.html": "bootstrap.html",
            "/app.js": "app.js",
            "/style.css": "style.css",
        }.get(path)
        if name is None and path.startswith("/web/"):
            name = path[len("/web/"):]
        if not name or "/" in name or "\\" in name or name.startswith("."):
            return False
        target = (WEB_DIR / name).resolve()
        try:
            target.relative_to(WEB_DIR.resolve())
        except ValueError:
            return False
        if not target.is_file():
            return False
        ctype = {
            ".html": "text/html; charset=utf-8",
            ".js": "application/javascript; charset=utf-8",
            ".css": "text/css; charset=utf-8",
            ".svg": "image/svg+xml",
            ".png": "image/png",
            ".ico": "image/x-icon",
        }.get(target.suffix, "application/octet-stream")
        data = target.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        self.wfile.write(data)
        return True

    def _json(self, data, code=200, headers=None):
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(json.dumps(data, ensure_ascii=False).encode())

    def _gateway_error(self, error):
        if isinstance(error, gateway_api.GatewayRateLimited):
            return self._json({"error": str(error), "code": "gateway_rate_limited",
                               "retry_after": error.retry_after}, 429,
                              {"Retry-After": str(error.retry_after)})
        return self._json({"error": str(error)}, 502)

    def _panel_token_ok(self):
        """MetaCubeXD assets are loaded by the WebView without our Bearer header.
        Accept either a short-lived signed panel cookie or the token query parameter."""
        for part in (self.headers.get("Cookie") or "").split(";"):
            name, _, value = part.strip().partition("=")
            if name == "nm_panel" and value:
                if self._panel_cookie_valid(value):
                    return True
        query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
        supplied = (query.get("token") or [""])[0]
        configured = (APP_API_TOKEN, WEB_API_TOKEN)
        return any(value and secrets.compare_digest(supplied, value) for value in configured)

    def _panel_cookie_valid(self, value):
        parts = value.split(".")
        if len(parts) != 4 or parts[0] != "v1":
            return False
        issued, instance, signature = parts[1], parts[2], parts[3]
        try:
            age = time.time() - float(issued)
        except ValueError:
            return False
        if age < 0 or age >= 3600 or instance not in mihomo_api.INSTANCES:
            return False
        expected = hmac.new(APP_API_TOKEN.encode(), f"v1.{issued}.{instance}".encode(),
                            hashlib.sha256).hexdigest()
        return secrets.compare_digest(signature, expected)

    def _panel_cookie_value(self, instance):
        issued = f"{time.time():.0f}"
        signature = hmac.new(APP_API_TOKEN.encode(), f"v1.{issued}.{instance}".encode(),
                             hashlib.sha256).hexdigest()
        return f"v1.{issued}.{instance}.{signature}"

    def _panel_instance(self):
        query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
        requested = (query.get("instance") or [""])[0] or self.headers.get("X-Mihomo-Instance", "")
        if requested in mihomo_api.INSTANCES:
            return requested
        for part in (self.headers.get("Cookie") or "").split(";"):
            name, _, value = part.strip().partition("=")
            if name == "nm_panel" and self._panel_cookie_valid(value):
                instance = value.split(".")[2]
                return mihomo_api.panel_instance(instance)
        return "core"

    def _panel_bootstrap(self):
        """Serve the MetaCubeXD shell; auth then rides on an HttpOnly cookie."""
        instance = self._panel_instance()
        query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
        # 不能依赖 302 后 WebView 保留 Set-Cookie：旧 WebView 会丢掉重定向里的 cookie。
        # 直接返回首屏，同时下发 cookie，并在页面内把 token 从地址栏抹掉。
        has_token = bool((query.get("token") or [""])[0])
        try:
            status, headers, body = mihomo_api.panel_fetch("/index.html")
        except mihomo_api.MihomoError as exc:
            return self._json({"error": str(exc)}, 502)
        if status != 200:
            return self._json({"error": "管理面板暂时不可用"}, 502)
        host = self.headers.get("X-Forwarded-Host") or self.headers.get("Host") or ""
        data = mihomo_api.panel_bootstrap_html(body.decode("utf-8", "replace"), host).encode()
        if has_token:
            clean = "/panel/index.html?instance=" + urllib.parse.quote(instance)
            data = data.replace(b"</head>", (
                "<script>try{history.replaceState(null,'','" + clean + "')}catch(e){}</script></head>"
            ).encode(), 1)
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        # Cookie 只在面板路径生效，避免把访问口令暴露在资源 URL 或日志里。
        self.send_header("Set-Cookie",
                         f"nm_panel={self._panel_cookie_value(instance)}; Path=/; HttpOnly; SameSite=Strict")
        self.end_headers()
        self.wfile.write(data)

    def _panel_proxy(self, path, method):
        """Transparently proxy MetaCubeXD static assets and API calls."""
        length = int(self.headers.get("Content-Length", "0") or 0)
        if length > 65536:
            return self._json({"error": "请求过大"}, 413)
        body = self.rfile.read(length) if length else None
        try:
            status, headers, data = mihomo_api.panel_fetch(path, method, body)
        except mihomo_api.MihomoError as exc:
            return self._json({"error": str(exc)}, 502)
        if path == "/config.js" and status == 200:
            # 保留端口，否则面板探测会落到 80 端口。
            host = self.headers.get("X-Forwarded-Host") or self.headers.get("Host") or ""
            data = mihomo_api.panel_config_js(data.decode("utf-8", "replace"), host).encode()
        self.send_response(status)
        for name in ("Location", "ETag", "Last-Modified"):
            value = headers.get(name)
            if value:
                self.send_header(name, value)
        self.send_header("Content-Type", headers.get("Content-Type", "application/octet-stream"))
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def _panel_api(self, path, method):
        """Same-origin control API for MetaCubeXD; the secret stays server-side."""
        length = int(self.headers.get("Content-Length", "0") or 0)
        if length > 65536:
            return self._json({"error": "请求过大"}, 413)
        body = None
        if length:
            try:
                body = json.loads(self.rfile.read(length).decode("utf-8") or "{}")
            except ValueError:
                return self._json({"error": "请求格式无效"}, 400)
        instance = self.headers.get("X-Mihomo-Instance") or self._panel_instance()
        if instance not in mihomo_api.INSTANCES:
            return self._json({"error": "未知代理实例"}, 404)
        try:
            if path == "/traffic":
                traffic = mihomo_api.traffic_sample(instance)
                return self._json({
                    "up": int(traffic.get("up") or 0),
                    "down": int(traffic.get("down") or 0),
                    "upTotal": int(traffic.get("upTotal") or 0),
                    "downTotal": int(traffic.get("downTotal") or 0),
                })
            if path == "/connections":
                return self._json(mihomo_api.panel_control(instance, "GET", "/connections"))
            if path == "/memory":
                return self._json({"inuse": 0, "oslimit": 0})
            if path in ("/proxies", "/providers/proxies", "/configs", "/rules", "/version"):
                return self._json(mihomo_api.panel_control(instance, "GET", path))
            if method == "GET" and (path.startswith("/proxies/") or path.startswith("/providers/")):
                return self._json(mihomo_api.panel_control(instance, "GET", path))
            if method == "PUT" and re.fullmatch(r"/proxies/[^/]+", path):
                return self._json(mihomo_api.panel_control(instance, "PUT", path, body or {}))
            if method == "PUT" and re.fullmatch(r"/configs", path):
                return self._json(mihomo_api.panel_control(instance, "PUT", path, body or {}))
            if method == "DELETE" and re.fullmatch(r"/connections/[^/]+", path):
                return self._json(mihomo_api.panel_control(instance, "DELETE", path))
            if method == "DELETE" and path == "/connections":
                return self._json(mihomo_api.panel_control(instance, "DELETE", path))
            return self._json({"error": "面板接口不在允许范围"}, 404)
        except mihomo_api.MihomoError as exc:
            return self._json({"error": str(exc)}, 502)

    def _panel_ws(self, path):
        """Relay a WebSocket upgrade to Mihomo, injecting the server-side secret.

        MetaCubeXD streams /traffic, /memory, /connections and /logs over WS.
        Without this the panel renders but its live numbers never move.
        """
        instance = self._panel_instance()
        if instance not in mihomo_api.INSTANCES:
            return self._json({"error": "未知代理实例"}, 404)
        upstream = urllib.parse.urlparse(mihomo_api.INSTANCES[instance]["api"])
        host = upstream.hostname or ""
        port = upstream.port or (443 if upstream.scheme == "https" else 80)
        query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
        forwarded = {k: v for k, v in query.items() if k not in ("token", "instance")}
        # 上游鉴权用 Authorization 头，URL 里不再带 token，避免写进上游日志。
        target = path + ("?" + urllib.parse.urlencode(forwarded, doseq=True) if forwarded else "")
        secret = mihomo_api._secret(instance)
        try:
            sock = socket.create_connection((host, port), timeout=8)
            sock.settimeout(None)
            headers = [
                f"GET {target} HTTP/1.1",
                f"Host: {host}:{port}",
                "Upgrade: websocket",
                "Connection: Upgrade",
                f"Sec-WebSocket-Key: {self.headers.get('Sec-WebSocket-Key', '')}",
                f"Sec-WebSocket-Version: {self.headers.get('Sec-WebSocket-Version', '13')}",
            ]
            if self.headers.get("Sec-WebSocket-Protocol"):
                headers.append(f"Sec-WebSocket-Protocol: {self.headers['Sec-WebSocket-Protocol']}")
            if secret:
                headers.append(f"Authorization: Bearer {secret}")
            sock.sendall(("\r\n".join(headers) + "\r\n\r\n").encode())
            response = b""
            while b"\r\n\r\n" not in response:
                chunk = sock.recv(4096)
                if not chunk:
                    break
                response += chunk
            if b" 101 " not in response.split(b"\r\n", 1)[0]:
                sock.close()
                return self._json({"error": "实时通道不可用"}, 502)
            # 仅本次升级使用 HTTP/1.1；其余响应仍是 1.0，避免 keep-alive 分帧问题。
            self.protocol_version = "HTTP/1.1"
            self.send_response(101, "Switching Protocols")
            for line in response.split(b"\r\n")[1:]:
                if b":" in line:
                    name, _, value = line.partition(b":")
                    key = name.strip().lower()
                    if key in (b"upgrade", b"connection", b"sec-websocket-accept", b"sec-websocket-protocol"):
                        self.send_header(name.decode(), value.strip().decode())
            self.end_headers()
            self.wfile.flush()
            client = self.connection
            sockets = [client, sock]
            while True:
                readable, _, errored = select.select(sockets, [], sockets, 30)
                if errored:
                    break
                if not readable:
                    continue
                for current in readable:
                    data = current.recv(65536)
                    if not data:
                        return
                    (sock if current is client else client).sendall(data)
        except Exception:
            try:
                sock.close()
            except Exception:
                pass

    def do_GET(self):
        path = self.path.split('?')[0].rstrip('/')
        if path == '/health' or path == '/api/health':
            return self._json({"ok": True, "service": "nas-monitor-api", "time": datetime.now().isoformat()})
        if path.startswith(('/proxies', '/traffic', '/connections', '/memory', '/configs',
                            '/rules', '/version')):
            if not self._panel_token_ok() and not self._authorized():
                return self._json({"error": "unauthorized"}, 401)
            if self.headers.get("Upgrade", "").lower() == "websocket":
                return self._panel_ws(path)
            return self._panel_api(path, "GET")
        # MetaCubeXD shell: same-origin, token passed as a query parameter and
        # stripped from the URL by a small bootstrap page before the SPA loads.
        if path in ('/panel', '/panel/index.html', '/panel.html'):
            if not self._authorized() and not self._panel_token_ok():
                return self._json({"error": "unauthorized"}, 401)
            return self._panel_bootstrap()
        # MetaCubeXD 使用绝对 /_nuxt/ 资源路径，必须原样反代，不能挂子路径。
        if path.startswith('/_nuxt/') or path.startswith('/_fonts/') or path in ('/config.js', '/polyfill.js',
                                                  '/favicon.svg',
                                                  '/manifest.webmanifest',
                                                  '/apple-touch-icon-180x180.png'):
            if not self._authorized() and not self._panel_token_ok():
                return self._json({"error": "unauthorized"}, 401)
            return self._panel_proxy(path, "GET")
        if path.startswith('/panel/') or path.startswith('/api/mihomo/panel/'):
            if not self._authorized() and not self._panel_token_ok():
                return self._json({"error": "unauthorized"}, 401)
            upstream = path[len('/api/mihomo'):] if path.startswith('/api/mihomo/panel/') else path
            # /panel/config.js 在上游是根路径 /config.js；其余 /panel/* 保持原样。
            if upstream == '/panel/config.js':
                upstream = '/config.js'
            return self._panel_proxy(upstream, "GET")
        # 内网 Web 端：先给静态壳，页面自己再拿口令调 /api/*
        if not path.startswith('/api'):
            if self._static(path):
                return
            return self._json({"error": "not found"}, 404)
        if not self._authorized():
            return self._json({"error": "unauthorized"}, 401)
        remotes = _remote_glances_map()
        target = None
        if path in ("/api/aliyun", "/api/tencent"):
            target = path.rsplit("/", 1)[-1]
        elif path.startswith("/api/security/"):
            target = "nas" if path == "/api/security/live" else path.split("/")[3]
        elif path.startswith(("/api/trends/temperature/", "/api/container/stats/")):
            target = path.rsplit("/", 1)[-1]
        elif path.startswith("/api/trends/"):
            target = path.split("/")[3]
        elif path.startswith("/api/container/") and len(path.split("/")) >= 5:
            target = path.split("/")[3]
        if target and target != "nas" and target not in remotes:
            return self._json({"error": "服务器不存在或已删除"}, 404)
        if path == "/api/servers":
            return self._json({"servers": server_entries()})
        if path.startswith("/api/servers/") and path.endswith("/detail"):
            server_id = path.split("/")[3]
            if server_id not in remotes:
                return self._json({"error": "服务器不存在或已删除"}, 404)
            system = get_glances(remotes[server_id])
            if not system:
                return self._json({"error": "Glances 不可达"}, 503)
            return self._json({"system": system, "services": {}})
        if path == '/api/knowledge/archive/status':
            query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            task_id = (query.get('task_id') or [''])[0]
            with _ARCHIVE_LOCK:
                task = dict(_ARCHIVE_TASKS.get(task_id) or {})
            return self._json(task if task else {"error": "task not found"}, 200 if task else 404)
        if path == '/api/app-setup-qr':
            try:
                return self._json(app_setup_qr(
                    self.headers.get('Host', ''),
                    self.headers.get('X-Forwarded-Proto', ''),
                    isinstance(self.connection, ssl.SSLSocket)))
            except Exception as e:
                return self._json({"error": str(e)}, 503)
        if path == '/api/ping':
            return self._json({"ok": True, "time": datetime.now().isoformat()})

        elif path == '/api/newapi/models':
            try:
                return self._json(gateway_api.list_models())
            except Exception as e:
                return self._gateway_error(e)

        elif path == '/api/newapi/balance':
            try:
                return self._json(gateway_api.balance_snapshot())
            except Exception as e:
                return self._json({"error": str(e)}, 502)

        elif path.startswith('/api/newapi/balance/pipio/'):
            try:
                name = urllib.parse.unquote(path.rsplit('/', 1)[-1])
                return self._json(gateway_api.pipio_usage_detail(name))
            except Exception as e:
                return self._json({"error": str(e)}, 502)

        elif path == '/api/newapi/overview':
            try:
                return self._json(gateway_api.overview())
            except Exception as e:
                return self._gateway_error(e)

        elif path == '/api/newapi/performance':
            try:
                return self._json({"models": gateway_api.performance(), "window": "24h",
                                   "generated_at": datetime.now().isoformat()})
            except Exception as e:
                return self._gateway_error(e)

        elif path == '/api/newapi/model-usage':
            try:
                return self._json(gateway_api.model_usage())
            except Exception as e:
                return self._gateway_error(e)

        elif path == '/api/newapi/probe':
            try:
                return self._json(gateway_api.probe())
            except Exception as e:
                return self._json({"error": str(e)}, 502)

        elif path == '/api/newapi/affinity':
            try:
                return self._json(gateway_api.affinity_state())
            except Exception as e:
                return self._gateway_error(e)

        elif path == '/api/newapi/data-source-meta':
            try:
                return self._json(gateway_api.data_source_meta())
            except Exception as e:
                return self._json({"error": str(e)}, 502)

        elif path == '/api/newapi/routing-policy':
            try:
                return self._json(gateway_api.routing_policy_state())
            except Exception as e:
                return self._json({"error": str(e)}, 502)

        elif path == '/api/newapi/log-tokens':
            try:
                return self._json(gateway_api.log_tokens())
            except Exception as e:
                return self._gateway_error(e)

        elif path == '/api/newapi/logs':
            try:
                query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
                return self._json(gateway_api.logs(query.get("p", [0])[0], query.get("page_size", [30])[0], query.get("token", [""])[0]))
            except Exception as e:
                return self._gateway_error(e)

        elif path == '/api/airport-traffic':
            return self._json(get_airport_traffic())

        elif path == '/api/mihomo/overview':
            try:
                return self._json({"instances": mihomo_api.all_instances()})
            except Exception as exc:
                return self._json({"error": str(exc)}, 502)

        elif path == '/api/mihomo/airports':
            try:
                return self._json({"instances": mihomo_api.airports()})
            except Exception as exc:
                return self._json({"error": str(exc)}, 502)

        elif path == '/api/mihomo/panel-url':
            token = self.headers.get("X-API-Token") or ""
            if not token:
                auth = self.headers.get("Authorization", "")
                token = auth[len("Bearer "):].strip() if auth.startswith("Bearer ") else ""
            query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            instance = (query.get("instance") or ["core"])[0]
            if instance not in mihomo_api.INSTANCES:
                return self._json({"error": "未知代理实例"}, 404)
            # v2 没有独立面板，复用 core 面板但把控制 API 指向 v2 实例。
            return self._json({"url": "/panel/index.html?token=" + urllib.parse.quote(token)
                                     + "&instance=" + urllib.parse.quote(instance)})

        elif path == '/api/mihomo/instances/connections':
            query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            instance = (query.get("instance") or ["core"])[0]
            try:
                limit = int((query.get("limit") or ["100"])[0])
            except ValueError:
                limit = 100
            try:
                return self._json(mihomo_api.connections(instance, limit))
            except mihomo_api.MihomoError as exc:
                return self._json({"error": str(exc)}, 502)

        elif path == '/api/mihomo/instances/rules':
            query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            instance = (query.get("instance") or ["core"])[0]
            try:
                return self._json(mihomo_api.rules(instance))
            except mihomo_api.MihomoError as exc:
                return self._json({"error": str(exc)}, 502)

        elif path == '/api/mihomo/instances/configs':
            query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            instance = (query.get("instance") or ["core"])[0]
            try:
                return self._json(mihomo_api.configs(instance))
            except mihomo_api.MihomoError as exc:
                return self._json({"error": str(exc)}, 502)

        elif path == '/api/mihomo/instances/providers':
            query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            instance = (query.get("instance") or ["core"])[0]
            try:
                return self._json(mihomo_api.providers(instance))
            except mihomo_api.MihomoError as exc:
                return self._json({"error": str(exc)}, 502)

        elif (match := re.fullmatch(r"/api/mihomo/instances/([^/]+)/proxies/([^/]+)/delay", path)):
            try:
                return self._json(mihomo_api.test_node(match.group(1), match.group(2)))
            except mihomo_api.MihomoError as exc:
                return self._json({"error": str(exc)}, 502)

        elif path == '/api/overview':
            now_mono = time.monotonic()
            with _overview_lock:
                data = _overview_cache["data"]
                age = now_mono - _overview_cache["fetched_mono"] if data is not None else None
            if data is not None and age < _OVERVIEW_TTL:
                return self._json(data)
            if data is not None:
                # 已有成功快照时始终先返回；离开 App 超过 5 分钟不应触发 503。
                # 保留原 timestamp 并标记年龄，刷新失败沿用已有退避策略。
                _overview_request_refresh()
                return self._json(data, headers={"X-Cache-Status": "stale", "X-Data-Age": str(int(age))})
            # 冷启动：只允许一个构建，其余请求等待有限时长。
            _overview_request_refresh()
            if _overview_built.wait(timeout=30):
                with _overview_lock:
                    data = _overview_cache["data"]
                if data is not None:
                    return self._json(data)
            return self._json({"error": "overview not ready", "last_error": _overview_cache.get("last_error")}, 503)

        elif path == '/api/nas':
            now = time.time()
            if _nas_cache['data'] is not None and now - _nas_cache['ts'] < _NAS_TTL:
                return self._json(_nas_cache['data'])
            ha = get_ha_sensors()
            system = get_glances(NAS_GLANCES)
            # 卷列表以现场挂载为准，新增/删除卷自动跟随，HA 仅补充容量文案。
            ha["volumes"] = merge_dynamic_volumes(ha.get("volumes"), system.get("disks"))
            result = {
                "system": system,
                "docker": get_docker_stats(),
                "services": {},
                "ha": ha,
                "kuma": get_kuma_status(ha),
            }
            # 
            for name, url in HEALTH_CHECKS["nas"].items():
                if name == "mihomo-proxy":
                    result["services"][name] = check_tcp(NAS_HOST, 8890)
                elif name == "safeline":
                    result["services"][name] = check_ssl(url)
                elif name == "fan-dashboard":
                    # Dashboard rejects HEAD with 501 but GET=401 is its normal auth wall.
                    result["services"][name] = check_service(url, token="probe")
                else:
                    tok = NEW_API_KEY if name == "new-api" else None
                    result["services"][name] = check_service(url, token=tok)
            # 
            # 硬盘温度（从 HA 获取）
            try:
                _hdd = ha.get('hdd_temps', {})
                result['hdd_temps'] = _hdd
            except: pass
            result["latency"] = {"aliyun": ping_host(ALIYUN_HOST)} if "aliyun" in remotes and ALIYUN_HOST else {}
            _nas_cache["data"] = result
            _nas_cache["ts"] = time.time()
            return self._json(result)


        elif path == '/api/containers':
            query = self.path.split('?')
            host = 'nas'
            if len(query) > 1:
                for p in query[1].split('&'):
                    kv = p.split('=')
                    if len(kv) == 2 and kv[0] == 'host':
                        host = kv[1]
            if host not in ("nas", *remotes):
                return self._json({"error": "unknown host"}, 400)
            if host in remotes:
                try:
                    r = urllib.request.urlopen(remotes[host] + '/api/4/containers', timeout=10)
                    raw = json.loads(r.read().decode())
                    items = []
                    for c in raw:
                        items.append({'name': c.get('name', ''), 'image': str(c.get('image', '')), 'status': c.get('status', ''), 'state': c.get('status', ''), 'uptime': c.get('uptime', ''), 'host': host})
                    return self._json({'total': len(items), 'running': sum(1 for i in items if i['status'] == 'running'), 'containers': items})
                except Exception as e:
                    return self._json({'error': str(e)}, 500)
            else:
                return self._json(get_docker_stats())

        elif path.startswith('/api/container/stats/'):
            host = path.split("/")[-1]
            if host not in ("nas", *remotes):
                return self._json({"host": host, "containers": [], "error": "unknown host"}, 400)
            return self._json(get_container_stats() if host == "nas" else get_remote_container_stats(host))

        elif path.startswith('/api/container/'):
            parts = path.split("/")
            if len(parts) >= 5 and parts[3] in ("nas", *remotes):
                host = parts[3]
                name = parts[4]
                if host != "nas":
                    return self._json({"host": host, "name": name, "logs": "该主机仅提供容器清单，不提供 Docker 日志读取。"})
                lines = int(parts[5]) if len(parts) >= 6 and parts[5].isdigit() else 50
                try:
                    import http.client
                    conn = http.client.HTTPConnection("localhost", timeout=20)
                    conn.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                    conn.sock.connect("/var/run/docker.sock")
                    conn.request("GET", f"/containers/{name}/logs?stderr=1&stdout=1&tail={lines}")
                    resp = conn.getresponse()
                    raw = resp.read().decode("utf-8", errors="replace")
                    clean = "\n".join([l[8:] if len(l) > 8 else l for l in raw.split("\n")])
                    conn.close()
                    return self._json({"host": "nas", "name": name, "lines": len(clean.splitlines()), "logs": clean[-10000:]})
                except Exception as e:
                    return self._json({"error": str(e)}, 500)
            if len(parts) >= 4:
                # 旧版 App 路径只允许访问 NAS 日志。
                name = parts[3]
                lines = int(parts[4]) if len(parts) >= 5 and parts[4].isdigit() else 50
                try:
                    import http.client
                    conn = http.client.HTTPConnection("localhost", timeout=20)
                    conn.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                    conn.sock.connect("/var/run/docker.sock")
                    conn.request("GET", f"/containers/{name}/logs?stderr=1&stdout=1&tail={lines}")
                    resp = conn.getresponse()
                    raw = resp.read().decode("utf-8", errors="replace")
                    clean = "\n".join([l[8:] if len(l) > 8 else l for l in raw.split("\n")])
                    conn.close()
                    return self._json({"host": "nas", "name": name, "lines": len(clean.splitlines()), "logs": clean[-10000:]})
                except Exception as e:
                    return self._json({"error": str(e)}, 500)
            return self._json({"error": "bad path"}, 400)

        elif path.startswith('/api/trends/temperature/'):
            host = path.split("/")[-1]
            try:
                query = self.path.split("?", 1)[1] if "?" in self.path else ""
                minutes = int(next((part.split("=", 1)[1] for part in query.split("&") if part.startswith("minutes=")), "60"))
            except (ValueError, IndexError):
                minutes = 60
            minutes = max(1, min(minutes, 1440))
            if host not in ("nas", *remotes):
                return self._json({"error": "unknown host"}, 400)
            return self._json({"host": host, "minutes": minutes, "disks": get_temperature_trends(host, minutes)})

        elif path.startswith('/api/trends/'):
            parts = path.split("/")
            host = parts[3] if len(parts) >= 4 else "nas"
            minutes = 60
            if len(parts) >= 5:
                try:
                    minutes = int(parts[4])
                except ValueError:
                    pass
            minutes = max(1, min(minutes, 1440))
            return self._json({"host": host, "minutes": minutes, "points": get_trends(host, minutes)})

        elif path == '/api/router':
            return self._json(get_router_status())

        elif path == '/api/router/speedtest':
            """触发测速并返回（不阻塞，异步）"""
            return self._json({"error": "use /api/router/speedtest/start and /api/router/speedtest/result"})

        elif path == '/api/router/speedtest/start':
            if _speedtest_lock.get('running', False):
                return self._json({"error": "测速正在进行中，请等待完成"})
            query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            interface = (query.get('interface') or ['wan1'])[0]
            if not re.fullmatch(r"wan\d+", interface):
                return self._json({"error": "不支持的 WAN 口：%s" % interface})
            _speedtest_lock['running'] = True
            """触发测速（异步，立即返回）"""
            import ssl as sslmod, threading
            def _run():
                try:
                    ctx = sslmod._create_unverified_context()
                    md5pw = hashlib.md5(IKUAI_PASS.encode()).hexdigest()
                    data = json.dumps({"username": "admin", "passwd": md5pw}).encode()
                    req = urllib.request.Request(ROUTER_BASE + "/Action/login", data=data, method="POST")
                    req.add_header("Content-Type", "application/json; charset=utf-8")
                    with urllib.request.urlopen(req, timeout=5, context=ctx) as r:
                        cookies = r.headers.get_all("Set-Cookie")
                        if not cookies: return
                        cookie = "; ".join(c.split(";")[0] for c in cookies)
                    def ik_call(func_name, action="show", params=None):
                        if params is None: params = {}
                        p = json.dumps({"action": action, "func_name": func_name, "param": params}).encode()
                        r = urllib.request.Request(ROUTER_BASE + "/Action/call", data=p, method="POST")
                        r.add_header("Content-Type", "application/json; charset=utf-8")
                        r.add_header("Cookie", cookie)
                        with urllib.request.urlopen(r, timeout=10, context=ctx) as resp:
                            return json.loads(resp.read().decode("utf-8", errors="replace"))
                    ik_call("speedtest", "start", {"interface": interface})
                    import time
                    time.sleep(60)
                    ik_call("speedtest", "stop")
                except: pass
                finally: _speedtest_lock['running'] = False
            threading.Thread(target=_run, daemon=True).start()
            return self._json({"message": "测速已启动（%s），约60秒后查看结果" % interface})

        elif path == '/api/router/speedtest/result':
            """获取测速结果"""
            query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            interface = (query.get('interface') or [''])[0]
            import ssl as sslmod
            ctx = sslmod._create_unverified_context()
            md5pw = hashlib.md5(IKUAI_PASS.encode()).hexdigest()
            data = json.dumps({"username": "admin", "passwd": md5pw}).encode()
            req = urllib.request.Request(ROUTER_BASE + "/Action/login", data=data, method="POST")
            req.add_header("Content-Type", "application/json; charset=utf-8")
            try:
                with urllib.request.urlopen(req, timeout=5, context=ctx) as r:
                    cookies = r.headers.get_all("Set-Cookie")
                    if not cookies: return self._json({"error": "login failed"})
                    cookie = "; ".join(c.split(";")[0] for c in cookies)
            except Exception as e:
                return self._json({"error": str(e)[:60]})
            def ik_call(func_name, action="show", params=None):
                if params is None: params = {}
                p = json.dumps({"action": action, "func_name": func_name, "param": params}).encode()
                r = urllib.request.Request(ROUTER_BASE + "/Action/call", data=p, method="POST")
                r.add_header("Content-Type", "application/json; charset=utf-8")
                r.add_header("Cookie", cookie)
                with urllib.request.urlopen(r, timeout=5, context=ctx) as resp:
                    return json.loads(resp.read().decode("utf-8", errors="replace"))
            st = ik_call("speedtest")
            sd = (st.get("Data") or {}).get("data") or {}
            # 指定接口优先；否则按实际返回的 wanN 顺序取第一个有效结果
            if interface:
                wanted = [interface]
            else:
                wanted = sorted([k for k in sd.keys() if re.fullmatch(r"wan\d+", k)], key=lambda k: int(k[3:]))
            for wname in wanted:
                if wname in sd and sd[wname].get("download", "--") != "--":
                    dw = float(sd[wname].get("download", 0) or 0)
                    up = float(sd[wname].get("upload", 0) or 0)
                    return self._json({"download": round(dw/1000,1), "upload": round(up/1000,1), "unit": "Mbps", "interface": wname})
            gs = st.get("Data", {}).get("global_status_msg", "未测速")
            return self._json({"status": gs, "interface": interface or ""})

        elif path == '/api/router/speedtest/history':
            query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            interface = (query.get('interface') or [''])[0]
            try:
                limit = int((query.get('limit') or ['30'])[0])
            except (ValueError, TypeError):
                limit = 30
            try:
                return self._json(speedtest_collector.history(interface or None, limit))
            except Exception as e:
                return self._json({"error": str(e)[:200]}, 500)

        elif path == '/api/router/speedtest/latest':
            try:
                return self._json(speedtest_collector.latest())
            except Exception as e:
                return self._json({"error": str(e)[:200]}, 500)

        elif path == '/api/router/speedtest/run':
            query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            interface = (query.get('interface') or ['wan1'])[0]
            if interface not in speedtest_collector.WAN_IP:
                return self._json({"error": "未配置测速源 IP，不支持该 WAN 口：%s" % interface})
            if speedtest_collector.is_running():
                return self._json({"error": "测速正在进行中，请等待完成"})
            def _run_speedtest_job(name):
                try:
                    speedtest_collector.run_speedtest(name)
                except Exception:
                    pass
            Thread(target=_run_speedtest_job, args=(interface,), daemon=True, name=f"speedtest-{interface}").start()
            return self._json({"success": True, "interface": interface, "status": "running", "message": "测速已启动，约2分钟后查看结果"})


        elif path in ('/api/security/nas/live', '/api/security/live'):
            try:
                import http.client as _http_client
                _conn = _http_client.HTTPConnection(NAS_HOST, int(os.environ.get('OPENWRT_HA_HOST_PORT', '8789')), timeout=8)
                _conn.request('GET', '/api/stats?minutes=5')
                _resp = _conn.getresponse()
                _data = json.loads(_resp.read().decode('utf-8', errors='replace'))
                _conn.close()
                return self._json({
                    'hosts': _data.get('hosts', []),
                    'rows': _data.get('rows', [])[:10],
                    'hourly': _data.get('hourly', []),
                    'statuses': _data.get('statuses', []),
                    'total': _data.get('total', 0),
                    'unique': _data.get('unique', 0)
                })
            except Exception as _exc:
                return self._json({'error': str(_exc)}, 500)

        elif path in ('/api/security/nas', '/api/security'):
            ha = get_ha_sensors()
            safeline = ha.get("safeline", {})
            hdd_temps = ha.get("hdd_temps", {})
            return self._json({
                "host": "nas",
                "source": "nas-safeline-ha",
                "status": "ok" if "error" not in ha else "unavailable",
                "safeline": safeline,
                "hdd_temps": hdd_temps,
                "ha_status": "error" if "error" in ha else "ok",
            })

        elif path == '/api/security/aliyun':
            return self._json(get_aliyun_security())

        elif path == '/api/security/tencent':
            return self._json(get_tencent_security())

        elif path == '/api/tencent':
            if "tencent" not in remotes:
                return self._json({"error": "tencent host not configured"}, 404)
            system = get_glances(remotes["tencent"])
            if not system:
                return self._json({"system": {}, "services": {}, "error": "Glances 61208 不可达"}, 503)
            return self._json({"system": system, "services": {}, "latency": {"nas_to_tencent": ping_host(TENCENT_HOST)}})

        elif path == '/api/aliyun':
            if "aliyun" not in remotes:
                return self._json({"error": "aliyun host not configured"}, 404)
            result = {
                "system": get_glances(remotes["aliyun"]),
                "services": {},
            }
            for name, url in (HEALTH_CHECKS.get("aliyun") or {}).items():
                if name == "mihomo":
                    result["services"][name] = check_tcp(ALIYUN_HOST, 8890)
                elif name == "coraza-waf":
                    result["services"][name] = check_https_sni(ALIYUN_HOST, os.environ.get("ALIYUN_SNI_HOST", ALIYUN_HOST), int(os.environ.get("ALIYUN_SNI_PORT", "23245")), "/health")
                else:
                    result["services"][name] = check_service(url)
            result["latency"] = {"nas_to_aliyun": ping_host(ALIYUN_HOST)}
            return self._json(result)

        wb_activity = re.match(r"^/api/workbuddy/groups/([a-zA-Z0-9_-]+)/activity$", path)
        if wb_activity:
            try:
                query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
                raw_minutes = (query.get("minutes") or [""])[0]
                try:
                    minutes = int(raw_minutes) if raw_minutes else 15
                except ValueError:
                    minutes = 15
                return self._json(workbuddy_activity.collect(minutes=minutes,
                                                            group_id=wb_activity.group(1)))
            except Exception as e:
                return self._json({"error": str(e)[:300]}, 502)

        return self._json({"error": "not found"}, 404)

    def do_DELETE(self):
        panel_path = self.path.split("?", 1)[0].rstrip("/")
        if panel_path.startswith(("/connections", "/proxies", "/configs", "/rules")):
            if not self._panel_token_ok() and not self._authorized():
                return self._json({"error": "unauthorized"}, 401)
            return self._panel_api(panel_path, "DELETE")
        if not self._authorized():
            return self._json({"error": "unauthorized"}, 401)
        path = self.path.split("?", 1)[0].rstrip("/")
        if not path.startswith("/api/servers/"):
            return self._json({"error": "not found"}, 404)
        server_id = path.rsplit("/", 1)[-1]
        try:
            delete_server(server_id)
            return self._json({"deleted": server_id, "servers": server_entries()})
        except KeyError:
            return self._json({"error": "服务器不存在或已删除"}, 404)
        except ValueError as error:
            return self._json({"error": str(error)}, 400)
        except OSError:
            return self._json({"error": "删除未保存，请重试"}, 500)

    def do_PUT(self):
        path = self.path.split("?", 1)[0].rstrip("/")
        if not self._panel_token_ok() and not self._authorized():
            return self._json({"error": "unauthorized"}, 401)
        return self._panel_api(path, "PUT")

    def do_POST(self):
        path = self.path.split('?')[0].rstrip('/')
        # 此端点不接受既有 bearer；仅限短时、一次性登录码兑换 Web 令牌。
        if path == "/api/web-bootstrap":
            try:
                length = int(self.headers.get("Content-Length", "0") or 0)
                if length <= 0 or length > 1024:
                    return self._json({"error": "not found"}, 404)
                body = json.loads(self.rfile.read(length).decode("utf-8") or "{}")
                code = str(body.get("code") or "")
                if not _consume_web_bootstrap_code(code):
                    return self._json({"error": "not found"}, 404)
                return self._json({"token": WEB_API_TOKEN})
            except Exception:
                return self._json({"error": "not found"}, 404)
        if path.startswith(('/proxies', '/traffic', '/connections', '/configs', '/rules')):
            if not self._panel_token_ok() and not self._authorized():
                return self._json({"error": "unauthorized"}, 401)
            return self._panel_api(path, "POST")
        if not self._authorized():
            return self._json({"error": "unauthorized"}, 401)
        match = re.fullmatch(r"/api/mihomo/instances/([^/]+)/groups/([^/]+)/select", path)
        if match:
            try:
                length = int(self.headers.get("Content-Length", "0") or 0)
                body = json.loads(self.rfile.read(length).decode("utf-8") or "{}") if length else {}
                return self._json(mihomo_api.select_group(match.group(1), match.group(2), body.get("target")))
            except mihomo_api.MihomoError as exc:
                return self._json({"error": str(exc)}, 502)
            except ValueError as exc:
                return self._json({"error": str(exc)}, 400)
        match = re.fullmatch(r"/api/mihomo/instances/([^/]+)/connections/close-all", path)
        if match:
            try:
                return self._json(mihomo_api.close_all_connections(match.group(1)))
            except mihomo_api.MihomoError as exc:
                return self._json({"error": str(exc)}, 502)
        match = re.fullmatch(r"/api/mihomo/instances/([^/]+)/connections/([^/]+)/close", path)
        if match:
            try:
                return self._json(mihomo_api.close_connection(match.group(1), match.group(2)))
            except mihomo_api.MihomoError as exc:
                return self._json({"error": str(exc)}, 502)
        match = re.fullmatch(r"/api/mihomo/instances/([^/]+)/rules/disable", path)
        if match:
            try:
                length = int(self.headers.get("Content-Length", "0") or 0)
                body = json.loads(self.rfile.read(length).decode("utf-8") or "{}") if length else {}
                return self._json(mihomo_api.set_rule_disabled(
                    match.group(1), body.get("index"), body.get("disabled")))
            except mihomo_api.MihomoError as exc:
                return self._json({"error": str(exc)}, 502)
            except ValueError as exc:
                return self._json({"error": str(exc)}, 400)
        match = re.fullmatch(r"/api/mihomo/instances/([^/]+)/config", path)
        if match:
            try:
                length = int(self.headers.get("Content-Length", "0") or 0)
                body = json.loads(self.rfile.read(length).decode("utf-8") or "{}") if length else {}
                return self._json(mihomo_api.set_config(match.group(1), body.get("key"), body.get("value")))
            except mihomo_api.MihomoError as exc:
                return self._json({"error": str(exc)}, 502)
            except ValueError as exc:
                return self._json({"error": str(exc)}, 400)
        match = re.fullmatch(r"/api/mihomo/instances/([^/]+)/providers/([^/]+)/(update|healthcheck)", path)
        if match:
            try:
                name = urllib.parse.unquote(match.group(2))
                return self._json(mihomo_api.provider_action(match.group(1), name, match.group(3)))
            except mihomo_api.MihomoError as exc:
                return self._json({"error": str(exc)}, 502)
        container_action = re.fullmatch(r"/api/containers/([^/]+)/([^/]+)/(start|stop|restart)", path)
        if container_action:
            host, raw_name, action = container_action.groups()
            host = urllib.parse.unquote(host)
            name = urllib.parse.unquote(raw_name)
            if host != "nas":
                return self._json({"error": "仅支持管理 NAS 本机容器"}, 400)
            try:
                result = docker_container_action(name, action)
                return self._json({**result, "containers": get_docker_stats()})
            except ValueError as error:
                return self._json({"error": str(error)}, 400)
            except LookupError as error:
                return self._json({"error": str(error)}, 404)
            except Exception as error:
                return self._json({"error": f"容器操作失败：{str(error)[:300]}"}, 500)
        if path.startswith('/api/mihomo/panel/'):
            if not self._authorized() and not self._panel_token_ok():
                return self._json({"error": "unauthorized"}, 401)
            return self._panel_proxy(path[len('/api/mihomo'):], "POST")
        if path in ("/api/servers", "/api/servers/check"):
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= 4096:
                    return self._json({"error": "请求大小无效"}, 400)
                body = json.loads(self.rfile.read(length))
                if path.endswith("/check"):
                    config = validate_server(body)
                    check_server(config)
                    return self._json({"ok": True})
                return self._json(add_server(body), 201)
            except ValueError as error:
                return self._json({"error": str(error)}, 400)
            except OSError:
                return self._json({"error": "服务器配置保存失败，请重试"}, 500)
        if path == "/api/knowledge/archive/status":
            tid=(urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query).get("task_id") or [""])[0]
            with _ARCHIVE_LOCK: item=dict(_ARCHIVE_TASKS.get(tid) or {})
            return self._json(item if item else {"error":"task not found"}, 200 if item else 404)
        if path == "/api/knowledge/archive/url":
            try:
                length=min(int(self.headers.get("Content-Length", "0")), 65536)
                body=json.loads(self.rfile.read(length).decode("utf-8") or "{}")
                link=str(body.get("url") or body.get("link") or "").strip()
                parsed=urllib.parse.urlparse(link)
                if parsed.scheme not in ("http", "https") or not parsed.netloc: return self._json({"error":"url must be http(s)"},400)
                tid=uuid.uuid4().hex
                with _ARCHIVE_LOCK: _ARCHIVE_TASKS[tid]={"id":tid,"kind":"url","source":link,"status":"queued","created_at":datetime.now().isoformat()}
                Thread(target=_archive_worker,args=(tid,link,body.get("metadata") or {}),daemon=True).start()
                return self._json({"success":True,"task_id":tid,"status":"queued","workspace":ANYTHINGLLM_WORKSPACE})
            except Exception as e: return self._json({"error":str(e)},400)
        if path == "/api/knowledge/archive/file":
            try:
                import cgi
                content_type=self.headers.get("Content-Type", "")
                length=int(self.headers.get("Content-Length", "0"))
                if not content_type.lower().startswith("multipart/form-data") or length <= 0 or length > 100*1024*1024:
                    return self._json({"error":"multipart/form-data file required; max 100MB"},400)
                form=cgi.FieldStorage(fp=self.rfile, headers=self.headers, environ={"REQUEST_METHOD":"POST","CONTENT_TYPE":content_type,"CONTENT_LENGTH":str(length)})
                item=form["file"] if "file" in form else None
                if item is None or not getattr(item,"filename",""): return self._json({"error":"field file required"},400)
                filename=Path(str(item.filename)).name[:180]
                data=item.file.read(100*1024*1024+1)
                if len(data)>100*1024*1024: return self._json({"error":"file too large"},413)
                boundary="----nas-monitor-"+uuid.uuid4().hex
                mime=mimetypes.guess_type(filename)[0] or "application/octet-stream"
                meta=form.getfirst("metadata","{}") if "metadata" in form else "{}"
                parts=[("--"+boundary+"\r\n").encode(),("Content-Disposition: form-data; name=\"addToWorkspaces\"\r\n\r\n"+ANYTHINGLLM_WORKSPACE+"\r\n").encode(),("--"+boundary+"\r\n").encode(),("Content-Disposition: form-data; name=\"metadata\"\r\n\r\n"+meta+"\r\n").encode(),("--"+boundary+"\r\n").encode(),("Content-Disposition: form-data; name=\"file\"; filename=\""+filename.replace('\"','')+"\"\r\nContent-Type: "+mime+"\r\n\r\n").encode(),data,b"\r\n--"+boundary.encode()+b"--\r\n"]
                result=_archive_request_raw("/api/v1/document/upload",b"".join(parts),"multipart/form-data; boundary="+boundary)
                return self._json({"success":bool(result.get("success")),"filename":filename,"workspace":ANYTHINGLLM_WORKSPACE,"result":result})
            except Exception as exc: return self._json({"error":str(exc)[:500]},502)
        if path == "/api/ask":
            try:
                length = min(int(self.headers.get("Content-Length", "0")), 8192)
                body = json.loads(self.rfile.read(length).decode("utf-8") or "{}")
                question = str(body.get("question") or body.get("message") or "").strip()
                if not question:
                    return self._json({"error": "question required"}, 400)
                if len(question) > 2000:
                    return self._json({"error": "question too long"}, 400)
                if not ANYTHINGLLM_API_KEY:
                    return self._json({"error": "ANYTHINGLLM_API_KEY not configured"}, 503)
                now = time.time()
                window = int(now // 30)
                with _rate_lock:
                    if _rate_windows.get(window, 0) >= 10:
                        return self._json({"error": "rate limited, try again later"}, 429)
                    _rate_windows[window] = _rate_windows.get(window, 0) + 1
                    for old in [k for k in _rate_windows if k < window - 1]:
                        _rate_windows.pop(old, None)
                payload = json.dumps({"message": question, "mode": body.get("mode") or "query", "sessionId": "nas-monitor-ask"}, ensure_ascii=False).encode("utf-8")
                req = urllib.request.Request(ANYTHINGLLM_URL + "/api/v1/workspace/" + ANYTHINGLLM_WORKSPACE + "/chat", data=payload, method="POST")
                req.add_header("Authorization", "Bearer " + ANYTHINGLLM_API_KEY)
                req.add_header("Content-Type", "application/json")
                opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
                with opener.open(req, timeout=60) as resp:
                    data = json.loads(resp.read().decode("utf-8", "replace") or "{}")
                sources = []
                for s in (data.get("sources") or []):
                    meta = s.get("metadata") or {}
                    sources.append({
                        "title": (s.get("title") or meta.get("title") or meta.get("chunkSource") or "")[:200],
                        "score": round(float(s.get("score") or 0), 3),
                        "url": (s.get("url") or "")[:300],
                    })
                return self._json({
                    "ok": data.get("type") != "abort" and not data.get("error"),
                    "answer": data.get("textResponse"),
                    "sources": sources,
                    "error": data.get("error"),
                })
            except urllib.error.HTTPError as exc:
                return self._json({"error": "AnythingLLM HTTP {}: {}".format(exc.code, exc.read().decode("utf-8", "replace")[:300])}, 502)
            except Exception as exc:
                return self._json({"error": str(exc)[:500]}, 502)
        length = min(int(self.headers.get("Content-Length", "0")), 4096)
        body = json.loads(self.rfile.read(length).decode("utf-8") or "{}")
        model_match = re.match(r"^/api/newapi/models/(\d+)/status$", path)
        if model_match:
            try:
                model = gateway_api.set_model_status(int(model_match.group(1)), body.get("status"))
                return self._json({"success": True, "model": model})
            except ValueError as e:
                return self._json({"error": str(e)}, 400)
            except Exception as e:
                return self._json({"error": str(e)}, 502)

        wb_toggle = re.match(r"^/api/workbuddy/groups/([a-zA-Z0-9_-]+)/accounts/([0-9a-f-]+)/toggle$", path)
        if wb_toggle:
            wb_group, uid = wb_toggle.group(1), wb_toggle.group(2)
        else:
            wb_toggle = re.match(r"^/api/workbuddy/accounts/([0-9a-f-]+)/toggle$", path)
            wb_group, uid = ("default", wb_toggle.group(1)) if wb_toggle else (None, None)
        if wb_toggle:
            try:
                query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
                raw_disabled = (query.get("disabled") or [""])[0].lower()
                if raw_disabled not in ("true", "false", "1", "0", "yes", "no"):
                    return self._json({"error": "请通过 query 参数 disabled=true/false 指定状态"}, 400)
                disabled = raw_disabled in ("true", "1", "yes")
                ok, result = workbuddy_collector.toggle_account(uid, disabled, group_id=wb_group)
                if ok:
                    if not disabled and result.get("disabled") is True:
                        return self._json({"success": False, "error": "该账号是文件禁用状态，请在 workbuddy-manager 中启用后再操作"}, 409)
                    return self._json({"success": True, "uid": uid, "group": wb_group, "disabled": disabled, "result": result})
                return self._json({"success": False, "error": result}, 502)
            except Exception as e:
                return self._json({"error": str(e)[:300]}, 500)

        status_match = re.match(r"^/api/newapi/channels/(\d+)/status$", path)
        route_match = re.match(r"^/api/newapi/channels/(\d+)/routing$", path)
        try:
            if path == '/api/newapi/routing-policy':
                policy = gateway_api.apply_routing_policy(body.get('channel_id'), body.get('mode'))
                return self._json({"success": True, "policy": policy})
            if path == '/api/newapi/routing-policy/restore':
                policy = gateway_api.restore_routing_policy()
                return self._json({"success": True, "policy": policy})
            if path == '/api/newapi/affinity':
                if not isinstance(body.get('enabled'), bool):
                    return self._json({"error": "enabled must be boolean"}, 400)
                return self._json({"success": True, "affinity": gateway_api.set_affinity_enabled(body['enabled'])})
            if path == '/api/newapi/affinity/cache/clear':
                cleared = gateway_api.clear_affinity_cache()
                return self._json({"success": True, "result": cleared, "affinity": gateway_api.affinity_state()})
            if status_match:
                view = gateway_api.set_channel_status(int(status_match.group(1)), body.get("status"))
                return self._json({"success": True, "channel": view, "warning": "auto_ban 仍为 1 时路由会跳过该渠道" if view.get("auto_ban") else ""})
            if route_match:
                view = gateway_api.update_channel_routing(int(route_match.group(1)), body.get("priority"), body.get("weight"))
                return self._json({"success": True, "channel": view})
            return self._json({"error": "not found"}, 404)
        except ValueError as e:
            return self._json({"error": str(e)}, 400)
        except Exception as e:
            return self._json({"error": str(e)}, 502)

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "*")
        self.end_headers()

    def log_message(self, format, *args):
        print(f"[{datetime.now().isoformat()}] {args[0]} {args[1]} {args[2]}")


# ── 启动 ──────────────────────────────────────────

# 服务层准入：在 process_request() 创建线程之前获取槽位，线程总数有硬上限。
# 拿不到槽位直接回 503 并关闭连接，不排队；request_queue_size 仅是 listen backlog。
_MAX_WORKERS = 32


class BoundedThreadingMixIn(ThreadingMixIn):
    daemon_threads = True
    _worker_sem = BoundedSemaphore(_MAX_WORKERS)

    def process_request(self, request, client_address):
        if not self._worker_sem.acquire(blocking=False):
            try:
                body = b'{"error":"server busy"}'
                request.sendall(
                    b"HTTP/1.1 503 Service Unavailable\r\n"
                    b"Content-Type: application/json\r\n"
                    b"Content-Length: " + str(len(body)).encode() + b"\r\n"
                    b"Retry-After: 2\r\n"
                    b"Connection: close\r\n\r\n" + body)
            except OSError:
                pass
            finally:
                self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except Exception:
            self._worker_sem.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self._worker_sem.release()


class MonitorHTTPServer(BoundedThreadingMixIn, ThreadingHTTPServer):
    request_queue_size = 64


class HealthHandler(BaseHTTPRequestHandler):
    """独立 loopback 健康监听：不鉴权、不触上游、与业务并发完全隔离。
    仅作 liveness 观测；compose healthcheck 的 unhealthy 不会触发容器重启。"""

    def do_GET(self):
        if self.path.split("?")[0] in ("/health", "/api/health"):
            try:
                scheduler = health_state()
            except Exception as exc:
                scheduler = {"started": False, "error": str(exc)[:200]}
            payload = {"ok": True, "service": "nas-monitor-api", "time": datetime.now().isoformat(), "scheduler": scheduler}
            body = json.dumps(payload).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()

    def log_message(self, format, *args):
        pass


def trend_sampler():
    while not stop_event.is_set():
        try:
            for host, url in {"nas": NAS_GLANCES, **_remote_glances_map()}.items():
                g = get_glances(url)
                if not g:
                    continue
                disk = 0
                disks = g.get("disks") or []
                if disks:
                    disk = disks[0].get("percent", 0) or 0
                ns = g.get("network_summary") or {}
                record_trend(host, g.get("cpu", {}).get("percent", 0) or 0, g.get("memory", {}).get("percent", 0) or 0, disk, ns.get("rx_bps", 0) or 0, ns.get("tx_bps", 0) or 0)
                temperatures = get_glances_temperatures(url)
                if host == "nas":
                    temperatures = merge_temperature_sources(temperatures, get_nas_hardware_temperatures())
                record_temperature_trends(host, temperatures)
        except Exception as e:
            print(f"trend sampler error: {e}")
        stop_event.wait(5)


if __name__ == "__main__":
    port = int(os.environ.get("API_PORT", 8765))
    health_port = int(os.environ.get("HEALTH_PORT", 8766))
    scheduler_lock = None
    scheduler_thread = None
    if os.environ.get("NAS_MONITOR_SCHEDULER", "1") != "0":
        lock_path = Path(os.environ.get("BACKEND_ROOT", "/app")) / ".nas-monitor-scheduler.lock"
        lock_path.parent.mkdir(parents=True, exist_ok=True)
        scheduler_lock = open(lock_path, "a+", encoding="utf-8")
        try:
            fcntl.flock(scheduler_lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            scheduler_lock.close()
            raise RuntimeError("nas-monitor scheduler already running")
        scheduler_thread = start_scheduler(stop_event)
    Thread(target=trend_sampler, daemon=True).start()
    # 预热路由器缓存
    Thread(target=get_router_status, daemon=True).start()
    # 概览预热与 log-tokens 周期刷新
    Thread(target=_overview_warmer, daemon=True).start()
    Thread(target=_airport_traffic_sampler, daemon=True).start()
    # WorkBuddy 账号监听：人工加/删账号后秒级重采，不必等 600s 周期。
    # 本地出口看 auths 目录指纹；wan3 的目录不在本机（不再挂 NFS），
    # 改用 remote-nas 面板的账号清单参与指纹。只读，不依赖面板回调，
    # 因此不受第三方 workbuddy-manager 更新影响。
    if os.environ.get("WORKBUDDY_WATCH_ENABLED", "1") != "0":
        try:
            workbuddy_collector.start_account_watch(stop_event)
            print("  workbuddy account watch: on (auths 目录/面板账号变化即重采)")
        except Exception as exc:
            print(f"  workbuddy account watch: off ({exc})")
    server = MonitorHTTPServer(("0.0.0.0", port), APIHandler)
    health_server = ThreadingHTTPServer(("127.0.0.1", health_port), HealthHandler)
    Thread(target=health_server.serve_forever, daemon=True).start()

    def _handle_sigterm(signum, frame):
        print("SIGTERM received, shutting down")
        stop_event.set()
        Thread(target=server.shutdown, daemon=True).start()
        Thread(target=health_server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, _handle_sigterm)
    print(f"NAS Monitor API running on http://0.0.0.0:{port}")
    print(f"  health listener on http://127.0.0.1:{health_port}/health (liveness only)")
    print(f"  GET /api/overview  - 概览")
    print(f"  GET /api/nas       - NAS 详情")
    print(f"  GET /api/aliyun    - 阿里云详情")
    print(f"  GET /api/ping      - 健康检查")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        stop_event.set()
        print("shutting down")
    finally:
        if scheduler_lock is not None:
            fcntl.flock(scheduler_lock.fileno(), fcntl.LOCK_UN)
            scheduler_lock.close()
        server.server_close()
        health_server.server_close()
