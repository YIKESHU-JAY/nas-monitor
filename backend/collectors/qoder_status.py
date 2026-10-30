"""Read-only Qoder account summary for the gateway balance card.

Only explicitly whitelisted, non-secret fields leave this module. Account
files also contain access/personal/refresh tokens and machine identity; those
values must never be returned, logged, or used in diagnostics.
"""

import json
import os
import time
from pathlib import Path

QODER_ACCOUNTS_DIR = os.environ.get("QODER_ACCOUNTS_DIR", "/qoder-accounts")
QODER_B_ACCOUNTS_DIR = os.environ.get("QODER_B_ACCOUNTS_DIR", "/qoder-accounts-b")
IGNORED_NAMES = {
    "active_realm.json",
    "settings.json",
    "machine_identity.json",
}
MAX_ACCOUNT_FILES = 200
MAX_TEXT_LENGTH = 240


def _text(value, limit=MAX_TEXT_LENGTH):
    if value is None:
        return None
    if not isinstance(value, (str, int, float)):
        return None
    text = str(value).strip()
    if not text:
        return None
    return text[:limit]


def _number(value):
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return value
    return None


def _timestamp(value):
    """Return an ISO-ish timestamp as text, or a numeric epoch as int."""
    if isinstance(value, bool) or value is None:
        return None
    if isinstance(value, (int, float)):
        return value
    if isinstance(value, str):
        text = value.strip()
        return text[:64] if text else None
    return None


def _is_account_file(path):
    name = path.name
    if not name.endswith(".json"):
        return False
    if name in IGNORED_NAMES or name.startswith("models-cache"):
        return False
    return True


def _cooldown_active(value, now):
    if value is None or isinstance(value, bool):
        return False
    if isinstance(value, (int, float)):
        # Accept both seconds and milliseconds, but ignore implausible values.
        seconds = value / 1000.0 if value > 10_000_000_000 else value
        return seconds > now
    if isinstance(value, str):
        text = value.strip()
        if not text:
            return False
        try:
            from datetime import datetime, timezone
            parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
            if parsed.tzinfo is None:
                parsed = parsed.replace(tzinfo=timezone.utc)
            return parsed.timestamp() > now
        except Exception:
            return False
    return False


def _account_view(raw):
    """Whitelist one account object. Never copy arbitrary/credential fields."""
    credits = raw.get("credits") if isinstance(raw.get("credits"), dict) else {}
    realm = _text(raw.get("realm")) or _text(raw.get("domain"))
    view = {
        "uid": _text(raw.get("uid")),
        "nickname": _text(raw.get("nickname")),
        "realm": realm,
        "domain": _text(raw.get("domain")),
        "platform": _text(raw.get("platform")),
        "plan": _text(raw.get("plan")),
        "enabled": raw.get("enabled") if isinstance(raw.get("enabled"), bool) else None,
        "credits": {
            "remain": _number(credits.get("remain")),
            "used": _number(credits.get("used")),
            "size": _number(credits.get("size")),
            "updated_iso": _timestamp(credits.get("updated_iso")),
        },
        "cooldownUntil": _timestamp(raw.get("cooldownUntil")),
        "lastError": _text(raw.get("lastError")),
        "lastCheckin": _timestamp(raw.get("lastCheckin")),
        "expiresAt": _timestamp(raw.get("expiresAt")),
        "addedAt": _timestamp(raw.get("addedAt")),
    }
    return view


def _status(view, now):
    if view.get("enabled") is False:
        return "disabled"
    if _cooldown_active(view.get("cooldownUntil"), now):
        return "cooldown"
    return "healthy"


def collect(accounts_dir=None, pool_id=None, label=None):
    """Return a whitelisted Qoder summary.

    Missing directories, unreadable files, and malformed JSON degrade to an
    empty/partial summary with ``error`` set; callers must not fail because of
    this collector.
    """
    root = Path(accounts_dir or os.environ.get("QODER_ACCOUNTS_DIR", QODER_ACCOUNTS_DIR))
    now = time.time()
    result = {
        "accounts": [],
        "summary": {
            "total": 0,
            "healthy": 0,
            "disabled": 0,
            "cooldown": 0,
            "remaining_credits": None,
        },
        "error": None,
    }
    if pool_id is not None:
        result["id"] = pool_id
    if label is not None:
        result["label"] = label
    if not root.is_dir():
        result["error"] = "Qoder 账号目录不可用"
        return result
    try:
        paths = sorted(path for path in root.iterdir() if path.is_file() and _is_account_file(path))
    except OSError:
        result["error"] = "Qoder 账号目录不可读"
        return result

    skipped = 0
    accounts = []
    for path in paths[:MAX_ACCOUNT_FILES]:
        try:
            with path.open(encoding="utf-8") as handle:
                raw = json.load(handle)
        except Exception:
            skipped += 1
            continue
        if not isinstance(raw, dict):
            skipped += 1
            continue
        view = _account_view(raw)
        view["status"] = _status(view, now)
        accounts.append(view)

    accounts.sort(key=lambda item: (item.get("status") != "healthy", item.get("nickname") or ""))
    remaining_values = [
        item["credits"]["remain"]
        for item in accounts
        if item["credits"].get("remain") is not None
    ]
    summary = result["summary"]
    summary["total"] = len(accounts)
    summary["healthy"] = sum(1 for item in accounts if item["status"] == "healthy")
    summary["disabled"] = sum(1 for item in accounts if item["status"] == "disabled")
    summary["cooldown"] = sum(1 for item in accounts if item["status"] == "cooldown")
    summary["remaining_credits"] = sum(remaining_values) if remaining_values else None
    if skipped:
        result["error"] = "%d 个账号文件无法解析" % skipped
    result["accounts"] = accounts
    return result


def collect_many(sources=None):
    """Collect independent Qoder pools without mixing their accounts.

    Each source is ``(pool_id, label, accounts_dir)``. A failure in one
    source is represented in that pool's ``error`` and never prevents the
    other pool from being collected.
    """
    if sources is None:
        sources = (
            ("default", "主实例", os.environ.get("QODER_ACCOUNTS_DIR", QODER_ACCOUNTS_DIR)),
            ("b", "B 实例", os.environ.get("QODER_B_ACCOUNTS_DIR", QODER_B_ACCOUNTS_DIR)),
        )
    pools = []
    for pool_id, label, accounts_dir in sources:
        try:
            pool = collect(accounts_dir, pool_id=pool_id, label=label)
        except Exception as exc:
            pool = {
                "id": pool_id,
                "label": label,
                "accounts": [],
                "summary": {
                    "total": 0, "healthy": 0, "disabled": 0, "cooldown": 0,
                    "remaining_credits": None,
                },
                "error": "Qoder 账号读取失败：%s" % (str(exc)[:120] or exc.__class__.__name__),
            }
        pools.append(pool)
    return pools
