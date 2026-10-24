#!/usr/bin/env python3
"""Execute original coordinator refresh, selected-Team constructors and selection persistence."""
import argparse,hashlib,json,pathlib,subprocess,tempfile
PIN='a5911a1f92f3eeb11c4d7f3e2ef89204718c5009'
P='Packages/Shared/'
FILES=dict(coordinator=P+'CmuxAuthRuntime/Sources/CmuxAuthRuntime/Coordinator/AuthCoordinator.swift',
 timeouts=P+'CmuxAuthRuntime/Sources/CmuxAuthRuntime/Coordinator/AuthTimeouts.swift',
 team=P+'CMUXAuthCore/Sources/CMUXAuthCore/Models/CMUXAuthTeam.swift',
 store=P+'CMUXAuthCore/Sources/CMUXAuthCore/Persistence/CMUXAuthTeamSelectionStore.swift',
 backing=P+'CMUXAuthCore/Sources/CMUXAuthCore/Persistence/CMUXAuthKeyValueStore.swift',
 user='vendor/stack-auth-swift-sdk-prerelease/Sources/StackAuth/Models/User.swift',
 current='vendor/stack-auth-swift-sdk-prerelease/Sources/StackAuth/Models/CurrentUser.swift',
 sdkTeam='vendor/stack-auth-swift-sdk-prerelease/Sources/StackAuth/Models/Team.swift')
p=argparse.ArgumentParser();p.add_argument('--upstream',type=pathlib.Path,required=True);p.add_argument('--out',type=pathlib.Path,default=pathlib.Path('app/src/test/resources/native-team-refresh-reference.json'));a=p.parse_args()
sources={key:subprocess.check_output(['git','-C',str(a.upstream),'show',PIN+':'+path]).decode() for key,path in FILES.items()}
def declaration(source,signature):
 start=source.index(signature);opening=source.index('{',start);depth=1;end=opening+1
 while depth:
  if source[end]=='{':depth+=1
  elif source[end]=='}':depth-=1
  end+=1
 return source[start:end]
