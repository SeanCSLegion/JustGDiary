#!/usr/bin/env python3
"""Verify the installable release APK with Android SDK build tools."""

from __future__ import annotations

import os
import re
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APPLICATION_ID = "com.cov.justdiary"


def sdk_root() -> Path:
    candidates = [os.environ.get("ANDROID_SDK_ROOT"), os.environ.get("ANDROID_HOME")]
    local_properties = ROOT / "local.properties"
    if local_properties.exists():
        for line in local_properties.read_text(encoding="utf-8").splitlines():
            if line.startswith("sdk.dir="):
                candidates.append(line.partition("=")[2].replace("\\:", ":"))
    for value in candidates:
        if value and Path(value).is_dir():
            return Path(value)
    raise SystemExit("Android SDK not found (set ANDROID_SDK_ROOT or sdk.dir in local.properties)")


def tool(sdk: Path, name: str) -> Path:
    matches = sorted((sdk / "build-tools").glob(f"*/{name}"), reverse=True)
    if not matches:
        raise SystemExit(f"Android SDK build tool not found: {name}")
    return matches[0]


def run(*args: str) -> str:
    result = subprocess.run(args, check=False, capture_output=True, text=True)
    if result.returncode:
        raise SystemExit(result.stderr.strip() or result.stdout.strip() or f"Command failed: {args[0]}")
    return result.stdout + result.stderr


def main() -> int:
    if len(sys.argv) != 2:
        raise SystemExit("Usage: python3 tools/verify_release_apk.py PATH_TO_APK")
    apk = Path(sys.argv[1]).resolve()
    if not apk.is_file() or apk.stat().st_size < 1_000:
        raise SystemExit(f"APK is missing or suspiciously small: {apk}")
    try:
        with zipfile.ZipFile(apk) as archive:
            if archive.testzip() is not None or "AndroidManifest.xml" not in archive.namelist():
                raise SystemExit("APK ZIP integrity or AndroidManifest.xml check failed")
    except zipfile.BadZipFile as error:
        raise SystemExit(f"Invalid APK ZIP archive: {error}") from error

    sdk = sdk_root()
    badging = run(str(tool(sdk, "aapt")), "dump", "badging", str(apk))
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'", badging, re.MULTILINE)
    if not package:
        raise SystemExit("Could not read APK package/version metadata")
    if package.group(1) != APPLICATION_ID:
        raise SystemExit(f"Unexpected applicationId: {package.group(1)}")
    if not package.group(2).isdigit() or int(package.group(2)) < 1:
        raise SystemExit(f"Invalid versionCode: {package.group(2)}")
    if not package.group(3):
        raise SystemExit("Empty versionName")

    signature = run(str(tool(sdk, "apksigner")), "verify", "--verbose", str(apk))
    if "Verifies" not in signature:
        raise SystemExit("APK signature verification did not report success")
    print(f"APK verified: {APPLICATION_ID} {package.group(3)} (versionCode {package.group(2)}), signed, {apk.stat().st_size:,} bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
