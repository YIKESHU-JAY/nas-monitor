import json
import os
import time
import urllib.request
from collections import Counter
from datetime import datetime, timedelta, timezone
from typing import Optional

from .shadow_store import atomic_write_snapshot

API = "https://pipio.io"
# 每个账号一个目录（compose 挂目录而非单文件，外部 token 丢失也不会阻塞容器启动）。
SECRET_DIR = os.environ.get("PIPIO_SECRET_DIR", "/run/secrets/pipio")
SECRET_DIR2 = os.environ.get("PIPIO2_SECRET_DIR", "/run/secrets/pipio2")
ACCOUNTS = (
    {"name": "pipio", "uid": "123", "display": "一棵树爱吃草", "token_file": "pipio-admin-token.txt", "secret_dir": SECRET_DIR},
    {"name": "pipio2", "uid": "1455", "display": "星群矩阵", "token_file": "pipio2-admin-token.txt", "secret_dir": SECRET_DIR2},
)
Q = 500000  # quota / 500000 = USD
SHANGHAI = timezone(timedelta(hours=8))


def _now_iso() -> str:
    return datetime.now(SHANGHAI).isoformat()


def _display_when() -> str:
    now = datetime.now(SHANGHAI)
    return now.strftime("%Y-%m-%d %H:%M")


def _read_token(token_file: str, secret_dir: str = SECRET_DIR) -> str:
    path = os.path.join(secret_dir, token_file)
    try:
        with open(path, encoding="utf-8") as stream:
            return stream.read().strip()
    except Exception:
        return ""


def _fetch(path: str, token: str, uid: str) -> dict:
    req = urllib.request.Request(API + path)
    req.add_header("Authorization", "Bearer " + token)
    req.add_header("Pipio-User", str(uid))
    req.add_header("User-Agent", "Mozilla/5.0")
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.loads(response.read().decode("utf-8", errors="replace"))


