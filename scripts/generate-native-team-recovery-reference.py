#!/usr/bin/env python3
"""Execute complete original recovery policy and Retry-After declarations."""
import argparse,hashlib,json,pathlib,subprocess,tempfile
PIN='a5911a1f92f3eeb11c4d7f3e2ef89204718c5009'
POLICY='Packages/Shared/CmuxAuthRuntime/Sources/CmuxAuthRuntime/Coordinator/AuthCoordinator+TeamScopeRecovery.swift'
PARSER='vendor/stack-auth-swift-sdk-prerelease/Sources/StackAuth/APIClient.swift'
p=argparse.ArgumentParser();p.add_argument('--upstream',type=pathlib.Path,required=True);p.add_argument('--out',type=pathlib.Path,default=pathlib.Path('app/src/test/resources/native-team-recovery-reference.json'));a=p.parse_args()
def read(path):return subprocess.check_output(['git','-C',str(a.upstream),'show',PIN+':'+path])
def declaration(source,signature):
 text=source.decode();start=text.index(signature);opening=text.index('{',start);depth=1;end=opening+1
 while depth:
  if text[end]=='{':depth+=1
  elif text[end]=='}':depth-=1
  end+=1
 return text[start:end]
policy=read(POLICY);parser=read(PARSER)
functions=[declaration(policy,'static func teamScopeRecoveryDelay('),declaration(policy,'static func teamScopeRateLimitDelay(')]
parse=declaration(parser,'func retryAfterSeconds(')
runner=r'''
func seconds(_ duration: Duration) -> Double { let value=duration.components;return Double(value.seconds)+Double(value.attoseconds)/1e18 }
var normal: [[String: Any]]=[]
for attempt in [-2147483648,-1,0,1,2,3,4,5,6,2147483647] {
 normal.append(["attempt":attempt,"milliseconds":seconds(OriginalPolicy.teamScopeRecoveryDelay(afterAttempt:attempt))*1000])
}
var rate: [[String: Any]]=[]
for streak in [-1,0,1,2,3,5,6,20,2147483647] {for jitter in [0.5,0.75,1.0] {
 rate.append(["retryAfterSeconds":NSNull(),"streak":streak,"jitter":jitter,
              "seconds":seconds(OriginalPolicy.teamScopeRateLimitDelay(retryAfter:nil,streak:streak,jitter:jitter))])
}}
for header in [0.0,0.5,1.0,1.234,600.0,900.0,86400.0] {for jitter in [0.5,0.75,1.0] {
 let duration=Duration.milliseconds(Int64(header*1000))
 rate.append(["retryAfterSeconds":seconds(duration),"streak":8,"jitter":jitter,
              "seconds":seconds(OriginalPolicy.teamScopeRateLimitDelay(retryAfter:duration,streak:8,jitter:jitter))])
}}
let now=Date(timeIntervalSince1970:1700000000)
var parsed: [[String: Any]]=[]
let headers:[String?]=[nil,""," 1 ","0","1.2345","999999","-1","NaN","Infinity","nonsense",
 "Tue, 14 Nov 2023 22:13:30 GMT","Tue, 14 Nov 2023 22:13:00 GMT","Thu, 01 Jan 2037 00:00:00 GMT","2026-01-01"]
for header in headers {parsed.append(["header":header as Any? ?? NSNull(),"seconds":retryAfterSeconds(header,now:now) as Any? ?? NSNull()])}
let data=try JSONSerialization.data(withJSONObject:["normal":normal,"rate":rate,"headers":parsed],options:[.prettyPrinted,.sortedKeys])
FileHandle.standardOutput.write(data)
'''
with tempfile.TemporaryDirectory(prefix='cmux-team-recovery-reference-') as directory:
 root=pathlib.Path(directory);swift=root/'main.swift';swift.write_text('import Foundation\nstruct OriginalPolicy {\n'+'\n'.join(functions)+'\n}\n'+parse+'\n'+runner)
 subprocess.run(['swiftc',str(swift),'-o',str(root/'reference')],check=True);rows=json.loads(subprocess.check_output([str(root/'reference')]))
result=dict(upstream=PIN,wholeOriginalFiles={POLICY:hashlib.sha256(policy).hexdigest(),PARSER:hashlib.sha256(parser).hexdigest()},
 executedDeclarationSha256=[hashlib.sha256(s.encode()).hexdigest() for s in functions+[parse]],nowMillis=1700000000000,**rows)
a.out.parent.mkdir(parents=True,exist_ok=True);a.out.write_text(json.dumps(result,indent=2)+'\n')
print('Executed complete original declarations:',len(rows['normal']),'normal,',len(rows['rate']),'rate,',len(rows['headers']),'header cases')
