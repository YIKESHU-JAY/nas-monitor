"""爱快 WAN 口测速 collector。

通过 Docker API 在 macvlan 网络 `qb_macvlan` 上启动一次性 Ookla CLI 容器，
绑定源 IP（wan1=192.168.2.201 / wan2=192.168.2.202），由爱快按源地址分流到
对应 WAN 出口。Ookla 返回的 bandwidth 单位为 bytes/s，这里统一转换为 Mbps。
"""
import json
import os
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Optional

from . import shadow_store
from .shadow_store import shadow_path

SPEEDTEST_BINARY = "/app/bin/speedtest"
SPEEDTEST_BINARY_HOST = os.environ.get("SPEEDTEST_BINARY_HOST", "")
SPEEDTEST_IMAGE = os.environ.get("SPEEDTEST_IMAGE", "python:3.11-slim")
SPEEDTEST_NETWORK = os.environ.get("SPEEDTEST_NETWORK", "")
WAN_IP = {k: v for k, v in (("wan1", os.environ.get("SPEEDTEST_WAN1_IP", "")),
                           ("wan2", os.environ.get("SPEEDTEST_WAN2_IP", "")),
                           ("wan3", os.environ.get("SPEEDTEST_WAN3_IP", ""))) if v}
MAX_HISTORY = 200
MAX_DAYS = 90
_RUNNING = {"lock": False}

# 每条测速独占 docker API 请求，避免同一进程并发跑两条时互相污染。
import threading
_RUN_LOCK = threading.Lock()


def _now() -> str:
    return datetime.now(timezone(timedelta(hours=8))).isoformat()


def _docker_request_raw(path: str, method: str = "GET", body: Optional[bytes] = None, timeout: int = 20) -> bytes:
    import http.client
    import socket
    conn = http.client.HTTPConnection("localhost", timeout=timeout)
    conn.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    conn.sock.settimeout(timeout)
    conn.sock.connect("/var/run/docker.sock")
    headers = {}
    if body is not None:
        headers["Content-Type"] = "application/json"
    conn.request(method, path, body=body, headers=headers)
    response = conn.getresponse()
    payload = response.read()
    status = response.status
    conn.close()
    if status >= 400:
        raise RuntimeError(f"Docker API {status}: {payload.decode('utf-8', errors='replace')[:200]}")
    return payload


def _docker_request(path: str, method: str = "GET", body: Optional[bytes] = None, timeout: int = 20) -> Any:
    payload = _docker_request_raw(path, method, body, timeout)
    if not payload:
        return {}
    return json.loads(payload.decode() or "{}")


def _run_ookla(source_ip: str, timeout: int = 120) -> dict:
    """在 macvlan 网络里跑一次 Ookla，返回解析后的 result dict。"""
    container_name = f"speedtest-{source_ip.split('.')[-1]}-{int(time.time())}"
    create = {
        "Image": SPEEDTEST_IMAGE,
        "Cmd": [
            SPEEDTEST_BINARY,
            "--accept-license", "--accept-gdpr",
            "--format=json",
        ],
        "HostConfig": {
            "NetworkMode": SPEEDTEST_NETWORK,
            "Binds": [f"{SPEEDTEST_BINARY_HOST}:{SPEEDTEST_BINARY}:ro"],
        },
        "NetworkingConfig": {
            "EndpointsConfig": {
                SPEEDTEST_NETWORK: {"IPAMConfig": {"IPv4Address": source_ip}},
            },
        },
        "Labels": {"speedtest-source": source_ip},
    }
    created = _docker_request("/containers/create", "POST", json.dumps(create).encode())
    cid = created.get("Id")
    if not cid:
        raise RuntimeError("docker create failed")
    try:
        _docker_request(f"/containers/{cid}/start", "POST", timeout=10)
        deadline = time.time() + timeout
        exited = False
        while time.time() < deadline:
            try:
                inspect = _docker_request(f"/containers/{cid}/json", timeout=10)
                state = inspect.get("State") or {}
                running = bool(state.get("Running"))
            except RuntimeError as exc:
                # AutoRemove 容器退出后会被删除，404 即视为已退出
                if "404" in str(exc):
                    exited = True
                    break
                raise
            if not running:
                exited = True
                break
            time.sleep(2)
        if not exited:
            # 超时：停止并记录
            try:
                _docker_request(f"/containers/{cid}/kill", "POST", timeout=10)
            except Exception:
                pass
            raise RuntimeError("speedtest timeout")
        # 容器退出后仍有一小段窗口可读 logs；AutoRemove 容器删除后读 logs 会 404，
        # 此时从异常里拿不到内容，改为容器未删除前读取。
        raw = _docker_request_raw(f"/containers/{cid}/logs?stderr=1&stdout=1", timeout=20)
        text = raw.decode("utf-8", errors="replace")
        # Docker 日志每行前有 8 字节 header，去掉后找最后一行 JSON result。
        lines = [l[8:] if len(l) > 8 else l for l in text.split("\n")]
        result = None
        for line in reversed(lines):
            line = line.strip()
            if not line:
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                continue
            if obj.get("type") == "result":
                result = obj
                break
        if not result:
            raise RuntimeError("no speedtest result")
        return result
    finally:
        try:
            _docker_request(f"/containers/{cid}", "DELETE", timeout=10)
        except Exception:
            pass


