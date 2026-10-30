import json
import os
import tempfile
import unittest
from unittest.mock import patch

from collectors import pipio_usage


class PipioUsageCollectorTests(unittest.TestCase):
    def setUp(self):
        self.tempdir = tempfile.TemporaryDirectory()
        self.addCleanup(self.tempdir.cleanup)
        self.environment = patch.dict(
            os.environ,
            {
                "BACKEND_ROOT": self.tempdir.name,
                "NAS_MONITOR_CUTOVER": "0",
                "NAS_MONITOR_CUTOVER_USAGE": "0",
            },
        )
        self.environment.start()
        self.addCleanup(self.environment.stop)

    @staticmethod
    def account(name, today=100):
        return {
            "name": name,
            "display": name,
            "ok": True,
            "quota": 1_000_000,
            "used_total": 10_000,
            "daily": [today] * 7,
            "today": today,
            "yest": today,
            "week": today * 7,
            "today_calls": 1,
            "today_err": 0,
            "tok": [],
            "mod": [],
            "cache_rates": {},
        }

    def run_collect(self, results):
        def fake_collect(token, uid, display, name):
            result = results[name]
            if isinstance(result, Exception):
                raise result
            return result

        with patch.object(pipio_usage, "_read_token", return_value="token"), \
             patch.object(pipio_usage, "_collect", side_effect=fake_collect):
            return pipio_usage.collect()

    def test_single_account_failure_keeps_previous_valid_account(self):
        first = self.run_collect({
            "pipio": self.account("pipio", today=111),
            "pipio2": self.account("pipio2", today=222),
        })
        self.assertEqual([111, 222], [item["today"] for item in first["accounts"]])

        second = self.run_collect({
            "pipio": RuntimeError("HTTP Error 502: Bad Gateway"),
            "pipio2": self.account("pipio2", today=333),
        })
        by_name = {item["name"]: item for item in second["accounts"]}
        self.assertTrue(by_name["pipio"]["ok"])
        self.assertEqual(111, by_name["pipio"]["today"])
        self.assertTrue(by_name["pipio"]["stale"])
        self.assertIn("502", by_name["pipio"]["last_attempt_error"])
        self.assertIsNone(by_name["pipio"]["error"])
        self.assertEqual(333, by_name["pipio2"]["today"])
        self.assertNotIn("stale", by_name["pipio2"])

        target = os.path.join(self.tempdir.name, "shadow", "pipio_usage_snapshot.json")
        with open(target, encoding="utf-8") as stream:
            persisted = json.load(stream)
        persisted_by_name = {item["name"]: item for item in persisted["accounts"]}
        self.assertEqual(111, persisted_by_name["pipio"]["today"])
        self.assertTrue(persisted_by_name["pipio"]["stale"])

    def test_all_account_failures_keep_previous_snapshot(self):
        first = self.run_collect({
            "pipio": self.account("pipio", today=111),
            "pipio2": self.account("pipio2", today=222),
        })
        with self.assertRaises(RuntimeError):
            self.run_collect({
                "pipio": RuntimeError("HTTP Error 502: Bad Gateway"),
                "pipio2": RuntimeError("HTTP Error 502: Bad Gateway"),
            })

        target = os.path.join(self.tempdir.name, "shadow", "pipio_usage_snapshot.json")
        with open(target, encoding="utf-8") as stream:
            persisted = json.load(stream)
        self.assertEqual(first["updated_at"], persisted["updated_at"])
        self.assertEqual([111, 222], [item["today"] for item in persisted["accounts"]])


if __name__ == "__main__":
    unittest.main()
