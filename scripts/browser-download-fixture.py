#!/usr/bin/env python3
"""Loopback-only generated downloads for physical streamed-browser acceptance.

Serves no filesystem paths, executes no commands, and does not change cmux
settings. HTTP receipts prove requests only; verify the saved file separately.
"""
import argparse
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import threading
import uuid


def digest_file(path):
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as stream:
        while data := stream.read(64 * 1024):
            digest.update(data)
            size += len(data)
    return size, digest.hexdigest()


def serve(output):
    output.mkdir(parents=True, exist_ok=False)
    run_id = uuid.uuid4().hex
    prefix = "/" + run_id
    payloads = {
        "text": (f"cmux-download-{run_id}.txt", "text/plain; charset=utf-8",
                 f"cmux browser download acceptance\nRun: {run_id}\nUnicode: λ 中 🙂\n".encode()),
        "binary": (f"cmux-download-{run_id}.bin", "application/octet-stream",
                   run_id.encode() + b"\x00" + bytes((i * 37 + 19) % 256 for i in range(65_673))),
    }
    report = {
        "run": run_id, "url": None, "stopped": False,
        "assets": {key: {"filename": name, "mime": mime, "bytes": len(data),
                         "sha256": hashlib.sha256(data).hexdigest(), "completedHttpResponses": 0}
                   for key, (name, mime, data) in payloads.items()},
        "scope": "HTTP responses do not prove a Mac or phone saved the file.",
    }
    receipt = output / "receipt.json"
    lock = threading.Lock()

    def save():
        temporary = output / "receipt.tmp"
        temporary.write_text(json.dumps(report, indent=2) + "\n")
        temporary.replace(receipt)

    page = f"""<!doctype html><html lang="en"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>cmux download acceptance</title>
<style>body{{background:#101114;color:#eee;font:18px system-ui;margin:24px;line-height:1.5}}
a{{display:block;background:#24394d;color:#a7d6ff;padding:18px;margin:18px 0;border-radius:12px}}
small{{overflow-wrap:anywhere}}</style>
<h1>Download check</h1><p>Open this page in the <b>Streamed</b> browser. Files should
be saved by the Mac, using its existing download setting.</p>
<a href="{prefix}/text" download="{payloads['text'][0]}">Download text — HTML download link</a>
<a href="{prefix}/binary">Download binary — attachment response</a>
<p>A completed request is not proof that a file was saved. Verify the saved file
with the fixture's verifier.</p><small>Run: {run_id}</small></html>""".encode()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_HEAD(self):
            self.respond(False)

        def do_GET(self):
            self.respond(True)

        def respond(self, include_body):
            self.connection.settimeout(10)
            key = next((key for key in payloads if self.path == prefix + "/" + key), None)
            if self.path == prefix + "/":
                name, mime, data = None, "text/html; charset=utf-8", page
            elif key:
                name, mime, data = payloads[key]
            else:
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header("Content-Type", mime)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            # Text exercises the HTML download attribute; binary exercises the
            # response-policy attachment path without a download attribute.
            if key == "binary":
                self.send_header("Content-Disposition", f'attachment; filename="{name}"')
            self.end_headers()
            if not include_body:
                return
            try:
                self.wfile.write(data)
                self.wfile.flush()
            except OSError:
                return
            if key:
                with lock:
                    report["assets"][key]["completedHttpResponses"] += 1
                    save()

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    report["url"] = f"http://127.0.0.1:{server.server_port}{prefix}/"
    save()
    print(json.dumps({"url": report["url"], "receipt": str(receipt.resolve())}), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        with lock:
            report["stopped"] = True
            save()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    start = commands.add_parser("serve")
    start.add_argument("--output", type=Path, required=True, help="New evidence directory; must not already exist")
    verify = commands.add_parser("verify")
    verify.add_argument("--receipt", type=Path, required=True)
    verify.add_argument("--asset", choices=("text", "binary"), required=True)
    verify.add_argument("--file", type=Path, required=True, help="Actual saved file; read only")
    args = parser.parse_args()
    if args.command == "serve":
        serve(args.output)
    else:
        expected = json.loads(args.receipt.read_text())["assets"][args.asset]
        size, digest = digest_file(args.file)
        matched = size == expected["bytes"] and digest == expected["sha256"]
        print(json.dumps({"asset": args.asset, "bytes": size, "sha256": digest, "matches": matched}))
        if not matched:
            raise SystemExit("Saved file does not match the generated download")


if __name__ == "__main__":
    main()
