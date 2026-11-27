#!/usr/bin/env python3

import argparse
import base64
import hashlib
import hmac
import http.client
import json
import os
import re
import secrets
import shlex
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from contextlib import ExitStack, contextmanager
from pathlib import Path
from xml.sax.saxutils import escape as xml_escape

CONFIG_PATH = os.path.expanduser("~/.ybconfig.json")
SLOTH_KEYS_DIR = os.path.expanduser("~/.yb-sloth-keys")
PROJECT_ROOT = os.path.realpath(os.environ.get("YB_PROJECT_ROOT", os.path.dirname(os.path.realpath(__file__))))
ROBOT_IP = "192.168.43.1"
ADB_PORT = "5555"
ADB_CONNECT_ATTEMPTS = 3
# The Robot Controller's own web server (plain HTTP; it has no HTTPS). The app's
# /yb/sloth page takes a sloth payload over Wi-Fi, so a sloth push needs no ADB.
ROBOT_HTTP_PORT = "8080"
SLOTH_UPLOAD_ROUTE = "/yb/sloth"
SLOTH_PAYLOAD = os.path.join("TeamCode", "build", "libs", "to_load.jar")
APK_OUTPUT_DIR = os.path.join("TeamCode", "build", "outputs", "apk", "debug")
HUB_UPDATER_NAMESPACE = "ControlHubUpdater"
APK_INSTALL_SUCCESS = "The APK file installed successfully."
# Right after joining the robot's Wi-Fi its web server can be slow to answer.
PAGE_ATTEMPTS = 3
PAGE_RETRY_PAUSE = 1.0
VERBOSE = False

IS_WINDOWS = sys.platform == "win32"
IS_LINUX = sys.platform.startswith("linux")

# resolved once by find_adb(); adb is frequently not on PATH on Windows
ADB = None

# resolved once by find_java_home(); Android Studio bundles a suitable JDK but
# does not normally expose it to shells as JAVA_HOME.
JAVA_HOME = None

# Keep every macOS Wi-Fi operation on the same discovered adapter.
MACOS_WIFI_INTERFACE = None

RC_PACKAGE = "com.qualcomm.ftcrobotcontroller"

# Failures with a specific, actionable cause. Gradle reports these as a wall of
# ddmlib stack trace with the one useful line buried in the middle of it.
KNOWN_FAILURES = (
    (
        "INSTALL_FAILED_UPDATE_INCOMPATIBLE",
        """The app already on the robot was signed with a different key than this build.

Build with the same signing key as the installed app and try again.""",
    ),
    (
        "no devices/emulators found",
        """adb lost the robot part way through the build.

If the Robot Controller app was not running, the Control Hub's access point is
down and the adb connection went with it. Connect over USB, launch the app, and
retry:
  adb -d shell monkey -p com.qualcomm.ftcrobotcontroller -c android.intent.category.LAUNCHER 1""",
    ),
    (
        "Detected lock file for Sloth loads",
        """A sloth load was already in progress on the robot, or an earlier one was
interrupted and left its lock file behind. Restart the Robot Controller app to
clear it, then push again.""",
    ),
    (
        "available for offline mode",
        """This build needs a dependency that has not been downloaded yet, and the
robot's Wi-Fi has no internet access.

Run the command again from normal internet Wi-Fi. yb prepares the selected
build there before joining the robot.""",
    ),
    (
        "nodename nor servname provided, or not known",
        """Gradle needs an Android build dependency that is not cached yet, but the
current Wi-Fi network has no internet access.

Disconnect from the Control Hub network, reconnect to normal internet Wi-Fi,
and run the push again. yb will prepare the build there before switching back
to the Control Hub.""",
    ),
)

def load_config():
    if not os.path.exists(CONFIG_PATH):
        return None
    try:
        with open(CONFIG_PATH, 'r') as f:
            return json.load(f)
    except Exception as e:
        print(f"Error loading config: {e}")
        return None

def save_config(ssid, password):
    config = {
        "ssid": ssid,
        "password": password
    }
    with open(CONFIG_PATH, 'w') as f:
        json.dump(config, f, indent=4)
    # the wifi password is stored in the clear, so at least keep it off the
    # default 0644 that leaves it readable by anyone else on a shared machine.
    # this is a no-op on Windows, where the file inherits the user profile ACL.
    os.chmod(CONFIG_PATH, 0o600)
    print(f"Saved configuration to {CONFIG_PATH}")

def run_cmd(cmd, check=True, quiet=False):
    if VERBOSE:
        print(f"> {format_command(cmd)}")
    result = subprocess.run(cmd, capture_output=True, text=True)
    if VERBOSE:
        if result.stdout.strip():
            print(f"  stdout: {result.stdout.strip()}")
        if result.stderr.strip():
            print(f"  stderr: {result.stderr.strip()}")
        print(f"  exit code: {result.returncode}")
    if check and result.returncode != 0:
        if VERBOSE:
            print(f"Command failed with code {result.returncode}")
        elif not quiet:
            print(f"Step failed with code {result.returncode}")
        sys.exit(result.returncode)
    return result


def format_command(cmd):
    """Format a verbose command without exposing the configured Wi-Fi password."""
    visible = list(cmd)
    if len(visible) >= 5 and visible[0] == "networksetup" \
            and visible[1] == "-setairportnetwork":
        visible[4] = "********"
    return " ".join(visible)

def run_cmd_live(cmd, env=None):
    # Run a command with live streaming output, collecting it as we go so a
    # failure can be matched against KNOWN_FAILURES afterwards.
    print(f"> {' '.join(cmd)}")
    process = subprocess.Popen(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        universal_newlines=True,
        env=env,
    )
    collected = []
    # pyre-ignore[16]: process.stdout is guaranteed to not be None here because stdout=subprocess.PIPE
    for line in iter(process.stdout.readline, ''):
        print(line, end='')
        collected.append(line)
    # pyre-ignore[16]: process.stdout is guaranteed to not be None here
    process.stdout.close()
    return process.wait(), "".join(collected)


def explain_failure(output):
    """Print advice for a recognised gradle failure. True if one matched."""
    for needle, advice in KNOWN_FAILURES:
        if needle in output:
            print()
            print("-" * 72)
            print(advice)
            print("-" * 72)
            return True
    return False


def launch_rc_app(serial):
    """
    An ADB install stops the app, and the Control Hub needs it running for its
    Wi-Fi access point.
    """
    print("Launching the Robot Controller app...")
    result = run_cmd(
        [find_adb(), "-s", serial, "shell", "monkey", "-p", RC_PACKAGE,
         "-c", "android.intent.category.LAUNCHER", "1"],
        check=False,
        quiet=True,
    )
    if result.returncode != 0 or "No activities found" in result.stdout:
        print("Error: could not launch the Robot Controller app.")
        print("The hub will have no wifi access point until it is running. Launch it with:")
        print(f"  adb -d shell monkey -p {RC_PACKAGE} -c android.intent.category.LAUNCHER 1")
        return False
    print("Robot Controller app launched.")
    return True

