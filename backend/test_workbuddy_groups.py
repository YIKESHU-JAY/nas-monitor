"""多出口（默认 / wan1 / wan3）分组与活动采集路由的回归测试。

背景：wan3 上游实例跑在 remote-nas（nas.example.com:7863），**不在本机 docker 内**。
它接入后有几处容易出错：
  · 分组清单漏配 → 卡片里看不到 wan3 的账号；
  · 活动采集按容器名取日志，wan3 本机没有容器，若退回默认出口容器就会把
    默认池的请求误算到 wan3 头上；
  · wan3 的账号目录在 remote-nas 上，本机不再挂 NFS，实时积分必须走面板 HTTP，
    否则会退回 0。
"""
import os
import tempfile
import unittest
from unittest.mock import patch

from collectors import workbuddy_activity as activity
from collectors import workbuddy_status as status


class GroupsTests(unittest.TestCase):
    def test_wan3_group_listed_when_configured(self):
        with patch.object(status, "WORKBUDDY_BASE", "http://default:7863"), \
             patch.object(status, "WORKBUDDY_WAN1_BASE", "http://wan1:7863"), \
             patch.object(status, "WORKBUDDY_WAN3_BASE", "http://wan3:7863"):
            ids = [g["id"] for g in status._groups()]
        self.assertEqual(["default", "wan1", "wan3"], ids)

    def test_wan3_skipped_when_unset(self):
        with patch.object(status, "WORKBUDDY_BASE", "http://default:7863"), \
             patch.object(status, "WORKBUDDY_WAN1_BASE", ""), \
             patch.object(status, "WORKBUDDY_WAN3_BASE", ""):
            ids = [g["id"] for g in status._groups()]
        self.assertEqual(["default"], ids)

    def test_wan3_group_has_name_and_config(self):
        with patch.object(status, "WORKBUDDY_WAN3_BASE", "http://wan3:7863"), \
             patch.object(status, "WORKBUDDY_WAN3_CONFIG", "/run/secrets/workbuddy-wan3-config.json"):
            group = [g for g in status._groups() if g["id"] == "wan3"][0]
        self.assertEqual("WAN3 出口", group["name"])
        self.assertEqual("/run/secrets/workbuddy-wan3-config.json", group["config"])


class AuthDirsTests(unittest.TestCase):
    def test_local_auth_dirs_in_fingerprint_scope(self):
        """本地出口的账号目录必须纳入指纹：增删账号时积分缓存要立即失效。

        wan3 的目录不在本机（不再挂 NFS），它由面板账号清单参与指纹，
        见 PanelCreditsTests.test_panel_accounts_join_fingerprint。
        """
        with patch.object(status, "_WORKBUDDY_AUTH_DIRS", ("/tmp/wb-a", "/tmp/wb-b")):
            fingerprint = status._auth_dirs_fingerprint()
        self.assertEqual(["/tmp/wb-a", "/tmp/wb-b"],
                         [entry[0] for entry in fingerprint])


