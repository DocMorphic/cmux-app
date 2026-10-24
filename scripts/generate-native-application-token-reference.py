#!/usr/bin/env python3
"""Execute original release coordinator/token-phase declarations with explicitly inert local seams."""
import argparse,pathlib,subprocess,json,hashlib,tempfile
PIN='a5911a1f92f3eeb11c4d7f3e2ef89204718c5009'
BASE='Packages/Shared/CmuxAuthRuntime/Sources/CmuxAuthRuntime/'
FILES=[BASE+'Coordinator/AuthCoordinator+Tokens.swift',BASE+'Coordinator/AuthCoordinator.swift',BASE+'Coordinator/AuthCoordinator+TokenTouchingPhaseTimeout.swift',BASE+'Client/StackAuthClient.swift']
p=argparse.ArgumentParser();p.add_argument('--upstream',type=pathlib.Path,required=True);p.add_argument('--out',type=pathlib.Path,default=pathlib.Path('app/src/test/resources/auth/native-application-token-reference.json'));a=p.parse_args()
sources={f:subprocess.check_output(['git','-C',str(a.upstream),'show',PIN+':'+f]).decode() for f in FILES}
def decl(file,signature):
 src=sources[file];start=src.index(signature);end=src.index('{',start)+1;depth=1
 while depth:
  if src[end]=='{':depth+=1
  elif src[end]=='}':depth-=1
  end+=1
 return src[start:end]
coordinator=[decl(FILES[0],s) for s in ['public func accessToken()','private func accessTokenWithoutStateClear()','private func devAuthAccessTokenFallback()','public func forceRefreshAccessToken()','private func forceRefreshAccessTokenWithoutStateClear()','private func emptyTokenReadError(']]
coordinator += [decl(FILES[1],s) for s in ['func clearAuthState(','private func advanceSessionGeneration(','var sessionTokenTransitionIsActive: Bool']]
coordinator += [decl(FILES[2],s) for s in ['func runTokenTouchingPhase<T: Sendable>(\n        _ phase: AuthPhase,\n        timeout: Duration,\n        _ operation:', 'func runTokenTouchingPhase<T: Sendable>(\n        _ phase: AuthPhase,\n        timeout: Duration,\n        deadline:']]
client=[decl(FILES[3],s) for s in ['public func resolvedAccessToken(forceRefresh:','public func refreshToken()']]
plans=[dict(case='access',access='access',refresh='refresh'),dict(case='token-only',access='access'),dict(case='empty'),dict(case='transient',refresh='refresh'),dict(case='timeout',failure='timedOut'),dict(case='cancelled',failure='cancelled'),dict(case='changed',failure='sessionChanged'),dict(case='timeout-with-refresh',failure='timedOut',refresh='refresh')]
roles=['none','signIn','staleSignIn','validationAuthenticated','validationRestoring','staleValidation','capture']
rows=[]
for plan in plans:
 for force in [False,True]:
  for available in [False,True]:
   for role in roles:
    for late in [False,True]:rows.append(dict(**plan,force=force,available=available,role=role,late=late))
for extra in ['cancelBefore','cancelDuring','retired','preExpired']:
 for force in [False,True]:
  for available in [False,True]:rows.append(dict(case=extra,force=force,available=available,role='none',access='access',**{extra:True}))