def sdk_dir_from_local_properties():
    path = os.path.join(os.getcwd(), "local.properties")
    if not os.path.exists(path):
        return None
    try:
        with open(path, 'r') as f:
            for line in f:
                line = line.strip()
                if not line.startswith("sdk.dir="):
                    continue
                # java .properties escaping: sdk.dir=C\:\\Users\\me\\Sdk
                value = line.split("=", 1)[1]
                return value.replace("\\:", ":").replace("\\\\", "\\")
    except Exception:
        return None
    return None

def find_adb():
    # Android Studio does not put platform-tools on PATH, which is the norm on
    # Windows, so fall back to the SDK location this project already builds with.
    global ADB
    if ADB:
        return ADB
    found = shutil.which("adb")
    if found:
        ADB = found
        return ADB
    exe = "adb.exe" if IS_WINDOWS else "adb"
    roots = [
        os.environ.get("ANDROID_HOME"),
        os.environ.get("ANDROID_SDK_ROOT"),
        sdk_dir_from_local_properties(),
    ]
    for root in roots:
        if not root:
            continue
        candidate = os.path.join(root, "platform-tools", exe)
        if os.path.exists(candidate):
            ADB = candidate
            return ADB
    print("Error: could not find adb on PATH, in ANDROID_HOME or ANDROID_SDK_ROOT,")
    print("or under the sdk.dir from local.properties.")
    print("Add the Android SDK's platform-tools directory to your PATH and retry.")
    sys.exit(1)


def jdk_major_version(java_home):
    """Return the JDK's major version from its standard release file."""
    try:
        with open(os.path.join(java_home, "release"), "r") as release:
            for line in release:
                if not line.startswith("JAVA_VERSION="):
                    continue
                version = line.split("=", 1)[1].strip().strip('"')
                match = re.match(r"(?:1\.)?(\d+)", version)
                return int(match.group(1)) if match else None
    except (OSError, ValueError):
        return None
    return None


def find_java_home():
    """Find a JDK capable of running this project's Gradle build."""
    global JAVA_HOME
    if JAVA_HOME:
        return JAVA_HOME

    executable = "java.exe" if IS_WINDOWS else "java"
    path_java = shutil.which("java")
    candidates = [
        os.environ.get("JAVA_HOME"),
        os.environ.get("STUDIO_JDK"),
        os.path.dirname(os.path.dirname(path_java)) if path_java else None,
    ]

    if sys.platform == "darwin":
        candidates.extend([
            "/Applications/Android Studio.app/Contents/jbr/Contents/Home",
            os.path.expanduser("~/Applications/Android Studio.app/Contents/jbr/Contents/Home"),
        ])
    elif IS_WINDOWS:
        candidates.extend([
            os.path.join(os.environ.get("ProgramFiles", "C:\\Program Files"),
                         "Android", "Android Studio", "jbr"),
            os.path.join(os.environ.get("LOCALAPPDATA", ""),
                         "Programs", "Android Studio", "jbr"),
        ])
    elif IS_LINUX:
        candidates.extend([
            "/opt/android-studio/jbr",
            os.path.expanduser("~/android-studio/jbr"),
        ])

    checked = set()
    for candidate in candidates:
        if not candidate:
            continue
        candidate = os.path.abspath(candidate)
        if candidate in checked:
            continue
        checked.add(candidate)
        java = os.path.join(candidate, "bin", executable)
        if os.path.isfile(java) and (jdk_major_version(candidate) or 0) >= 17:
            JAVA_HOME = candidate
            if VERBOSE and candidate != os.environ.get("JAVA_HOME"):
                print(f"Using JDK from {candidate}")
            return JAVA_HOME

    print("Error: could not find a compatible JDK (version 17 or newer).")
    print("Install Android Studio or JDK 17, set JAVA_HOME, and retry.")
    sys.exit(1)

# `netsh wlan connect` cannot take a passphrase, so credentials have to go
# through a profile. connectionMode is manual so Windows never auto-rejoins the
# Control Hub later, which is what the macOS branch gets by removing the
# preferred network after a push. The Control Hub access point is WPA2-PSK/AES.
WINDOWS_WLAN_PROFILE = """<?xml version="1.0"?>
<WLANProfile xmlns="http://www.microsoft.com/networking/WLAN/profile/v1">
    <name>{ssid}</name>
    <SSIDConfig>
        <SSID>
            <name>{ssid}</name>
        </SSID>
    </SSIDConfig>
    <connectionType>ESS</connectionType>
    <connectionMode>manual</connectionMode>
    <MSM>
        <security>
            <authEncryption>
                <authentication>WPA2PSK</authentication>
                <encryption>AES</encryption>
                <useOneX>false</useOneX>
            </authEncryption>
            <sharedKey>
                <keyType>passPhrase</keyType>
                <protected>false</protected>
                <keyMaterial>{password}</keyMaterial>
            </sharedKey>
        </security>
    </MSM>
</WLANProfile>
"""

def netsh_wlan_interfaces():
    # Parse `netsh wlan show interfaces` into one dict per wireless adapter.
    # netsh output is localized, so only an English Windows yields the "state"
    # and "ssid" keys; elsewhere this returns entries without them and the
    # callers fall back to their "SSID cannot be read" paths.
    result = run_cmd(["netsh", "wlan", "show", "interfaces"], check=False, quiet=True)
    if result.returncode != 0:
        return []
    interfaces = []
    current = None
    for line in result.stdout.splitlines():
        if ":" not in line:
            continue
        key, value = line.split(":", 1)
        key = key.strip().lower()
        value = value.strip()
        if key == "name":
            current = {}
            interfaces.append(current)
        if current is not None:
            current[key] = value
    return interfaces

def find_macos_wifi_interface():
    global MACOS_WIFI_INTERFACE
    if MACOS_WIFI_INTERFACE is not None:
        return MACOS_WIFI_INTERFACE

    result = run_cmd(["networksetup", "-listallhardwareports"], check=False, quiet=True)
    if result.returncode != 0:
        print("Error: Could not list macOS network hardware. Run 'networksetup -listallhardwareports' to check Wi-Fi.")
        sys.exit(1)

    # en0 can be Ethernet. Match the hardware port, not a user-renamed service.
    wifi_port = False
    for line in result.stdout.splitlines():
        key, separator, value = line.partition(":")
        if not separator:
            continue
        key, value = key.strip(), value.strip()
        if key == "Hardware Port":
            wifi_port = value in ("Wi-Fi", "AirPort")
        elif wifi_port and key == "Device" and value:
            MACOS_WIFI_INTERFACE = value
            return value

    print("Error: No Wi-Fi interface found in 'networksetup -listallhardwareports'.")
    sys.exit(1)


