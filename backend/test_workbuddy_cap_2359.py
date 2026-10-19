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


if __name__ == "__main__":
    unittest.main()
