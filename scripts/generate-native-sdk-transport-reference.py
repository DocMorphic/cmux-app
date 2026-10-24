#!/usr/bin/env python3
"""Execute pinned SDK request/retry/error declarations with inert transport and clock seams."""
import argparse, hashlib, json, pathlib, subprocess, tempfile

PIN = 'a5911a1f92f3eeb11c4d7f3e2ef89204718c5009'
BASE = 'vendor/stack-auth-swift-sdk-prerelease/Sources/StackAuth/'
p = argparse.ArgumentParser()
p.add_argument('--upstream', type=pathlib.Path, required=True)
p.add_argument('--out', type=pathlib.Path, default=pathlib.Path('app/src/test/resources/auth/native-sdk-transport-reference.json'))
a = p.parse_args()
sources = {name: subprocess.check_output(['git', '-C', str(a.upstream), 'show', PIN+':'+BASE+name+'.swift']).decode()
           for name in ['APIClient', 'Errors', 'RateLimitObservation']}

def declaration(signature):
    text = sources['APIClient']; start = text.index(signature); end = text.index('{', start)+1; depth = 1
    while depth:
        if text[end] == '{': depth += 1
        elif text[end] == '}': depth -= 1
        end += 1
    return text[start:end]

decls = [declaration('func sendRequest('), declaration('private func sendWithRetry('),
         declaration('func retryAfterSeconds('), declaration('extension HTTPURLResponse {')]
rows = []
def reply(status=200, headers=None, body='{}'):
    return dict(status=status, headers=headers or {}, body=body)
def add(name, events=None, **kw):
    rows.append(dict(case=name, events=events or [reply()], **kw))
add('authenticated-get', refresh='new-access')
add('unauthenticated-get', authenticated=False)
for method in ['POST', 'PATCH', 'PUT', 'DELETE', 'HEAD', 'OPTIONS']:
    add('method-'+method, method=method)
add('explicit-body', method='PATCH', body={'selected_team_id':'team'})
add('modern-success-overrides-physical-failure', [reply(500, {'x-hexclave-actual-status':'201'})])
add('modern-success-overrides-physical-429', [reply(429, {'x-hexclave-actual-status':'200'})])
add('legacy-status', [reply(200, {'x-stack-actual-status':'503'})])
add('modern-status-precedence', [reply(200, {'x-hexclave-actual-status':'202','x-stack-actual-status':'503'})])
for status in ['', 'x', ' 401', '401 ', '9223372036854775808', '-9223372036854775809', '+201', '-1', '4294967296']:
    add('actual-status-'+repr(status), [reply(200, {'x-hexclave-actual-status':status,'x-stack-actual-status':'503'})])
add('bare-401', [reply(401)], refresh='new-access')
add('body-code-does-not-invent-known-error', [reply(401, body='{"code":"invalid_access_token","error":"other"}')], refresh='new-access')
for code in ['invalid_access_token', 'INVALID_ACCESS_TOKEN', '', 'other']:
    for status in [200, 401, 403]:
        add('refresh-'+str(status)+'-'+repr(code), [reply(status, {'x-hexclave-known-error':code}), reply()], refresh='new-access')
add('refresh-unauthenticated', [reply(401, {'x-hexclave-known-error':'invalid_access_token'})], authenticated=False, refresh='new-access')
add('refresh-unavailable', [reply(401, {'x-hexclave-known-error':'invalid_access_token'})])
add('refresh-once', [reply(401, {'x-stack-known-error':'invalid_access_token'})]*2, refresh='new-access')
add('refresh-stable-patch', [reply(401, {'x-hexclave-known-error':'invalid_access_token'}),reply()],
    method='PATCH', body={'selected_team_id':'team'}, refresh='new-access')
add('modern-empty-known-wins', [reply(401, {'x-hexclave-known-error':'','x-stack-known-error':'invalid_access_token'})], refresh='new-access')
for method in ['GET', 'HEAD', 'OPTIONS', 'PUT', 'DELETE', 'POST', 'PATCH']:
    add('network-exhaustion-'+method, [{'network':True}]*6, method=method)
    add('network-recovery-'+method, [{'network':True},reply()], method=method)
