#!/usr/bin/env python3
"""Build a source-bound, offline UI host for the unchanged Cloud onboarding leaves."""
import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tarfile


def prepare(upstream, output):
    root = Path(__file__).resolve().parents[1]
    manifest = json.loads((root / 'third_party/cmux-cloud/onboarding-manifest.json').read_text())
    pin = manifest['revision']
    git = lambda *args: subprocess.check_output(['git', '-C', str(upstream), *args])
    for path, tree in manifest['packageTrees'].items():
        if git('rev-parse', pin + ':' + path).decode().strip() != tree:
            raise ValueError('Unreviewed source package: ' + path)
    files = {path: git('show', pin + ':' + path) for path in manifest['files']}
    for path, raw in files.items():
        if hashlib.sha256(raw).hexdigest() != manifest['files'][path]:
            raise ValueError('Unreviewed source file: ' + path)
    output.mkdir(parents=True, exist_ok=False)
    # Keep the real relative package dependency paths. No source or Package.swift edits.
    source = output / 'Upstream'; source.mkdir()
    with tarfile.open(fileobj=io.BytesIO(git('archive', pin, *manifest['packageTrees']))) as archive:
        for member in archive:
            if member.isdir(): continue
            name = PurePosixPath(member.name)
            if not member.isfile() or name.is_absolute() or '..' in name.parts:
                raise ValueError('Unsafe source package member')
            target = source / str(name); target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.extractfile(member).read())
    (source / 'LICENSE').write_bytes(git('show', pin + ':LICENSE'))
    wrapper = output / 'ReferencePackage'; leaves = wrapper / 'Sources/CmuxCloudOnboardingReference'
    leaves.mkdir(parents=True)
    for path, raw in files.items():
        if path.endswith('.swift'): (leaves / Path(path).name).write_bytes(raw)
        else: (output / 'Localizable.xcstrings').write_bytes(raw)
    (wrapper / 'Package.swift').write_text('''// swift-tools-version: 6.0
import PackageDescription
let package = Package(name: "CmuxCloudOnboardingReference", platforms: [.iOS(.v17)],
    products: [.library(name: "CmuxCloudOnboardingReference", targets: ["CmuxCloudOnboardingReference"])],
    dependencies: [.package(path: "../Upstream/Packages/iOS/CmuxMobileCloud"),
        .package(path: "../Upstream/Packages/iOS/CmuxMobileSupport")],
    targets: [.target(name: "CmuxCloudOnboardingReference", dependencies: ["CmuxMobileCloud", "CmuxMobileSupport"],
        swiftSettings: [.swiftLanguageMode(.v6), .enableUpcomingFeature("ExistentialAny")])])
''')
    templates = root / 'tests/ios-cloud-onboarding-reference'
    for name in ('ReferenceApp.swift', 'ReferenceUITests.swift', 'References.json'):
        shutil.copy2(templates / name, output / name)
    dirty = subprocess.check_output(['git', '-C', str(root), 'status', '--porcelain', '--untracked-files=normal']).strip()
    provenance = dict(upstreamRevision=pin, packageTrees=manifest['packageTrees'], sourceFiles=manifest['files'],
        projectSourceCommit=subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD'], text=True).strip(),
        projectSourceDirty=bool(dirty), fixtureSha256=hashlib.sha256((templates / 'References.json').read_bytes()).hexdigest(),
        appSha256=hashlib.sha256((templates / 'ReferenceApp.swift').read_bytes()).hexdigest(),
        testSha256=hashlib.sha256((templates / 'ReferenceUITests.swift').read_bytes()).hexdigest(),
        scope=manifest['scope'] + ' No session, VPN controller, credentials or production service is instantiated.')
    (output / 'Provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
    # Two small targets: host and real UI-test process/compositor. No XCTest host-only raster substitute.
    uid = lambda name: hashlib.sha256(name.encode()).hexdigest()[:24].upper()
    q = json.dumps
    objects = []
    def obj(name, kind, body):
        objects.append(f'{uid(name)} = {{ isa = {kind}; {body} }};'); return uid(name)
    references = {}
    for name, kind in [('ReferenceApp.swift', 'sourcecode.swift'), ('ReferenceUITests.swift', 'sourcecode.swift'),
                       ('References.json', 'text.json'), ('Provenance.json', 'text.json'), ('Localizable.xcstrings', 'text.json.xcstrings')]:
        references[name] = obj(name, 'PBXFileReference', f'lastKnownFileType = {q(kind)}; path = {q(name)}; sourceTree = "<group>";')
    app_product = obj('app-product', 'PBXFileReference', 'explicitFileType = wrapper.application; path = CloudOnboardingReference.app; sourceTree = BUILT_PRODUCTS_DIR;')
    test_product = obj('test-product', 'PBXFileReference', 'explicitFileType = wrapper.cfbundle; path = CloudOnboardingUITests.xctest; sourceTree = BUILT_PRODUCTS_DIR;')
    products = obj('products', 'PBXGroup', f'children = ({app_product}, {test_product},); name = Products; sourceTree = "<group>";')
    group = obj('main-group', 'PBXGroup', f'children = ({", ".join(references.values())}, {products},); sourceTree = "<group>";')
    package = obj('package', 'XCLocalSwiftPackageReference', 'relativePath = ReferencePackage;')
    product = obj('cloud-product', 'XCSwiftPackageProductDependency', f'package = {package}; productName = CmuxCloudOnboardingReference;')
    library = obj('cloud-build', 'PBXBuildFile', f'productRef = {product};')
    frameworks = obj('app-frameworks', 'PBXFrameworksBuildPhase', f'buildActionMask = 2147483647; files = ({library},); runOnlyForDeploymentPostprocessing = 0;')
    def sources(name, file):
        build = obj(name + '-source', 'PBXBuildFile', f'fileRef = {references[file]};')
        return obj(name + '-sources', 'PBXSourcesBuildPhase', f'buildActionMask = 2147483647; files = ({build},); runOnlyForDeploymentPostprocessing = 0;')
    def resources(name, names):
        builds = [obj(name + '-' + file, 'PBXBuildFile', f'fileRef = {references[file]};') for file in names]
        return obj(name + '-resources', 'PBXResourcesBuildPhase', f'buildActionMask = 2147483647; files = ({", ".join(builds)},); runOnlyForDeploymentPostprocessing = 0;')
    app_sources = sources('app', 'ReferenceApp.swift'); test_sources = sources('test', 'ReferenceUITests.swift')
    app_resources = resources('app', ['References.json', 'Provenance.json', 'Localizable.xcstrings'])
    test_resources = resources('test', ['References.json', 'Provenance.json'])
    def configurations(name, settings):
        values = [obj(name + config, 'XCBuildConfiguration', f'buildSettings = {{ {settings} }}; name = {config};') for config in ('Debug', 'Release')]
        return obj(name + '-configs', 'XCConfigurationList', f'buildConfigurations = ({", ".join(values)},); defaultConfigurationIsVisible = 0; defaultConfigurationName = Debug;')
    common = 'SDKROOT = iphoneos; IPHONEOS_DEPLOYMENT_TARGET = 17.0; SWIFT_VERSION = 6.0; CLANG_ENABLE_MODULES = YES; CODE_SIGNING_ALLOWED = NO; ONLY_ACTIVE_ARCH = YES;'
    project_config = configurations('project', common)
    app_config = configurations('app', 'PRODUCT_NAME = "$(TARGET_NAME)"; PRODUCT_BUNDLE_IDENTIFIER = io.github.docmorphic.cmuxapp.reference.cloud; GENERATE_INFOPLIST_FILE = YES; INFOPLIST_KEY_UIApplicationSceneManifest_Generation = YES; INFOPLIST_KEY_UILaunchScreen_Generation = YES; INFOPLIST_KEY_UISupportedInterfaceOrientations = "UIInterfaceOrientationPortrait UIInterfaceOrientationLandscapeLeft UIInterfaceOrientationLandscapeRight"; TARGETED_DEVICE_FAMILY = "1,2"; SUPPORTED_PLATFORMS = "iphoneos iphonesimulator";')
    test_config = configurations('test', 'PRODUCT_NAME = "$(TARGET_NAME)"; PRODUCT_BUNDLE_IDENTIFIER = io.github.docmorphic.cmuxapp.reference.cloud.uitests; GENERATE_INFOPLIST_FILE = YES; TARGETED_DEVICE_FAMILY = "1,2"; SUPPORTED_PLATFORMS = "iphoneos iphonesimulator"; TEST_TARGET_NAME = CloudOnboardingReference;')
    proxy = obj('host-proxy', 'PBXContainerItemProxy', f'containerPortal = {uid("project")}; proxyType = 1; remoteGlobalIDString = {uid("app")}; remoteInfo = CloudOnboardingReference;')
    dependency = obj('host-dependency', 'PBXTargetDependency', f'target = {uid("app")}; targetProxy = {proxy};')
    app = obj('app', 'PBXNativeTarget', f'buildConfigurationList = {app_config}; buildPhases = ({app_sources}, {frameworks}, {app_resources},); buildRules = (); dependencies = (); packageProductDependencies = ({product},); name = CloudOnboardingReference; productName = CloudOnboardingReference; productReference = {app_product}; productType = "com.apple.product-type.application";')
    test = obj('test', 'PBXNativeTarget', f'buildConfigurationList = {test_config}; buildPhases = ({test_sources}, {test_resources},); buildRules = (); dependencies = ({dependency},); name = CloudOnboardingUITests; productName = CloudOnboardingUITests; productReference = {test_product}; productType = "com.apple.product-type.bundle.ui-testing";')
    obj('project', 'PBXProject', f'attributes = {{ LastUpgradeCheck = 2600; TargetAttributes = {{ {test} = {{ TestTargetID = {app}; }}; }}; }}; buildConfigurationList = {project_config}; compatibilityVersion = "Xcode 15.0"; developmentRegion = en; hasScannedForEncodings = 0; knownRegions = (en, Base, ar, de, es, fr, ja, ko, "zh-Hans", "zh-Hant",); mainGroup = {group}; packageReferences = ({package},); productRefGroup = {products}; projectDirPath = ""; projectRoot = ""; targets = ({app}, {test},);')
    project = output / 'CloudOnboardingReference.xcodeproj'; project.mkdir()
    (project / 'project.pbxproj').write_text('// !$*UTF8*$!\n{ archiveVersion = 1; classes = {}; objectVersion = 60; objects = {\n' + '\n'.join(objects) + f'\n}}; rootObject = {uid("project")}; }}\n')
    def buildable(name, file, identifier):
        return f'<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{identifier}" BuildableName="{file}" BlueprintName="{name}" ReferencedContainer="container:CloudOnboardingReference.xcodeproj"/>'
    host = buildable('CloudOnboardingReference', 'CloudOnboardingReference.app', app)
    tests = buildable('CloudOnboardingUITests', 'CloudOnboardingUITests.xctest', test)
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
    (schemes / 'CloudOnboardingReference.xcscheme').write_text(scheme)
    return provenance


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--upstream', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    print(json.dumps(prepare(args.upstream.resolve(), args.output.resolve()), indent=2))
