"""真实 HTTP 回归；采集器替身避免测试访问外部主机或修改生产数据。"""
import http.client
import json
import threading
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch

import api_server


class OverviewCacheTests(unittest.TestCase):
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
        cls.thread.join(timeout=2)

    def setUp(self):
        self.old = {"timestamp": "2026-09-28T10:00:00", "nas": {"status": "up"}}
        self.new = {"timestamp": "2026-09-28T10:06:00", "nas": {"status": "unknown"}}
        cache = dict(api_server._overview_cache)
        cache.update(data=self.old, fetched_mono=time.monotonic() - 600,
                     refreshing=False, refresh_started=0, failures=0,
                     last_error=None, next_retry_mono=0, generation=0)
        for item in (
            patch.object(api_server, "_overview_cache", cache),
            patch.object(api_server, "_overview_built", threading.Event()),
            patch.object(api_server, "APP_API_TOKEN", "overview-test-token"),
            patch.object(api_server, "WEB_API_TOKEN", ""),
        ):
            item.start()
            self.addCleanup(item.stop)

    def request(self, authorized=True):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=2)
        headers = {"Authorization": "Bearer overview-test-token"} if authorized else {}
        try:
            conn.request("GET", "/api/overview", headers=headers)
            response = conn.getresponse()
            return response.status, dict(response.getheaders()), json.loads(response.read())
        finally:
            conn.close()

    def test_expired_snapshot_returns_before_slow_refresh_and_refreshes_once(self):
        started, release = threading.Event(), threading.Event()

        def slow_build():
            started.set()
            release.wait(timeout=10)
            return self.new

        with patch.object(api_server, "_build_overview", side_effect=slow_build) as build:
            try:
                first = self.request()
                self.assertTrue(started.wait(timeout=2))
                with ThreadPoolExecutor(max_workers=4) as pool:
                    responses = list(pool.map(lambda _: self.request(), range(8)))
                for status, headers, data in [first] + responses:
                    self.assertEqual(200, status)
                    self.assertEqual(self.old, data)
                    self.assertEqual("stale", headers["X-Cache-Status"])
                    self.assertGreaterEqual(int(headers["X-Data-Age"]), 600)
                build.assert_called_once()
            finally:
                release.set()
                self.assertTrue(api_server._overview_built.wait(timeout=2))
            status, _, data = self.request()
            self.assertEqual(200, status)
            self.assertEqual(self.new, data)

    def test_refresh_failure_keeps_old_data_and_honors_backoff(self):
        with patch.object(api_server, "_build_overview", side_effect=RuntimeError("collector unavailable")) as build:
            first = self.request()
            self.assertTrue(api_server._overview_built.wait(timeout=2))
            second = self.request()
            for status, headers, data in (first, second):
                self.assertEqual(200, status)
                self.assertEqual(self.old, data)
                self.assertEqual("stale", headers["X-Cache-Status"])
            build.assert_called_once()
            self.assertGreater(api_server._overview_cache["next_retry_mono"], time.monotonic())

    def test_fresh_snapshot_does_not_trigger_collection(self):
        api_server._overview_cache["fetched_mono"] = time.monotonic()
        with patch.object(api_server, "_build_overview") as build:
            status, _, data = self.request()
            self.assertEqual(200, status)
            self.assertEqual(self.old, data)
            build.assert_not_called()

    def test_short_expiry_keeps_existing_stale_behavior(self):
        api_server._overview_cache["fetched_mono"] = time.monotonic() - 20
        with patch.object(api_server, "_overview_request_refresh", return_value=False) as refresh:
            status, headers, data = self.request()
            self.assertEqual(200, status)
            self.assertEqual(self.old, data)
            self.assertEqual("stale", headers["X-Cache-Status"])
            refresh.assert_called_once()

    def test_cold_start_waits_for_first_success(self):
        api_server._overview_cache["data"] = None
        with patch.object(api_server, "_build_overview", return_value=self.new) as build:
            status, _, data = self.request()
            self.assertEqual(200, status)
            self.assertEqual(self.new, data)
            build.assert_called_once()

    def test_no_successful_snapshot_still_reports_unavailable(self):
        api_server._overview_cache["data"] = None
        with patch.object(api_server, "_build_overview", side_effect=RuntimeError("collector unavailable")):
            status, _, data = self.request()
            self.assertEqual(503, status)
            self.assertEqual("overview not ready", data["error"])

    def test_unauthorized_request_cannot_read_snapshot_or_trigger_collection(self):
        with patch.object(api_server, "_build_overview") as build:
            status, _, _ = self.request(authorized=False)
            self.assertEqual(401, status)
            build.assert_not_called()


class AppSetupQrTests(unittest.TestCase):
    """App 扫码导入：地址推断与 payload 组装（不依赖 qrcode 可选依赖）。"""

    def setUp(self):
        for item in (
            patch.object(api_server, "PUBLIC_BASE_URL", ""),
            patch.object(api_server, "APP_API_TOKEN", "app-token-for-qr"),
        ):
            item.start()
            self.addCleanup(item.stop)

    def test_public_base_url_wins_over_request_host(self):
        with patch.object(api_server, "PUBLIC_BASE_URL", "https://monitor.example.com:23245"):
            self.assertEqual("https://monitor.example.com:23245",
                             api_server._setup_base_url("192.0.2.10:8765", "", False))

    def test_request_host_falls_back_to_http(self):
        self.assertEqual("http://192.0.2.10:8765",
                         api_server._setup_base_url("192.0.2.10:8765", "", False))

    def test_forwarded_proto_is_honoured(self):
        self.assertEqual("https://nas.example.com",
                         api_server._setup_base_url("nas.example.com", "https", False))

    def test_tls_connection_defaults_to_https(self):
        self.assertEqual("https://nas.example.com",
                         api_server._setup_base_url("nas.example.com", "", True))

    def test_invalid_host_is_rejected(self):
        for host in ("", "   ", "has space", "evil/path", "host?x=1", "a\nb"):
            with self.subTest(host=host):
                self.assertEqual("", api_server._setup_base_url(host, "", False))

    def test_payload_shape_and_escaping(self):
        payload = api_server.app_setup_payload("https://monitor.example.com:23245")
        self.assertTrue(payload.startswith("nasmonitor://setup?url="))
        self.assertIn("url=https%3A%2F%2Fmonitor.example.com%3A23245", payload)
        self.assertIn("token=app-token-for-qr", payload)

    def test_payload_requires_base_url_and_token(self):
        with self.assertRaises(RuntimeError):
            api_server.app_setup_payload("")
        with patch.object(api_server, "APP_API_TOKEN", ""):
            with self.assertRaises(RuntimeError):
                api_server.app_setup_payload("http://192.0.2.10:8765")


if __name__ == "__main__":
    unittest.main()
