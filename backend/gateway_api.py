"""NAS New API aggregation. Admin token stays inside the monitor backend."""
import datetime
import copy
import json
import math
import os
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from collections import OrderedDict
from concurrent.futures import ThreadPoolExecutor, as_completed

NEW_API_URL = os.environ.get("NEW_API_URL", os.environ.get("GATEWAY_NEW_API_URL", "")).rstrip("/")
# 运行数据根目录：与 shadow_store 保持一致，便于自定义部署（默认 /app）。
_BACKEND_ROOT = Path(os.environ.get("BACKEND_ROOT", "/app"))
ADMIN_TOKEN_FILE = os.environ.get("NEWAPI_ADMIN_TOKEN_FILE", str(_BACKEND_ROOT / ".newapi_admin_token"))
PROBE_STATE_FILE = _BACKEND_ROOT / "probe_state" / "model_channel_probe.json"
BALANCE_SNAPSHOT_FILE = _BACKEND_ROOT / "balance_snapshot.json"
PIPIO_DETAIL_FILE = _BACKEND_ROOT / "pipio_usage_snapshot.json"
ROUTING_POLICY_STATE_FILE = _BACKEND_ROOT / "routing_policy_snapshot.json"
PROBE_STALE_AFTER = 15 * 60
_routing_policy_lock = threading.RLock()
_read_cache_lock = threading.RLock()
_read_cache = OrderedDict()
_upstream_lock = threading.RLock()
_retry_at = 0.0
READ_CACHE_SECONDS = 60
LOG_WINDOW_SECONDS = 3600


class GatewayRateLimited(RuntimeError):
    def __init__(self, retry_after):
        self.retry_after = max(1, math.ceil(retry_after))
        super().__init__("网关管理查询过于频繁，请在 %d 秒后重试" % self.retry_after)


def _cached_read(key, loader):
    with _read_cache_lock:
        cached = _read_cache.get(key)
        if cached is not None and time.monotonic() - cached[0] < READ_CACHE_SECONDS:
            _read_cache.move_to_end(key)
            return copy.deepcopy(cached[1])
        data = loader()
        _read_cache[key] = (time.monotonic(), copy.deepcopy(data))
        _read_cache.move_to_end(key)
        while len(_read_cache) > 16:
            _read_cache.popitem(last=False)
        return data

LOG_TYPE_UNKNOWN = 0
LOG_TYPE_TOPUP = 1
LOG_TYPE_CONSUME = 2
LOG_TYPE_MANAGE = 3
LOG_TYPE_SYSTEM = 4
LOG_TYPE_ERROR = 5
LOG_TYPE_REFUND = 6
LOG_TYPE_LOGIN = 7


def _token():
    with open(ADMIN_TOKEN_FILE) as f:
        token = f.read().strip()
    if not token:
        raise RuntimeError("New API 管理令牌不可用")
    return token


def request(method, path, payload=None):
    if method != "GET":
        with _read_cache_lock:
            try:
                return _request_upstream(method, path, payload)
            finally:
                _read_cache.clear()
    return _request_upstream(method, path, payload)


def _request_upstream(method, path, payload=None):
    global _retry_at
    data = None if payload is None else json.dumps(payload, separators=(",", ":")).encode()
    req = urllib.request.Request(NEW_API_URL + path, data=data, method=method)
    req.add_header("Authorization", "Bearer " + _token())
    if data is not None:
        req.add_header("Content-Type", "application/json")
    with _upstream_lock:
        remaining = _retry_at - time.monotonic()
        if remaining > 0:
            raise GatewayRateLimited(remaining)
        try:
            with urllib.request.urlopen(req, timeout=15) as response:
                obj = json.loads(response.read().decode("utf-8", errors="replace"))
        except urllib.error.HTTPError as exc:
            if exc.code != 429:
                exc.close()
                raise
            try:
                wait = float(exc.headers.get("Retry-After", "60"))
                if not math.isfinite(wait):
                    wait = 60
            except (ValueError, TypeError):
                wait = 60
            exc.close()
            wait = max(5, min(wait, 300))
            _retry_at = time.monotonic() + wait
            raise GatewayRateLimited(wait) from None
    if not obj.get("success"):
        raise RuntimeError(str(obj.get("message") or "New API 返回失败"))
    return obj.get("data", obj)


