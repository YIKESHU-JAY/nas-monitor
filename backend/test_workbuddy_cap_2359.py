import json
import os
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch

from collectors import workbuddy_cap_2359 as cap
from collectors import workbuddy_status as status

SHANGHAI = timezone(timedelta(hours=8))


class BaselineCreditTests(unittest.TestCase):
    def test_upstream_credit_kept(self):
        self.assertEqual((50, None), cap._baseline_credit({"uid": "u", "realm": "cn", "credits": 50},
                                                          {"u": {"remain": 129}}))

    def test_zero_credit_falls_back_to_billing(self):
        self.assertEqual((129, "billing_remain"),
                         cap._baseline_credit({"uid": "u", "realm": "global", "credits": 0},
                                              {"u": {"remain": 129}}))

    def test_cn_zero_credit_is_kept(self):
        self.assertEqual((0, None),
                         cap._baseline_credit({"uid": "u", "realm": "cn", "credits": 0}, {}))

    def test_unknown_when_no_source(self):
        self.assertEqual((None, None), cap._baseline_credit({"uid": "u", "realm": "global", "credits": 0}, {}))
        self.assertEqual((None, None), cap._baseline_credit({"uid": "u", "realm": "global"}, {}))


class RollCapTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.addCleanup(patch.stopall)
        patch.dict(os.environ, {"BACKEND_ROOT": self.tmp.name,
                                "NAS_MONITOR_CUTOVER_BALANCE": "1"}, clear=False).start()

    def test_global_account_uses_billing_remainder(self):
        payload = {"accounts": [
            {"uid": "cn-1", "realm": "cn", "credits": 2000},
            {"uid": "global-1", "realm": "global", "credits": 0},
            {"uid": "unknown-1", "realm": "global", "credits": 0},
        ]}
        with patch.object(cap, "_billing_map", return_value={"global-1": {"remain": 129}}):
            count = cap.roll_cap(payload, datetime.now(SHANGHAI), source="cron_2359")
        self.assertEqual(2, count)
        target = json.loads(Path(self.tmp.name, "workbuddy_credit_cap.json").read_text(encoding="utf-8"))
        values = next(iter(target.values()))["values"]
        self.assertEqual(2000, values["cn-1"]["credits"])
        self.assertEqual(129, values["global-1"]["credits"])
        self.assertEqual("billing_remain", values["global-1"]["source_note"])
        self.assertNotIn("unknown-1", values)


class CapBaselineCreditTests(unittest.TestCase):
    """lazy 路径（workbuddy_status）的基线规则。"""

    def test_cn_zero_is_kept(self):
        self.assertEqual((0, None), status.cap_baseline_credit({"uid": "u", "realm": "cn", "credits": 0}, {}))

    def test_global_zero_falls_back_to_billing(self):
        self.assertEqual((350, "billing_remain"),
                         status.cap_baseline_credit({"uid": "u", "realm": "global", "credits": 0},
                                                    {"u": {"remain": 350}}))

    def test_global_without_billing_is_skipped(self):
        self.assertEqual((None, None),
                         status.cap_baseline_credit({"uid": "u", "realm": "global", "credits": 0}, {}))

    def test_cn_credit_not_overridden_by_billing(self):
        self.assertEqual((2000, None),
                         status.cap_baseline_credit({"uid": "u", "realm": "cn", "credits": 2000},
                                                    {"u": {"remain": 999}}))


