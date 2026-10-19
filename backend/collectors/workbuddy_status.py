import glob
import json
import os
import threading
import time
import urllib.request
from datetime import datetime, timedelta, timezone

from .shadow_store import atomic_write_json, atomic_write_snapshot, shadow_path, snapshot_target

WORKBUDDY_BASE = os.environ.get("WORKBUDDY_BASE", "")
WORKBUDDY_CONFIG = os.environ.get("WORKBUDDY_CONFIG", "/run/secrets/workbuddy/config.json")
WORKBUDDY_WAN1_BASE = os.environ.get("WORKBUDDY_WAN1_BASE", "")
WORKBUDDY_WAN1_CONFIG = os.environ.get("WORKBUDDY_WAN1_CONFIG", "/run/secrets/workbuddy-wan1/config.json")
WORKBUDDY_AUTH_DIR = os.environ.get("WORKBUDDY_AUTH_DIR", "/app/secrets/workbuddy-auths")
WORKBUDDY_WAN3_BASE = os.environ.get("WORKBUDDY_WAN3_BASE", "")
WORKBUDDY_WAN3_CONFIG = os.environ.get("WORKBUDDY_WAN3_CONFIG", "/run/secrets/workbuddy-wan3/config.json")
# 各出口实例的授权目录：默认出口 / WAN1 各有独立 auths，
# billing 积分必须按分组各自读取，否则 WAN1 账号取不到额度。
#
# wan3 不在本机：它的账号目录在 remote-nas 上，此前用 NFS 挂进容器。
# 实测该 NFS 导出会抖动（导出为空 / root-squash 后 0700 目录读不到），
# 且挂载失败会拖住 docker 启动，故**不再挂 NFS**。wan3 的账号清单与
# 实时积分改走 remote-nas 上的 workbuddy-manager HTTP 接口（见 _panel_*）。
WORKBUDDY_WAN1_AUTH_DIR = os.environ.get("WORKBUDDY_WAN1_AUTH_DIR", "/app/secrets/workbuddy-wan1-auths")
_WORKBUDDY_AUTH_DIRS = (WORKBUDDY_AUTH_DIR, WORKBUDDY_WAN1_AUTH_DIR)
# wan3 面板（remote-nas 上的 workbuddy-manager）地址与只读 API Token。
# 两者都配了才启用；未配置时 wan3 仍可读上游 /status，只是拿不到实时积分。
WORKBUDDY_WAN3_PANEL = os.environ.get("WORKBUDDY_WAN3_PANEL", "")
WORKBUDDY_WAN3_PANEL_TOKEN = os.environ.get("WORKBUDDY_WAN3_PANEL_TOKEN", "")
SHANGHAI = timezone(timedelta(hours=8))

_CREDIT_CACHE = {"ts": 0.0, "value": None}
_CREDIT_CACHE_TTL = 600  # 10 分钟
_AUTH_FINGERPRINT = None


def _read_json(path):
    # 兼容两种挂载：目录挂载（新，默认）与整文件挂载（旧）。
    # 路径指向目录时取目录内的 config.json；文件缺失时抛错，由调用方按分组降级。
    if os.path.isdir(path):
        path = os.path.join(path, "config.json")
    with open(path, encoding="utf-8") as stream:
        value = json.load(stream)
    return value if isinstance(value, dict) else {}


