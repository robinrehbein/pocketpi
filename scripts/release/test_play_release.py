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


def service_with_snapshot(version=1):
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
    def test_new_play_app_uses_first_version_code(self):
        self.assertEqual(play_release.PACKAGE_NAME, "de.robinrehbein.pocketpi")
        service = service_with_snapshot()
        edits = service.edits.return_value
        edits.tracks.return_value.list.return_value.execute.return_value = {
            "tracks": [
                {"track": "qa", "releases": []},
                {"track": "closed-alpha-id", "releases": []},
            ]
        }
        edits.bundles.return_value.list.return_value.execute.return_value = {"bundles": []}
        edits.apks.return_value.list.return_value.execute.return_value = {"apks": []}
        self.assertEqual(play_release.prepare(service, "closed-alpha-id"), 1)
        edits.tracks.return_value.list.assert_called_once_with(
            packageName="de.robinrehbein.pocketpi", editId="edit-1"
        )

    def test_closed_track_accepts_alpha_or_exact_custom_id(self):
        invalid = (
            "production", "wear:production", "automotive:production", "tv:production",
            "qa", "beta", "internal", "Production", "wear:alpha",
            "closed/alpha", "closed alpha", "closed-alpha\nproduction",
        )
        for track in invalid:
            with self.subTest(track=track), patch.dict(os.environ, {"POCKETPI_CLOSED_TRACK": track}):
                with self.assertRaises(ValueError):
                    play_release.closed_track()
        for track in ("alpha", "closed-alpha-id"):
            with self.subTest(track=track), patch.dict(os.environ, {"POCKETPI_CLOSED_TRACK": track}):
                self.assertEqual(play_release.closed_track(), track)

    def test_missing_closed_track_selects_internal_only(self):
        with patch.dict(os.environ, {"POCKETPI_CLOSED_TRACK": ""}):
            self.assertIsNone(play_release.closed_track())
        service = service_with_snapshot()
        self.assertEqual(play_release.prepare(service, None), 2)
        self.assertEqual(tuple(play_release.target_tracks(None)), ("qa",))

    def test_internal_only_publish_updates_and_verifies_qa(self):
        service = service_with_snapshot()
        edits = service.edits.return_value
        edits.insert.return_value.execute.side_effect = [{"id": "edit-1"}, {"id": "edit-2"}]
        edits.tracks.return_value.list.return_value.execute.side_effect = [
            {"tracks": [{"track": "qa", "releases": []}]},
            {"tracks": [{"track": "qa", "releases": [
                {"versionCodes": ["2"], "status": "completed"}
            ]}]},
        ]
        edits.bundles.return_value.list.return_value.execute.return_value = {"bundles": []}
        edits.bundles.return_value.upload.return_value.execute.return_value = {"versionCode": 2}
        edits.apks.return_value.list.return_value.execute.return_value = {"apks": []}
        media = types.ModuleType("googleapiclient.http")
        media.MediaFileUpload = lambda *args, **kwargs: object()
        with tempfile.TemporaryDirectory() as directory:
            bundle = Path(directory) / "app.aab"
            bundle.write_bytes(b"signed-bundle-placeholder")
            with patch.dict(sys.modules, {"googleapiclient": types.ModuleType("googleapiclient"),
                                          "googleapiclient.http": media}):
                play_release.publish(service, None, 2, bundle)
        edits.tracks.return_value.update.assert_called_once()
        self.assertEqual(edits.tracks.return_value.update.call_args.kwargs["track"], "qa")

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
                {"track": "qa", "releases": []},
                {"track": "alpha", "releases": []},
            ]
        }
        verified = {
            "tracks": [
                {"track": "qa", "releases": [{"versionCodes": ["1"], "status": "completed"}]},
                {"track": "alpha", "releases": [{"versionCodes": ["1"], "status": "completed"}]},
            ]
        }
        edits.tracks.return_value.list.return_value.execute.side_effect = [first, verified]
        edits.bundles.return_value.list.return_value.execute.return_value = {"bundles": []}
        edits.bundles.return_value.upload.return_value.execute.return_value = {"versionCode": 1}
        edits.apks.return_value.list.return_value.execute.return_value = {"apks": []}
        media = types.ModuleType("googleapiclient.http")
        media.MediaFileUpload = lambda *args, **kwargs: object()
        with tempfile.TemporaryDirectory() as directory:
            bundle = Path(directory) / "app.aab"
            bundle.write_bytes(b"signed-bundle-placeholder")
            with patch.dict(sys.modules, {"googleapiclient": types.ModuleType("googleapiclient"),
                                          "googleapiclient.http": media}):
                play_release.publish(service, "alpha", 1, bundle)
        self.assertEqual(edits.tracks.return_value.update.call_count, 2)
        self.assertEqual(
            [call.kwargs["track"] for call in edits.tracks.return_value.update.call_args_list],
            ["qa", "alpha"],
        )
        edits.commit.assert_called_once_with(
            packageName=play_release.PACKAGE_NAME,
            editId="edit-1",
            changesInReviewBehavior="ERROR_IF_IN_REVIEW",
        )

    def test_version_collision_prevents_upload_and_commit(self):
        service = service_with_snapshot(version=2)
        media = types.ModuleType("googleapiclient.http")
        media.MediaFileUpload = lambda *args, **kwargs: object()
        with tempfile.TemporaryDirectory() as directory:
            bundle = Path(directory) / "app.aab"
            bundle.write_bytes(b"signed-bundle-placeholder")
            with patch.dict(sys.modules, {"googleapiclient": types.ModuleType("googleapiclient"),
                                          "googleapiclient.http": media}):
                with self.assertRaisesRegex(ValueError, "used since"):
                    play_release.publish(service, "closed-alpha-id", 2, bundle)
        edits = service.edits.return_value
        edits.bundles.return_value.upload.assert_not_called()
        edits.commit.assert_not_called()


if __name__ == "__main__":
    unittest.main()
