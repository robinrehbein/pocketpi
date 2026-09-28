#!/usr/bin/env python3
"""Reject an unsigned AAB or one signed by a different Play upload key."""

import argparse
import re
import subprocess
import sys
from pathlib import Path

UPLOAD_CERT_SHA256 = "06:0C:E8:05:BB:E7:36:AF:A7:30:F3:DF:F3:05:01:2A:62:2A:88:6A:EE:8E:E1:95:47:2D:87:1B:C9:B7:04:15"


def verify_bundle(bundle: Path):
    if not bundle.is_file() or bundle.stat().st_size == 0:
        raise ValueError("Release AAB is missing or empty")

    verification = subprocess.run(
        ["jarsigner", "-J-Duser.language=en", "-J-Duser.country=US", "-verify", str(bundle)],
        capture_output=True, text=True, check=False,
    )
    output = verification.stdout.lower()
    # Android upload certificates are self-signed, so -strict rejects valid
    # uploads. Require positive verification and reject partially signed jars.
    if (verification.returncode != 0 or "jar verified." not in output
            or "unsigned entries" in output):
        raise ValueError("Release AAB is not fully signed and verified")

    certificate = subprocess.run(
        ["keytool", "-J-Duser.language=en", "-J-Duser.country=US", "-printcert", "-jarfile", str(bundle)],
        capture_output=True, text=True, check=False,
    )
    fingerprints = re.findall(r"(?im)^\s*SHA256:\s*([0-9a-f:]+)\s*$", certificate.stdout)
    if certificate.returncode != 0 or UPLOAD_CERT_SHA256 not in (value.upper() for value in fingerprints):
        raise ValueError("Release AAB signer is not the existing Play upload certificate")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("bundle", type=Path)
    args = parser.parse_args()
    verify_bundle(args.bundle)
    print("Release AAB signature and Play upload certificate verified")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError) as error:
        print(f"PocketPi AAB verification failed: {error}", file=sys.stderr)
        sys.exit(1)
