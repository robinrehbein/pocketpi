"""Verify the benchmark fixture is isolated from the regular app after manifest merging."""
import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
FIXTURE = "de.joinnoah.pi.remote.PerformanceFixtureActivity"


def verify(regular_path, benchmark_path):
    regular = ET.parse(regular_path).getroot()
    benchmark = ET.parse(benchmark_path).getroot()
    if regular.get("package") != "de.robinrehbein.pocketpi":
        raise ValueError("Unexpected regular app ID")
    if benchmark.get("package") != "de.robinrehbein.pocketpi.benchmark":
        raise ValueError("Benchmark must have isolated app data")
    if any(node.get(ANDROID + "name") == FIXTURE for node in regular.iter("activity")):
        raise ValueError("Benchmark fixture leaked into the regular app")
    app = benchmark.find("application")
    if app is None or app.get(ANDROID + "debuggable", "false") != "false":
        raise ValueError("Benchmark target must not be debuggable")
    profileable = app.find("profileable")
    if profileable is None or profileable.get(ANDROID + "shell") != "true":
        raise ValueError("Benchmark target must be shell-profileable")
    fixtures = [node for node in app.iter("activity") if node.get(ANDROID + "name") == FIXTURE]
    if len(fixtures) != 1 or fixtures[0].get(ANDROID + "exported") != "true":
        raise ValueError("Missing externally launchable benchmark-only fixture")
    print("Benchmark manifest isolation: passed")


if __name__ == "__main__":
    try:
        if len(sys.argv) != 3:
            raise ValueError("Usage: check-benchmark-isolation.py REGULAR_MANIFEST BENCHMARK_MANIFEST")
        verify(sys.argv[1], sys.argv[2])
    except (OSError, ValueError, ET.ParseError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
