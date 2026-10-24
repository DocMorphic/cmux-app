#!/usr/bin/env python3
"""Execute complete pinned SDK token-resolution control flow with explicit predicate/store/coordinator seams."""
import argparse,hashlib,json,pathlib,subprocess,tempfile
PIN='a5911a1f92f3eeb11c4d7f3e2ef89204718c5009'
SOURCE='vendor/stack-auth-swift-sdk-prerelease/Sources/StackAuth/APIClient.swift'
p=argparse.ArgumentParser();p.add_argument('--upstream',type=pathlib.Path,required=True)
p.add_argument('--out',type=pathlib.Path,default=pathlib.Path('app/src/test/resources/auth/native-token-resolution-reference.json'));a=p.parse_args()
src=subprocess.check_output(['git','-C',str(a.upstream),'show',PIN+':'+SOURCE]).decode()
def decl(signature):
 start=src.index(signature);end=src.index('{',start)+1;depth=1
 while depth:
  if src[end]=='{':depth+=1
  elif src[end]=='}':depth-=1
  end+=1
 return src[start:end]
decls=[decl('private func getOrFetchLikelyValidTokensFromStore('),decl('private func fetchNewAccessToken(\n'),decl('private func resolveRefresh(')]
rows=[]
results={'success':dict(refresh='R',access='new'),'transient':dict(refresh='R'),
 'timeout':dict(failure='timedOut'),'changed':dict(failure='sessionChanged'),'cancelled':dict(failure='cancelled'),'rejected':{}}
for force in [False,True]:
 for token in [None,'fresh','stale','expired']:
  for outcome,result in results.items():
   for rejected in ([None,'stale','different'] if force else [None]):
    reads=['R']*(2 if force else 3)
    if outcome=='rejected':reads[-1]=None
    rows.append(dict(case=f'{force}-{token}-{outcome}-{rejected}',force=force,rejected=rejected,refreshReads=reads,accessReads=[token],result=result))
for force in [False,True]:
 for token in [None,'fresh','stale','expired']:
  rows.append(dict(case=f'token-only-{force}-{token}',force=force,refreshReads=[None],accessReads=[token],result={}))
rows += [dict(case='token-only-access-replaced',force=False,refreshReads=[None],accessReads=['fresh','new'],result={}),
 dict(case='token-only-refresh-replaced',force=False,refreshReads=[None,'replacement'],accessReads=['fresh'],result={}),
 dict(case='refresh-replaced-before-freshness',force=False,refreshReads=['R','replacement'],accessReads=['fresh'],result={}),
 dict(case='refresh-replaced-after-exchange',force=False,refreshReads=['R','R','replacement'],accessReads=['stale'],result=results['success']),
 dict(case='cancel-before-proactive',force=False,refreshReads=['R'],accessReads=['stale'],result=results['success'],cancelBefore=True),
 dict(case='cancel-during-exchange',force=False,refreshReads=['R'],accessReads=['stale'],result=results['success'],cancelDuring=True)]
