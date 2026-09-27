import importlib.util
import os
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

MODULE_PATH = Path(__file__).with_name("play_release.py")
SPEC = importlib.util.spec_from_file_location("play_release", MODULE_PATH)
play_release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(play_release)


def service_with_snapshot(version=22):
    service = MagicMock()
    edits = service.edits.return_value
    edits.insert.return_value.execute.return_value = {"id": "edit-1"}
    edits.tracks.return_value.list.return_value.execute.return_value = {
        "tracks": [
            {"track": "qa", "releases": [{"versionCodes": [str(version)], "status": "completed"}]},
            {"track": "closed-alpha-id", "releases": []},
        ]
    }
    edits.bundles.return_value.list.return_value.execute.return_value = {
        "bundles": [{"versionCode": version}]
    }
    edits.apks.return_value.list.return_value.execute.return_value = {"apks": []}
    return service


class PlayReleaseTests(unittest.TestCase):
    def test_closed_track_requires_exact_custom_id(self):
        with patch.dict(os.environ, {"POCKETPI_CLOSED_TRACK": "production"}):
            with self.assertRaises(ValueError):
                play_release.closed_track()
        with patch.dict(os.environ, {"POCKETPI_CLOSED_TRACK": "closed-alpha-id"}):
            self.assertEqual(play_release.closed_track(), "closed-alpha-id")

    def test_prepare_uses_maximum_across_tracks_bundles_and_apks(self):
        service = service_with_snapshot()
        service.edits.return_value.apks.return_value.list.return_value.execute.return_value = {
            "apks": [{"versionCode": 35}]
        }
        self.assertEqual(play_release.prepare(service, "closed-alpha-id"), 36)
        service.edits.return_value.delete.assert_called_once_with(
            packageName=play_release.PACKAGE_NAME, editId="edit-1"
        )

    def test_missing_closed_track_stops_before_version_allocation(self):
        service = service_with_snapshot()
        with self.assertRaisesRegex(ValueError, "does not exist"):
            play_release.prepare(service, "wrong-track")

    def test_publish_uses_one_edit_for_both_tracks_and_checks_commit(self):
        service = service_with_snapshot()
        edits = service.edits.return_value
        edits.insert.return_value.execute.side_effect = [{"id": "edit-1"}, {"id": "edit-2"}]
        first = {
            "tracks": [
                {"track": "qa", "releases": [{"versionCodes": ["22"]}]},
                {"track": "closed-alpha-id", "releases": []},
            ]
        }
        verified = {
            "tracks": [
                {"track": "qa", "releases": [{"versionCodes": ["23"], "status": "completed"}]},
                {"track": "closed-alpha-id", "releases": [{"versionCodes": ["23"], "status": "completed"}]},
            ]
        }
        edits.tracks.return_value.list.return_value.execute.side_effect = [first, verified]
        edits.bundles.return_value.upload.return_value.execute.return_value = {"versionCode": 23}
        media = types.ModuleType("googleapiclient.http")
        media.MediaFileUpload = lambda *args, **kwargs: object()
        with tempfile.TemporaryDirectory() as directory:
            bundle = Path(directory) / "app.aab"
            bundle.write_bytes(b"signed-bundle-placeholder")
            with patch.dict(sys.modules, {"googleapiclient": types.ModuleType("googleapiclient"),
                                          "googleapiclient.http": media}):
                play_release.publish(service, "closed-alpha-id", 23, bundle)
        self.assertEqual(edits.tracks.return_value.update.call_count, 2)
        edits.commit.assert_called_once_with(
            packageName=play_release.PACKAGE_NAME,
            editId="edit-1",
            changesInReviewBehavior="ERROR_IF_IN_REVIEW",
        )

    def test_version_collision_prevents_upload_and_commit(self):
        service = service_with_snapshot(version=23)
        media = types.ModuleType("googleapiclient.http")
        media.MediaFileUpload = lambda *args, **kwargs: object()
        with tempfile.TemporaryDirectory() as directory:
            bundle = Path(directory) / "app.aab"
            bundle.write_bytes(b"signed-bundle-placeholder")
            with patch.dict(sys.modules, {"googleapiclient": types.ModuleType("googleapiclient"),
                                          "googleapiclient.http": media}):
                with self.assertRaisesRegex(ValueError, "used since"):
                    play_release.publish(service, "closed-alpha-id", 23, bundle)
        edits = service.edits.return_value
        edits.bundles.return_value.upload.assert_not_called()
        edits.commit.assert_not_called()


if __name__ == "__main__":
    unittest.main()
