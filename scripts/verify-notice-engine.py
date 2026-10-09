#!/usr/bin/env python3
"""Verify the packaged engine source pin, original notices and private extension assets."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PIN = json.loads((ROOT / "third_party/geckoview/manifest.json").read_text())


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def verify(apk: Path) -> None:
    with zipfile.ZipFile(apk) as package:
        names = package.namelist()
        existing = set(json.loads((ROOT / "third_party/android-native-modules.json").read_text()))
        expected = {f"lib/arm64-v8a/{name}" for name in existing | set(PIN["arm64_libraries"])}
        actual = {name for name in names if name.startswith("lib/") and name.endswith(".so")}
        require(actual == expected, "Packaged ABI/library set differs from the reviewed engine and existing native modules")
        require(not any("fixture-ca" in n or "server-test-key" in n for n in names), "Fixture TLS material in main APK")
        with zipfile.ZipFile(io.BytesIO(package.read("assets/omni.ja"))) as engine:
            config = engine.read("chrome/toolkit/content/global/buildconfig.html").decode()
            require(f"/rev/{PIN['source_revision']}" in config, "Engine source revision differs from reviewed API pin")
            original = engine.read("chrome/toolkit/content/global/license.html")
            require(hashlib.sha256(original).hexdigest() == PIN["license_html_sha256"], "Engine license changed")
        notice = package.read("assets/licenses/GeckoView.txt")
        require(hashlib.sha256(notice).hexdigest() == PIN["text_sha256"], "Readable engine notices changed")
        assets = ROOT / "app/src/main/assets/notice-session"
        for source in assets.iterdir():
            if source.is_file():
                require(package.read(f"assets/notice-session/{source.name}") == source.read_bytes(), f"Stale extension asset: {source.name}")
    print(f"{apk.name}: PASS pinned engine, original/readable notices, private extension, no TLS fixture")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apks", type=Path, nargs="+")
    for path in parser.parse_args().apks:
        verify(path)
