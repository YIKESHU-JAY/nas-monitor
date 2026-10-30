import datetime
import http.client
import json
import os
import sqlite3
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

import gateway_api
import api_server


class GatewayUsageTests(unittest.TestCase):
    NOW = datetime.datetime(2026, 10, 20, 12, 0, 0, tzinfo=datetime.timezone(datetime.timedelta(hours=8)))

    def setUp(self):
        self.tempdir = tempfile.TemporaryDirectory()
        self.addCleanup(self.tempdir.cleanup)
        self.db_path = os.path.join(self.tempdir.name, "new-api.db")
        self.snapshot_path = os.path.join(self.tempdir.name, "model_usage_snapshot.json")
        for replacement in (
            patch.object(gateway_api, "MODEL_USAGE_SNAPSHOT_FILE", gateway_api.Path(self.snapshot_path)),
            patch.object(gateway_api, "MODEL_USAGE_DB", self.db_path),
        ):
            replacement.start()
            self.addCleanup(replacement.stop)
        gateway_api.reset_model_usage_cache()
        self.addCleanup(gateway_api.reset_model_usage_cache)
        connection = sqlite3.connect(self.db_path)
        connection.execute(
            "CREATE TABLE logs (id INTEGER PRIMARY KEY, created_at INTEGER, type INTEGER, "
            "model_name TEXT, quota INTEGER, prompt_tokens INTEGER, "
            "completion_tokens INTEGER, use_time INTEGER, user_id INTEGER, username TEXT)"
        )
        connection.execute(
            "CREATE TABLE users (id INTEGER PRIMARY KEY, username TEXT, display_name TEXT, "
            "created_at INTEGER, type INTEGER, model_name TEXT)"
        )
        connection.execute("INSERT INTO users (id, username, display_name) VALUES (1, 'root', 'Root User')")
        connection.execute("INSERT INTO users (id, username, display_name) VALUES (2, 'huangtao', 'huangtao')")
        connection.commit()
        connection.close()

    def timestamp(self, days_ago, hour=12):
        day = self.NOW.date() - datetime.timedelta(days=days_ago)
        return int(datetime.datetime.combine(
            day, datetime.time(hour), tzinfo=self.NOW.tzinfo
        ).timestamp())

    def current_timestamp(self):
        return int(datetime.datetime.now().astimezone().timestamp()) - 60

    def insert_log(self, created_at, model="model-a", log_type=2, quota=500000,
                   prompt=10, completion=5, use_time=2, user_id=None, username=None):
        connection = sqlite3.connect(self.db_path)
        connection.execute(
            "INSERT INTO logs (created_at, type, model_name, quota, prompt_tokens, "
            "completion_tokens, use_time, user_id, username) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (created_at, log_type, model, quota, prompt, completion, use_time,
             user_id, username),
        )
        connection.commit()
        connection.close()

    def build(self):
        return gateway_api._build_model_usage(self.NOW)

    def test_window_boundaries_use_inclusive_local_days(self):
        self.insert_log(self.timestamp(0, 0), model="today")
        self.insert_log(self.timestamp(1, 23), model="yesterday")
        self.insert_log(self.timestamp(6, 0), model="day-six")
        self.insert_log(self.timestamp(7, 0), model="outside-seven")
        self.insert_log(self.timestamp(30, 0), model="outside")
        result = self.build()
        one_day = {item["model"] for item in result["windows"]["1d"]["models"]}
        seven_day = {item["model"] for item in result["windows"]["7d"]["models"]}
        thirty_day = {item["model"] for item in result["windows"]["30d"]["models"]}
        self.assertEqual({"today"}, one_day)
        self.assertEqual({"today", "yesterday", "day-six"}, seven_day)
        self.assertEqual({"today", "yesterday", "day-six", "outside-seven"}, thirty_day)
        self.assertEqual("2026-10-14", result["windows"]["7d"]["start_date"])
        self.assertEqual("2026-10-20", result["windows"]["30d"]["end_date"])

    def test_filters_empty_models_and_non_usage_log_types(self):
        self.insert_log(self.timestamp(0), model="")
        self.insert_log(self.timestamp(0), model="   ")
        self.insert_log(self.timestamp(0), model="topup", log_type=1)
        self.insert_log(self.timestamp(0), model="consume", log_type=2)
        self.insert_log(self.timestamp(0), model="error", log_type=5)
        result = self.build()
        models = result["windows"]["1d"]["models"]
        self.assertEqual(["consume", "error"], [item["model"] for item in models])

    def test_tokens_quota_and_errors_are_aggregated_correctly(self):
        self.insert_log(self.timestamp(0), model="a", log_type=2, quota=500000,
                        prompt=100, completion=25, use_time=2)
        self.insert_log(self.timestamp(0), model="a", log_type=5, quota=250000,
                        prompt=40, completion=10, use_time=4)
        item = self.build()["windows"]["1d"]["models"][0]
        self.assertEqual(2, item["requests"])
        self.assertEqual(175, item["tokens"])
        self.assertEqual(750000, item["quota_raw"])
        self.assertEqual(1, item["errors"])
        self.assertEqual(3.0, item["avg_use_time"])
        self.assertEqual(1.5, item["quota"])

    def test_daily_trend_and_model_ordering(self):
        self.insert_log(self.timestamp(2), model="older", log_type=2)
        self.insert_log(self.timestamp(0), model="busy", log_type=2)
        self.insert_log(self.timestamp(0), model="busy", log_type=2)
        self.insert_log(self.timestamp(0), model="quiet", log_type=2)
        result = self.build()
        models = result["windows"]["1d"]["models"]
        self.assertEqual(["busy", "quiet"], [item["model"] for item in models])
        daily = result["daily"]
        self.assertEqual(4, sum(item["requests"] for item in daily))
        self.assertEqual(4.0, sum(item["quota"] for item in daily))
        self.assertEqual(3, sum(item["requests"] for item in daily if item["date"] == "2026-10-20"))
        self.assertEqual(2, max(item["requests"] for item in daily))
        self.assertEqual({"busy", "quiet"}, {item["model"] for item in daily if item["date"] == "2026-10-20"})

    def test_hourly_trend_covers_latest_twelve_hours_and_keeps_model_rows(self):
        self.insert_log(self.timestamp(1, 23), model="yesterday")
        self.insert_log(self.timestamp(0, 0), model="midnight")
        self.insert_log(self.timestamp(0, 0), model="midnight")
        self.insert_log(self.timestamp(0, 11), model="late_morning")
        self.insert_log(self.timestamp(0, 13), model="future")
        result = self.build()
        hourly = result["hourly"]
        self.assertEqual({"2026-10-20"}, {item["date"] for item in hourly})
        self.assertEqual(1, sum(item["requests"] for item in hourly))
        self.assertEqual(1, sum(item["requests"] for item in hourly if item["hour_start"] == "2026-10-20T11:00:00"))
        self.assertEqual(1, sum(item["requests"] for item in hourly if item["hour"] == 11))
        self.assertNotIn("future", {item["model"] for item in hourly})
        self.assertIn("hourly_limit", result["coverage"])

    def test_sqlite_connection_is_uri_read_only(self):
        self.insert_log(self.timestamp(0), model="read-only")
        result = self.build()
        self.assertEqual("read-only", result["windows"]["1d"]["models"][0]["model"])
        self.assertTrue(os.path.isfile(self.db_path))

    def test_repeated_requests_query_database_once(self):
        self.insert_log(self.current_timestamp(), model="cached")
        with patch.object(gateway_api, "_load_usage_aggregates_sqlite",
                          wraps=gateway_api._load_usage_aggregates_sqlite) as query:
            first = gateway_api.model_usage()
            second = gateway_api.model_usage()
            third = gateway_api.model_usage()
        self.assertEqual(1, query.call_count)
        self.assertEqual("fresh", first["cache"]["state"])
        self.assertEqual("cached", second["windows"]["1d"]["models"][0]["model"])
        self.assertEqual(300, third["cache"]["ttl_seconds"])

    def test_expired_snapshot_starts_single_background_refresh(self):
        self.insert_log(self.current_timestamp(), model="cached")
        gateway_api.model_usage()
        release = threading.Event()
        started = threading.Event()
        original = gateway_api._build_model_usage
        query = MockWithEvents(original, release, started)
        with patch.object(gateway_api, "MODEL_USAGE_CACHE_SECONDS", 0.001), \
             patch.object(gateway_api, "_build_model_usage", side_effect=query):
            time.sleep(0.01)
            stale = gateway_api.model_usage()
            self.assertTrue(started.wait(1))
            repeated = gateway_api.model_usage()
            self.assertTrue(stale["stale"])
            self.assertEqual(1, query.count)
            release.set()
            self.assertTrue(gateway_api.wait_model_usage_refresh())
        gateway_api.invalidate_model_usage_cache()
        with patch.object(gateway_api, "MODEL_USAGE_CACHE_SECONDS", 0.001):
            time.sleep(0.01)
            stale_again = gateway_api.model_usage()
            self.assertTrue(stale_again["stale"])
            self.assertTrue(gateway_api.wait_model_usage_refresh())
        fresh = gateway_api.model_usage()
        self.assertFalse(fresh["stale"])
        self.assertEqual(2, query.count)

    def test_database_failure_returns_last_success_as_stale(self):
        self.insert_log(self.current_timestamp(), model="survivor")
        first = gateway_api.model_usage()
        self.assertFalse(first["stale"])
        with patch.object(gateway_api, "MODEL_USAGE_DB", os.path.join(self.tempdir.name, "missing.db")), \
             patch.object(gateway_api, "MODEL_USAGE_CACHE_SECONDS", 0.001):
            time.sleep(0.01)
            stale = gateway_api.model_usage()
            self.assertTrue(gateway_api.wait_model_usage_refresh())
            stale = gateway_api.model_usage()
        self.assertTrue(stale["stale"])
        self.assertEqual("stale_snapshot", stale["source"])
        self.assertIn("survivor", [item["model"] for item in stale["windows"]["1d"]["models"]])
        self.assertTrue(stale["coverage"]["error"])

    def test_restart_can_serve_persisted_snapshot_before_refresh(self):
        self.insert_log(self.current_timestamp(), model="persisted")
        gateway_api.model_usage()
        self.assertTrue(os.path.isfile(self.snapshot_path))
        with open(self.snapshot_path, encoding="utf-8") as handle:
            payload = json.load(handle)
        self.assertIn("snapshot", payload)
        gateway_api.reset_model_usage_cache()
        with patch.object(gateway_api, "_build_model_usage", side_effect=RuntimeError("database down")):
            restored = gateway_api.model_usage()
        self.assertTrue(restored["stale"])
        self.assertEqual("persisted", restored["windows"]["1d"]["models"][0]["model"])

    def test_account_windows_do_not_leak_across_boundaries(self):
        self.insert_log(self.timestamp(0, 0), model="today", user_id=1, username="root")
        self.insert_log(self.timestamp(1, 23), model="yesterday", user_id=2, username="huangtao")
        self.insert_log(self.timestamp(6, 0), model="day-six", user_id=1, username="root")
        self.insert_log(self.timestamp(7, 0), model="outside-seven", user_id=2, username="huangtao")
        result = self.build()
        one_day = {item["account_name"] for item in result["windows"]["1d"]["accounts"]}
        seven_day = {item["account_name"] for item in result["windows"]["7d"]["accounts"]}
        thirty_day = {item["account_name"] for item in result["windows"]["30d"]["accounts"]}
        self.assertEqual({"root"}, one_day)
        self.assertEqual({"root", "huangtao"}, seven_day)
        self.assertEqual({"root", "huangtao"}, thirty_day)

    def test_same_user_id_merges_and_uses_username_not_display_name(self):
        self.insert_log(self.timestamp(0), model="a", user_id=1, username="root")
        self.insert_log(self.timestamp(0), model="b", user_id=1, username="root")
        self.insert_log(self.timestamp(0), model="a", user_id=2, username="huangtao")
        accounts = self.build()["windows"]["1d"]["accounts"]
        by_id = {item["account_id"]: item for item in accounts}
        self.assertEqual(2, by_id["1"]["requests"])
        self.assertEqual("root", by_id["1"]["account_name"])
        self.assertNotEqual("Root User", by_id["1"]["account_name"])
        self.assertEqual("huangtao", by_id["2"]["account_name"])

    def test_empty_account_name_uses_unknown_label_without_key_or_model(self):
        self.insert_log(self.timestamp(0), model="secret-model", user_id=None, username="")
        self.insert_log(self.timestamp(0), model="secret-model", user_id=None, username="   ")
        accounts = self.build()["windows"]["1d"]["accounts"]
        self.assertEqual(1, len(accounts))
        self.assertEqual("unknown", accounts[0]["account_id"])
        self.assertEqual("未知账号", accounts[0]["account_name"])
        self.assertNotIn("secret-model", accounts[0]["account_name"])
        self.assertNotIn("token", accounts[0])

    def test_account_totals_match_model_totals_per_window(self):
        self.insert_log(self.timestamp(0), model="a", user_id=1, username="root", prompt=10, completion=5)
        self.insert_log(self.timestamp(0), model="b", user_id=2, username="huangtao", prompt=20, completion=7)
        self.insert_log(self.timestamp(2), model="a", user_id=1, username="root", prompt=30, completion=9)
        result = self.build()
        for window in ("1d", "7d", "30d"):
            models = result["windows"][window]["models"]
            accounts = result["windows"][window]["accounts"]
            self.assertEqual(sum(item["requests"] for item in models),
                             sum(item["requests"] for item in accounts), window)
            self.assertEqual(sum(item["tokens"] for item in models),
                             sum(item["tokens"] for item in accounts), window)

    def test_missing_user_fields_fall_back_to_unknown_account(self):
        self.insert_log(self.timestamp(0), model="a", user_id=None, username=None)
        result = self.build()
        # 无 user_id/username 的日志仍归入“未知账号”，不应丢失请求。
        self.assertEqual(1, sum(item["requests"] for item in result["windows"]["1d"]["accounts"]))

    def test_no_usage_logs_returns_empty_account_lists(self):
        result = self.build()
        for window in ("1d", "7d", "30d"):
            self.assertEqual([], result["windows"][window]["accounts"])

    def test_legacy_snapshot_without_accounts_is_rebuilt(self):
        self.insert_log(self.current_timestamp(), model="legacy", user_id=1, username="root")
        with open(self.snapshot_path, "w", encoding="utf-8") as handle:
            json.dump({
                "saved_wall_at": time.time(),
                "snapshot": {
                    "windows": {
                        "1d": {"models": [], "label": "最近 1 天"},
                        "7d": {"models": [], "label": "最近 7 天"},
                        "30d": {"models": [], "label": "最近 30 天"},
                    },
                    "daily": [], "hourly": [], "source": "sqlite", "stale": False,
                },
            }, handle)
        gateway_api.reset_model_usage_cache()
        result = gateway_api.model_usage()
        self.assertEqual(["root"], [item["account_name"] for item in result["windows"]["1d"]["accounts"]])

    def test_new_user_is_included_dynamically_in_all_windows(self):
        connection = sqlite3.connect(self.db_path)
        connection.execute(
            "INSERT INTO users (id, username, display_name) VALUES (3, 'newuser', 'New User Display')"
        )
        connection.commit()
        connection.close()
        self.insert_log(self.timestamp(0), model="model-new", user_id=3, username="newuser")
        result = self.build()
        for window in ("1d", "7d", "30d"):
            names = {item["account_name"] for item in result["windows"][window]["accounts"]}
            self.assertIn("newuser", names, window)
            self.assertNotIn("New User Display", names, window)


