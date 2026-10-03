"""Exact installer command allowlist and SFTP path confinement for the SSH fixture."""
import os
from pathlib import Path
import re
import asyncssh


def quote(value):
    return "'" + value.replace("'", "'\\''") + "'"


def prepare(token):
    return ('set -eu; umask 077\n'
            'b="$HOME/.local/bin"; mkdir -p "$b"\n'
            f'mktemp -d "$b/.cmux-android-install-{token}.XXXXXXXX"')


def prefix(stage):
    return f'set -eu; d={quote(str(stage))}; [ -d "$d" ] && [ ! -L "$d" ]; '


def extract(stage):
    return prefix(stage) + ('tar -xzf "$d/release.tgz" -C "$d" package/bin/cmux-tui\n'
                           'p="$d/package/bin/cmux-tui"\n'
                           '[ -f "$p" ] && [ ! -L "$p" ]; chmod 755 "$p"\n'
                           '"$p" remote-probe --json')


def commit(stage):
    return prefix(stage) + ('p="$d/package/bin/cmux-tui"; b="${d%/*}/cmux-tui"\n'
                           '[ -f "$p" ] && [ ! -L "$p" ] || exit 74\n'
                           '[ ! -e "$b" ] && [ ! -L "$b" ] || exit 73\n'
                           'ln "$p" "$b" || exit 73\n'
                           'printf \'%s\\n\' "$b"')


def cleanup(stage):
    return prefix(stage) + 'rm -rf -- "$d"'


class InstallFixture:
    def __init__(self, root, spawn):
        self.root = root
        self.spawn = spawn
        self.stages = set()
        self.prepared = 0
        self.activated = 0

    async def command(self, script):
        match = re.search(r'\.cmux-android-install-([0-9a-f-]{36})\.XXXXXXXX', script)
        is_prepare = bool(match and script == prepare(match[1]))
        is_commit = any(script == commit(stage) for stage in self.stages)
        if not is_prepare and not any(script in (extract(stage), commit(stage), cleanup(stage)) for stage in self.stages):
            return None
        process = await self.spawn('/bin/sh', '-c', script)
        import asyncio
        out, _ = await asyncio.wait_for(process.communicate(), 30)
        if is_prepare and process.returncode == 0:
            stage = Path(out.decode().strip())
            assert stage.parent == self.root / '.local/bin' and stage.is_dir() and not stage.is_symlink()
            self.stages.add(stage)
            self.prepared += 1
        if is_commit and process.returncode == 0:
            self.activated += 1
        return out.decode(), process.returncode

    def sftp(self, channel):
        fixture = self
        class Confined(asyncssh.SFTPServer):
            def map_path(self, path):
                raw = os.fsdecode(path)
                if raw in ('', '.', str(fixture.root)):
                    return os.fsencode(fixture.root)
                candidate = Path(os.path.normpath(raw))
                allowed = candidate in fixture.stages or any(candidate == stage / 'release.tgz' for stage in fixture.stages)
                if not allowed or candidate.is_symlink():
                    raise asyncssh.SFTPPermissionDenied('Path outside private install fixture')
                return os.fsencode(candidate)
        return Confined(channel)
