#!/usr/bin/env python3
"""Execute the whole pinned original auth-state model with a JSON adapter."""
import argparse, hashlib, json, subprocess, tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
REVISION='a5911a1f92f3eeb11c4d7f3e2ef89204718c5009'
PREFIX='Packages/Shared/CMUXAuthCore/Sources/CMUXAuthCore/Models/'
SOURCES={PREFIX+'CMUXAuthState.swift':'c76babdc04c6defd4b75f3f0efa3907d03edec999382229e7db076cabb2fe8f8',
    PREFIX+'CMUXAuthUser.swift':'efb7028c38c08ec70e2dc53449b022c33cb2e073eed663f9d84bf8f63828de08',
    PREFIX+'CMUXAuthAutoLoginCredentials.swift':'dd84e1e1973c3ad555a474e268d833349dbb88ec94445742265d3fc48cea1eb3'}
ADAPTER='''import Foundation
let cached=CMUXAuthUser(id:"cached",primaryEmail:"cached@example.test",displayName:"Cached Person")
let fixture=CMUXAuthUser(id:"fixture",primaryEmail:nil,displayName:nil)
let mock=CMUXAuthUser(id:"mock",primaryEmail:nil,displayName:nil)
var cases:[[String:Any]]=[]
for bits in 0..<64 {
    func flag(_ bit:Int)->Bool { bits & (1<<bit) != 0 }
    let state=CMUXAuthState.primed(clearAuthRequested:flag(0),mockDataEnabled:flag(1),fixtureUser:flag(2) ? fixture:nil,
        autoLoginCredentials:flag(3) ? CMUXAuthAutoLoginCredentials(email:"fixture@example.test",password:"fixture-only"):nil,
        cachedUser:flag(4) ? cached:nil,hasTokens:flag(5),mockUser:mock)
    cases.append(["clear":flag(0),"mock":flag(1),"fixture":flag(2),"autoLogin":flag(3),"cached":flag(4),"hasTokens":flag(5),
        "authenticated":state.isAuthenticated,"restoring":state.isRestoringSession,"user":state.currentUser.map{$0.id as Any} ?? NSNull()])
}
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject:cases,options:[.sortedKeys]))
'''
def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--upstream',type=Path,required=True)
    parser.add_argument('--output',type=Path,default=ROOT/'tests/fixtures/auth-priming-reference.json');parser.add_argument('--check',action='store_true');args=parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='cmux-auth-priming-') as folder:
        folder=Path(folder);files=[]
        for path,sha in SOURCES.items():
            raw=subprocess.check_output(['git','-C',str(args.upstream),'show',REVISION+':'+path])
            if hashlib.sha256(raw).hexdigest()!=sha:raise ValueError('Changed original '+path)
            file=folder/Path(path).name;file.write_bytes(raw);files.append(str(file))
        adapter=folder/'main.swift';adapter.write_text(ADAPTER)
        subprocess.run(['swiftc',*files,str(adapter),'-o',str(folder/'reference')],check=True)
        cases=json.loads(subprocess.check_output([str(folder/'reference')]))
    result=dict(schema=1,upstream=REVISION,sourceFiles=SOURCES,adapterSha256=hashlib.sha256(ADAPTER.encode()).hexdigest(),
        generatorSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),compiler=subprocess.check_output(['swiftc','--version'],text=True).strip(),
        cases=cases,scope='Unchanged original Swift state/user/auto-login declarations executed. Android consumes the four normal priming rows; debug launch cases are reference only. Not runtime validation, storage, UI, backend or full parity proof.')
    if args.check:
        old=json.loads(args.output.read_text())
        for key in ['schema','upstream','sourceFiles','adapterSha256','cases']:
            if result[key]!=old[key]:raise ValueError('Changed priming reference '+key)
    else:args.output.write_text(json.dumps(result,indent=2)+'\n')
    print(str(len(cases))+' original priming cases '+('verified' if args.check else 'captured'))
if __name__=='__main__':main()
