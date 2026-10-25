#!/usr/bin/env python3
"""Create an offline host linking the whole pinned original ShellUI package."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

ROOT=Path(__file__).resolve().parents[1]
PACKAGES={'CmuxMobileShellUI':'Packages/iOS/CmuxMobileShellUI',
          'CmuxMobileShell':'Packages/iOS/CmuxMobileShell',
          'CmuxMobileRPC':'Packages/iOS/CmuxMobileRPC',
          'CMUXMobileCore':'Packages/Shared/CMUXMobileCore'}


def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def prepare(upstream, output):
    manifest=json.loads((ROOT/'third_party/cmux-chief/manifest.json').read_text())
    revision=manifest['revision']
    def git(*args):return subprocess.check_output(['git','-C',str(upstream),*args])
    assert git('rev-parse','HEAD').decode().strip()==revision
    assert not git('diff','--name-only').strip(),'Original sources must be unchanged'
    pins=dict(manifest['questionPresentationFiles'])
    pins.update(manifest['nativeBundleFiles'])
    pins.update(manifest['files'])
    for path,expected in pins.items():assert hashlib.sha256(git('show',revision+':'+path)).hexdigest()==expected,path
    output.mkdir(parents=True,exist_ok=False)
    templates=ROOT/'tests/ios-chief-home-reference'
    for name in ['ReferenceApp.swift','HomeUITests.swift','Scenes.json']:shutil.copy2(templates/name,output/name)
    catalog=next(row for row in manifest['homeStrings']['catalogs'] if row['path'].startswith('ios/'))
    raw=git('show',revision+':'+catalog['path']);assert hashlib.sha256(raw).hexdigest()==catalog['sha256']
    (output/'Localizable.xcstrings').write_bytes(raw)
    provenance=dict(schema=1,revision=revision,sourceSha256=pins,mainCatalog=catalog,
        packages={name:dict(path=path,tree=git('rev-parse',revision+':'+path).decode().strip()) for name,path in PACKAGES.items()},
        projectCommit=subprocess.check_output(['git','-C',str(ROOT),'rev-parse','HEAD'],text=True).strip(),
        projectSourceDirty=bool(subprocess.check_output(['git','-C',str(ROOT),'status','--porcelain','--untracked-files=normal'])),
        generatorSha256=sha(__file__),templates={p.name:sha(p) for p in templates.iterdir() if p.is_file()},
        scope='Whole original Home view, MobileChiefClient, ChiefConversationBackend and original package dependencies with an offline byte-transport seam. Main app catalog is copied unchanged; no account, external network, real Mac or physical parity acceptance.')
    (output/'Provenance.json').write_text(json.dumps(provenance,indent=2)+'\n')
    def uid(name):return hashlib.sha256(name.encode()).hexdigest()[:24].upper()
    q=json.dumps;objects=[]
    def obj(name,isa,body):
        objects.append(f'{uid(name)} = {{ isa = {isa}; {body} }};');return uid(name)
    refs={}
    for name,kind in [('ReferenceApp.swift','sourcecode.swift'),('HomeUITests.swift','sourcecode.swift'),('Scenes.json','text.json'),('Localizable.xcstrings','text.json.xcstrings'),('Provenance.json','text.json')]:
        refs[name]=obj(name,'PBXFileReference',f'lastKnownFileType = {q(kind)}; path = {q(name)}; sourceTree = "<group>";')
    app_product=obj('app-product','PBXFileReference','explicitFileType = wrapper.application; path = ChiefHomeReference.app; sourceTree = BUILT_PRODUCTS_DIR;')
    ui_product=obj('ui-product','PBXFileReference','explicitFileType = wrapper.cfbundle; path = ChiefHomeUITests.xctest; sourceTree = BUILT_PRODUCTS_DIR;')
    products=obj('products','PBXGroup',f'children = ({app_product}, {ui_product},); name = Products; sourceTree = "<group>";')
    group=obj('main-group','PBXGroup',f'children = ({", ".join(refs.values())}, {products},); sourceTree = "<group>";')
    package_refs=[];package_products=[];frameworks=[]
    for name,path in PACKAGES.items():
        ref=obj(name+'-package','XCLocalSwiftPackageReference',f'relativePath = {q(os.path.relpath(upstream/path,output))};')
        package_refs.append(ref)
        product=obj(name+'-product','XCSwiftPackageProductDependency',f'package = {ref}; productName = {name};')
        package_products.append(product);frameworks.append(obj(name+'-build','PBXBuildFile',f'productRef = {product};'))
    def phase(name,isa,files):return obj(name,isa,f'buildActionMask = 2147483647; files = ({", ".join(files)},); runOnlyForDeploymentPostprocessing = 0;')
    def file_build(name,target):return obj(name+'-'+target,'PBXBuildFile',f'fileRef = {refs[name]};')
    sources=phase('app-sources','PBXSourcesBuildPhase',[file_build('ReferenceApp.swift','app')])
    ui_sources=phase('ui-sources','PBXSourcesBuildPhase',[file_build('HomeUITests.swift','ui')])
    app_frameworks=phase('app-frameworks','PBXFrameworksBuildPhase',frameworks)
    resources=phase('app-resources','PBXResourcesBuildPhase',[file_build(n,'app') for n in ['Scenes.json','Localizable.xcstrings','Provenance.json']])
    def configs(name,settings):
        ids=[obj(name+c,'XCBuildConfiguration',f'buildSettings = {{ {settings} }}; name = {c};') for c in ['Debug','Release']]
        return obj(name+'-configs','XCConfigurationList',f'buildConfigurations = ({", ".join(ids)},); defaultConfigurationIsVisible = 0; defaultConfigurationName = Debug;')
    common=configs('project','SDKROOT = iphoneos; IPHONEOS_DEPLOYMENT_TARGET = 17.0; SWIFT_VERSION = 6.0; CLANG_ENABLE_MODULES = YES; CODE_SIGNING_ALLOWED = NO; ONLY_ACTIVE_ARCH = YES; ENABLE_TESTABILITY = YES; SWIFT_OPTIMIZATION_LEVEL = "-Onone";')
    app_configs=configs('app','PRODUCT_NAME = "$(TARGET_NAME)"; PRODUCT_BUNDLE_IDENTIFIER = io.github.docmorphic.cmuxapp.reference.home; GENERATE_INFOPLIST_FILE = YES; INFOPLIST_KEY_UIApplicationSceneManifest_Generation = YES; INFOPLIST_KEY_UILaunchScreen_Generation = YES; TARGETED_DEVICE_FAMILY = 1; SUPPORTED_PLATFORMS = "iphoneos iphonesimulator";')
    ui_configs=configs('ui','PRODUCT_NAME = "$(TARGET_NAME)"; PRODUCT_BUNDLE_IDENTIFIER = io.github.docmorphic.cmuxapp.reference.home.uitests; GENERATE_INFOPLIST_FILE = YES; TARGETED_DEVICE_FAMILY = 1; SUPPORTED_PLATFORMS = "iphoneos iphonesimulator"; TEST_TARGET_NAME = ChiefHomeReference;')
    proxy=obj('host-proxy','PBXContainerItemProxy',f'containerPortal = {uid("project")}; proxyType = 1; remoteGlobalIDString = {uid("app")}; remoteInfo = ChiefHomeReference;')
    dependency=obj('host-dependency','PBXTargetDependency',f'target = {uid("app")}; targetProxy = {proxy};')
    app=obj('app','PBXNativeTarget',f'buildConfigurationList = {app_configs}; buildPhases = ({sources}, {app_frameworks}, {resources},); buildRules = (); dependencies = (); packageProductDependencies = ({", ".join(package_products)},); name = ChiefHomeReference; productName = ChiefHomeReference; productReference = {app_product}; productType = "com.apple.product-type.application";')
    ui=obj('ui','PBXNativeTarget',f'buildConfigurationList = {ui_configs}; buildPhases = ({ui_sources},); buildRules = (); dependencies = ({dependency},); name = ChiefHomeUITests; productName = ChiefHomeUITests; productReference = {ui_product}; productType = "com.apple.product-type.bundle.ui-testing";')
    obj('project','PBXProject',f'attributes = {{ LastUpgradeCheck = 2600; TargetAttributes = {{ {ui} = {{ TestTargetID = {app}; }}; }}; }}; buildConfigurationList = {common}; compatibilityVersion = "Xcode 15.0"; developmentRegion = en; hasScannedForEncodings = 0; knownRegions = (en, ja, de, es, fr, ar, ko, "zh-Hans", "zh-Hant", Base,); mainGroup = {group}; packageReferences = ({", ".join(package_refs)},); productRefGroup = {products}; projectDirPath = ""; projectRoot = ""; targets = ({app}, {ui},);')
    project=output/'ChiefHomeReference.xcodeproj';project.mkdir()
    (project/'project.pbxproj').write_text('// !$*UTF8*$!\n{ archiveVersion = 1; classes = {}; objectVersion = 60; objects = {\n'+'\n'.join(objects)+f'\n}}; rootObject = {uid("project")}; }}\n')
    def buildable(name,ext,id):return f'<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{id}" BuildableName="{name}.{ext}" BlueprintName="{name}" ReferencedContainer="container:ChiefHomeReference.xcodeproj"/>'
    host=buildable('ChiefHomeReference','app',app);test=buildable('ChiefHomeUITests','xctest',ui)
    scheme=f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="2600" version="1.3">
<BuildAction parallelizeBuildables="NO" buildImplicitDependencies="YES"><BuildActionEntries>
<BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="NO" buildForArchiving="NO" buildForAnalyzing="YES">{host}</BuildActionEntry>
<BuildActionEntry buildForTesting="YES" buildForRunning="NO" buildForProfiling="NO" buildForArchiving="NO" buildForAnalyzing="YES">{test}</BuildActionEntry>
</BuildActionEntries></BuildAction>
<TestAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv="YES"><MacroExpansion>{host}</MacroExpansion><Testables><TestableReference skipped="NO" parallelizable="NO">{test}</TestableReference></Testables></TestAction>
<LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB"><BuildableProductRunnable runnableDebuggingMode="0">{host}</BuildableProductRunnable></LaunchAction>
</Scheme>
'''
    directory=project/'xcshareddata/xcschemes';directory.mkdir(parents=True)
    (directory/'ChiefHomeReference.xcscheme').write_text(scheme)
    return provenance


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--upstream',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();result=prepare(args.upstream.resolve(),args.output.resolve())
    print(json.dumps(result,indent=2))
