#!/usr/bin/env python3
"""Execute whole pinned SDK JWT declarations; no translated reference predicate."""
import argparse, hashlib, json, pathlib, subprocess, tempfile
PIN = 'a5911a1f92f3eeb11c4d7f3e2ef89204718c5009'
SOURCE = 'vendor/stack-auth-swift-sdk-prerelease/Sources/StackAuth/APIClient.swift'
p = argparse.ArgumentParser()
p.add_argument('--upstream', type=pathlib.Path, required=True)
p.add_argument('--out', type=pathlib.Path, default=pathlib.Path('app/src/test/resources/native-token-validity-reference.json'))
a = p.parse_args()
source = subprocess.check_output(['git','-C',str(a.upstream),'show',f'{PIN}:{SOURCE}'])
s = source.decode(); block = s[s.index('struct JWTPayload {'):s.index('/// Parse an HTTP `Retry-After`')]
runner = r'''
let now = Date().timeIntervalSince1970
func token(_ payload: Any) -> String {
    let data = try! JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys, .fragmentsAllowed])
    return "fixture." + data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "") + ".fixture"
}
let cases: [(String, String?)] = [
    ("absent",nil),("malformed","bad"),("non-object",token([1,2])),
    ("no-expiry",token([String:String]())),("string-expiry",token(["exp":"100"])),
    ("expired",token(["exp":now-3600])),("ten-seconds",token(["exp":now+10])),
    ("forty-seconds",token(["exp":now+40])),("ninety-seconds",token(["exp":now+90])),
    ("long-lived",token(["exp":now+3600,"iat":now-7200])),
    ("short-lived",token(["exp":now+40,"iat":now-20])),
    ("minimum-lead",token(["exp":now+10,"iat":now])),
    ("boolean-expiry",token(["exp":true])),("null-expiry",token(["exp":NSNull()])),
    ("non-numeric-issue",token(["exp":now+90,"iat":"old"])),
    ("future-issue",token(["exp":now+90,"iat":now+100]))
]
let rows: [[String:Any]] = cases.map { id, value in
    ["id":id,"token":value as Any? ?? NSNull(),"nowMillis":now*1000,
     "expired":isTokenExpired(value),"fresh60":isTokenFreshEnough(value,refreshLeadMillis:60_000),
     "fresh120":isTokenFreshEnough(value,refreshLeadMillis:120_000)]
}
print(String(data:try! JSONSerialization.data(withJSONObject:rows,options:[.sortedKeys]),encoding:.utf8)!)
'''
with tempfile.TemporaryDirectory(prefix='cmux-token-reference-') as tmp:
    path = pathlib.Path(tmp)/'main.swift'; path.write_text('import Foundation\n'+block+runner)
    rows = json.loads(subprocess.check_output(['swift',str(path)],text=True))
result = dict(upstream=PIN,source=SOURCE,sourceSha256=hashlib.sha256(source).hexdigest(),
              declarationsSha256=hashlib.sha256(block.encode()).hexdigest(),
              scope='Original SDK expiry/freshness declarations executed with generated fixture tokens. No backend, refresh coordinator, Android runtime or full parity acceptance.',rows=rows)
a.out.parent.mkdir(parents=True,exist_ok=True);a.out.write_text(json.dumps(result,indent=2)+'\n')
print(f'{len(rows)} original SDK rows written to {a.out}')