def get_current_wifi():
    if IS_LINUX:
        result = run_cmd(["nmcli", "-t", "-f", "active,ssid", "dev", "wifi"], check=False, quiet=True)
        if result.returncode == 0:
            for line in result.stdout.splitlines():
                if line.startswith("yes:"):
                    # Terse nmcli output escapes ':' and '\'. Decode in one
                    # pass so a literal backslash before a colon is preserved.
                    return re.sub(r"\\([:\\])", r"\1", line.split(":", 1)[1])
        return None
    elif IS_WINDOWS:
        for interface in netsh_wlan_interfaces():
            if interface.get("state", "").lower() == "connected" and interface.get("ssid"):
                return interface["ssid"]
        return None
    else:
        # macOS specific command to get current Wi-Fi SSID
        result = run_cmd(["networksetup", "-getairportnetwork", find_macos_wifi_interface()],
                         check=False, quiet=True)
        if result.returncode == 0 and "Current Wi-Fi Network:" in result.stdout:
            ssid = result.stdout.split("Current Wi-Fi Network:")[1].strip()
            if ssid != "<redacted>":
                return ssid
        return None

def get_wifi_power():
    if IS_LINUX:
        result = run_cmd(["nmcli", "radio", "wifi"], check=False, quiet=True)
        if result.returncode == 0 and "enabled" in result.stdout.lower():
            return True
        return False
    elif IS_WINDOWS:
        interfaces = netsh_wlan_interfaces()
        if not interfaces:
            return False
        off_states = ("hardware off", "software off", "not ready")
        return any(i.get("state", "").lower() not in off_states for i in interfaces)
    else:
        result = run_cmd(["networksetup", "-getairportpower", find_macos_wifi_interface()],
                         check=False, quiet=True)
        if result.returncode == 0 and "On" in result.stdout:
            return True
        return False

def windows_connect_wifi(ssid, password):
    profile = WINDOWS_WLAN_PROFILE.format(
        ssid=xml_escape(ssid), password=xml_escape(password))
    handle, path = tempfile.mkstemp(prefix="yb-wlan-", suffix=".xml")
    try:
        with os.fdopen(handle, "w", encoding="utf-8") as f:
            f.write(profile)
        result = run_cmd(
            ["netsh", "wlan", "add", "profile", f"filename={path}", "user=current"],
            check=False)
        if result.returncode != 0:
            print(f"Error adding Wi-Fi profile for {ssid}:")
            print(result.stdout.strip() if result.stdout.strip() else "netsh failed silently.")
            sys.exit(1)
    finally:
        # the profile file holds the password in the clear
        os.remove(path)

    result = run_cmd(["netsh", "wlan", "connect", f"name={ssid}", f"ssid={ssid}"], check=False)
    if result.returncode != 0:
        print(f"Error connecting to network {ssid}:")
        print(result.stdout.strip() if result.stdout.strip() else "Failed silently or password incorrect.")
        sys.exit(1)

def ping_robot():
    if IS_WINDOWS:
        res = run_cmd(["ping", "-n", "1", "-w", "1000", ROBOT_IP], check=False)
        # Windows ping exits 0 when a router answers "destination host
        # unreachable" on the robot's behalf, so require a real echo reply.
        return res.returncode == 0 and "ttl=" in res.stdout.lower()
    res = run_cmd(["ping", "-c", "1", "-W", "1", ROBOT_IP], check=False)
    return res.returncode == 0

def connect_wifi(ssid, password):
    print(f"Connecting to Wi-Fi network: {ssid}...")
    if IS_LINUX:
        cmd = ["nmcli", "device", "wifi", "connect", ssid, "--ask"]
        result = subprocess.run(cmd, input=password + "\n", capture_output=True, text=True)
        if VERBOSE:
            print(f"> {format_command(cmd)}")
            if result.stdout.strip():
                print(f"  stdout: {result.stdout.strip()}")
            if result.stderr.strip():
                print(f"  stderr: {result.stderr.strip()}")
            print(f"  exit code: {result.returncode}")
        if result.returncode != 0:
            print(f"Error connecting to network {ssid}:")
            print(result.stdout.strip() if result.stdout.strip() else "Failed silently or password incorrect.")
            sys.exit(1)
    elif IS_WINDOWS:
        windows_connect_wifi(ssid, password)
    else:
        # Connect to a Wi-Fi network with password on macOS
        result = run_cmd(["networksetup", "-setairportnetwork", find_macos_wifi_interface(), ssid, password],
                         check=False)

        if result.returncode != 0 or "Could not find network" in result.stdout or "Failed to join" in result.stdout:
            print(f"Error connecting to network {ssid}:")
            if result.stdout.strip():
                print(result.stdout.strip())
            else:
                print("Failed silently or password incorrect.")
            sys.exit(1)

    # Wait a few seconds for an IP to be assigned
    print("Waiting for network to stabilize...")
    time.sleep(3)

    # Wait until we can ping the robot IP (up to 15 seconds)
    print(f"Waiting for robot IP {ROBOT_IP} to become reachable...")
    for _ in range(15):
        if ping_robot():
            print(f"Successfully reached {ROBOT_IP}!")
            return
        time.sleep(1)

    print(f"Error: Could not reach {ROBOT_IP} after connecting to {ssid}.")
    sys.exit(1)

def adb_is_connected(adb=None):
    adb = adb or find_adb()
    target = f"{ROBOT_IP}:{ADB_PORT}"
    result = run_cmd([adb, "devices"], check=False, quiet=True)
    for line in result.stdout.splitlines():
        fields = line.split()
        if len(fields) >= 2 and fields[0] == target and fields[1] == "device":
            return True
    return False


def adb_connect():
    adb = find_adb()
    if adb_is_connected(adb):
        print("ADB already connected.")
        return

    print("Connecting to ADB...")
    target = f"{ROBOT_IP}:{ADB_PORT}"
    res = None
    for attempt in range(1, ADB_CONNECT_ATTEMPTS + 1):
        res = run_cmd([adb, "connect", target], check=False)
        answer = res.stdout.lower()
        if "connected to" in answer or "already connected" in answer:
            print("ADB Connected.")
            # Give adb's device list a moment to reflect the new network transport.
            time.sleep(1)
            return
        if attempt < ADB_CONNECT_ATTEMPTS:
            # adb is flaky across a Wi-Fi switch: a long-running adb service can
            # keep a dead route to the robot, so start it afresh and try again
            print(f"ADB did not connect ({res.stdout.strip()}). Restarting adb and trying again...")
            run_cmd([adb, "disconnect", target], check=False, quiet=True)
            run_cmd([adb, "kill-server"], check=False, quiet=True)
            time.sleep(2)
    print(f"Failed to connect ADB: {res.stdout}")
    reason = probe_robot_port(ADB_PORT)
    answers = probe_robot_adb(ADB_PORT) if reason is None else None
    print(explain_adb_failure(reason, answers))
    sys.exit(1)


def probe_robot_port(port, timeout=3):
    """Why this computer cannot open a TCP connection to the robot, or None if it can."""
    try:
        with socket.create_connection((ROBOT_IP, int(port)), timeout=timeout):
            return None
    except ConnectionRefusedError:
        return "refused"
    except OSError as error:
        if isinstance(error, socket.timeout):
            return "timed out"
        return error.strerror or str(error)


