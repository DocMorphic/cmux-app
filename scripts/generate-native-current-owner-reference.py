#!/usr/bin/env python3
"""Run unchanged original current-session/transition declarations without storage or SDK seams."""
import argparse,hashlib,itertools,json,pathlib,subprocess,tempfile
PIN='a5911a1f92f3eeb11c4d7f3e2ef89204718c5009'
BASE='Packages/Shared/CmuxAuthRuntime/Sources/CmuxAuthRuntime/Coordinator/'
p=argparse.ArgumentParser();p.add_argument('--upstream',type=pathlib.Path,required=True);p.add_argument('--out',type=pathlib.Path,default=pathlib.Path('app/src/test/resources/auth/native-current-owner-reference.json'));a=p.parse_args()
sources={BASE+n:subprocess.check_output(['git','-C',str(a.upstream),'show',PIN+':'+BASE+n]).decode() for n in ['TokenProviding.swift','AuthCoordinator.swift']}
def declaration(path,signature):
 s=sources[BASE+path];start=s.index(signature);end=s.index('{',start)+1;depth=1
 while depth:
  if s[end]=='{':depth+=1
  elif s[end]=='}':depth-=1
  end+=1
 return s[start:end]
decls=[declaration('TokenProviding.swift','public func isAuthenticatedSessionCurrent('),declaration('AuthCoordinator.swift','var sessionTokenTransitionIsActive: Bool')]
rows=[dict(authenticated=auth,validating=validating,restoring=restoring,capturing=capturing,role=role,generation=generation,user=user)
 for auth,validating,restoring,capturing,role,generation,user in itertools.product([False,True],[False,True],[False,True],[False,True],['none','current','staleGeneration','staleSignOut'],[3,4],[None,'','account-a','account-b'])]
assert len(rows)==512
wrapper=r'''
import Foundation
struct User {let id:String}
struct Flow {let generation:UInt64;let signOutEpoch:UInt64}
public struct AuthenticatedSessionSnapshot {let generation:UInt64;let accountID:String}
@MainActor final class Coordinator {
 var isAuthenticated=false,isRevalidatingSession=false,isRestoringSession=false,isCapturingSignOutCredentials=false
 var sessionGeneration:UInt64=3,signOutEpoch:UInt64=1
 var authSessionGeneration:UInt64{sessionGeneration}
 var activeSignInFlows:[Int:Flow]=[:]
 var currentUser:User?
 DECLARATIONS
}
@main struct Main {
 @MainActor static func main()async throws {
  let rows=try JSONSerialization.jsonObject(with:Data(contentsOf:URL(fileURLWithPath:CommandLine.arguments[1]))) as! [[String:Any]]
  var results:[[String:Any]]=[]
  for row in rows {
   let c=Coordinator();c.isAuthenticated=row["authenticated"] as! Bool;c.isRevalidatingSession=row["validating"] as! Bool
   c.isRestoringSession=row["restoring"] as! Bool;c.isCapturingSignOutCredentials=row["capturing"] as! Bool
   c.sessionGeneration=(row["generation"] as! NSNumber).uint64Value
   if let user=row["user"] as? String{c.currentUser=User(id:user)}
   let role=row["role"] as! String
   if role != "none"{c.activeSignInFlows[1]=Flow(generation:role == "staleGeneration" ? c.sessionGeneration+1:c.sessionGeneration,signOutEpoch:role == "staleSignOut" ? 2:1)}
   let current=await c.isAuthenticatedSessionCurrent(AuthenticatedSessionSnapshot(generation:3,accountID:"account-a"))
   results.append(["input":row,"transition":c.sessionTokenTransitionIsActive,"current":current])
  }
  let data=try JSONSerialization.data(withJSONObject:results,options:[.sortedKeys])
  FileHandle.standardOutput.write(data)
 }
}
'''.replace('DECLARATIONS','\n'.join(decls))
with tempfile.TemporaryDirectory(prefix='cmux-current-owner-reference-') as d:
 d=pathlib.Path(d);src=d/'main.swift';src.write_text(wrapper);input=d/'inputs.json';input.write_text(json.dumps(rows));exe=d/'reference'
 subprocess.run(['swiftc','-parse-as-library',str(src),'-o',str(exe)],check=True)
 cases=json.loads(subprocess.check_output([str(exe),str(input)]))
result=dict(upstream=PIN,wholeOriginalFiles={k:hashlib.sha256(v.encode()).hexdigest() for k,v in sources.items()},executedDeclarationsSha256={str(i):hashlib.sha256(v.encode()).hexdigest() for i,v in enumerate(decls)},cases=cases,seams=['Inert published model and active-flow value storage; the complete original current-session and transition declarations execute unchanged.','No token storage, SDK, timers, phase registry, UI or iOS runtime is executed.','Snapshot identity is the production-captured nonempty account-a at generation3; current model includes nil/empty/different users.'])
a.out.parent.mkdir(parents=True,exist_ok=True);a.out.write_text(json.dumps(result,indent=2,sort_keys=True)+'\n');print('Executed',len(cases),'cases from',len(decls),'unchanged original declarations')
