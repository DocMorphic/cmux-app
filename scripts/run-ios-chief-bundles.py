#!/usr/bin/env python3
"""Build the original pinned app and read its actual Simulator bundle lookups.

The original project, app code and catalogs are not edited. A Foundation-only
sidecar reads resources in that app process; it does not sign in or operate UI.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import plistlib
import re
import shutil
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
LANGUAGES = {'en':'en_US','ja':'ja_JP','de':'de_DE','es':'es_ES','fr':'fr_FR',
             'ar':'ar_SA','ko':'ko_KR','zh-Hans':'zh_CN','zh-Hant':'zh_TW'}


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def prepare(upstream, out, project_commit):
    manifest = json.loads((ROOT/'third_party/cmux-chief/manifest.json').read_text())
    pins = dict(manifest['nativeBundleFiles'])
    pins.update(manifest['questionPresentationFiles'])
    for item in manifest['homeStrings']['catalogs'] + [manifest['homeStrings']['unreadHelper'], manifest['questionStrings']]:
        pins[item['path']] = item['sha256']
    originals = {}
    for path, expected in pins.items():
        raw = subprocess.check_output(['git','-C',str(upstream),'show',manifest['revision']+':'+path])
        assert hashlib.sha256(raw).hexdigest() == expected, path
        originals[path] = raw
    helper = manifest['homeStrings']['unreadHelper']['path']
    view = 'Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/ChiefHomeView.swift'
    catalog = json.loads(originals[manifest['questionStrings']['path']])['strings']
    main_keys = set(manifest['questionStrings']['keys'])
    for item in manifest['homeStrings']['catalogs']:
        main_keys.update(item['keys'])
    module_keys = {key for key in catalog if key.startswith('mobile.chief.error.')}
    pattern = re.compile(r'(?:L10n\.)?string\(\s*"(?P<key>[^"\\]+)"\s*,\s*defaultValue:\s*(?P<value>"(?:\\.|[^"\\])*")\s*\)', re.S)
    calls = {}
    for raw in [originals[view], originals[helper]]:
        for match in pattern.finditer(raw.decode()):
            if match['key'] in main_keys:
                assert '\\(' not in match['value'], 'Dynamic default needs explicit review'
                call=match.group(0)
                if not call.startswith('L10n.'):call='L10n.'+call
                value = (call, match['value'])
                assert match['key'] not in calls or calls[match['key']][1] == value[1]
                calls[match['key']] = value
    assert set(calls) == main_keys, sorted(main_keys-set(calls))
    out.mkdir(parents=True, exist_ok=False)
    (out/'OriginalL10n.swift').write_bytes(originals[helper])
    literal = lambda value: json.dumps(value, ensure_ascii=False)
    generated = 'import Foundation\nfunc sourceMainBundleValues() -> [String:String] {\nreturn [\n'
    generated += ',\n'.join(literal(key)+': '+calls[key][0] for key in sorted(calls))+'\n]\n}\n'
    generated += 'func sourceBundleDefaults() -> [String:String] {\nreturn [\n'
    generated += ',\n'.join(literal(key)+': '+calls[key][1] for key in sorted(calls))+'\n]\n}\n'
    generated += 'func sourceBundleKeys() -> [String] { return ['+', '.join(map(literal, sorted(main_keys | module_keys)))+'] }\n'
    (out/'CaptureCalls.swift').write_text(generated)
    shutil.copy2(ROOT/'scripts/native-chief-bundle-probe.swift',out/'Probe.swift')
    (out/'ProbeLoad.m').write_text('#import <Foundation/Foundation.h>\n#import <dispatch/dispatch.h>\nextern void cmuxChiefBundleCapture(void);\n__attribute__((constructor)) static void cmuxLoad(void) { dispatch_async(dispatch_get_main_queue(), ^{ cmuxChiefBundleCapture(); }); }\n')
    provenance = dict(schema=1,revision=manifest['revision'],projectCommit=project_commit,sourceSha256=pins,
                      mainKeys=sorted(main_keys),moduleKeys=sorted(module_keys),languages=list(LANGUAGES),
                      generatedSha256={p.name:sha(p) for p in out.glob('*') if p.is_file()},
                      generatorSha256=sha(__file__),scope='Original full app project/catalogs and source L10n. Read-only sidecar, no authentication or UI actions; not native Home/question rendering.')
    (out/'Provenance.json').write_text(json.dumps(provenance,indent=2)+'\n')
    return provenance


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--upstream',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--project-commit',required=True)
    parser.add_argument('--prepare-only',action='store_true')
    args=parser.parse_args();upstream=args.upstream.resolve();out=args.output.resolve()
    provenance=prepare(upstream,out,args.project_commit)
    if args.prepare_only:
        print('Prepared original L10n and',len(provenance['mainKeys']),'main /',len(provenance['moduleKeys']),'module keys');return
    assert subprocess.check_output(['git','-C',str(upstream),'rev-parse','HEAD'],text=True).strip()==provenance['revision']
    assert not subprocess.check_output(['git','-C',str(upstream),'diff','--name-only'],text=True).strip()
    sid=None;passed=False
    def command(argv,timeout=120,env=None):
        return subprocess.check_output([str(x) for x in argv],text=True,timeout=timeout,env=env).strip()
    try:
        runtimes=json.loads(command(['xcrun','simctl','list','runtimes','-j']))['runtimes']
        available=[r for r in runtimes if r.get('isAvailable') and r['name'].startswith('iOS 26')]
        assert available,'No available pinned-family iOS26 runtime'
        runtime=sorted(available,key=lambda r:tuple(int(x) for x in r['version'].split('.')))[-1]
        devices=json.loads(command(['xcrun','simctl','list','devicetypes','-j']))['devicetypes']
        device=next(d for d in devices if d['name']=='iPhone 17 Pro')
        sid=command(['xcrun','simctl','create','cmux-chief-bundle-'+os.environ.get('GITHUB_RUN_ID','local'),device['identifier'],runtime['identifier']])
        (out/'simulator.json').write_text(json.dumps(dict(udid=sid,runtime=runtime,device=device),indent=2)+'\n')
        derived=out/'DerivedData'
        argv=['xcodebuild','-workspace','ios/cmux.xcworkspace','-scheme','cmux-ios','-configuration','Debug',
              '-destination','platform=iOS Simulator,id='+sid,'-derivedDataPath',str(derived),
              '-clonedSourcePackagesDirPath',str(out/'SourcePackages'),'-jobs','2','-onlyUsePackageVersionsFromResolvedFile','CODE_SIGNING_ALLOWED=NO','build']
        with (out/'build.log').open('w') as log:
            result=subprocess.run(argv,cwd=upstream,stdout=log,stderr=subprocess.STDOUT,timeout=2400)
        assert result.returncode==0,'Original app build failed; inspect build.log'
        assert not command(['git','-C',upstream,'diff','--name-only']),'Original tracked app inputs changed'
        apps=list((derived/'Build/Products/Debug-iphonesimulator').glob('*.app'));assert len(apps)==1,apps
        app=apps[0];info=plistlib.loads((app/'Info.plist').read_bytes());bundle_id=info['CFBundleIdentifier']
        executable=app/info['CFBundleExecutable'];arch=os.uname().machine
        assert arch in command(['lipo','-archs',executable]).split()
        (out/'build-product.json').write_text(json.dumps(dict(bundleID=bundle_id,executableSha256=sha(executable),infoSha256=sha(app/'Info.plist'),
            resourceSha256={str(p.relative_to(app)):sha(p) for p in app.rglob('*') if p.is_file() and p.name in ['Localizable.strings','Localizable.stringsdict']},
            xcode=command(['xcodebuild','-version']),architecture=arch,go=command(['go','version']),originalTrackedInputsUnchanged=True),indent=2)+'\n')
        sdk=command(['xcrun','--sdk','iphonesimulator','--show-sdk-path']);target=arch+'-apple-ios18.0-simulator'
        command(['xcrun','clang','-target',target,'-isysroot',sdk,'-fobjc-arc','-fblocks','-c',out/'ProbeLoad.m','-o',out/'ProbeLoad.o'])
        command(['xcrun','swiftc','-swift-version','6','-target',target,'-sdk',sdk,'-emit-library','-module-name','ChiefBundleProbe',
                 out/'OriginalL10n.swift',out/'CaptureCalls.swift',out/'Probe.swift',out/'ProbeLoad.o','-o',out/'libChiefBundleProbe.dylib'])
        product=json.loads((out/'build-product.json').read_text())
        product['probeLibrarySha256']=sha(out/'libChiefBundleProbe.dylib')
        (out/'build-product.json').write_text(json.dumps(product,indent=2)+'\n')
        command(['xcrun','simctl','boot',sid]);command(['xcrun','simctl','bootstatus',sid,'-b'],timeout=300)
        command(['xcrun','simctl','install',sid,app],timeout=180)
        container=Path(command(['xcrun','simctl','get_app_container',sid,bundle_id,'data']))
        captures=out/'observations';captures.mkdir()
        for language,region in LANGUAGES.items():
            env=os.environ.copy();env['SIMCTL_CHILD_DYLD_INSERT_LIBRARIES']=str(out/'libChiefBundleProbe.dylib')
            env['SIMCTL_CHILD_CMUX_REFERENCE_BUNDLE_LOCALE']=language
            launch=command(['xcrun','simctl','launch','--terminate-running-process',sid,bundle_id,
                            '-AppleLanguages','('+json.dumps(language)+')','-AppleLocale',region],env=env)
            (out/('launch-'+language+'.log')).write_text(launch+'\n')
            report=container/'Documents'/('cmux-chief-bundle-'+language+'.json')
            deadline=time.monotonic()+60
            while not report.is_file() and time.monotonic()<deadline:time.sleep(.25)
            assert report.is_file(),'Native sidecar did not capture '+language
            shutil.copy2(report,captures/(language+'.json'))
            command(['xcrun','simctl','io',sid,'screenshot',captures/(language+'.png')])
            command(['xcrun','simctl','terminate',sid,bundle_id])
        command(['python3',ROOT/'scripts/verify-ios-chief-bundles.py','--capture',out,'--project-commit',args.project_commit])
        passed=True
    finally:
        cleanup=[]
        if sid:
            for action in ['shutdown','delete']:
                result=subprocess.run(['xcrun','simctl',action,sid],capture_output=True,text=True,timeout=60)
                cleanup.append(dict(action=action,exitCode=result.returncode,output=result.stdout+result.stderr))
        (out/'completion.json').write_text(json.dumps(dict(passed=passed,ownedSimulator=sid,cleanup=cleanup),indent=2)+'\n')
    assert all(item['exitCode']==0 for item in cleanup),'Owned simulator cleanup failed'
    print('Original app bundle observations complete')


if __name__=='__main__':main()
