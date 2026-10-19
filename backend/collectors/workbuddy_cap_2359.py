import fcntl
import json
import math
import os
import tempfile
import urllib.request
from datetime import datetime, timedelta, timezone

from .shadow_store import atomic_write_snapshot, shadow_path

WORKBUDDY_BASE = os.environ.get("WORKBUDDY_BASE", "")
WORKBUDDY_CONFIG = os.environ.get("WORKBUDDY_CONFIG", "/run/secrets/workbuddy/config.json")
SHANGHAI = timezone(timedelta(hours=8))
SHADOW_CAP = "workbuddy_credit_cap.json"
WB_CAP_KEEP_DAYS = 35


def _now_local():
    return datetime.now(SHANGHAI)


def _valid_credit(value):
    return (isinstance(value, (int, float)) and not isinstance(value, bool)
            and math.isfinite(float(value)) and float(value) >= 0
            and float(value).is_integer())


def _read_json(path):
    # 兼容目录挂载（新）与整文件挂载（旧）。
    if os.path.isdir(path):
        path = os.path.join(path, "config.json")
    with open(path, encoding="utf-8") as stream:
        value = json.load(stream)
    return value if isinstance(value, dict) else {}


def _http_json(url, token=None, extra_headers=None, timeout=20):
    headers = {"User-Agent": "Mozilla/5.0"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if extra_headers:
        headers.update(extra_headers)
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8", errors="replace"))


def _load_cap() -> dict:
    try:
        from .shadow_store import snapshot_target
        target = snapshot_target(SHADOW_CAP,
                                 production_relative="workbuddy_credit_cap.json",
                                 switch="NAS_MONITOR_CUTOVER_BALANCE")
        with target.open(encoding="utf-8") as stream:
            value = json.load(stream)
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return {}
    return value if isinstance(value, dict) else {}


def _billing_map():
    """billing 聚合结果 {uid: {"used","remain","size"}}；不可用时返回 {}。

    国际版（realm=global）上游 /status 的 credits 恒为 0（不参与 CN 签到体系），
    其真实余额只能从 billing API 取；基线若直接写 0 会导致 App 端分母为 0。
    """
    try:
        from .workbuddy_status import _workbuddy_credit_cached
    except Exception:
        return {}
    try:
        mapping, err = _workbuddy_credit_cached()
    except Exception:
        return {}
    if err or not isinstance(mapping, dict):
        return {}
    return mapping


def _baseline_credit(account, billing):
    """基线积分：复用采集侧的 realm 感知口径，返回 (credits, source_note)。

    CN 账号以签到积分为准（0 合法）；国际版上游恒为 0，改用 billing 剩余额度，
    两者都拿不到时返回 (None, None)，表示跳过该账号而不是写 0。
    """
    from .workbuddy_status import cap_baseline_credit
    return cap_baseline_credit(account, billing)


def _backfill_missing_days(data, workbuddy, now_local):
    """补写 23:59 cron 漏跑的日期基线（最多回看 3 天）。

    用当天任意时刻抓到的账户 credits 作为该日基线（source 标记 lazy），
    避免后续 _apply_wb_daily_cap 因缺基线而退化到 seed 口径。
    """
    today = now_local.date()
    values = {}
    captured_at = now_local.isoformat(timespec="seconds")
    billing = _billing_map()
    for account in (workbuddy or {}).get("accounts") or []:
        uid = account.get("uid")
        credit, note = _baseline_credit(account, billing)
        if uid and credit is not None:
            entry = {
                "credits": credit,
                "captured_at": captured_at,
                "source": "backfill",
            }
            if note:
                entry["source_note"] = note
            values[str(uid)] = entry
    if not values:
        return 0
    filled = 0
    for offset in (2, 1):
        day = (today - timedelta(days=offset)).isoformat()
        if day in data:
            continue
        data[day] = {
            "effective_date": day,
            "timezone": "Asia/Shanghai",
            "captured_at": captured_at,
            "source": "backfill",
            "values": values,
        }
        filled += 1
    return filled


def _prune_cap(data, local_day):
    cutoff = local_day - timedelta(days=WB_CAP_KEEP_DAYS)
    for key in list(data):
        try:
            day = datetime.fromisoformat(key).date()
        except (TypeError, ValueError):
            continue
        if day < cutoff:
            del data[key]


def _save_cap(data) -> None:
    from .shadow_store import snapshot_target
    target = snapshot_target(SHADOW_CAP,
                             production_relative="workbuddy_credit_cap.json",
                             switch="NAS_MONITOR_CUTOVER_BALANCE")
    target.parent.mkdir(parents=True, exist_ok=True)
    lock_path = target.with_suffix(target.suffix + ".lock")
    with open(lock_path, "a+", encoding="utf-8") as lock:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
        try:
            fd, tmp = tempfile.mkstemp(prefix=".workbuddy_credit_cap.", suffix=".tmp", dir=str(target.parent))
            try:
                with os.fdopen(fd, "w", encoding="utf-8") as stream:
                    json.dump(data, stream, ensure_ascii=False, sort_keys=True, indent=2)
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


def roll_cap(workbuddy, now_local, source):
    local_now = now_local.astimezone(SHANGHAI)
    day = (local_now.date() + timedelta(days=1)).isoformat()
    values = {}
    captured_at = local_now.isoformat(timespec="seconds")
    billing = _billing_map()
    for account in (workbuddy or {}).get("accounts") or []:
        uid = account.get("uid")
        credit, note = _baseline_credit(account, billing)
        if uid and credit is not None:
            entry = {
                "credits": credit,
                "captured_at": captured_at,
                "source": source,
            }
            if note:
                entry["source_note"] = note
            values[str(uid)] = entry
    if not values:
        return 0
    data = _load_cap()
    data[day] = {
        "effective_date": day,
        "timezone": "Asia/Shanghai",
        "captured_at": captured_at,
        "source": source,
        "values": values,
    }
    _prune_cap(data, local_now.date())
    _save_cap(data)
    return len(values)


def collect() -> dict:
    try:
        cfg = _read_json(WORKBUDDY_CONFIG)
    except Exception as exc:
        raise RuntimeError("workbuddy config 不可用: %s" % str(exc)[:120])
    key = cfg.get("api_key") or ""
    if not key:
        raise RuntimeError("workbuddy2api 未找到 api_key")
    payload = _http_json(WORKBUDDY_BASE + "/status", token=key, extra_headers={"Accept": "application/json"})
    if isinstance(payload, dict) and payload.get("error"):
        raise RuntimeError(str(payload["error"])[:120])
    if not isinstance(payload, dict):
        raise RuntimeError("响应格式异常")
    now_local = _now_local()
    data = _load_cap()
    _backfill_missing_days(data, payload, now_local)
    _save_cap(data)
    count = roll_cap(payload, now_local, source="cron_2359")
    if count == 0:
        raise RuntimeError("未抓到任何有效账户积分，不写基线")
    return {"source": "cron_2359", "effective_date": (now_local.date() + timedelta(days=1)).isoformat(), "count": count}
