#!/usr/bin/env python3
"""Execute complete original coherent-context/currentTokens declarations with explicit IO/clock/finish seams."""
import argparse,hashlib,json,pathlib,subprocess,tempfile
PIN='a5911a1f92f3eeb11c4d7f3e2ef89204718c5009'
BASE='Packages/Shared/CmuxAuthRuntime/Sources/CmuxAuthRuntime/Coordinator/'
FILES=[BASE+x for x in ['AuthCoordinator+Tokens.swift','AuthCoordinator+CurrentTokens.swift','AuthCoordinator.swift','AuthCoordinator+TokenTouchingPhaseTimeout.swift']]
p=argparse.ArgumentParser();p.add_argument('--upstream',type=pathlib.Path,required=True);p.add_argument('--out',type=pathlib.Path,default=pathlib.Path('app/src/test/resources/auth/native-cloud-context-reference.json'));a=p.parse_args()
sources={f:subprocess.check_output(['git','-C',str(a.upstream),'show',PIN+':'+f]).decode() for f in FILES}
def decl(file,signature):
 s=sources[file];start=s.index(signature);end=s.index('{',start)+1;depth=1
 while depth:
  if s[end]=='{':depth+=1
  elif s[end]=='}':depth-=1
  end+=1
 return s[start:end]
decls=[decl(FILES[0],s) for s in ['public func coherentTokenPair()','func coherentTokenPairWithoutStateClear()','public func coherentTokenContext()','private func emptyTokenReadError(']]
decls+=[decl(FILES[1],s) for s in ['public func currentTokens()','private func captureCloudTokens()']]
decls+=[decl(FILES[2],'var sessionTokenTransitionIsActive: Bool')]
decls+=[decl(FILES[3],s) for s in ['func runTokenTouchingPhase<T: Sendable>(\n        _ phase: AuthPhase,\n        timeout: Duration,\n        _ operation:','func runTokenTouchingPhase<T: Sendable>(\n        _ phase: AuthPhase,\n        timeout: Duration,\n        deadline:']]
rows=[]
for method in ['pair','context','current']:
 for state in ['complete','missingAccess','missingRefresh','empty','unavailable','transient']:
  for role in ['none','signIn','staleSignIn','validation','capture']:
   for team in [None,'team-a','']:rows.append(dict(method=method,state=state,role=role,team=team))
for method in ['pair','context','current']:
 for action in ['session','teamScope','team','transition','dropRefresh','rotate','churn']:
  rows.append(dict(method=method,state='missingAccess',role='none',team='team-a',event='resolved',action=action))
 for action in ['unavailable','restore','transition']:
  rows.append(dict(method=method,state='missingAccess' if action=='unavailable' else 'unavailable',role='none',team=None,event='availability',action=action))
for action in ['session','teamScope','team','transition','dropRefresh','restore']:
 rows.append(dict(method='current',state='missingAccess',role='none',team=None,event='bootstrap',action=action))
for bootstrap in [0,59999,60000,60001]:
 for resolve in [0,1000]:rows.append(dict(method='current',state='missingAccess',role='none',team=None,bootstrapMillis=bootstrap,resolveMillis=resolve))
for method in ['pair','context','current']:
 rows.append(dict(method=method,state='missingAccess',role='none',team=None,resolveMillis=60001))
 for field in ['cancelBefore','cancelDuring']:rows.append(dict(method=method,state='missingAccess',role='none',team=None,**{field:True}))