wrapper=r'''
import Foundation
import CoreFoundation
enum AuthError:Error {case networkError,unauthorized,timedOut}
enum Failure:String {case cancelled,sessionChanged,timedOut}
enum AuthPhase:String,Hashable {case accessToken,forceRefreshAccessToken}
struct Pair:Sendable {let accessToken:String?;let refreshFailure:Failure?}
final class Clock:@unchecked Sendable {var expired=false;func authTokenDeadline(after:Duration)->AuthTokenDeadline{AuthTokenDeadline(clock:self)}}
struct AuthTokenDeadline:Sendable {let clock:Clock;func hasExpired()->Bool{clock.expired}}
struct AuthTrackedTokenWork {let cancel:()->Void;let completion:Task<Void,Never>}
struct TimedOutState {let id:UUID}
struct Flow {let generation:UInt64;let signOutEpoch:UInt64}
struct Timeouts {let network=Duration.seconds(60)}
struct Logger {func log(_ message:String){}}
final class Cache {var clears=0;func clear(){clears+=1}}
enum State {case cleared;static func cleared()->State{.cleared}}
actor Stack {
 let row:[String:Any];let clock:Clock;var calls:[String]=[]
 init(_ row:[String:Any],_ clock:Clock){self.row=row;self.clock=clock}
 func resolvedTokenPair(forceRefresh:Bool)->Pair {
  calls.append(forceRefresh ? "forced":"proactive")
  if row["late"] as? Bool == true {clock.expired=true}
  if row["cancelDuring"] as? Bool == true {withUnsafeCurrentTask{$0?.cancel()}}
  return Pair(accessToken:row["access"] as? String,refreshFailure:(row["failure"] as? String).flatMap(Failure.init))
 }
 func getRefreshToken()->String?{calls.append("refresh");return row["refresh"] as? String}
}
struct Client {let stack:Stack
CLIENT
}
@MainActor final class Coordinator {
 let client:Client;let row:[String:Any];let clock:Clock;let timeouts=Timeouts();let log=Logger()
 var sessionGeneration:UInt64=3,signOutEpoch:UInt64=1,tokenStoreWriteHighWater:UInt64=0
 var activeSignInFlows:[String:Flow]=[:]
 var isRevalidatingSession=false,isRestoringSession=false,isAuthenticated=false,isCapturingSignOutCredentials=false
 var timedOutTokenTouchingPhaseStates:[AuthPhase:TimedOutState]=[:],activeTokenTouchingPhases:[UUID:AuthTrackedTokenWork]=[:]
 var latestSignInRefreshToken:String?="old",pendingNonce:String?="challenge",availableTeams=["team"],selectedTeamID:String?="team",authenticatedTeamsSessionGeneration:UInt64?=3
 let userCache=Cache(),sessionCache=Cache();var transitions=0,recoveryCancels=0,applies=0,finishCalls=0
 init(_ row:[String:Any],_ clock:Clock,_ stack:Stack){self.row=row;self.clock=clock;self.client=Client(stack:stack)
  switch row["role"] as! String {
  case "signIn":activeSignInFlows=["flow":Flow(generation:3,signOutEpoch:1)]
  case "staleSignIn":activeSignInFlows=["flow":Flow(generation:2,signOutEpoch:1)]
  case "validationAuthenticated":isRevalidatingSession=true;isAuthenticated=true
  case "validationRestoring":isRevalidatingSession=true;isRestoringSession=true
  case "staleValidation":isRevalidatingSession=true
  case "capture":isCapturingSignOutCredentials=true
  default:break
  }
 }
 func isTokenStorageAvailable()async->Bool{row["available"] as! Bool}
 func onSessionWillTransition(){transitions+=1}
 func cancelTeamScopeRecovery(){recoveryCancels+=1}
 func apply(_ state:State){applies+=1;isAuthenticated=false;isRestoringSession=false}
 func expireTimedOutTokenTouchingPhaseIfNeeded(_ phase:AuthPhase){}
 func gateTokenTouchingPhase(_ phase:AuthPhase,id:UUID){timedOutTokenTouchingPhaseStates[phase]=TimedOutState(id:id)}
 func finishTokenTouchingPhase(generation:UInt64,signOutEpoch:UInt64,storeWriteHighWater:UInt64)async throws{
  finishCalls+=1;if row["retired"] as? Bool == true {throw CancellationError()}
 }
 func waitForTokenTouchingPhase<T:Sendable>(_ task:Task<T,any Error>,id:UUID,phase:AuthPhase,timeout:Duration,deadline:AuthTokenDeadline)async throws->T{try await task.value}
COORDINATOR
}
actor Gate {var open=false;var waiter:CheckedContinuation<Void,Never>?
 func wait()async{if !open{await withCheckedContinuation{waiter=$0}}}
 func release(){open=true;waiter?.resume();waiter=nil}
}
@MainActor func evaluate(_ rows:[[String:Any]])async->[[String:Any]]{
 var output:[[String:Any]]=[]
 for row in rows {
  let clock=Clock();clock.expired=row["preExpired"] as? Bool == true
  let stack=Stack(row,clock),owner=Coordinator(row,clock,stack),gate=Gate()
  let transition=owner.sessionTokenTransitionIsActive
  let task=Task{() -> [String:Any] in await gate.wait()
   do {let access=try await (row["force"] as! Bool ? owner.forceRefreshAccessToken():owner.accessToken());return ["access":access,"error":NSNull()]}
   catch is CancellationError{return ["access":NSNull(),"error":"cancelled"]}
   catch {return ["access":NSNull(),"error":String(describing:error)]}
  }
  if row["cancelBefore"] as? Bool == true{task.cancel()};await gate.release()
  let result=await task.value
  for tracked in owner.activeTokenTouchingPhases.values{await tracked.completion.value}
  output.append(["input":row,"outcome":result,"transitionActive":transition,"calls":await stack.calls,
   "projection":["generation":owner.sessionGeneration,"userCacheClears":owner.userCache.clears,"sessionCacheClears":owner.sessionCache.clears,
    "nonce":owner.pendingNonce as Any? ?? NSNull(),"teams":owner.availableTeams,"selected":owner.selectedTeamID as Any? ?? NSNull(),"transitions":owner.transitions,"applies":owner.applies,"finishCalls":owner.finishCalls]])
 }
 return output
}
let rows=try JSONSerialization.jsonObject(with:Data(INPUT.utf8)) as! [[String:Any]]
let output=await evaluate(rows)
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject:["cases":output,"foundation":["os":ProcessInfo.processInfo.operatingSystemVersionString,"coreFoundationVersion":kCFCoreFoundationVersionNumber]],options:[.sortedKeys]))
'''.replace('CLIENT','\n'.join(client)).replace('COORDINATOR','\n'.join(coordinator)).replace('INPUT',json.dumps(json.dumps(rows)))
with tempfile.TemporaryDirectory(prefix='cmux-application-token-') as folder:
 root=pathlib.Path(folder);(root/'main.swift').write_text(wrapper)
 subprocess.run(['swiftc',str(root/'main.swift'),'-o',str(root/'reference')],check=True,timeout=60)
 result=json.loads(subprocess.check_output([str(root/'reference')],timeout=60))
out=dict(upstream=PIN,wholeOriginalFiles={f:hashlib.sha256(s.encode()).hexdigest() for f,s in sources.items()},executedDeclarationsSha256=[hashlib.sha256(d.encode()).hexdigest() for d in coordinator+client],seams='13 complete unchanged release coordinator/client declarations execute, including both token-touching phase overloads, current-transition property, clearAuthState and generation advancement. Stack pair/refresh replies, availability, clock expiry and phase finish ownership are inert. The original inner Task checks cancellation and late success/failure deadlines. Waiter awaits the original task without the original timer race. Cache/apply/recovery callbacks are inert counters. No Keychain/UserDefaults/Android encryption, original wait race/scheduling/IO, debug sign-in/mock fallback or iOS-runtime equivalence is established.',**result)
a.out.parent.mkdir(parents=True,exist_ok=True);a.out.write_text(json.dumps(out,indent=2)+'\n');print('Executed original application token cases:',len(rows))
