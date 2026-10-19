"""Aggregate backend-owned probe state into the production-shaped file.

Reads the backend shadow collectors (Pipio uptime models + cron registry
tasks) and merges them into the same schema as Hermes' probe_state.py output,
so /api/newapi/probe stays source-compatible after cutover.

Codex main-pool is intentionally excluded: the user dropped that monitor.

This collector stays shadow-only: it writes BACKEND_ROOT/shadow/probe_state/
until cutover flips the aggregate store to the production path.
"""
import fcntl
import json
import os
import tempfile
from datetime import datetime, timedelta, timezone

from .shadow_store import atomic_write_snapshot, shadow_path

SHANGHAI = timezone(timedelta(hours=8))
STALE_AFTER = 15 * 60

PIPIO_UPTIME_FILE = "pipio_uptime.json"
CRON_REGISTRY_FILE = "cron_registry.json"
AGGREGATE_FILE = "probe_state/model_channel_probe.json"

# Single source of truth for excluding the dropped Codex main-pool task.
_EXCLUDED_SCRIPT_TOKENS = ("codex_main_pool",)


def _now() -> str:
    return datetime.now(SHANGHAI).isoformat()


def _load(relative: str) -> dict:
    try:
        with shadow_path(relative).open(encoding="utf-8") as stream:
            value = json.load(stream)
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return {}
    return value if isinstance(value, dict) else {}


def _safe_int(value, default=0):
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def _source_age(value, now):
    """Age in seconds of an ISO-8601 source timestamp; None when unparseable."""
    if not value:
        return None
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except Exception:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=SHANGHAI)
    return (now - parsed.astimezone(SHANGHAI)).total_seconds()


def _model_items():
    data = _load(PIPIO_UPTIME_FILE)
    items = data.get("items") or []
    if not isinstance(items, list):
        return [], None
    return items, data.get("updated_at")


def _task_items():
    """Cron-registry task items, minus the dropped Codex main-pool task."""
    data = _load(CRON_REGISTRY_FILE)
    items = data.get("items") or []
    if not isinstance(items, list):
        return [], None
    filtered = []
    for item in items:
        if not isinstance(item, dict):
            continue
        script = str(item.get("script") or "")
        if any(token in script.casefold() for token in _EXCLUDED_SCRIPT_TOKENS):
            continue
        filtered.append(item)
    return filtered, data.get("checked_at")


def _atomic_write_result(result):
    """Write the full aggregate result under an exclusive flock."""
    from .shadow_store import snapshot_target
    target = snapshot_target(AGGREGATE_FILE,
                             production_relative="probe_state/model_channel_probe.json",
                             switch="NAS_MONITOR_CUTOVER_PROBE")
    target.parent.mkdir(parents=True, exist_ok=True)
    lock_path = str(target) + ".lock"
    with open(lock_path, "a+", encoding="utf-8") as lock:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
        try:
            fd, tmp = tempfile.mkstemp(prefix=f".{target.name}.", dir=str(target.parent))
            try:
                with os.fdopen(fd, "w", encoding="utf-8") as stream:
                    json.dump(result, stream, ensure_ascii=False, separators=(",", ":"))
                    stream.write("\n")
                    stream.flush()
                    os.fsync(stream.fileno())
                os.replace(tmp, target)
            except Exception:
                try:
                    os.unlink(tmp)
                except FileNotFoundError:
                    pass
                raise
        finally:
            fcntl.flock(lock.fileno(), fcntl.LOCK_UN)


def _read_previous():
    """Read previous counters/transitions; empty on any malformed input."""
    try:
        from .shadow_store import snapshot_target
        target = snapshot_target(AGGREGATE_FILE,
                                 production_relative="probe_state/model_channel_probe.json",
                                 switch="NAS_MONITOR_CUTOVER_PROBE")
        with target.open(encoding="utf-8") as stream:
            prev = json.load(stream)
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return {}, []
    if not isinstance(prev, dict):
        return {}, []
    previous = {}
    for row in (prev.get("items") or []):
        if isinstance(row, dict):
            previous[(str(row.get("kind")), str(row.get("id") or row.get("name")))] = row
    transitions = prev.get("transitions")
    if not isinstance(transitions, list):
        transitions = []
    return previous, list(transitions)


def collect() -> dict:
    model_items, model_checked = _model_items()
    task_items, task_checked = _task_items()
    if not model_items:
        raise RuntimeError("Pipio uptime shadow 尚未生成，保留上一份聚合探针")
    # Cron 注册表来自 Hermes（trim.hermes），属可选数据源：
    # Hermes 掉线或 /vol6 异常时只隐藏任务项并标记该来源不可用，不阻断 Pipio 探针。
    cron_available = bool(task_items)

    now = datetime.now(SHANGHAI)
    checked_at = now.isoformat()

    model_age = _source_age(model_checked, now)
    task_age = _source_age(task_checked, now)
    model_stale = model_age is None or model_age > STALE_AFTER
    task_stale = task_age is None or task_age > STALE_AFTER

    previous, transitions = _read_previous()
    merged = []
    for item in model_items + task_items:
        row = dict(item)
        key = (str(row.get("kind", "model")), str(row.get("id") or row.get("name")))
        old = previous.get(key)
        if old:
            if row.get("status") in ("healthy", "degraded"):
                row["consecutive_successes"] = _safe_int(old.get("consecutive_successes")) + 1
                row["consecutive_failures"] = 0
            elif row.get("status") == "failed":
                row["consecutive_failures"] = _safe_int(old.get("consecutive_failures")) + 1
                row["consecutive_successes"] = 0
            else:
                row["consecutive_failures"] = _safe_int(old.get("consecutive_failures"))
                row["consecutive_successes"] = _safe_int(old.get("consecutive_successes"))
            if old.get("status") != row.get("status"):
                transitions.append({
                    "at": checked_at, "kind": row.get("kind"), "id": row.get("id"),
                    "name": row.get("name"), "from": old.get("status"), "to": row.get("status"),
                })
        else:
            row["consecutive_failures"] = 0
            row["consecutive_successes"] = 0
        merged.append(row)

    if model_stale or task_stale:
        for item in merged:
            if (item.get("kind") == "model" and model_stale) or \
               (item.get("kind") == "task" and task_stale):
                if item.get("status") not in ("failed",):
                    item["status"] = "unknown"

    counts = {"healthy": 0, "degraded": 0, "failed": 0, "unknown": 0}
    for item in merged:
        status = item.get("status", "unknown")
        counts[status if status in counts else "unknown"] += 1

    if counts["failed"]:
        probe_status = "degraded"
    elif counts["unknown"]:
        probe_status = "unknown"
    else:
        probe_status = "ok"

    sources = {
        "pipio-gpt56": {
            "status": "failed" if model_stale else "ok",
            "checked_at": model_checked or checked_at,
            "error": "数据源已过期" if model_stale else "",
        },
        "cron-registry": {
            "status": "unavailable" if not cron_available else ("failed" if task_stale else "ok"),
            "checked_at": task_checked or checked_at,
            "error": "未配置（可选数据源）" if not cron_available else ("数据源已过期" if task_stale else ""),
            "task_count": len(task_items),
        },
    }

    result = {
        "schema_version": 1,
        "checked_at": checked_at,
        "stale_after_seconds": STALE_AFTER,
        "probe_status": probe_status,
        "sources": sources,
        "items": merged,
        "summary": {"total": len(merged), **counts},
        "transitions": transitions,
    }
    _atomic_write_result(result)
    return result
