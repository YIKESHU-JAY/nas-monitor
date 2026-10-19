import http.client
import json
from pathlib import Path
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch

import api_server as api


class ServerManagementTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.registry = Path(temporary.name) / "servers.json"
        for mocked in (
            patch.object(api, "SERVER_REGISTRY_FILE", self.registry),
            patch.object(api, "APP_API_TOKEN", "test-token"),
            patch.object(api, "WEB_API_TOKEN", ""),
            patch.object(api, "_trends", {}),
            patch.object(api, "_trends_temp", {}),
            patch.object(api, "_overview_cache", dict(api._overview_cache)),
        ):
            mocked.start()
            self.addCleanup(mocked.stop)
        self.server = api.ThreadingHTTPServer(("127.0.0.1", 0), api.APIHandler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop_server)

    def stop_server(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)

    def request(self, method, path, body=None, auth=True):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        headers = {"Authorization": "Bearer test-token"} if auth else {}
        try:
            connection.request(method, path, body=json.dumps(body) if body is not None else None, headers=headers)
            response = connection.getresponse()
            return response.status, json.loads(response.read())
        finally:
            connection.close()

    def test_migration_only_once_and_empty_persists(self):
        self.assertEqual({"aliyun", "tencent"}, set(api._load_server_registry()))
        api.delete_server("aliyun")
        api.delete_server("tencent")
        self.assertEqual({}, api._load_server_registry())
        self.assertEqual(["nas"], [entry["id"] for entry in api.server_entries()])

    def test_corrupt_file_is_never_overwritten_or_reseeded(self):
        self.registry.write_text("broken")
        with self.assertRaises(ValueError):
            api._load_server_registry()
        self.assertEqual("broken", self.registry.read_text())

    def test_auth_required_for_mutations(self):
        for method, path in (("POST", "/api/servers"), ("POST", "/api/servers/check"), ("DELETE", "/api/servers/tencent")):
            self.assertEqual(401, self.request(method, path, {}, auth=False)[0])
        self.assertFalse(self.registry.exists())

    def test_invalid_urls_and_unreachable_do_not_save(self):
        for url in ("file:///etc/passwd", "http://user:password@host:61208", "http://host/?token=secret", "http://host/#fragment", "http://host:99999"):
            self.assertEqual(400, self.request("POST", "/api/servers", {"name": "Test", "glances_url": url})[0])
        with patch.object(api, "check_server", side_effect=ValueError("offline")):
            self.assertEqual(400, self.request("POST", "/api/servers", {"name": "Test", "glances_url": "http://test:61208"})[0])
        self.assertFalse(self.registry.exists())

    def test_check_is_readonly_add_checks_again_and_duplicate_rejected(self):
        body = {"name": "Test", "glances_url": "HTTP://EXAMPLE.com:80/"}
        with patch.object(api, "check_server") as check:
            self.assertEqual(200, self.request("POST", "/api/servers/check", body)[0])
            self.assertFalse(self.registry.exists())
            status, server = self.request("POST", "/api/servers", body)
            self.assertEqual(201, status)
            self.assertEqual("http://example.com", server["glances_url"])
            self.assertEqual(400, self.request("POST", "/api/servers", body)[0])
            self.assertEqual(3, check.call_count)
        self.assertIn(server["id"], api._load_server_registry())

    def test_concurrent_additions_do_not_lose_updates(self):
        with patch.object(api, "check_server"):
            with ThreadPoolExecutor(max_workers=4) as pool:
                servers = list(pool.map(lambda i: api.add_server({"name": str(i), "glances_url": f"http://test-{i}:61208"}), range(8)))
        self.assertEqual(10, len(api._load_server_registry()))
        self.assertEqual(8, len({server["id"] for server in servers}))

    def test_delete_cleans_runtime_and_late_samples_cannot_resurrect(self):
        api.record_trend("tencent", 80, 90, 20, 1, 2)
        api.record_temperature_trends("tencent", {"disk": 40})
        api.build_alerts({}, {}, {"tencent": {"memory": 99}})
        api._overview_cache["data"] = {"tencent": {"status": "up"}}
        status, result = self.request("DELETE", "/api/servers/tencent")
        self.assertEqual(200, status)
        self.assertNotIn("tencent", [server["id"] for server in result["servers"]])
        api.record_trend("tencent", 80, 90, 20, 1, 2)
        api.record_temperature_trends("tencent", {"disk": 40})
        self.assertNotIn("tencent", api._trends)
        self.assertNotIn("tencent", api._trends_temp)
        self.assertFalse(any(key[0] == "tencent" for key in api.build_alerts._first_seen))
        self.assertIsNone(api._overview_cache["data"])
        with patch.object(api, "get_glances") as collector, patch.object(api, "get_tencent_security") as security:
            for path in ("/api/tencent", "/api/security/tencent", "/api/trends/tencent/60", "/api/trends/temperature/tencent", "/api/container/stats/tencent", "/api/container/tencent/log", "/api/servers/tencent/detail"):
                self.assertEqual(404, self.request("GET", path)[0], path)
            collector.assert_not_called()
            security.assert_not_called()

    def test_local_nas_cannot_be_deleted(self):
        self.assertEqual(400, self.request("DELETE", "/api/servers/nas")[0])

    def test_security_credentials_never_returned_and_deleted_with_server(self):
        with patch.dict(api.os.environ, {"TENCENT_SECURITY_TOKEN": "test-private-secret"}):
            api._load_server_registry()
        self.assertIn("test-private-secret", self.registry.read_text())
        self.assertNotIn("test-private-secret", json.dumps(api.server_entries()))
        api.delete_server("tencent")
        self.assertNotIn("test-private-secret", self.registry.read_text())

    def test_failed_disk_write_preserves_registration_and_trends(self):
        api.record_trend("tencent", 1, 2, 3, 4, 5)
        with patch.object(api.os, "replace", side_effect=OSError("disk full")):
            self.assertEqual(500, self.request("DELETE", "/api/servers/tencent")[0])
        self.assertIn("tencent", api._load_server_registry())
        self.assertEqual(1, len(api._trends["tencent"]))

    def test_inflight_overview_cannot_restore_deleted_host(self):
        started, release = threading.Event(), threading.Event()
        def glances(url):
            if url == api.TENCENT_GLANCES:
                started.set()
                release.wait(3)
            return {"cpu": {"percent": 10}, "memory": {"percent": 95}}
        with patch.object(api, "get_glances", side_effect=glances), patch.object(api, "get_core_services", return_value={}), patch.object(api, "get_public_ip", return_value=""), patch.object(api, "ping_host", return_value=1.0), patch.object(api, "get_router_status", return_value={}):
            with ThreadPoolExecutor(max_workers=1) as pool:
                future = pool.submit(api._build_overview)
                try:
                    self.assertTrue(started.wait(2))
                    api.delete_server("tencent")
                finally:
                    release.set()
                result = future.result(3)
        self.assertNotIn("tencent", result)
        self.assertNotIn("tencent", [entry["id"] for entry in result["servers"]])
        self.assertFalse(any(alert["host"] == "tencent" for alert in result["alerts"]))
        self.assertNotIn("tencent", api._trends)


if __name__ == "__main__":
    unittest.main()