assert len(rows)==323
wrapper=r'''
import Foundation
import CoreFoundation
enum AuthError:Error {case networkError,unauthorized,timedOut}
enum AuthPhase:String,Hashable {case accessToken}
struct Flow {let generation:UInt64;let signOutEpoch:UInt64}
struct Timeouts {let network=Duration.seconds(60)}
struct Logger {func log(_ message:String){}}
struct Registry {}
final class Clock:@unchecked Sendable {
 var elapsed:Int64=0
 func authTokenDeadline(after:Duration)->AuthTokenDeadline{AuthTokenDeadline(clock:self,limit:elapsed+60000)}
}
struct AuthTokenDeadline:Sendable {
 let clock:Clock;let limit:Int64
 func hasExpired()->Bool{clock.elapsed>=limit}
 func remaining()->Duration{.milliseconds(max(0,limit-clock.elapsed))}
}
struct AuthTrackedTokenWork {let cancel:()->Void;let completion:Task<Void,Never>}
struct TimedOutState {let id:UUID}
@MainActor func withAuthPhaseTimeout<T>(_ phase:AuthPhase,duration:Duration,clock:Clock,log:Logger,registry:Registry,
 blocksRetriesWhileTimedOutOperationActive:Bool,deadline:AuthTokenDeadline,_ body:()async throws->T)async rethrows->T{try await body()}
@MainActor final class Client {
 unowned let owner:Coordinator
 init(_ owner:Coordinator){self.owner=owner}
 func refreshToken()async->String?{owner.refreshReads+=1;return owner.available ? owner.refresh : nil}
 func resolvedAccessToken(forceRefresh:Bool)async throws->String? {
  owner.resolutions+=1;let value=owner.available ? owner.resolved : nil
  owner.clock.elapsed+=(owner.row["resolveMillis"] as? Int64 ?? 0)
  owner.event("resolved")
  if owner.row["cancelDuring"] as? Bool == true {withUnsafeCurrentTask{$0?.cancel()}}
  return value
 }
}
@MainActor final class Coordinator {
 let row:[String:Any];let clock=Clock();let timeouts=Timeouts();let log=Logger();let phaseTimeoutRegistry=Registry()
 lazy var client=Client(self)
 var sessionGeneration:UInt64=3,signOutEpoch:UInt64=1,tokenStoreWriteHighWater:UInt64=0,authenticatedTeamScopeGeneration:UInt64=7
 var resolvedTeamID:String?;var activeSignInFlows:[String:Flow]=[:]
 var isRevalidatingSession=false,isRestoringSession=false,isAuthenticated=true,isCapturingSignOutCredentials=false
 var refresh:String?="refresh",resolved:String?="resolved",available=true
 var resolutions=0,bootstraps=0,finishCalls=0,refreshReads=0;var fired=false
 var timedOutTokenTouchingPhaseStates:[AuthPhase:TimedOutState]=[:],activeTokenTouchingPhases:[UUID:AuthTrackedTokenWork]=[:]
 init(_ row:[String:Any]){self.row=row;resolvedTeamID=row["team"] as? String
  switch row["state"] as! String {
  case "missingRefresh":refresh=nil
  case "empty":refresh=nil;resolved=nil
  case "unavailable":available=false
  case "transient":resolved=nil
  default:break
  }
  switch row["role"] as! String {
  case "signIn":activeSignInFlows=["flow":Flow(generation:3,signOutEpoch:1)]
  case "staleSignIn":activeSignInFlows=["flow":Flow(generation:2,signOutEpoch:1)]
  case "validation":isRevalidatingSession=true
  case "capture":isCapturingSignOutCredentials=true
  default:break
  }
 }
 func event(_ name:String){guard row["event"] as? String==name else{return}
  let action=row["action"] as! String;if fired && action != "churn"{return};fired=true
  switch action {
  case "session":sessionGeneration+=1
  case "teamScope":authenticatedTeamScopeGeneration+=1
  case "team":resolvedTeamID="team-b"
  case "transition":isCapturingSignOutCredentials=true
  case "dropRefresh":refresh=nil
  case "rotate","churn":refresh="rotation-\(resolutions)"
  case "unavailable":available=false
  case "restore":available=true;refresh="restored-refresh";resolved="restored"
  default:break
  }
 }
 func isTokenStorageAvailable()async->Bool{let value=available;event("availability");return value}
 func awaitBootstrapped()async{bootstraps+=1;clock.elapsed+=(row["bootstrapMillis"] as? Int64 ?? 0);event("bootstrap")}
 func expireTimedOutTokenTouchingPhaseIfNeeded(_ phase:AuthPhase){}
 func gateTokenTouchingPhase(_ phase:AuthPhase,id:UUID){timedOutTokenTouchingPhaseStates[phase]=TimedOutState(id:id)}
 func finishTokenTouchingPhase(generation:UInt64,signOutEpoch:UInt64,storeWriteHighWater:UInt64)async throws{finishCalls+=1}
 func waitForTokenTouchingPhase<T:Sendable>(_ task:Task<T,any Error>,id:UUID,phase:AuthPhase,timeout:Duration,deadline:AuthTokenDeadline)async throws->T{try await task.value}
DECLARATIONS
}
actor Gate {var open=false;var waiter:CheckedContinuation<Void,Never>?
 func wait()async{if !open{await withCheckedContinuation{waiter=$0}}}
 func release(){open=true;waiter?.resume();waiter=nil}
}
@MainActor func evaluate(_ rows:[[String:Any]])async->[[String:Any]]{
 var output:[[String:Any]]=[]
 for row in rows {
  let owner=Coordinator(row),gate=Gate()
  let task=Task{() -> [String:Any] in await gate.wait()
   do {
    let access:String,refresh:String,team:String?
    switch row["method"] as! String {
    case "context":let pair=try await owner.coherentTokenContext();access=pair.accessToken;refresh=pair.refreshToken;team=pair.teamID
    case "current":let pair=try await owner.currentTokens();access=pair.accessToken;refresh=pair.refreshToken;team=nil
    default:let pair=try await owner.coherentTokenPair();access=pair.accessToken;refresh=pair.refreshToken;team=nil
    }
    return ["access":access,"refresh":refresh,"team":team as Any? ?? NSNull(),"error":NSNull()]
   }catch is CancellationError{return ["error":"cancelled"]}
   catch{return ["error":String(describing:error)]}
  }
  if row["cancelBefore"] as? Bool == true{task.cancel()};await gate.release()
  let outcome=await task.value
  for tracked in owner.activeTokenTouchingPhases.values{await tracked.completion.value}
  output.append(["input":row,"outcome":outcome,"resolutions":owner.resolutions,"bootstraps":owner.bootstraps,"finishCalls":owner.finishCalls,"elapsedMillis":owner.clock.elapsed])
 }
 return output
}
let rows=try JSONSerialization.jsonObject(with:Data(INPUT.utf8)) as! [[String:Any]]
let output=await evaluate(rows)
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject:["cases":output,"foundation":["os":ProcessInfo.processInfo.operatingSystemVersionString,"coreFoundationVersion":kCFCoreFoundationVersionNumber]],options:[.sortedKeys]))
'''.replace('DECLARATIONS','\n'.join(decls)).replace('INPUT',json.dumps(json.dumps(rows)))
with tempfile.TemporaryDirectory(prefix='cmux-cloud-context-') as folder:
 root=pathlib.Path(folder);(root/'main.swift').write_text(wrapper)
 subprocess.run(['swiftc',str(root/'main.swift'),'-o',str(root/'reference')],check=True,timeout=60)
 result=json.loads(subprocess.check_output([str(root/'reference')],timeout=60))
out=dict(upstream=PIN,wholeOriginalFiles={f:hashlib.sha256(s.encode()).hexdigest() for f,s in sources.items()},executedDeclarationsSha256=[hashlib.sha256(d.encode()).hexdigest() for d in decls],seams='Nine complete unchanged release declarations execute: coherent public/inner pair, Cloud context, currentTokens/captureCloudTokens, empty-read classifier, transition property and both token-phase overloads. SDK refresh/access, storage availability, bootstrap, clock advancement and phase finish are inert. The bootstrap timeout seam calls the original bootstrap operation and currentTokens checks its absolute deadline; the original timer race/admission/high-water reconciliation and Keychain/Android IO are not executed. Real child-task cancellation and original late success/failure deadline checks run. No full original coordinator, Cloud/UIKit composition, transport, OS lock/death or iOS-runtime equivalence is established.',**result)
a.out.parent.mkdir(parents=True,exist_ok=True);a.out.write_text(json.dumps(out,indent=2)+'\n');print('Executed original Cloud/current token cases:',len(rows))
