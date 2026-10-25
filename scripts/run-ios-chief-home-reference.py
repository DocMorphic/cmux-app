#!/usr/bin/env python3
"""Run the actual original Home/sheet through a controlled offline RPC transport."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import plistlib
import subprocess

ROOT=Path(__file__).resolve().parents[1]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--upstream',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();upstream=args.upstream.resolve();out=args.output.resolve()
    spec=importlib.util.spec_from_file_location('chief_home_prepare',ROOT/'scripts/prepare-ios-chief-home-reference.py')
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    provenance=module.prepare(upstream,out)
    assert provenance['projectSourceDirty'] is False,'Reference source must be committed and clean'
    def command(argv,timeout=120):return subprocess.check_output([str(x) for x in argv],text=True,timeout=timeout).strip()
    sid=None;passed=False;cleanup=[]
    try:
        runtimes=json.loads(command(['xcrun','simctl','list','runtimes','-j']))['runtimes']
        available=[r for r in runtimes if r.get('isAvailable') and r['name'].startswith('iOS 26')]
        assert available,'No pinned-family iOS26 Simulator'
        runtime=sorted(available,key=lambda r:tuple(map(int,r['version'].split('.'))))[-1]
        devices=json.loads(command(['xcrun','simctl','list','devicetypes','-j']))['devicetypes']
        device=next(d for d in devices if d['name']=='iPhone 17 Pro')
        sid=command(['xcrun','simctl','create','cmux-original-home-'+os.environ.get('GITHUB_RUN_ID','local'),device['identifier'],runtime['identifier']])
        (out/'simulator.json').write_text(json.dumps(dict(udid=sid,runtime=runtime,device=device),indent=2)+'\n')
        command(['xcrun','simctl','boot',sid]);command(['xcrun','simctl','bootstatus',sid,'-b'],300)
        argv=['xcodebuild','-project',out/'ChiefHomeReference.xcodeproj','-scheme','ChiefHomeReference',
            '-configuration','Debug','-destination','platform=iOS Simulator,id='+sid,'-derivedDataPath',out/'DerivedData',
            '-clonedSourcePackagesDirPath',out/'SourcePackages','-resultBundlePath',out/'Home.xcresult',
            '-parallel-testing-enabled','NO','-maximum-concurrent-test-simulator-destinations','1','-jobs','2',
            'CODE_SIGNING_ALLOWED=NO','ENABLE_TESTABILITY=YES','test']
        with (out/'test.log').open('w') as log:
            result=subprocess.run([str(x) for x in argv],cwd=upstream,stdout=log,stderr=subprocess.STDOUT,timeout=3600)
        if (out/'Home.xcresult').exists():
            for name,argv in [('test-summary.json',['xcrun','xcresulttool','get','test-results','summary','--path',out/'Home.xcresult']),
                              ('export.log',['xcrun','xcresulttool','export','attachments','--path',out/'Home.xcresult','--output-path',out/'attachments'])]:
                export=subprocess.run([str(x) for x in argv],capture_output=True,timeout=120)
                (out/name).write_bytes(export.stdout)
                (out/(name+'.stderr')).write_bytes(export.stderr)
                assert export.returncode==0,'Result export failed: '+name
        result.check_returncode()
        assert 'Executed 3 tests, with 0 failures' in (out/'test.log').read_text(),'Expected three real UI tests'
        app=out/'DerivedData/Build/Products/Debug-iphonesimulator/ChiefHomeReference.app'
        info=plistlib.loads((app/'Info.plist').read_bytes())
        sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
        product=dict(bundleID=info['CFBundleIdentifier'],executableSha256=sha(app/info['CFBundleExecutable']),
            infoSha256=sha(app/'Info.plist'),resourceSha256={str(p.relative_to(app)):sha(p) for p in app.rglob('*') if p.is_file() and p.name in ['Localizable.strings','Localizable.stringsdict']},
            originalTrackedInputsUnchanged=not command(['git','-C',upstream,'diff','--name-only']),xcode=command(['xcodebuild','-version']))
        assert product['originalTrackedInputsUnchanged']
        (out/'build-product.json').write_text(json.dumps(product,indent=2)+'\n')
        # The test runner installs its host in this owned Simulator. The last
        # persisted file records only authored reference method/parameter data.
        container=Path(command(['xcrun','simctl','get_app_container',sid,info['CFBundleIdentifier'],'data']))
        rpc=container/'Documents/reference-rpc.json'
        if rpc.exists():(out/'last-reference-rpc.json').write_bytes(rpc.read_bytes())
        passed=True
    finally:
        if sid:
            for action in ['shutdown','delete']:
                result=subprocess.run(['xcrun','simctl',action,sid],capture_output=True,text=True,timeout=60)
                cleanup.append(dict(action=action,exitCode=result.returncode,output=result.stdout+result.stderr))
        (out/'completion.json').write_text(json.dumps(dict(passed=passed,ownedSimulator=sid,cleanup=cleanup),indent=2)+'\n')
    assert passed and all(row['exitCode']==0 for row in cleanup)
    print('Original Home offline reference UI tests completed; independent artifact/visual verification remains required.')


if __name__=='__main__':main()