def _model_cache_rates(token: str, uid: str, start: int, end: int) -> dict:
    """按官方调用明细汇总各模型的缓存 Token 占比。"""
    page_size = 100
    first = _fetch(
        "/api/log/self?p=0&page_size=%d&type=2&start_timestamp=%d&end_timestamp=%d"
        % (page_size, start, end), token, uid
    )
    first_data = first.get("data") or {}
    rows = list(first_data.get("items") or []) if isinstance(first_data, dict) else []
    try:
        total = max(0, int(first_data.get("total") or len(rows)))
    except (TypeError, ValueError):
        total = len(rows)
    pages = max(1, (total + page_size - 1) // page_size)
    for page in range(2, pages + 1):
        result = _fetch(
            "/api/log/self?p=%d&page_size=%d&type=2&start_timestamp=%d&end_timestamp=%d"
            % (page, page_size, start, end), token, uid
        )
        page_data = result.get("data") or {}
        if isinstance(page_data, dict):
            rows.extend(page_data.get("items") or [])

    totals = {}
    for row in rows:
        if not isinstance(row, dict):
            continue
        model = row.get("model_name") or "-"
        try:
            prompt_tokens = float(row.get("prompt_tokens") or 0)
        except (TypeError, ValueError):
            prompt_tokens = 0.0
        other = row.get("other") or {}
        if isinstance(other, str):
            try:
                other = json.loads(other)
            except (TypeError, ValueError):
                other = {}
        if not isinstance(other, dict):
            other = {}
        try:
            cache_tokens = float(other.get("cache_tokens") or 0)
        except (TypeError, ValueError):
            cache_tokens = 0.0
        if prompt_tokens <= 0 or cache_tokens < 0:
            continue
        item = totals.setdefault(model, [0.0, 0.0])
        item[0] += prompt_tokens
        item[1] += cache_tokens
    return {
        model: min(1.0, max(0.0, cached / prompt))
        for model, (prompt, cached) in totals.items()
        if prompt > 0
    }


def _day0(offset: int = 0) -> int:
    """上海时区当日 00:00 的时间戳。"""
    now = datetime.now(SHANGHAI)
    base = datetime(now.year, now.month, now.day, tzinfo=SHANGHAI)
    return int((base + timedelta(days=offset)).timestamp())


def _collect(token: str, uid: str, display: str, name: str) -> dict:
    now = int(time.time())
    t0 = _day0()
    d = {"name": name, "display": display, "ok": False}

    me = _fetch("/api/user/self", token, uid).get("data", {})
    d["quota"] = me.get("quota", 0)
    d["used_total"] = me.get("used_quota", 0)

    def spend(start, end):
        r = _fetch("/api/log/self/stat?type=2&start_timestamp=%d&end_timestamp=%d" % (start, end), token, uid)
        return r.get("data", {}).get("quota", 0)

    daily = []
    for i in range(6, 0, -1):
        daily.append(spend(_day0(-i), _day0(-i + 1) - 1))
    daily.append(spend(t0, now))
    d["daily"] = daily
    d["today"] = daily[-1]
    d["yest"] = daily[-2]
    d["week"] = sum(daily)

    def total(q):
        r = _fetch("/api/log/self?p=0&page_size=1&%s" % q, token, uid)
        return r.get("data", {}).get("total", 0)

    d["today_calls"] = total("type=2&start_timestamp=%d&end_timestamp=%d" % (t0, now))
    d["today_err"] = total("type=5&start_timestamp=%d&end_timestamp=%d" % (t0, now))

    rows = _fetch("/api/data/flow/self?start_timestamp=%d&end_timestamp=%d" % (t0, now), token, uid).get("data") or []
    try:
        cache_rates = _model_cache_rates(token, uid, t0, now)
    except Exception:
        cache_rates = {}
    qt, ct, qm = Counter(), Counter(), Counter()
    for r in rows:
        n = r.get("token_name") or "-"
        qt[n] += r.get("quota", 0)
        ct[n] += r.get("count", 0)
        qm[r.get("model_name") or "-"] += r.get("quota", 0)
    d["tok"] = [(k, v, ct[k]) for k, v in qt.most_common(3)]
    d["mod"] = [(k, v) for k, v in qm.most_common(3)]
    d["cache_rates"] = cache_rates
    d["ok"] = True
    return d


def _usd(q) -> float:
    return q / Q


def collect() -> dict:
    items = []
    any_ok = False
    for account in ACCOUNTS:
        name = account["name"]
        token = _read_token(account["token_file"], account["secret_dir"])
        if not token:
            data = {"name": name, "display": account["display"], "ok": False,
                    "err": "无令牌文件", "tok": [], "mod": [], "daily": [0] * 7}
        else:
            try:
                data = _collect(token, account["uid"], account["display"], name)
                any_ok = any_ok or bool(data.get("ok"))
            except Exception as exc:
                data = {"name": name, "display": account["display"], "ok": False,
                        "err": str(exc)[:60], "tok": [], "mod": [], "daily": [0] * 7}

        full = (data.get("daily") or [])[:-1]
        avg = _usd(sum(full)) / len(full) if full else 0.0
        days = int(_usd(data.get("quota") or 0) / avg) if data.get("ok") and avg > 0.5 else None
        items.append({"d": data, "daily7": avg, "days": days})

    if not any_ok:
        raise RuntimeError("Pipio 用量明细全部渠道失败，保留上一份有效快照")

    accounts = []
    for it in items:
        d = it["d"]
        accounts.append({
            "name": d.get("name"), "display": d.get("display"),
            "ok": bool(d.get("ok")), "error": d.get("err"),
            "quota": d.get("quota"), "used_total": d.get("used_total"),
            "today": d.get("today", 0), "yesterday": d.get("yest", 0),
            "week": d.get("week", 0), "daily": d.get("daily", []),
            "today_calls": d.get("today_calls", 0), "today_errors": d.get("today_err", 0),
            "tokens": [{"name": n, "quota": q, "calls": c} for n, q, c in d.get("tok", [])],
            "models": [{"name": n, "quota": q, "cache_rate": d.get("cache_rates", {}).get(n)} for n, q in d.get("mod", [])],
            "daily_average_usd": it.get("daily7", 0), "support_days": it.get("days"),
        })
    payload = {"updated_at": _display_when(), "accounts": accounts, "source": "backend-shadow"}
    atomic_write_snapshot("pipio_usage_snapshot.json", payload,
                          production_relative="pipio_usage_snapshot.json",
                          switch="NAS_MONITOR_CUTOVER_USAGE")
    return payload