class PanelCreditsTests(unittest.TestCase):
    """wan3 经 remote-nas 面板取数：实时积分兜底与远端加号快速重采。

    本机不再挂 wan3 的 auths 目录（NFS 导出抖动会拖住 docker 启动），
    因此 wan3 的真实积分只能由面板代查腾讯后回传。
    """

    def setUp(self):
        self.addCleanup(patch.stopall)
        patch.object(status, "WORKBUDDY_WAN3_PANEL", "http://panel:7864").start()
        patch.object(status, "WORKBUDDY_WAN3_PANEL_TOKEN", "wbt_test").start()
        patch.object(status, "_PANEL_CREDIT_CACHE", {"ts": 0.0, "value": None}).start()

    def test_panel_credits_parsed_and_zero_dropped(self):
        """面板返回的积分为正才采纳；0 不采纳（避免把「查不到」当成真额度）。"""
        with patch.object(status, "_panel_json",
                          return_value={"credits": {"u1": 3900, "u2": 0, "u3": None}}):
            self.assertEqual({"u1": 3900}, status._panel_wan3_credits())

    def test_panel_credits_skipped_without_config(self):
        with patch.object(status, "WORKBUDDY_WAN3_PANEL", ""), \
             patch.object(status, "_panel_json", side_effect=AssertionError("不该发起请求")):
            self.assertEqual({}, status._panel_wan3_credits())

    def test_panel_credits_failure_degrades_to_empty(self):
        with patch.object(status, "_panel_json", side_effect=OSError("unreachable")):
            self.assertEqual({}, status._panel_wan3_credits())

    def test_panel_credits_cached_after_success(self):
        with patch.object(status, "_panel_json", return_value={"credits": {"u1": 3900}}) as call:
            status._panel_wan3_credits_cached()
            status._panel_wan3_credits_cached()
        self.assertEqual(1, call.call_count)

    def test_panel_today_credits_reads_usage_credit(self):
        with patch.object(status, "_panel_json",
                          return_value={"today_credit": 12.6}) as call:
            self.assertEqual(13, status._panel_wan3_today_credits())
        self.assertIn("/api/stats/summary", call.call_args.args[0])

    def test_panel_today_credits_failure_returns_none(self):
        with patch.object(status, "_panel_json", side_effect=OSError("unreachable")):
            self.assertIsNone(status._panel_wan3_today_credits())

    def test_panel_today_credits_are_grouped_by_account_uid(self):
        def fake_panel(path, *_args, **_kwargs):
            if path.startswith("/api/logs"):
                return {
                    "total": 3,
                    "items": [
                        {"account": "A(5f268566)", "credit": 1.5},
                        {"account": "A(5f268566)", "credit": 2.5},
                        {"account": "B(710cf934)", "credit": 0.25},
                    ],
                }
            self.fail("unexpected panel path: " + path)

        with patch.object(status, "_panel_json", side_effect=fake_panel):
            values = status._panel_wan3_today_credits_by_account()
        self.assertEqual({"5f268566": 4.0, "710cf934": 0.25}, values)

    def test_panel_today_credits_grouping_reads_every_page(self):
        calls = []

        def fake_panel(path, *_args, **_kwargs):
            calls.append(path)
            if "page=1" in path:
                return {
                    "total": 201,
                    "items": [{"account": "A(5f268566)", "credit": 1.0}]
                    + [{"account": "A(5f268566)", "credit": 0.0}] * 199,
                }
            return {
                "total": 201,
                "items": [{"account": "B(710cf934)", "credit": 2.0}],
            }

        with patch.object(status, "_panel_json", side_effect=fake_panel):
            values = status._panel_wan3_today_credits_by_account()
        self.assertEqual({"5f268566": 1.0, "710cf934": 2.0}, values)
        self.assertEqual(2, len(calls))

    def test_panel_today_credits_do_not_allocate_unknown_accounts(self):
        with patch.object(status, "_panel_json", return_value={
            "total": 2,
            "items": [
                {"account": "", "credit": 4.0},
                {"account": "A(5f268566)", "credit": 1.0},
            ],
        }):
            values = status._panel_wan3_today_credits_by_account()
        self.assertEqual({"5f268566": 1.0}, values)

    def test_complete_today_logs_resolve_missing_account_to_zero(self):
        with patch.object(status, "_panel_json", return_value={
            "total": 1,
            "items": [{"account": "A(5f268566)", "credit": 4.0}],
        }):
            result = status._panel_wan3_today_credits_for_uid(
                "710cf934-7906-4cd7-822c-e3c6b09057cf")
        self.assertEqual((0, True), result)

    def test_incomplete_today_logs_keep_missing_account_unknown(self):
        with patch.object(status, "_panel_json", return_value={
            "total": 2,
            "items": [{"account": "A(5f268566)", "credit": 4.0}],
        }):
            result = status._panel_wan3_today_credits_for_uid(
                "710cf934-7906-4cd7-822c-e3c6b09057cf")
        self.assertEqual((None, False), result)

    def test_today_logs_failure_keeps_account_unknown(self):
        with patch.object(status, "_panel_json", side_effect=OSError("unreachable")):
            result = status._panel_wan3_today_credits_for_uid(
                "710cf934-7906-4cd7-822c-e3c6b09057cf")
        self.assertEqual((None, False), result)

    def test_panel_account_change_invalidates_credit_and_today_caches(self):
        with patch.object(status, "_PANEL_CREDIT_CACHE", {
            "ts": 1.0, "value": {"u1": 100},
        }), patch.object(status, "_PANEL_TODAY_CACHE", {
            "ts": 1.0, "value": {"u1": 10.0},
        }):
            status._invalidate_panel_credit_caches()
            self.assertIsNone(status._PANEL_CREDIT_CACHE["value"])
            self.assertIsNone(status._PANEL_TODAY_CACHE["value"])
            self.assertEqual(0.0, status._PANEL_CREDIT_CACHE["ts"])
            self.assertEqual(0.0, status._PANEL_TODAY_CACHE["ts"])

    def test_collection_refreshes_panel_credit_cache_instead_of_using_stale_value(self):
        with patch.object(status, "_PANEL_CREDIT_CACHE", {
            "ts": 1.0, "value": {"full-uid": 100},
        }), patch.object(status, "_panel_wan3_credits",
                          return_value={"full-uid": 2300}) as refresh:
            value = status._panel_wan3_credits_fresh()
            self.assertEqual({"full-uid": 2300}, value)
            self.assertEqual({"full-uid": 2300}, status._PANEL_CREDIT_CACHE["value"])
        refresh.assert_called_once_with()

    def test_today_credit_uid8_prefix_matches_full_uid(self):
        result = status._panel_today_credit_for_uid(
            {"710cf934": 4.0, "5f268566": 2.0},
            "710cf934-7906-4cd7-822c-e3c6b09057cf",
        )
        self.assertEqual(4.0, result)

    def test_panel_accounts_join_fingerprint(self):
        """远端面板账号参与指纹：在 remote-nas 侧加号也能触发重采。"""
        with patch.object(status, "_WORKBUDDY_AUTH_DIRS", ()), \
             patch.object(status, "_panel_accounts_fingerprint", return_value="u1|a.json"):
            fingerprint = status._account_watch_fingerprint()
        self.assertIn("panel|wan3|u1|a.json", fingerprint)

    def test_panel_accounts_fingerprint_throttled(self):
        """面板清单按 WORKBUDDY_PANEL_WATCH_INTERVAL 节流，不随 5s 轮询打远端。"""
        with patch.object(status, "_PANEL_WATCH_INTERVAL", 60.0), \
             patch.object(status, "_panel_watch_state", {"ts": 0.0, "fingerprint": None}), \
             patch.object(status, "_panel_json",
                          return_value={"accounts": [{"uid": "u1", "file": "a.json"}]}) as call:
            first = status._panel_accounts_fingerprint()
            second = status._panel_accounts_fingerprint()
        self.assertEqual(first, second)
        self.assertEqual(1, call.call_count)

    def test_panel_accounts_fingerprint_failure_returns_none(self):
        with patch.object(status, "_panel_watch_state", {"ts": 0.0, "fingerprint": None}), \
             patch.object(status, "_panel_json", side_effect=OSError("unreachable")):
            self.assertIsNone(status._panel_accounts_fingerprint())


