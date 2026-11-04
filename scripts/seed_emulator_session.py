#!/usr/bin/env python3
"""Seed an existing session into a debug emulator for a temporary read-only smoke test.

Reads secrets from the user's capture into memory and streams them directly to the
app-private runtime file. Never writes credentials to the repository or APK.
The instrumentation test must consume and delete the runtime file immediately.
"""
import argparse
import json
from pathlib import Path
import shlex
import subprocess
import sys
from urllib.parse import parse_qs, urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from inspect_capture import body, read_flows


def extract_session(capture: Path) -> dict:
    flows = list(read_flows(capture))
    main = None
    device = None
    for flow in flows:
        request = flow.get("request") or {}
        response = flow.get("response") or {}
        if (request.get("host") == "api.is74.ru"
                and urlsplit(request.get("path", "")).path == "/mobile/auth/get-token"
                and request.get("method") == "POST" and response.get("status_code") == 200):
            candidate = json.loads(body(response))
            if isinstance(candidate, dict) and candidate.get("TOKEN"):
                main = candidate
                form = parse_qs(body(request))
                device = form.get("uniqueDeviceId", [None])[0] or candidate.get("UNIQUE_DEVICE_ID")
    if not main or not device:
        raise ValueError("No complete main session")
    td_token = None
    for flow in flows:
        request = flow.get("request") or {}
        response = flow.get("response") or {}
        if (request.get("host") == "td-crm.is74.ru"
                and urlsplit(request.get("path", "")).path == "/api/auth-lk"
                and request.get("method") == "POST" and response.get("status_code") == 200):
            if parse_qs(body(request)).get("token", [None])[0] == main["TOKEN"]:
                td_token = json.loads(body(response)).get("TOKEN")
    if not td_token or main.get("USER_ID") is None or main.get("PROFILE_ID") is None:
        raise ValueError("No complete paired session")
    return {
        "token": str(main["TOKEN"]),
        "userId": str(main["USER_ID"]),
        "profileId": str(main["PROFILE_ID"]),
        "deviceId": str(device),
        "intercomToken": str(td_token),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture", type=Path)
    parser.add_argument("--device", default="emulator-5560")
    args = parser.parse_args()
    if not args.device.startswith("emulator-"):
        raise SystemExit("Only a local Android emulator is allowed.")
    try:
        session = extract_session(args.capture)
        payload = json.dumps(session, ensure_ascii=True, separators=(",", ":")).encode("utf-8")
        # adb shell performs shell parsing remotely. Quote the entire sh -c body.
        remote = "run-as ru.is74.door.custom sh -c " + shlex.quote(
            "umask 077; mkdir -p files && cat > files/smoke-session.json"
        )
        result = subprocess.run(
            ["adb", "-s", args.device, "shell", remote],
            input=payload, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            timeout=20, check=False,
        )
        if result.returncode != 0:
            raise RuntimeError("Emulator write failed")
        print("Session seeded for the temporary emulator smoke test.")
    except Exception:
        # Do not expose raw exceptions, adb output, HTTP bodies or credentials.
        raise SystemExit("Could not seed the emulator session. Check the capture, installed debug app and emulator.") from None


if __name__ == "__main__":
    main()
