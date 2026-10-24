#!/usr/bin/env python3
"""Execute original CurrentUser update/selection declarations with a recording HTTP boundary."""
import argparse,hashlib,json,pathlib,subprocess,tempfile
PIN='a5911a1f92f3eeb11c4d7f3e2ef89204718c5009'
CURRENT='vendor/stack-auth-swift-sdk-prerelease/Sources/StackAuth/Models/CurrentUser.swift'
USER='vendor/stack-auth-swift-sdk-prerelease/Sources/StackAuth/Models/User.swift'
COORDINATOR='Packages/Shared/CmuxAuthRuntime/Sources/CmuxAuthRuntime/Coordinator/AuthCoordinator.swift'
TEAM='Packages/Shared/CMUXAuthCore/Sources/CMUXAuthCore/Models/CMUXAuthTeam.swift'
p=argparse.ArgumentParser();p.add_argument('--upstream',type=pathlib.Path,required=True);p.add_argument('--out',type=pathlib.Path,default=pathlib.Path('app/src/test/resources/native-team-selection-reference.json'));a=p.parse_args()
def read(path):return subprocess.check_output(['git','-C',str(a.upstream),'show',PIN+':'+path])
def declaration(source,signature):
 text=source.decode();start=text.index(signature);opening=text.index('{',start);depth=1;end=opening+1
 while depth:
  if text[end]=='{':depth+=1
  elif text[end]=='}':depth-=1
  end+=1
 return text[start:end]
current=read(CURRENT);user=read(USER);coordinator=read(COORDINATOR);team=read(TEAM)
functions=[declaration(current,'public func update('),declaration(current,'public func setSelectedTeam(id teamId: String?)')]
resolve=declaration(coordinator,'private static func resolveTeamID(')
resolver='struct OriginalCoordinator {\n'+resolve+'\nstatic func evaluate(_ selected:String?,_ teams:[CMUXAuthTeam])->String? {resolveTeamID(selectedTeamID:selected,teams:teams)}\n}\n'
harness=r'''
actor RecordingClient {
 let reply:Data
 var recorded:[String:Any]=[:]
 init(_ reply:String) {self.reply=Data(reply.utf8)}
 func sendRequest(path:String,method:String,body:[String:Any],authenticated:Bool) async throws -> (Data,Int) {
  recorded=["path":path,"method":method,"body":body,"authenticated":authenticated];return(reply,200)
 }
}
actor OriginalCurrentUser {
 let client:RecordingClient
 var userData:User
 init(_ client:RecordingClient) {self.client=client;self.userData=User(from:["id":"original-user"])}
'''
runner=r'''
var rows:[[String:Any]]=[]
for id:String? in ["two","one",nil] {
 for (kind,reply) in [("json",#"{"id":"other-user","selected_team":{"id":"other-team"}}"#),("empty",""),("malformed","private-response-canary")] {
  let client=RecordingClient(reply);let user=OriginalCurrentUser(client)
  try await user.setSelectedTeam(id:id)
  rows.append(["selected":id as Any? ?? NSNull(),"responseKind":kind,"succeeded":true,"request":await client.recorded])
 }
}
var resolved:[[String:Any]]=[]
for (selected,ids):(String?,[String]) in [(nil,["one","two"]),("one",["one","two"]),("two",["one","two"]),("missing",["one","two"]),(nil,[]),("remembered",[]),("",[])] {
 let teams=ids.map {CMUXAuthTeam(id:$0,displayName:$0)}
 resolved.append(["selected":selected as Any? ?? NSNull(),"members":ids,"resolved":OriginalCoordinator.evaluate(selected,teams) as Any? ?? NSNull()])
}
let data=try JSONSerialization.data(withJSONObject:["rows":rows,"resolved":resolved],options:[.prettyPrinted,.sortedKeys]);FileHandle.standardOutput.write(data)
'''
with tempfile.TemporaryDirectory(prefix='cmux-team-selection-reference-') as directory:
 root=pathlib.Path(directory);swift=root/'main.swift';swift.write_text(user.decode()+'\n'+team.decode()+'\n'+resolver+harness+'\n'+'\n'.join(functions)+'\n}\n'+runner)
 subprocess.run(['swiftc',str(swift),'-o',str(root/'reference')],check=True);rows=json.loads(subprocess.check_output([str(root/'reference')]))
result=dict(upstream=PIN,wholeOriginalFiles={CURRENT:hashlib.sha256(current).hexdigest(),USER:hashlib.sha256(user).hexdigest(),COORDINATOR:hashlib.sha256(coordinator).hexdigest(),TEAM:hashlib.sha256(team).hexdigest()},
 executedDeclarationSha256=[hashlib.sha256(s.encode()).hexdigest() for s in functions+[resolve]],**rows)
a.out.parent.mkdir(parents=True,exist_ok=True);a.out.write_text(json.dumps(result,indent=2)+'\n');print('Executed complete original declarations:',len(rows['rows']),'wire and',len(rows['resolved']),'resolved-selection cases')