def _http_json(url, token=None, extra_headers=None, timeout=20, data=None):
    headers = {"User-Agent": "Mozilla/5.0"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if extra_headers:
        headers.update(extra_headers)
    if data is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(data).encode()
    req = urllib.request.Request(url, data=data, headers=headers, method="POST" if data is not None else "GET")
    with urllib.request.urlopen(req, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8", errors="replace"))


def _load_shadow_json(relative):
    try:
        with shadow_path(relative).open(encoding="utf-8") as stream:
            value = json.load(stream)
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return {}
    return value if isinstance(value, dict) else {}


def _save_shadow_json(relative, value):
    atomic_write_json(relative, value)

def _load_cap_cutover():
    target = snapshot_target("workbuddy_credit_cap.json",
                             production_relative="workbuddy_credit_cap.json",
                             switch="NAS_MONITOR_CUTOVER_BALANCE")
    try:
        with target.open(encoding="utf-8") as stream:
            value = json.load(stream)
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return {}
    return value if isinstance(value, dict) else {}


def _valid_credit(value):
    import math
    return (isinstance(value, (int, float)) and not isinstance(value, bool)
            and math.isfinite(float(value)) and float(value) >= 0
            and float(value).is_integer())


def _auth_dirs_fingerprint():
    """auths 目录文件指纹：新增/删除账号时变化，用于使积分缓存立即失效。"""
    parts = []
    for directory in _WORKBUDDY_AUTH_DIRS:
        try:
            names = sorted(os.listdir(directory))
        except OSError:
            names = []
        stat_parts = []
        for name in names:
            try:
                st = os.stat(os.path.join(directory, name))
                stat_parts.append((name, st.st_size, int(st.st_mtime)))
            except OSError:
                continue
        parts.append((directory, tuple(stat_parts)))
    return tuple(parts)


def _today_key():
    return datetime.now(SHANGHAI).strftime("%Y-%m-%d")


def _today_used(used_now, channel_name):
    """今日消耗 = 当前累计值 - 今日基线累计值；wb 月累计月初归零，回退视为周期重置。"""
    if used_now is None:
        return None
    today = _today_key()
    base = _load_shadow_json("balance_baseline.json")
    base = {k: v for k, v in base.items() if k == today}
    today_base = base.get(today, {})
    prev = today_base.get(channel_name)
    if prev is None:
        today_base[channel_name] = used_now
        base[today] = today_base
        _save_shadow_json("balance_baseline.json", base)
        return None
    if used_now < prev:
        return used_now
    return used_now - prev


def cap_baseline_credit(account, credit_map):
    """基线分母候选值：返回 (credits, source_note)；None 表示跳过该账号。

    国际版（realm=global）上游 /status 的 credits 恒为 0（不参与 CN 签到），
    其真实余额只能取自 billing；把 0 当基线会让 App 端分母为 0。
    CN 账号以签到积分为准，0 是合法值（当日额度已用尽）。
    """
    uid = str(account.get("uid") or "")
    realm = str(account.get("realm") or "").strip().lower()
    credit = account.get("credits")
    if realm != "global" and _valid_credit(credit):
        return int(credit), None
    entry = (credit_map or {}).get(uid)
    remain = entry.get("remain") if isinstance(entry, dict) else None
    if _valid_credit(remain) and int(remain) > 0:
        return int(remain), "billing_remain"
    if _valid_credit(credit) and int(credit) > 0:
        return int(credit), None
    return None, None


def _roll_cap_shadow(workbuddy, now_local, source, effective_date=None, merge_missing=False):
    """shadow 版 roll_wb_cap：写 shadow workbuddy_credit_cap.json。"""
    local_now = now_local.astimezone(SHANGHAI)
    day = effective_date or (local_now.date() + timedelta(days=1)).isoformat()
    values = {}
    captured_at = local_now.isoformat(timespec="seconds")
    credit_map, _credit_err = _workbuddy_credit_cached()
    for account in (workbuddy or {}).get("accounts") or []:
        uid = account.get("uid")
        credit, note = cap_baseline_credit(account, credit_map)
        if uid and credit is not None:
            entry = {"credits": credit, "captured_at": captured_at, "source": source}
            if note:
                entry["source_note"] = note
            values[str(uid)] = entry
    if not values:
        return 0
    data = _load_cap_cutover()
    existing = data.get(day)
    if isinstance(existing, dict) and not merge_missing:
        return 0
    if isinstance(existing, dict) and merge_missing:
        old_values = existing.get("values") if isinstance(existing.get("values"), dict) else {}
        missing = {uid: value for uid, value in values.items() if uid not in old_values}
        # 修正历史误写的 0 基线。两类账号的 0 都不是真实额度，拿到正数就该覆盖，
        # 否则 App 端分母一直是 0（表现为「积分 0 / 0」）：
        #   · 国际版：上游 /status 的 credits 恒为 0，0 只代表「上游无此口径」；
        #   · 走了权威兜底的账号（面板代查 / billing）：上游本地额度尚未同步时为 0，
        #     例如 wan3 的账号目录不在本机、上游实例本地 credits 一直是 0。
        # 只覆盖「有权威正数」的情形，CN 账号当日额度用尽的 0 不受影响。
        repaired = {}
        authoritative_uids = set()
        for a in (workbuddy or {}).get("accounts") or []:
            if not isinstance(a, dict):
                continue
            if str(a.get("credit_source") or "") in ("panel_credits", "billing_remain", "state_fallback"):
                authoritative_uids.add(str(a.get("uid") or ""))
        global_uids = {str(a.get("uid") or "") for a in (workbuddy or {}).get("accounts") or []
                       if str(a.get("realm") or "").strip().lower() == "global"}
        for uid, value in values.items():
            if uid not in global_uids and uid not in authoritative_uids:
                continue
            old = old_values.get(uid)
            old_credits = old.get("credits") if isinstance(old, dict) else old
            if uid in old_values and isinstance(old_credits, (int, float)) and not isinstance(old_credits, bool) \
                    and int(old_credits) == 0 and int(value.get("credits") or 0) > 0:
                repaired[uid] = value
        if not missing and not repaired:
            return 0
        existing["values"] = {**old_values, **missing, **repaired}
        existing["source"] = existing.get("source") or source
        data[day] = existing
    else:
        data[day] = {
            "effective_date": day, "timezone": "Asia/Shanghai",
            "captured_at": captured_at, "source": source, "values": values,
        }
    atomic_write_snapshot("workbuddy_credit_cap.json", data,
                          production_relative="workbuddy_credit_cap.json",
                          switch="NAS_MONITOR_CUTOVER_BALANCE")
    return len(values)


def _apply_wb_daily_cap(workbuddy, now_local):
    local_now = now_local.astimezone(SHANGHAI)
    today = local_now.date().isoformat()
    try:
        _roll_cap_shadow(workbuddy, local_now, source="lazy", effective_date=today, merge_missing=True)
    except Exception:
        pass
    data = _load_cap_cutover()
    record = data.get(today) if isinstance(data.get(today), dict) else None
    values = record.get("values") if isinstance(record, dict) else {}
    if not isinstance(values, dict):
        values = {}
    captured_at = local_now.isoformat(timespec="seconds")
    for entry in (workbuddy or {}).get("accounts") or []:
        uid = str(entry.get("uid") or "")
        raw_value = values.get(uid)
        if isinstance(raw_value, dict):
            base_value = raw_value.get("credits")
            raw_source = raw_value.get("source") or record.get("source")
            raw_captured_at = raw_value.get("captured_at")
        else:
            base_value = raw_value
            raw_source = record.get("source") if isinstance(record, dict) else None
            raw_captured_at = record.get("captured_at") if isinstance(record, dict) else None
        is_global = str(entry.get("realm") or "").strip().lower() == "global"
        if uid in values and _valid_credit(base_value) and not (is_global and int(base_value) <= 0):
            entry["daily_cap"] = int(base_value)
            entry["daily_cap_source"] = "lazy" if raw_source == "lazy" else "baseline"
            entry["daily_cap_effective_date"] = today
            entry["daily_cap_captured_at"] = raw_captured_at
            entry["daily_cap_timezone"] = record.get("timezone", "Asia/Shanghai")
            entry["daily_cap_record_source"] = raw_source
        elif _valid_credit(entry.get("credits")) and not (is_global and int(entry["credits"]) <= 0):
            entry["daily_cap"] = int(entry["credits"])
            entry["daily_cap_source"] = "seed"
            entry["daily_cap_effective_date"] = None
            entry["daily_cap_captured_at"] = captured_at
            entry["daily_cap_timezone"] = "Asia/Shanghai"
            entry["daily_cap_record_source"] = "seed_current_uid"
        else:
            entry["daily_cap"] = None
            entry["daily_cap_source"] = None
            entry["daily_cap_effective_date"] = None
            entry["daily_cap_captured_at"] = None
            entry["daily_cap_timezone"] = "Asia/Shanghai"
            entry["daily_cap_record_source"] = None
    return workbuddy



WORKBUDDY_STATE_FILE = os.environ.get(
    "WORKBUDDY_STATE",
    os.environ.get("WORKBUDDY_STATE_FILE", ""),
)


def _workbuddy_state_fallback(uids):
    """billing API 全挂时的兜底：读 workbuddy2api 的 state.json。

    state.json 的 credits 是签到/任务积分余额（整数），与 billing 的 token
    额度不是一个口径，但总比前端显示「积分未知」好。返回 {uid: credits}。
    """
    try:
        with open(WORKBUDDY_STATE_FILE, encoding="utf-8") as stream:
            data = json.load(stream)
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return {}
    accounts = data.get("accounts") or {}
    result = {}
    for uid in uids:
        entry = accounts.get(uid)
        if isinstance(entry, dict):
            credits = entry.get("credits")
            if isinstance(credits, (int, float)) and not isinstance(credits, bool):
                result[uid] = int(credits)
    return result


# ── wan3：经 remote-nas 上的 workbuddy-manager 面板取数 ──────────────────
# wan3 上游实例跑在 remote-nas（nas.example.com:7863），账号目录也在那台机器上。
# 该目录曾用 NFS 挂进本机，但导出会抖动（导出列表为空 / root-squash 后
# 0700 目录读不到），且 automount 被 docker 触发时挂载失败会直接拖住容器
# 启动。因此改为**只走 HTTP**：账号清单仍取上游 /status（上游自己热加载
# auths，5s 一轮），实时积分取面板代查腾讯的结果。
_PANEL_CREDIT_CACHE = {"ts": 0.0, "value": None}
_PANEL_WATCH_INTERVAL = float(os.environ.get("WORKBUDDY_PANEL_WATCH_INTERVAL", "60"))
_panel_watch_state = {"ts": 0.0, "fingerprint": None}


def _panel_json(path, token, timeout=25, data=None):
    return _http_json(WORKBUDDY_WAN3_PANEL.rstrip("/") + path, token=token,
                      extra_headers={"Accept": "application/json"},
                      timeout=timeout, data=data)


def _panel_wan3_credits():
    """向面板查询 wan3 账号的实时积分，返回 {uid: credits}。

    为什么要面板代查：真实余额只能拿账号的 accessToken 直连腾讯 billing 查，
    而该 token 在 remote-nas 的 auths 文件里，本机已不再挂载该目录。面板本身能读
    到该目录，也实现了同一个 billing 查询（server/services/tencent.py），
    所以它是本机拿得到 wan3 真实余额的唯一通道。

    用 force=false：命中面板自己的 60s 缓存时不重复打腾讯，避免把远端打热。
    未配置面板或调用失败返回 {}，调用方保留 /status 原值（降级不报错）。
    """
    if not (WORKBUDDY_WAN3_PANEL and WORKBUDDY_WAN3_PANEL_TOKEN):
        return {}
    try:
        payload = _panel_json("/api/accounts/refresh-credits?force=false",
                              WORKBUDDY_WAN3_PANEL_TOKEN, data={})
    except Exception:
        return {}
    credits = payload.get("credits") if isinstance(payload, dict) else None
    if not isinstance(credits, dict):
        return {}
    out = {}
    for uid, value in credits.items():
        if isinstance(value, (int, float)) and not isinstance(value, bool) and value > 0:
            out[str(uid)] = int(value)
    return out


def _panel_wan3_credits_cached():
    """带缓存的面板积分查询。只缓存成功且非空的结果，失败下次重试。"""
    import time as _time
    now = _time.time()
    cached = _PANEL_CREDIT_CACHE["value"]
    if cached and now - _PANEL_CREDIT_CACHE["ts"] < _CREDIT_CACHE_TTL:
        return cached
    value = _panel_wan3_credits()
    if value:
        _PANEL_CREDIT_CACHE["ts"] = now
        _PANEL_CREDIT_CACHE["value"] = value
    return value


def _panel_accounts_fingerprint():
    """面板账号指纹，用于「加号后秒级可见」。

    本机不再有 wan3 的 auths 目录，因此 wan3 的增删只能从面板看到。这里按
    WORKBUDDY_PANEL_WATCH_INTERVAL（默认 60s）节流：本地轮询是 5s，但远端
    接口没必要这么密。查询失败返回 None（调用方不把「暂时查不到」当变化）。
    """
    if not (WORKBUDDY_WAN3_PANEL and WORKBUDDY_WAN3_PANEL_TOKEN):
        return None
    import time as _time
    now = _time.monotonic()
    if (_panel_watch_state["fingerprint"] is not None
            and now - _panel_watch_state["ts"] < _PANEL_WATCH_INTERVAL):
        return _panel_watch_state["fingerprint"]
    try:
        payload = _panel_json("/api/accounts", WORKBUDDY_WAN3_PANEL_TOKEN, timeout=10)
    except Exception:
        return None
    accounts = payload.get("accounts") if isinstance(payload, dict) else None
    if not isinstance(accounts, list):
        return None
    parts = sorted(
        "%s|%s" % (a.get("uid") or "", a.get("file") or "")
        for a in accounts if isinstance(a, dict)
    )
    value = "\n".join(parts)
    _panel_watch_state["ts"] = now
    _panel_watch_state["fingerprint"] = value
    return value


def _workbuddy_credit():
    """读 auths 目录 + 调 billing API，聚合每账号自然月累计消耗。"""
    try:
        files = []
        for directory in _WORKBUDDY_AUTH_DIRS:
            files.extend(sorted(glob.glob(os.path.join(directory, "workbuddy-*.json"))))
        if not files:
            return None, "auths 目录无账号文件"
        accounts = {}
        now = datetime.now()
        for f in files:
            try:
                d = _read_json(f)
            except Exception:
                continue
            a = d.get("auth") or {}
            acc = d.get("account") or {}
            uid = acc.get("uid") or ""
            token = a.get("accessToken") or ""
            if not uid or not token:
                continue
            domain = (a.get("domain") or "").strip()
            realm = (a.get("realm") or "").strip().lower()
            is_global = realm == "global" or domain == "workbuddy.ai" or domain.endswith(".workbuddy.ai")
            base = "https://www.workbuddy.ai" if is_global else "https://www.codebuddy.cn"
            path = "/billing/meter/get-user-resource" if is_global else "/v2/billing/meter/get-user-resource"
            body = {
                "PageNumber": 1, "PageSize": 100, "ProductCode": "p_tcaca",
                "Status": [0, 3],
                "PackageEndTimeRangeBegin": now.strftime("%Y-%m-%d %H:%M:%S"),
                "PackageEndTimeRangeEnd": (now + timedelta(days=365 * 101)).strftime("%Y-%m-%d %H:%M:%S"),
            }
            headers = {
                "Authorization": "Bearer " + token,
                "Accept": "application/json",
                "Content-Type": "application/json",
                "X-User-Id": uid,
                "User-Agent": "CLI/2.63.2 CodeBuddy/2.63.2",
                "Origin": base,
                "Referer": base + "/",
            }
            if acc.get("enterpriseId"):
                headers["X-Enterprise-Id"] = acc["enterpriseId"]
                headers["X-Tenant-Id"] = acc["enterpriseId"]
            if domain:
                headers["X-Domain"] = domain
            try:
                with urllib.request.urlopen(urllib.request.Request(base + path, data=json.dumps(body).encode(), headers=headers, method="POST"), timeout=20) as r:
                    env = json.loads(r.read().decode("utf-8", errors="replace"))
            except Exception:
                continue
            if env.get("code") != 0:
                continue
            pkgs = (((env.get("data") or {}).get("Response") or {}).get("Data") or {}).get("Accounts") or []
            remain = used = size = 0
            for p in pkgs:
                cyc_size = p.get("CycleCapacitySize") or 0
                if cyc_size > 0:
                    r = p.get("CycleCapacityRemain") or 0
                    s = cyc_size
                    u = p.get("CycleCapacityUsed") or 0
                else:
                    r = p.get("CapacityRemain") or 0
                    s = p.get("CapacitySize") or 0
                    u = p.get("CapacityUsed") or 0
                if r < 0:
                    r = 0
                if s < r:
                    s = r
                if u == 0 and s > r:
                    u = s - r
                remain += r
                used += u
                size += s
            if size > 0 and size - remain > used:
                used = size - remain
            accounts[uid] = {"used": used, "remain": remain, "size": size}
        return accounts, None
    except Exception as exc:
        return None, str(exc)[:120]


def _groups():
    """按出口分组返回 WorkBuddy 实例；未配置 base 的分组自动跳过。

    WorkBuddy 是可选插件，开源用户不配置 WORKBUDDY_BASE 时该模块不产生任何请求。
    """
    groups = (
        {"id": "default", "name": "默认出口", "base": WORKBUDDY_BASE, "config": WORKBUDDY_CONFIG},
        {"id": "wan1", "name": "WAN1 出口", "base": WORKBUDDY_WAN1_BASE, "config": WORKBUDDY_WAN1_CONFIG},
        # wan3 上游实例跑在 remote-nas（nas.example.com:7863），账号目录与实时积分
        # 都经面板 HTTP 获取（不再挂 NFS，见 _panel_wan3_credits）。
        {"id": "wan3", "name": "WAN3 出口", "base": WORKBUDDY_WAN3_BASE, "config": WORKBUDDY_WAN3_CONFIG},
    )
    return tuple(group for group in groups if group["base"])


def toggle_account(uid, disabled, group_id=None):
    """调 workbuddy2api admin 接口切换 manual_disabled，返回 (success, result)。"""
    groups = {group["id"]: group for group in _groups()}
    group = groups.get(group_id or "default")
    if not group:
        raise RuntimeError("未知 WorkBuddy 分组: %s" % group_id)
    cfg = _read_json(group["config"])
    key = cfg.get("api_key") or ""
    if not key:
        raise RuntimeError("%s 未找到 api_key" % group["name"])
    action = "disable" if disabled else "enable"
    url = group["base"] + "/admin/accounts/" + uid + "/" + action
    body = {"reason": "nas-monitor"} if disabled else {}
    try:
        result = _http_json(url, token=key, data=body, extra_headers={"Accept": "application/json"})
        if isinstance(result, dict) and result.get("changed"):
            threading.Thread(target=_refresh_snapshots, daemon=True, name="workbuddy-toggle-refresh").start()
        return True, result
    except Exception as exc:
        return False, str(exc)[:300]


def _refresh_snapshots():
    try:
        collect()
        from .balance_combined import collect as collect_balance_combined
        collect_balance_combined()
    except Exception:
        pass


def _collect_group(group):
    cfg = _read_json(group["config"])
    key = cfg.get("api_key") or ""
    if not key:
        raise RuntimeError("%s 未找到 api_key" % group["name"])
    payload = _http_json(group["base"] + "/status", token=key, extra_headers={"Accept": "application/json"})
    if isinstance(payload, dict) and payload.get("error"):
        raise RuntimeError(str(payload["error"])[:120])
    if not isinstance(payload, dict):
        raise RuntimeError("响应格式异常")
    return payload


def collect():
    group_results = []
    group_errors = []
    all_accounts = []

    for group in _groups():
        try:
            payload = _collect_group(group)
        except Exception as exc:
            group_errors.append("%s: %s" % (group["name"], str(exc)[:120]))
            continue

        accounts_raw = payload.get("accounts") or []
        credit_map, credit_err = _workbuddy_credit_cached()
        # wan3 的账号目录不在本机：实时积分改由面板代查（见 _panel_wan3_credits）。
        panel_credits = _panel_wan3_credits_cached() if group["id"] == "wan3" else {}
        state_fallback = None
        if not credit_map:
            state_fallback = _workbuddy_state_fallback([a.get("uid") for a in accounts_raw if isinstance(a, dict)])
        norm = []
        for a in accounts_raw:
            if not isinstance(a, dict):
                continue
            uid = a.get("uid") or ""
            entry = {
                "uid": uid,
                "nickname": a.get("nickname") or a.get("name") or "",
                "credits": a.get("credits"),
                "credits_total": a.get("credits_total"),
                "realm": a.get("realm"),
                "cooling": bool(a.get("cooling")),
                "manual_disabled": bool(a.get("manual_disabled")),
                "disabled": bool(a.get("disabled")) or bool(a.get("manual_disabled")),
                "in_flight": a.get("in_flight"),
                "success_count": a.get("success_count"),
                "last_success": a.get("last_success"),
                "last_err": a.get("last_err"),
                "breaker_fails": a.get("breaker_fails"),
                "group_id": group["id"],
            }
            if credit_map and uid in credit_map:
                entry["month_used"] = credit_map[uid]["used"]
                entry["today_used"] = _today_used(credit_map[uid]["used"], "wb:" + uid)
                # 上游 /status 对新账号可能返回 0（实例本地额度尚未同步），
                # 用 billing 的剩余额度兜底，避免前端显示 0 / 0。
                remain = credit_map[uid].get("remain")
                if not entry.get("credits") and isinstance(remain, (int, float)) and remain > 0:
                    entry["credits"] = int(remain)
                    entry["credit_source"] = "billing_remain"
            elif state_fallback and uid in state_fallback:
                entry["credits"] = state_fallback[uid]
                entry["credit_source"] = "state_fallback"
            # 上游 /status 的 credits 对新账号常为 0（实例本地额度尚未同步），
            # 用面板代查腾讯的真实余额兜底，避免前端显示 0 / 0。
            if not entry.get("credits") and uid in panel_credits:
                entry["credits"] = panel_credits[uid]
                entry["credit_source"] = "panel_credits"
            norm.append(entry)
        norm.sort(key=lambda x: (x["disabled"], x["cooling"],
                                 -(x["credits"] if isinstance(x["credits"], (int, float)) else -1)))

        # 注入 daily_cap（读 shadow workbuddy_credit_cap.json）
        workbuddy_for_cap = {"accounts": norm}
        _apply_wb_daily_cap(workbuddy_for_cap, datetime.now(SHANGHAI))

        realm_raw = payload.get("realm_totals") or payload.get("realm_servable") or {}
        realm_servable = None
        if isinstance(realm_raw, dict) and realm_raw:
            realm_servable = {}
            for k, v in realm_raw.items():
                if isinstance(v, dict):
                    realm_servable[str(k)] = (v.get("healthy") or 0) > 0
                else:
                    realm_servable[str(k)] = bool(v)

        group_result = {
            "id": group["id"],
            "name": group["name"],
            "total": payload.get("total", len(norm)),
            "healthy": payload.get("healthy", len(norm) - sum(1 for x in norm if x["disabled"] or x["cooling"])),
            "cooling": payload.get("cooling", sum(1 for x in norm if x["cooling"])),
            "disabled": payload.get("disabled", sum(1 for x in norm if x["disabled"])),
            "realm_servable": realm_servable,
            "accounts": norm,
        }
        if credit_err and not credit_map:
            group_result["credit_error"] = credit_err
        group_results.append(group_result)
        all_accounts.extend(norm)

    if not group_results:
        raise RuntimeError("；".join(group_errors) or "全部 WorkBuddy 分组不可用")

    realm_servable = {}
    for group_result in group_results:
        if isinstance(group_result.get("realm_servable"), dict):
            for realm, servable in group_result["realm_servable"].items():
                realm_servable[realm] = realm_servable.get(realm, False) or bool(servable)

    result = {
        "updated_at": datetime.now(SHANGHAI).strftime("%Y-%m-%dT%H:%M:%S+08:00"),
        "total": sum(x.get("total", len(x.get("accounts") or [])) for x in group_results),
        "healthy": sum(x.get("healthy", 0) for x in group_results),
        "cooling": sum(x.get("cooling", 0) for x in group_results),
        "disabled": sum(x.get("disabled", 0) for x in group_results),
        "realm_servable": realm_servable or None,
        "in_flight_full": None,
        "accounts": all_accounts,
        "groups": group_results,
        "source": "backend-shadow",
    }
    if group_errors:
        result["group_errors"] = group_errors
    atomic_write_json("workbuddy_status.json", result)
    # 立即重跑合并快照，避免 balance_combined 独立调度相位滞后导致
    # App 读到的 balance_snapshot.json 晚一拍（曾出现约 100s 延迟）。
    threading.Thread(target=_refresh_combined_snapshot, daemon=True,
                     name="workbuddy-merge-refresh").start()
    return result


def _refresh_combined_snapshot():
    try:
        from .balance_combined import collect as collect_balance_combined
        collect_balance_combined()
    except Exception:
        pass


def _workbuddy_credit_cached():
    """带 10 分钟内存缓存的 _workbuddy_credit，避免每次 collect 都串行调多次外部 API。

    账号增删时（auths 目录文件指纹变化）立即失效缓存，避免新增账号在
    10 分钟内积分显示为 0、删除账号后仍被计入。
    """
    import time as _time
    global _AUTH_FINGERPRINT
    now = _time.time()
    fingerprint = _auth_dirs_fingerprint()
    changed = _AUTH_FINGERPRINT is not None and fingerprint != _AUTH_FINGERPRINT
    if _CREDIT_CACHE["value"] is not None and not changed and now - _CREDIT_CACHE["ts"] < _CREDIT_CACHE_TTL:
        return _CREDIT_CACHE["value"]
    value = _workbuddy_credit()
    _CREDIT_CACHE["ts"] = now
    _CREDIT_CACHE["value"] = value
    _AUTH_FINGERPRINT = fingerprint
    return value


# ── 运行态实时刷新（「在飞」等瞬时字段）──────────────────────────────────
# 背景：workbuddy_status 采集周期 600s，而 in_flight 是上游 pool 的瞬时并发
# 计数（见上游 internal/pool/entry.go）。快照里的 in_flight 只在采集那一刻
# 有效，App 每 30s 拉到的其实是同一份旧值，导致「实际在用但看不到在飞」。
# 这里按需直连上游 /status，只覆盖运行态字段，不重算积分与分组，成本极低。
_RUNTIME_CACHE = {"ts": 0.0, "value": None}
_RUNTIME_CACHE_TTL = 3.0  # 秒；同一秒内多次请求共用一次上游查询

# 会被实时覆盖的运行态字段（积分/分组等慢变字段一律不动）。
_RUNTIME_KEYS = (
    "in_flight", "cooling", "disabled", "manual_disabled", "breaker_fails",
    "success_count", "last_success", "last_err",
)


def _runtime_snapshot_cached():
    """直连各分组上游 /status，取 {uid: {运行态字段}}；带 3s 内存缓存。

    失败时返回 None，调用方保留快照原值（降级不报错）。
    """
    import time as _time
    now = _time.time()
    if _RUNTIME_CACHE["value"] is not None and now - _RUNTIME_CACHE["ts"] < _RUNTIME_CACHE_TTL:
        return _RUNTIME_CACHE["value"]
    result = {}
    ok = False
    for group in _groups():
        try:
            cfg = _read_json(group["config"])
            key = cfg.get("api_key") or ""
            if not key:
                continue
            payload = _http_json(group["base"] + "/status", token=key,
                                 extra_headers={"Accept": "application/json"}, timeout=5)
            if not isinstance(payload, dict):
                continue
            ok = True
            for account in payload.get("accounts") or []:
                if not isinstance(account, dict):
                    continue
                uid = account.get("uid") or ""
                if not uid:
                    continue
                result[uid] = {name: account.get(name) for name in _RUNTIME_KEYS}
        except Exception:
            continue
    if not ok:
        _RUNTIME_CACHE["ts"] = now
        _RUNTIME_CACHE["value"] = None
        return None
    _RUNTIME_CACHE["ts"] = now
    _RUNTIME_CACHE["value"] = result
    return result


def apply_runtime_overrides(payload):
    """把实时运行态字段覆盖到 balance 快照的 workbuddy 段（原地修改并返回）。

    只覆盖 in_flight 等运行态字段；uid 匹配不到（新账号尚未进快照）时跳过，
    等下一轮完整采集补全，避免出现「有账号没积分」的中间态。
    """
    if not isinstance(payload, dict):
        return payload
    workbuddy = payload.get("workbuddy")
    if not isinstance(workbuddy, dict):
        return payload
    runtime = _runtime_snapshot_cached()
    if not runtime:
        return payload
    for group in workbuddy.get("groups") or []:
        if not isinstance(group, dict):
            continue
        for account in group.get("accounts") or []:
            if not isinstance(account, dict):
                continue
            fresh = runtime.get(account.get("uid") or "")
            if not fresh:
                continue
            _apply_runtime_fields(account, fresh)
    # 顶层 accounts 与 groups 是同一批账号的两份投影，需同步覆盖。
    for account in workbuddy.get("accounts") or []:
        if not isinstance(account, dict):
            continue
        fresh = runtime.get(account.get("uid") or "")
        if not fresh:
            continue
        _apply_runtime_fields(account, fresh)
    _attach_recent_activity(workbuddy)
    return payload


def _attach_recent_activity(workbuddy):
    """把「最近被哪个账号处理」写进快照，供 App 直接展示。

    上游日志是请求结束时才写出的，所以这里给的是「刚刚用过谁」；配合
    in_flight（正在处理中）即可判断此刻在跑哪个账号。采集失败不影响返回。
    """
    try:
        from . import workbuddy_activity
    except Exception:
        return
    for group in workbuddy.get("groups") or []:
        if not isinstance(group, dict):
            continue
        group_id = group.get("id") or "default"
        try:
            activity = workbuddy_activity.collect(minutes=30, group_id=group_id)
        except Exception:
            continue
        recent = activity.get("accounts") or []
        group["recent_accounts"] = recent
        if recent:
            group["last_account"] = recent[0]
        by_uid = {}
        for item in recent:
            uid = item.get("uid") or ""
            if uid:
                by_uid[uid] = item
        for account in group.get("accounts") or []:
            if not isinstance(account, dict):
                continue
            item = by_uid.get(account.get("uid") or "")
            if item:
                account["recent_requests"] = item.get("requests")
                account["recent_last_ts"] = item.get("last_ts")


def _apply_runtime_fields(account, fresh):
    """把一条实时运行态写入快照账号对象。

    disabled 在快照里是 `上游 disabled OR manual_disabled` 的合成值，
    不能直接用原始字段覆盖，否则手动停用的账号会显示成可用。
    """
    for name in _RUNTIME_KEYS:
        if name in fresh:
            account[name] = fresh[name]
    account["disabled"] = bool(fresh.get("disabled")) or bool(fresh.get("manual_disabled"))


# ── auths 目录监听：加/删账号后秒级重采 ──────────────────────────────
# 背景：采集周期 600s，而账号是人工在 workbuddy-manager 里加的。加号后最坏要
# 等 10 分钟卡片才出现。上游自己每 5s 轮询 auths 目录热加载（internal/pool/
# watch.go），这里用同一套「目录指纹」口径在本后端也监听一次，指纹一变就立刻
# 重跑采集并重算合并快照，把可见延迟从 600s 压到秒级。
#
# 为什么不用 fsnotify：与上游同一理由——容器/NFS 上会丢事件、句柄会耗尽；
# 轮询只看目录内容指纹，漏不掉也不会抖。目录里只有几十个文件，开销可忽略。
#
# 为什么不用「面板加号后回调本服务」：那要改第三方 workbuddy-manager 镜像，
# 后续它更新会被覆盖。本模块只读目录，不依赖任何外部回调。
_ACCOUNT_WATCH_INTERVAL = float(os.environ.get("WORKBUDDY_WATCH_INTERVAL", "5"))
_account_watch_state = {"fingerprint": None, "not_before": 0.0}
_ACCOUNT_WATCH_LOCK = threading.Lock()
# 重采最短间隔：指纹抖动（例如面板先写临时文件再原子改名）不该连着打好几轮上游。
_ACCOUNT_WATCH_MIN_GAP = 3.0


def _account_watch_fingerprint():
    """账号指纹：本地 auths 目录（文件名+大小+mtime）+ 远端面板账号清单。

    本地部分与上游 watch.go 同口径。wan3 的账号目录不在本机，改由面板账号
    清单参与指纹（见 _panel_accounts_fingerprint），这样在 remote-nas 侧加号
    也能触发重采，而不必等满 600s 采集周期。

    本地目录整体不可读时返回 None，调用方保留旧指纹（不把「暂时读不到」当成
    变化）。远端面板查不到时**只跳过该部分**，不影响本地变化的检测。
    """
    parts = []
    for directory in _WORKBUDDY_AUTH_DIRS:
        if not directory:
            continue
        try:
            names = sorted(os.listdir(directory))
        except OSError:
            return None
        for name in names:
            if not name.endswith(".json"):
                continue
            try:
                st = os.stat(os.path.join(directory, name))
            except OSError:
                continue
            parts.append("%s|%s|%d|%d" % (directory, name, st.st_size, int(st.st_mtime)))
    panel = _panel_accounts_fingerprint()
    if panel:
        parts.append("panel|wan3|" + panel)
    return "\n".join(parts)


def refresh_on_account_change():
    """检查 auths 目录指纹；有变化则立即重采。返回是否触发了重采。

    只做「一次检查 + 可能的采集」，供后台线程周期调用；任何异常都吞掉，
    绝不能因为监听失败影响正常采集或接口。
    """
    try:
        fingerprint = _account_watch_fingerprint()
        if fingerprint is None:
            return False
        now = time.monotonic()
        with _ACCOUNT_WATCH_LOCK:
            previous = _account_watch_state["fingerprint"]
            _account_watch_state["fingerprint"] = fingerprint
            if previous is None or previous == fingerprint:
                return False
            if now < _account_watch_state["not_before"]:
                return False
            _account_watch_state["not_before"] = now + _ACCOUNT_WATCH_MIN_GAP
        collect()
        _refresh_combined_snapshot()
        return True
    except Exception:
        return False


def start_account_watch(stop_event, interval=None):
    """启动 auths 目录监听线程，返回该线程（daemon）。"""
    period = float(interval if interval is not None else _ACCOUNT_WATCH_INTERVAL)

    def run():
        while not stop_event.is_set():
            refresh_on_account_change()
            if stop_event.wait(period):
                return

    thread = threading.Thread(target=run, daemon=True, name="workbuddy-account-watch")
    thread.start()
    return thread
