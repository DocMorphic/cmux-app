// This port is reachable only from the app's checked extension delegate.
const port = browser.runtime.connectNative("cmux_notice_session");
let pending = Promise.resolve();
port.onMessage.addListener(command => {
  // Ordering preserves acquire -> seed -> close/clear even when a native waiter is canceled.
  pending = pending.then(async () => {
    if (!Number.isSafeInteger(command.id)) return;
    try {
      let done;
      if (command.op === "acquire") {
        const marker = "about:blank#cmux-notice-" + command.lease;
        const tabs = (await browser.tabs.query({})).filter(tab => tab.incognito && tab.url === marker);
        if (tabs.length !== 1) throw new Error("Owned tab unavailable");
        const receipt = await browser.noticeSession.acquire(tabs[0].id, command.lease);
        port.postMessage({ id: command.id, done: true, receipt });
        return;
      } else if (command.op === "seed") {
        const result = await browser.noticeSession.seed(command.lease, command.url, command.cookies);
        if (!result.accepted) {
          const reason = /^(cookie-validation-[0-9]+|cookie-not-stored|cookie-api-threw|cookie-api-no-result|cookie-readback-failed)$/.test(result.reason)
            ? result.reason : "operation-failed";
          port.postMessage({ id: command.id, failed: true, failureCode: reason });
          return;
        }
        done = true;
      } else if (command.op === "retire") {
        const tabs = await browser.tabs.query({});
        done = await browser.noticeSession.retire(command.receipt, tabs.map(tab => tab.id));
      } else if (command.op === "clear") {
        done = await browser.noticeSession.clear(command.lease);
      } else throw new Error("Unknown operation");
      port.postMessage({ id: command.id, done });
    } catch (error) {
      const code = /^(cookie-validation-[0-9]+|cookie-not-stored)$/.test(error.noticeCode || "")
        ? error.noticeCode : "operation-failed";
      port.postMessage({ id: command.id, failed: true, failureCode: code });
    }
  });
});
port.postMessage({ ready: true });