def channel_view(channel):
    return {
        "id": channel.get("id"), "name": channel.get("name", ""),
        "status": channel.get("status"), "models": channel.get("models", ""),
        "group": channel.get("group", ""), "priority": channel.get("priority", 0),
        "weight": channel.get("weight", 0), "test_time": channel.get("test_time", 0),
        "response_time": channel.get("response_time", 0), "auto_ban": channel.get("auto_ban", 0),
        "used_quota": channel.get("used_quota", 0),
    }


def _log_total(cutoff, log_type):
    """Lightweight 24h count: ask New API for one row and read its total.

    The upstream supports server-side type filtering, so this avoids downloading
    every log body just to count. Returns None when the response has no total.
    """
    data = request("GET", "/api/log/?p=0&page_size=1&start_timestamp=%d&type=%d" % (cutoff, log_type))
    try:
        return max(0, int(data.get("total")))
    except (TypeError, ValueError, AttributeError):
        return None


def _requests_24h():
    cutoff = int(datetime.datetime.now().timestamp()) - 24 * 3600
    consume = _log_total(cutoff, LOG_TYPE_CONSUME)
    errors = _log_total(cutoff, LOG_TYPE_ERROR)
    if consume is None and errors is None:
        return None
    return (consume or 0) + (errors or 0)


def _fetch_log_page(cutoff, page):
    data = request("GET", "/api/log/?p=%d&page_size=100&start_timestamp=%d" % (page, cutoff))
    return data if isinstance(data, dict) else {}


def _performance_logs(max_pages=120):
    """Read up to 24h of logs for per-model performance only.

    This is intentionally NOT used by overview(): overview uses the lightweight
    _requests_24h() count. Model performance is loaded on demand by the 模型 tab.
    Page count is derived from the upstream total instead of a fixed 25-page cap,
    with a hard safety ceiling so a bad total cannot trigger unbounded reads.
    New API ignores a combined type filter, so consume/error are filtered locally.
    """
    cutoff = int(datetime.datetime.now().timestamp()) - 24 * 3600
    first = _fetch_log_page(cutoff, 0)
    first_batch = first.get("items", []) if isinstance(first, dict) else []
    try:
        expected = max(0, int(first.get("total") or 0))
    except (TypeError, ValueError):
        expected = 0
    if not first_batch:
        return []
    pages = min(max_pages, max(1, math.ceil(expected / 100.0))) if expected else max_pages
    out = list(first_batch)
    if pages > 1:
        # Bounded parallelism: enough to cut latency, low enough not to hammer
        # the New API admin endpoint.
        workers = min(6, pages - 1)
        with ThreadPoolExecutor(max_workers=workers) as pool:
            futures = [pool.submit(_fetch_log_page, cutoff, p) for p in range(1, pages)]
            for fut in as_completed(futures):
                try:
                    data = fut.result()
                except Exception:
                    continue
                batch = data.get("items", []) if isinstance(data, dict) else []
                if batch:
                    out.extend(batch)
    kept = []
    for x in out:
        if int(x.get("created_at") or 0) < cutoff:
            continue
        if x.get("type") in (LOG_TYPE_CONSUME, LOG_TYPE_ERROR):
            kept.append(x)
    return kept


def _other(log):
    other = log.get("other")
    if isinstance(other, str):
        try:
            other = json.loads(other)
        except Exception:
            other = {}
    return other if isinstance(other, dict) else {}


def _is_admin_log(log, other=None):
    log_type = log.get("type")
    if log_type in (LOG_TYPE_TOPUP, LOG_TYPE_MANAGE, LOG_TYPE_SYSTEM, LOG_TYPE_REFUND, LOG_TYPE_LOGIN):
        return True
    other = _other(log) if other is None else other
    path = str(other.get("request_path") or "")
    model = str(log.get("model_name") or log.get("model") or "")
    if path.startswith("/api/") or path.startswith("/console"):
        return True
    if not model and not path and not log.get("channel") and not log.get("token_name"):
        return True
    return False


