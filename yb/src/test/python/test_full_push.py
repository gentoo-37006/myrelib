import argparse
import importlib.util
import io
import json
import subprocess
import time
import unittest
from pathlib import Path
from unittest.mock import DEFAULT, Mock, patch


SCRIPT = Path(__file__).resolve().parents[2] / "launcher/resources/yb.py"
SPEC = importlib.util.spec_from_file_location("yb", SCRIPT)
yb = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(yb)


class FullPushTest(unittest.TestCase):
    def test_full_push_uses_http_without_adb(self):
        with self.push_environment() as calls:
            calls["http_push_full"].return_value = None
            yb.push_command(self.args())

        calls["http_push_full"].assert_called_once_with(Path("TeamCode-debug.apk"))
        calls["find_adb"].assert_not_called()

    def test_full_push_falls_back_when_http_updater_is_unavailable(self):
        with self.push_environment() as calls:
            calls["http_push_full"].return_value = "connection refused"
            yb.push_command(self.args())

        calls["adb_connect"].assert_called_once()
        calls["adb_push"].assert_called_once_with(
            "full", "192.168.43.1:5555", Path("TeamCode-debug.apk"), launch_app=True,
        )

    def test_uninstall_prompt_cancels_upload_without_adb_fallback(self):
        transaction = "b45ea095-ee21-4091-891d-8aa31d787c49"
        message = {
            "namespace": yb.HUB_UPDATER_NAMESPACE,
            "type": "notification",
            "payload": json.dumps({
                "presentationType": "PROMPT",
                "message": "The APK conflicts with the installed app. Uninstall it?",
            }),
        }
        with self.push_environment() as calls, patch.multiple(
            yb, read_websocket_message=Mock(return_value=message), send_updater_message=DEFAULT,
        ) as socket_calls:
            connection = Mock()
            calls["http_push_full"].side_effect = lambda apk: yb.wait_for_apk_update(
                connection, None, transaction, time.monotonic() + 1,
            )
            with self.assertRaisesRegex(yb.FullPushError, "canceled"):
                yb.push_command(self.args())

        self.assertEqual(
            [(call.args[2], call.args[3]) for call in socket_calls["send_updater_message"].call_args_list],
            [("bind", transaction), ("deleteUploadedFile", transaction)],
        )
        calls["find_adb"].assert_not_called()

    def test_adb_signing_conflict_does_not_uninstall_or_retry(self):
        apk = Path("TeamCode-debug.apk")
        result = subprocess.CompletedProcess([], 1, "", "INSTALL_FAILED_UPDATE_INCOMPATIBLE")
        with patch.multiple(
            yb, find_adb=Mock(return_value="adb"), run_cmd=Mock(return_value=result),
            launch_rc_app=DEFAULT, pair_sloth_http=DEFAULT,
        ) as calls:
            with self.assertRaises(SystemExit):
                yb.adb_push("full", "my-hub", apk)
            calls["launch_rc_app"].assert_not_called()
            calls["pair_sloth_http"].assert_not_called()
            yb.run_cmd.assert_called_once_with(
                ["adb", "-s", "my-hub", "install", "-r", str(apk)], check=False, quiet=True,
            )

    def test_waits_for_install_confirmation_after_reconnect(self):
        status = {
            "namespace": yb.HUB_UPDATER_NAMESPACE,
            "type": "notification",
            "payload": json.dumps({"presentationType": "STATUS", "message": "Installing"}),
        }
        success = {
            **status,
            "payload": json.dumps({"presentationType": "SUCCESS", "message": yb.APK_INSTALL_SUCCESS}),
        }

        def frame(message):
            payload = json.dumps(message).encode("utf-8")
            return b"\x81\x7e" + len(payload).to_bytes(2, "big") + payload

        initial_connection = Mock()
        reconnected = Mock()
        initial_socket = Mock()
        initial_socket.__enter__ = Mock(return_value=(initial_connection, io.BytesIO(frame(status))))
        initial_socket.__exit__ = Mock(return_value=False)
        next_socket = Mock()
        next_socket.__enter__ = Mock(return_value=(reconnected, io.BytesIO(frame(success))))
        next_socket.__exit__ = Mock(return_value=False)

        with patch.multiple(
            yb, control_hub_info=Mock(return_value={"appUpdateRequiresReboot": False, "serialNumber": "hub"}),
            hub_update_socket=Mock(side_effect=[initial_socket, next_socket]),
            upload_full_apk=Mock(return_value="b45ea095-ee21-4091-891d-8aa31d787c49"),
            send_updater_message=DEFAULT,
        ) as calls:
            self.assertIsNone(yb.http_push_full(Path("TeamCode-debug.apk")))
            yb.upload_full_apk.assert_called_once()
            calls["send_updater_message"].assert_any_call(
                reconnected, "system", "subscribeToNamespace", yb.HUB_UPDATER_NAMESPACE,
            )

    @staticmethod
    def args():
        return argparse.Namespace(type="full", verbose=False, adb=False, usb=False, no_launch=False)

    @staticmethod
    def push_environment():
        return patch.multiple(
            yb, load_config=Mock(return_value={"ssid": "robot", "password": "password"}),
            get_current_wifi=Mock(return_value="robot"), get_wifi_power=Mock(return_value=True),
            full_push_apk=Mock(return_value=Path("TeamCode-debug.apk")),
            find_java_home=DEFAULT, prepare_gradle_push=DEFAULT, http_push_full=DEFAULT,
            find_adb=DEFAULT, adb_connect=DEFAULT, adb_push=DEFAULT, adb_disconnect=DEFAULT,
        )


if __name__ == "__main__":
    unittest.main()
