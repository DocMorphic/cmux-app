// Bundled only: no content scripts, external messages, certificate overrides or eval.
// Internal cookie API is pinned to GeckoView 157 and covered by the runtime gate.
var noticeSession = class extends ExtensionCommon.ExtensionAPI {
  getAPI(context) {
    const leases = new Map();
    const marker = lease => "about:blank#cmux-notice-" + lease;
    const tabFor = owned => {
      const tab = context.extension.tabManager.get(owned.tabId, null);
      if (!tab || !tab.incognito || tab.browser.currentURI.spec !== marker(owned.lease))
        throw new Error("Notice is not at its owned blank document");
      const attrs = tab.browser.browsingContext.originAttributes;
      if (attrs.privateBrowsingId !== 1 || attrs.geckoViewSessionContextId !== owned.attrs.geckoViewSessionContextId)
        throw new Error("Private context changed");
      return tab;
    };
    return { noticeSession: {
      async acquire(tabId, lease) {
        if (!/^[0-9a-f-]{36}$/.test(lease) || leases.has(lease)) throw new Error("Invalid lease");
        const tab = context.extension.tabManager.get(tabId, null);
        if (!tab || !tab.incognito || tab.browser.currentURI.spec !== marker(lease)) throw new Error("Invalid owned tab");
        const attrs = JSON.parse(JSON.stringify(tab.browser.browsingContext.originAttributes));
        if (attrs.privateBrowsingId !== 1 || !attrs.geckoViewSessionContextId) throw new Error("Missing private context");
        leases.set(lease, { lease, tabId, attrs, seeded: false });
        // Native retains this non-secret scope receipt across extension restarts.
        return { privateBrowsingId: 1, userContextId: attrs.userContextId,
          geckoViewSessionContextId: attrs.geckoViewSessionContextId };
      },
      async retire(receipt, tabIds) {
        if (receipt.privateBrowsingId !== 1 || !Number.isSafeInteger(receipt.userContextId) ||
            receipt.userContextId < 0 || typeof receipt.geckoViewSessionContextId !== "string" ||
            !receipt.geckoViewSessionContextId || receipt.geckoViewSessionContextId.length > 1024)
          throw new Error("Invalid retirement scope");
        // The bundled background obtains this inventory itself, never from a native command.
        // Refuse clearing any matching live context, even if tab IDs changed after restart.
        for (const id of tabIds) {
          const tab = context.extension.tabManager.get(id, null);
          const attrs = tab?.browser?.browsingContext?.originAttributes;
          if (attrs?.privateBrowsingId === 1 && attrs.userContextId === receipt.userContextId &&
              attrs.geckoViewSessionContextId === receipt.geckoViewSessionContextId)
            throw new Error("Retired context still open");
        }
        Services.cookies.removeCookiesWithOriginAttributes(JSON.stringify({
          privateBrowsingId: 1, userContextId: receipt.userContextId,
          geckoViewSessionContextId: receipt.geckoViewSessionContextId
        }));
        return true;
      },
      async seed(lease, destination, cookies) {
        const owned = leases.get(lease);
        if (!owned || owned.seeded) throw new Error("Invalid seed lifecycle");
        tabFor(owned);
        const url = Services.io.newURI(destination);
        if (url.userPass || !(url.scheme === "https" ||
            (url.scheme === "http" && ["127.0.0.1", "localhost"].includes(url.asciiHost)))) throw new Error("Invalid destination");
        if (cookies.length > 64) throw new Error("Too many cookies");
        // Validate the whole batch before any mutation; values/errors are never echoed.
        for (const c of cookies) {
          if (c.domain !== url.asciiHost || typeof c.name !== "string" || !c.name ||
              typeof c.value !== "string" || typeof c.path !== "string" || !c.path.startsWith("/") ||
              typeof c.secure !== "boolean" || typeof c.httpOnly !== "boolean" || typeof c.hostOnly !== "boolean" ||
              !Number.isSafeInteger(c.expires) || c.expires <= Date.now() ||
              (c.secure && url.scheme !== "https") || /[\x00-\x20;,=]/.test(c.name) || /[\r\n\0]/.test(c.value))
            throw new Error("Invalid cookie batch");
        }
        owned.seeded = true;
        for (const c of cookies) {
          // Pinned Gecko 157 nsICookieManager.add uses milliseconds, as in ext-cookies.js.
          let validation;
          try { validation = Services.cookies.add(c.hostOnly ? c.domain : "." + c.domain, c.path, c.name, c.value,
            c.secure, c.httpOnly, true, c.expires, owned.attrs, Ci.nsICookie.SAMESITE_UNSET,
            url.scheme === "https" ? Ci.nsICookie.SCHEME_HTTPS : Ci.nsICookie.SCHEME_HTTP, false);
          } catch (_) { return { accepted: false, reason: "cookie-api-threw" }; }
          if (!validation) return { accepted: false, reason: "cookie-api-no-result" };
          if (validation.result !== Ci.nsICookieValidation.eOK) {
            return { accepted: false, reason: "cookie-validation-" + validation.result };
          }
          let stored;
          try { stored = Services.cookies.getCookiesFromHost(c.domain, owned.attrs); }
          catch (_) { return { accepted: false, reason: "cookie-readback-failed" }; }
          if (!Array.from(stored).some(value => value.name === c.name && value.path === c.path && value.value === c.value)) {
            return { accepted: false, reason: "cookie-not-stored" };
          }
        }
        return { accepted: true };
      },
      async clear(lease) {
        const owned = leases.get(lease);
        // A canceled acquire may never have registered. Nothing can be seeded without it.
        if (!owned) return true;
        const until = Date.now() + 3000;
        while (context.extension.tabManager.get(owned.tabId, null)) {
          if (Date.now() >= until) throw new Error("Owned tab still open");
          await new Promise(resolve => setTimeout(resolve, 25));
        }
        // Deliberately omit host/partitionKey: clear every partition within ONLY this private context.
        Services.cookies.removeCookiesWithOriginAttributes(JSON.stringify({
          privateBrowsingId: 1, userContextId: owned.attrs.userContextId,
          geckoViewSessionContextId: owned.attrs.geckoViewSessionContextId
        }));
        leases.delete(lease);
        return true;
      }
    } };
  }
};
