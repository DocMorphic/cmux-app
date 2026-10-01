#!/usr/bin/env python3
"""Loopback-only SSH -> real tmux fixture. Unique server; cat panes, empty HOME.
Requires the same AsyncSSH venv as ssh-engine-fixture.py. No host shell commands
are executed from SSH input: approved tmux argument vectors go to a private server.
"""
import argparse
import asyncio
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import signal
import tempfile
import uuid
import asyncssh


FORMATS = {
    "#{session_attached}:#{session_name}", "#{pid}", "#{window_id}", "#{session_name}", "#{pane_id}",
    "#{window_id} #{pane_id} #{pane_width}x#{pane_height}", "#{alternate_on}",
    "#{pid}:#{session_id}:#{session_created}:#{window_id}:#{window_index}:#{pane_id}:#{pane_index}:#{pane_width}:#{pane_height}:#{window_panes}:#{session_name}:#{window_name}",
    "cursor_x=#{cursor_x},cursor_y=#{cursor_y},scroll_region_upper=#{scroll_region_upper},scroll_region_lower=#{scroll_region_lower},cursor_flag=#{cursor_flag},insert_flag=#{insert_flag},keypad_cursor_flag=#{keypad_cursor_flag},keypad_flag=#{keypad_flag},wrap_flag=#{wrap_flag},origin_flag=#{origin_flag},bracket_paste_flag=#{bracket_paste_flag},pane_width=#{pane_width},pane_height=#{pane_height},mouse_all_flag=#{mouse_all_flag},mouse_button_flag=#{mouse_button_flag},mouse_standard_flag=#{mouse_standard_flag},mouse_sgr_flag=#{mouse_sgr_flag},mouse_utf8_flag=#{mouse_utf8_flag}",
}


def approved(args):
    if not args or any(any(c in a for c in "\n\r\0") or "#(" in a for a in args):
        return False
    if args == ["-V"]:
        return True
    if args[0] == "if-shell":
        if (len(args) == 7 and args[1:3] == ["-F", "-t"]
                and re.fullmatch(r"=cmux-[0-9a-f]{8}-cmux-android-[0-9a-f]{32}:", args[3])
                and args[4] == "#{==:#{session_attached},0}"
                and shlex.split(args[5]) == ["kill-session", "-t", args[3][:-1]]
                and args[6] == "display-message -p CMUX_GROUP_IN_USE"):
            return True
        return (len(args) == 7 and args[1:3] == ["-F", "-t"] and re.fullmatch(r"\$\d+(?::@\d+\.%\d+)?", args[3])
                and re.fullmatch(r"#\{&&:#\{==:#\{pid},\d+},#\{==:#\{session_created},\d+}}", args[4])
                and approved(shlex.split(args[5])) and args[6] == "display-message -p CMUX_STALE_TARGET")
    if args[0] == "set-option":
        return len(args) == 5 and args[1] == "-t" and "-cmux-android-" in args[2] and args[3:] == ["destroy-unattached", "off"]
    rules = {
        "list-sessions": ({}, {"-F": "format"}),
        "list-panes": ({"-a", "-s"}, {"-F": "format"}),
        "new-session": ({"-d"}, {"-s": "name", "-t": "target", "-e": "environment"}),
        "new-window": ({"-d"}, {"-t": "target", "-e": "environment"}),
        "split-window": ({"-d", "-h", "-v"}, {"-t": "target", "-e": "environment"}),
        "kill-session": ({}, {"-t": "target"}),
        "display-message": ({"-p"}, {"-t": "target", "-F": "format"}),
        "capture-pane": ({"-p", "-e"}, {"-S": "history", "-t": "target"}),
        "refresh-client": ({}, {"-C": "size", "-A": "pause"}),
    }
    if args[0] == "send-keys":
        return len(args) >= 5 and args[1] == "-t" and re.fullmatch(r"=cmux-[0-9a-f]{8}-cmux-android-[0-9a-f]{32}:@\d+\.%\d+", args[2]) and args[3] == "-H" and all(re.fullmatch(r"[0-9a-f]{2}", x) for x in args[4:])
    if args[0] not in rules:
        return False
    flags, values = rules[args[0]]
    patterns = {"name": r"(?:cmux-\d+|cmux-[0-9a-f]{8}-cmux-android-[0-9a-f]{32})",
                "target": r"(?:\\?\$\d+(?::@\d+\.%\d+|:)?|%\d+|=cmux-[0-9a-f]{8}-cmux-android-[0-9a-f]{32}(?::@\d+\.%\d+|:)?)",
                "history": r"-2000", "size": r"\d+x\d+", "pause": r"%\d+:(?:pause|continue)", "environment": r"COLORTERM=truecolor"}
    i = 1
    while i < len(args):
        value = args[i]
        if value in flags:
            i += 1
        elif value in values and i + 1 < len(args):
            kind = values[value]
            if not (args[i + 1] in FORMATS if kind == "format" else re.fullmatch(patterns[kind], args[i + 1])):
                return False
            i += 2
        elif args[0] == "display-message" and i == len(args) - 1 and value in FORMATS:
            i += 1
        else:
            return False
    return True


