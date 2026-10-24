#!/usr/bin/env python3
"""Execute the complete release snapshot/coherent declarations, with explicit inert IO/phase seams."""
import argparse, hashlib, json, pathlib, subprocess, tempfile

PIN = 'a5911a1f92f3eeb11c4d7f3e2ef89204718c5009'
BASE = 'Packages/Shared/CmuxAuthRuntime/Sources/CmuxAuthRuntime/Coordinator/'
FILES = [BASE+'AuthCoordinator+Tokens.swift', BASE+'AuthCoordinator.swift']
p = argparse.ArgumentParser()
p.add_argument('--upstream', type=pathlib.Path, required=True)
p.add_argument('--out', type=pathlib.Path, default=pathlib.Path('app/src/test/resources/auth/native-snapshot-reference.json'))
a = p.parse_args()
sources = {f: subprocess.check_output(['git', '-C', str(a.upstream), 'show', PIN+':'+f]).decode() for f in FILES}

def declaration(file, signature):
    source = sources[file]; start = source.index(signature); end = source.index('{', start)+1; depth = 1
    while depth:
        if source[end] == '{': depth += 1
        elif source[end] == '}': depth -= 1
        end += 1
    return source[start:end]

decls = [declaration(FILES[0], s) for s in [
    'public func coherentTokenPair()', 'func coherentTokenPairWithoutStateClear()',
    'public func authenticatedSessionSnapshot()', 'private func storedSessionSnapshot()',
    'public func authenticatedRefreshTokenSnapshot()', 'private func emptyTokenReadError(']]
decls += [declaration(FILES[1], 'var sessionTokenTransitionIsActive: Bool')]

rows = []
for method in ['session', 'refresh']:
    for identity in ['account-a', None, '']:
        for state in ['complete', 'missingAccess', 'missingRefresh', 'empty', 'unavailable', 'transient']:
            for role in ['none', 'signIn', 'staleSignIn', 'validationAuthenticated', 'validationRestoring', 'capture']:
                for clear in [False, True]:
                    rows.append(dict(method=method, identity=identity, state=state, role=role, clear=clear))
for event, actions, methods in [
    ('resolved', ['user', 'generation', 'transition', 'dropRefresh', 'unavailable', 'rotate', 'churn'], ['session']),
    ('storedAccess', ['user', 'generation', 'transition', 'rotate', 'dropRefresh', 'unavailable'], ['session']),
    ('availability', ['unavailable', 'dropRefresh', 'restore'], ['session', 'refresh']),
    ('refreshCapture', ['user', 'generation', 'transition', 'rotate', 'dropRefresh', 'unavailable'], ['refresh']),
    ('bootstrap', ['user', 'generation', 'transition', 'restore', 'unavailable'], ['session', 'refresh']),
]:
    for action in actions:
        for method in methods:
            rows.append(dict(method=method, identity='account-a', state='complete' if event in ['storedAccess', 'refreshCapture'] else 'missingAccess',
                             role='none', clear=False, event=event, action=action))
for method in ['session', 'refresh']:
    rows.append(dict(method=method, identity='account-a', state='unavailable', role='none', clear=False, event='availability', action='restore'))
    rows.append(dict(method=method, identity='account-a', state='unavailable', role='none', clear=False, event='availability', action='transition'))