class ActivityContainerTests(unittest.TestCase):
    def test_wan3_has_no_local_container(self):
        """wan3 上游不在本机 docker：返回空表示不采集，绝不退回默认容器。"""
        with patch.object(activity, "WAN3_CONTAINER", ""), \
             patch.object(activity, "DEFAULT_CONTAINER", "workbuddy2api"):
            self.assertEqual("", activity._group_container("wan3"))

    def test_known_groups_map_to_their_containers(self):
        with patch.object(activity, "DEFAULT_CONTAINER", "workbuddy2api"), \
             patch.object(activity, "WAN1_CONTAINER", "workbuddy2api-wan1"):
            self.assertEqual("workbuddy2api", activity._group_container("default"))
            self.assertEqual("workbuddy2api-wan1", activity._group_container("wan1"))

    def test_collect_skips_docker_read_for_wan3(self):
        with patch.object(activity, "WAN3_CONTAINER", ""), \
             patch.object(activity, "_docker_logs") as logs:
            result = activity.collect(minutes=15, group_id="wan3")
        logs.assert_not_called()
        self.assertEqual([], result["accounts"])
        self.assertEqual(0, result["total_requests"])


if __name__ == "__main__":
    unittest.main()


class AccountWatchTests(unittest.TestCase):
    """auths 目录监听：加/删账号后秒级重采。

    采集周期 600s，加号是人工操作，若只靠周期轮询最坏要等 10 分钟卡片才出现。
    监听用与上游 watch.go 同口径的目录指纹（文件名+大小+mtime）把延迟压到秒级。
    """

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.addCleanup(patch.stopall)
        self.dirs = (os.path.join(self.tmp.name, "a"), os.path.join(self.tmp.name, "b"))
        for directory in self.dirs:
            os.makedirs(directory)
        patch.object(status, "_WORKBUDDY_AUTH_DIRS", self.dirs).start()
        patch.object(status, "_account_watch_state",
                     {"fingerprint": None, "not_before": 0.0}).start()
        self.calls = []
        patch.object(status, "collect", side_effect=lambda: self.calls.append("collect")).start()
        patch.object(status, "_refresh_combined_snapshot",
                     side_effect=lambda: self.calls.append("snapshot")).start()

    def _write(self, directory, name, body='{"uid":"x"}'):
        path = os.path.join(directory, name)
        with open(path, "w", encoding="utf-8") as stream:
            stream.write(body)
        return path

    def test_first_check_only_baselines_without_recollect(self):
        self._write(self.dirs[0], "workbuddy-a.json")
        self.assertFalse(status.refresh_on_account_change())
        self.assertEqual([], self.calls)

    def test_new_account_triggers_recollect(self):
        self._write(self.dirs[0], "workbuddy-a.json")
        status.refresh_on_account_change()
        self._write(self.dirs[1], "workbuddy-b.json")
        self.assertTrue(status.refresh_on_account_change())
        self.assertEqual(["collect", "snapshot"], self.calls)

    def test_removed_account_triggers_recollect(self):
        path = self._write(self.dirs[0], "workbuddy-a.json")
        self._write(self.dirs[0], "workbuddy-b.json")
        status.refresh_on_account_change()
        os.unlink(path)
        self.assertTrue(status.refresh_on_account_change())
        self.assertEqual(["collect", "snapshot"], self.calls)

    def test_unchanged_directory_does_not_recollect(self):
        self._write(self.dirs[0], "workbuddy-a.json")
        status.refresh_on_account_change()
        self.calls.clear()
        self.assertFalse(status.refresh_on_account_change())
        self.assertEqual([], self.calls)

    def test_non_json_files_ignored(self):
        self._write(self.dirs[0], "workbuddy-a.json")
        status.refresh_on_account_change()
        self.calls.clear()
        self._write(self.dirs[0], "notes.txt")
        self.assertFalse(status.refresh_on_account_change())
        self.assertEqual([], self.calls)

    def test_unreadable_directory_keeps_previous_fingerprint(self):
        """NFS 抖动导致目录暂时读不到时，不能误判成「账号被删」而重采。"""
        self._write(self.dirs[0], "workbuddy-a.json")
        status.refresh_on_account_change()
        self.calls.clear()
        with patch.object(os, "listdir", side_effect=OSError("stale nfs")):
            self.assertFalse(status.refresh_on_account_change())
        self.assertEqual([], self.calls)

    def test_min_gap_suppresses_rapid_twice(self):
        """面板「写临时文件再原子改名」会造成指纹连续变化，最短间隔内只重采一次。"""
        self._write(self.dirs[0], "workbuddy-a.json")
        status.refresh_on_account_change()
        self.calls.clear()
        self._write(self.dirs[0], "workbuddy-b.json")
        self.assertTrue(status.refresh_on_account_change())
        self._write(self.dirs[0], "workbuddy-c.json")
        self.assertFalse(status.refresh_on_account_change())
        self.assertEqual(["collect", "snapshot"], self.calls)

    def test_collect_error_is_swallowed(self):
        """采集失败不能把监听线程带崩，也不能让异常冒到调用方。"""
        self._write(self.dirs[0], "workbuddy-a.json")
        status.refresh_on_account_change()
        patch.stopall()
        patch.object(status, "_WORKBUDDY_AUTH_DIRS", self.dirs).start()
        patch.object(status, "_account_watch_state",
                     {"fingerprint": "stale", "not_before": 0.0}).start()
        with patch.object(status, "collect", side_effect=RuntimeError("上游不可用")):
            self.assertFalse(status.refresh_on_account_change())
