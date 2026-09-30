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

    class Server(asyncssh.SSHServer):
        def begin_auth(self, user):
            return True

        def public_key_auth_supported(self):
            return True

        def validate_public_key(self, user, key):
            # AsyncSSH still verifies proof of private-key possession.
            return user == username

        def connection_requested(self, dest_host, dest_port, orig_host, orig_port):
            return dest_host == "127.0.0.1" and dest_port == port

    async def process(proc):
        if proc.command == "probe":
            proc.stdout.write(nonce + "\n")
            proc.exit(0)
        elif proc.command == "wait":
            await proc.stdin.read()
            proc.exit(0)
        elif proc.command is None:
            proc.stdout.write("SIZE %d %d\n" % proc.term_size[:2])
            while True:
                try:
                    line = await proc.stdin.readline()
                    if not line:
                        break
                    proc.stdout.write("ECHO " + line)
                except asyncssh.TerminalSizeChanged as event:
                    proc.stdout.write("SIZE %d %d\n" % (event.width, event.height))
            proc.exit(0)
        else:
            proc.stderr.write("fixture command refused\n")
            proc.exit(127)

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
                       "port": port, "imports": imports}
            (assets / "fixture.json").write_text(json.dumps(fixture))
            print(json.dumps({"ready": True, "port": port,
                              "assets": str(assets)}), flush=True)
            await asyncio.Event().wait()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