add('network-budget-resets-after-refresh', [{'network':True}]*2+[reply(401, {'x-hexclave-known-error':'invalid_access_token'})]+[{'network':True}]*5+[reply()], refresh='new-access')
add('cancellation-not-network-retry', [{'cancel':True}])
add('http-failure-not-network-retry', [reply(503),reply()])
add('known-error-not-network-retry', [reply(200, {'x-stack-known-error':'other'}),reply()])
for status in [200, 429]:
    add('effective-429-'+str(status), [reply(status, {'x-hexclave-actual-status':'429','Content-Type':'text/html','Retry-After':'1.2345','x-stack-request-id':'request'}, '<html>fixture')])
for code in ['RATE_LIMITED','rate_limited','RATE_LIMIT','']:
    add('known-rate-'+repr(code), [reply(200, {'x-hexclave-known-error':code})])
for name, body in [('message','{"message":"source message","error":"other"}'),('empty-message','{"message":"","error":"other"}'),
                   ('error','{"message":1,"error":"source error"}'),('unknown','{"message":1,"error":false}'),('invalid-json','bad'),
                   ('details','{"message":"m","details":{"httpStatus":"body","requestId":"body-id","would_work_if_email_was_verified":true}}')]:
    add('known-'+name, [reply(200, {'x-hexclave-known-error':'USER_EMAIL_ALREADY_EXISTS','x-hexclave-request-id':'header-id'},body)])
for code in ['EMAIL_PASSWORD_MISMATCH','PASSWORD_REQUIREMENTS_NOT_MET','USER_NOT_FOUND','VERIFICATION_CODE_ERROR',
             'INVALID_TOTP_CODE','REDIRECT_URL_NOT_WHITELISTED','PASSKEY_AUTHENTICATION_FAILED','MULTI_FACTOR_AUTHENTICATION_REQUIRED',
             'PASSWORD_CONFIRMATION_MISMATCH','OAUTH_PROVIDER_ACCOUNT_ID_ALREADY_USED_FOR_SIGN_IN']:
    add('typed-'+code, [reply(400, {'x-hexclave-known-error':code}, '{"message":"body","details":{"attempt_code":"attempt"}}')])
add('generic-diagnostics', [reply(503, {'x-hexclave-request-id':'','x-stack-request-id':'legacy'}, '{"message":"ignored"}')])

