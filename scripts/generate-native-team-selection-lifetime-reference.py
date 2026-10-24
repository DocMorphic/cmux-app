#!/usr/bin/env python3
"""Execute original selection publication/clear declarations with isolated cache/client seams."""
import argparse, hashlib, json, pathlib, subprocess, tempfile

PIN = 'a5911a1f92f3eeb11c4d7f3e2ef89204718c5009'
BASE = 'Packages/Shared/'
FILES = dict(
    coordinator=BASE+'CmuxAuthRuntime/Sources/CmuxAuthRuntime/Coordinator/AuthCoordinator.swift',
    flow=BASE+'CmuxAuthRuntime/Sources/CmuxAuthRuntime/Coordinator/SignInFlowContext.swift',
    timeouts=BASE+'CmuxAuthRuntime/Sources/CmuxAuthRuntime/Coordinator/AuthTimeouts.swift',
    user=BASE+'CMUXAuthCore/Sources/CMUXAuthCore/Models/CMUXAuthUser.swift',
    team=BASE+'CMUXAuthCore/Sources/CMUXAuthCore/Models/CMUXAuthTeam.swift',
    store=BASE+'CMUXAuthCore/Sources/CMUXAuthCore/Persistence/CMUXAuthTeamSelectionStore.swift',
    backing=BASE+'CMUXAuthCore/Sources/CMUXAuthCore/Persistence/CMUXAuthKeyValueStore.swift')
p=argparse.ArgumentParser();p.add_argument('--upstream',type=pathlib.Path,required=True)
p.add_argument('--out',type=pathlib.Path,required=True);a=p.parse_args()
sources={key:subprocess.check_output(['git','-C',str(a.upstream),'show',PIN+':'+path]).decode() for key,path in FILES.items()}
def declaration(signature):
    source=sources['coordinator'];start=source.index(signature);end=source.index('{',start)+1;depth=1
    while depth:
        if source[end]=='{':depth+=1
        elif source[end]=='}':depth-=1
        end+=1
    return source[start:end]
signatures=['public var selectedTeamID: String?', 'public var resolvedTeamID: String?',
    'private func beginSignInFlow()', 'private func finishSignInFlow(', 'enum SessionPublication',
    'func applySignedInUser(', 'func refreshTeams(generation: UInt64)',
    'private static func resolveTeamID(', 'func clearAuthState(', 'private func advanceSessionGeneration(']
