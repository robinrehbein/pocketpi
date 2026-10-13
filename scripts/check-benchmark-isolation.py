"""Verify the benchmark fixture is isolated from the regular app.

The merged manifests must keep the fixture activities out of the regular app. With the optional
APK paths, the regular APK's dex files must also hold none of the benchmark-only classes, and the
benchmark APK must hold all of them (so the check would notice a renamed class).
"""
import sys
import xml.etree.ElementTree as ET
import zipfile

ANDROID = "{http://schemas.android.com/apk/res/android}"
FIXTURES = {
    "de.joinnoah.pi.remote.PerformanceFixtureActivity",
    "de.joinnoah.pi.remote.FullChatFixtureActivity",
    "de.joinnoah.pi.remote.ConnectedFlowActivity",
}

# Benchmark-only classes from src/benchmark, as dex type descriptors.
FIXTURE_CLASSES = [
    b"Lde/joinnoah/pi/remote/FakeRelayTransport;",
    b"Lde/joinnoah/pi/remote/RelayFixture;",
    b"Lde/joinnoah/pi/remote/ConnectedFlowActivity;",
    b"Lde/joinnoah/pi/remote/FlowFreshness;",
    b"Lde/joinnoah/pi/remote/FlowTrace;",
]


def dex_classes(apk_path):
    with zipfile.ZipFile(apk_path) as apk:
        dex = [apk.read(name) for name in apk.namelist() if name.startswith("classes") and name.endswith(".dex")]
    if not dex:
        raise ValueError(f"No dex files in {apk_path}")
    return {name for name in FIXTURE_CLASSES if any(name in data for data in dex)}


def verify_apks(regular_apk, benchmark_apk):
    leaked = dex_classes(regular_apk)
    if leaked:
        raise ValueError("Benchmark-only classes leaked into the regular APK: " + ", ".join(sorted(n.decode() for n in leaked)))
    missing = set(FIXTURE_CLASSES) - dex_classes(benchmark_apk)
    if missing:
        raise ValueError("Benchmark APK lacks: " + ", ".join(sorted(n.decode() for n in missing)))
    print("Benchmark class isolation: passed")


def verify(regular_path, benchmark_path):
    regular = ET.parse(regular_path).getroot()
    benchmark = ET.parse(benchmark_path).getroot()
    if regular.get("package") != "de.robinrehbein.pocketpi":
        raise ValueError("Unexpected regular app ID")
    if benchmark.get("package") != "de.robinrehbein.pocketpi.benchmark":
        raise ValueError("Benchmark must have isolated app data")
    if any(node.get(ANDROID + "name") in FIXTURES for node in regular.iter("activity")):
        raise ValueError("Benchmark fixture leaked into the regular app")
    app = benchmark.find("application")
    if app is None or app.get(ANDROID + "debuggable", "false") != "false":
        raise ValueError("Benchmark target must not be debuggable")
    profileable = app.find("profileable")
    if profileable is None or profileable.get(ANDROID + "shell") != "true":
        raise ValueError("Benchmark target must be shell-profileable")
    for name in FIXTURES:
        fixtures = [node for node in app.iter("activity") if node.get(ANDROID + "name") == name]
        if len(fixtures) != 1 or fixtures[0].get(ANDROID + "exported") != "true":
            raise ValueError(f"Missing externally launchable benchmark-only fixture: {name}")
    print("Benchmark manifest isolation: passed")


if __name__ == "__main__":
    try:
        if len(sys.argv) not in (3, 5):
            raise ValueError(
                "Usage: check-benchmark-isolation.py REGULAR_MANIFEST BENCHMARK_MANIFEST [REGULAR_APK BENCHMARK_APK]"
            )
        verify(sys.argv[1], sys.argv[2])
        if len(sys.argv) == 5:
            verify_apks(sys.argv[3], sys.argv[4])
    except (OSError, ValueError, ET.ParseError, zipfile.BadZipFile) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