seams = r'''
protocol TokenStoreProtocol { func getStoredAccessToken() async -> String?; func getStoredRefreshToken() async -> String? }
struct Store:TokenStoreProtocol {func getStoredAccessToken() async->String?{"old-access"};func getStoredRefreshToken() async->String?{"stored-refresh"}}
struct TokenPair {let accessToken:String?}
enum RecordedClock {static var delays:[UInt64]=[]}
final class FixtureSession {
 let events:[[String:Any]];var requests:[URLRequest]=[]
 init(_ events:[[String:Any]]){self.events=events}
 func data(for request:URLRequest) async throws -> (Data,URLResponse) {
  requests.append(request);let event=events[min(requests.count-1,events.count-1)]
  if event["cancel"] as? Bool == true {throw CancellationError()}
  if event["network"] as? Bool == true {throw URLError(.notConnectedToInternet)}
  return (Data((event["body"] as! String).utf8),HTTPURLResponse(url:request.url!,statusCode:event["status"] as! Int,
    httpVersion:"HTTP/1.1",headerFields:event["headers"] as? [String:String])!)
 }
}
actor APIClient {
 static let sdkVersion="1.0.0"
 let baseUrl="https://fixture.invalid",projectId="fixture-project",publishableClientKey="fixture-key"
 let secretServerKey:String?=nil;let tokenStore:any TokenStoreProtocol=Store();let session:FixtureSession
 let refreshed:String?;var rejected:[String?]=[]
 init(_ session:FixtureSession,_ refreshed:String?){self.session=session;self.refreshed=refreshed}
 enum Task {static func sleep(nanoseconds:UInt64) async throws {try _Concurrency.Task.checkCancellation();RecordedClock.delays.append(nanoseconds)}}
 private func fetchNewAccessToken(tokenStore:any TokenStoreProtocol,ifAccessTokenUnchangedFrom access:String?) async->TokenPair {
  rejected.append(access);return TokenPair(accessToken:refreshed)
 }
'''
runner = r'''
let rows=try JSONSerialization.jsonObject(with:Data(INPUT.utf8)) as! [[String:Any]]
var output:[[String:Any]]=[]
for row in rows {
 RecordedClock.delays=[]
 let session=FixtureSession(row["events"] as! [[String:Any]]),client=APIClient(session,row["refresh"] as? String)
 var outcome:[String:Any]
 do {let (_,response)=try await client.sendRequest(path:"/users/me",method:row["method"] as? String ?? "GET",body:row["body"] as? [String:Any],authenticated:row["authenticated"] as? Bool ?? true)
  outcome=["success":true,"physicalStatus":response.statusCode]
 } catch let error as any StackAuthErrorProtocol {
  outcome=["success":false,"code":error.code,"message":error.code == "network_error" ? "<platform network message>" : error.message,"details":error.details as Any? ?? NSNull()]
 } catch {outcome=["success":false,"cancelled":error is CancellationError]}
 let nonces=session.requests.map{$0.value(forHTTPHeaderField:"x-hexclave-random-nonce") ?? ""}
 let requestRows=session.requests.map{request->[String:Any] in
  var headers=request.allHTTPHeaderFields ?? [:]
  headers=Dictionary(uniqueKeysWithValues:headers.map{($0.key.lowercased(),$0.value)})
  headers["x-hexclave-random-nonce"]=headers["x-hexclave-random-nonce"].map{UUID(uuidString:$0) == nil ? "invalid" : "UUID"}
  return ["method":request.httpMethod!,"headers":headers,"body":request.httpBody.flatMap{String(data:$0,encoding:.utf8)} as Any? ?? NSNull(),"timeout":request.timeoutInterval,"reloadIgnoringCache":request.cachePolicy == .reloadIgnoringLocalCacheData]
 }
 output.append(["input":row,"outcome":outcome,"requests":requestRows,"stableNonce":Set(nonces).count==1,
  "rejectedAccess":await client.rejected.map{$0 as Any? ?? NSNull()},"delaysMillis":RecordedClock.delays.map{$0/1_000_000}])
}
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject:["cases":output,"foundation":["os":ProcessInfo.processInfo.operatingSystemVersionString,"coreFoundationVersion":kCFCoreFoundationVersionNumber]],options:[.sortedKeys]))
'''.replace('INPUT', json.dumps(json.dumps(rows)))
with tempfile.TemporaryDirectory(prefix='cmux-sdk-transport-') as folder:
    root = pathlib.Path(folder)
    (root/'main.swift').write_text('import CoreFoundation\n'+sources['Errors']+'\n'+sources['RateLimitObservation']+'\n'+decls[2]+'\n'+seams+'\n'+decls[0]+'\n'+decls[1]+'\n}\n'+decls[3]+'\n'+runner)
    subprocess.run(['swiftc', str(root/'main.swift'), '-o', str(root/'reference')], check=True, timeout=60)
    result = json.loads(subprocess.check_output([str(root/'reference')], timeout=30))
out = dict(upstream=PIN, wholeOriginalFiles={BASE+k+'.swift':hashlib.sha256(v.encode()).hexdigest() for k,v in sources.items()},
           executedDeclarationsSha256=[hashlib.sha256(d.encode()).hexdigest() for d in decls],
           seams='Complete unchanged sendRequest/sendWithRetry/Retry-After/HTTPURLResponse helpers and whole Errors/RateLimitObservation sources. Inert session returns fixture Data/HTTPURLResponse or URLError/CancellationError; inert token store and refresh record the rejected token and supply a planned result. Nested Task.sleep records delays without waiting; it checks parent cancellation. This proves SDK control flow, not URLSession networking, real refresh coalescing, observer wiring, or iOS-runtime behavior.', **result)
a.out.parent.mkdir(parents=True, exist_ok=True)
a.out.write_text(json.dumps(out, indent=2)+'\n')
print('Executed original SDK transport cases:', len(result['cases']))