wrapper = r'''
import Foundation
import CoreFoundation
enum AuthError:Error {case networkError, unauthorized, timedOut}
enum AuthPhase {case accessToken}
struct User {let id:String}
struct Flow {let generation:UInt64;let signOutEpoch:UInt64}
struct Launch {let shouldClearStoredSessionBeforePriming:Bool}
struct Timeouts {let network=Duration.seconds(60)}
struct AuthenticatedSessionSnapshot {let generation:UInt64;let accountID:String;let accessToken:String;let refreshToken:String}
struct AuthenticatedRefreshTokenSnapshot {let generation:UInt64;let accountID:String;let refreshToken:String}
@MainActor final class Client {
 unowned let owner:Coordinator
 init(_ owner:Coordinator){self.owner=owner}
 func refreshToken()async->String? {
  owner.calls.append("refresh")
  let result=owner.available ? owner.refresh : nil
  if owner.row["method"] as! String == "refresh" {owner.event("refreshCapture")}
  return result
 }
 func storedAccessToken()async->String? {
  owner.calls.append("storedAccess");let value=owner.available ? owner.access : nil
  owner.event("storedAccess");return value
 }
 func resolvedAccessToken(forceRefresh:Bool)async throws->String? {
  owner.calls.append("resolved");owner.resolutions+=1
  let value=owner.available ? owner.resolved : nil
  owner.event("resolved");return value
 }
}
@MainActor final class Coordinator {
 let row:[String:Any];let launch:Launch;let timeouts=Timeouts()
 lazy var client=Client(self)
 var sessionGeneration:UInt64=3,signOutEpoch:UInt64=1
 var currentUser:User?;var isAuthenticated:Bool
 var activeSignInFlows:[String:Flow]=[:]
 var isRevalidatingSession=false,isRestoringSession=false,isCapturingSignOutCredentials=false
 var access:String?="stored",refresh:String?="refresh",resolved:String?="resolved",available=true
 var calls:[String]=[];var resolutions=0,phases=0,bootstraps=0;var fired=false
 init(_ row:[String:Any]){
  self.row=row;launch=Launch(shouldClearStoredSessionBeforePriming:row["clear"] as! Bool)
  currentUser=(row["identity"] as? String).map{User(id:$0)};isAuthenticated=currentUser != nil
  switch row["state"] as! String {
  case "missingAccess":access=nil
  case "missingRefresh":refresh=nil
  case "empty":access=nil;refresh=nil;resolved=nil
  case "unavailable":available=false
  case "transient":access=nil;resolved=nil
  default:break
  }
  switch row["role"] as! String {
  case "signIn":activeSignInFlows=["flow":Flow(generation:3,signOutEpoch:1)]
  case "staleSignIn":activeSignInFlows=["flow":Flow(generation:2,signOutEpoch:1)]
  case "validationAuthenticated":isRevalidatingSession=true
  case "validationRestoring":isRevalidatingSession=true;isRestoringSession=true
  case "capture":isCapturingSignOutCredentials=true
  default:break
  }
 }
 func event(_ name:String){
  guard row["event"] as? String == name else{return}
  let action=row["action"] as! String
  if fired && action != "churn" {return};fired=true
  switch action {
  case "user":currentUser=User(id:"account-b")
  case "generation":sessionGeneration+=1
  case "transition":isCapturingSignOutCredentials=true
  case "dropRefresh":refresh=nil
  case "unavailable":available=false
  case "restore":available=true;access="restored";refresh="restored-refresh";resolved="restored"
  case "rotate", "churn":refresh="rotation-\(resolutions)"
  default:break
  }
 }
 func isTokenStorageAvailable()async->Bool{calls.append("availability");let value=available;event("availability");return value}
 func awaitBootstrapped()async{calls.append("bootstrap");bootstraps+=1;event("bootstrap")}
 func runTokenTouchingPhase<T>(_ phase:AuthPhase,timeout:Duration,_ body:()async throws->T)async rethrows->T{
  phases+=1;return try await body()
 }
DECLARATIONS
}
@MainActor func evaluate(_ rows:[[String:Any]])async->[[String:Any]]{
 var result:[[String:Any]]=[]
 for row in rows {
  let owner=Coordinator(row);var outcome:[String:Any]
  do {
   if row["method"] as! String == "session" {
    let value=try await owner.authenticatedSessionSnapshot()
    outcome=["account":value.accountID,"generation":value.generation,"access":value.accessToken,"refresh":value.refreshToken,"error":NSNull()]
   }else{
    let value=try await owner.authenticatedRefreshTokenSnapshot()
    outcome=["account":value.accountID,"generation":value.generation,"access":NSNull(),"refresh":value.refreshToken,"error":NSNull()]
   }
  }catch{outcome=["error":String(describing:error)]}
  result.append(["input":row,"outcome":outcome,"resolutions":owner.resolutions,"phases":owner.phases,"bootstraps":owner.bootstraps,"calls":owner.calls])
 }
 return result
}
let rows=try JSONSerialization.jsonObject(with:Data(INPUT.utf8)) as! [[String:Any]]
let result=await evaluate(rows)
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject:["cases":result,"foundation":["os":ProcessInfo.processInfo.operatingSystemVersionString,"coreFoundationVersion":kCFCoreFoundationVersionNumber]],options:[.sortedKeys]))
'''.replace('DECLARATIONS', '\n'.join(decls)).replace('INPUT', json.dumps(json.dumps(rows)))
with tempfile.TemporaryDirectory(prefix='cmux-snapshot-') as folder:
    root = pathlib.Path(folder); (root/'main.swift').write_text(wrapper)
    subprocess.run(['swiftc', str(root/'main.swift'), '-o', str(root/'reference')], check=True, timeout=60)
    result = json.loads(subprocess.check_output([str(root/'reference')], timeout=60))
out = dict(upstream=PIN, wholeOriginalFiles={f:hashlib.sha256(s.encode()).hexdigest() for f,s in sources.items()},
           executedDeclarationsSha256=[hashlib.sha256(d.encode()).hexdigest() for d in decls],
           seams='Seven complete unchanged release declarations: authenticated session/refresh snapshots, stored fast path, coherent public/inner pair, empty-read classifier and transition property. Storage availability, SDK token reads/resolution, bootstrap and mutations at await boundaries are inert MainActor fixtures. The phase seam calls the complete original coherent operation once; it does not run the timer/admission/finish/high-water machinery. No original cache, Keychain/Android storage, SDK transport, real scheduler race or iOS-runtime equivalence is established. Android atomic document reads may combine separate original SDK reads; compare credential outcomes and semantic resolution/phase/bootstrap counts, not SDK read-call counts.', **result)
a.out.parent.mkdir(parents=True, exist_ok=True)
a.out.write_text(json.dumps(out, indent=2)+'\n')
print('Executed original snapshot cases:', len(rows))
