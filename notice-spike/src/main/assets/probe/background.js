// Experiment only. No content script, external messaging, remote code or native account tokens.
const port = browser.runtime.connectNative("notice_probe");
port.onMessage.addListener(async command => {
  try {
    if (!Number.isInteger(command.id)) throw new Error("Invalid request");
    if (command.op === "httpsFixture") {
      port.postMessage({ id: command.id, done: await browser.noticeCookies.httpsFixture(command.action) });
      return;
    }
    const tabs = await browser.tabs.query({});
    if (command.op === "tabs") {
      port.postMessage({ id: command.id, tabs: tabs.map(t => ({
        url: t.url, id: t.id, incognito: t.incognito, storeId: t.cookieStoreId
      })) });
      return;
    }
    if (command.op === "capture") {
      const matches = tabs.filter(t => t.url === command.tabUrl && t.incognito);
      if (matches.length !== 1) throw new Error("Private fixture not uniquely identified");
      port.postMessage({ id: command.id, lease: await browser.noticeCookies.capture(matches[0].id) });
      return;
    }
    if (command.op === "clear") {
      port.postMessage({ id: command.id, cleared: await browser.noticeCookies.clear(command.lease) });
      return;
    }
    const httpsFixture = /^https:\/\/cmux-notice\.invalid:\d+\/$/.test(command.url);
    if (command.op !== "seed" || (!/^http:\/\/127\.0\.0\.1:\d+\/$/.test(command.url) && !httpsFixture)
        || !["a", "b"].includes(command.value)) throw new Error("Invalid synthetic fixture");
    if (httpsFixture && command.scoped !== true) throw new Error("HTTPS requires scoped fixture");
    const matches = tabs.filter(t => t.url === command.tabUrl && t.incognito);
    if (matches.length !== 1 || !matches[0].cookieStoreId) throw new Error("Private tab not uniquely identified");
    const tab = matches[0];
    if (command.scoped === true) {
      const result = await browser.noticeCookies.seed(tab.id, command.url, command.value);
      port.postMessage({ id: command.id, storeId: JSON.stringify(result.contextAttributes), scoped: result });
      return;
    }
    const cookie = await browser.cookies.set({
      url: command.url, name: "notice_probe", value: command.value,
      httpOnly: true, path: "/", storeId: tab.cookieStoreId,
      expirationDate: Date.now() / 1000 + 3600
    });
    port.postMessage({ id: command.id, storeId: tab.cookieStoreId,
      cookie: cookie && { value: cookie.value, httpOnly: cookie.httpOnly, storeId: cookie.storeId } });
  } catch (e) {
    port.postMessage({ id: command.id, error: String(e) });
  }
});
port.postMessage({ ready: true });