wrapper=r'''
import Foundation
import CoreFoundation
enum TokenRefreshFailure:String {case cancelled,sessionChanged,timedOut}
struct TokenPair {let refreshToken:String?;let accessToken:String?;var refreshFailure:TokenRefreshFailure?=nil}
protocol TokenStoreProtocol {func getStoredRefreshToken() async->String?;func getStoredAccessToken() async->String?}
actor Store:TokenStoreProtocol {
 let row:[String:Any];var ri=0,ai=0;var reads:[String]=[]
 init(_ row:[String:Any]){self.row=row}
 func getStoredRefreshToken()->String? {reads.append("refresh");let list=row["refreshReads"] as! [Any];defer{ri+=1};return list[min(ri,list.count-1)] as? String}
 func getStoredAccessToken()->String? {reads.append("access");let list=row["accessReads"] as! [Any];defer{ai+=1};return list[min(ai,list.count-1)] as? String}
}
func isTokenExpired(_ token:String?)->Bool{token==nil || token=="expired"}
func isTokenFreshEnough(_ token:String?,refreshLeadMillis:Int)->Bool{token=="fresh" || token=="new"}
actor Coordinator {
 let row:[String:Any];var inputs:[[String:Any]]=[]
 init(_ row:[String:Any]){self.row=row}
 func resolve(store:any TokenStoreProtocol,refreshToken:String,accessToken:String?,clock:Int,timeoutNanoseconds:UInt64,operation:() async->Bool) async->TokenPair {
  inputs.append(["refresh":refreshToken,"access":accessToken as Any? ?? NSNull()])
  if row["cancelDuring"] as? Bool == true {withUnsafeCurrentTask{$0?.cancel()}}
  let result=row["result"] as! [String:Any]
  return TokenPair(refreshToken:result["refresh"] as? String,accessToken:result["access"] as? String,
   refreshFailure:(result["failure"] as? String).flatMap(TokenRefreshFailure.init))
 }
}
actor APIClient {
 let refreshCoordinator:Coordinator;let refreshClock=0,refreshLeadMillis=60_000;let refreshTimeoutNanoseconds:UInt64=30_000_000_000
 init(_ coordinator:Coordinator){refreshCoordinator=coordinator}
 func refreshRespectingRateLimit(refreshToken:String) async->Bool{fatalError("Coordinator seam does not invoke HTTP")}
DECLS
 func run(_ row:[String:Any],_ store:Store) async->TokenPair {
  if row["force"] as! Bool {return await fetchNewAccessToken(tokenStore:store,ifAccessTokenUnchangedFrom:row["rejected"] as? String)}
  return await getOrFetchLikelyValidTokensFromStore(store)
 }
}
actor Gate {var open=false;var waiter:CheckedContinuation<Void,Never>?
 func wait() async {if !open {await withCheckedContinuation{waiter=$0}}}
 func release(){open=true;waiter?.resume();waiter=nil}
}
let rows=try JSONSerialization.jsonObject(with:Data(INPUT.utf8)) as! [[String:Any]]
var output:[[String:Any]]=[]
for row in rows {
 let store=Store(row),coordinator=Coordinator(row);let api=APIClient(coordinator);let gate=Gate()
 let task=Task{await gate.wait();return await api.run(row,store)}
 if row["cancelBefore"] as? Bool == true {task.cancel()};await gate.release()
 let result=await task.value
 output.append(["input":row,"pair":["refresh":result.refreshToken as Any? ?? NSNull(),"access":result.accessToken as Any? ?? NSNull(),
  "failure":result.refreshFailure?.rawValue as Any? ?? NSNull()],"reads":await store.reads,"exchangeInputs":await coordinator.inputs])
}
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject:["cases":output,
 "foundation":["os":ProcessInfo.processInfo.operatingSystemVersionString,"coreFoundationVersion":kCFCoreFoundationVersionNumber]],options:[.sortedKeys]))
'''.replace('DECLS','\n'.join(decls)).replace('INPUT',json.dumps(json.dumps(rows)))
with tempfile.TemporaryDirectory(prefix='cmux-token-resolution-') as folder:
 root=pathlib.Path(folder);(root/'main.swift').write_text(wrapper)
 subprocess.run(['swiftc',str(root/'main.swift'),'-o',str(root/'reference')],check=True,timeout=60)
 result=json.loads(subprocess.check_output([str(root/'reference')],timeout=30))
out=dict(upstream=PIN,wholeOriginalFiles={SOURCE:hashlib.sha256(src.encode()).hexdigest()},
 executedDeclarationsSha256=[hashlib.sha256(d.encode()).hexdigest() for d in decls],
 seams='Complete unchanged getOrFetchLikelyValidTokensFromStore, fetchNewAccessToken(store/rejected) and resolveRefresh execute. Store getter sequences, expiry/freshness predicates and coordinator result are inert seams; child-task cancellation is real. This proves selection/fallback/control flow, not JWT parsing, actual shared refresh/coalescing/cooldown/commit, timers, HTTP or iOS-runtime behavior. Predicate execution has a separate original SDK reference.',**result)
a.out.parent.mkdir(parents=True,exist_ok=True);a.out.write_text(json.dumps(out,indent=2)+'\n')
print('Executed original token-resolution cases:',len(rows))
