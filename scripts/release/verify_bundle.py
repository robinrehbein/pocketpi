#!/usr/bin/env python3
"""Reject an unsigned AAB or one signed by a different Play upload key."""

import argparse
import re
import subprocess
import sys
from pathlib import Path

UPLOAD_CERT_SHA256 = "E5:0A:EE:1E:63:41:FF:AE:CD:1E:FE:AE:0B:25:B3:64:B5:80:B2:01:1B:E4:3C:06:2F:0A:23:54:1D:72:83:B5"


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
        raise ValueError("Release AAB signer is not the new Play upload certificate")


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
