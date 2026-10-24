#!/usr/bin/env python3
"""Run pinned SDK declarations against raw JSON bytes using the installed Foundation."""
import argparse,base64,decimal,hashlib,json,pathlib,random,subprocess,tempfile
PIN='a5911a1f92f3eeb11c4d7f3e2ef89204718c5009'
S='vendor/stack-auth-swift-sdk-prerelease/Sources/StackAuth/Models/'
P='Packages/Shared/CMUXAuthCore/Sources/CMUXAuthCore/'
FILES=dict(user=S+'User.swift',current=S+'CurrentUser.swift',team=S+'Team.swift',shared=P+'Models/CMUXAuthUser.swift',
 store=P+'Persistence/CMUXAuthIdentityStore.swift',backing=P+'Persistence/CMUXAuthKeyValueStore.swift')
p=argparse.ArgumentParser();p.add_argument('--upstream',type=pathlib.Path,required=True)
p.add_argument('--out',type=pathlib.Path,default=pathlib.Path('app/src/test/resources/auth/native-sdk-json-reference.json'));a=p.parse_args()
src={k:subprocess.check_output(['git','-C',str(a.upstream),'show',PIN+':'+v]).decode() for k,v in FILES.items()}
def declaration(key,signature):
 text=src[key];start=text.index(signature);end=text.index('{',start)+1;depth=1
 while depth:
  if text[end]=='{':depth+=1
  elif text[end]=='}':depth-=1
  end+=1
 return text[start:end]
decl=[declaration('current','init(client: APIClient, json: [String: Any])'),declaration('current','public func listTeams()'),declaration('team','init(client: APIClient, json: [String: Any])')]
rows=[]
def add(name,value):
 wire=value.encode('utf-8') if isinstance(value,str) else value
 rows.append(dict(case=name,base64=base64.b64encode(wire).decode()))
base='{"id":"wire-user","items":[{"id":"one","display_name":"One"}]}'
cases={'valid':base,'object-trailing-comma':base[:-1]+',}',
 'array-trailing-comma':base.replace('"One"}]','"One"},]'),
 'duplicate-id':base[:-1]+',"id":"replacement"}',
 'duplicate-items':base[:-1]+',"items":[{"id":"two"}]}',
 'escaped-duplicate-id':base[:-1]+',"\\u0069d":"replacement"}',
 'duplicate-anonymous-true-first':base[:-1]+',"is_anonymous":true,"is_anonymous":false}',
 'duplicate-anonymous-false-first':base[:-1]+',"is_anonymous":false,"is_anonymous":true}',
 'bare-key':base.replace('"id":','id:',1),'single-quote':base.replace('"',"'"),
 'block-comment':base.replace('{','{/*comment*/',1),'line-comment':base.replace('{','{//comment\n',1),
 'hash-comment':'#comment\n'+base,'semicolon':base.replace(',',';',1),'equals':base.replace(':','=',1),
 'trailing-text':base+' private-body-canary','trailing-object':base+'{}','trailing-null':base+'null',
 'trailing-nul':base+'\0','leading-nul':'\0'+base,'leading-whitespace':' \t\r\n'+base,
 'trailing-whitespace':base+' \t\r\n','leading-formfeed':'\f'+base,'leading-nbsp':'\u00a0'+base,
 'mid-bom':' '+chr(0xfeff)+base,'root-array':'[]','root-null':'null','root-string':'"text"','root-number':'1',
 'items-mixed':'{"id":"wire-user","items":[{},null]}','items-double-comma':'{"items":[{},,]}',
 'object-double-comma':'{"id":"wire-user",,}','null-key':'{null:1}',
 'duplicate-invalid-surrogate':'{"id":"first","id":"\\uD800"}',
 'duplicate-invalid-number':'{"id":"first","id":1e400}'}
for name,raw in cases.items():add(name,raw)
for code in range(32):add('raw-control-'+str(code),' {"id":"a'+chr(code)+'b","items":[{}]}')
for name,value in [('escaped-nul',r'a\u0000b'),('escaped-controls',r'\b\f\n\r\t\/\\\"'),
 ('emoji-pair',r'\uD83D\uDE00'),('high-alone',r'\uD800'),('low-alone',r'\uDC00'),
 ('high-nonlow',r'\uD800\u0041'),('reversed-pair',r'\uDC00\uD800'),('bad-unicode',r'\uZZZZ'),
 ('short-unicode',r'\u123'),('unknown-escape',r'\x41'),('unicode-noncharacter',r'\uFFFF'),
 ('raw-unicode','é e\u0301 😀\u2028\ufffd')]:add(name,'{"id":"'+value+'","items":[{}]}')