def _log_outcome(log):
    """Classify New API logs to match the admin panel status.

    New API log types: 1=充值 2=消费 3=管理 4=系统 5=错误 6=退款 7=登录.
    - type 3/7/1/4/6 -> admin (never a request failure)
    - type 5 -> upstream_failed (content carries status_code)
    - type 2 with quota>0 -> success (billed, even when stream_status is absent)
    - type 2 with client-cancel signals -> client_cancelled
    """
    other = _other(log)
    if _is_admin_log(log, other):
        return "admin"
    log_type = log.get("type")
    stream = other.get("stream_status")
    if not isinstance(stream, dict):
        stream = {}
    status = str(stream.get("status") or "").lower()
    end_error = str(stream.get("end_error") or "").lower()
    end_reason = str(stream.get("end_reason") or "").lower()
    content = str(log.get("content") or "").lower()
    if log_type == LOG_TYPE_ERROR:
        return "upstream_failed"
    if log_type == LOG_TYPE_CONSUME:
        if end_reason == "client_gone" or "context canceled" in end_error:
            return "client_cancelled"
        if int(log.get("quota") or 0) > 0:
            if status == "ok" or not (status or end_error or end_reason):
                return "success"
            return "upstream_failed"
        if status == "ok":
            return "success"
        if end_reason == "client_gone" or "context canceled" in end_error:
            return "client_cancelled"
        return "unknown"
    if status == "ok":
        return "success"
    if end_reason == "client_gone" or "context canceled" in end_error:
        return "client_cancelled"
    if status or end_error or end_reason or log.get("quota", 0) == 0 or "无法扣费" in content or "error" in content:
        return "upstream_failed"
    return "unknown"


def overview():
    return _cached_read("overview", _build_overview)


def _gateway_rpm():
    """Upstream rolling 1-minute request rate.

    New API exposes it on /api/log/stat (admin scope). It is a live gauge, not a
    window aggregate, so it is read through the same 60s read cache as overview
    and degrades to None instead of failing the whole overview payload.
    """
    try:
        data = _cached_read("log_stat", lambda: request("GET", "/api/log/stat"))
    except Exception:
        return None
    if not isinstance(data, dict):
        return None
    try:
        value = int(data.get("rpm"))
    except (TypeError, ValueError):
        return None
    return value if value >= 0 else None


def _build_performance():
    logs = _performance_logs()
    models = {}
    for log in logs:
        name = log.get("model_name", "")
        if not name:
            continue
        outcome = _log_outcome(log)
        if outcome == "admin":
            continue
        item = models.setdefault(name, {"model": name, "requests": 0, "success": 0, "failed": 0,
                                        "client_cancelled": 0, "unknown": 0, "use_time_sum": 0.0,
                                        "frt_sum": 0.0, "frt_count": 0})
        item["requests"] += 1
        if outcome == "success": item["success"] += 1
        elif outcome == "upstream_failed": item["failed"] += 1
        elif outcome == "client_cancelled": item["client_cancelled"] += 1
        else: item["unknown"] += 1
        item["use_time_sum"] += float(log.get("use_time") or 0)
        other = _other(log)
        frt = other.get("frt")
        if isinstance(frt, (int, float)) and frt >= 0:
            item["frt_sum"] += frt; item["frt_count"] += 1
    performance = []
    for item in models.values():
        count = item["requests"]
        measured = item["success"] + item["failed"]
        performance.append({"model": item["model"], "requests": count,
            "success": item["success"], "failed": item["failed"],
            "client_cancelled": item["client_cancelled"], "unknown": item["unknown"],
            "success_rate": round(item["success"] * 100.0 / measured, 1) if measured else 0,
            "avg_use_time": round(item["use_time_sum"] * 1000 / count, 0) if count else 0,
            "avg_frt": round(item["frt_sum"] / item["frt_count"], 0) if item["frt_count"] else None})
    performance.sort(key=lambda x: x["requests"], reverse=True)
    return performance


