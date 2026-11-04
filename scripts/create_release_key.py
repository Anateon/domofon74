#!/usr/bin/env python3
"""Create the local release identity once. Never overwrites an existing key."""
from pathlib import Path
import os
import secrets
import shutil
import subprocess


def main():
    root = Path(__file__).resolve().parents[1]
    directory = root / ".signing"
    keystore = directory / "door74-release.p12"
    password = directory / "password"
    if keystore.exists() or password.exists():
        raise SystemExit("Release identity already exists. Back it up; do not replace it for an update.")
    if not shutil.which("keytool"):
        raise SystemExit("Install JDK 21 and make keytool available in PATH.")
    directory.mkdir(mode=0o700, exist_ok=True)
    directory.chmod(0o700)
    fd = os.open(password, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as target:
        target.write(secrets.token_urlsafe(48) + "\n")
    try:
        subprocess.run([
            "keytool", "-genkeypair", "-noprompt", "-alias", "door74",
            "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
            "-dname", "CN=Door74, O=Door74 Community",
            "-storetype", "PKCS12", "-keystore", str(keystore),
            "-storepass:file", str(password), "-keypass:file", str(password),
        ], check=True)
        keystore.chmod(0o600)
    except BaseException:
        password.unlink(missing_ok=True)
        keystore.unlink(missing_ok=True)
        raise
    print("Release key created in ignored .signing/. Keep an encrypted backup of the whole directory.")


if __name__ == "__main__":
    main()