class DailyCapRepairTests(unittest.TestCase):
    def setUp(self):
        self.now = datetime(2026, 10, 29, 11, 30, tzinfo=SHANGHAI)

    def test_zero_baseline_is_repaired_from_current_positive_credit(self):
        saved = {}
        workbuddy = {"accounts": [{
            "uid": "710cf934-7906-4cd7-822c-e3c6b09057cf",
            "realm": "cn",
            "credits": 2700,
        }]}
        today = "2026-10-29"
        with patch.object(status, "_workbuddy_credit_cached", return_value=({}, None)), \
             patch.object(status, "_load_cap_cutover", return_value={today: {
                 "timezone": "Asia/Shanghai",
                 "values": {
                     "710cf934-7906-4cd7-822c-e3c6b09057cf": {
                         "credits": 0,
                         "captured_at": "2026-10-29T11:18:22+08:00",
                         "source": "lazy",
                     },
                 },
             }}), \
             patch.object(status, "atomic_write_snapshot",
                          side_effect=lambda _name, value, **_kwargs: saved.update(value)):
            status._apply_wb_daily_cap(workbuddy, self.now)
        account = workbuddy["accounts"][0]
        self.assertEqual(2700, account["daily_cap"])
        self.assertEqual("repair_current_credit", account["daily_cap_source"])
        self.assertEqual(2700, saved[today]["values"]["710cf934-7906-4cd7-822c-e3c6b09057cf"]["credits"])

    def test_zero_baseline_stays_zero_when_current_credit_is_zero(self):
        workbuddy = {"accounts": [{
            "uid": "u",
            "realm": "cn",
            "credits": 0,
        }]}
        with patch.object(status, "_workbuddy_credit_cached", return_value=({}, None)), \
             patch.object(status, "_load_cap_cutover", return_value={"2026-10-29": {
                 "timezone": "Asia/Shanghai",
                 "values": {"u": {"credits": 0, "source": "lazy"}},
             }}), \
             patch.object(status, "atomic_write_snapshot"):
            status._apply_wb_daily_cap(workbuddy, self.now)
        self.assertEqual(0, workbuddy["accounts"][0]["daily_cap"])

    def test_new_account_without_credit_does_not_persist_zero_baseline(self):
        saved = {}
        account = {
            "uid": "new-account",
            "realm": "cn",
            "credits": 0,
            "group_id": "wan3",
        }
        workbuddy = {"accounts": [account]}
        today = "2026-10-29"
        with patch.object(status, "_workbuddy_credit_cached", return_value=({}, None)), \
             patch.object(status, "_load_cap_cutover", return_value={}), \
             patch.object(status, "atomic_write_snapshot",
                          side_effect=lambda _name, value, **_kwargs: saved.update(value)):
            status._apply_wb_daily_cap(workbuddy, self.now)
        self.assertIsNone(account["daily_cap"])
        self.assertEqual("awaiting_credit", account["daily_cap_source"])
        self.assertNotIn("new-account", saved.get(today, {}).get("values", {}))

    def test_new_account_with_panel_credit_gets_cap_on_first_collection(self):
        account = {
            "uid": "new-account",
            "realm": "cn",
            "credits": 2300,
            "credit_source": "panel_credits",
        }
        workbuddy = {"accounts": [account]}
        with patch.object(status, "_workbuddy_credit_cached", return_value=({}, None)), \
             patch.object(status, "_load_cap_cutover", return_value={}), \
             patch.object(status, "atomic_write_snapshot"):
            status._apply_wb_daily_cap(workbuddy, self.now)
        self.assertEqual(2300, account["daily_cap"])
        self.assertEqual("lazy", account["daily_cap_source"])


class TodayCreditsTests(unittest.TestCase):
    def setUp(self):
        self.now = datetime(2026, 10, 22, 9, 30, tzinfo=SHANGHAI)

    def test_today_credits_use_used_difference(self):
        with patch.object(status, "_load_shadow_json", return_value={"2026-10-22": {"wb:u": {
                "used": 100, "captured_at": "2026-10-22T00:01:00+08:00"}}}), \
             patch.object(status, "_save_shadow_json"):
            self.assertEqual((25, True), status._today_used(125, "wb:u", self.now))

    def test_period_reset_rebaselines_without_reporting_month_total(self):
        saved = {}
        with patch.object(status, "_load_shadow_json", return_value={"2026-10-22": {"wb:u": {
                "used": 3606, "captured_at": "2026-10-22T00:01:00+08:00"}}}), \
             patch.object(status, "_save_shadow_json", side_effect=lambda _name, value: saved.update(value)):
            self.assertEqual((None, False), status._today_used(3113, "wb:u", self.now))
        self.assertEqual(3113, saved["2026-10-22"]["wb:u"]["used"])

    def test_first_sample_has_no_valid_daily_baseline(self):
        saved = {}
        with patch.object(status, "_load_shadow_json", return_value={}), \
             patch.object(status, "_save_shadow_json", side_effect=lambda _name, value: saved.update(value)):
            self.assertEqual((None, False), status._today_used(500, "wb:u", self.now))
        self.assertEqual(500, saved["2026-10-22"]["wb:u"]["used"])

    def test_legacy_zero_delta_is_not_treated_as_known_zero(self):
        with patch.object(status, "_load_shadow_json", return_value={"2026-10-22": {"wb:u": 3113}}), \
             patch.object(status, "_save_shadow_json"):
            self.assertEqual((0, False), status._today_used(3113, "wb:u", self.now))


if __name__ == "__main__":
    unittest.main()
