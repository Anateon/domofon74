#!/usr/bin/env python3
"""Collect runtime attribution from a Gradle dependency report and local artifacts.

Run `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` and
pass its saved output as the first argument. This script makes no network calls,
does not run Gradle, and never writes local cache paths into generated files.
Review upstream license changes separately when updating dependencies.
"""
import argparse
import hashlib
import io
import re
import zipfile
from pathlib import Path


def coordinates(report):
    found = set()
    for line in report.splitlines():
        if " (c)" in line:
            continue
        match = re.search(r"--- ([\w.-]+):([\w.-]+)(?::([^\s]+))?(?: -> ([^\s]+))?", line)
        if not match:
            continue
        group, artifact, version, replacement = match.groups()
        if replacement:
            if ":" in replacement:
                group, artifact, version = replacement.split(":")
            else:
                version = replacement
        if not version or version == "FAILED":
            raise ValueError("Unresolved dependency in report")
        found.add(f"{group}:{artifact}:{version}")
    if not found:
        raise ValueError("No resolved dependencies found")
    return sorted(found)


def license_entries(data, prefix=""):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for name in sorted(archive.namelist()):
            base = name.rsplit("/", 1)[-1].lower()
            if not base or base.endswith(".class"):
                continue
            if re.fullmatch(r"(?:license|notice|copyright)(?:[._-].*)?|al2\.0|lgpl2\.1", base):
                content = archive.read(name)
                content.decode("utf-8")  # Only human-readable notices are included.
                yield prefix + name, content
            elif name == "classes.jar" or (name.startswith("libs/") and name.endswith(".jar")):
                yield from license_entries(archive.read(name), prefix + name + "!/")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    parser.add_argument("--cache", type=Path, default=Path.home() / ".gradle/caches/modules-2/files-2.1")
    parser.add_argument("--output", type=Path, default=Path("app/src/main/assets/licenses"))
    args = parser.parse_args()
    dependencies = coordinates(args.report.read_text())
    allowed_groups = ("androidx.", "org.jetbrains.kotlin", "org.jetbrains:", "com.google.guava:", "org.jspecify:")
    unknown = [value for value in dependencies if not value.startswith(allowed_groups)]
    if unknown:
        raise ValueError("Review the licenses of new dependency families: " + ", ".join(unknown))
    notices = {}
    for coordinate in dependencies:
        group, artifact, version = coordinate.split(":")
        directory = args.cache / group / artifact / version
        artifacts = sorted(p for p in directory.glob("*/*")
                           if p.suffix in (".jar", ".aar")
                           and not p.name.endswith(("-sources.jar", "-javadoc.jar")))
        for artifact_file in artifacts:
            for entry, content in license_entries(artifact_file.read_bytes()):
                digest = hashlib.sha256(content).hexdigest()
                record = notices.setdefault(digest, {"content": content.decode("utf-8"), "origins": set()})
                record["origins"].add(f"{coordinate} — {entry}")
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "RUNTIME_DEPENDENCIES.txt").write_text(
        "Resolved releaseRuntimeClasspath (including platform metadata)\n\n" + "\n".join(dependencies) + "\n")
    sections = ["Original license and NOTICE entries retained from runtime JAR/AAR artifacts.\n"]
    for record in sorted(notices.values(), key=lambda value: sorted(value["origins"])[0]):
        sections.append("\n" + "=" * 72 + "\n" + "\n".join(sorted(record["origins"])) + "\n\n" + record["content"])
    (args.output / "Embedded-NOTICES.txt").write_text("\n".join(sections))
    print(f"Collected {len(dependencies)} resolved dependencies and {len(notices)} distinct embedded notices")


if __name__ == "__main__":
    main()
