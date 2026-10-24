#!/usr/bin/env python3
"""Execute pinned getUser, its app adapters and sign-in error translation."""
import argparse, base64, hashlib, json, pathlib, subprocess, tempfile
PIN='a5911a1f92f3eeb11c4d7f3e2ef89204718c5009'
SDK='vendor/stack-auth-swift-sdk-prerelease/Sources/StackAuth/'
CORE='Packages/Shared/CMUXAuthCore/Sources/CMUXAuthCore/Models/'
RUNTIME='Packages/Shared/CmuxAuthRuntime/Sources/CmuxAuthRuntime/'
FILES=dict(stack=SDK+'StackClientApp.swift',errors=SDK+'Errors.swift',user=SDK+'Models/User.swift',
 current=SDK+'Models/CurrentUser.swift',team=SDK+'Models/Team.swift',mapped=CORE+'CMUXAuthUser.swift',
 summary=CORE+'CMUXAuthTeam.swift',client=RUNTIME+'Client/StackAuthClient.swift',
 coordinator=RUNTIME+'Coordinator/AuthCoordinator.swift',auth=RUNTIME+'Coordinator/AuthError.swift',
 display=RUNTIME+'Coordinator/AuthError+DisplaySafe.swift',detail=RUNTIME+'Coordinator/AuthError+FailureDetail.swift',
 action=RUNTIME+'Coordinator/CachedSessionValidationFailureAction.swift')
p=argparse.ArgumentParser();p.add_argument('--upstream',type=pathlib.Path,required=True)
p.add_argument('--out',type=pathlib.Path,default=pathlib.Path('app/src/test/resources/auth/native-user-fetch-reference.json'));a=p.parse_args()
src={k:subprocess.check_output(['git','-C',str(a.upstream),'show',PIN+':'+v]).decode() for k,v in FILES.items()}
decls=[]
def decl(key,signature):
 text=src[key];start=text.index(signature);end=text.index('{',start)+1;depth=1
 while depth:
  if text[end]=='{':depth+=1
  elif text[end]=='}':depth-=1
  end+=1
 result=text[start:end];decls.append((key,signature,result));return result
def flatten(text):return '\n'.join(l for l in text.splitlines() if not l.startswith(('import StackAuth','public import ')))
rows=[]
def add(name,raw='{"id":"user","display_name":" User ","selected_team":{"id":"team"}}',**kw):
 rows.append(dict(case=name,wire=base64.b64encode(raw.encode()).decode(),**kw))
add('valid');add('missing-token',hasTokens=False)
for name,raw in [('invalid-json','private-body-canary'),('empty',''),('array','[]'),('null','null'),('empty-object','{}'),
                 ('anonymous','{"id":"user","is_anonymous":true}'),('restricted','{"id":"user","is_restricted":true}'),
                 ('numeric-anonymous-one','{"id":"user","is_anonymous":1}'),('numeric-restricted-two','{"id":"user","is_restricted":2}'),
                 ('selected-empty','{"id":"user","selected_team":{}}')]:add(name,raw)
for code in ['SCHEMA_ERROR','RATE_LIMITED','rate_limited','RATE_LIMIT','USER_NOT_SIGNED_IN','UNAUTHORIZED',
             'invalid_access_token','http_error','network_error','INVALID_OTP','VERIFICATION_CODE_ERROR','USER_EMAIL_ALREADY_EXISTS','']:
 add('user-error-'+repr(code),error=code)
for kind in ['network','cancel','foreign-rate']:add('user-'+kind,errorKind=kind)
for raw in ['not-json','{}','{"items":[]}','{"items":[{"id":"","display_name":""}]}']:
 add('teams-body-'+raw,teamsWire=base64.b64encode(raw.encode()).decode())
for code in ['SCHEMA_ERROR','RATE_LIMITED','rate_limited','http_error']:
 add('teams-error-'+code,teamsError=code)
consumers=['sdk-throw','sdk-null','client-throw','client-null','teams','selected','sign-in']