declarations=[declaration(s) for s in signatures]
wrapper=r'''
import CoreFoundation
import OSLog
private let authLog=Logger(subsystem:"cmux.fixture.reference",category:"selection-lifetime")
enum StackAuthRateLimitReporting {@TaskLocal static var callerReports=false}
enum AuthPhase {case listTeams}
enum Event {case sessionCheck}
struct FixtureFailure:Error {}
final class Telemetry {func succeeded(_ event:Event){}}
final class Backing:CMUXAuthKeyValueStore {
 var values:[String:Any]=[:]
 func bool(forKey key:String)->Bool {values[key] as? Bool ?? false}
 func data(forKey key:String)->Data? {values[key] as? Data}
 func string(forKey key:String)->String? {values[key] as? String}
 func set(_ value:Any?,forKey key:String){values[key]=value}
 func removeObject(forKey key:String){values[key]=nil}
}
final class Cache {func clear(){};func setHasTokens(_ value:Bool){}}
struct State {static func cleared()->Self{Self()}}
actor FixtureClient {
 var teams:[CMUXAuthTeam]=[];var selected:String?;var fail=false
 func configure(_ ids:[String],_ selected:String?,_ fail:Bool){teams=ids.map{CMUXAuthTeam(id:$0,displayName:$0)};self.selected=selected;self.fail=fail}
 func listTeams()throws->[CMUXAuthTeam] {if fail{throw FixtureFailure()};return teams}
 func selectedTeamID()->String? {selected}
}
@MainActor final class AuthCoordinator {
 let teamSelection:CMUXAuthTeamSelectionStore;let client=FixtureClient();let timeouts=AuthTimeouts.default
 let userCache=Cache();let sessionCache=Cache();let rateLimitTelemetry=Telemetry()
 var availableTeams:[CMUXAuthTeam]=[];var sessionGeneration:UInt64=0;var authenticatedTeamsSessionGeneration:UInt64?
 var currentUser:CMUXAuthUser?;var isAuthenticated=false;var isRestoringSession=false
 var activeTeamRefreshCount=0;var teamScopeRateLimit:Int?;var latestSignInRefreshToken:String?;var pendingNonce:String?
 var signInAttemptCounter:UInt64=0;var signOutEpoch:UInt64=0;var activeSignInFlows:[UInt64:SignInFlowContext]=[:]
 let onSessionWillTransition:()->Void={};let onSignedIn:@Sendable ()async->Void={}
 init(_ store:CMUXAuthTeamSelectionStore){teamSelection=store;selectedTeamID=store.selectedTeamID}
 func waitForSessionTokenWorkToQuiesceBeforeSignIn()async throws{}
 func publishAuthenticatedSessionIdentity(){};func publishAuthenticatedTeamScope(){}
 func saveCachedUser(_ user:CMUXAuthUser){};func cancelTeamScopeRecovery(){}
 func apply(_ state:State){isAuthenticated=false;currentUser=nil;isRestoringSession=false}
 func runPhase<T>(_ phase:AuthPhase,timeout:Duration,operation:@escaping @Sendable ()async throws->T)async throws->T{try await operation()}
 func runPostSignInHook(timeout:Duration,operation:@escaping @Sendable ()async->Void)async{await operation()}
 func noteTeamScopeFailure(_ error:any Error){};func scheduleTeamScopeRecoveryIfNeeded(){}
 func exerciseEntry()async throws{let flow=try await beginSignInFlow();finishSignInFlow(flow)}
 func exerciseGeneration(){advanceSessionGeneration()}
'''
runner=r'''
@MainActor func evaluate()async throws {
 var rows:[[String:Any]]=[]
 for action in ["entry","generation","sign-in","changed-user","empty-list","fallback","server-choice","clear","clear-preserving-code","reconstruct"] {
  let backing=Backing();let store=CMUXAuthTeamSelectionStore(keyValueStore:backing,key:"choice")
  store.selectedTeamID=" two ";let c=AuthCoordinator(store)
  let first=CMUXAuthUser(id:"first",primaryEmail:nil,displayName:nil)
  let second=CMUXAuthUser(id:"second",primaryEmail:nil,displayName:nil)
  await c.client.configure([],nil,true)
  switch action {
  case "entry":try await c.exerciseEntry()
  case "generation":c.availableTeams=[CMUXAuthTeam(id:"old",displayName:"Old")];c.authenticatedTeamsSessionGeneration=0;c.exerciseGeneration()
  case "sign-in":await c.applySignedInUser(first,publication:.signIn)
  case "changed-user":
   await c.client.configure(["two"],nil,false);await c.applySignedInUser(first,publication:.signIn)
   await c.client.configure([],nil,true);await c.applySignedInUser(second,publication:.revalidation)
  case "empty-list":await c.client.configure([],nil,false);await c.applySignedInUser(first,publication:.signIn)
  case "fallback":await c.client.configure(["one"],nil,false);await c.applySignedInUser(first,publication:.signIn)
  case "server-choice":await c.client.configure(["one","two"],"one",false);await c.applySignedInUser(first,publication:.signIn)
  case "clear":c.clearAuthState()
  case "clear-preserving-code":c.clearAuthState(preservePendingCode:true)
  case "reconstruct":break
  default:fatalError()
  }
  let cold=AuthCoordinator(store)
  rows.append(["case":action,"selected":c.selectedTeamID as Any? ?? NSNull(),"persisted":store.selectedTeamID as Any? ?? NSNull(),
   "coldSelected":cold.selectedTeamID as Any? ?? NSNull(),"resolved":c.resolvedTeamID as Any? ?? NSNull(),
   "members":c.availableTeams.map(\.id),"membershipLoaded":c.authenticatedTeamsSessionGeneration != nil,
   "authenticated":c.isAuthenticated,"user":c.currentUser?.id as Any? ?? NSNull(),"generation":c.sessionGeneration])
 }
 FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject:rows,options:[.sortedKeys]))
}
try await evaluate()
'''
with tempfile.TemporaryDirectory(prefix='cmux-selection-lifetime-') as directory:
    root=pathlib.Path(directory);swift=root/'main.swift'
    swift.write_text('import CoreFoundation\n'+'\n'.join(sources[key] for key in ['flow','timeouts','user','team','backing','store'])+
        '\n'+wrapper+'\n'+'\n'.join(declarations)+'\n}\n'+runner)
    subprocess.run(['swiftc',str(swift),'-o',str(root/'reference')],check=True,timeout=60)
    rows=json.loads(subprocess.check_output([str(root/'reference')],timeout=30))
result=dict(upstream=PIN,wholeOriginalFiles={FILES[key]:hashlib.sha256(value.encode()).hexdigest() for key,value in sources.items()},
    declarations={signature:hashlib.sha256(value.encode()).hexdigest() for signature,value in zip(signatures,declarations)},
    seams='Memory backing, fake team client, nonblocking preflight/phase/hook and inert user/session caches/publication notifications. No original SDK, timer, transport, sign-in exchange or full scope publisher.',rows=rows)
a.out.parent.mkdir(parents=True,exist_ok=True);a.out.write_text(json.dumps(result,indent=2)+'\n')
print('Executed original selection lifetime:',len(rows),'cases;',len(declarations),'complete declarations')
