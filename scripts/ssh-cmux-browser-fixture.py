"""Private Chrome/HTTP peer for the opt-in cmux SSH browser integration suite."""
import asyncio
import json
from urllib.parse import urlsplit, parse_qs, quote
from urllib.request import urlopen, Request


class BrowserFixture:
    def __init__(self, binary, root, spawn):
        self.binary, self.root, self.spawn = binary, root, spawn
        self.server = self.chrome = self.provider = None
        self.port = 0
        self.events = []

    async def start(self):
        async def http(reader, writer):
            try:
                headers = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), 5)
                target = headers.split(b" ")[1].decode("ascii")
                parsed = urlsplit(target)
                if parsed.path == "/event":
                    self.events.append(parse_qs(parsed.query))
                    body = b"ok"
                else:
                    title = "SSH Chrome next" if parsed.path == "/next" else "SSH Chrome start"
                    color = "30,60,120" if parsed.path == "/next" else "18,95,55"
                    body = f'''<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><title>{title}</title>
<style>body{{margin:0;background:rgb({color});font:20px sans-serif}}button,input,a{{position:absolute;left:15%;width:70%;box-sizing:border-box;height:60px}}button{{top:30%}}input{{top:55%}}a{{top:80%;color:white}}</style>
<button onclick="document.title='SSH Chrome clicked';fetch('/event?click=1')">Fixture click</button>
<input aria-label="Fixture input" oninput="fetch('/event?text='+encodeURIComponent(this.value))">
<a href="/next">Next Chrome page</a>'''.encode()
                writer.write(f"HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {len(body)}\r\nConnection: close\r\n\r\n".encode() + body)
                await writer.drain()
            except (OSError, asyncio.IncompleteReadError, asyncio.TimeoutError):
                pass
            finally:
                writer.close()
                await writer.wait_closed()
        self.server = await asyncio.start_server(http, "127.0.0.1", 0)
        self.port = self.server.sockets[0].getsockname()[1]
        profile = self.root / "chrome-profile"
        # This disposable profile must never use or prompt for macOS Safe Storage.
        self.chrome = await self.spawn(str(self.binary), "--headless=new", "--disable-gpu", "--use-mock-keychain",
            "--no-first-run", "--no-default-browser-check", "--disable-background-networking",
            "--disable-background-timer-throttling", "--disable-backgrounding-occluded-windows",
            "--disable-renderer-backgrounding", "--no-proxy-server", "--disable-extensions",
            "--remote-debugging-address=127.0.0.1", "--remote-debugging-port=0",
            f"--user-data-dir={profile}", self.url)
        async with asyncio.timeout(15):
            while not (profile / "DevToolsActivePort").is_file():
                if self.chrome.returncode is not None: raise RuntimeError("Private Chrome exited")
                await asyncio.sleep(.05)
        port, path = (profile / "DevToolsActivePort").read_text().splitlines()[:2]
        self.debug_url = f"http://127.0.0.1:{port}"
        self.endpoint = f"ws://127.0.0.1:{port}{path}"
        def targets():
            with urlopen(f"http://127.0.0.1:{port}/json/list", timeout=5) as response:
                return json.load(response)
        self.target = next(t["id"] for t in await asyncio.to_thread(targets) if t["type"] == "page")

    @property
    def url(self): return f"http://127.0.0.1:{self.port}/start"

    async def detach(self):
        if self.provider:
            await self.provider.close()
            self.provider = None

    async def reset(self, wire):
        self.events.clear()
        # A previous run may leave Chrome at /next with edited DOM state. Create
        # a fresh target before registering the replacement tab, and close only
        # our previously recorded target. Repeated tests share no page state.
        def fresh_target():
            with urlopen(Request(self.debug_url + "/json/new?" + quote(self.url, safe=""), method="PUT"), timeout=5) as response:
                target = json.load(response)["id"]
            with urlopen(self.debug_url + "/json/close/" + self.target, timeout=5) as response:
                response.read()
            return target
        self.target = await asyncio.to_thread(fresh_target)
        self.provider = await wire()
        surface = (await self.provider.request("new-browser-tab", {"url": self.url}))["surface"]
        tree = await self.provider.request("list-workspaces")
        tab = next(tab for ws in tree["workspaces"] for screen in ws["screens"]
                   for pane in screen["panes"] for tab in pane["tabs"] if tab["surface"] == surface)
        await self.provider.request("register-browser-provider", {"provider_id": "private-android-browser",
            "endpoint": self.endpoint, "authentication": "none",
            "targets": [{"tab_id": tab["tab_resource_id"], "target_id": self.target}]})
        # Keep this wire alive: provider registration belongs to its connection.

    async def close(self):
        await self.detach()
        if self.chrome and self.chrome.returncode is None:
            self.chrome.terminate()
            try: await asyncio.wait_for(self.chrome.wait(), 5)
            except asyncio.TimeoutError: self.chrome.kill(); await self.chrome.wait()
        if self.server:
            self.server.close()
            await self.server.wait_closed()
