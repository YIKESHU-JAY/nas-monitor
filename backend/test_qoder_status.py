import json
import os
import tempfile
import time
import unittest
from unittest.mock import patch

import gateway_api
from collectors import qoder_status


class QoderStatusTests(unittest.TestCase):
    def setUp(self):
        self.tempdir = tempfile.TemporaryDirectory()
        self.addCleanup(self.tempdir.cleanup)
        self.root = self.tempdir.name

    def write(self, name, payload):
        path = os.path.join(self.root, name)
        with open(path, "w", encoding="utf-8") as handle:
            if isinstance(payload, str):
                handle.write(payload)
            else:
                json.dump(payload, handle)
        return path

    def test_whitelist_and_summary(self):
        future = time.time() + 3600
        self.write("a.json", {
            "uid": "u1", "nickname": "alpha", "realm": "cn", "domain": "qoder.cn",
            "platform": "web", "plan": "Free", "enabled": True,
            "credits": {"remain": 96, "used": 4, "size": 100,
                        "updated_iso": "2026-10-20T10:00:00+08:00"},
            "cooldownUntil": None, "lastError": None, "lastCheckin": "2026-10-20",
            "expiresAt": "2026-11-20", "addedAt": "2026-10-01",
            "accessToken": "SECRET_ACCESS", "personalToken": "SECRET_PERSONAL",
            "refreshToken": "SECRET_REFRESH", "machine_identity": {"secret": "SECRET_MACHINE"},
        })
        self.write("b.json", {
            "uid": "u2", "nickname": "beta", "realm": "cn", "enabled": True,
            "credits": {"remain": 5, "used": 5, "size": 10},
            "cooldownUntil": future,
        })
        self.write("c.json", {
            "uid": "u3", "nickname": "gamma", "realm": "cn", "enabled": False,
            "credits": {"remain": 0, "used": 10, "size": 10},
        })
        self.write("settings.json", {"panel_password_hash": "SECRET_SETTINGS"})
        self.write("active_realm.json", {"realm": "cn"})
        self.write("models-cache-cn.json", {"models": []})
        self.write("machine_identity.json", {"secret": "SECRET_MACHINE"})

        result = qoder_status.collect(self.root)
        summary = result["summary"]
        self.assertEqual(3, summary["total"])
        self.assertEqual(1, summary["healthy"])
        self.assertEqual(1, summary["cooldown"])
        self.assertEqual(1, summary["disabled"])
        self.assertEqual(101, summary["remaining_credits"])
        self.assertEqual(3, len(result["accounts"]))

        serialized = json.dumps(result)
        for forbidden in ("SECRET_ACCESS", "SECRET_PERSONAL", "SECRET_REFRESH",
                          "SECRET_MACHINE", "SECRET_SETTINGS", "accessToken",
                          "personalToken", "refreshToken", "machine_identity"):
            self.assertNotIn(forbidden, serialized)
        first = result["accounts"][0]
        self.assertEqual({"uid", "nickname", "realm", "domain", "platform", "plan",
                          "enabled", "credits", "cooldownUntil", "lastError",
                          "lastCheckin", "expiresAt", "addedAt", "status"}, set(first))
        self.assertEqual({"remain", "used", "size", "updated_iso"}, set(first["credits"]))

    def test_corrupt_and_non_object_are_skipped(self):
        self.write("broken.json", "{not-json")
        self.write("list.json", [{"uid": "x"}])
        self.write("ok.json", {"uid": "u1", "nickname": "alpha", "enabled": True,
                               "credits": {"remain": 1, "used": 0, "size": 1}})
        result = qoder_status.collect(self.root)
        self.assertEqual(1, result["summary"]["total"])
        self.assertEqual(1, len(result["accounts"]))
        self.assertIn("无法解析", result["error"])

    def test_missing_directory_degrades(self):
        result = qoder_status.collect(os.path.join(self.root, "missing"))
        self.assertEqual([], result["accounts"])
        self.assertEqual(0, result["summary"]["total"])
        self.assertIsNone(result["summary"]["remaining_credits"])
        self.assertIn("不可用", result["error"])

    def test_balance_snapshot_injects_qoder_and_keeps_workbuddy(self):
        accounts = os.path.join(self.root, "accounts")
        os.makedirs(accounts)
        with open(os.path.join(accounts, "a.json"), "w", encoding="utf-8") as handle:
            json.dump({"uid": "u1", "nickname": "alpha", "enabled": True,
                       "credits": {"remain": 96, "used": 4, "size": 100}}, handle)
        snapshot = os.path.join(self.root, "balance.json")
        with open(snapshot, "w", encoding="utf-8") as handle:
            json.dump({"channels": [], "total": 1.5,
                       "workbuddy": {"total": 2, "accounts": []}}, handle)
        with patch.object(gateway_api, "BALANCE_SNAPSHOT_FILE", gateway_api.Path(snapshot)), \
             patch.object(qoder_status, "QODER_ACCOUNTS_DIR", accounts), \
             patch.dict(os.environ, {"QODER_ACCOUNTS_DIR": accounts}):
            data = gateway_api.balance_snapshot()
        self.assertEqual(1, data["qoder"]["summary"]["total"])
        self.assertEqual(96, data["qoder"]["summary"]["remaining_credits"])
        self.assertIsNone(data["qoder_error"])
        self.assertEqual(2, data["workbuddy"]["total"])
        self.assertNotIn("accessToken", json.dumps(data))

    def test_balance_snapshot_survives_collector_failure(self):
        snapshot = os.path.join(self.root, "balance.json")
        with open(snapshot, "w", encoding="utf-8") as handle:
            json.dump({"channels": [], "total": 1.5}, handle)
        with patch.object(gateway_api, "BALANCE_SNAPSHOT_FILE", gateway_api.Path(snapshot)), \
             patch.object(qoder_status, "collect", side_effect=RuntimeError("boom")):
            data = gateway_api.balance_snapshot()
        self.assertEqual([], data["qoder"]["accounts"])
        self.assertIn("读取失败", data["qoder_error"])
        self.assertEqual(1.5, data["total"])

    def test_two_pools_stay_independent_and_whitelisted(self):
        main = os.path.join(self.root, "main")
        other = os.path.join(self.root, "b")
        os.makedirs(main)
        os.makedirs(other)
        with open(os.path.join(main, "a.json"), "w", encoding="utf-8") as handle:
            json.dump({"uid": "main-1", "nickname": "main", "enabled": True,
                       "credits": {"remain": 96, "used": 4, "size": 100},
                       "accessToken": "SECRET_MAIN"}, handle)
        for name, remain in (("b1.json", 300), ("b2.json", 300)):
            with open(os.path.join(other, name), "w", encoding="utf-8") as handle:
                json.dump({"uid": name, "nickname": name, "enabled": True,
                           "credits": {"remain": remain, "used": 0, "size": 300},
                           "refreshToken": "SECRET_B"}, handle)
        snapshot = os.path.join(self.root, "balance.json")
        with open(snapshot, "w", encoding="utf-8") as handle:
            json.dump({"channels": [], "total": 1}, handle)
        with patch.object(gateway_api, "BALANCE_SNAPSHOT_FILE", gateway_api.Path(snapshot)), \
             patch.dict(os.environ, {"QODER_ACCOUNTS_DIR": main,
                                     "QODER_B_ACCOUNTS_DIR": other}):
            data = gateway_api.balance_snapshot()
        pools = {pool["id"]: pool for pool in data["qoder_pools"]}
        self.assertEqual({"default", "b"}, set(pools))
        self.assertEqual(1, pools["default"]["summary"]["total"])
        self.assertEqual(2, pools["b"]["summary"]["total"])
        self.assertEqual(600, pools["b"]["summary"]["remaining_credits"])
        self.assertEqual(["main-1"], [a["uid"] for a in pools["default"]["accounts"]])
        self.assertEqual({"b1.json", "b2.json"}, {a["uid"] for a in pools["b"]["accounts"]})
        self.assertEqual(1, data["qoder"]["summary"]["total"])
        serialized = json.dumps(data)
        self.assertNotIn("SECRET_MAIN", serialized)
        self.assertNotIn("SECRET_B", serialized)
        self.assertNotIn("accessToken", serialized)
        self.assertNotIn("refreshToken", serialized)

    def test_one_missing_pool_does_not_break_other(self):
        main = os.path.join(self.root, "main")
        os.makedirs(main)
        with open(os.path.join(main, "a.json"), "w", encoding="utf-8") as handle:
            json.dump({"uid": "main-1", "enabled": True,
                       "credits": {"remain": 96, "used": 4, "size": 100}}, handle)
        snapshot = os.path.join(self.root, "balance.json")
        with open(snapshot, "w", encoding="utf-8") as handle:
            json.dump({"channels": [], "total": 1}, handle)
        missing = os.path.join(self.root, "missing")
        with patch.object(gateway_api, "BALANCE_SNAPSHOT_FILE", gateway_api.Path(snapshot)), \
             patch.dict(os.environ, {"QODER_ACCOUNTS_DIR": main,
                                     "QODER_B_ACCOUNTS_DIR": missing}):
            data = gateway_api.balance_snapshot()
        pools = {pool["id"]: pool for pool in data["qoder_pools"]}
        self.assertEqual(1, pools["default"]["summary"]["total"])
        self.assertEqual(0, pools["b"]["summary"]["total"])
        self.assertIn("不可用", pools["b"]["error"])
        self.assertIsNone(pools["default"]["error"])

    def test_corrupt_file_only_affects_its_pool(self):
        main = os.path.join(self.root, "main")
        other = os.path.join(self.root, "b")
        os.makedirs(main)
        os.makedirs(other)
        with open(os.path.join(main, "ok.json"), "w", encoding="utf-8") as handle:
            json.dump({"uid": "main-1", "enabled": True,
                       "credits": {"remain": 96, "used": 4, "size": 100}}, handle)
        with open(os.path.join(other, "broken.json"), "w", encoding="utf-8") as handle:
            handle.write("{broken")
        with patch.dict(os.environ, {"QODER_ACCOUNTS_DIR": main,
                                     "QODER_B_ACCOUNTS_DIR": other}):
            pools = qoder_status.collect_many()
        by_id = {pool["id"]: pool for pool in pools}
        self.assertEqual(1, by_id["default"]["summary"]["total"])
        self.assertEqual(0, by_id["b"]["summary"]["total"])
        self.assertIn("无法解析", by_id["b"]["error"])


if __name__ == "__main__":
    unittest.main()