for token in ['0','-0','1','1.0','1e0','1E+0','0.99999999999999999','1.00000000000000001','1.0000000000000002',
 '-1','2','01','-01','+1','.5','1.','1e','NaN','Infinity','1e308','1e309','1e400','1e-400','0e9999999999',
 '1e9999999999','0e-9999999999','18446744073709551615','18446744073709551616','9'*309]:
 add('number-'+token[:40],'{"id":"wire-user","is_anonymous":'+token+',"items":[{}]}')
numeric_edges=['0e'+exp for exp in ['400','1000','9999','10000','0000','00001','+999','+1000','-999','-1000','-0000']]
numeric_edges += ['1'+('0'*n) for n in [126,127,128,164,165,166]]
numeric_edges += ['4'+('0'*165),'0.'+('0'*127)+'1','0.'+('0'*127)+'12','0.'+('0'*127)+'10','0.'+('0'*128)+'0',
 '1.00000000000000001e-128','1.00000000000000001e308','123456789012345678901e-308',
 '0.1234567890123456789012345678901234567899999999999','1.0000000000000001110223024625156540423699999999999']
for n,token in enumerate(numeric_edges):add('number-edge-'+str(n),'{"id":"wire-user","is_anonymous":'+token+',"items":[{}]}')
rng=random.Random(1731)
for n in range(128):
 digits=''.join(str(rng.randrange(10)) for _ in range(rng.choice([1,15,16,17,18,37,38,39,127,128,166])))
 if n%3==0:token='0.'+digits
 elif n%3==1:token='1'+digits
 else:token='1.'+digits
 if n%2:token='-'+token
 if n%4:token+=rng.choice(['e','E'])+rng.choice(['','+','-'])+rng.choice(['0','0000','127','128','308','999','1000','00001'])
 add('number-corpus-'+str(n),'{"id":"wire-user","is_anonymous":'+token+',"items":[{}]}')
for n,token in enumerate(['-1e309','-1e400','-11e308','-11111111111111111e400','-111111111111111111e400','1798543607185678E1000']):
 add('number-overflow-'+str(n),'{"id":"wire-user","is_anonymous":'+token+',"items":[{}]}')
with decimal.localcontext() as context:
 context.prec=100
 for offset in range(-32,32):
  token=format(decimal.Decimal(1)+decimal.Decimal(offset)*decimal.Decimal('1e-18'),'.70f')
  add('number-near-one-'+str(offset),'{"id":"wire-user","is_anonymous":'+token+',"items":[{}]}')
for count in [510,511,512,513,514]:
 add('depth-'+str(count),'{"id":"wire-user","padding":'+'['*count+'0'+']'*count+',"items":[{}]}')
for encoding,bom in [('utf-8',b'\xef\xbb\xbf'),('utf-16-le',b'\xff\xfe'),('utf-16-be',b'\xfe\xff'),
 ('utf-32-le',b'\xff\xfe\0\0'),('utf-32-be',b'\0\0\xfe\xff')]:
 add(encoding,base.encode(encoding));add(encoding+'-bom',bom+base.encode(encoding))
add('double-utf8-bom',b'\xef\xbb\xbf\xef\xbb\xbf'+base.encode())
for name,value in [('invalid-utf8',b'\xff'),('overlong-utf8',b'\xc0\xaf'),('truncated-utf8',b'\xe2\x82'),('utf8-surrogate',b'\xed\xa0\x80')]:
 add(name,b'{"id":"a'+value+b'b","items":[{}]}')
add('odd-utf16',b'\xff\xfe'+base.encode('utf-16-le')+b'x')
for encoding in ['utf-16-le','utf-16-be','utf-32-le','utf-32-be']:
 for count in [1,2,3]:add(encoding+'-extra-'+str(count),base.encode(encoding)+b'\xff'*count)
