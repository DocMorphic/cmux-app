#!/usr/bin/env python3
"""Capture a local Vim PTY session for deterministic parser regression checks."""
import base64
import fcntl
import json
import os
import pathlib
import pty
import select
import signal
import struct
import subprocess
import tempfile
import termios
import time

ROWS, COLUMNS = 12, 48

def collect(fd, timeout=3):
    result = bytearray()
    deadline = time.monotonic() + timeout
    quiet = None
    while time.monotonic() < deadline:
        ready, _, _ = select.select([fd], [], [], 0.05)
        if ready:
            try:
                chunk = os.read(fd, 65536)
            except OSError:
                break
            if not chunk:
                break
            result.extend(chunk)
            quiet = time.monotonic()
        elif quiet is not None and time.monotonic() - quiet > 0.3:
            break
    return base64.b64encode(result).decode('ascii')

with tempfile.TemporaryDirectory(prefix='cmux-vt-fixture-') as directory:
    pathlib.Path(directory, 'fixture.txt').write_text('cmux terminal fixture\nUnicode: cafe\u0301 日本語\nthird line\n')
    master, slave = pty.openpty()
    fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack('HHHH', ROWS, COLUMNS, 0, 0))
    env = dict(os.environ, TERM='xterm-256color', LANG='en_US.UTF-8')
    env.pop('VIMINIT', None)
    env.pop('EXINIT', None)
    process = subprocess.Popen(['/usr/bin/vim', '-Nu', 'NONE', '-n', '-i', 'NONE', '-N',
                                '+set noswapfile', '+set encoding=utf-8', 'fixture.txt'],
                               cwd=directory, env=env, stdin=slave, stdout=slave, stderr=slave,
                               start_new_session=True)
    os.close(slave)
    try:
        opened = collect(master)
        os.write(master, b'gg0iEdited ')
        edited = collect(master)
        os.write(master, b'\x1b:q!\r')
        exited = collect(master)
        process.wait(timeout=5)
        assert process.returncode == 0
    finally:
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
            process.wait(timeout=5)
        os.close(master)

version = subprocess.check_output(['/usr/bin/vim', '--version'], text=True).splitlines()[0]
fixture = {'program': version, 'columns': COLUMNS, 'rows': ROWS,
           'opened_b64': opened, 'edited_b64': edited, 'exited_b64': exited}
path = pathlib.Path(__file__).resolve().parents[1] / 'app/src/test/resources/terminal/vim-session.json'
path.write_text(json.dumps(fixture, indent=2) + '\n')
print('Captured Vim open/edit/exit bytes:', [len(base64.b64decode(x)) for x in (opened, edited, exited)])