class MockWithEvents:
    def __init__(self, func, release, started):
        self.func = func
        self.release = release
        self.started = started
        self.count = 0
        self.lock = threading.Lock()

    def __call__(self, *args, **kwargs):
        with self.lock:
            self.count += 1
        self.started.set()
        self.release.wait(3)
        return self.func(*args, **kwargs)


class GatewayUsageHttpTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = api_server.ThreadingHTTPServer(("127.0.0.1", 0), api_server.APIHandler)
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def request(self):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        try:
            connection.request(
                "GET", "/api/newapi/model-usage",
                headers={"Authorization": "Bearer test-monitor-token"},
            )
            response = connection.getresponse()
            return response.status, json.loads(response.read().decode())
        finally:
            connection.close()

    def test_model_usage_route_requires_auth_and_returns_cache_metadata(self):
        payload = {
            "windows": {}, "daily": [], "source": "sqlite", "stale": False,
            "cache": {"state": "fresh", "ttl_seconds": 300},
        }
        with patch.object(api_server, "APP_API_TOKEN", "test-monitor-token"), \
             patch.object(api_server.gateway_api, "model_usage", return_value=payload) as usage:
            connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
            try:
                connection.request("GET", "/api/newapi/model-usage")
                self.assertEqual(401, connection.getresponse().status)
            finally:
                connection.close()
            status, data = self.request()
        self.assertEqual(200, status)
        self.assertEqual(300, data["cache"]["ttl_seconds"])
        usage.assert_called_once_with()


if __name__ == "__main__":
    unittest.main()