async def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tmux", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    binary = str(args.tmux.resolve(strict=True))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    user = "tmux-fixture-" + secrets.token_hex(12)
    key = asyncssh.generate_private_key("ssh-ed25519")
    socket = "cmux-android-ssh-test-" + uuid.uuid4().hex
    stop = asyncio.Event()
    for sig in (signal.SIGINT, signal.SIGTERM):
        asyncio.get_running_loop().add_signal_handler(sig, stop.set)
    with tempfile.TemporaryDirectory(prefix="cmux-tmux-ssh-") as temp:
        root = Path(temp)
        config = root / "tmux.conf"
        config.write_text("set -g default-shell /bin/sh\nset -g default-command /bin/cat\nset -g update-environment ''\n")
        prefix = [binary, "-L", socket, "-f", str(config)]
        environment = {"HOME": temp, "PATH": "/usr/bin:/bin", "TERM": "xterm-256color", "LC_ALL": "en_US.UTF-8"}
        async def launch(tokens):
            return await asyncio.create_subprocess_exec(*prefix, *tokens, cwd=temp, env=environment,
                stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
        async def run(tokens):
            proc = await launch(tokens)
            out, err = await proc.communicate()
            if proc.returncode:
                raise RuntimeError(err.decode())
            return out.decode().strip()
        class Server(asyncssh.SSHServer):
            def begin_auth(self, username): return True
            def public_key_auth_supported(self): return True
            def validate_public_key(self, username, public_key): return username == user
        children = set()
        async def reset():
            existing = await launch(["kill-server"])
            await existing.communicate()
            await run(["new-session", "-d", "-s", "desktop", "-x", "100", "-y", "30"])
            await run(["new-window", "-d", "-t", "=desktop:", "-n", "other"])
            pane = await run(["display-message", "-p", "-t", "=desktop:", "#{pane_id}"])
            seed = "\x1b[32mRemote λ 中\x1b[0m\x1b[?2004h\n".encode()
            await run(["send-keys", "-t", pane, "-H", *[f"{b:02x}" for b in seed]])
        async def handle(proc):
            child = None
            try:
                tokens = shlex.split(proc.command or "")
                if tokens == ["fixture-reset"]:
                    await reset(); proc.exit(0); return
                if len(tokens) == 3 and tokens[:2] == ["sh", "-c"] and tokens[2].startswith('for p in "$(command -v tmux 2>/dev/null)"'):
                    proc.stdout.write("/fixture/tmux\n"); proc.exit(0); return
                if not tokens or tokens.pop(0) != "/fixture/tmux" or proc.term_type:
                    raise ValueError("Unexpected command or PTY")
                control = tokens[:1] == ["-C"]
                if control:
                    tokens.pop(0)
                    delimiter = tokens.index(";")
                    if not approved(tokens[:delimiter]) or not approved(tokens[delimiter + 1:]): raise ValueError("Invalid control startup")
                    tokens.insert(0, "-C")
                elif not approved(tokens):
                    raise ValueError("Command refused")
                child = await launch(tokens); children.add(child)
                if os.environ.get("CMUX_SSH_TRACE") == "1":
                    print(json.dumps({"started": tokens[0], "control": control}), flush=True)
                async def forward(source, target):
                    while True:
                        data = await source.read(8192)
                        if not data: break
                        target.write(data.decode("utf-8"))
                # Control output is ASCII framing plus UTF-8. Incremental decoder
                # retains any multi-byte character split by a local pipe read.
                async def stdout():
                    import codecs
                    decoder = codecs.getincrementaldecoder("utf-8")()
                    while True:
                        data = await child.stdout.read(8192)
                        if not data: break
                        proc.stdout.write(decoder.decode(data))
                    proc.stdout.write(decoder.decode(b"", final=True))
                async def input_stream():
                    if not control: return
                    try:
                        while True:
                            line = await proc.stdin.readline()
                            if not line: break
                            if not approved(shlex.split(line.rstrip("\r\n"))): raise ValueError("Control command refused")
                            child.stdin.write(line.encode()); await child.stdin.drain()
                    finally:
                        child.stdin.close()
                tasks = [asyncio.create_task(stdout()), asyncio.create_task(forward(child.stderr, proc.stderr)), asyncio.create_task(input_stream())]
                try:
                    await child.wait()
                    await asyncio.gather(*tasks[:2])
                    if os.environ.get("CMUX_SSH_TRACE") == "1":
                        print(json.dumps({"finished": tokens[0], "status": child.returncode}), flush=True)
                    proc.exit(child.returncode)
                finally:
                    for task in tasks: task.cancel()
                    await asyncio.gather(*tasks, return_exceptions=True)
            except Exception as exc:
                print(json.dumps({"fixtureError": type(exc).__name__, "detail": str(exc)}), flush=True)
                proc.stderr.write("Private tmux fixture refused request\n"); proc.exit(127)
            finally:
                if child:
                    if child.returncode is None: child.terminate(); await child.wait()
                    children.discard(child)
        try:
            await reset()
            listener = await asyncssh.create_server(Server, "127.0.0.1", 0, server_host_keys=[key], process_factory=handle, encoding="utf-8")
            metadata = {"port": listener.get_port(), "username": user, "hostKey": key.export_public_key().decode().strip(), "nonce": "tmux", "silentPort": 0}
            args.output.write_text(json.dumps(metadata) + "\n")
            print(json.dumps({"ready": True, "port": listener.get_port(), "tmux": await run(["-V"])}), flush=True)
            await stop.wait()
            listener.close(); await listener.wait_closed()
        finally:
            for child in list(children):
                if child.returncode is None: child.terminate()
            cleanup = await launch(["kill-server"])
            await cleanup.communicate()


if __name__ == "__main__":
    asyncio.run(main())