current_props='\n'.join(decl('current','public var '+name+':') for name in ['id','displayName','primaryEmail','profileImageUrl','clientReadOnlyMetadata','isAnonymous','isRestricted'])
current_init=decl('current','init(client: APIClient, json: [String: Any])')
current_list=decl('current','public func listTeams()')
team_init=decl('team','init(client: APIClient, json: [String: Any])')
get_user=decl('stack','public func getUser(or:')
no_user=decl('stack','private func handleNoUser(or:')
mapped=decl('client','private static func mapped(')
demo=decl('client','extension CurrentUser {')
adapters='\n'.join(decl('client',signature) for signature in ['public func currentUser(throwOnMissing:', 'public func listTeams()', 'public func selectedTeamID()'])
completion=decl('coordinator','private func completeSignIn(flow:')
wrapper=r'''
public protocol TokenStoreProtocol {}
public enum TokenStoreInit {case fixture}
struct Store:TokenStoreProtocol {}
struct ForeignRate:StackAuthErrorProtocol {
 let code="RATE_LIMITED",message="private-canary";let details:[String:Any]?=nil;var description:String{code}
}
actor APIClient {
 let row:[String:Any];var calls:[String]=[];var preflights=0
 init(_ row:[String:Any]){self.row=row}
 func getAccessToken() -> String?{preflights+=1;return row["hasTokens"] as? Bool == false ? nil : "fixture-access"}
 func getAccessToken(tokenStoreOverride:any TokenStoreProtocol)->String?{getAccessToken()}
 func sendRequest(path:String,method:String="GET",authenticated:Bool=false,tokenStoreOverride:(any TokenStoreProtocol)?=nil) throws -> (Data,Int) {
  calls.append(path)
  if path=="/users/me" {
   if let code=row["error"] as? String {throw StackAuthError.from(code:code,message:"private-canary",details:["httpStatus":503,"requestId":"request"])}
   switch row["errorKind"] as? String {
   case "network":throw URLError(.notConnectedToInternet)
   case "cancel":throw CancellationError()
   case "foreign-rate":throw ForeignRate()
   default:break
   }
   return (Data(base64Encoded:row["wire"] as! String)!,200)
  }
  if let code=row["teamsError"] as? String {throw StackAuthError.from(code:code,message:"private-canary",details:["httpStatus":503,"requestId":"request"])}
  return (Data(base64Encoded:row["teamsWire"] as? String ?? "eyJpdGVtcyI6W3siaWQiOiJ0ZWFtIiwiZGlzcGxheV9uYW1lIjoiVGVhbSJ9XX0=")!,200)
 }
}
public actor Team {
 private let client:APIClient;public nonisolated let id:String
 public var displayName:String;var profileImageUrl:String?;var clientMetadata:[String:Any];var clientReadOnlyMetadata:[String:Any]
TEAM_INIT
}
public actor CurrentUser {
 private let client:APIClient;private var userData:User;public let selectedTeam:Team?
CURRENT_PROPS
CURRENT_INIT
CURRENT_LIST
}
public actor StackClientApp {
 public enum GetUserOr {case returnNull,redirect,`throw`,anonymous}
 let client:APIClient
 init(_ client:APIClient){self.client=client}
 func resolveTokenStore(_ value:TokenStoreInit?)->(any TokenStoreProtocol)?{value == nil ? nil : Store()}
 func signUpAnonymously(tokenStoreOverride:(any TokenStoreProtocol)?) async throws {fatalError("Not an app consumer in this corpus")}
GET_USER
NO_USER
}
enum AuthClientError:Error {case userUnavailable}
struct StackAuthClient {
 let stack:StackClientApp
ADAPTERS
MAPPED
 func refreshToken() async->String?{"fixture-refresh"}
 func clearLocalSession() async {}
}
DEMO
actor AuthCoordinator {
 struct SignInFlowContext {let generation:UInt64=0,attempt:UInt64=1,signOutEpoch:UInt64=0}
 struct Timeouts {let network:Double=60}
 enum Phase {case fetchUser};enum Publication {case signIn}
 let client:StackAuthClient;let timeouts=Timeouts();var published:CMUXAuthUser?
 var tokenStoreWriteHighWater:UInt64=0,sessionGeneration:UInt64=0,signOutEpoch:UInt64=0
 var latestSignInRefreshToken:String?;var isAuthenticated=false
 init(_ client:StackAuthClient){self.client=client}
 func runPhase<T>(_ phase:Phase,timeout:Double,operation:() async throws->T) async rethrows->T {try await operation()}
 func applySignedInUser(_ user:CMUXAuthUser,publication:Publication) async {published=user;isAuthenticated=true}
COMPLETION
 func complete() async throws {try await completeSignIn(flow:SignInFlowContext())}
}
'''
for token,value in dict(TEAM_INIT=team_init,CURRENT_PROPS=current_props,CURRENT_INIT=current_init,CURRENT_LIST=current_list,
 GET_USER=get_user,NO_USER=no_user,ADAPTERS=adapters,MAPPED=mapped,DEMO=demo,COMPLETION=completion).items():wrapper=wrapper.replace(token,value)
