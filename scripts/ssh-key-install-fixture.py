#!/usr/bin/env python3
"""Disposable loopback SSH key-install server; only a fixed install script may run.
Uses the existing AsyncSSH venv. Never touches the user's HOME or host SSH daemon.
"""
import argparse
import asyncio
import json
import os
from pathlib import Path
import secrets
import shlex
import signal
import stat
import tempfile
import asyncssh

SCRIPT = ('umask 077; IFS= read -r key || exit 64; d="$HOME/.ssh"; f="$d/authorized_keys"; '
          'mkdir -p "$d" && touch "$f" && chmod 700 "$d" && chmod 600 "$f" && '
          '{ grep -qxF "$key" "$f" || printf \'\\n%s\\n\' "$key" >> "$f"; }')

async def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    os.umask(0o077)
    stop = asyncio.Event()
    for sig in (signal.SIGINT, signal.SIGTERM):
        asyncio.get_running_loop().add_signal_handler(sig, stop.set)
    base = 'install-' + secrets.token_hex(10)
    password = 'setup-' + base  # Disposable fixture value, never a real account credential.
    target_key = asyncssh.generate_private_key('ssh-ed25519')
    jump_key = asyncssh.generate_private_key('ssh-ed25519')
    existing_key = asyncssh.generate_private_key('ssh-ed25519').export_public_key().decode().strip()
    servers, connections, tasks = [], set(), set()
    counters = {}
    jump_passwords = 0
    with tempfile.TemporaryDirectory(prefix='cmux-key-install-') as temp:
        root = Path(temp)
        def user_valid(user):
            return user.startswith(base + '-') and len(user) <= 120 and all(c.isalnum() or c == '-' for c in user)
        def home(user):
            if not user_valid(user): raise ValueError('Invalid fixture user')
            path = root / user
            if not path.exists():
                (path / '.ssh').mkdir(parents=True)
                (path / '.ssh/authorized_keys').write_text('# Existing fixture entry\n' + existing_key)
                (path / '.ssh').chmod(0o755)
                (path / '.ssh/authorized_keys').chmod(0o644)
            return path
        def stats(user):
            path = home(user) / '.ssh/authorized_keys'
            result = dict(counters.get(user, {}))
            result.update(lines=len([s for s in path.read_text().splitlines() if s and not s.startswith('#')]),
                          existing=existing_key in path.read_text(),
                          directoryMode=stat.S_IMODE(path.parent.stat().st_mode),
                          fileMode=stat.S_IMODE(path.stat().st_mode), jumpPasswords=jump_passwords)
            return result
        def count(user, field):
            row = counters.setdefault(user, {})
            row[field] = row.get(field, 0) + 1
        class Server(asyncssh.SSHServer):
            def __init__(self, jump=False): self.jump = jump
            def connection_made(self, conn): self.conn = conn; connections.add(conn)
            def connection_lost(self, error): connections.discard(self.conn)
            def begin_auth(self, user): return True
            def password_auth_supported(self): return not self.jump
            def public_key_auth_supported(self): return True
            def validate_password(self, user, value):
                nonlocal jump_passwords
                if self.jump: jump_passwords += 1; return False
                if not user_valid(user): return False
                count(user, 'passwords')
                return secrets.compare_digest(value, password)
            def validate_public_key(self, user, key):
                if self.jump: return user == base
                if not user_valid(user): return False
                count(user, 'publicKeys')
                return not user.endswith('-denyverify') and key.export_public_key().decode().strip() in (home(user) / '.ssh/authorized_keys').read_text().splitlines()
            def connection_requested(self, host, port, origin_host, origin_port):
                return self.jump and host == '127.0.0.1' and port == target_port
        async def process(proc):
            task = asyncio.current_task(); tasks.add(task)
            try:
                user = proc.get_extra_info('username')
                tokens = shlex.split(proc.command or '')
                if user == base and len(tokens) == 2 and tokens[0] == 'fixture-status' and user_valid(tokens[1]):
                    proc.stdout.write(json.dumps(stats(tokens[1]))); proc.exit(0); return
                if not user_valid(user) or tokens != ['sh', '-c', SCRIPT]: proc.exit(126); return
                count(user, 'commands')
                line = await asyncio.wait_for(proc.stdin.readline(), 10)
                if len(line) > 16_384 or not line.endswith('\n'): proc.exit(64); return
                asyncssh.import_public_key(line)
                if user.endswith('-writefail'):
                    proc.stderr.write('private fixture error ' + password); proc.exit(1); return
                child = await asyncio.create_subprocess_exec('/bin/sh', '-c', SCRIPT,
                    cwd=home(user), env={'HOME': str(home(user)), 'PATH': '/usr/bin:/bin'},
                    stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
                out, err = await child.communicate(line.encode())
                if user.endswith('-lostreply'):
                    proc.get_extra_info('connection').abort(); return
                proc.stdout.write(out.decode()); proc.stderr.write(err.decode()); proc.exit(child.returncode)
            except (asyncio.TimeoutError, asyncssh.Error, ValueError, OSError):
                proc.exit(1)
            finally: tasks.discard(task)
        try:
            target = await asyncssh.create_server(Server, '127.0.0.1', 0, server_host_keys=[target_key], process_factory=process)
            servers.append(target); target_port = target.get_port()
            jump = await asyncssh.create_server(lambda: Server(True), '127.0.0.1', 0, server_host_keys=[jump_key], process_factory=process)
            servers.append(jump)
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps({'port': target_port, 'controlPort': jump.get_port(), 'username': base,
                'hostKey': target_key.export_public_key().decode().strip(), 'controlHostKey': jump_key.export_public_key().decode().strip(),
                'nonce': 'key-install', 'silentPort': 0}))
            await stop.wait()
        finally:
            for server in servers: server.close()
            for connection in list(connections): connection.abort()
            for task in list(tasks): task.cancel()
            await asyncio.gather(*(server.wait_closed() for server in servers), return_exceptions=True)
            await asyncio.gather(*list(tasks), return_exceptions=True)

if __name__ == '__main__': asyncio.run(main())
