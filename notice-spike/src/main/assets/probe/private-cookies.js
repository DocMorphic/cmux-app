// Synthetic-only experiment using Gecko internals from the pinned engine.
// No arbitrary JS execution, external messaging or page-script access is exposed.
var noticeCookies = class extends ExtensionCommon.ExtensionAPI {
  getAPI(context) {
    const leases = new Map();
    let fixtureCert = null;
    let savedDns = null;
    return { noticeCookies: {
      async httpsFixture(action) {
        const pref = "network.dns.localDomains";
        const certDb = Cc["@mozilla.org/security/x509certdb;1"].getService(Ci.nsIX509CertDB);
        if (action === "resolve") {
          if (savedDns !== null) throw new Error("Fixture already active");
          savedDns = { existed: Services.prefs.prefHasUserValue(pref), value: Services.prefs.getCharPref(pref, "") };
          Services.prefs.setCharPref(pref, "cmux-notice.invalid");
          return true;
        }
        if (action === "trust") {
          if (savedDns === null || fixtureCert) throw new Error("Invalid fixture lifecycle");
          const fixture = await context.extension.readJSON("fixture-ca.json");
          fixtureCert = certDb.addCertFromBase64(fixture.base64, "CT,,");
          return true;
        }
        if (action === "clear") {
          if (fixtureCert) { certDb.deleteCertificate(fixtureCert); fixtureCert = null; }
          if (savedDns) {
            if (savedDns.existed) Services.prefs.setCharPref(pref, savedDns.value);
            else Services.prefs.clearUserPref(pref);
            savedDns = null;
          }
          return true;
        }
        throw new Error("Invalid HTTPS fixture operation");
      },
      async capture(tabId) {
        const tab = context.extension.tabManager.get(tabId);
        if (!tab || !tab.incognito || !/^http:\/\/127\.0\.0\.1:\d+\/probe\?/.test(tab.browser.currentURI.spec)) {
          throw new Error("Expected owned private storage fixture");
        }
        const attrs = tab.browser.browsingContext.originAttributes;
        if (attrs.privateBrowsingId !== 1 || !attrs.geckoViewSessionContextId) throw new Error("Missing private context");
        const lease = Services.uuid.generateUUID().toString();
        leases.set(lease, { tabId, attrs: JSON.parse(JSON.stringify(attrs)) });
        return lease;
      },
      async clear(lease) {
        const owned = leases.get(lease);
        if (!owned) throw new Error("Unknown owned context");
        if (context.extension.tabManager.get(owned.tabId, null)) throw new Error("Tab still open");
        // Include privateBrowsingId explicitly; the public context clear supplies only its context ID.
        Services.cookies.removeCookiesWithOriginAttributes(JSON.stringify({
          privateBrowsingId: 1,
          userContextId: owned.attrs.userContextId,
          geckoViewSessionContextId: owned.attrs.geckoViewSessionContextId
        }));
        leases.delete(lease);
        return true;
      },
      async seed(tabId, url, value) {
        const secure = /^https:\/\/cmux-notice\.invalid:\d+\/$/.test(url);
        if ((!secure && !/^http:\/\/127\.0\.0\.1:\d+\/$/.test(url)) || !["a", "b"].includes(value)) {
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
        Services.cookies.add(secure ? "cmux-notice.invalid" : "127.0.0.1", "/", "notice_probe", value,
          secure, true, true, Number.MAX_SAFE_INTEGER, attrs,
          Ci.nsICookie.SAMESITE_UNSET, secure ? Ci.nsICookie.SCHEME_HTTPS : Ci.nsICookie.SCHEME_HTTP, false);
        return { contextAttributes: attrs, httpOnly: true, secure };
      }
    } };
  }
};
