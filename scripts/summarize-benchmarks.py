"""Print a Markdown summary of Macrobenchmark results and APK sizes.

Usage: summarize-benchmarks.py [--apk LABEL=PATH ...] LABEL=DIR [LABEL=DIR ...]

Each DIR is searched for *-benchmarkData.json files. For every benchmark and metric the table
shows the median, minimum and maximum, one row per labelled directory, so runs of the same tests
(for example with the committed and with a freshly generated baseline profile) sit side by side.
"""
import json
import os
import sys
from pathlib import Path


def results(directory):
    rows = {}
    for path in sorted(Path(directory).rglob("*-benchmarkData.json")):
        data = json.loads(path.read_text())
        for benchmark in data.get("benchmarks", []):
            for metric, values in benchmark.get("metrics", {}).items():
                rows[(benchmark["name"], metric)] = values
    return rows


def size(path):
    return f"{os.path.getsize(path) / 1024 / 1024:.2f} MiB"


def main(arguments):
    apks, runs = [], []
    while arguments:
        argument = arguments.pop(0)
        if argument == "--apk":
            apks.append(arguments.pop(0).split("=", 1))
        else:
            runs.append(argument.split("=", 1))
    if apks:
        print("| APK | Size |\n| --- | --- |")
        for label, path in apks:
            print(f"| {label} | {size(path) if os.path.isfile(path) else 'missing'} |")
        print()
    labelled = [(label, results(directory)) for label, directory in runs]
    keys = sorted({key for _, rows in labelled for key in rows})
    if not keys:
        print("No benchmark results found.")
        return
    print("| Benchmark | Metric | Run | Median | Min | Max |\n| --- | --- | --- | --- | --- | --- |")
    for name, metric in keys:
        for label, rows in labelled:
            values = rows.get((name, metric))
            if values:
                print(
                    f"| {name} | {metric} | {label} | {values['median']:.1f} | "
                    f"{values['minimum']:.1f} | {values['maximum']:.1f} |"
                )


if __name__ == "__main__":
    main(sys.argv[1:])
