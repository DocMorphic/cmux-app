#!/usr/bin/env python3
"""Generate search inputs; native original code, not this generator, decides matches."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

ROOT=Path(__file__).resolve().parents[1]

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home',type=Path,required=True)
    parser.add_argument('--icu4j',type=Path,required=True)
    args=parser.parse_args();folder=ROOT/'tests/chief-home-search';baseline=folder/'BaselineCases.json';source=folder/'GenerateCases.java'
    rows=json.loads(baseline.read_text());assert len(rows)==102
    with tempfile.TemporaryDirectory() as scratch:
        subprocess.run([str(args.java_home/'bin/javac'),'-cp',str(args.icu4j),'-d',scratch,str(source)],check=True)
        raw=subprocess.check_output([str(args.java_home/'bin/java'),'-cp',scratch+':'+str(args.icu4j),'GenerateCases'],text=True)
    for line in raw.splitlines():
        key,text,query=line.split('\t')
        rows.append(dict(id=key,title=base64.b64decode(text).decode(),preview='',query=base64.b64decode(query).decode()))
    extra=[]
    for root in ['1','١','१','①','¹','１','𝟙','क','क़','क़','ক','ક','க','漢','a','é','é','İ','ß','ﬃ','가','क्','a्','👩🏽‍💻','x\u200dy']:
        for mark in ['\u0301','\u0307','\u0345','\u034f','\u200d','\ufe0f']:
            extra.extend([(root+mark,root),('Q'+root+mark,'Q'+root),('Q'+root+mark,root),(root+mark,mark)])
    extra.extend([('1\x00A','1A'),('1é','1e'),('1\u00adA','1A'),('1\u200bA','1A'),('1','①'),('①','1'),('Q1é','Q1e'),('!1é','!1e'),('कXé','कXe')])
    for size in [1,2,15,63,64,65,128]:
        for marks in ['\u0301','\u0301\u0327','\u0345']:
            text='a'+marks*size;extra.extend([(text,'a'),(text,text),('é'+marks*size,'é'+marks*size),(text,marks)])
    for index,(text,query) in enumerate(extra):rows.append(dict(id='branch-boundary-%04d'%index,title=text,preview='',query=query))
    assert len({row['id'] for row in rows})==len(rows)
    target=folder/'Cases.json'
    target.write_text('[\n'+',\n'.join(json.dumps(row,ensure_ascii=False,separators=(',',':')) for row in rows)+'\n]\n')
    provenance=dict(cases=len(rows),baselineCases=102,baselineSha256=sha(baseline),generatorSha256=sha(Path(__file__)),
        mappingInputGeneratorSha256=sha(source),icu4jSha256=sha(args.icu4j),casesSha256=sha(target),
        scope='ICU mapping/normalization is used only to author title/query inputs, never expected results. Entire original native Home predicate/model decides truth; iOS acceptance requires a new exact-source capture. No Android matcher or platform parity claim.')
    (folder/'GeneratedInputs.json').write_text(json.dumps(provenance,indent=2)+'\n')
    print('Generated',len(rows),'source-query inputs; no expected match values.')

if __name__=='__main__':main()
