#!/usr/bin/env python3
"""Execute original StackAuthClient token projections with an inert Stack token-pair seam."""
import argparse,pathlib,hashlib,json,subprocess,tempfile
PIN='a5911a1f92f3eeb11c4d7f3e2ef89204718c5009'
SOURCE='Packages/Shared/CmuxAuthRuntime/Sources/CmuxAuthRuntime/Client/StackAuthClient.swift'
p=argparse.ArgumentParser();p.add_argument('--upstream',type=pathlib.Path,required=True);p.add_argument('--out',type=pathlib.Path,default=pathlib.Path('app/src/test/resources/auth/native-token-adapter-reference.json'));a=p.parse_args()
src=subprocess.check_output(['git','-C',str(a.upstream),'show',PIN+':'+SOURCE]).decode()
def decl(signature):
 start=src.index(signature);end=src.index('{',start)+1;depth=1
 while depth:
  if src[end]=='{':depth+=1
  elif src[end]=='}':depth-=1
  end+=1
 return src[start:end]
decls=[decl('public func accessToken()'),decl('public func resolvedAccessToken(forceRefresh:'),decl('public func forceRefreshAccessToken()')]
rows=[]
for mode in ['ordinary','forced','resolved']:
 for force in ([False,True] if mode=='resolved' else [mode=='forced']):
  for failure in [None,'cancelled','sessionChanged','timedOut']:
   for access in [None,'access']:
    for cancel in [False,True]:rows.append(dict(mode=mode,force=force,failure=failure,access=access,cancelBefore=cancel))
wrapper=r'''
import Foundation
import CoreFoundation
enum Failure:String {case cancelled,sessionChanged,timedOut}
struct Pair:Sendable {let accessToken:String?;let refreshFailure:Failure?}
enum AuthError:Error {case timedOut}
actor Stack {
 let pair:Pair;var calls:[String]=[]
 init(_ pair:Pair){self.pair=pair}
 func getAccessToken()->String? {calls.append("ordinary");return pair.accessToken}
 func fetchNewAccessToken()->String? {calls.append("forced");return pair.accessToken}
 func resolvedTokenPair(forceRefresh:Bool)->Pair {calls.append(forceRefresh ? "resolved-forced":"resolved-proactive");return pair}
}
struct Client {let stack:Stack
DECLS
}
actor Gate {var open=false;var waiter:CheckedContinuation<Void,Never>?
 func wait()async {if !open{await withCheckedContinuation{waiter=$0}}}
 func release(){open=true;waiter?.resume();waiter=nil}
}
let rows=try JSONSerialization.jsonObject(with:Data(INPUT.utf8)) as! [[String:Any]]
var cases:[[String:Any]]=[]
for row in rows {
 let stack=Stack(Pair(accessToken:row["access"] as? String,refreshFailure:(row["failure"] as? String).flatMap(Failure.init))),gate=Gate()
 let client=Client(stack:stack)
 let task=Task{() -> [String:Any] in await gate.wait()
  do {
   let result:String?
   switch row["mode"] as! String {
   case "ordinary":result=await client.accessToken()
   case "forced":result=await client.forceRefreshAccessToken()
   default:result=try await client.resolvedAccessToken(forceRefresh:row["force"] as! Bool)
   }
   return ["access":result as Any? ?? NSNull(),"error":NSNull()]
  } catch is CancellationError {return ["access":NSNull(),"error":"cancelled"]}
  catch {return ["access":NSNull(),"error":"timedOut"]}
 }
 if row["cancelBefore"] as! Bool{task.cancel()};await gate.release()
 cases.append(["input":row,"outcome":await task.value,"calls":await stack.calls])
}
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject:["cases":cases,"foundation":["os":ProcessInfo.processInfo.operatingSystemVersionString,"coreFoundationVersion":kCFCoreFoundationVersionNumber]],options:[.sortedKeys]))
'''.replace('DECLS','\n'.join(decls)).replace('INPUT',json.dumps(json.dumps(rows)))
with tempfile.TemporaryDirectory(prefix='cmux-token-adapter-') as folder:
 root=pathlib.Path(folder);(root/'main.swift').write_text(wrapper)
 subprocess.run(['swiftc',str(root/'main.swift'),'-o',str(root/'reference')],check=True,timeout=60)
 result=json.loads(subprocess.check_output([str(root/'reference')],timeout=30))
out=dict(upstream=PIN,wholeOriginalFiles={SOURCE:hashlib.sha256(src.encode()).hexdigest()},executedDeclarationsSha256=[hashlib.sha256(d.encode()).hexdigest() for d in decls],seams='Three complete unchanged StackAuthClient accessToken, forceRefreshAccessToken and resolvedAccessToken declarations execute. Stack optional access/pair results are inert; real child task cancellation executes. The corpus proves wrapper routing and nil/cancellation/timeout projections, not underlying refresh, transport, original coordinator/public-state clearing, storage or iOS behavior.',**result)
a.out.parent.mkdir(parents=True,exist_ok=True);a.out.write_text(json.dumps(out,indent=2)+'\n');print('Executed original token adapter cases:',len(rows))
