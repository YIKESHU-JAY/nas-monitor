import http.client
import json
import threading
import unittest
from unittest.mock import patch

import api_server


class FakeDockerConnection:
    status = 204
    payload = b""

    def __init__(self, host, timeout=None):
        self.host = host
        self.timeout = timeout
        self.sock = None
        self.request_method = None
        self.request_path = None

    def request(self, method, path):
        self.request_method = method
        self.request_path = path

    def getresponse(self):
        response = self
        return response

    def read(self):
        return self.payload

    def close(self):
        pass


class ContainerActionHttpTests(unittest.TestCase):
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

    def request(self, method, path, payload=None, authorized=True):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        headers = {"Content-Type": "application/json"}
        if authorized:
            headers["Authorization"] = "Bearer test-monitor-token"
        body = None if payload is None else json.dumps(payload)
        try:
            conn.request(method, path, body=body, headers=headers)
            response = conn.getresponse()
            data = json.loads(response.read().decode())
            return response.status, data
        finally:
            conn.close()

    def test_container_action_requires_auth(self):
        with patch.object(api_server, "APP_API_TOKEN", "test-monitor-token"):
            status, data = self.request("POST", "/api/containers/nas/kuma/restart", authorized=False)
        self.assertEqual(401, status)
        self.assertEqual("unauthorized", data["error"])

    def test_container_action_returns_updated_list(self):
        snapshot = {"total": 1, "running": 1, "stopped": 0, "healthy": 1,
                    "containers": [{"name": "kuma", "status": "running"}]}
        with patch.object(api_server, "APP_API_TOKEN", "test-monitor-token"), \
                patch.object(api_server, "docker_container_action",
                             return_value={"ok": True, "host": "nas", "name": "kuma", "action": "restart"}) as action, \
                patch.object(api_server, "get_docker_stats", return_value=snapshot):
            status, data = self.request("POST", "/api/containers/nas/kuma/restart")
        self.assertEqual(200, status)
        self.assertTrue(data["ok"])
        self.assertEqual("restart", data["action"])
        self.assertEqual("kuma", data["containers"]["containers"][0]["name"])
        action.assert_called_once_with("kuma", "restart")

    def test_remote_container_action_is_rejected(self):
        with patch.object(api_server, "APP_API_TOKEN", "test-monitor-token"):
            status, data = self.request("POST", "/api/containers/aliyun/kuma/start")
        self.assertEqual(400, status)
        self.assertIn("NAS 本机", data["error"])

    def test_unknown_container_action_is_not_routed(self):
        with patch.object(api_server, "APP_API_TOKEN", "test-monitor-token"):
            status, data = self.request("POST", "/api/containers/nas/kuma/delete")
        self.assertEqual(404, status)
        self.assertEqual("not found", data["error"])

    def test_encoded_traversal_name_is_rejected(self):
        with patch.object(api_server, "APP_API_TOKEN", "test-monitor-token"):
            status, data = self.request("POST", "/api/containers/nas/%2e%2e%2fkuma/start")
        self.assertEqual(400, status)
        self.assertIn("容器名称", data["error"])

    def test_container_action_reports_docker_errors(self):
        with patch.object(api_server, "APP_API_TOKEN", "test-monitor-token"), \
                patch.object(api_server, "docker_container_action", side_effect=RuntimeError("permission denied")):
            status, data = self.request("POST", "/api/containers/nas/kuma/stop")
        self.assertEqual(500, status)
        self.assertIn("permission denied", data["error"])


class DockerContainerActionTests(unittest.TestCase):
    def test_posts_whitelisted_action_to_docker_socket(self):
        connection = FakeDockerConnection("localhost", timeout=20)
        with patch("http.client.HTTPConnection", return_value=connection):
            result = api_server.docker_container_action("my.container", "restart")
        self.assertTrue(result["ok"])
        self.assertEqual("POST", connection.request_method)
        self.assertEqual("/containers/my.container/restart", connection.request_path)

    def test_keeps_valid_container_name_in_path(self):
        connection = FakeDockerConnection("localhost", timeout=20)
        with patch("http.client.HTTPConnection", return_value=connection):
            api_server.docker_container_action("name_with.dot", "start")
        self.assertEqual("/containers/name_with.dot/start", connection.request_path)

    def test_rejects_unknown_action_and_bad_name(self):
        with self.assertRaises(ValueError):
            api_server.docker_container_action("kuma", "delete")
        with self.assertRaises(ValueError):
            api_server.docker_container_action("../kuma", "start")

    def test_maps_missing_container_to_lookup_error(self):
        connection = FakeDockerConnection("localhost", timeout=20)
        connection.status = 404
        with patch("http.client.HTTPConnection", return_value=connection):
            with self.assertRaises(LookupError):
                api_server.docker_container_action("missing", "stop")


if __name__ == "__main__":
    unittest.main()
