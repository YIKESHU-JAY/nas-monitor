"""WorkBuddy「最近使用哪个账号」采集。

为什么要读上游容器日志
----------------------
账号是**上游**在它内部选出来的，转发端不知道用了哪个号；上游也不在响应头或
`/status` 里透出「请求 → 账号」的映射（`/status` 只有 in_flight 这类聚合计数）。
唯一的信息源是上游的请求流水日志（`internal/server/logging.go` 的 `logChatRow`）：

    | #453 | 13:04:52 | global:deepseek-v4.1-flash | stream | 200 | user@example.com(deadbeef) | TTFB=3164ms | tok=208 | 47.0tok/s | total=4.9s |

该行在请求**结束时**写出，所以这里给出的是「刚刚用过谁」，配合 `in_flight`
（正在处理中）即可回答「现在在用哪个账号」。

边界
----
本模块只读 Docker 日志，不写任何状态；docker 不可用、日志滚掉、格式变化都
只返回空结果，绝不影响其它接口。
"""
import json
import os
import re
import socket
import time
from datetime import datetime, timedelta, timezone

SHANGHAI = timezone(timedelta(hours=8))

# 上游实例：分组 id -> 容器名（可被环境变量覆盖，便于自定义部署）。
DEFAULT_CONTAINER = os.environ.get("WORKBUDDY_CONTAINER", "workbuddy2api")
WAN1_CONTAINER = os.environ.get("WORKBUDDY_WAN1_CONTAINER", "workbuddy2api-wan1")
# wan3 的上游跑在 remote-nas 上，本机 docker 里没有它的容器，日志不可达。
# 用空字符串显式表示「该分组不做最近使用采集」，避免退回默认出口容器而
# 把默认池的请求误算到 wan3 头上。
WAN3_CONTAINER = os.environ.get("WORKBUDDY_WAN3_CONTAINER", "")
DOCKER_SOCK = os.environ.get("DOCKER_SOCK", "/var/run/docker.sock")

# 一次读取的日志行数。上游一行一个请求，500 行足以覆盖最近十几分钟的活动。
_TAIL = int(os.environ.get("WORKBUDDY_ACTIVITY_TAIL", "800"))

# 请求流水行。字段与上游 logChatRow 一一对应；模型名与昵称都可能含空格，
# 因此按 `|` 切分而不是按空白切分。
_CHAT_ROW = re.compile(
    r"\|\s*#(?P<seq>\d+)\s*\|"
    r"\s*(?P<clock>\d{2}:\d{2}:\d{2})\s*\|"
    r"\s*(?P<model>[^|]*?)\s*\|"
    r"\s*(?P<mode>\S+)\s*\|"
    r"\s*(?P<status>\d+)\s*\|"
    r"\s*(?P<acct>[^|]*?)\s*\|"
    r"\s*TTFB=(?P<ttfb>[^|]*?)\s*\|"
    r"\s*tok=(?P<tok>[^|]*?)\s*\|"
    r"\s*(?P<rate>[^|]*?)\s*\|"
    r"\s*total=(?P<total>[0-9.]+)s\s*\|"
)

# 账号标签形如 `昵称(uid8)`；昵称可能为空 → `uid8`。
_ACCT_LABEL = re.compile(r"^(?P<nick>.*)\((?P<uid8>[0-9a-fA-F]{1,16})\)$")

# docker logs 的 RFC3339 纳秒时间戳前缀。
_TS_PREFIX = re.compile(r"^(?P<stamp>\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z)\s*")


def _docker_logs(container, tail):
    """经 docker.sock 读容器日志，返回解码后的文本行列表。

    Docker 的日志流是复用格式：每帧 8 字节头（首字节 1=stdout/2=stderr，
    4..8 为大端长度）+ 载荷。这里按帧解析，避免把帧头混进文本。
    """
    import http.client
    conn = http.client.HTTPConnection("localhost", timeout=20)
    try:
        conn.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        conn.sock.settimeout(20)
        conn.sock.connect(DOCKER_SOCK)
        conn.request("GET", f"/containers/{container}/logs?stdout=1&stderr=1&tail={tail}&timestamps=1")
        response = conn.getresponse()
        raw = response.read()
        if response.status >= 400:
            return []
    except Exception:
        return []
    finally:
        try:
            conn.close()
        except Exception:
            pass

    lines = []
    offset = 0
    size = len(raw)
    while offset + 8 <= size:
        frame_type = raw[offset]
        length = int.from_bytes(raw[offset + 4:offset + 8], "big")
        offset += 8
        if frame_type not in (0, 1, 2) or length <= 0 or offset + length > size:
            # 非复用格式（例如 TTY 容器）：退回整体解码。
            lines = raw.decode("utf-8", errors="replace").splitlines()
            break
        payload = raw[offset:offset + length].decode("utf-8", errors="replace")
        offset += length
        lines.extend(payload.splitlines())
    return lines


