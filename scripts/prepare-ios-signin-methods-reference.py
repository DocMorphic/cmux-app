#!/usr/bin/env python3
"""Prepare unchanged original sign-in UI declarations with an offline service adapter."""
import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]
SHELL = 'Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/'

def declaration(source, marker):
    start = source.index(marker)
    brace = source.index('{', start)
    depth = 0; quoted = False; escaped = False; line_comment = False
    for i in range(brace, len(source)):
        c = source[i]
        if line_comment:
            if c == '\n': line_comment = False
            continue
        if quoted:
            if escaped: escaped = False
            elif c == '\\': escaped = True
            elif c == '"': quoted = False
            continue
        if c == '"': quoted = True; continue
        if source[i:i+2] == '//': line_comment = True; continue
        if c == '{': depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0: return source[start:i+1]
    raise ValueError('Unclosed original declaration: '+marker)


def prepare(upstream, output):
    manifest = json.loads((ROOT/'third_party/cmux-signin/methods-reference.json').read_text())
    pin = manifest['revision']; git = lambda *args: subprocess.check_output(['git','-C',str(upstream),*args])
    for path, tree in manifest['packageTrees'].items():
        if git('rev-parse',pin+':'+path).decode().strip()!=tree: raise ValueError('Changed package: '+path)
    files={p:git('show',pin+':'+p) for p in manifest['files']}
    for path, raw in files.items():
        if hashlib.sha256(raw).hexdigest()!=manifest['files'][path]: raise ValueError('Changed original: '+path)
    output.mkdir(parents=True,exist_ok=False); source=output/'Upstream';source.mkdir()
    with tarfile.open(fileobj=io.BytesIO(git('archive',pin,*manifest['packageTrees']))) as archive:
        for member in archive:
            if member.isdir(): continue
            name=PurePosixPath(member.name)
            if not member.isfile() or name.is_absolute() or '..' in name.parts: raise ValueError('Unsafe package entry')
            target=source/str(name);target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(archive.extractfile(member).read())
    (source/'LICENSE').write_bytes(git('show',pin+':LICENSE'))
    wrapper=output/'ReferencePackage';leaves=wrapper/'Sources/CmuxSignInMethodsReference';leaves.mkdir(parents=True)
    for name in ['GlassInputPill.swift','DividerLabel.swift','PlatformPalette.swift','GameOfLifeHeader.swift','GameOfLifeGrid.swift','View+MobileButtonLoading.swift']:
        (leaves/name).write_bytes(files[SHELL+name])
    declarations={}
    def original(path, marker):
        value=declaration(files[path].decode(),marker);declarations[path+'#'+marker]=hashlib.sha256(value.encode()).hexdigest();return value
    members=[original(SHELL+'SignInView.swift',m) for m in [
        '    @ViewBuilder\n    var body: some View', '    private var keyboardDismissSurface:',
        '    @ViewBuilder\n    private var signInEntrySwitcher:', '    private var emailEntryView:',
        '    private var isInteractiveAuthInProgress:', '    private var isAuthInProgress:',
        '    private func oauthButton(', '    private func errorText(', '    private func authCard<', '    private var brandHeader:']]
    providers=original(SHELL+'OAuthSignInProvider.swift','enum OAuthSignInProvider:')
    restore=original(SHELL+'SignInAuthRestoreStatusView.swift','struct SignInAuthRestoreStatusView:')
    email=original(SHELL+'View+MobileTextInput.swift','    @ViewBuilder\n    func mobileEmailTextInput()')
    nav=original(manifest['navigationSource'],'    @ViewBuilder\n    func mobileInlineNavigationTitle()')
    adapter=(ROOT/'tests/ios-signin-methods-reference/OriginalContext.swift').read_text()
    (leaves/'OriginalMethods.swift').write_text(adapter.replace('/* ORIGINAL_MEMBERS */','\n\n'.join(members))+
        '\n'+providers+'\n'+restore+'\nextension View {\n'+email+'\n'+nav+'\n}\n')
    (wrapper/'Package.swift').write_text('''// swift-tools-version: 6.0
import PackageDescription
let package = Package(name: "CmuxSignInMethodsReference", platforms: [.iOS(.v17)],
 products: [.library(name: "CmuxSignInMethodsReference", targets: ["CmuxSignInMethodsReference"])],
 dependencies: [.package(path: "../Upstream/Packages/iOS/CmuxMobileSupport")],
 targets: [.target(name: "CmuxSignInMethodsReference", dependencies: ["CmuxMobileSupport"],
 swiftSettings: [.swiftLanguageMode(.v6), .enableUpcomingFeature("ExistentialAny")])])
''')
    for path,raw in files.items():
        if '/Assets.xcassets/' in path:
            asset=output/'Assets.xcassets'/path.split('/Assets.xcassets/',1)[1];asset.parent.mkdir(parents=True,exist_ok=True);asset.write_bytes(raw)
    (output/'Assets.xcassets/Contents.json').write_text('{"info":{"author":"xcode","version":1}}\n')
    (output/'Localizable.xcstrings').write_bytes(files['ios/cmux/Resources/Localizable.xcstrings'])
    templates=ROOT/'tests/ios-signin-methods-reference'
    for name in ['ReferenceApp.swift','ReferenceUITests.swift','References.json']: shutil.copy2(templates/name,output/name)
    provenance=dict(upstreamRevision=pin,packageTrees=manifest['packageTrees'],sourceFiles=manifest['files'],declarations=declarations,
        projectSourceCommit=subprocess.check_output(['git','-C',str(ROOT),'rev-parse','HEAD'],text=True).strip(),
        projectSourceDirty=bool(subprocess.check_output(['git','-C',str(ROOT),'status','--porcelain'])),
        templates={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in templates.iterdir() if p.is_file()},
        generatorSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),scope=manifest['scope'])
    (output/'Provenance.json').write_text(json.dumps(provenance,indent=2)+'\n')
    # Two small targets: host and real UI-test process/compositor. No XCTest host-only raster substitute.
    uid = lambda name: hashlib.sha256(name.encode()).hexdigest()[:24].upper()
    q = json.dumps
    objects = []
    def obj(name, kind, body):
        objects.append(f'{uid(name)} = {{ isa = {kind}; {body} }};'); return uid(name)
    references = {}
    for name, kind in [('ReferenceApp.swift', 'sourcecode.swift'), ('ReferenceUITests.swift', 'sourcecode.swift'),
                       ('References.json', 'text.json'), ('Provenance.json', 'text.json'), ('Localizable.xcstrings', 'text.json.xcstrings'), ('Assets.xcassets', 'folder.assetcatalog')]:
        references[name] = obj(name, 'PBXFileReference', f'lastKnownFileType = {q(kind)}; path = {q(name)}; sourceTree = "<group>";')
    app_product = obj('app-product', 'PBXFileReference', 'explicitFileType = wrapper.application; path = SignInMethodsReference.app; sourceTree = BUILT_PRODUCTS_DIR;')
    test_product = obj('test-product', 'PBXFileReference', 'explicitFileType = wrapper.cfbundle; path = SignInMethodsUITests.xctest; sourceTree = BUILT_PRODUCTS_DIR;')
    products = obj('products', 'PBXGroup', f'children = ({app_product}, {test_product},); name = Products; sourceTree = "<group>";')
    group = obj('main-group', 'PBXGroup', f'children = ({", ".join(references.values())}, {products},); sourceTree = "<group>";')
    package = obj('package', 'XCLocalSwiftPackageReference', 'relativePath = ReferencePackage;')
    product = obj('cloud-product', 'XCSwiftPackageProductDependency', f'package = {package}; productName = CmuxSignInMethodsReference;')
    library = obj('cloud-build', 'PBXBuildFile', f'productRef = {product};')
    frameworks = obj('app-frameworks', 'PBXFrameworksBuildPhase', f'buildActionMask = 2147483647; files = ({library},); runOnlyForDeploymentPostprocessing = 0;')
    def sources(name, file):
        build = obj(name + '-source', 'PBXBuildFile', f'fileRef = {references[file]};')
        return obj(name + '-sources', 'PBXSourcesBuildPhase', f'buildActionMask = 2147483647; files = ({build},); runOnlyForDeploymentPostprocessing = 0;')
    def resources(name, names):
        builds = [obj(name + '-' + file, 'PBXBuildFile', f'fileRef = {references[file]};') for file in names]
        return obj(name + '-resources', 'PBXResourcesBuildPhase', f'buildActionMask = 2147483647; files = ({", ".join(builds)},); runOnlyForDeploymentPostprocessing = 0;')
    app_sources = sources('app', 'ReferenceApp.swift'); test_sources = sources('test', 'ReferenceUITests.swift')
    app_resources = resources('app', ['References.json', 'Provenance.json', 'Localizable.xcstrings', 'Assets.xcassets'])
    test_resources = resources('test', ['References.json', 'Provenance.json'])
    def configurations(name, settings):
        values = [obj(name + config, 'XCBuildConfiguration', f'buildSettings = {{ {settings} }}; name = {config};') for config in ('Debug', 'Release')]
        return obj(name + '-configs', 'XCConfigurationList', f'buildConfigurations = ({", ".join(values)},); defaultConfigurationIsVisible = 0; defaultConfigurationName = Debug;')
    common = 'SDKROOT = iphoneos; IPHONEOS_DEPLOYMENT_TARGET = 17.0; SWIFT_VERSION = 6.0; CLANG_ENABLE_MODULES = YES; CODE_SIGNING_ALLOWED = NO; ONLY_ACTIVE_ARCH = YES;'
    project_config = configurations('project', common)
    app_config = configurations('app', 'PRODUCT_NAME = "$(TARGET_NAME)"; PRODUCT_BUNDLE_IDENTIFIER = io.github.docmorphic.cmuxapp.reference.signin; GENERATE_INFOPLIST_FILE = YES; INFOPLIST_KEY_UIApplicationSceneManifest_Generation = YES; INFOPLIST_KEY_UILaunchScreen_Generation = YES; INFOPLIST_KEY_UISupportedInterfaceOrientations = "UIInterfaceOrientationPortrait UIInterfaceOrientationLandscapeLeft UIInterfaceOrientationLandscapeRight"; TARGETED_DEVICE_FAMILY = "1,2"; SUPPORTED_PLATFORMS = "iphoneos iphonesimulator";')
    test_config = configurations('test', 'PRODUCT_NAME = "$(TARGET_NAME)"; PRODUCT_BUNDLE_IDENTIFIER = io.github.docmorphic.cmuxapp.reference.signin.uitests; GENERATE_INFOPLIST_FILE = YES; TARGETED_DEVICE_FAMILY = "1,2"; SUPPORTED_PLATFORMS = "iphoneos iphonesimulator"; TEST_TARGET_NAME = SignInMethodsReference;')
    proxy = obj('host-proxy', 'PBXContainerItemProxy', f'containerPortal = {uid("project")}; proxyType = 1; remoteGlobalIDString = {uid("app")}; remoteInfo = SignInMethodsReference;')
    dependency = obj('host-dependency', 'PBXTargetDependency', f'target = {uid("app")}; targetProxy = {proxy};')
    app = obj('app', 'PBXNativeTarget', f'buildConfigurationList = {app_config}; buildPhases = ({app_sources}, {frameworks}, {app_resources},); buildRules = (); dependencies = (); packageProductDependencies = ({product},); name = SignInMethodsReference; productName = SignInMethodsReference; productReference = {app_product}; productType = "com.apple.product-type.application";')
    test = obj('test', 'PBXNativeTarget', f'buildConfigurationList = {test_config}; buildPhases = ({test_sources}, {test_resources},); buildRules = (); dependencies = ({dependency},); name = SignInMethodsUITests; productName = SignInMethodsUITests; productReference = {test_product}; productType = "com.apple.product-type.bundle.ui-testing";')
    obj('project', 'PBXProject', f'attributes = {{ LastUpgradeCheck = 2600; TargetAttributes = {{ {test} = {{ TestTargetID = {app}; }}; }}; }}; buildConfigurationList = {project_config}; compatibilityVersion = "Xcode 15.0"; developmentRegion = en; hasScannedForEncodings = 0; knownRegions = (en, Base, ar, de, es, fr, ja, ko, "zh-Hans", "zh-Hant",); mainGroup = {group}; packageReferences = ({package},); productRefGroup = {products}; projectDirPath = ""; projectRoot = ""; targets = ({app}, {test},);')
    project = output / 'SignInMethodsReference.xcodeproj'; project.mkdir()
    (project / 'project.pbxproj').write_text('// !$*UTF8*$!\n{ archiveVersion = 1; classes = {}; objectVersion = 60; objects = {\n' + '\n'.join(objects) + f'\n}}; rootObject = {uid("project")}; }}\n')
    def buildable(name, file, identifier):
        return f'<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{identifier}" BuildableName="{file}" BlueprintName="{name}" ReferencedContainer="container:SignInMethodsReference.xcodeproj"/>'
    host = buildable('SignInMethodsReference', 'SignInMethodsReference.app', app)
    tests = buildable('SignInMethodsUITests', 'SignInMethodsUITests.xctest', test)
    scheme = f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="2600" version="1.3">