def adb_hello():
    """The first packet of the ADB protocol (CNXN), which a live adbd answers at once."""
    payload = b"host::\x00"
    command = 0x4E584E43  # "CNXN"
    return struct.pack("<6I", command, 0x01000001, 256 * 1024, len(payload),
                       sum(payload) & 0xFFFFFFFF, command ^ 0xFFFFFFFF) + payload


def probe_robot_adb(port, timeout=3):
    """Whether the robot's adbd answers an ADB hello. A stuck adbd still accepts the TCP
    connection (the kernel does that for it) but never replies."""
    try:
        with socket.create_connection((ROBOT_IP, int(port)), timeout=timeout) as link:
            link.settimeout(timeout)
            link.sendall(adb_hello())
            reply = link.recv(24)
    except OSError:
        return False
    return len(reply) >= 4 and reply[:4] in (b"CNXN", b"AUTH", b"STLS")


def explain_adb_failure(reason, adb_answers=None):
    """What to do about an adb that will not connect, from what a direct connection finds."""
    if reason is None and adb_answers is False:
        return ("The robot accepts the connection but its ADB does not answer, so the robot's ADB is\n"
                "stuck. Turn the robot off and on, wait for its Wi-Fi to come back, then run the\n"
                "push again.")
    if reason is None:
        return ("The robot's ADB answers this computer directly, so the adb service on this computer\n"
                "is what fails. Quit Android Studio (it keeps its own adb service running), then run\n"
                "the push again.")
    if reason == "refused":
        return ("The robot is on the network but its ADB port is closed. Turn the robot off and on,\n"
                "wait for its Wi-Fi to come back, then run the push again.")
    return (f"This computer cannot open a connection to the robot ({reason}) although ping works.\n"
            "On macOS, open System Settings > Privacy & Security > Local Network and allow the app\n"
            "this terminal runs in, then run the push again.")

def sloth_upload_url():
    return f"http://{ROBOT_IP}:{ROBOT_HTTP_PORT}{SLOTH_UPLOAD_ROUTE}"


def robot_http_opener():
    # never through a system proxy or VPN: the robot is on its own Wi-Fi
    return urllib.request.build_opener(urllib.request.ProxyHandler({}))


# Why the last robot_upload_page() got no answer at all, or None if the robot answered.
LAST_PAGE_PROBLEM = None


def network_problem(error):
    """A short description of why a request to the robot got no answer."""
    reason = getattr(error, "reason", error)
    if isinstance(reason, ConnectionRefusedError):
        return "connection refused"
    if isinstance(reason, (socket.timeout, TimeoutError)):
        return "timed out"
    return getattr(reason, "strerror", None) or str(reason) or reason.__class__.__name__


