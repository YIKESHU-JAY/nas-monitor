import json
import os
import urllib.request
from datetime import datetime, timedelta, timezone
from typing import Any, Optional

from .shadow_store import atomic_write_json, shadow_path

RATE = 500000  # 50 万额度 ≈ $1
API = "https://pipio.io/api/user/self"
# 每个账号一个目录（compose 挂目录而非单文件，外部 token 丢失也不会阻塞容器启动）。
SECRET_DIR = os.environ.get("PIPIO_SECRET_DIR", "/run/secrets/pipio")
SECRET_DIR2 = os.environ.get("PIPIO2_SECRET_DIR", "/run/secrets/pipio2")
ACCOUNTS = (
    {"name": "pipio", "uid": "123", "token_file": "pipio-admin-token.txt", "secret_dir": SECRET_DIR},
    {"name": "pipio2", "uid": "1455", "token_file": "pipio2-admin-token.txt", "secret_dir": SECRET_DIR2},
)


def _now() -> str:
    return datetime.now(timezone(timedelta(hours=8))).isoformat()


def _read_token(token_file: str, secret_dir: str = SECRET_DIR) -> str:
    path = os.path.join(secret_dir, token_file)
    try:
        with open(path, encoding="utf-8") as stream:
            return stream.read().strip()
    except Exception:
        return ""


def _fetch_balance(name: str, uid: str, token: str) -> dict:
    if not token:
        return {"name": name, "usd": None, "quota": None, "error": "无管理令牌文件"}
    request = urllib.request.Request(API)
    request.add_header("Authorization", "Bearer " + token)
    request.add_header("Pipio-User", uid)
    request.add_header("User-Agent", "Mozilla/5.0")
    try:
        with urllib.request.urlopen(request, timeout=45) as response:
            payload = json.load(response)
    except Exception as exc:
        return {"name": name, "usd": None, "quota": None, "error": str(exc)[:120]}
    data = (payload or {}).get("data") or {}
    quota = data.get("quota")
    if quota is None:
        return {"name": name, "usd": None, "quota": None, "error": "接口无 quota 字段"}
    usd = round(quota / RATE, 2)
    return {"name": name, "usd": usd, "quota": quota,
            "used_quota": data.get("used_quota"),
            "display_name": data.get("display_name")}


def _today_key() -> str:
    return datetime.now().strftime("%Y-%m-%d")


def _month_key() -> str:
    return datetime.now().strftime("%Y-%m")


def _load_shadow_json(relative: str) -> dict:
    try:
        with shadow_path(relative).open(encoding="utf-8") as stream:
            value = json.load(stream)
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return {}
    return value if isinstance(value, dict) else {}


def _save_shadow_json(relative: str, value: dict) -> None:
    atomic_write_json(relative, value)


def _today_used(used_quota_now: Optional[float], channel_name: str) -> Optional[float]:
    if used_quota_now is None:
        return None
    today = _today_key()
    base = _load_shadow_json("balance_baseline.json")
    base = {k: v for k, v in base.items() if k == today}
    today_base = base.get(today, {})
    prev = today_base.get(channel_name)
    if prev is None:
        today_base[channel_name] = used_quota_now
        base[today] = today_base
        _save_shadow_json("balance_baseline.json", base)
        return None
    if used_quota_now < prev:
        return None
    return used_quota_now - prev


def _month_start_quota(quota_now: Optional[float], channel_name: str) -> Optional[float]:
    if quota_now is None:
        return None
    month = _month_key()
    base = _load_shadow_json("balance_month_baseline.json")
    base = {k: v for k, v in base.items() if k == month}
    month_base = base.get(month, {})
    if channel_name not in month_base:
        month_base[channel_name] = quota_now
        base[month] = month_base
        _save_shadow_json("balance_month_baseline.json", base)
        return quota_now
    return month_base[channel_name]


def collect() -> dict:
    now_str = datetime.now(timezone(timedelta(hours=8))).strftime("%Y-%m-%dT%H:%M:%S+08:00")
    channels = []
    total = 0.0
    for account in ACCOUNTS:
        name = account["name"]
        token = _read_token(account["token_file"], account["secret_dir"])
        info = _fetch_balance(name, account["uid"], token)
        if info.get("error") or info.get("quota") is None:
            channels.append({
                "name": name, "usd": None, "quota": None,
                "today_used_quota": None, "today_used_usd": None,
                "month_start_quota": None, "month_start_usd": None,
                "error": info.get("error"),
            })
            continue
        quota = info["quota"]
        usd = info["usd"]
        used_quota = info.get("used_quota")
        today_q = _today_used(used_quota, name)
        today_usd = round(today_q / RATE, 4) if isinstance(today_q, (int, float)) else None
        month_q = _month_start_quota(quota, name)
        month_usd = round(month_q / RATE, 2) if isinstance(month_q, (int, float)) else None
        total += usd
        channels.append({
            "name": name, "usd": usd, "quota": quota,
            "today_used_quota": today_q, "today_used_usd": today_usd,
            "month_start_quota": month_q, "month_start_usd": month_usd,
            "error": None,
        })

    # 全部失败则不覆盖上一份有效 shadow 快照
    if all(c.get("error") for c in channels):
        raise RuntimeError("Pipio balance 全部渠道失败，保留上一份有效快照")

    result = {
        "updated_at": now_str,
        "channels": channels,
        "total": round(total, 2),
        "source": "backend-shadow",
    }
    atomic_write_json("balance_snapshot.json", result)
    return result
