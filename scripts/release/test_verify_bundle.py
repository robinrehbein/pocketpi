import importlib.util
import re
import shutil
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

MODULE_PATH = Path(__file__).with_name("verify_bundle.py")
SPEC = importlib.util.spec_from_file_location("verify_bundle", MODULE_PATH)
verify_bundle = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verify_bundle)


class VerifyBundleTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which("jarsigner") and shutil.which("keytool"), "JDK tools unavailable")
    def test_unsigned_archive_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            bundle = Path(directory) / "unsigned.aab"
            with zipfile.ZipFile(bundle, "w") as archive:
                archive.writestr("base/manifest/AndroidManifest.xml", "fixture")
            with self.assertRaisesRegex(ValueError, "not fully signed"):
                verify_bundle.verify_bundle(bundle)

    @unittest.skipUnless(shutil.which("jarsigner") and shutil.which("keytool"), "JDK tools unavailable")
    def test_self_signed_archive_with_expected_certificate_is_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            bundle = Path(directory) / "signed.aab"
            key = Path(directory) / "fixture.jks"
            with zipfile.ZipFile(bundle, "w") as archive:
                archive.writestr("base/manifest/AndroidManifest.xml", "fixture")
            subprocess.run([
                "keytool", "-genkeypair", "-alias", "fixture", "-keyalg", "RSA",
                "-keysize", "2048", "-validity", "30", "-keystore", str(key),
                "-storepass", "password", "-keypass", "password", "-dname", "CN=Test",
                "-noprompt",
            ], check=True, capture_output=True)
            subprocess.run([
                "jarsigner", "-keystore", str(key), "-storepass", "password",
                "-keypass", "password", str(bundle), "fixture",
            ], check=True, capture_output=True)
            printed = subprocess.run([
                "keytool", "-J-Duser.language=en", "-printcert", "-jarfile", str(bundle),
            ], check=True, capture_output=True, text=True).stdout
            fingerprint = re.search(r"(?im)^\s*SHA256:\s*([0-9a-f:]+)\s*$", printed).group(1)
            with patch.object(verify_bundle, "UPLOAD_CERT_SHA256", fingerprint.upper()):
                verify_bundle.verify_bundle(bundle)

    def test_wrong_certificate_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            bundle = Path(directory) / "bundle.aab"
            bundle.write_bytes(b"fixture")
            result = unittest.mock.Mock(returncode=0, stdout="jar verified.\n", stderr="")
            cert = unittest.mock.Mock(returncode=0, stdout="SHA256: 00:11:22\n", stderr="")
            with patch.object(verify_bundle.subprocess, "run", side_effect=[result, cert]):
                with self.assertRaisesRegex(ValueError, "not the new Play upload certificate"):
                    verify_bundle.verify_bundle(bundle)

    def test_partially_signed_archive_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            bundle = Path(directory) / "bundle.aab"
            bundle.write_bytes(b"fixture")
            result = unittest.mock.Mock(
                returncode=0,
                stdout="jar verified.\nWarning: This jar contains unsigned entries.\n",
                stderr="",
            )
            with patch.object(verify_bundle.subprocess, "run", return_value=result) as run:
                with self.assertRaisesRegex(ValueError, "not fully signed"):
                    verify_bundle.verify_bundle(bundle)
            run.assert_called_once()

    def test_expected_certificate_is_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            bundle = Path(directory) / "bundle.aab"
            bundle.write_bytes(b"fixture")
            result = unittest.mock.Mock(returncode=0, stdout="jar verified.\n", stderr="")
            cert = unittest.mock.Mock(
                returncode=0,
                stdout=f"Owner: CN=PocketPi\nSHA256: {verify_bundle.UPLOAD_CERT_SHA256}\n",
                stderr="",
            )
            with patch.object(verify_bundle.subprocess, "run", side_effect=[result, cert]):
                verify_bundle.verify_bundle(bundle)


if __name__ == "__main__":
    unittest.main()
