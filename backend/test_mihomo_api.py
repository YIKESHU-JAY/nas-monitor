import json
import time
import unittest
from unittest.mock import patch

import mihomo_api


class MihomoApiTests(unittest.TestCase):
    def setUp(self):
        mihomo_api.history = mihomo_api.TrafficHistory()
        mihomo_api._airport_cache.clear()

    def test_history_is_isolated_per_instance(self):
        mihomo_api.history.record("core", 100, 200, 3)
        mihomo_api.history.record("v2", 1, 2, 1)
        core = mihomo_api.history.snapshot("core")
        v2 = mihomo_api.history.snapshot("v2")
        self.assertEqual([100], [p["up"] for p in core])
        self.assertEqual([1], [p["up"] for p in v2])

    def test_history_is_capped(self):
        for i in range(mihomo_api.HISTORY_POINTS + 20):
            mihomo_api.history.record("core", i, i, 0)
        points = mihomo_api.history.snapshot("core")
        self.assertEqual(mihomo_api.HISTORY_POINTS, len(points))
        self.assertEqual(mihomo_api.HISTORY_POINTS + 19, points[-1]["up"])

    @patch("mihomo_api.traffic_sample")
    @patch("mihomo_api._request")
    def test_speeds_use_global_traffic_not_live_connection_sums(self, request_mock, traffic_mock):
        def fake(instance, method, path, body=None, timeout=8):
            if path == "/connections":
                return {"connections": [{"upload": 1, "download": 2}]}
            raise AssertionError(path)

        request_mock.side_effect = fake
        traffic_mock.return_value = {"up": 50, "down": 100, "upTotal": 9000, "downTotal": 8000}
        result = mihomo_api.sample_speeds("core")
        self.assertEqual(50, result["upload_speed"])
        self.assertEqual(100, result["download_speed"])
        self.assertEqual(9000, result["upload_total"])
        self.assertEqual(1, mihomo_api.history.snapshot("core")[-1]["connections"])
        self.assertEqual([], mihomo_api.history.snapshot("v2"))

    @patch("mihomo_api._airport_traffic")
    def test_airport_subscription_is_cached_for_ttl(self, traffic_mock):
        traffic_mock.return_value = [{"id": "airport1"}]
        mihomo_api.airports()
        mihomo_api.airports()
        self.assertEqual(2, traffic_mock.call_count)  # once per instance, second call cached
        with patch("mihomo_api.time.time", return_value=time.time() + mihomo_api.AIRPORT_TTL + 1):
            mihomo_api.airports()
        self.assertEqual(4, traffic_mock.call_count)

    @patch("mihomo_api._request")
    def test_airport_connection_attribution_stays_in_instance(self, request_mock):
        def fake(instance, method, path, body=None, timeout=8):
            if path == "/connections":
                return {"connections": [
                    {"chains": ["airport1"], "upload": 1, "download": 2},
                    {"chains": ["airport2", "airport1"], "upload": 3, "download": 4},
                ]}
            if path.startswith("/providers/proxies/"):
                return {"subscriptionInfo": {"Upload": 1024 ** 3, "Download": 2 * 1024 ** 3,
                                             "Total": 100 * 1024 ** 3}}
            raise AssertionError(path)

        request_mock.side_effect = fake
        items = mihomo_api._airport_traffic("v2")
        by_id = {item["id"]: item for item in items}
        # 每条连接只按 chains 里第一个命中的机场归属：第一条 airport1，第二条 airport2。
        self.assertEqual(1, by_id["airport1"]["connections"])
        self.assertEqual(1, by_id["airport2"]["connections"])
        self.assertEqual(3.0, by_id["airport1"]["used_gb"])
        # 两条连接各计一次，不能因为 chains 里同时出现两个机场而重复计数。
        self.assertEqual(2, sum(item["connections"] for item in items))

    @patch("mihomo_api._request")
    def test_select_group_verifies_readback(self, request_mock):
        payload = {"proxies": {"主出口": {"type": "Selector", "now": "旧节点", "all": ["旧节点", "新节点"]}}}
        calls = []

        def fake(instance, method, path, body=None, timeout=8):
            calls.append((method, path, body))
            if method == "PUT":
                payload["proxies"]["主出口"]["now"] = "新节点"
                return {}
            return payload

        request_mock.side_effect = fake
        result = mihomo_api.select_group("core", "主出口", "新节点")
        self.assertTrue(result["applied"])
        self.assertEqual("新节点", result["now"])
        self.assertEqual(("PUT", "/proxies/%E4%B8%BB%E5%87%BA%E5%8F%A3", {"name": "新节点"}), calls[1])

    @patch("mihomo_api._request")
    def test_select_group_rejects_unknown_target(self, request_mock):
        request_mock.return_value = {"proxies": {"主出口": {"type": "Selector", "now": "旧节点", "all": ["旧节点"]}}}
        with self.assertRaises(mihomo_api.MihomoError):
            mihomo_api.select_group("core", "主出口", "不存在的节点")

    def test_error_messages_do_not_leak_secret(self):
        with patch("mihomo_api._secret", return_value="super-secret-value"), \
             patch("mihomo_api.urllib.request.urlopen", side_effect=OSError("boom")):
            with self.assertRaises(mihomo_api.MihomoError) as ctx:
                mihomo_api._request("core", "GET", "/version")
        self.assertNotIn("super-secret-value", str(ctx.exception))

    @patch("mihomo_api.INSTANCES", new_callable=dict)
    def test_unknown_instance_never_reaches_upstream(self, instances):
        instances["core"] = {"label": "主代理", "api": "http://x", "config": "/nope",
                             "airports": {}, "panel": "http://x"}
        with patch("mihomo_api._request") as request_mock:
            with self.assertRaises(mihomo_api.MihomoError):
                mihomo_api.select_group("nope", "g", "t")
        request_mock.assert_not_called()

    def test_panel_backend_keeps_port(self):
        with patch.dict("os.environ", {"MIHOMO_PANEL_BACKEND_URL": ""}):
            self.assertEqual("http://192.0.2.10:8765",
                             mihomo_api.panel_backend_url("192.0.2.10:8765"))

    def test_panel_config_rewrites_default_backend(self):
        original = "window.__METACUBEXD_CONFIG__ = { defaultBackendURL: '' }"
        patched = mihomo_api.panel_config_js(original, "192.0.2.10:8765")
        self.assertIn('defaultBackendURL: "http://192.0.2.10:8765"', patched)

    def test_panel_bootstrap_injects_backend_and_polyfill(self):
        html = '<head><script>window.__NUXT__.config={public:{defaultBackendURL:""}}</script></head>'
        patched = mihomo_api.panel_bootstrap_html(html, "192.0.2.10:8765")
        self.assertIn('defaultBackendURL:"http://192.0.2.10:8765"', patched)
        self.assertIn('<script src="/polyfill.js"></script>', patched)

    def test_panel_compat_prefers_local_asset(self):
        import tempfile
        with tempfile.TemporaryDirectory() as root:
            target = f"{root}/_nuxt/a.js"
            import os
            os.makedirs(os.path.dirname(target))
            with open(target, "w") as stream:
                stream.write("var a=1")
            with patch.object(mihomo_api, "PANEL_COMPAT_DIR", root):
                self.assertEqual(target, mihomo_api.panel_compat_file("/_nuxt/a.js"))
                # 路径穿越必须被拒绝。
                self.assertIsNone(mihomo_api.panel_compat_file("/_nuxt/../secret"))

    @patch("mihomo_api._request")
    def test_connections_are_trimmed_for_the_app(self, request_mock):
        request_mock.return_value = {"uploadTotal": 5, "downloadTotal": 6, "connections": [{
            "id": "c1", "upload": 1, "download": 2, "chains": ["新加坡", "主出口"],
            "rule": "Match", "metadata": {"host": "example.com", "destinationPort": "443",
                                          "network": "tcp", "process": ""},
        }]}
        result = mihomo_api.connections("core", 10)
        self.assertEqual(1, len(result["connections"]))
        self.assertEqual("example.com", result["connections"][0]["host"])
        self.assertEqual(["新加坡", "主出口"], result["connections"][0]["chains"])

    @patch("mihomo_api._request")
    def test_rules_keep_kernel_index(self, request_mock):
        request_mock.return_value = {"rules": [
            {"index": 7, "type": "GeoIP", "payload": "lan", "proxy": "直连", "size": 0,
             "extra": {"disabled": True}},
        ]}
        rule = mihomo_api.rules("core")["rules"][0]
        self.assertEqual(("GeoIP", "lan", "直连"), (rule["type"], rule["payload"], rule["proxy"]))
        self.assertTrue(rule["disabled"])
        self.assertEqual(7, rule["index"])

    @patch("mihomo_api._request")
    def test_config_write_is_allowlisted(self, request_mock):
        with self.assertRaises(mihomo_api.MihomoError):
            mihomo_api.set_config("core", "external-controller", "0.0.0.0:9999")
        with self.assertRaises(mihomo_api.MihomoError):
            mihomo_api.set_config("core", "mode", "bogus")
        request_mock.assert_not_called()
        mihomo_api.set_config("core", "mode", "global")
        self.assertEqual(("PATCH", "/configs", {"mode": "global"}),
                         (request_mock.call_args.args[1], request_mock.call_args.args[2],
                          request_mock.call_args.args[3]))

    @patch("mihomo_api._request")
    def test_provider_update_quotes_names(self, request_mock):
        mihomo_api.provider_action("v2", "机场 一号", "update")
        self.assertEqual("/providers/proxies/%E6%9C%BA%E5%9C%BA%20%E4%B8%80%E5%8F%B7",
                         request_mock.call_args.args[2])


if __name__ == "__main__":
    unittest.main()