add('utf16-high-alone',b'\xff\xfe'+('{"id":"'+chr(0xd800)+'"}').encode('utf-16-le',errors='surrogatepass'))
add('utf32-outside-unicode',b'\xff\xfe\0\0'+b'{\0\0\0"\0\0\0i\0\0\0d\0\0\0"\0\0\0:\0\0\0"\0\0\0'+b'\0\0\x11\0'+b'"\0\0\0}\0\0\0')
cache=[dict(id='cached-user',displayName=' Cache ',selected=v) for v in [None,1,False,{},[]," team "]]
cache += [dict(id='cached-user',displayName=False,selected={}),dict(id='cached-user',demonstrationContentEnabled=1)]
wrapper=r'''
actor APIClient {
 let wire:Data;init(_ wire:Data){self.wire=wire}
 func sendRequest(path:String,method:String,authenticated:Bool)->(Data,Int){(wire,200)}
}
actor Team {
 private let client:APIClient;public nonisolated let id:String
 var displayName:String;var profileImageUrl:String?;var clientMetadata:[String:Any];var clientReadOnlyMetadata:[String:Any]
'''+decl[2]+r'''
}
actor CurrentUser {
 private let client:APIClient;private var userData:User;let selectedTeam:Team?
'''+decl[0]+'\n'+decl[1]+r'''
 func projection()->[String:Any] {
  ["id":userData.id,"name":userData.displayName as Any? ?? NSNull(),"email":userData.primaryEmail as Any? ?? NSNull(),
   "image":userData.profileImageUrl as Any? ?? NSNull(),"eligible": !userData.isAnonymous && !userData.isRestricted,
   "selected":selectedTeam?.id as Any? ?? NSNull(),
   "demo":CMUXAuthUser.demonstrationContentEnabled(fromClientReadOnlyMetadata:userData.clientReadOnlyMetadata)]
 }
}
final class Backing:CMUXAuthKeyValueStore {
 var values:[String:Any]=[:]
 func bool(forKey key:String)->Bool{values[key] as? Bool ?? false};func data(forKey key:String)->Data?{values[key] as? Data}
 func string(forKey key:String)->String?{values[key] as? String};func set(_ value:Any?,forKey key:String){values[key]=value}
 func removeObject(forKey key:String){values[key]=nil}
}
'''
runner=r'''
let input=try JSONSerialization.jsonObject(with:Data(INPUT.utf8)) as! [String:Any]
var output:[[String:Any]]=[]
for row in input["wire"] as! [[String:Any]] {
 let data=Data(base64Encoded:row["base64"] as! String)!;let client=APIClient(data)
 let parsed=try? JSONSerialization.jsonObject(with:data) as? [String:Any]
 let user:Any;if let parsed{user=await CurrentUser(client:client,json:parsed).projection()}else{user=NSNull()}
 var teams:[[String:String]]=[]
 for team in try await CurrentUser(client:client,json:[:]).listTeams(){teams.append(["id":team.id,"name":await team.displayName])}
 output.append(["case":row["case"]!,"base64":row["base64"]!,"user":user,"teams":teams])
}
var cached:[[String:Any]]=[]
for payload in input["cache"] as! [[String:Any]] {
 let b=Backing();b.set(try JSONSerialization.data(withJSONObject:payload),forKey:"user")
 let user=try? CMUXAuthIdentityStore(keyValueStore:b,key:"user").load()
 cached.append(["payload":payload,"id":user?.id as Any? ?? NSNull(),"name":user?.displayName as Any? ?? NSNull()])
}
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject:["wire":output,"cache":cached,
 "foundation":["os":ProcessInfo.processInfo.operatingSystemVersionString,"coreFoundationVersion":kCFCoreFoundationVersionNumber]],options:[.sortedKeys]))
'''.replace('INPUT',json.dumps(json.dumps(dict(wire=rows,cache=cache))))
with tempfile.TemporaryDirectory(prefix='cmux-sdk-json-') as directory:
 root=pathlib.Path(directory);swift=root/'main.swift'
 swift.write_text('import CoreFoundation\n'+'\n'.join(src[k] for k in ['user','shared','backing','store'])+'\n'+wrapper+runner)
 subprocess.run(['swiftc',str(swift),'-o',str(root/'reference')],check=True,timeout=60)
 result=json.loads(subprocess.check_output([str(root/'reference')],timeout=30))
out=dict(upstream=PIN,wholeOriginalFiles={FILES[k]:hashlib.sha256(v.encode()).hexdigest() for k,v in src.items()},
 executedDeclarationsSha256=[hashlib.sha256(v.encode()).hexdigest() for v in decl],
 seams='Inert APIClient supplies exact bytes. Complete original User/CMUXAuthUser/store/backing sources, CurrentUser/Team initializers and listTeams execute. The user parse guard calls the same Foundation JSONSerialization API, not complete getUser or transport/timer/error code.',**result)
a.out.parent.mkdir(parents=True,exist_ok=True);a.out.write_text(json.dumps(out,indent=2)+'\n')
print('Executed raw wire cases:',len(result['wire']),'shared-cache cases:',len(result['cache']))