<BuildAction parallelizeBuildables="NO" buildImplicitDependencies="YES"><BuildActionEntries>
<BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="NO" buildForArchiving="NO" buildForAnalyzing="YES">{host}</BuildActionEntry>
<BuildActionEntry buildForTesting="YES" buildForRunning="NO" buildForProfiling="NO" buildForArchiving="NO" buildForAnalyzing="YES">{tests}</BuildActionEntry>
</BuildActionEntries></BuildAction>
<TestAction buildConfiguration="Debug" shouldUseLaunchSchemeArgsEnv="YES"><MacroExpansion>{host}</MacroExpansion><Testables><TestableReference skipped="NO" parallelizable="NO">{tests}</TestableReference></Testables></TestAction>
<LaunchAction buildConfiguration="Debug" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO"><BuildableProductRunnable runnableDebuggingMode="0">{host}</BuildableProductRunnable></LaunchAction>
</Scheme>
'''
    schemes = project / 'xcshareddata/xcschemes'; schemes.mkdir(parents=True)
    (schemes / 'SignInMethodsReference.xcscheme').write_text(scheme)
    return provenance


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--upstream',required=True,type=Path);parser.add_argument('--output',required=True,type=Path)
    args=parser.parse_args();print(json.dumps(prepare(args.upstream.resolve(),args.output.resolve()),indent=2))