runner=r'''
let input=try JSONSerialization.jsonObject(with:Data(INPUT.utf8)) as! [[String:Any]]
let consumers=CONSUMERS
func userProjection(_ user:CurrentUser?) async->[String:Any] {
 guard let user else{return ["nil":true]}
 return ["id":await user.id,"name":await user.displayName as Any? ?? NSNull(),"selected":await user.selectedTeam?.id as Any? ?? NSNull()]
}
var output:[[String:Any]]=[]
for row in input {for consumer in consumers {
 let api=APIClient(row),stack=StackClientApp(api);let client=StackAuthClient(stack:stack)
 var outcome:[String:Any]
 do {
  switch consumer {
  case "sdk-throw","sdk-null":outcome=await userProjection(try await stack.getUser(or:consumer == "sdk-throw" ? .throw : .returnNull))
  case "client-throw","client-null":
   let user=try await client.currentUser(throwOnMissing:consumer == "client-throw")
   outcome=user.map{["id":$0.id,"name":$0.displayName as Any? ?? NSNull()]} ?? ["nil":true]
  case "selected":outcome=["selected":try await client.selectedTeamID() as Any? ?? NSNull()]
  case "teams":outcome=["teams":try await client.listTeams().map{["id":$0.id,"name":$0.displayName]}]
  default:
   let coordinator=AuthCoordinator(client);try await coordinator.complete()
   outcome=["id":await coordinator.published!.id]
  }
 } catch {
  outcome=["error":(error as? any StackAuthErrorProtocol)?.code ?? (error is AuthClientError ? "user_unavailable" : AuthError.failureDetail(for:error))]
  let translated=AuthError(displaySafe:error) ?? error
  outcome["displayDetail"]=AuthError.failureDetail(for:translated)
 }
 output.append(["input":row,"consumer":consumer,"outcome":outcome,"calls":await api.calls,"preflights":await api.preflights])
}}
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject:["cases":output,"foundation":["os":ProcessInfo.processInfo.operatingSystemVersionString,"coreFoundationVersion":kCFCoreFoundationVersionNumber]],options:[.sortedKeys]))
'''.replace('INPUT',json.dumps(json.dumps(rows))).replace('CONSUMERS',json.dumps(consumers))
with tempfile.TemporaryDirectory(prefix='cmux-user-fetch-') as directory:
 root=pathlib.Path(directory)
 whole='\n'.join(flatten(src[k]) for k in ['errors','user','mapped','summary','auth','action','display','detail'])
 (root/'main.swift').write_text('import CoreFoundation\n'+whole+'\n'+wrapper+runner)
 subprocess.run(['swiftc',str(root/'main.swift'),'-o',str(root/'reference')],check=True,timeout=60)
 result=json.loads(subprocess.check_output([str(root/'reference')],timeout=30))
out=dict(upstream=PIN,wholeOriginalFiles={FILES[k]:hashlib.sha256(v.encode()).hexdigest() for k,v in src.items()},
 executedDeclarations=[dict(file=FILES[k],signature=s,sha256=hashlib.sha256(v.encode()).hexdigest()) for k,s,v in decls],
 seams='Complete unchanged getUser/handleNoUser, SDK user/team initializers/getters/listTeams, StackAuthClient currentUser/listTeams/selectedTeamID/mapped/demo extension, and coordinator completeSignIn execute. Whole original models, Errors, AuthError/display-safe/failure-detail and validation-action sources compile in one module; module imports are flattened. Inert API supplies token presence and planned response/error; anonymous signup is outside the app corpus. Coordinator phase timing/publication/receipt state is inert: this does not prove token freshness, transport/refresh, ownership races, timers, UI rendering or iOS-runtime behavior.',**result)
a.out.parent.mkdir(parents=True,exist_ok=True);a.out.write_text(json.dumps(out,indent=2)+'\n')
print('Executed user-fetch projections:',len(result['cases']),'complete declarations:',len(decls))
