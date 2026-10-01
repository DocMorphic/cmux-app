#!/usr/bin/env python3
"""Isolated loopback SSH fixture. No real shell, user credentials or host sshd.

Start before building the opt-in ssh-spike APK; generated import keys go only
into that APK's test assets. Requires asyncssh[bcrypt]==2.24.0. Stop with Ctrl-C.
The fixture accepts valid public-key signatures for a random test username; its
only commands are synthetic, forwarding targets itself, and SFTP is chrooted to
a temporary directory. Never expose its port outside loopback.
"""
import asyncio
import json
import os
from pathlib import Path
import secrets
import tempfile

import asyncssh


async def main():
    os.umask(0o077)
    root = Path(__file__).resolve().parents[1]
    assets = root / "ssh-spike/build/fixture-assets"
    assets.mkdir(parents=True, exist_ok=True)
    username = "spike-" + secrets.token_hex(12)
    host_key = asyncssh.generate_private_key("ssh-ed25519")
    nonce = secrets.token_hex(16)
    port = 0
    silent_port = 0
    active_shells = 0

    def trace(event):
        if os.environ.get("CMUX_SSH_TRACE") == "1":
            print(json.dumps({"fixture_event": event, "active_shells": active_shells}), flush=True)

    class Server(asyncssh.SSHServer):
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
            return dest_host == "127.0.0.1" and dest_port in (port, silent_port)

    async def process(proc):
        nonlocal active_shells
        if proc.command == "shell-count":
            trace("count_requested")
            proc.stdout.write(str(active_shells) + "\n")
            proc.exit(0)
        elif proc.command == "probe":
            proc.stdout.write(nonce + "\n")
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

    silent = await asyncio.start_server(silent_peer, "127.0.0.1", 0)
    silent_port = silent.sockets[0].getsockname()[1]
    with tempfile.TemporaryDirectory(prefix="cmux-ssh-spike-") as directory:
        async with await asyncssh.create_server(
            Server, "127.0.0.1", 0, server_host_keys=[host_key],
            process_factory=process, encoding="utf-8", line_editor=False,
            sftp_factory=lambda chan: asyncssh.SFTPServer(chan, chroot=directory),
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
                       "port": port, "silentPort": silent_port, "imports": imports}
            (assets / "fixture.json").write_text(json.dumps(fixture))
            print(json.dumps({"ready": True, "port": port,
                              "assets": str(assets)}), flush=True)
            try:
                await asyncio.Event().wait()
            finally:
                silent.close()
                await silent.wait_closed()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
