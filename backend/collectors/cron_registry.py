import json
import os
from datetime import datetime, timedelta, timezone

from .shadow_store import atomic_write_json

# 支持目录挂载（新，默认）与整文件挂载（旧）；指向目录时取其中的 jobs.json。
JOBS_FILE = os.environ.get("HERMES_JOBS_FILE", "/app/secrets/hermes/jobs.json")
SHANGHAI = timezone(timedelta(hours=8))

# Codex 主号池监控已按用户要求下线：采集层直接排除，避免残留在任何探针输出中。
_EXCLUDED_SCRIPT_TOKENS = ("codex_main_pool",)


def _now() -> str:
    return datetime.now(SHANGHAI).isoformat()


def _is_monitor_job(job):
    if not isinstance(job, dict):
        return False
    name = str(job.get("name") or "").lower()
    script = str(job.get("script") or "").lower()
    text = name + " " + script
    if not job.get("enabled", False):
        return False
    if "monitor" not in text and "监控" not in text and "探针" not in text:
        return False
    keywords = ("model", "models", "channel", "codex", "futureppo", "pipio", "status",
                "availability", "模型", "渠道", "倍率", "主号池", "状态", "可用性")
    return any(x in text for x in keywords)


def _is_excluded(job):
    if not isinstance(job, dict):
        return False
    script = str(job.get("script") or "").casefold()
    return any(token in script for token in _EXCLUDED_SCRIPT_TOKENS)


def collect() -> dict:
    jobs_file = os.path.join(JOBS_FILE, "jobs.json") if os.path.isdir(JOBS_FILE) else JOBS_FILE
    try:
        with open(jobs_file, encoding="utf-8") as stream:
            payload = json.load(stream)
        if not isinstance(payload, dict):
            raise ValueError("jobs 文件顶层不是对象")
        jobs = payload.get("jobs", [])
        if not isinstance(jobs, list):
            raise ValueError("jobs 字段不是列表")
    except Exception as exc:
        result = {"source": "cron-registry", "status": "failed", "checked_at": _now(), "error": str(exc)[:180], "items": []}
        atomic_write_json("cron_registry.json", result)
        return result

    now = _now()
    items = []
    for job in jobs:
        if not isinstance(job, dict):
            continue
        if not _is_monitor_job(job):
            continue
        if _is_excluded(job):
            continue
        last_status = str(job.get("last_status") or "unknown").lower()
        if last_status in ("ok", "success", "healthy"):
            status = "healthy"
        elif last_status in ("running", "queued"):
            status = "degraded"
        else:
            status = "failed"
        schedule = str(job.get("schedule_display") or job.get("schedule") or "未设置")
        last_run = str(job.get("last_run_at") or "未运行")
        items.append({
            "kind": "task",
            "id": "cron:" + str(job.get("id") or job.get("name")),
            "name": "任务 · " + str(job.get("name") or job.get("script") or "未命名"),
            "script": str(job.get("script") or ""),
            "status": status,
            "checked_at": last_run if last_run != "未运行" else now,
            "evidence": "频率 " + schedule + " · 最近 " + last_run.replace("T", " ")[:16],
        })

    result = {
        "source": "cron-registry",
        "status": "ok",
        "checked_at": now,
        "error": "",
        "task_count": len(items),
        "items": items,
    }
    atomic_write_json("cron_registry.json", result)
    return result
