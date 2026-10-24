#!/usr/bin/env python3
"""Run the whole pinned SDK User declaration to derive eligibility flag fixtures."""
import argparse,hashlib,json,pathlib,subprocess,tempfile
PIN='a5911a1f92f3eeb11c4d7f3e2ef89204718c5009'
SOURCE='vendor/stack-auth-swift-sdk-prerelease/Sources/StackAuth/Models/User.swift'
p=argparse.ArgumentParser();p.add_argument('--upstream',type=pathlib.Path,required=True)
p.add_argument('--out',type=pathlib.Path,default=pathlib.Path('app/src/test/resources/native-team-user-eligibility-reference.json'));a=p.parse_args()
source=subprocess.check_output(['git','-C',str(a.upstream),'show',PIN+':'+SOURCE])
runner=r'''
let values: [(String, String?)] = [
    ("absent",nil),("true","true"),("false","false"),("one","1"),("zero","0"),
    ("two","2"),("negative","-1"),("decimal-one","1.0"),("decimal-half","0.5"),
    ("null","null"),("string-true","\"true\""),("string-one","\"1\""),("array","[]"),("object","{}")
]
var rows: [[String: Any]] = []
for key in ["is_anonymous","is_restricted"] {
    for (name, value) in values {
        var text = "{\"id\":\"fixture-user\",\"display_name\":\"Fixture User\""
        if let value { text += ",\"\(key)\":\(value)" }
        text += "}"
        let payload = try JSONSerialization.jsonObject(with: Data(text.utf8)) as! [String: Any]
        let user = User(from: payload)
        rows.append(["case":"\(key):\(name)","payload":payload,"anonymous":user.isAnonymous,
                     "restricted":user.isRestricted,"eligible":!user.isAnonymous && !user.isRestricted])
    }
}
let both: [String: Any] = ["id":"fixture-user","is_anonymous":true,"is_restricted":true]
let user = User(from: both)
rows.append(["case":"both","payload":both,"anonymous":user.isAnonymous,"restricted":user.isRestricted,"eligible":!user.isAnonymous && !user.isRestricted])
let data = try JSONSerialization.data(withJSONObject: rows,options:[.prettyPrinted,.sortedKeys])
FileHandle.standardOutput.write(data)
'''
with tempfile.TemporaryDirectory(prefix='cmux-team-user-reference-') as directory:
 root=pathlib.Path(directory);swift=root/'main.swift';swift.write_bytes(source+b'\n'+runner.encode());binary=root/'reference'
 subprocess.run(['swiftc',str(swift),'-o',str(binary)],check=True)
 rows=json.loads(subprocess.check_output([str(binary)]))
result=dict(upstream=PIN,source=SOURCE,sourceSha256=hashlib.sha256(source).hexdigest(),rows=rows)
a.out.parent.mkdir(parents=True,exist_ok=True);a.out.write_text(json.dumps(result,indent=2,ensure_ascii=False)+'\n')
print('Executed whole SDK User.swift:',len(rows),'rows;',a.out)
for row in rows:
 if row['eligible'] is False:print(row['case'],'ineligible')
