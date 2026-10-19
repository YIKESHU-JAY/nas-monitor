import json
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

import api_server
import nas_hardware


class NasHardwareTests(unittest.TestCase):
    def entity(self, age=0, state="ok"):
        return {"state": state, "attributes": {"updated_at": time.time() - age,
                "source": "sysfs-smart-ipmi", "disks": {"sdg": 40.0},
                "fans": {"CPU0_FAN": {"value": 1200, "status": "ok"},
                         "SYS_FAN2": {"value": 1000, "status": "lnc"}},
                "temperatures": {"BMC · PCH_TEMP": 46}, "voltages": {"P12V": {"value": 12.01}}}}

    def test_ipmi_ignores_missing_sensors_and_retains_threshold_status(self):
        result = nas_hardware.parse_ipmi(
            "CPU0_TEMP | 21h | ok | 3.1 | 37 degrees C\n"
            "SYS_FAN1 | 62h | lnc | 29.2 | 800 RPM\n"
            "SYS_FAN4 | 65h | ns | 29.5 | No Reading\n"
            "DIMM_A | 01h | ns | 7.4 | No Reading\n"
            "P12V | 40h | ok | 7.16 | 12.01 Volts\n")
        self.assertEqual({"CPU0_TEMP": {"value": 37.0, "status": "ok"}}, result["temperatures"])
        self.assertEqual({"SYS_FAN1": {"value": 800.0, "status": "lnc"}}, result["fans"])
        self.assertEqual(12.01, result["voltages"]["P12V"]["value"])

    def test_invalid_values_are_not_fabricated(self):
        for value in [None, "unavailable", "nan", "inf", 999]:
            self.assertIsNone(nas_hardware.numeric_reading(value, -20, 150))
        self.assertEqual({}, nas_hardware.parse_ipmi("bad\nTEMP | 1 | ok | 1 | 999 degrees C")["temperatures"])

    def test_merge_replaces_old_disk_and_fake_pwm_values(self):
        result = {"fans": {"fan1": {"rpm": "0", "pwm": "150"}}, "hdd_temps": {"sda": 0}}
        api_server.merge_nas_hardware(result, self.entity())
        self.assertEqual({"sdg": 40.0}, result["hdd_temps"])
        self.assertEqual(1200, result["fans"]["fan1"]["rpm"])
        self.assertEqual("bmc", result["fans"]["fan1"]["mode"])
        self.assertIsNone(result["fans"]["fan1"]["pwm"])
        self.assertEqual("lnc", result["fans"]["fan2"]["status"])

    def test_stale_future_and_unavailable_hardware_is_not_merged(self):
        for entity in [self.entity(age=121), self.entity(age=-30), self.entity(state="unavailable"),
                       {"state": "ok", "attributes": {"updated_at": "bad"}}]:
            self.assertEqual({}, api_server.hardware_attributes(entity))
            result = {"hdd_temps": {"existing": 30}}
            api_server.merge_nas_hardware(result, entity)
            self.assertEqual({"hdd_temps": {"existing": 30}}, result)

    def test_partial_readings_remain_usable(self):
        self.assertEqual({"sdg": 40.0}, api_server.hardware_attributes(self.entity(state="partial"))["disks"])

    def test_dynamic_disk_discovery_includes_new_sdg_and_avoids_wake(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "class/hwmon").mkdir(parents=True)
            for name, rotating in [("sda", 1), ("sdg", 0), ("nvme0n1", 0), ("loop0", 0)]:
                queue = root / "block" / name / "queue"
                queue.mkdir(parents=True)
                (queue / "rotational").write_text(str(rotating))
            response = Mock(stdout=json.dumps({"model_name": "test disk", "temperature": {"current": 40}}))
            runner = Mock(return_value=response)
            with patch.object(nas_hardware.Path, "exists", return_value=False):
                result = nas_hardware.collect(root, runner)
            self.assertEqual({"sda", "sdg", "nvme0n1"}, set(result["disks"]))
            self.assertTrue(result["disk_details"]["sda"]["rotational"])
            self.assertFalse(result["disk_details"]["sdg"]["rotational"])
            self.assertEqual("test disk", result["disk_details"]["sdg"]["model"])
            self.assertEqual(3, runner.call_count)
            for call in runner.call_args_list:
                self.assertEqual(["smartctl", "-n", "standby", "-j", "-i", "-A"], call.args[0][:-1])

    def test_curve_average_uses_rotational_disks_only(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "curve.csv"
            path.write_text("timestamp,CPU_C,HDD_avg,sda_C,sdg_C,PWM,fan1_RPM,fan2_RPM\n")
            snapshot = {"cpu_temp": 37, "disks": {"sda": 32, "sdg": 45},
                        "disk_details": {"sda": {"rotational": True, "temperature": 32},
                                         "sdg": {"rotational": False, "temperature": 45}},
                        "fans": {"CPU0_FAN": {"value": 1200}, "SYS_FAN1": {"value": 800}}}
            nas_hardware.append_bmc_curve(snapshot, path)
            values = path.read_text().splitlines()[1].split(",")
            self.assertEqual("32.0", values[2])
            self.assertEqual("", values[5])
            self.assertEqual(["1200", "800"], values[6:])

    def test_atomic_snapshot_replace_has_no_leftover_temporary_files(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "hardware.json"
            nas_hardware.save_snapshot({"status": "old"}, path)
            nas_hardware.save_snapshot({"status": "ok"}, path)
            self.assertEqual({"status": "ok"}, json.loads(path.read_text()))
            self.assertEqual([path], list(Path(directory).iterdir()))

    def test_temperature_sources_do_not_duplicate_cpu_readings(self):
        result = api_server.merge_temperature_sources({"Core 0": 35},
                    {"coretemp · Core 0": 35, "disk · sdg": 40, "BMC · PCH_TEMP": 46})
        self.assertEqual({"Core 0": 35, "disk · sdg": 40, "BMC · PCH_TEMP": 46}, result)
        self.assertEqual({"coretemp · Core 0": 35},
                         api_server.merge_temperature_sources({}, {"coretemp · Core 0": 35}))

    def test_hardware_temperature_fetch_uses_ha_entity_and_checks_freshness(self):
        response = Mock()
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        response.read.return_value = json.dumps(self.entity()).encode()
        with patch.object(api_server, "HA_TOKEN", "test-token"), \
             patch.object(api_server.urllib.request, "urlopen", return_value=response) as fetch:
            self.assertEqual({"BMC · PCH_TEMP": 46}, api_server.get_nas_hardware_temperatures())
            self.assertTrue(fetch.call_args.args[0].full_url.endswith("/api/states/sensor.nas_hardware"))
            response.read.return_value = json.dumps(self.entity(age=121)).encode()
            self.assertEqual({}, api_server.get_nas_hardware_temperatures())

    def test_nas_disk_mounts_exclude_container_artifacts_and_mirrors(self):
        filesystem = [{"mnt_point": mount, "size": 1024 ** 3, "used": 0}
                      for mount in ["/vol1", "/vol5", "/host-root", "/host-root/vol5",
                                    "/vol5/docker/overlay2/test/merged", "/etc/hosts"]]

        def fetch(url, **kwargs):
            if url.endswith("/cpu"):
                return {"total": 1}
            if url.endswith("/fs"):
                return filesystem
            return {}

        with patch.object(api_server, "fetch_json", side_effect=fetch):
            result = api_server.get_glances(api_server.NAS_GLANCES)
        self.assertEqual(["/vol1", "/vol5", "/host-root"], [entry["mount"] for entry in result["disks"]])

    def test_new_volume_mirror_mount_is_normalized_and_deduplicated(self):
        # Glances 只给新卷 /host-root/vol7（未单独 bind mount），且 /vol7 也可能同时存在。
        filesystem = [
            {"mnt_point": "/vol1", "device_name": "/dev/x1", "size": 1024 ** 3, "used": 0, "percent": 1},
            {"mnt_point": "/host-root/vol7", "device_name": "/dev/x7", "size": 2048 ** 3, "used": 0, "percent": 5},
            {"mnt_point": "/vol7", "device_name": "/dev/x7", "size": 2048 ** 3, "used": 0, "percent": 5},
            {"mnt_point": "/host-root", "device_name": "/dev/root", "size": 1024 ** 3, "used": 0, "percent": 2},
        ]

        def fetch(url, **kwargs):
            if url.endswith("/cpu"):
                return {"total": 1}
            if url.endswith("/fs"):
                return filesystem
            return {}

        with patch.object(api_server, "fetch_json", side_effect=fetch):
            result = api_server.get_glances(api_server.NAS_GLANCES)
        self.assertEqual(["/vol1", "/vol7", "/host-root"], [entry["mount"] for entry in result["disks"]])

    def test_volume_list_follows_actual_mounts(self):
        disks = [{"mount": "/vol1", "percent": 10.0, "used_gb": 100.0, "total_gb": 1000.0},
                 {"mount": "/vol7", "percent": 34.3, "used_gb": 80.9, "total_gb": 236.4},
                 {"mount": "/host-root", "percent": 75.0, "used_gb": 44.0, "total_gb": 62.0}]
        ha_volumes = [{"name": "vol1", "percent": 9.0, "capacity": "100G / 1000G",
                       "used": "99", "friendly": "NAS vol1 容量"}]
        merged = api_server.merge_dynamic_volumes(ha_volumes, disks)
        self.assertEqual(["vol1", "vol7"], [v["name"] for v in merged])
        # 已有 HA 传感器保留文案，新卷按 Glances 数值生成文案
        self.assertEqual("100G / 1000G", merged[0]["capacity"])
        self.assertEqual(10.0, merged[0]["percent"])
        self.assertEqual("80.9G / 236.4G", merged[1]["capacity"])
        self.assertNotIn("/host-root", [v["name"] for v in merged])

    def test_removed_volume_disappears(self):
        disks = [{"mount": "/vol2", "percent": 50.0, "used_gb": 5.0, "total_gb": 10.0}]
        ha_volumes = [{"name": "vol1", "percent": 9.0, "capacity": "x", "used": "1", "friendly": "f1"},
                      {"name": "vol2", "percent": 40.0, "capacity": "y", "used": "2", "friendly": "f2"}]
        merged = api_server.merge_dynamic_volumes(ha_volumes, disks)
        self.assertEqual(["vol2"], [v["name"] for v in merged])
        self.assertEqual("y", merged[0]["capacity"])

    def test_volume_merge_falls_back_to_ha_when_glances_unavailable(self):
        ha_volumes = [{"name": "vol1", "percent": 9.0, "capacity": "x", "used": "1", "friendly": "f1"}]
        self.assertEqual(ha_volumes, api_server.merge_dynamic_volumes(ha_volumes, []))

    def test_github_archive_falls_back_to_raw_text_when_url_collector_fails(self):
        calls = []

        def archive_request(path, body, timeout=60):
            calls.append((path, json.loads(body)))
            if path.endswith("/upload-link"):
                raise RuntimeError("collector timeout")
            return {"success": True, "documents": [{"id": "doc"}]}

        with patch.object(api_server, "_archive_request", side_effect=archive_request), \
             patch.object(api_server, "_archive_fetch_github_readme", return_value="# ECC\nREADME content"), \
             patch.object(api_server.time, "sleep"):
            result = api_server._archive_upload_link(
                "https://github.com/affaan-m/ECC", {"title": "ECC"}
            )

        self.assertTrue(result["success"])
        self.assertEqual("raw-text", result["fallback_mode"])
        self.assertEqual("https://raw.githubusercontent.com/affaan-m/ECC/main/README.md",
                         result["fallback_source"])
        self.assertEqual("/api/v1/document/raw-text", calls[-1][0])
        self.assertEqual("# ECC\nREADME content", calls[-1][1]["textContent"])
        self.assertEqual("ECC", calls[-1][1]["metadata"]["title"])
        # GitHub 走 raw-text 直连时不应再调用不稳定的 upload-link collector
        self.assertEqual(["/api/v1/document/raw-text"], [c[0] for c in calls])

    def test_github_archive_prefers_raw_text_without_touching_collector(self):
        calls = []

        def archive_request(path, body, timeout=60):
            calls.append((path, timeout))
            return {"success": True, "documents": [{"id": "doc"}]}

        with patch.object(api_server, "_archive_request", side_effect=archive_request),              patch.object(api_server, "_archive_fetch_github_readme", return_value="# ECC\n"),              patch.object(api_server.time, "sleep"):
            result = api_server._archive_upload_link("https://github.com/affaan-m/ECC", {})

        self.assertEqual("raw-text", result["fallback_mode"])
        self.assertEqual(["/api/v1/document/raw-text"], [p for p, _ in calls])
        self.assertGreaterEqual(calls[0][1], 300)

    def test_github_subpath_uses_collector_not_readme(self):
        calls = []

        def archive_request(path, body, timeout=60):
            calls.append(path)
            return {"success": True, "documents": [{"id": "doc"}]}

        with patch.object(api_server, "_archive_request", side_effect=archive_request), \
             patch.object(api_server, "_archive_fetch_github_readme") as fetch, \
             patch.object(api_server.time, "sleep"):
            result = api_server._archive_upload_link("https://github.com/affaan-m/ECC/issues/29", {})

        self.assertTrue(result["success"])
        fetch.assert_not_called()
        self.assertEqual(["/api/v1/document/upload-link"], calls)

    def test_github_repo_root_detection(self):
        self.assertTrue(api_server._is_github_repo_root("https://github.com/affaan-m/ECC"))
        self.assertTrue(api_server._is_github_repo_root("https://github.com/affaan-m/ECC/"))
        self.assertTrue(api_server._is_github_repo_root("https://github.com/affaan-m/ECC.git"))
        self.assertFalse(api_server._is_github_repo_root("https://github.com/affaan-m/ECC/issues/29"))
        self.assertFalse(api_server._is_github_repo_root("https://github.com/affaan-m"))
        self.assertFalse(api_server._is_github_repo_root("https://example.com/affaan-m/ECC"))

    def test_non_github_link_uses_collector(self):
        calls = []

        def archive_request(path, body, timeout=60):
            calls.append(path)
            return {"success": True, "documents": [{"id": "doc"}]}

        with patch.object(api_server, "_archive_request", side_effect=archive_request), \
             patch.object(api_server, "_archive_fetch_github_readme") as fetch, \
             patch.object(api_server.time, "sleep"):
            result = api_server._archive_upload_link("https://example.com/docs", {})

        self.assertTrue(result["success"])
        fetch.assert_not_called()
        self.assertEqual(["/api/v1/document/upload-link"], calls)

    def test_pinned_https_connection_preserves_sni(self):
        seen = {}

        def fake_wrap(sock, server_hostname=None):
            seen["server_hostname"] = server_hostname
            return sock

        conn = api_server._PinnedHTTPSConnection("raw.githubusercontent.com", "185.199.108.133")
        with patch.object(api_server.socket, "create_connection", return_value=object()),              patch.object(conn._context, "wrap_socket", side_effect=fake_wrap):
            conn.connect()

        self.assertEqual("raw.githubusercontent.com", seen["server_hostname"])


if __name__ == "__main__":
    unittest.main()
