#!/usr/bin/env python3
"""Execute pinned SDK constructors/listTeams and shared identity-store code with inert IO seams."""
import argparse,hashlib,json,pathlib,subprocess,tempfile
PIN='a5911a1f92f3eeb11c4d7f3e2ef89204718c5009'
P='Packages/Shared/CMUXAuthCore/Sources/CMUXAuthCore/'
S='vendor/stack-auth-swift-sdk-prerelease/Sources/StackAuth/Models/'
FILES=dict(user=S+'User.swift',current=S+'CurrentUser.swift',team=S+'Team.swift',
 shared=P+'Models/CMUXAuthUser.swift',store=P+'Persistence/CMUXAuthIdentityStore.swift',backing=P+'Persistence/CMUXAuthKeyValueStore.swift',
 adapter='Packages/Shared/CmuxAuthRuntime/Sources/CmuxAuthRuntime/Client/StackAuthClient.swift')
p=argparse.ArgumentParser();p.add_argument('--upstream',type=pathlib.Path,required=True)
p.add_argument('--out',type=pathlib.Path,default=pathlib.Path('app/src/test/resources/native-sdk-model-reference.json'));a=p.parse_args()
src={k:subprocess.check_output(['git','-C',str(a.upstream),'show',PIN+':'+v]).decode() for k,v in FILES.items()}
def declaration(key,signature):
 text=src[key];start=text.index(signature);end=text.index('{',start)+1;depth=1
 while depth:
  if text[end]=='{':depth+=1
  elif text[end]=='}':depth-=1
  end+=1
 return text[start:end]
declarations=[declaration('current','init(client: APIClient, json: [String: Any])'),
 declaration('current','public func listTeams()'),declaration('team','init(client: APIClient, json: [String: Any])'),
 declaration('adapter','private static func mapped('),declaration('adapter','var cmuxDemonstrationContentEnabled: Bool')]
wrapper=r'''
struct FixtureFailure:Error {}
actor APIClient {
 let raw:String;let fails:Bool
 init(_ raw:String="{}",_ fails:Bool=false){self.raw=raw;self.fails=fails}
 func sendRequest(path:String,method:String,authenticated:Bool)throws->(Data,Int) {
  precondition(path=="/teams?user_id=me" && method=="GET" && authenticated)
  if fails{throw FixtureFailure()};return (Data(raw.utf8),200)
 }
}
actor Team {
 private let client:APIClient;public nonisolated let id:String
 public private(set) var displayName:String;public private(set) var profileImageUrl:String?
 public private(set) var clientMetadata:[String:Any];public private(set) var clientReadOnlyMetadata:[String:Any]
'''+declarations[2]+r'''
}
actor CurrentUser {
 private let client:APIClient;private var userData:User;public let selectedTeam:Team?
 var id:String{userData.id};var displayName:String?{userData.displayName};var primaryEmail:String?{userData.primaryEmail}
 var profileImageUrl:String?{userData.profileImageUrl};var clientReadOnlyMetadata:[String:Any]{userData.clientReadOnlyMetadata}
 var eligible:Bool{!userData.isAnonymous && !userData.isRestricted}
'''+declarations[0]+'\n'+declarations[1]+'\n'+declarations[4]+r'''
}
struct Adapter {
 static func project(_ user:CurrentUser)async->CMUXAuthUser{await mapped(user)}
'''+declarations[3]+r'''
}
final class Backing:CMUXAuthKeyValueStore {
 var values:[String:Any]=[:]
 func bool(forKey key:String)->Bool{values[key] as? Bool ?? false}
 func data(forKey key:String)->Data?{values[key] as? Data}
 func string(forKey key:String)->String?{values[key] as? String}
 func set(_ value:Any?,forKey key:String){values[key]=value}
 func removeObject(forKey key:String){values[key]=nil}
}
func projection(_ user:CMUXAuthUser)->[String:Any]{["id":user.id,"name":user.displayName as Any? ?? NSNull(),"email":user.primaryEmail as Any? ?? NSNull(),"image":user.profileImageURL as Any? ?? NSNull(),"demo":user.demonstrationContentEnabled]}
'''
users=[{}, {'id':5},{'id':''},{'id':' '},{'id':' raw ','display_name':' Name ','primary_email':' email '},
 {'id':'user','display_name':5,'primary_email':False,'profile_image_url':[]},
 *[{'id':'user','selected_team':v} for v in [None,False,[],{}, {'id':4},{'id':''},{'id':' raw '}]],
 {'id':'x'*129,'display_name':'n'*513,'primary_email':'e'*513,'profile_image_url':' url '},
 *[{'id':'user','client_read_only_metadata':{'cmuxReviewDemoContent':v}} for v in [True,False,1,0,'true',{},None]],
 {'id':'user','client_metadata':{'cmuxReviewDemoContent':True}},
 *[{'id':'user',key:v} for key in ['is_anonymous','is_restricted'] for v in [True,False,1,0,2,'true']]]
