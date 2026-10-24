#!/usr/bin/env python3
"""Execute the complete pinned AuthTimeouts model and inspect Foundation's request default."""
import argparse,hashlib,json,pathlib,subprocess,tempfile
PIN='a5911a1f92f3eeb11c4d7f3e2ef89204718c5009'
SOURCE='Packages/Shared/CmuxAuthRuntime/Sources/CmuxAuthRuntime/Coordinator/AuthTimeouts.swift'
p=argparse.ArgumentParser();p.add_argument('--upstream',type=pathlib.Path,required=True);p.add_argument('--out',type=pathlib.Path,default=pathlib.Path('app/src/test/resources/native-team-phase-reference.json'));a=p.parse_args()
source=subprocess.check_output(['git','-C',str(a.upstream),'show',PIN+':'+SOURCE])
runner=r'''
func millis(_ value:Duration)->Double {let c=value.components;return Double(c.seconds)*1000+Double(c.attoseconds)/1e15}
func row(_ value:AuthTimeouts)->[String:Double] {["interactiveMillis":millis(value.interactiveFlow),"networkMillis":millis(value.network),"restoreMillis":millis(value.sessionRestore)]}
let data=try JSONSerialization.data(withJSONObject:["default":row(.default),
 "fallback":row(AuthTimeouts(interactiveFlow:.seconds(42),network:.milliseconds(1234))),
 "explicit":row(AuthTimeouts(interactiveFlow:.seconds(42),network:.seconds(17),sessionRestore:.seconds(5))),
 "foundationRequestDefaultMillis":URLRequest(url:URL(string:"https://example.invalid")!).timeoutInterval*1000],options:[.prettyPrinted,.sortedKeys])
FileHandle.standardOutput.write(data)
'''
with tempfile.TemporaryDirectory(prefix='cmux-team-phase-reference-') as directory:
 root=pathlib.Path(directory);swift=root/'main.swift';swift.write_bytes(source+b'\n'+runner.encode())
 subprocess.run(['swiftc',str(swift),'-o',str(root/'reference')],check=True);rows=json.loads(subprocess.check_output([str(root/'reference')]))
result=dict(upstream=PIN,wholeOriginalFiles={SOURCE:hashlib.sha256(source).hexdigest()},**rows)
a.out.parent.mkdir(parents=True,exist_ok=True);a.out.write_text(json.dumps(result,indent=2)+'\n');print('Executed complete original AuthTimeouts; network default:',rows['default']['networkMillis'],'ms')