def robot_upload_page(timeout=4):
    """What the robot app's yb page says about itself, or None when there is no answer.

    None has two meanings, and LAST_PAGE_PROBLEM tells them apart: the robot's web server
    answered without the page (an app from before the page; LAST_PAGE_PROBLEM is None), or
    nothing answered at all (LAST_PAGE_PROBLEM says why). A slow answer is tried again."""
    global LAST_PAGE_PROBLEM
    LAST_PAGE_PROBLEM = None
    for attempt in range(1, PAGE_ATTEMPTS + 1):
        try:
            with robot_http_opener().open(sloth_upload_url(), timeout=timeout) as reply:
                answer = json.loads(reply.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            # the web server is there and has no such page
            error.close()
            return None
        except ValueError:
            return None
        except OSError as error:
            LAST_PAGE_PROBLEM = network_problem(error)
            if VERBOSE:
                print(f"  {sloth_upload_url()}: {LAST_PAGE_PROBLEM}")
            refused = isinstance(getattr(error, "reason", error), ConnectionRefusedError)
            if attempt < PAGE_ATTEMPTS and not refused:
                time.sleep(PAGE_RETRY_PAUSE)
                continue
            return None
        LAST_PAGE_PROBLEM = None
        if isinstance(answer, dict) and answer.get("endpoint") == "yb sloth upload":
            return answer
        return None
    return None


def pair_sloth_http(serial):
    """Provision or retrieve the hub's app-private key over the authorized ADB connection."""
    script = (
        "umask 077; mkdir -p files || exit 1; key=files/yb-sloth.key; "
        'if [ ! -s "$key" ]; then '
        'tmp=$(mktemp files/.yb-sloth.XXXXXX) || exit 1; '
        'if cat >"$tmp"; then ln "$tmp" "$key" 2>/dev/null; fi; '
        'rm -f "$tmp"; fi; cat "$key"'
    )
    try:
        result = subprocess.run(
            [find_adb(), "-s", serial, "shell", "-T", f"run-as {RC_PACKAGE} sh -c {shlex.quote(script)}"],
            input=secrets.token_hex(32) + "\n", capture_output=True, text=True, timeout=10,
        )
        key = result.stdout.strip()
        if result.returncode != 0 or not re.fullmatch(r"[0-9a-f]{64}", key):
            print("Could not pair HTTP hot loads. Sloth can still use ADB.")
            return

        os.makedirs(SLOTH_KEYS_DIR, mode=0o700, exist_ok=True)
        key_id = hashlib.sha256(key.encode("ascii")).hexdigest()
        handle, path = tempfile.mkstemp(dir=SLOTH_KEYS_DIR)
        try:
            with os.fdopen(handle, "w", encoding="ascii") as target:
                target.write(key)
            os.replace(path, os.path.join(SLOTH_KEYS_DIR, key_id))
        finally:
            if os.path.exists(path):
                os.unlink(path)
    except (OSError, subprocess.TimeoutExpired):
        print("Could not pair HTTP hot loads. Sloth can still use ADB.")
        return
    print("Paired HTTP hot loads with this Control Hub.")


def http_push_sloth(page, timeout=30):
    """Send the built Sloth payload with a one-time upload signature."""
    key_id = page.get("keyId")
    challenge = page.get("challenge")
    if (page.get("version") != 3 or not isinstance(key_id, str)
            or not re.fullmatch(r"[0-9a-f]{64}", key_id)
            or not isinstance(challenge, str) or not re.fullmatch(r"[0-9a-f-]{36}", challenge)):
        return "the robot app does not support paired HTTP hot loads"
    try:
        with open(os.path.join(SLOTH_KEYS_DIR, key_id), "rb") as source:
            key = source.read(65)
    except OSError:
        return "this computer is not paired with the hub yet; run 'yb push full --adb' once"
    if hashlib.sha256(key).hexdigest() != key_id:
        return "the saved pairing key is invalid; run 'yb push full --adb' to pair again"
    try:
        with open(SLOTH_PAYLOAD, "rb") as source:
            payload = source.read()
    except OSError as error:
        return f"could not read {SLOTH_PAYLOAD} ({error.strerror or error})"
    digest = hashlib.sha256(payload).hexdigest()
    message = f"yb-sloth-v3\n{challenge}\n{digest}".encode("ascii")
    signature = hmac.new(key, message, hashlib.sha256).hexdigest()
    request = urllib.request.Request(
        f"{sloth_upload_url()}?sha256={digest}",
        data=payload,
        method="PUT",
        headers={"Content-Type": "application/java-archive", "X-Yb-Signature": signature},
    )
    try:
        with robot_http_opener().open(request, timeout=timeout) as reply:
            answer = json.loads(reply.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        try:
            reason = json.loads(error.read().decode("utf-8")).get("error")
        except (OSError, ValueError, AttributeError):
            reason = None
        return reason or f"the robot answered HTTP {error.code}"
    except (OSError, ValueError) as error:
        return str(error)
    if isinstance(answer, dict) and answer.get("ok") is True and answer.get("sha256") == digest:
        return None
    if isinstance(answer, dict) and answer.get("error"):
        return answer["error"]
    return "the robot's answer was not understood"


class FullPushError(Exception):
    """A full push cannot proceed safely."""


def full_push_apk():
    directory = Path(APK_OUTPUT_DIR)
    try:
        metadata = json.loads((directory / "output-metadata.json").read_text(encoding="utf-8"))
        if metadata["applicationId"] != RC_PACKAGE or metadata["variantName"] != "debug":
            raise ValueError("not a Robot Controller debug build")
        outputs = [item["outputFile"] for item in metadata["elements"] if not item["filters"]]
        if len(outputs) != 1:
            raise ValueError("expected one standalone APK")
        filename = outputs[0]
        if filename != Path(filename).name or not filename.endswith(".apk"):
            raise ValueError("invalid APK filename")
        apk = directory / filename
        if not apk.is_file():
            raise ValueError(f"{filename} is missing")
        return apk
    except (OSError, ValueError, AttributeError, KeyError, TypeError) as error:
        raise FullPushError(f"Could not find the built Robot Controller APK ({error}).") from error


def control_hub_info(timeout=4):
    url = f"http://{ROBOT_IP}:{ROBOT_HTTP_PORT}/js/rcInfo.json"
    with robot_http_opener().open(url, timeout=timeout) as reply:
        answer = json.loads(reply.read(65536))
    if not isinstance(answer, dict) or answer.get("isREVControlHub") is not True:
        raise ValueError("the web console does not identify itself as a REV Control Hub")
    return answer


@contextmanager
def hub_update_socket(timeout):
    """Connect to the updater's WebSocket on the port next to its web console."""
    port = int(ROBOT_HTTP_PORT) + 1
    key = base64.b64encode(os.urandom(16)).decode("ascii")
    accept = base64.b64encode(hashlib.sha1(
        (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode("ascii")
    ).digest()).decode("ascii")
    with socket.create_connection((ROBOT_IP, port), timeout) as connection, connection.makefile("rb") as source:
        headers = (
            f"GET / HTTP/1.1\r\nHost: {ROBOT_IP}:{port}\r\n"
            "Upgrade: websocket\r\nConnection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n"
        )
        connection.sendall(headers.encode("ascii"))
        status = source.readline(4096).split()
        if status[1:2] != [b"101"] or http.client.parse_headers(source).get("Sec-WebSocket-Accept") != accept:
            raise ValueError("the updater's WebSocket handshake failed")
        yield connection, source


def send_websocket_frame(connection, opcode, payload):
    # YB only sends small control messages, which fit a single short frame.
    mask = os.urandom(4)
    masked = bytes(byte ^ mask[index % 4] for index, byte in enumerate(payload))
    connection.sendall(bytes((0x80 | opcode, 0x80 | len(payload))) + mask + masked)


def send_updater_message(connection, namespace, message_type, payload):
    message = json.dumps(
        {"namespace": namespace, "type": message_type, "payload": payload}, separators=(",", ":"),
    ).encode("utf-8")
    send_websocket_frame(connection, 1, message)


def read_websocket_message(connection, source, deadline):
    def read_exact(size):
        connection.settimeout(max(0.001, deadline - time.monotonic()))
        data = source.read(size)
        if len(data) != size:
            raise EOFError("the updater disconnected")
        return data

    message = bytearray()
    fragmented = False
    while time.monotonic() < deadline:
        flags, size = read_exact(2)
        opcode = flags & 0x0F
        final = bool(flags & 0x80)
        if flags & 0x70 or size & 0x80:
            raise ValueError("unexpected WebSocket frame")
        if size >= 126:
            size = int.from_bytes(read_exact(2 if size == 126 else 8), "big")
        if opcode >= 8 and (not final or size > 125):
            raise ValueError("invalid WebSocket control frame")
        if len(message) + size > 65536:
            raise ValueError("updater message is too large")
        payload = read_exact(size)
        if opcode == 8:
            raise EOFError("the updater disconnected")
        if opcode == 9:
            send_websocket_frame(connection, 10, payload)
        elif opcode != 10:
            if opcode != (0 if fragmented else 1):
                raise ValueError("unexpected WebSocket message type")
            message.extend(payload)
            if final:
                return json.loads(message)
            fragmented = True
    raise TimeoutError("timed out waiting for the updater")


def upload_full_apk(apk):
    boundary = secrets.token_hex(16)
    # Use a fresh safe filename for each upload, so no overwrite flag is needed.
    filename = f"yb-{secrets.token_hex(8)}.apk"
    prefix = (
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{filename}\"\r\n"
        "Content-Type: application/vnd.android.package-archive\r\n\r\n"
    ).encode("ascii")
    suffix = f"\r\n--{boundary}--\r\n".encode("ascii")
    connection = http.client.HTTPConnection(ROBOT_IP, int(ROBOT_HTTP_PORT), timeout=90)
    try:
        with apk.open("rb") as source:
            def body():
                yield prefix
                yield from iter(lambda: source.read(65536), b"")
                yield suffix

            connection.request("POST", "/updateControlHubAPK", body(), headers={
                "Content-Type": f"multipart/form-data; boundary={boundary}",
                "Content-Length": str(len(prefix) + os.fstat(source.fileno()).st_size + len(suffix)),
            })
            with connection.getresponse() as reply:
                if reply.status != 200:
                    raise FullPushError(f"The hub refused the APK upload (HTTP {reply.status}).")
                answer = json.loads(reply.read(65536))
    except (OSError, http.client.HTTPException, ValueError) as error:
        raise FullPushError("The hub did not confirm the APK upload. Check its Manage page before retrying.") from error
    finally:
        connection.close()
    transaction = answer.get("uploadIdentifier") if isinstance(answer, dict) else None
    if not isinstance(transaction, str) or not re.fullmatch(
            r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", transaction):
        raise FullPushError("The hub did not send an update ID. Check its Manage page before retrying.")
    return transaction


def wait_for_apk_update(connection, source, transaction, deadline, reconnected=False):
    send_updater_message(connection, HUB_UPDATER_NAMESPACE, "bind", transaction)
    if reconnected:
        # The old app can lose its transaction when replaced. The updater then
        # reports the result as a broadcast from the relaunched app.
        send_updater_message(connection, "system", "subscribeToNamespace", HUB_UPDATER_NAMESPACE)
    while time.monotonic() < deadline:
        message = read_websocket_message(connection, source, deadline)
        if not isinstance(message, dict) or (
                message.get("namespace"), message.get("type")) != (HUB_UPDATER_NAMESPACE, "notification"):
            continue
        payload = json.loads(message["payload"])
        kind, text = payload["presentationType"], payload["message"]
        if kind == "PROMPT":
            # The only reply to a prompt is the web console's cancel action.
            try:
                send_updater_message(connection, HUB_UPDATER_NAMESPACE, "deleteUploadedFile", transaction)
            except OSError:
                pass
            raise FullPushError("The hub requested confirmation to replace the app. "
                                "Full Push was canceled. Use the installed app's signing key.")
        if kind == "ERROR":
            raise FullPushError(f"The hub rejected the APK: {text}")
        if kind == "SUCCESS" and text == APK_INSTALL_SUCCESS:
            return
        if VERBOSE:
            print(f"  Hub updater: {text}")
    raise TimeoutError("timed out waiting for the updater")


def http_push_full(apk):
    """Return an ADB fallback reason only if an upload has not begun."""
    with ExitStack() as stack:
        try:
            info = control_hub_info()
            if info.get("appUpdateRequiresReboot") is not False:
                return "this Control Hub does not have the supported web updater"
            connection, source = stack.enter_context(hub_update_socket(timeout=4))
        except (OSError, http.client.HTTPException, ValueError) as error:
            return network_problem(error)

        print("Sending the Robot Controller APK over Wi-Fi (HTTP)...")
        transaction = upload_full_apk(apk)
        print("APK uploaded. Waiting for the Control Hub to finish installing it...")
        deadline = time.monotonic() + 120
        try:
            wait_for_apk_update(connection, source, transaction, deadline)
            return None
        except (OSError, EOFError):
            pass
        except (KeyError, TypeError, ValueError) as error:
            raise FullPushError("The hub returned an unknown update result. Check its Manage page.") from error

    while time.monotonic() < deadline:
        try:
            current = control_hub_info(timeout=3)
            if current.get("serialNumber") != info.get("serialNumber"):
                raise FullPushError("The reconnected hub is different. Check the original hub's Manage page.")
            with hub_update_socket(timeout=3) as (connection, source):
                wait_for_apk_update(connection, source, transaction, deadline, reconnected=True)
                return None
        except (OSError, EOFError, http.client.HTTPException):
            time.sleep(1)
        except (KeyError, TypeError, ValueError) as error:
            raise FullPushError("The hub returned an unknown update result. Check its Manage page.") from error
    raise FullPushError("The hub did not confirm installation. Check its Manage page before retrying.")


def adb_disconnect():
    print("Disconnecting ADB...")
    run_cmd([find_adb(), "disconnect", f"{ROBOT_IP}:{ADB_PORT}"], check=False)
    print("ADB Disconnected.")

def gradlew_path():
    # the wrapper shell script is not executable by CreateProcess on Windows
    path = os.path.abspath("gradlew.bat") if IS_WINDOWS else "./gradlew"
    if not os.path.exists(path):
        print(f"Error: Could not find {os.path.basename(path)} in the current directory. Are you in an Android Studio project root?")
        sys.exit(1)
    return path


def gradle_environment():
    environment = os.environ.copy()
    environment["JAVA_HOME"] = find_java_home()
    return environment


def run_gradle_task(task, offline=False, rerun=False):
    command = [gradlew_path()]
    if offline:
        command.append("--offline")
    if rerun:
        command.append("--rerun-tasks")
    command.append(task)
    environment = gradle_environment()
    if VERBOSE:
        code, output = run_cmd_live(command, env=environment)
    else:
        result = subprocess.run(
            command,
            capture_output=True,
            text=True,
            env=environment,
        )
        code = result.returncode
        output = result.stdout + result.stderr
    if code != 0:
        if not explain_failure(output):
            print(f"Gradle task {task} failed with code {code}.")
            if not VERBOSE:
                print("Run the same command with -v to see Gradle's output.")
        sys.exit(code)


def prepare_gradle_push(deploy_type, force_rebuild=False):
    """Build and download dependencies before joining the robot's Wi-Fi."""
    task = "assembleSloth" if deploy_type == "sloth" else ":TeamCode:assembleDebug"
    if deploy_type == "sloth" and force_rebuild:
        print("Rebuilding sloth payload from current sources...")
        run_gradle_task(task, rerun=True)
    else:
        print(f"Preparing {deploy_type} build while internet is available...")
        run_gradle_task(task)


def adb_push(deploy_type, serial, apk, launch_app=True):
    print(f"Starting {deploy_type} deployment...")
    if deploy_type == "sloth":
        run_gradle_task("deploySloth", offline=True)
    else:
        # Android rejects a signing mismatch with -r and keeps the installed app.
        result = run_cmd([find_adb(), "-s", serial, "install", "-r", str(apk)], check=False, quiet=True)
        if result.returncode != 0:
            output = (result.stdout + result.stderr).strip()
            if not explain_failure(output):
                print(output or "ADB could not install the APK.")
            sys.exit(result.returncode)

        # Restore the app's Wi-Fi access point before attempting to pair.
        if launch_app and not launch_rc_app(serial):
            sys.exit(1)
        pair_sloth_http(serial)

    print(f"{deploy_type.capitalize()} push completed successfully.")


def restore_wifi(original_wifi, original_power, robot_ssid):
    """Put the machine back on the network it was on before the robot."""
    if original_wifi and original_wifi != robot_ssid:
        print(f"Restoring Wi-Fi connection to {original_wifi}...")
        # Note: We assume the original Wi-Fi is known to the OS, so we don't need a password.
        if IS_LINUX:
            # A saved profile's name need not match its SSID. Let NetworkManager
            # find the saved connection for this network instead of guessing its ID.
            run_cmd(["nmcli", "device", "wifi", "connect", original_wifi], check=False, quiet=True)
        elif IS_WINDOWS:
            run_cmd(["netsh", "wlan", "connect", f"name={original_wifi}", f"ssid={original_wifi}"],
                    check=False, quiet=True)
        else:
            run_cmd(["networksetup", "-setairportnetwork", find_macos_wifi_interface(), original_wifi],
                    check=False, quiet=True)
        print(f"Switched back to {original_wifi}.")
    elif not original_wifi and not original_power:
        print("Disconnecting from Robot Wi-Fi (turning Wi-Fi off)...")
        if IS_LINUX:
            run_cmd(["nmcli", "radio", "wifi", "off"], check=False, quiet=True)
            print("Switched off Wi-Fi.")
        elif IS_WINDOWS:
            # turning the radio off needs an elevated `netsh interface set
            # interface`, so disconnect instead and say what was left alone
            run_cmd(["netsh", "wlan", "disconnect"], check=False, quiet=True)
            print("Disconnected from the Control Hub. Windows Wi-Fi was left on; turn the radio off yourself if you need it off.")
        else:
            run_cmd(["networksetup", "-setairportpower", find_macos_wifi_interface(), "off"],
                    check=False, quiet=True)
            print("Switched off Wi-Fi.")
    elif not original_wifi and original_power:
        print(f"Disconnecting from Control Hub Wi-Fi {robot_ssid} to auto-reconnect...")
        if IS_LINUX:
            run_cmd(["nmcli", "radio", "wifi", "off"], check=False, quiet=True)
            time.sleep(1)
            run_cmd(["nmcli", "radio", "wifi", "on"], check=False, quiet=True)
            print("Wi-Fi cycled. Your system should automatically rejoin your default network.")
        elif IS_WINDOWS:
            # the Control Hub profile is connectionMode=manual, so Windows
            # picks the next preferred network instead of rejoining it
            run_cmd(["netsh", "wlan", "disconnect"], check=False, quiet=True)
            time.sleep(2)
            print(f"Disconnected from {robot_ssid}. Windows should now rejoin your previous network.")
        else:
            # Step 1: Remove robot from preferred networks so macOS won't auto-rejoin it.
            run_cmd(["networksetup", "-removepreferredwirelessnetwork", find_macos_wifi_interface(), robot_ssid],
                    check=False, quiet=True)
            # Step 2: Power cycle Wi-Fi to force the active disconnection.
            # Since the robot SSID is no longer preferred, macOS will rejoin the
            # next known network (your home/school Wi-Fi) instead.
            run_cmd(["networksetup", "-setairportpower", find_macos_wifi_interface(), "off"],
                    check=False, quiet=True)
            time.sleep(1)
            run_cmd(["networksetup", "-setairportpower", find_macos_wifi_interface(), "on"],
                    check=False, quiet=True)
            time.sleep(2)
            # No need to re-add the robot network to preferred list.
            # macOS will automatically save it as a known network the next time
            # connect_wifi explicitly connects with the SSID and password.
            print(f"Disconnected from {robot_ssid}. macOS should now rejoin your previous network.")


def logcat_args(crash=False, dump=False):
    """
    Arguments for adb logcat, without the adb executable itself.

    The default filter is the set that every debugging session actually needed:
    robot status from RobotCore, class loading from Sinister and the sloth
    loader, opmode crashes from AndroidRuntime, and errors from everything else.
    The RC app logs enough at boot that android's chatty throttling evicts
    unfiltered history within minutes, so a tight filter is also what keeps the
    interesting lines alive long enough to read.
    """
    if crash:
        # the crash ring buffer survives chatty and holds only stack traces
        return ["logcat", "-d", "-b", "crash"]
    args = ["logcat"]
    if dump:
        args.append("-d")
    args += [
        "RobotCore:V",
        "Sinister:V",
        "SlothTeamCodeLoader:V",
        "AndroidRuntime:E",
        "*:E",
    ]
    return args


def stream_logcat(args):
    """Runs adb logcat until it exits or the user interrupts it."""
    try:
        result = subprocess.run([find_adb()] + args)
    except KeyboardInterrupt:
        # ctrl-c is the normal way to stop a stream, not an error
        return
    if result.returncode != 0:
        print(f"adb logcat failed with code {result.returncode}.")
        sys.exit(result.returncode)


def log_command(args):
    """
    Join the robot's wifi and read its logcat.

    `yb log` streams live until ctrl-c. `--dump` prints what is buffered and
    exits; `--crash` prints the crash ring buffer, which is the first place to
    look when an opmode dies and the hub starts blinking blue.

    With --usb the log is read over a cable and no Wi-Fi is touched.
    """
    if getattr(args, "usb", False):
        serial = find_usb_robot()
        os.environ["ANDROID_SERIAL"] = serial
        if not args.crash and not args.dump:
            print(f"Streaming robot logs over USB from {serial}. Press ctrl-c to stop.")
        stream_logcat(logcat_args(crash=args.crash, dump=args.dump))
        return

    config = load_config()
    if not config:
        print("Error: No configuration found. Please run 'yb config --ssid <SSID> --password <PASSWORD>' first.")
        sys.exit(1)

    # Find adb before touching the network, as a push does, so a missing
    # executable never moves the machine off its normal Wi-Fi.
    find_adb()

    robot_ssid = config["ssid"]
    original_wifi = get_current_wifi()
    original_power = get_wifi_power()
    already_there = is_on_robot_network(original_wifi, original_power, robot_ssid)

    try:
        if not already_there:
            connect_wifi(robot_ssid, config["password"])
        else:
            print(f"Already on {robot_ssid}.")

        adb_connect()
        if not args.crash and not args.dump:
            print("Streaming robot logs. Press ctrl-c to stop.")
        stream_logcat(logcat_args(crash=args.crash, dump=args.dump))
    finally:
        try:
            adb_disconnect()
        finally:
            if not already_there:
                restore_wifi(original_wifi, original_power, robot_ssid)


def usb_transports(adb=None):
    """(serial, state) for each device adb sees on a USB cable. A network transport is
    listed by address and port, and an emulator by its own prefix; neither is a cable."""
    adb = adb or find_adb()
    result = run_cmd([adb, "devices"], check=False, quiet=True)
    found = []
    for line in result.stdout.splitlines():
        # a device row is "serial<TAB>state"; adb's own notices have no tab
        serial, tab, state = line.partition("\t")
        serial, state = serial.strip(), state.strip()
        if not tab or not serial or not state or ":" in serial or serial.startswith("emulator-"):
            continue
        found.append((serial, state))
    return found


USB_STATE_ADVICE = {
    "unauthorized": "It has not allowed this computer yet. Accept the USB debugging prompt on it, "
                    "or turn the robot off and on.",
    "offline": "adb cannot talk to it. Unplug the cable, plug it back in, and run the push again.",
    "no permissions": "This computer is not allowed to open it. On Linux, add the udev rule for "
                      "Android devices, then plug the cable in again.",
}


def find_usb_robot():
    """The serial of the one robot on a USB cable, or exit saying what is in the way."""
    adb = find_adb()
    found = usb_transports(adb)
    ready = [serial for serial, state in found if state == "device"]
    if len(ready) > 1:
        print(f"Error: adb sees {len(ready)} devices on USB cables ({', '.join(ready)}).")
        print("Unplug every phone and tablet so that only the Control Hub is connected, then run "
              "the push again.")
        sys.exit(1)
    if not ready:
        if found:
            serial, state = found[0]
            print(f"Error: adb sees a device on USB ({serial}) but it is '{state}'.")
            print(next((advice for prefix, advice in USB_STATE_ADVICE.items() if state.startswith(prefix)),
                       "Unplug the cable, plug it back in, and run the push again."))
        else:
            print("Error: adb sees no device on a USB cable.")
            print("Turn the robot on and plug a cable into the Control Hub's USB-C port. Use a "
                  "data cable: a charge-only cable shows nothing.")
        sys.exit(1)
    serial = ready[0]
    # Anything with USB debugging on would be accepted by adb, a teammate's phone on
    # charge included, and a full install would then put the app on it.
    installed = run_cmd([adb, "-s", serial, "shell", "pm", "path", RC_PACKAGE], check=False, quiet=True)
    if "package:" not in installed.stdout:
        print(f"Error: the device on USB ({serial}) has no Robot Controller app, so it does not "
              "look like the robot.")
        print("Unplug it and connect the Control Hub's USB-C port instead.")
        sys.exit(1)
    return serial


def usb_push_command(args):
    """A push over a USB cable: no Wi-Fi is touched, for venues where the robot's Wi-Fi
    is crowded or laptops are asked to stay off it."""
    adb = find_adb()
    find_java_home()
    serial = find_usb_robot()
    print(f"Pushing over USB to {serial}. This computer's Wi-Fi is left alone.")

    # A leftover Wi-Fi transport to the same robot would give Gradle and adb two
    # devices to choose from; the cable is the one asked for.
    run_cmd([adb, "disconnect", f"{ROBOT_IP}:{ADB_PORT}"], check=False, quiet=True)
    os.environ["ANDROID_SERIAL"] = serial

    prepare_gradle_push(args.type, force_rebuild=args.type == "sloth")
    apk = full_push_apk() if args.type == "full" else None
    adb_push(args.type, serial, apk, launch_app=not args.no_launch)


def is_on_robot_network(current_wifi, wifi_power, robot_ssid):
    if current_wifi == robot_ssid:
        return True
    # macOS 14+ may redact the SSID without Location Services permission. In
    # that case, a reachable Control Hub is the best available confirmation.
    return current_wifi is None and wifi_power and ping_robot()


def push_command(args):
    global VERBOSE
    VERBOSE = args.verbose

    if getattr(args, "usb", False):
        usb_push_command(args)
        return

    config = load_config()
    if not config:
        print("Error: No configuration found. Please run 'yb config --ssid <SSID> --password <PASSWORD>' first.")
        sys.exit(1)

    robot_ssid = config['ssid']
    robot_password = config['password']

    deploy_type = args.type
    use_http = not args.adb and not (deploy_type == "full" and args.no_launch)
    if not use_http:
        find_adb()
    find_java_home()

    original_wifi = get_current_wifi()
    original_power = get_wifi_power()
    already_there = is_on_robot_network(original_wifi, original_power, robot_ssid)

    # Sloth's assembly task has not always tracked every payload input. When the
    # machine is already on the robot network, force it to rerun so deploySloth
    # cannot reuse a stale artifact from a previous push.
    prepare_gradle_push(
        deploy_type,
        force_rebuild=already_there and deploy_type == "sloth",
    )
    apk = full_push_apk() if deploy_type == "full" else None

    if already_there:
        print(f"Already on {robot_ssid}. Will stay connected after push.")
    elif original_wifi:
        print(f"Current Wi-Fi is {original_wifi}. Will restore after push.")
    elif original_power:
        if IS_WINDOWS:
            print("Wi-Fi is ON, but the SSID cannot be read. Will disconnect afterwards so Windows rejoins a preferred network.")
        else:
            print("Wi-Fi is ON, but SSID cannot be read (likely macOS 14+ Location Privacy). Will cycle Wi-Fi power to auto-restore afterwards.")
    else:
        print("Wi-Fi is currently OFF. Will turn off after push.")

    used_adb = False
    try:
        if not already_there:
            connect_wifi(robot_ssid, robot_password)

        if deploy_type == "full" and use_http:
            unavailable = http_push_full(apk)
            if unavailable is None:
                print("Full push completed successfully over HTTP.")
                return
            print(f"The HTTP updater is unavailable ({unavailable}). Using ADB...")
        elif deploy_type == "sloth" and use_http:
            page = robot_upload_page()
            if page is not None:
                print("Sending the Sloth payload over Wi-Fi (HTTP)...")
                failure = http_push_sloth(page)
                if failure is None:
                    print("Sloth payload accepted over HTTP.")
                    return
                print(f"The HTTP upload failed: {failure}")
                print("Falling back to ADB...")
            elif LAST_PAGE_PROBLEM:
                print(f"yb could not reach the robot's web page ({LAST_PAGE_PROBLEM}). Using ADB...")
            else:
                print("The robot app has no yb upload page. Using ADB...")

        find_adb()
        used_adb = True
        adb_connect()
        adb_push(deploy_type, f"{ROBOT_IP}:{ADB_PORT}", apk, launch_app=not args.no_launch)
    finally:
        try:
            if used_adb:
                adb_disconnect()
        finally:
            if not already_there:
                restore_wifi(original_wifi, original_power, robot_ssid)


def enter_project_root():
    os.chdir(PROJECT_ROOT)


def main():
    enter_project_root()
    parser = argparse.ArgumentParser(prog="yb", description="YanBridge (yb) - Quick code pushing for FTC Control Hub")
    subparsers = parser.add_subparsers(dest="command", help="Available commands")

    # Config command
    config_parser = subparsers.add_parser("config", help="Set or view Control Hub Wi-Fi credentials")
    config_parser.add_argument("--ssid", help="Control Hub Wi-Fi SSID")
    config_parser.add_argument("--password", help="Control Hub Wi-Fi Password")

    # Log command
    log_parser = subparsers.add_parser("log", help="Read the robot's logcat over wifi (or a cable, with --usb)")
    log_parser.add_argument("--crash", action="store_true",
                            help="Print the crash buffer and exit; the first stop when an opmode dies")
    log_parser.add_argument("--dump", action="store_true",
                            help="Print the buffered filtered log and exit instead of streaming")
    log_parser.add_argument("--usb", action="store_true",
                            help="Read the log over a USB cable plugged into the Control Hub's USB-C port")

    # Push command
    push_parser = subparsers.add_parser("push", help="Push code to the Control Hub")
    push_parser.add_argument("type", nargs="?", choices=["sloth", "full"], default="sloth",
                             help="'sloth' hot-loads code (default), 'full' installs the complete app")
    push_parser.add_argument("-v", "--verbose", action="store_true",
                             help="Enable verbose output for debugging network and command issues")
    push_parser.add_argument("--adb", action="store_true",
                             help="Use ADB for the push instead of trying HTTP")
    push_parser.add_argument("--usb", action="store_true",
                             help="Push over a USB cable plugged into the Control Hub's USB-C port. "
                                  "Wi-Fi is left alone")
    push_parser.add_argument("--no-launch", action="store_true",
                             help="Use ADB for a full push and skip launching the Robot Controller app")

    args = parser.parse_args()

    if args.command == "config":
        if args.ssid and args.password:
            save_config(args.ssid, args.password)
        elif not args.ssid and not args.password:
            config = load_config()
            if config:
                print(f"Current configuration ({CONFIG_PATH}):")
                print(f"  SSID: {config.get('ssid')}")
                print(f"  Password: {config.get('password')}")
            else:
                print("No configuration found.")
        else:
            print("Error: Both --ssid and --password are required to update the configuration.")
            sys.exit(1)

    elif args.command == "log":
        log_command(args)

    elif args.command == "push":
        try:
            push_command(args)
        except FullPushError as error:
            sys.exit(f"Full Push failed: {error}")
    else:
        parser.print_help()

if __name__ == "__main__":
    main()
