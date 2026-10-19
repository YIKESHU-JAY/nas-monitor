import json
import urllib.request
from datetime import datetime, timedelta, timezone
from typing import Optional

from .shadow_store import atomic_write_json

URL = "https://pipio.io/api/uptime/status"
WATCH_PREFIX = "gpt-5.6"
WATCH_MODELS = ("gemini-3.8-flash-high", "claude-sonnet-4-6", "gpt-6-astra")


def _uptime_ratio(raw) -> Optional[float]:
    """返回 [0,1] 可用率；非有限数值返回 None。"""
    try:
        value = float(raw)
    except (TypeError, ValueError):
        return None
    if value != value or value in (float("inf"), float("-inf")):
        return None
    if value > 1:
        value /= 100.0
    if value < 0 or value > 1:
        return None
    return value


def _status(m):
    """对齐 Hermes monitor_pipio_gpt56_local.py 的档位规则。"""
    status = m.get("status")
    if status == 0 or status == "0":
        return "failed"
    ratio = _uptime_ratio(m.get("uptime"))
    if ratio is None:
        return "unknown"
    return "degraded" if ratio < 0.90 else "healthy"


def _monitors(payload):
    """真实 API 为 {data:[{monitors:[{name,status,uptime,...}]}]}。"""
    for cat in (payload or {}).get("data", []):
        for item in cat.get("monitors", []):
            if isinstance(item, dict):
                yield item


def collect():
    request = urllib.request.Request(URL, headers={"Accept": "application/json", "User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(request, timeout=45) as response:
        payload = json.load(response)

    now = datetime.now(timezone(timedelta(hours=8))).isoformat()
    items = []
    for item in _monitors(payload):
        name = str(item.get("name") or "")
        if not (name.startswith(WATCH_PREFIX) or name in WATCH_MODELS):
            continue
        ratio = _uptime_ratio(item.get("uptime"))
        status = _status(item)
        evidence = "24h 可用性 %.1f%%" % (ratio * 100.0) if ratio is not None else "可用性未知"
        items.append({
            "kind": "model",
            "provider": "Pipio",
            "id": "pipio:" + name,
            "name": "Pipio · " + name,
            "status": status,
            "evidence": evidence,
            "checked_at": now,
        })

    if not items:
        # 空数据不可靠：不覆盖上一份有效快照，标记本次失败由调度器记入状态。
        raise RuntimeError("Pipio uptime API 返回空监控集，保留上一份有效快照")

    result = {"updated_at": now, "source": URL, "items": items}
    atomic_write_json("pipio_uptime.json", result)
    return result