refresh=declaration(sources['coordinator'],'func refreshTeams(generation: UInt64) async')
resolve=declaration(sources['coordinator'],'private static func resolveTeamID(')
current=declaration(sources['current'],'init(client: APIClient, json: [String: Any])')
team=declaration(sources['sdkTeam'],'init(client: APIClient, json: [String: Any])')
harness=r'''
import OSLog
private let authLog=Logger(subsystem:"cmux.fixture.reference",category:"refresh")
enum StackAuthRateLimitReporting {@TaskLocal static var callerReports=false}
enum AuthPhase {case listTeams}
enum Event {case sessionCheck}
struct FixtureFailure:Error {}
final class Telemetry {var successes=0;func succeeded(_ event:Event){successes+=1}}
actor Signals {
 var started:Set<String>=[];var released=false
 var both:[CheckedContinuation<Void,Never>]=[];var waits:[CheckedContinuation<Void,Never>]=[]
 func begin(_ name:String) {started.insert(name);if started.count==2 {let list=both;both=[];for c in list {c.resume()}}}
 func awaitBoth() async {if started.count<2 {await withCheckedContinuation{both.append($0)}}}
 func wait() async {if !released {await withCheckedContinuation{waits.append($0)}}}
 func release(){released=true;let list=waits;waits=[];for c in list {c.resume()}}
}
actor FixtureClient {
 let signals:Signals;let teams:[CMUXAuthTeam];let selected:String?;let failure:String?
 init(_ signals:Signals,_ ids:[String],_ selected:String?,_ failure:String?){self.signals=signals;teams=ids.map{CMUXAuthTeam(id:$0,displayName:$0)};self.selected=selected;self.failure=failure}
 func listTeams() async throws ->[CMUXAuthTeam] {await signals.begin("membership");await signals.wait();if failure=="membership" {throw FixtureFailure()};return teams}
 func selectedTeamID() async throws ->String? {await signals.begin("selection");await signals.wait();if failure=="selection" {throw FixtureFailure()};if failure=="selectedCancellation" {throw CancellationError()};return selected}
}
@MainActor final class AuthCoordinator {
 var activeTeamRefreshCount=0;var sessionGeneration:UInt64=0;let client:FixtureClient;let timeouts=AuthTimeouts.default
 var availableTeams=[CMUXAuthTeam(id:"old",displayName:"Old")];var selectedTeamID:String?;var authenticatedTeamsSessionGeneration:UInt64?
 var teamScopeRateLimit:Int?=3;let rateLimitTelemetry=Telemetry();var failures=0;var recoveries=0;var phases=0
 init(_ client:FixtureClient,_ remembered:String?){self.client=client;selectedTeamID=remembered}
 func runPhase<T>(_ phase:AuthPhase,timeout:Duration,operation:@escaping @Sendable () async throws ->T) async throws ->T {phases+=1;return try await operation()}
 func noteTeamScopeFailure(_ error:any Error){failures+=1}
 func scheduleTeamScopeRecoveryIfNeeded(){recoveries+=1}
'''
constructors=r'''
final class APIClient: @unchecked Sendable {}
actor Team {
 private let client:APIClient;public nonisolated let id:String
 public private(set) var displayName:String;public private(set) var profileImageUrl:String?
 public private(set) var clientMetadata:[String:Any];public private(set) var clientReadOnlyMetadata:[String:Any]
'''+team+r'''
}
actor CurrentUser {
 private let client:APIClient;private var userData:User;public let selectedTeam:Team?
'''+current+r'''
}
final class Backing:CMUXAuthKeyValueStore {
 var values:[String:Any]=[:]
 func bool(forKey key:String)->Bool {values[key] as? Bool ?? false}
 func data(forKey key:String)->Data? {values[key] as? Data}
 func string(forKey key:String)->String? {values[key] as? String}
 func set(_ value:Any?,forKey key:String){values[key]=value}
 func removeObject(forKey key:String){values[key]=nil}
}
'''
runner=r'''
@MainActor func evaluate() async throws {
 var rows:[[String:Any]]=[]
 let cases:[(String,[String],String?,String?,String?,Bool)]=[
 ("server-wins",["one","two"],"two","one",nil,false),
 ("selected-fails",["one","two"],"one","two","selection",false),
 ("selected-cancels",["one","two"],"one","two","selectedCancellation",false),
 ("selected-nil",["one","two"],nil,"two",nil,false),
 ("selected-stale",["one","two"],"stale","two",nil,false),
 ("empty-remembers",[],nil,"remembered",nil,false),
 ("empty-server",[],"external","remembered",nil,false),
 ("empty-string",[],"","remembered",nil,false),
 ("membership-fails",["one","two"],"two","remembered","membership",false),
 ("stale-generation",["one","two"],"two","remembered",nil,true)]
 for (name,ids,selected,remembered,failure,stale) in cases {
  let signals=Signals();let client=FixtureClient(signals,ids,selected,failure);let c=AuthCoordinator(client,remembered)
  let task=Task {@MainActor in await c.refreshTeams(generation:0)}
  await signals.awaitBoth();let startedCount=await signals.started.count;let parallel=c.activeTeamRefreshCount==1 && startedCount==2
  if stale {c.sessionGeneration=1};await signals.release();await task.value
  rows.append(["case":name,"members":ids,"serverSelected":selected as Any? ?? NSNull(),"remembered":remembered as Any? ?? NSNull(),"failure":failure as Any? ?? NSNull(),"stale":stale,
   "publishedMembers":c.availableTeams.map(\.id),"selected":c.selectedTeamID as Any? ?? NSNull(),"loaded":c.authenticatedTeamsSessionGeneration != nil,
   "failures":c.failures,"recoveries":c.recoveries,"successes":c.rateLimitTelemetry.successes,"active":c.activeTeamRefreshCount,"parallel":parallel,"phases":c.phases])
 }
 var projections:[[String:Any]]=[]
 let payloads:[String]=[#"{}"#,#"{"selected_team":null}"#,#"{"selected_team":false}"#,#"{"selected_team":[]}"#,#"{"selected_team":"two"}"#,#"{"selected_team":{}}"#,#"{"selected_team":{"id":4}}"#,#"{"selected_team":{"id":""}}"#,#"{"selected_team":{"id":" two "}}"#,#"{"display_name":4,"selected_team":{"id":"two"}}"#]
 for raw in payloads {
  let payload=try JSONSerialization.jsonObject(with:Data(raw.utf8)) as! [String:Any];let user=CurrentUser(client:APIClient(),json:payload)
  projections.append(["payload":payload,"selected":user.selectedTeam?.id as Any? ?? NSNull()])
 }
 var persistence:[[String:Any]]=[]
 for value:String? in [nil,""," \n"," two ","remembered","\u{0085}two\u{0085}","\u{00a0}two\u{00a0}","\u{2007}two\u{202f}","\u{200b}"] {
  let backing=Backing();let store=CMUXAuthTeamSelectionStore(keyValueStore:backing,key:"choice");store.selectedTeamID=value
  persistence.append(["input":value as Any? ?? NSNull(),"stored":backing.string(forKey:"choice") as Any? ?? NSNull(),"restored":store.selectedTeamID as Any? ?? NSNull()])
 }
 let data=try JSONSerialization.data(withJSONObject:["refresh":rows,"selectedProjection":projections,"persistence":persistence],options:[.prettyPrinted,.sortedKeys]);FileHandle.standardOutput.write(data)
}
try await evaluate()
'''
with tempfile.TemporaryDirectory(prefix='cmux-team-refresh-reference-') as directory:
 root=pathlib.Path(directory);swift=root/'main.swift';swift.write_text('\n'.join(sources[key] for key in ['timeouts','team','user','backing','store'])+'\n'+harness+'\n'+refresh+'\n'+resolve+'\n}\n'+constructors+runner)
 subprocess.run(['swiftc',str(swift),'-o',str(root/'reference')],check=True,timeout=60)
 rows=json.loads(subprocess.check_output([str(root/'reference')],timeout=30))
result=dict(upstream=PIN,wholeOriginalFiles={FILES[key]:hashlib.sha256(value.encode()).hexdigest() for key,value in sources.items()},executedDeclarationSha256=[hashlib.sha256(value.encode()).hexdigest() for value in [refresh,resolve,current,team]],**rows)
a.out.parent.mkdir(parents=True,exist_ok=True);a.out.write_text(json.dumps(result,indent=2)+'\n');print('Executed original refresh:',len(rows['refresh']),'cases;',len(rows['selectedProjection']),'constructor projections;',len(rows['persistence']),'store cases')
