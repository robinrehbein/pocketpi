#!/usr/bin/env python3
"""Publish one PocketPi bundle to both Play testing tracks in one edit."""

import argparse
import os
import re
import sys
from pathlib import Path

PACKAGE_NAME = "de.robinrehbein.pocketpi"
INTERNAL_TRACK = "qa"
MAX_VERSION_CODE = 2_100_000_000
RESERVED_TRACKS = frozenset({INTERNAL_TRACK, "production", "beta", "alpha", "internal"})
CUSTOM_TRACK_ID = re.compile(r"[a-z0-9][a-z0-9_-]*\Z")


def required_env(name):
    value = os.environ.get(name, "").strip()
    if not value:
        raise ValueError(f"Missing {name}")
    return value


def validate_closed_track(track):
    # Form-factor tracks use a colon (for example wear:production). Only a
    # plain custom closed-test identifier may reach the release update loop.
    if (not CUSTOM_TRACK_ID.fullmatch(track) or track in RESERVED_TRACKS
            or "production" in track):
        raise ValueError("POCKETPI_CLOSED_TRACK must be the exact custom closed-test track ID")
    return track


def closed_track():
    track = os.environ.get("POCKETPI_CLOSED_TRACK", "").strip()
    return validate_closed_track(track) if track else None


def target_tracks(closed):
    return (INTERNAL_TRACK, validate_closed_track(closed)) if closed else (INTERNAL_TRACK,)


def release_version_codes(tracks, bundles, apks):
    codes = [0]
    for track in tracks:
        for release in track.get("releases", []):
            codes.extend(int(value) for value in release.get("versionCodes", []))
    for item in bundles + apks:
        codes.append(int(item["versionCode"]))
    return codes


def track_map(tracks, closed):
    by_name = {track["track"]: track for track in tracks}
    for name in target_tracks(closed):
        if name not in by_name:
            raise ValueError(f"Required Play track {name!r} does not exist")
    return by_name


def play_api():
    from google.oauth2.credentials import Credentials
    from googleapiclient.discovery import build

    credentials = Credentials(required_env("GOOGLE_ACCESS_TOKEN"))
    return build(
        "androidpublisher", "v3", credentials=credentials,
        cache_discovery=False, static_discovery=False,
    )


def snapshot(service, edit_id):
    edits = service.edits()
    tracks = edits.tracks().list(packageName=PACKAGE_NAME, editId=edit_id).execute()["tracks"]
    bundles = edits.bundles().list(packageName=PACKAGE_NAME, editId=edit_id).execute().get("bundles", [])
    apks = edits.apks().list(packageName=PACKAGE_NAME, editId=edit_id).execute().get("apks", [])
    return tracks, bundles, apks


def new_edit(service):
    return service.edits().insert(packageName=PACKAGE_NAME, body={}).execute()["id"]


def prepare(service, closed):
    edit_id = new_edit(service)
    try:
        tracks, bundles, apks = snapshot(service, edit_id)
        track_map(tracks, closed)
        version = max(release_version_codes(tracks, bundles, apks)) + 1
        if version > MAX_VERSION_CODE:
            raise ValueError("Play versionCode limit reached")
        return version
    finally:
        service.edits().delete(packageName=PACKAGE_NAME, editId=edit_id).execute()


def publish(service, closed, version, bundle):
    from googleapiclient.http import MediaFileUpload

    if not bundle.is_file() or bundle.stat().st_size == 0:
        raise ValueError("Signed AAB is missing or empty")
    if version < 1 or version > MAX_VERSION_CODE:
        raise ValueError("Invalid versionCode")
    edit_id = new_edit(service)
    committed = False
    try:
        tracks, bundles, apks = snapshot(service, edit_id)
        track_map(tracks, closed)
        if version <= max(release_version_codes(tracks, bundles, apks)):
            raise ValueError("Play versionCode has been used since the bundle was built")
        uploaded = service.edits().bundles().upload(
            packageName=PACKAGE_NAME,
            editId=edit_id,
            media_body=MediaFileUpload(str(bundle), mimetype="application/octet-stream", resumable=True),
        ).execute()
        if int(uploaded["versionCode"]) != version:
            raise ValueError("Uploaded AAB versionCode does not match the planned version")
        release = {
            "name": f"PocketPi {version}",
            "versionCodes": [str(version)],
            "status": "completed",
        }
        for track_name in target_tracks(closed):
            service.edits().tracks().update(
                packageName=PACKAGE_NAME,
                editId=edit_id,
                track=track_name,
                body={"track": track_name, "releases": [release]},
            ).execute()
        service.edits().commit(
            packageName=PACKAGE_NAME,
            editId=edit_id,
            changesInReviewBehavior="ERROR_IF_IN_REVIEW",
        ).execute()
        committed = True
    finally:
        if not committed:
            service.edits().delete(packageName=PACKAGE_NAME, editId=edit_id).execute()

    verify_id = new_edit(service)
    try:
        tracks, _, _ = snapshot(service, verify_id)
        current = track_map(tracks, closed)
        for track_name in target_tracks(closed):
            if not any(
                str(version) in release.get("versionCodes", []) and release.get("status") == "completed"
                for release in current[track_name].get("releases", [])
            ):
                raise RuntimeError(f"Play did not report completed release {version} on {track_name}")
    finally:
        service.edits().delete(packageName=PACKAGE_NAME, editId=verify_id).execute()


def main():
    parser = argparse.ArgumentParser()
    subcommands = parser.add_subparsers(dest="command", required=True)
    subcommands.add_parser("prepare")
    subcommands.add_parser("validate-track")
    publish_command = subcommands.add_parser("publish")
    publish_command.add_argument("--version-code", type=int, required=True)
    publish_command.add_argument("--bundle", type=Path, required=True)
    args = parser.parse_args()
    closed = closed_track()
    if args.command == "validate-track":
        print(" and ".join(target_tracks(closed)))
        return
    service = play_api()
    if args.command == "prepare":
        print(prepare(service, closed))
    else:
        publish(service, closed, args.version_code, args.bundle)
        print(f"Committed versionCode {args.version_code} to {' and '.join(target_tracks(closed))}")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"PocketPi Play release failed: {error}", file=sys.stderr)
        sys.exit(1)
