"""Compare Android resources per module, including modules missing a locale entirely."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def keys(directory):
    result = set()
    for path in sorted(directory.glob("*.xml")):
        try:
            resources = ET.parse(path).getroot()
        except ET.ParseError as error:
            raise ValueError(f"{path}: {error}") from error
        for entry in resources:
            if entry.tag not in {"string", "plurals", "string-array"}:
                continue
            if entry.get("translatable") != "false" and entry.get("name"):
                result.add((entry.tag, entry.get("name")))
    return result


def main(root):
    if not root.is_dir():
        raise ValueError(f"Android resource root does not exist: {root}")
    modules = sorted({
        path.parent for path in root.rglob("*")
        if path.is_dir() and path.name in {"values", "values-de"}
        and path.parent.parts[-3:] == ("src", "main", "res")
        and "build" not in path.relative_to(root).parts
    })
    if not modules:
        raise ValueError(f"No Android resource modules found under {root}")
    failures = 0
    for module in modules:
        english, german = keys(module / "values"), keys(module / "values-de")
        if english == german:
            continue
        failures += 1
        print(f"== {module.relative_to(root)}")
        for locale, missing in [("values-de", english - german), ("values", german - english)]:
            for kind, name in sorted(missing):
                print(f"   missing in {locale}/: {kind} {name}")
    print(f"modules with mismatches: {failures}")
    return int(failures > 0)


if __name__ == "__main__":
    try:
        sys.exit(main(Path(sys.argv[1])))
    except (OSError, ValueError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