def _parse_line(line):
    """解析一行请求流水；非流水行返回 None。"""
    stamp = None
    match = _TS_PREFIX.match(line)
    if match:
        stamp = match.group("stamp")
        line = line[match.end():]
    row = _CHAT_ROW.search(line)
    if not row:
        return None
    acct_raw = (row.group("acct") or "").strip()
    uid8 = ""
    nickname = acct_raw
    label = _ACCT_LABEL.match(acct_raw)
    if label:
        nickname = (label.group("nick") or "").strip()
        uid8 = label.group("uid8").lower()

    ts = None
    if stamp:
        try:
            ts = datetime.strptime(stamp, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
        except ValueError:
            try:
                ts = datetime.strptime(stamp[:19], "%Y-%m-%dT%H:%M:%S").replace(tzinfo=timezone.utc)
            except ValueError:
                ts = None
    if ts is None:
        # 无 docker 时间戳（原生日志）：用行内时钟 + 当天日期近似还原。
        try:
            today = datetime.now(SHANGHAI).date()
            clock = datetime.strptime(row.group("clock"), "%H:%M:%S").time()
            ts = datetime.combine(today, clock, tzinfo=SHANGHAI)
        except ValueError:
            return None

    try:
        status = int(row.group("status"))
    except (TypeError, ValueError):
        status = None
    tokens = None
    tok_raw = (row.group("tok") or "").strip()
    if tok_raw.isdigit():
        tokens = int(tok_raw)
    try:
        total = float(row.group("total"))
    except (TypeError, ValueError):
        total = None

    return {
        "ts": ts.astimezone(SHANGHAI).isoformat(timespec="seconds"),
        "epoch": int(ts.timestamp()),
        "seq": int(row.group("seq")),
        "model": (row.group("model") or "").strip(),
        "mode": (row.group("mode") or "").strip(),
        "status": status,
        "uid8": uid8,
        "nickname": nickname,
        "tokens": tokens,
        "seconds": total,
    }


def _group_container(group_id):
    """分组 -> 上游容器名；返回空字符串表示该分组不做日志采集。"""
    if group_id == "wan1":
        return WAN1_CONTAINER
    if group_id == "wan3":
        return WAN3_CONTAINER
    return DEFAULT_CONTAINER


def collect(minutes=15, group_id="default"):
    """返回最近 minutes 分钟的请求明细与按账号聚合。

    结构：{"updated_at", "window_minutes", "requests": [...], "accounts": [...]}
    accounts 按最近使用时间倒序，每项含 uid8/nickname/requests/last_ts/last_model。
    """
    try:
        minutes = max(1, min(int(minutes), 24 * 60))
    except (TypeError, ValueError):
        minutes = 15

    container = _group_container(group_id)
    lines = _docker_logs(container, _TAIL) if container else []
    cutoff = int(time.time()) - minutes * 60

    requests = []
    seen = set()
    for line in lines:
        entry = _parse_line(line)
        if not entry:
            continue
        # 上游序号在一段时间内唯一；去重防止 tail 重叠或重复读取。
        key = (entry["seq"], entry["ts"])
        if key in seen:
            continue
        seen.add(key)
        if entry["epoch"] < cutoff:
            continue
        requests.append(entry)

    requests.sort(key=lambda x: x["epoch"], reverse=True)

    by_account = {}
    for entry in requests:
        uid8 = entry["uid8"] or "-"
        bucket = by_account.get(uid8)
        if bucket is None:
            bucket = {
                "uid8": uid8,
                "nickname": entry["nickname"],
                "requests": 0,
                "ok": 0,
                "failed": 0,
                "tokens": 0,
                "last_ts": entry["ts"],
                "last_model": entry["model"],
            }
            by_account[uid8] = bucket
        bucket["requests"] += 1
        if entry["status"] == 200:
            bucket["ok"] += 1
        elif entry["status"] is not None and entry["status"] >= 400:
            bucket["failed"] += 1
        if entry["tokens"]:
            bucket["tokens"] += entry["tokens"]

    accounts = sorted(by_account.values(), key=lambda x: x["last_ts"], reverse=True)

    # 用快照里的完整 uid 把日志的 uid8 前缀补齐（日志按约定只留前 8 位）。
    _attach_full_uids(accounts, requests, group_id)

    return {
        "updated_at": datetime.now(SHANGHAI).isoformat(timespec="seconds"),
        "window_minutes": minutes,
        "group_id": group_id,
        "container": container,
        "total_requests": len(requests),
        "accounts": accounts,
        "requests": requests[:60],
    }


def _load_group_accounts(group_id):
    """从 workbuddy_status 快照取该分组的 uid/nickname 列表，供前缀补齐。"""
    try:
        from .shadow_store import shadow_path
        with shadow_path("workbuddy_status.json").open(encoding="utf-8") as stream:
            status = json.load(stream)
    except Exception:
        return []
    for group in (status.get("groups") or []):
        if isinstance(group, dict) and group.get("id") == group_id:
            return [a for a in (group.get("accounts") or []) if isinstance(a, dict)]
    return []


def _attach_full_uids(accounts, requests, group_id):
    """把 uid8 前缀映射回完整 uid 与快照昵称（原地修改）。

    上游日志只写 uid 前 8 位；App 端用 uid 做账号唯一标识，因此这里按前缀
    唯一匹配补齐。前缀有歧义（命中多个账号）时保持 uid8 不补，避免指错账号。
    """
    known = _load_group_accounts(group_id)
    if not known:
        return
    mapping = {}
    ambiguous = set()
    for account in known:
        uid = str(account.get("uid") or "")
        if len(uid) < 8:
            continue
        prefix = uid[:8].lower()
        if prefix in mapping:
            ambiguous.add(prefix)
        else:
            mapping[prefix] = account
    for bucket in accounts:
        account = mapping.get(bucket["uid8"])
        if account is None or bucket["uid8"] in ambiguous:
            continue
        bucket["uid"] = str(account.get("uid") or "")
        if not bucket.get("nickname"):
            bucket["nickname"] = account.get("nickname") or ""
    for entry in requests:
        account = mapping.get(entry["uid8"])
        if account is None or entry["uid8"] in ambiguous:
            continue
        entry["uid"] = str(account.get("uid") or "")