def performance():
    """Per-model 24h aggregation. Loaded only when the 模型 tab is opened."""
    return _cached_read("performance", _build_performance)


def _build_overview():
    channel_data = request("GET", "/api/channel/?p=0&page_size=100")
    channels = channel_data.get("items", []) if isinstance(channel_data, dict) else []
    requests_24h = _requests_24h()
    return {"channels": [channel_view(c) for c in channels], "models": [],
            "requests_24h": requests_24h,
            "recent_requests": requests_24h or 0, "window": "24h",
            "disabled_channels": sum(1 for c in channels if c.get("status") != 1),
            "auto_banned_channels": sum(1 for c in channels if c.get("auto_ban")),
            "rpm": _gateway_rpm(),
            "generated_at": datetime.datetime.now().isoformat()}


# Token filtering is done here instead of in the App, so pagination and totals remain
# accurate. The upstream New API logs endpoint has no reliable token-name filter.
LOG_FILTER_SCAN_PAGES = 10


def _log_item(log):
    other = _other(log)
    admin = other.get("admin_info") if isinstance(other.get("admin_info"), dict) else {}
    return {
        "id": log.get("id"), "created_at": log.get("created_at"),
        "model": log.get("model_name", ""), "channel_id": log.get("channel"),
        "channel_name": log.get("channel_name", ""), "outcome": _log_outcome(log),
        "token_name": log.get("token_name", ""),
        "prompt_tokens": log.get("prompt_tokens", 0),
        "completion_tokens": log.get("completion_tokens", 0),
        "quota": log.get("quota", 0), "use_time": log.get("use_time", 0),
        "frt": other.get("frt"), "reasoning_effort": other.get("reasoning_effort", ""),
        "use_channel": admin.get("use_channel", []),
        "request_path": other.get("request_path", ""),
        "log_type": log.get("type"),
        "content": str(log.get("content") or ""),
    }


def _recent_raw_logs(max_pages=LOG_FILTER_SCAN_PAGES):
    return _cached_read(("recent_logs", max_pages), lambda: _build_recent_logs(max_pages))


def _build_recent_logs(max_pages):
    all_items = []
    cutoff = int(time.time()) - LOG_WINDOW_SECONDS
    expected_total = None
    truncated = True
    for page in range(max_pages):
        data = request("GET", "/api/log/?p=%d&page_size=100&start_timestamp=%d" % (page, cutoff))
        batch = data.get("items", []) if isinstance(data, dict) else []
        if expected_total is None:
            expected_total = int(data.get("total") or 0)
        if not batch:
            truncated = False
            break
        all_items.extend(batch)
        oldest = min(int(item.get("created_at") or 0) for item in batch)
        if oldest < cutoff or len(all_items) >= expected_total or len(batch) < 100:
            truncated = False
            break
    return {"items": [item for item in all_items if int(item.get("created_at") or 0) >= cutoff],
            "window_seconds": LOG_WINDOW_SECONDS, "truncated": truncated,
            "scan_limit": max_pages * 100}


def _hour_logs():
    data = _recent_raw_logs()
    cutoff = int(time.time()) - LOG_WINDOW_SECONDS
    data["items"] = [item for item in data["items"] if int(item.get("created_at") or 0) >= cutoff]
    return data


def log_tokens():
    data = _hour_logs()
    names = {str(item.get("token_name") or "").strip() for item in data["items"]}
    return {"tokens": sorted(name for name in names if name), "scan_limit": data["scan_limit"],
            "window_seconds": LOG_WINDOW_SECONDS, "truncated": data["truncated"]}


def logs(page=0, page_size=30, token_name=""):
    page = max(0, min(int(page), 9999)); page_size = max(1, min(int(page_size), 100))
    token_name = str(token_name or "").strip()
    data = _hour_logs()
    matched = [item for item in data["items"] if not token_name or str(item.get("token_name") or "").strip() == token_name]
    start = page * page_size
    return {"items": [_log_item(log) for log in matched[start:start + page_size]], "total": len(matched),
            "page": page + 1, "page_size": page_size, "token_filter": token_name,
            "scan_limit": data["scan_limit"], "window_seconds": LOG_WINDOW_SECONDS,
            "truncated": data["truncated"]}


