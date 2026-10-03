// Synthetic-only experiment using Gecko internals from the pinned engine.
// No arbitrary JS execution, external messaging or page-script access is exposed.
var noticeCookies = class extends ExtensionCommon.ExtensionAPI {
  getAPI(context) {
    return { noticeCookies: {
      async seed(tabId, url, value) {
        if (!/^http:\/\/127\.0\.0\.1:\d+\/$/.test(url) || !["a", "b"].includes(value)) {
          throw new Error("Invalid loopback fixture");
        }
        const tab = context.extension.tabManager.get(tabId);
        if (!tab || !tab.incognito || !tab.browser.currentURI.spec.startsWith("about:blank#notice-")) {
          throw new Error("Expected an owned private fixture tab");
        }
        const attrs = tab.browser.browsingContext.originAttributes;
        if (attrs.privateBrowsingId !== 1 || !(attrs.geckoViewSessionContextId || attrs.userContextId)) {
          throw new Error("Missing private context attributes");
        }
        Services.cookies.add("127.0.0.1", "/", "notice_probe", value,
          false, true, true, Number.MAX_SAFE_INTEGER, attrs,
          Ci.nsICookie.SAMESITE_UNSET, Ci.nsICookie.SCHEME_HTTP, false);
        return { contextAttributes: attrs, httpOnly: true };
      }
    } };
  }
};
