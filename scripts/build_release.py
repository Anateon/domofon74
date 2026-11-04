#!/usr/bin/env python3
"""Build, test and verify a signed release without exposing signing passwords."""
from pathlib import Path
import argparse
import hashlib
import os
import re
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, help="Android SDK directory (optional)")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    for name in ("door74-release.p12", "password"):
        if not (root / ".signing" / name).is_file():
            raise SystemExit("Missing local release identity. See docs/BUILD.md.")
    sdk = args.sdk
    if sdk is None:
        value = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        properties = root / "local.properties"
        if not value and properties.is_file():
            value = next((line.split("=", 1)[1].strip().replace("\\ ", " ")
                          for line in properties.read_text().splitlines() if line.startswith("sdk.dir=")), None)
        sdk = Path(value) if value else None
    if sdk is None or not (sdk / "build-tools").is_dir():
        raise SystemExit("Set ANDROID_HOME, sdk.dir in local.properties, or --sdk.")
    versions = [p for p in (sdk / "build-tools").iterdir()
                if re.fullmatch(r"\d+\.\d+\.\d+", p.name) and (p / "apksigner").is_file()]
    if not versions:
        raise SystemExit("Install Android SDK Build Tools.")
    build_tools = max(versions, key=lambda p: tuple(map(int, p.name.split("."))))
    subprocess.run([str(root / "gradlew"), ":app:testDebugUnitTest", ":app:lintRelease", ":app:assembleRelease"], cwd=root, check=True)
    apk = root / "app/build/outputs/apk/release/app-release.apk"
    subprocess.run([str(build_tools / "apksigner"), "verify", "--verbose", str(apk)], check=True)
    manifest = subprocess.check_output([str(build_tools / "aapt"), "dump", "badging", str(apk)], text=True)
    if "application-debuggable" in manifest:
        raise SystemExit("Refusing to distribute a debuggable APK.")
    output = root / "dist"
    output.mkdir(exist_ok=True)
    artifact = output / "Door74.apk"
    shutil.copy2(apk, artifact)
    checksum = hashlib.sha256(artifact.read_bytes()).hexdigest()
    (output / "SHA256SUMS.txt").write_text(f"{checksum}  Door74.apk\n")
    certificate = subprocess.check_output([str(build_tools / "apksigner"), "verify", "--print-certs", str(apk)], text=True)
    (output / "SIGNING-CERTIFICATE.txt").write_text(certificate)
    print("Verified release: dist/Door74.apk, SHA256SUMS.txt and SIGNING-CERTIFICATE.txt")


if __name__ == "__main__":
    main()
