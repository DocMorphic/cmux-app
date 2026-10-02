#!/usr/bin/env python3
"""Run the opt-in physical NIGHTLY browser journey with an owned Mac HTTP fixture.

Requires an already signed-in debug app and a built instrumentation APK. The test
retains the intended NIGHTLY pairing, preserves existing data, and removes only
its generated workspace. Never use this runner with a disposable-account fixture.
"""
import argparse
import datetime
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import uuid


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default=shutil.which("adb") or str(Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk")) / "platform-tools/adb"))
    parser.add_argument("--output", type=Path, default=root / "captures/runtime/live-browser-ui" /
                        datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ"))
    args = parser.parse_args()
    adb = [args.adb, "-s", args.serial]
    apk = root / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    if not apk.is_file():
        parser.error("Build :app:assembleDebugAndroidTest first")
    policy = subprocess.check_output(adb + ["shell", "dumpsys", "window", "policy"], text=True, timeout=15)
    if re.findall(r"^\s*showing=(true|false)\s*$", policy, re.M) != ["false"]:
        parser.error("Unlock the physical Pixel and leave cmux (debug) open; no test was started")
    original = subprocess.check_output(adb + ["shell", "settings", "get", "global", "stay_on_while_plugged_in"], text=True, timeout=15).strip()
    if original not in {str(i) for i in range(8)}:
        parser.error("Cannot safely preserve the phone's current sleep setting")
    args.output.mkdir(parents=True, exist_ok=False)
    marker = "CMUX_BROWSER_UI_" + uuid.uuid4().hex
    requests = []

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *_):
            pass

        def do_GET(self):
            part = self.path.removeprefix("/" + marker + "/")
            if self.path not in {f"/{marker}/start", f"/{marker}/next"}:
                self.send_error(404)
                return
            requests.append(part)
            title = "Mac browser fixture" if part == "start" else "Next Mac page"
            page = (f'<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">'
                    f'<title>{title}</title><style>body{{background:#164f3b;color:white;font:20px sans-serif;'
                    f'padding:24px;overflow-wrap:anywhere}}a{{color:white;display:block;margin:28px 0}}</style>'
                    f'<h1>{title}</h1><p>{marker}</p><a href="/{marker}/next">Open next Mac page</a>').encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(page)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(page)
            self.close_connection = True

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    worker = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.1}, daemon=True)
    worker.start()
    restored = False
    try:
        subprocess.run(adb + ["shell", "settings", "put", "global", "stay_on_while_plugged_in", "7"], check=True, timeout=15)
        with (args.output / "install.txt").open("w") as log:
            subprocess.run(adb + ["install", "-r", str(apk)], stdout=log, stderr=subprocess.STDOUT, check=True, timeout=90)
        print("Running physical browser UI check; existing sign-in/data will be preserved.", flush=True)
        with (args.output / "runtime.txt").open("w") as log:
            subprocess.run(adb + ["shell", "am", "instrument", "-w", "-r", "-e", "class",
                "io.github.docmorphic.cmuxapp.LiveNativeBrowserUiCheck", "-e", "cmux_live_browser_ui", "true",
                "-e", "cmux_live_browser_fixture_port", str(server.server_port),
                "-e", "cmux_live_browser_fixture_marker", marker,
                "io.github.docmorphic.cmuxapp.debug.test/androidx.test.runner.AndroidJUnitRunner"],
                stdout=log, stderr=subprocess.STDOUT, check=True, timeout=300)
        result = (args.output / "runtime.txt").read_text()
        logs = subprocess.check_output(adb + ["logcat", "-d", "-v", "brief", "-s", "System.out:I"], text=True, timeout=15)
        (args.output / "reports.txt").write_text("\n".join(line for line in logs.splitlines()
            if marker in line and any(prefix in line for prefix in (
                "CMUX_LIVE_BROWSER_UI_STAGE ", "CMUX_LIVE_BROWSER_UI_REPORT ", "CMUX_LIVE_BROWSER_UI_CLEANUP "))) + "\n")
        if "OK (1 test)" not in result or "FAILURES!!!" in result:
            raise RuntimeError("Browser UI check did not pass; inspect runtime.txt and any private fixture receipt before rerunning")
        if not {"start", "next"}.issubset(requests):
            raise RuntimeError("The Mac fixture did not observe both page requests")
        subprocess.run(adb + ["pull", "/sdcard/Android/data/io.github.docmorphic.cmuxapp.debug/files/live-browser-ui-check/",
                             str(args.output / "screenshots")], check=True, timeout=30)
        print("Browser UI check passed; inspect the saved screenshots before claiming visual acceptance.")
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=5)
        try:
            subprocess.run(adb + ["shell", "settings", "put", "global", "stay_on_while_plugged_in", original], check=True, timeout=15)
            restored = subprocess.check_output(adb + ["shell", "settings", "get", "global", "stay_on_while_plugged_in"], text=True, timeout=15).strip() == original
        finally:
            (args.output / "fixture.json").write_text(json.dumps({"requests": requests, "listenerClosed": True,
                "sleepSettingRestored": restored, "originalSleepSetting": original}, indent=2) + "\n")
        if not restored:
            raise RuntimeError("Restore of the original phone sleep setting was not verified")


if __name__ == "__main__":
    main()