lists=['','broken','[]','null','"text"','{}','{"items":null}','{"items":{}}','{"items":[]}',
 '{"items":[null]}','{"items":[1]}','{"items":[{},false]}','{"items":[{}]}',
 '{"items":[{"id":7,"display_name":false}]}','{"items":[{"id":"","display_name":""}]}',
 '{"items":[{"id":" raw ","display_name":" Name "}]}',
 '{"items":[{"id":"same","display_name":"First"},{"id":"same","display_name":"Second"}]}',
 json.dumps({'items':[{'id':'x'*129,'display_name':'n'*513}]}),
 '{"items":[{"id":"one"},{"id":"two","display_name":null}]}']
caches=[{'id':'user'},{'id':''},{'id':' '},{'id':4},{'id':'user','displayName':4},
 {'id':'x'*129,'displayName':'n'*513,'primaryEmail':'e'*513},
 *[{'id':'user','demonstrationContentEnabled':v} for v in [True,False,None,1,'true']],
 {'id':'user','profileImageURL':' url '},{'id':'user','profileImageURL':4}]
data=json.dumps(dict(users=users,lists=lists,caches=caches),ensure_ascii=True)
runner=r'''
let input=try JSONSerialization.jsonObject(with:Data(INPUT.utf8)) as! [String:Any]
var userRows:[[String:Any]]=[]
for payload in input["users"] as! [[String:Any]] {
 let u=CurrentUser(client:APIClient(),json:payload);let projected=await Adapter.project(u)
 let b=Backing();let store=CMUXAuthIdentityStore(keyValueStore:b,key:"user");try store.save(projected)
 userRows.append(["payload":payload,"user":projection(projected),"selected":u.selectedTeam?.id as Any? ?? NSNull(),"eligible":await u.eligible,"restored":projection(try store.load()!)])
}
var listRows:[[String:Any]]=[]
for raw in input["lists"] as! [String] {
 let user=CurrentUser(client:APIClient(raw),json:[:]);var values:[[String:Any]]=[]
 for team in try await user.listTeams(){values.append(["id":team.id,"name":await team.displayName])}
 listRows.append(["raw":raw,"teams":values])
}
var cacheRows:[[String:Any]]=[]
for payload in input["caches"] as! [[String:Any]] {
 let b=Backing();b.set(try JSONSerialization.data(withJSONObject:payload),forKey:"user")
 let store=CMUXAuthIdentityStore(keyValueStore:b,key:"user")
 let value=try? store.load();cacheRows.append(["payload":payload,"user":value.map(projection) as Any? ?? NSNull()])
}
let large:[String:Any]=["items":(0..<4097).map{["id":"t\($0)","display_name":"N"]},"padding":String(repeating:"x",count:2_100_000)]
let largeRaw=String(data:try JSONSerialization.data(withJSONObject:large),encoding:.utf8)!
let largeUser=CurrentUser(client:APIClient(largeRaw),json:[:]);let largeTeams=try await largeUser.listTeams()
var transportThrows=false;do{_ = try await CurrentUser(client:APIClient("{}",true),json:[:]).listTeams()}catch{transportThrows=true}
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject:["users":userRows,"lists":listRows,"caches":cacheRows,"boundaries":["rows":largeTeams.count,"rawBytes":largeRaw.utf8.count,"transportFailureThrows":transportThrows]],options:[.sortedKeys]))
'''.replace('INPUT',json.dumps(data))
with tempfile.TemporaryDirectory(prefix='cmux-sdk-model-') as directory:
 root=pathlib.Path(directory);swift=root/'main.swift'
 swift.write_text('import CoreFoundation\n'+'\n'.join(src[k] for k in ['user','shared','backing','store'])+'\n'+wrapper+runner)
 subprocess.run(['swiftc',str(swift),'-o',str(root/'reference')],check=True,timeout=60)
 rows=json.loads(subprocess.check_output([str(root/'reference')],timeout=30))
result=dict(upstream=PIN,wholeOriginalFiles={FILES[k]:hashlib.sha256(v.encode()).hexdigest() for k,v in src.items()},
 executedDeclarationsSha256=[hashlib.sha256(v.encode()).hexdigest() for v in declarations],
 seams='Inert APIClient returns controlled body/transport failure. Complete original list and constructor/mapping declarations and shared User/cache sources execute. Not the original APIClient/getUser/token/timer, scope publisher, UI or platform storage.',**rows)
a.out.parent.mkdir(parents=True,exist_ok=True);a.out.write_text(json.dumps(result,indent=2)+'\n')
print('Original SDK projections:',{k:len(rows[k]) for k in ['users','lists','caches']},rows['boundaries'])
