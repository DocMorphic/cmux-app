import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import fs from 'node:fs';

const source = fs.readFileSync(new URL('../../app/src/main/assets/notice-session/session.js', import.meta.url), 'utf8');
const A = '11111111-1111-1111-1111-111111111111';
const B = '22222222-2222-2222-2222-222222222222';
function fixture() {
  const tabs = new Map();
  const cookies = [];
  let tick = 0;
  const sandbox = {
    ExtensionCommon: {ExtensionAPI: class {}}, URL,
    Date: {now: () => Date.now() + (tick += 4000)}, setTimeout,
    Ci: {nsICookieValidation: {eOK: 0}, nsICookie: {SAMESITE_UNSET: 0, SCHEME_HTTPS: 2, SCHEME_HTTP: 1}},
    Services: {io: {newURI: raw => {
      const url = new URL(raw);
      return {userPass: url.username + url.password, scheme: url.protocol.slice(0, -1), asciiHost: url.hostname};
    }}, cookies: {
      add: (...args) => { cookies.push({args, attrs: args[8]}); return {result: 0}; },
      getCookiesFromHost: (host, attrs) => cookies.filter(cookie => cookie.args?.[0] === host && cookie.attrs === attrs)
        .map(cookie => ({name: cookie.args[2], path: cookie.args[1], value: cookie.args[3]})),
      removeCookiesWithOriginAttributes: pattern => {
        const match = JSON.parse(pattern);
        for (let i = cookies.length - 1; i >= 0; --i) {
          if (Object.entries(match).every(([k, v]) => cookies[i].attrs[k] === v)) cookies.splice(i, 1);
        }
      }
    }}
  };
  vm.createContext(sandbox); vm.runInContext(source, sandbox);
  const api = new sandbox.noticeSession().getAPI({extension: {tabManager: {get: id => tabs.get(id)}}}).noticeSession;
  function tab(id, lease, context) {
    tabs.set(id, {incognito: true, browser: {currentURI: {spec: 'about:blank#cmux-notice-' + lease},
      browsingContext: {originAttributes: {privateBrowsingId: 1, userContextId: 0, geckoViewSessionContextId: context}}}});
  }
  const cookie = {domain: 'cmux.com', path: '/', name: 'stack-access', value: 'synthetic',
    secure: true, httpOnly: true, hostOnly: true, expires: 253402300799999};
  return {api, tabs, cookies, tab, cookie};
}
test('only exact owned private blanks can acquire or seed', async () => {
  const f = fixture(); f.tab(1, A, 'a');
  f.tabs.get(1).incognito = false;
  await assert.rejects(f.api.acquire(1, A));
  f.tabs.get(1).incognito = true;
  await f.api.acquire(1, A);
  await assert.rejects(f.api.acquire(1, A));
  f.tabs.get(1).browser.currentURI.spec = 'https://cmux.com/';
  await assert.rejects(f.api.seed(A, 'https://cmux.com/', [f.cookie]));
  assert.equal(f.cookies.length, 0);
});
test('invalid mixed batches are atomic and do not consume the seed lease', async () => {
  const f = fixture(); f.tab(1, A, 'a'); await f.api.acquire(1, A);
  await assert.rejects(f.api.seed(A, 'https://cmux.com/', [f.cookie, {...f.cookie, domain: 'evil.invalid'}]));
  assert.equal(f.cookies.length, 0);
  await f.api.seed(A, 'https://cmux.com/', [f.cookie]);
  assert.equal(f.cookies.length, 1);
  assert.equal(f.cookies[0].attrs.geckoViewSessionContextId, 'a');
  assert.equal(f.cookies[0].args[5], true); // HttpOnly
  assert.equal(f.cookies[0].args[6], true); // Session-only
  await assert.rejects(f.api.seed(A, 'https://cmux.com/', [f.cookie]));
});
test('clear requires closure and covers partitions without touching another or normal context', async () => {
  const f = fixture(); f.tab(1, A, 'a'); f.tab(2, B, 'b');
  await f.api.acquire(1, A); await f.api.acquire(2, B);
  await f.api.seed(A, 'https://cmux.com/', [f.cookie]); await f.api.seed(B, 'https://cmux.com/', [f.cookie]);
  f.cookies.push({attrs: {...f.cookies[0].attrs, partitionKey: '(https,third.invalid)'}});
  f.cookies.push({attrs: {...f.cookies[0].attrs, privateBrowsingId: 0}});
  await assert.rejects(f.api.clear(A)); assert.equal(f.cookies.length, 4);
  f.tabs.delete(1); await f.api.clear(A);
  assert.equal(f.cookies.length, 2);
  assert.equal(f.cookies[0].attrs.geckoViewSessionContextId, 'b');
  assert.equal(f.cookies[1].attrs.privateBrowsingId, 0);
  await assert.rejects(f.api.seed(A, 'https://cmux.com/', [f.cookie]));
  assert.equal(await f.api.clear(A), true); // Canceled/duplicate cleanup is harmless.
});
test('context replacement and insecure Secure-cookie delivery are rejected', async () => {
  const f = fixture(); f.tab(1, A, 'a'); await f.api.acquire(1, A);
  f.tabs.get(1).browser.browsingContext.originAttributes.geckoViewSessionContextId = 'b';
  await assert.rejects(f.api.seed(A, 'https://cmux.com/', [f.cookie]));
  f.tabs.get(1).browser.browsingContext.originAttributes.geckoViewSessionContextId = 'a';
  await assert.rejects(f.api.seed(A, 'http://127.0.0.1/', [{...f.cookie, domain: '127.0.0.1'}]));
  await assert.rejects(f.api.seed(A, 'https://name:password@cmux.com/', [f.cookie]));
  assert.equal(f.cookies.length, 0);
});

test('expiry is milliseconds and a seconds-valued timestamp is rejected before writing', async () => {
  const f = fixture(); f.tab(1, A, 'a'); await f.api.acquire(1, A);
  await assert.rejects(f.api.seed(A, 'https://cmux.com/', [{...f.cookie, expires: Math.floor(Date.now() / 1000) + 3600}]));
  assert.equal(f.cookies.length, 0);
  await f.api.seed(A, 'https://cmux.com/', [f.cookie]);
  assert.equal(f.cookies[0].args[7], f.cookie.expires);
  assert.ok(f.cookies[0].args[7] > Date.now());
});