def probe():
    """Read the credential-free state written by NAS Hermes probe jobs."""
    try:
        raw = json.loads(PROBE_STATE_FILE.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return {
            "schema_version": 1, "probe_status": "unknown", "is_stale": True,
            "checked_at": None, "summary": {"total": 0, "healthy": 0, "degraded": 0, "failed": 0, "unknown": 0},
            "items": [], "sources": {}, "transitions": [],
        }
    except Exception as exc:
        raise RuntimeError("探针状态不可读取") from exc
    if not isinstance(raw, dict):
        raise RuntimeError("探针状态格式无效")
    checked_at = raw.get("checked_at")
    stale = True
    if checked_at:
        try:
            parsed = datetime.datetime.fromisoformat(str(checked_at).replace("Z", "+00:00"))
            if parsed.tzinfo is None:
                parsed = parsed.replace(tzinfo=datetime.timezone.utc)
            stale = (datetime.datetime.now(datetime.timezone.utc) - parsed.astimezone(datetime.timezone.utc)).total_seconds() > int(raw.get("stale_after_seconds") or PROBE_STALE_AFTER)
        except Exception:
            stale = True
    raw["is_stale"] = stale
    if stale and raw.get("probe_status") == "ok":
        raw["probe_status"] = "stale"
    return raw



def data_source_meta():
    """Read-only metadata for the monitor data sources consumed by this backend.

    Tells the UI which snapshot file backs each source, its updated_at and
    whether it is currently stale. This is the versioned companion endpoint
    for the (still schema-compatible) legacy balance/probe endpoints.
    """
    import datetime as _dt

    def _read(path):
        try:
            with open(path, encoding="utf-8") as f:
                value = json.load(f)
        except (FileNotFoundError, json.JSONDecodeError, OSError):
            return None
        return value if isinstance(value, dict) else None

    def _updated(raw):
        if not raw:
            return None
        value = raw.get("updated_at") or raw.get("checked_at")
        return str(value) if value else None

    def _stale(raw, key="updated_at"):
        value = _updated(raw)
        if not value:
            return True
        parsed = None
        # Hermes balance writes "MM-dd HH:mm" (no year); assume current year,
        # Asia/Shanghai, before trying strict ISO-8601.
        try:
            candidate = _dt.datetime.strptime(value, "%m-%d %H:%M")
            now_sh = _dt.datetime.now(_dt.timezone(_dt.timedelta(hours=8)))
            candidate = candidate.replace(year=now_sh.year, tzinfo=_dt.timezone(_dt.timedelta(hours=8)))
            parsed = candidate
        except Exception:
            pass
        if parsed is None:
            try:
                parsed = _dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
                if parsed.tzinfo is None:
                    parsed = parsed.replace(tzinfo=_dt.timezone.utc)
            except Exception:
                return True
        age = (_dt.datetime.now(_dt.timezone.utc) - parsed.astimezone(_dt.timezone.utc)).total_seconds()
        return age > PROBE_STALE_AFTER

    def _entry(name, raw, stale_key="updated_at"):
        return {
            "name": name,
            "updated_at": _updated(raw),
            "is_stale": _stale(raw, stale_key),
            "available": raw is not None,
        }

    return {
        "schema_version": 1,
        "generated_at": _dt.datetime.now().isoformat(),
        "sources": [
            _entry("balance", _read(BALANCE_SNAPSHOT_FILE)),
            _entry("pipio_usage", _read(PIPIO_DETAIL_FILE)),
            _entry("probe", _read(PROBE_STATE_FILE), stale_key="checked_at"),
        ],
    }


def affinity_state():
    return _cached_read("affinity", _build_affinity_state)


def _build_affinity_state():

    """Return the NAS main New API channel-affinity setting and cache summary."""
    options = request("GET", "/api/option/")
    value = next((item.get("value") for item in options
                  if item.get("key") == "channel_affinity_setting.enabled"), None)
    if value is None:
        raise RuntimeError("未找到渠道亲和性开关配置")
    cache = request("GET", "/api/option/channel_affinity_cache")
    return {"enabled": str(value).lower() == "true", "cache": cache}


def set_affinity_enabled(enabled):
    enabled = bool(enabled)
    request("PUT", "/api/option/", {
        "key": "channel_affinity_setting.enabled",
        "value": "true" if enabled else "false",
    })
    state = affinity_state()
    if state["enabled"] != enabled:
        raise RuntimeError("渠道亲和性开关回读不一致")
    return state


def clear_affinity_cache():
    """Clear every affinity entry through the New API admin endpoint."""
    return request("DELETE", "/api/option/channel_affinity_cache?all=true")


def _all_channels():
    data = request("GET", "/api/channel/?p=0&page_size=100")
    return data.get("items", []) if isinstance(data, dict) else []


def _policy_snapshot(channels):
    return {
        str(int(c["id"])): {
            "name": c.get("name", ""),
            "status": int(c.get("status") or 0),
            "priority": int(c.get("priority") or 0),
            "weight": int(c.get("weight") or 0),
        }
        for c in channels
    }


def _write_policy_state(state):
    ROUTING_POLICY_STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
    tmp = ROUTING_POLICY_STATE_FILE.with_suffix(".tmp")
    with open(tmp, "w", encoding="utf-8") as handle:
        json.dump(state, handle, ensure_ascii=False, separators=(",", ":"))
        handle.flush()
        os.fsync(handle.fileno())
    os.chmod(tmp, 0o600)
    os.replace(tmp, ROUTING_POLICY_STATE_FILE)


def _read_policy_state():
    if not ROUTING_POLICY_STATE_FILE.exists():
        return None
    with open(ROUTING_POLICY_STATE_FILE, encoding="utf-8") as handle:
        state = json.load(handle)
    if not isinstance(state.get("channels"), dict):
        raise RuntimeError("路由策略快照损坏，拒绝继续操作")
    return state


def routing_policy_state():
    with _routing_policy_lock:
        state = _read_policy_state()
        if state is None:
            return {"active": False, "mode": "normal", "channel_id": None,
                    "channel_name": "", "applied_at": None, "phase": "idle"}
        return {
            "active": True,
            "mode": state.get("mode", "unknown"),
            "channel_id": state.get("channel_id"),
            "channel_name": state.get("channel_name", ""),
            "applied_at": state.get("applied_at"),
            "phase": state.get("phase", "active"),
            "channels_count": len(state.get("channels", {})),
            "error": state.get("error", ""),
        }


def _set_channel_status_verified(channel_id, status):
    request("POST", "/api/channel/%d/status" % int(channel_id), {"status": int(status)})
    current = request("GET", "/api/channel/%d" % int(channel_id))
    if int(current.get("status") or 0) != int(status):
        raise RuntimeError("渠道 %s 状态回读不一致" % channel_id)


def _set_channel_routing_verified(channel_id, priority, weight):
    payload = {"id": int(channel_id), "priority": int(priority), "weight": int(weight)}
    request("PUT", "/api/channel/", payload)
    current = request("GET", "/api/channel/%d" % int(channel_id))
    if int(current.get("priority") or 0) != payload["priority"]:
        raise RuntimeError("渠道 %s 优先级回读不一致" % channel_id)
    if int(current.get("weight") or 0) != payload["weight"]:
        raise RuntimeError("渠道 %s 权重回读不一致" % channel_id)


def _restore_policy_channels(snapshot):
    errors = []
    # Routing and status are separate New API operations. Attempt every saved
    # channel even if one fails, so rollback is best-effort rather than partial.
    for channel_id, saved in snapshot.items():
        try:
            _set_channel_routing_verified(channel_id, saved["priority"], saved["weight"])
        except Exception as exc:
            errors.append("%s routing: %s" % (channel_id, exc))
    for channel_id, saved in snapshot.items():
        try:
            _set_channel_status_verified(channel_id, saved["status"])
        except Exception as exc:
            errors.append("%s status: %s" % (channel_id, exc))
    if errors:
        raise RuntimeError("; ".join(errors))


def apply_routing_policy(channel_id, mode):
    mode = str(mode or "").lower()
    if mode not in ("preferred", "exclusive"):
        raise ValueError("mode must be preferred or exclusive")
    try:
        channel_id = int(channel_id)
    except (TypeError, ValueError):
        raise ValueError("channel_id must be an integer")

    with _routing_policy_lock:
        if _read_policy_state() is not None:
            raise RuntimeError("已有未恢复的路由策略，请先一键恢复原有配置")
        channels = _all_channels()
        target = next((c for c in channels if int(c.get("id") or 0) == channel_id), None)
        if target is None:
            raise ValueError("目标渠道不存在")
        snapshot = _policy_snapshot(channels)
        state = {
            "schema_version": 1,
            "mode": mode,
            "channel_id": channel_id,
            "channel_name": target.get("name", ""),
            "applied_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
            "phase": "applying",
            "channels": snapshot,
        }
        _write_policy_state(state)
        try:
            target_saved = snapshot[str(channel_id)]
            if target_saved["status"] != 1:
                _set_channel_status_verified(channel_id, 1)
            _set_channel_routing_verified(channel_id, 100, target_saved["weight"])
            if mode == "exclusive":
                for current in channels:
                    current_id = int(current["id"])
                    if current_id != channel_id and int(current.get("status") or 0) != 2:
                        _set_channel_status_verified(current_id, 2)
            clear_affinity_cache()
            state["phase"] = "active"
            _write_policy_state(state)
            return routing_policy_state()
        except Exception as apply_exc:
            try:
                _restore_policy_channels(snapshot)
                clear_affinity_cache()
                ROUTING_POLICY_STATE_FILE.unlink(missing_ok=True)
                raise RuntimeError("策略应用失败，已自动恢复原配置：%s" % apply_exc)
            except RuntimeError as rollback_exc:
                if str(rollback_exc).startswith("策略应用失败"):
                    raise
                state["phase"] = "restore_failed"
                state["error"] = str(rollback_exc)
                _write_policy_state(state)
                raise RuntimeError("策略应用失败且自动恢复不完整，请再次执行一键恢复：%s" % rollback_exc)


def restore_routing_policy():
    with _routing_policy_lock:
        state = _read_policy_state()
        if state is None:
            return routing_policy_state()
        try:
            _restore_policy_channels(state["channels"])
            clear_affinity_cache()
        except Exception as exc:
            state["phase"] = "restore_failed"
            state["error"] = str(exc)
            _write_policy_state(state)
            raise RuntimeError("恢复原配置失败，快照已保留：%s" % exc)
        restored_count = len(state["channels"])
        ROUTING_POLICY_STATE_FILE.unlink(missing_ok=True)
        result = routing_policy_state()
        result["restored_count"] = restored_count
        return result


def set_channel_status(channel_id, status):
    if status not in (1, 2):
        raise ValueError("status must be 1 or 2")
    request("POST", "/api/channel/%d/status" % int(channel_id), {"status": status})
    verified = request("GET", "/api/channel/%d" % int(channel_id))
    view = channel_view(verified)
    if view.get("status") != status:
        raise RuntimeError("New API status readback mismatch")
    return view


def list_models(page=0, page_size=100):
    page = max(0, min(int(page), 9999)); page_size = max(1, min(int(page_size), 100))
    return _cached_read(("models", page, page_size),
                        lambda: request("GET", "/api/models/?p=%d&page_size=%d" % (page, page_size)))


def set_model_status(model_id, status):
    status = int(status)
    if status not in (0, 1):
        raise ValueError("model status must be 0 or 1")
    result = request("PUT", "/api/models/?status_only=true", {"id": int(model_id), "status": status})
    verified = request("GET", "/api/models/%d" % int(model_id))
    if int(verified.get("status", -1)) != status:
        raise RuntimeError("模型状态回读不一致")
    return verified


def update_channel_routing(channel_id, priority=None, weight=None):
    if priority is None and weight is None:
        raise ValueError("priority 或 weight 至少提供一项")
    current = request("GET", "/api/channel/%d" % int(channel_id))
    payload = {"id": int(channel_id)}
    if priority is not None:
        priority = int(priority)
        if priority < 0 or priority > 100:
            raise ValueError("priority 范围 0-100")
        payload["priority"] = priority
    else:
        payload["priority"] = int(current.get("priority") or 0)
    if weight is not None:
        weight = int(weight)
        if weight < 0 or weight > 10000:
            raise ValueError("weight 范围 0-10000")
        payload["weight"] = weight
    else:
        payload["weight"] = int(current.get("weight") or 0)
    request("PUT", "/api/channel/", payload)
    verified = request("GET", "/api/channel/%d" % int(channel_id))
    view = channel_view(verified)
    if "priority" in payload and view.get("priority") != payload["priority"]:
        raise RuntimeError("priority 回读不一致")
    if "weight" in payload and view.get("weight") != payload["weight"]:
        raise RuntimeError("weight 回读不一致")
    return view

def balance_snapshot():
    """Latest channel-balance snapshot written by the NAS cron job.

    The cron script writes /app/balance_snapshot.json (mounted from the
    backend workspace dir); this endpoint just relays it with staleness info.
    """
    if not BALANCE_SNAPSHOT_FILE.exists():
        raise RuntimeError("余额快照尚未生成（等待 NAS 定时任务写入）")
    with open(BALANCE_SNAPSHOT_FILE, encoding="utf-8") as f:
        data = json.load(f)
    import time as _t
    data.setdefault("stale_seconds", None)
    # Both views use the same report snapshot, including its timestamp.
    if PIPIO_DETAIL_FILE.exists():
        with open(PIPIO_DETAIL_FILE, encoding="utf-8") as f:
            report = json.load(f)
        accounts = {a["name"]: dict(a, updated_at=report.get("updated_at"))
                    for a in report.get("accounts", [])}
        for channel in data.get("channels", []):
            account = accounts.get(channel.get("name"))
            if account is not None:
                channel["usage_detail"] = account
                today = account.get("today") if account.get("ok") else None
                channel["today_used_quota"] = today
                channel["today_used_usd"] = today / 500000.0 if today is not None else None
    # WorkBuddy 的「在飞」等是上游瞬时运行态，而快照 600s 才采一次；
    # 这里在返回前用上游 /status 实时覆盖运行态字段，失败则保留快照原值。
    try:
        from collectors import workbuddy_status as _wb
        _wb.apply_runtime_overrides(data)
    except Exception:
        pass
    return data


def pipio_usage_detail(name):
    """Read one Pipio account's detail snapshot produced by Hermes."""
    if name not in ("pipio", "pipio2"):
        raise RuntimeError("只支持 pipio / pipio2")
    if not PIPIO_DETAIL_FILE.exists():
        # 旧版余额任务至少保留了今日与本月起始额度，先提供可用的降级详情。
        snapshot = balance_snapshot()
        channel = next((x for x in snapshot.get("channels", []) if x.get("name") == name), None)
        if channel is None:
            raise RuntimeError("Pipio 使用明细尚未生成")
        return {
            "name": name, "display": name, "ok": True,
            "quota": channel.get("quota"), "today": channel.get("today_used_quota", 0),
            "month_start_quota": channel.get("month_start_quota"),
            "today_calls": None, "today_errors": None, "daily": [], "tokens": [],
            "details_available": False, "updated_at": snapshot.get("updated_at"),
        }
    with open(PIPIO_DETAIL_FILE, encoding="utf-8") as f:
        data = json.load(f)
    account = next((x for x in data.get("accounts", []) if x.get("name") == name), None)
    if account is None:
        raise RuntimeError("未找到 %s 使用明细" % name)
    account["updated_at"] = data.get("updated_at")
    return account
