#!/usr/bin/env python3
"""Isolated loopback SSH fixture. No real shell, user credentials or host sshd.

Start before building the opt-in ssh-spike APK; generated import keys go only
into that APK's test assets. Requires asyncssh[bcrypt]==2.24.0; optional
--websocket also requires websockets==17.1. Stop with Ctrl-C.
The fixture accepts valid public-key signatures for a random test username; its
only commands are synthetic, forwarding targets itself, and SFTP is chrooted to
a temporary directory. Never expose its port outside loopback.
"""
import argparse
import asyncio
import json
import os
from pathlib import Path
import secrets
import tempfile

import asyncssh


async def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--websocket", action="store_true", help="Enable the optional websockets==17.1 browser peer")
    args = parser.parse_args()
    os.umask(0o077)
    root = Path(__file__).resolve().parents[1]
    assets = root / "ssh-spike/build/fixture-assets"
    assets.mkdir(parents=True, exist_ok=True)
    username = "spike-" + secrets.token_hex(12)
    host_key = asyncssh.generate_private_key("ssh-ed25519")
    nonce = secrets.token_hex(16)
    port = 0
    silent_port = 0
    browser_port = 0
    websocket_port = 0
    websocket_server = None
    websocket_state = {"active": 0, "opened": 0, "text": [], "binary": []}
    active_shells = 0
    transfer_gate = asyncio.Event()
    transfer_gate.set()
    waiting = {"reads": 0, "writes": 0}

    class FixtureSFTP(asyncssh.SFTPServer):
        async def hold(self, kind):
            waiting[kind] += 1
            try:
                await transfer_gate.wait()
            finally:
                waiting[kind] -= 1

        async def read(self, file_obj, offset, size):
            if Path(os.fsdecode(file_obj.name)).name == "slow-download.bin" and offset >= 32768:
                await self.hold("reads")
            return super().read(file_obj, offset, size)

        async def write(self, file_obj, offset, data):
            if Path(os.fsdecode(file_obj.name)).parent.name == "slow-upload":
                await self.hold("writes")
            return super().write(file_obj, offset, data)

    def trace(event):
        if os.environ.get("CMUX_SSH_TRACE") == "1":
            print(json.dumps({"fixture_event": event, "active_shells": active_shells}), flush=True)

    async def echo_after_eof(reader, writer):
        # A bounded binary payload with a tail only after SSH EOF tests half-close.
        data = await reader.read(256 * 1024)
        while not reader.at_eof() and len(data) < 256 * 1024:
            data += await reader.read(256 * 1024 - len(data))
        writer.write(data + b"\x00\xffSSH-tail")
        writer.write_eof()
        writer.close()

    class Server(asyncssh.SSHServer):
        def connection_made(self, conn):
            self.conn = conn

        def connection_lost(self, exc):
            trace("connection_closed:" + type(exc).__name__)

        def begin_auth(self, user):
            return True

        def public_key_auth_supported(self):
            return True

        def validate_public_key(self, user, key):
            # AsyncSSH still verifies proof of private-key possession.
            return user == username

        def connection_requested(self, dest_host, dest_port, orig_host, orig_port):
            if dest_host == "ssh-only.invalid" and dest_port == 7:
                return echo_after_eof
            if dest_host in ("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "ssh-only.invalid") and dest_port in ({browser_port, websocket_port} if websocket_port else {browser_port}):
                return self.conn.forward_connection("127.0.0.1", dest_port)
            return dest_host == "127.0.0.1" and dest_port in (port, silent_port)

    async def process(proc):
        nonlocal active_shells
        if proc.command == "fixture-websocket-status" and args.websocket:
            proc.stdout.write(json.dumps(websocket_state)); proc.exit(0)
        elif proc.command == "files-transfer-arm":
            transfer_gate.clear()
            proc.exit(0)
        elif proc.command == "files-transfer-release":
            transfer_gate.set()
            proc.exit(0)
        elif proc.command == "files-transfer-status":
            proc.stdout.write(json.dumps(waiting) + "\n")
            proc.exit(0)
        elif proc.command == "files-fixture-link":
            # Fixture-owned symlink setup avoids JSch's OpenSSH-style symlink
            # argument order differing from AsyncSSH's standard-order decoder.
            # This command accepts no remote path and never invokes a shell.
            target = Path(directory) / "files-link-target *?\\"
            target.mkdir(exist_ok=True)
            link = Path(directory) / "files-link"
            link.unlink(missing_ok=True)
            link.symlink_to(target.name, target_is_directory=True)
            proc.exit(0)
        elif proc.command == "shell-count":
            trace("count_requested")
            proc.stdout.write(str(active_shells) + "\n")
            proc.exit(0)
        elif proc.command == "probe":
            proc.stdout.write(nonce + "\n")
            proc.exit(0)
        elif proc.command == "control-echo":
            if proc.term_type:
                proc.stderr.write("Control stream must not allocate a PTY\n")
                proc.exit(1)
                return
            proc.stdout.write("RAW\n")
            while True:
                chunk = await proc.stdin.read(8192)
                if not chunk:
                    break
                proc.stdout.write(chunk)
            proc.exit(0)
        elif proc.command == "wait":
            await proc.stdin.read()
            proc.exit(0)
        elif proc.command == "stall":
            proc.stdout.write("WAIT\n")
            await asyncio.sleep(120)
            proc.exit(0)
        elif proc.command == "drop":
            proc.channel.get_connection().abort()
        elif proc.command is None:
            active_shells += 1
            trace("shell_opened")
            try:
                proc.stdout.write("SIZE %d %d\r\n" % proc.term_size[:2])
                line = ""
                after_cr = False
                while True:
                    try:
                        char = await proc.stdin.read(1)
                        if not char:
                            break
                        if char not in "\r\n":
                            line += char
                            after_cr = False
                            continue
                        if char == "\n" and after_cr:
                            after_cr = False
                            continue
                        after_cr = char == "\r"
                        bracketed = line.startswith("\x1b[200~") and line.endswith("\x1b[201~")
                        value = line[6:-6] if bracketed else line
                        line = ""
                        if value == "vt-demo":
                            proc.stdout.write("\x1b[2J\x1b[H\x1b[32mGreen λ 中\x1b[0m\r\n\x1b[?2004h")
                        elif value == "vt-query":
                            proc.stdout.write("\x1b[3;4H\x1b[6n")
                            reply = await proc.stdin.readexactly(6)
                            proc.stdout.write("\r\n" + ("QUERY-OK" if reply == "\x1b[3;4R" else "QUERY-FAILED") + "\r\n")
                        elif value == "vt-alt":
                            proc.stdout.write("\x1b[?1049h\x1b[HALTERNATE\r\n")
                        elif value == "vt-primary":
                            proc.stdout.write("\x1b[?1049l")
                        elif value == "vt-cwd":
                            folder = Path(directory) / "Shell files λ +%?#"
                            folder.mkdir(exist_ok=True)
                            (folder / "cwd-marker.txt").write_text("shell folder fixture")
                            (folder / "'a'.txt").write_text("quoted path fixture")
                            # Split an OSC across writes, including its ST terminator.
                            proc.stdout.write("\x1b]7;file://fixture/Shell%20files%20")
                            await asyncio.sleep(0.03)
                            proc.stdout.write("%CE%BB%20+%25?#\x1b")
                            await asyncio.sleep(0.03)
                            proc.stdout.write("\\CWD-REPORTED\r\n")
                        elif value == "vt-exit":
                            break
                        elif value == "bracket-check":
                            proc.stdout.write(("BRACKET-OK" if bracketed else "BRACKET-MISSING") + "\r\n")
                        else:
                            proc.stdout.write("ECHO " + value + "\r\n")
                    except asyncssh.TerminalSizeChanged as event:
                        proc.stdout.write("SIZE %d %d\r\n" % (event.width, event.height))
                proc.exit(0)
            finally:
                active_shells -= 1
                trace("shell_closed")
        else:
            proc.stderr.write("fixture command refused\n")
            proc.exit(127)

    async def silent_peer(reader, writer):
        try:
            await reader.read()  # Accept TCP but never send an SSH greeting.
        finally:
            writer.close()
            await writer.wait_closed()

    async def browser_peer(reader, writer):
        try:
            header = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), 10)
            path = header.split(b" ", 2)[1]
            if path == b"/websocket" and args.websocket:
                body = ("<!doctype html><meta name='viewport' content='width=device-width,initial-scale=1'>"
                        "<title>SSH WebSocket loading</title><style>body{background:#112e3c;color:white;font:24px sans-serif;padding:20px}</style>"
                        "<h1 id='result'>Connecting WebSocket</h1><button id='send' disabled>Send live update</button>"
                        f"<script>const ws=new WebSocket('ws://ssh-only.invalid:{websocket_port}/live');ws.binaryType='arraybuffer';"
                        "ws.onmessage=e=>{if(typeof e.data==='string'){"
                        "if(e.data.startsWith('ready:'))ws.send('hello λ 中');"
                        "else if(e.data==='hello λ 中'){document.querySelector('#result').textContent='WebSocket text verified';ws.send(new Uint8Array([0,255,42]));}"
                        "else if(e.data==='second')document.querySelector('#result').textContent='WebSocket update verified';"
                        "}else{const b=new Uint8Array(e.data);if(b.length===3&&b[0]===0&&b[1]===255&&b[2]===42){"
                        "document.querySelector('#result').textContent='WebSocket binary verified';document.title='SSH WebSocket';document.querySelector('#send').disabled=false;}}};"
                        "document.querySelector('#send').onclick=()=>ws.send('second');"
                        "ws.onerror=()=>{document.querySelector('#result').textContent='WebSocket failed'};</script>").encode()
                mime = "text/html; charset=utf-8"
            elif path == b"/api":
                body = nonce.encode()
                mime = "text/plain"
            else:
                body = ("<!doctype html><meta name='viewport' content='width=device-width,initial-scale=1'>"
                        "<title>SSH loading</title><style>body{background:#112e3c;color:white;font:24px sans-serif;padding:20px}</style>"
                        "<h1 id='result'>Loading through SSH</h1><a style='color:white' href='/next'>Next SSH page</a>"
                        "<script>fetch('/api').then(r=>r.text()).then(t=>{document.querySelector('#result').textContent='SSH route verified';"
                        "document.title=location.pathname==='/next'?'SSH next':'SSH routed fixture';"
                        "document.body.append(document.createTextNode(location.origin));document.cookie='ssh_browser=kept;path=/';})</script>").encode()
                mime = "text/html; charset=utf-8"
            writer.write((f"HTTP/1.1 200 OK\r\nContent-Type: {mime}\r\nContent-Length: {len(body)}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").encode() + body)
            await writer.drain()
        finally:
            writer.close()
            await writer.wait_closed()

    if args.websocket:
        from websockets.asyncio.server import serve
        async def websocket_peer(socket):
            websocket_state["active"] += 1; websocket_state["opened"] += 1
            try:
                await socket.send("ready:" + nonce)
                async for message in socket:
                    if isinstance(message, str): websocket_state["text"].append(message)
                    else: websocket_state["binary"].append(list(message))
                    await socket.send(message)
            finally:
                websocket_state["active"] -= 1
        websocket_server = await serve(websocket_peer, "127.0.0.1", 0, max_size=4096, compression=None, close_timeout=2)
        websocket_port = websocket_server.sockets[0].getsockname()[1]
    browser_server = await asyncio.start_server(browser_peer, "127.0.0.1", 0)
    browser_port = browser_server.sockets[0].getsockname()[1]
    silent = await asyncio.start_server(silent_peer, "127.0.0.1", 0)
    silent_port = silent.sockets[0].getsockname()[1]
    with tempfile.TemporaryDirectory(prefix="cmux-ssh-spike-") as directory:
        async with await asyncssh.create_server(
            Server, "127.0.0.1", 0, server_host_keys=[host_key],
            process_factory=process, encoding="utf-8", line_editor=False,
            sftp_factory=lambda chan: FixtureSFTP(chan, chroot=directory),
        ) as listener:
            port = listener.get_port()
            imports = []
            for algorithm in ("ssh-ed25519", "ecdsa-sha2-nistp256"):
                key = asyncssh.generate_private_key(algorithm)
                passphrase = secrets.token_hex(24)
                imports.append({"algorithm": algorithm, "passphrase": passphrase,
                                "privateKey": key.export_private_key(
                                    "openssh", passphrase=passphrase).decode()})
            fixture = {"username": username, "nonce": nonce,
                       "hostKey": host_key.export_public_key().decode().strip(),
                       "changedHostKey": asyncssh.generate_private_key("ssh-ed25519")
                           .export_public_key().decode().strip(),
                       "port": port, "silentPort": silent_port, "browserPort": browser_port, "imports": imports,
                       **({"websocketPort": websocket_port} if websocket_port else {})}
            (assets / "fixture.json").write_text(json.dumps(fixture))
            print(json.dumps({"ready": True, "port": port,
                              "assets": str(assets)}), flush=True)
            try:
                await asyncio.Event().wait()
            finally:
                if websocket_server:
                    websocket_server.close(); await websocket_server.wait_closed()
                browser_server.close()
                await browser_server.wait_closed()
                silent.close()
                await silent.wait_closed()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