def _bandwidth_to_mbps(bandwidth: Any) -> float:
    """Ookla bandwidth 为 bytes/s，转 Mbps。"""
    try:
        return round(float(bandwidth) * 8 / 1_000_000, 1)
    except (TypeError, ValueError):
        return 0.0


def _parse_result(interface_name: str, source_ip: str, result: dict) -> dict:
    download = _bandwidth_to_mbps((result.get("download") or {}).get("bandwidth"))
    upload = _bandwidth_to_mbps((result.get("upload") or {}).get("bandwidth"))
    ping_obj = result.get("ping") or {}
    server = result.get("server") or {}
    iface = result.get("interface") or {}
    return {
        "id": f"{datetime.now().strftime('%Y%m%d-%H%M%S')}-{interface_name}",
        "interface": interface_name,
        "timestamp": _now(),
        "download_mbps": download,
        "upload_mbps": upload,
        "ping_ms": round(float(ping_obj.get("latency") or 0), 1),
        "jitter_ms": round(float(ping_obj.get("jitter") or 0), 2),
        "server": server.get("name") or "",
        "server_location": server.get("location") or "",
        "source_ip": iface.get("externalIp") or source_ip,
        "status": "ok",
        "error": None,
    }


def _load_history() -> dict:
    try:
        with shadow_path("speedtest_history.json").open(encoding="utf-8") as stream:
            value = json.load(stream)
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return {"items": []}
    if not isinstance(value, dict):
        return {"items": []}
    items = value.get("items")
    if not isinstance(items, list):
        items = []
    return {"items": items}


def _prune(items: list) -> list:
    cutoff = datetime.now(timezone(timedelta(hours=8))) - timedelta(days=MAX_DAYS)
    kept = []
    for item in items:
        if not isinstance(item, dict):
            continue
        try:
            ts = datetime.fromisoformat(str(item.get("timestamp", "")))
            if ts.tzinfo is None:
                ts = ts.replace(tzinfo=timezone(timedelta(hours=8)))
        except (ValueError, TypeError):
            continue
        if ts >= cutoff:
            kept.append(item)
    return kept[-MAX_HISTORY:]


def _append_record(item: dict) -> None:
    history = _load_history()
    items = history.get("items", [])
    items = [x for x in items if isinstance(x, dict)]
    items.append(item)
    items = _prune(items)
    payload = {"items": items, "updated_at": _now()}
    shadow_store.atomic_write_json("speedtest_history.json", payload)


def run_speedtest(interface_name: str, timeout: int = 120) -> dict:
    """手动/调度测速入口，返回单条记录。"""
    if not WAN_IP:
        raise RuntimeError("未配置测速源 IP（SPEEDTEST_WAN*_IP），测速功能不可用")
    if interface_name not in WAN_IP:
        raise ValueError(f"不支持的 WAN 口：{interface_name}")
    source_ip = WAN_IP[interface_name]
    if _RUNNING["lock"]:
        raise RuntimeError("测速正在进行中，请等待完成")
    _RUNNING["lock"] = True
    try:
        with _RUN_LOCK:
            result = _run_ookla(source_ip, timeout=timeout)
            record = _parse_result(interface_name, source_ip, result)
            _append_record(record)
            return record
    finally:
        _RUNNING["lock"] = False


def latest() -> dict:
    """返回每个接口最近一次结果。"""
    history = _load_history()
    latest_map = {}
    for item in history.get("items", []):
        if not isinstance(item, dict):
            continue
        name = item.get("interface")
        if not name:
            continue
        existing = latest_map.get(name)
        if existing is None or (item.get("timestamp", "") > existing.get("timestamp", "")):
            latest_map[name] = item
    return {"interfaces": latest_map, "updated_at": _now()}


def history(interface: Optional[str] = None, limit: int = 30) -> dict:
    """按接口查询历史；接口为空时返回全部。"""
    items = _load_history().get("items", [])
    if interface:
        items = [x for x in items if x.get("interface") == interface]
    items = sorted(items, key=lambda x: x.get("timestamp", ""), reverse=True)
    limit = max(1, min(int(limit), MAX_HISTORY))
    return {"items": items[:limit], "total": len(items), "updated_at": _now()}


def is_running() -> bool:
    return _RUNNING["lock"]


def collect(interface_name: str = "wan1") -> dict:
    """scheduler 适配入口：定时任务用 collector 风格调用。"""
    return run_speedtest(interface_name)


def collect_daily() -> dict:
    """每天 02:00 定时：依次测所有已配置源 IP 的 WAN 口，单条失败不影响另一条。

    源 IP 由 WAN_IP 决定：爱快后台新增 WAN 后，只要在此处补充对应源 IP 映射，
    每日测速就会自动纳入；删除 WAN 不会导致异常（该接口单独标记失败）。
    """
    results = {}
    for name in list(WAN_IP.keys()):
        try:
            results[name] = run_speedtest(name)
        except Exception as exc:
            results[name] = {"interface": name, "status": "failed", "error": str(exc)[:200]}
    return results
