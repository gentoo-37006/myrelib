import re
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from xml.etree import ElementTree


def published_versions(group, artifact):
    path = group.replace(".", "/")
    url = f"https://repo.maven.apache.org/maven2/{path}/{artifact}/maven-metadata.xml"
    request = Request(url, headers={"Cache-Control": "no-cache"})
    try:
        with urlopen(request, timeout=30) as response:
            metadata = ElementTree.parse(response).getroot()
    except HTTPError as error:
        if error.code == 404:
            return []
        raise

    if metadata.findtext("{*}groupId") != group or metadata.findtext("{*}artifactId") != artifact:
        raise ValueError(f"Unexpected Maven metadata at {url}")
    return [node.text.strip() for node in metadata.findall("{*}versioning/{*}versions/{*}version")]


def next_version(major, versions):
    # Count legacy three-part releases so the next release sorts after them.
    release = re.compile(rf"{major}\.([0-9]+)(?:\.[0-9]+)?")
    minors = [int(match[1]) for version in versions if (match := release.fullmatch(version))]
    return f"{major}.{max(minors, default=-1) + 1}"


def main():
    properties = Path(__file__).resolve().parents[2] / "gradle.properties"
    settings = {}
    for line in properties.read_text(encoding="utf-8").splitlines():
        key, separator, value = line.partition("=")
        key = key.strip()
        if separator and key in ("myrelib_version", "GROUP"):
            settings[key] = value.strip()

    major = settings["myrelib_version"]
    group = settings["GROUP"]
    if not re.fullmatch(r"0|[1-9][0-9]*", major):
        raise ValueError("myrelib_version must be a non-negative integer")

    versions = [
        version
        for artifact in ("myrelib", "myrelib-processor", "myrelib-yb")
        for version in published_versions(group, artifact)
    ]
    print(next_version(major, versions))


if __name__ == "__main__":
    main()
