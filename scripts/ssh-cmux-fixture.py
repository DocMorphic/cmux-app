#!/usr/bin/env python3
"""Loopback SSH fixture with private real cmux-tui/tmux owners and cat terminals.
No SSH-supplied shell command is executed. Discovery is served from the fixture's
private paths, and relay commands are restricted to the app's tested operations.
"""
import argparse
import asyncio
import base64
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shlex
import shutil
import signal
import tempfile
import uuid
import asyncssh

spec = importlib.util.spec_from_file_location("tmux_fixture", Path(__file__).with_name("ssh-tmux-fixture.py"))
tmux_fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tmux_fixture)


async def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cmux-tui", type=Path, required=True)
    parser.add_argument("--tmux", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--install", action="store_true", help="Require the phone to install into the private HOME over SFTP")
    parser.add_argument("--chrome", type=Path, help="Opt-in private real Chrome browser fixture")
    args = parser.parse_args()
    if args.chrome and args.install: parser.error("Choose browser or installation fixture")
    binary = str(args.cmux_tui.resolve(strict=True)); tmux = str(args.tmux.resolve(strict=True))
    root = Path(tempfile.mkdtemp(prefix="cs-", dir="/tmp"))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    session = "fixture"
    tmux_socket = "cmux-app-fixture-" + uuid.uuid4().hex
    shell = root / "fixture-shell"
    shell.write_text("#!/bin/sh\nexec /bin/cat\n"); shell.chmod(0o700)
    config = root / "config.json"; config.write_text("{}")
    tmux_config = root / "tmux.conf"
    tmux_config.write_text("set -g default-shell /bin/sh\nset -g default-command /bin/cat\nset -g update-environment ''\n")
    env = {"HOME": str(root), "PATH": "/usr/bin:/bin", "SHELL": str(shell), "TERM": "xterm-256color",
           "LANG": "en_US.UTF-8", "CMUX_TUI_CONFIG": str(config)}
    for key, folder in {"XDG_RUNTIME_DIR": "run", "XDG_STATE_HOME": "state", "XDG_CONFIG_HOME": "config",
                        "XDG_DATA_HOME": "data", "TMPDIR": "tmp"}.items():
        (root / folder).mkdir(); env[key] = str(root / folder)
    stop = asyncio.Event()
    for sig in (signal.SIGINT, signal.SIGTERM): asyncio.get_running_loop().add_signal_handler(sig, stop.set)
    stderr = (root / "stderr.txt").open("ab")
    async def spawn(*tokens, forward_stderr=False):
        return await asyncio.create_subprocess_exec(*tokens, cwd=root, env=env, stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE if forward_stderr else stderr, limit=16 * 1024 * 1024)
    install_spec = importlib.util.spec_from_file_location("install_fixture", Path(__file__).with_name("ssh-cmux-install-fixture.py"))
    install_module = importlib.util.module_from_spec(install_spec)
    install_spec.loader.exec_module(install_module)
    install = install_module.InstallFixture(root, spawn)
    exposed_binary = str(root / ".local/bin/cmux-tui") if args.install else "/fixture/cmux-tui"
    async def cli(*tokens):
        proc = await spawn(binary, *tokens)
        try: out, _ = await asyncio.wait_for(proc.communicate(), 10)
        except BaseException: proc.kill(); await proc.wait(); raise
        if proc.returncode: raise RuntimeError(f"Private cmux-tui command failed; retained {root}: {out.decode()}")
        return json.loads(out)
    async def tmux_run(*tokens, allow_failure=False):
        proc = await spawn(tmux, "-L", tmux_socket, "-f", str(tmux_config), *tokens)
        out, _ = await asyncio.wait_for(proc.communicate(), 10)
        if proc.returncode and not allow_failure: raise RuntimeError("Private tmux operation failed")
        return out.decode().strip()
    class Wire:
        def __init__(self, proc): self.proc = proc; self.sequence = 0
        async def request(self, cmd=None, params=None, operation=None):
            self.sequence += 1; rid = f"fixture-{self.sequence}"
            value = {"id": rid, "cmd": cmd, **(params or {})} if cmd else {
                "id": rid, "protocol": "cmux.protocol/2", "type": "request", "operation": operation,
                "params": params or {}, **({"idempotency_key": str(uuid.uuid4())} if operation == "terminal.close" else {})}
            self.proc.stdin.write((json.dumps(value) + "\n").encode()); await self.proc.stdin.drain()
            while True:
                line = await asyncio.wait_for(self.proc.stdout.readline(), 10)
                if not line: raise RuntimeError("Private relay closed")
                result = json.loads(line)
                if result.get("id") != rid: continue
                if result.get("ok") is not True: raise RuntimeError(f"Private relay rejected {cmd or operation}: {result}")
                return result.get("result" if operation else "data", {})
        async def close(self):
            self.proc.stdin.close()
            try: await asyncio.wait_for(self.proc.wait(), 3)
            except asyncio.TimeoutError: self.proc.terminate(); await self.proc.wait()
    async def wire(owner=session): return Wire(await spawn(binary, "relay", "--session", owner))
    def tabs(tree):
        return [tab for ws in tree["workspaces"] for screen in ws.get("screens", [])
                for pane in screen.get("panes", []) for tab in pane.get("tabs", [])]
    async def clear(control, owner=session):
        machines = await control.request(operation="machine.list")
        assert len(machines) == 1
        machine = machines[0]["id"]
        sessions = await control.request(operation="session.list", params={"machine": machine})
        matching = [s for s in sessions if s.get("name") == owner]; assert len(matching) == 1
        scope = {"machine": machine, "session": matching[0]["id"]}
        # Resource inventory also includes terminals with no remaining view.
        terminals = await control.request(operation="terminal.list", params=scope)
        assert isinstance(terminals, list), terminals
        for terminal in terminals:
            if terminal.get("lifecycle") == "tombstoned": continue
            await control.request(operation="terminal.close", params={**scope, "terminal": terminal["id"]})
        tree = await control.request("list-workspaces")
        for workspace in tree["workspaces"]: await control.request("close-workspace", {"key": workspace["key"]})
    browser = None
    async def reset():
        if browser: await browser.detach()
        await cli("server", "ensure", "--session", session, "--json")
        control = await wire()
        try:
            await clear(control)
            key = str(uuid.uuid4())
            await control.request("create-workspace", {"key": key, "name": "Desktop cmux"})
            created = await control.request("create-terminal", {"key": key, "argv": ["/bin/cat"], "cols": 100, "rows": 30})
            await control.request("send", {"surface": created["surface"], "bytes": base64.b64encode("\x1b[32mRemote cmux λ 中\x1b[0m\x1b[?2004h\n".encode()).decode()})
        finally: await control.close()
        if browser: await browser.reset(wire)
        if "cmux-android" in owned_sessions:
            await cli("server", "ensure", "--session", "cmux-android", "--json")
            phone = await wire("cmux-android")
            try: await clear(phone, "cmux-android")
            finally: await phone.close()
        await tmux_run("kill-server", allow_failure=True)
        await tmux_run("new-session", "-d", "-s", "desktop-tmux", "-x", "100", "-y", "30")
        await tmux_run("send-keys", "-t", "=desktop-tmux:", "Remote tmux", "Enter")
    children = set(); listener = None
    owned_sessions = {session}
    phone_ensures = 0
    key = asyncssh.generate_private_key("ssh-ed25519")
    user = "cmux-fixture-" + secrets.token_hex(12)
    class Server(asyncssh.SSHServer):
        def connection_requested(self, dest_host, dest_port, orig_host, orig_port):
            return bool(browser and dest_host in ("127.0.0.1", "localhost") and dest_port == browser.port)
        def begin_auth(self, username): return True
        def public_key_auth_supported(self): return True
        def validate_public_key(self, username, public_key): return username == user
    allowed = {"identify", "set-client-info", "subscribe", "list-workspaces", "create-workspace", "create-terminal", "close-workspace",
               "new-screen", "new-tab", "split", "attach-surface", "set-client-sizing", "send", "resize-attached-view", "resize-surface", "release-attached-view-size", "detach-attached-view"}
    if args.chrome:
        allowed.update({"get-cell-pixels", "browser-frame-presented", "browser-mouse-guarded", "browser-wheel-guarded",
            "browser-insert-text", "browser-key-press", "browser-navigate", "browser-back", "browser-forward", "browser-reload"})
    def relay_line(line):
        if len(line.encode()) > 1024 * 1024: raise ValueError("Oversized fixture request")
        value = json.loads(line)
        if "cmd" in value:
            if value["cmd"] not in allowed: raise ValueError("Fixture command refused")
            if value["cmd"] == "create-terminal" and ("command" in value or value.get("argv") not in (None, ["/bin/cat"])):
                raise ValueError("Fixture permits cat only")
        elif value.get("protocol") != "cmux.protocol/2" or value.get("operation") not in {"machine.list", "session.list", "terminal.close"}:
            raise ValueError("Fixture resource operation refused")
        return line.encode()
    async def handle(proc):
        nonlocal phone_ensures
        child = None
        try:
            tokens = shlex.split(proc.command or "")
            if tokens == ["fixture-browser-status"] and browser:
                proc.stdout.write(json.dumps({"events": browser.events, "registered": browser.provider is not None,
                    "registrations": browser.registrations, "target": browser.target,
                    "chromePid": browser.chrome.pid, "chromeExited": browser.chrome.returncode is not None,
                    "replyLossArmed": browser.reply_loss_armed, "replyLosses": browser.reply_losses,
                    "mouseReleases": browser.mouse_releases})); proc.exit(0); return
            if tokens == ["fixture-browser-drop-next-click-reply"] and browser:
                browser.reply_loss_armed = True; proc.exit(0); return
            if tokens == ["fixture-restart-browser-owner"] and browser:
                await cli("server", "ensure", "--session", session, "--json")
                await browser.reconnect(wire); proc.exit(0); return
            if tokens == ["fixture-browser-process-stop"] and browser:
                await browser.stop_chrome(); proc.exit(0); return
            if tokens == ["fixture-browser-process-restart"] and browser:
                await browser.restart_chrome(wire); proc.exit(0); return
            if tokens == ["fixture-browser-provider-detach"] and browser:
                await browser.detach(); proc.exit(0); return
            if tokens == ["fixture-browser-provider-reconnect"] and browser:
                await browser.reconnect(wire); proc.exit(0); return
            if tokens == ["fixture-owner-status"]:
                proc.stdout.write(json.dumps({"phone": socket.with_name("cmux-android.sock").is_socket(),
                    "desktop": socket.is_socket(), "phoneEnsures": phone_ensures})); proc.exit(0); return
            if tokens == ["fixture-install-status"]:
                proc.stdout.write(json.dumps({"prepared": install.prepared, "activated": install.activated,
                    "stages": sum(p.exists() for p in install.stages)})); proc.exit(0); return
            if tokens in (["fixture-stop-phone-owner"], ["fixture-stop-desktop-owner"]):
                owner = "cmux-android" if tokens[0] == "fixture-stop-phone-owner" else session
                if owner not in owned_sessions: raise ValueError("Owner not created")
                await cli("server", "stop", "--session", owner, "--json")
                proc.exit(0); return
            if tokens == ["fixture-reset"]:
                await reset(); proc.exit(0); return
            if len(tokens) == 3 and tokens[:2] == ["sh", "-c"]:
                script = tokens[2]
                if args.install:
                    result = await install.command(script)
                    if result is not None:
                        proc.stdout.write(result[0]); proc.exit(result[1]); return
                if script == "uname -s && uname -m":
                    proc.stdout.write("Darwin\narm64\n"); proc.exit(0); return
                if script.startswith('for p in "$HOME/.local/bin/cmux-tui"'):
                    if args.install and not Path(exposed_binary).is_file():
                        proc.exit(1); return
                    proc.stdout.write(exposed_binary + "\n"); proc.exit(0); return
                if script.startswith('for p in "$(command -v tmux 2>/dev/null)"'):
                    proc.stdout.write("/fixture/tmux\n"); proc.exit(0); return
                if script.startswith("u=$(id -u)"):
                    for owner in sorted(owned_sessions):
                        path = socket.with_name(owner + ".sock")
                        if path.is_socket(): proc.stdout.write(str(path) + "\n")
                    proc.exit(0); return
                if script == f"{install_module.quote(exposed_binary)} server ensure --session cmux-android --json >&2 && exec {install_module.quote(exposed_binary)} relay --session cmux-android":
                    if args.install:
                        start = await spawn(exposed_binary, "server", "ensure", "--session", "cmux-android", "--json")
                        await asyncio.wait_for(start.communicate(), 10)
                        if start.returncode: raise ValueError("Installed owner failed to start")
                    else: await cli("server", "ensure", "--session", "cmux-android", "--json")
                    owned_sessions.add("cmux-android"); phone_ensures += 1
                    tokens = ["exec", exposed_binary, "relay", "--socket", str(socket.with_name("cmux-android.sock"))]
            kind = "shell"
            if not tokens and proc.term_type:
                child = await spawn("/bin/cat")
                proc.stdout.write("Plain shell fixture λ 中\r\n")
            elif len(tokens) == 5 and tokens[:4] == ["exec", exposed_binary, "relay", "--socket"] and tokens[4] in {str(socket.with_name(owner + ".sock")) for owner in owned_sessions} and not proc.term_type:
                kind = "cmux"; child = await spawn(exposed_binary if args.install else binary, "relay", "--socket", tokens[4])
            elif tokens and tokens[0] == "/fixture/tmux" and not proc.term_type:
                kind = "tmux"; tmux_args = tokens[1:]
                if tmux_args[:1] == ["-C"]:
                    delimiter = tmux_args.index(";")
                    if not tmux_fixture.approved(tmux_args[1:delimiter]) or not tmux_fixture.approved(tmux_args[delimiter+1:]): raise ValueError("Invalid tmux control startup")
                elif not tmux_fixture.approved(tmux_args): raise ValueError("Invalid tmux operation")
                child = await spawn(tmux, "-L", tmux_socket, "-f", str(tmux_config), *tmux_args, forward_stderr=True)
            else: raise ValueError("Unexpected fixture exec or PTY")
            children.add(child)
            if os.environ.get("CMUX_SSH_TRACE") == "1": print(json.dumps({"started": kind}), flush=True)
            drop_reply = None
            async def output():
                if browser and kind == "cmux":
                    # Fault injection belongs only to this private Chrome fixture.
                    # Deliver the real command to cmux-tui, consume its successful
                    # response, then end this Android relay without forwarding it.
                    while data := await child.stdout.readline():
                        value = json.loads(data)
                        if drop_reply is not None and value.get("id") == drop_reply:
                            if value.get("ok") is not True:
                                raise RuntimeError("Cannot inject reply loss after a rejected browser click")
                            browser.reply_losses += 1
                            child.stdin.close()
                            return
                        proc.stdout.write(data.decode("utf-8"))
                    return
                import codecs
                decoder = codecs.getincrementaldecoder("utf-8")()
                while data := await child.stdout.read(8192): proc.stdout.write(decoder.decode(data))
                proc.stdout.write(decoder.decode(b"", final=True))
            async def input_stream():
                nonlocal drop_reply
                try:
                    while True:
                        try:
                            data = await (proc.stdin.read(8192) if kind == "shell" else proc.stdin.readline())
                        except asyncssh.TerminalSizeChanged:
                            if kind != "shell": raise
                            # This mixed-navigation fixture uses cat pipes. The
                            # dedicated PTY fixture verifies actual OS resizing.
                            continue
                        if not data: break
                        if kind == "cmux":
                            encoded = relay_line(data)
                            if browser:
                                value = json.loads(data)
                                if value.get("cmd") == "browser-mouse-guarded" and value.get("kind") == "up":
                                    browser.mouse_releases += 1
                                    if browser.reply_loss_armed:
                                        browser.reply_loss_armed = False
                                        drop_reply = value["id"]
                        else:
                            if kind == "tmux" and not tmux_fixture.approved(shlex.split(data.rstrip("\r\n"))): raise ValueError("tmux control command refused")
                            encoded = data.encode()
                        child.stdin.write(encoded); await child.stdin.drain()
                finally: child.stdin.close()
            async def error_output():
                # Real SSH exec returns stderr to the caller. In particular,
                # tmux's last-session exit must expose its no-server diagnostic
                # so discovery can distinguish an empty server from a failure.
                if child.stderr is None: return
                import codecs
                decoder = codecs.getincrementaldecoder("utf-8")(errors="replace")
                while data := await child.stderr.read(8192):
                    stderr.write(data); stderr.flush()
                    proc.stderr.write(decoder.decode(data))
                proc.stderr.write(decoder.decode(b"", final=True))
            tasks = [asyncio.create_task(output()), asyncio.create_task(input_stream()), asyncio.create_task(error_output())]
            try:
                await child.wait(); await asyncio.gather(tasks[0], tasks[2]); proc.exit(child.returncode)
            finally:
                for task in tasks: task.cancel()
                await asyncio.gather(*tasks, return_exceptions=True)
        except Exception as exc:
            print(json.dumps({"fixtureError": type(exc).__name__, "detail": str(exc)}), flush=True)
            proc.stderr.write("Private SSH fixture refused request\n"); proc.exit(127)
        finally:
            if child:
                if child.returncode is None: child.terminate(); await child.wait()
                children.discard(child)
    try:
        await cli("server", "ensure", "--session", session, "--json")
        socket = root / "run" / f"cmux-tui-{os.getuid()}" / f"{session}.sock"
        assert socket.is_socket(), socket
        if args.chrome:
            browser_spec = importlib.util.spec_from_file_location("browser_fixture", Path(__file__).with_name("ssh-cmux-browser-fixture.py"))
            browser_module = importlib.util.module_from_spec(browser_spec); browser_spec.loader.exec_module(browser_module)
            browser = browser_module.BrowserFixture(args.chrome.resolve(strict=True), root, spawn)
            await browser.start()
        await reset()
        listener = await asyncssh.create_server(Server, "127.0.0.1", 0, server_host_keys=[key], process_factory=handle, sftp_factory=install.sftp if args.install else None, encoding="utf-8")
        args.output.write_text(json.dumps({"port": listener.get_port(), "username": user, "hostKey": key.export_public_key().decode().strip(),
                                           "nonce": "cmux-install" if args.install else "cmux-browser" if browser else "cmux", "silentPort": 0,
                                           **({"browserPort": browser.port} if browser else {})}) + "\n")
        print(json.dumps({"ready": True, "port": listener.get_port(), "root": str(root)}), flush=True)
        await stop.wait()
    finally:
        if listener: listener.close(); await listener.wait_closed()
        for child in list(children):
            if child.returncode is None: child.terminate(); await child.wait()
        if browser: await browser.close()
        await tmux_run("kill-server", allow_failure=True)
        # Do not erase durable state if terminal-host cleanup fails.
        for owner in owned_sessions:
            await cli("server", "ensure", "--session", owner, "--json")
            control = await wire(owner)
            try: await clear(control, owner)
            finally: await control.close()
            await cli("server", "stop", "--session", owner, "--json")
            preview = await cli("session", owner, "reset-state", "--json")
            await cli("session", owner, "reset-state", "--force", "--confirm-reset", preview["confirm_reset"], "--json")
        stderr.close(); shutil.rmtree(root)


if __name__ == "__main__": asyncio.run(main())
