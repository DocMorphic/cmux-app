#!/usr/bin/env python3
"""Prepare the pinned whole original Feed reader with real local module dependencies."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
TEMPLATES = ROOT / 'tests/ios-feed-reader-reference'
PACKAGE = """// swift-tools-version: 6.0
import PackageDescription
let package = Package(name: "OfflineFeedReference", defaultLocalization: "en",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "CmuxMobileShellUI", targets: ["CmuxMobileShellUI"])],
    targets: [
        .target(name: "CmuxAgentChat"),
        .target(name: "CmuxAgentChatUI", dependencies: ["CmuxAgentChat"], resources: [.process("Resources")]),
        .target(name: "CmuxMobileShellModel"),
        .target(name: "CmuxMobileShellUI", dependencies: ["CmuxAgentChatUI", "CmuxMobileShellModel"], resources: [.process("Resources")])
    ])
"""


def prepare(upstream, output):
    manifest = json.loads((TEMPLATES / 'Source.json').read_text())
    pin = manifest['upstream']
    originals = {}
    # Read and validate every whole file before creating output. Never edit the
    # original declarations, branch out web dependencies or substitute model types.
    for path, expected in manifest['files'].items():
        raw = subprocess.check_output(['git', '-C', str(upstream), 'show', pin + ':' + path])
        if hashlib.sha256(raw).hexdigest() != expected: raise ValueError('Unreviewed whole original source: ' + path)
        originals[path] = raw
    output.mkdir(parents=True, exist_ok=False)
    modules = output / 'Modules'; modules.mkdir()
    (modules / 'Package.swift').write_text(PACKAGE)
    for path, raw in originals.items():
        relative = path.split('/Sources/', 1)[1]
        target = modules / 'Sources' / relative
        target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(raw)
    template_names = ('ReferenceApp.swift', 'ReferenceUITests.swift', 'References.json', 'OriginalSheetAdapter.swift', 'Source.json')
    for name in template_names:
        if name != 'OriginalSheetAdapter.swift': shutil.copy2(TEMPLATES / name, output / name)
    shutil.copy2(TEMPLATES / 'OriginalSheetAdapter.swift', modules / 'Sources/CmuxMobileShellUI/OriginalSheetAdapter.swift')
    (output / 'Localizable.xcstrings').write_bytes(originals['Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/Resources/Localizable.xcstrings'])
    (output / 'LICENSE').write_bytes(subprocess.check_output(['git', '-C', str(upstream), 'show', pin + ':LICENSE']))
    provenance = dict(upstreamRevision=pin, sourceFiles=manifest['files'],
        projectSourceCommit=subprocess.check_output(['git', '-C', str(ROOT), 'rev-parse', 'HEAD'], text=True).strip(),
        projectSourceDirty=bool(subprocess.check_output(['git', '-C', str(ROOT), 'status', '--porcelain', '--untracked-files=normal']).strip()),
        templateHashes={name: hashlib.sha256((TEMPLATES / name).read_bytes()).hexdigest() for name in template_names},
        packageManifestSha256=hashlib.sha256(PACKAGE.encode()).hexdigest(),
        preparerSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        context='presentedOriginalFullTextSheet', scope=manifest['scope'])
    (output / 'Provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
    source_names = []
    # Two small targets: host and real UI-test process/compositor. No XCTest host-only raster substitute.
    uid = lambda name: hashlib.sha256(name.encode()).hexdigest()[:24].upper()
    q = json.dumps
    objects = []
    def obj(name, kind, body):
        objects.append(f'{uid(name)} = {{ isa = {kind}; {body} }};'); return uid(name)
    references = {}
    for name, kind in [('ReferenceApp.swift', 'sourcecode.swift'), ('ReferenceUITests.swift', 'sourcecode.swift'),
                       ('References.json', 'text.json'), ('Provenance.json', 'text.json'), ('Localizable.xcstrings', 'text.json.xcstrings')] + [(name, 'sourcecode.swift') for name in source_names]:
        references[name] = obj(name, 'PBXFileReference', f'lastKnownFileType = {q(kind)}; path = {q(name)}; sourceTree = "<group>";')
    app_product = obj('app-product', 'PBXFileReference', 'explicitFileType = wrapper.application; path = FeedFullTextReference.app; sourceTree = BUILT_PRODUCTS_DIR;')
    test_product = obj('test-product', 'PBXFileReference', 'explicitFileType = wrapper.cfbundle; path = FeedFullTextUITests.xctest; sourceTree = BUILT_PRODUCTS_DIR;')
    products = obj('products', 'PBXGroup', f'children = ({app_product}, {test_product},); name = Products; sourceTree = "<group>";')
    group = obj('main-group', 'PBXGroup', f'children = ({", ".join(references.values())}, {products},); sourceTree = "<group>";')
    local_package = obj('reference-package', 'XCLocalSwiftPackageReference', 'relativePath = Modules;')
    package_product = obj('reference-product', 'XCSwiftPackageProductDependency', f'package = {local_package}; productName = CmuxMobileShellUI;')
    package_build = obj('reference-package-build', 'PBXBuildFile', f'productRef = {package_product};')
    frameworks = obj('app-frameworks', 'PBXFrameworksBuildPhase', f'buildActionMask = 2147483647; files = ({package_build},); runOnlyForDeploymentPostprocessing = 0;')
    def source_phase(name, names):
        builds = [obj(name + '-' + file, 'PBXBuildFile', f'fileRef = {references[file]};') for file in names]
        return obj(name + '-sources', 'PBXSourcesBuildPhase', f'buildActionMask = 2147483647; files = ({", ".join(builds)},); runOnlyForDeploymentPostprocessing = 0;')
    def resources(name, names):
        builds = [obj(name + '-' + file, 'PBXBuildFile', f'fileRef = {references[file]};') for file in names]
        return obj(name + '-resources', 'PBXResourcesBuildPhase', f'buildActionMask = 2147483647; files = ({", ".join(builds)},); runOnlyForDeploymentPostprocessing = 0;')
    app_sources = source_phase('app', ['ReferenceApp.swift'] + source_names); test_sources = source_phase('test', ['ReferenceUITests.swift'])
    app_resources = resources('app', ['References.json', 'Provenance.json', 'Localizable.xcstrings'])
    test_resources = resources('test', ['References.json', 'Provenance.json'])
    def configurations(name, settings):
        values = [obj(name + config, 'XCBuildConfiguration', f'buildSettings = {{ {settings} }}; name = {config};') for config in ('Debug', 'Release')]
        return obj(name + '-configs', 'XCConfigurationList', f'buildConfigurations = ({", ".join(values)},); defaultConfigurationIsVisible = 0; defaultConfigurationName = Debug;')
    common = 'SDKROOT = iphoneos; IPHONEOS_DEPLOYMENT_TARGET = 17.0; SWIFT_VERSION = 6.0; CLANG_ENABLE_MODULES = YES; CODE_SIGNING_ALLOWED = NO; ONLY_ACTIVE_ARCH = YES;'
    project_config = configurations('project', common)
    app_config = configurations('app', 'PRODUCT_NAME = "$(TARGET_NAME)"; PRODUCT_BUNDLE_IDENTIFIER = io.github.docmorphic.cmuxapp.reference.feedreader; GENERATE_INFOPLIST_FILE = YES; INFOPLIST_KEY_UIApplicationSceneManifest_Generation = YES; INFOPLIST_KEY_UILaunchScreen_Generation = YES; INFOPLIST_KEY_UISupportedInterfaceOrientations = "UIInterfaceOrientationPortrait UIInterfaceOrientationLandscapeLeft UIInterfaceOrientationLandscapeRight"; TARGETED_DEVICE_FAMILY = "1,2"; SUPPORTED_PLATFORMS = "iphoneos iphonesimulator";')
    test_config = configurations('test', 'PRODUCT_NAME = "$(TARGET_NAME)"; PRODUCT_BUNDLE_IDENTIFIER = io.github.docmorphic.cmuxapp.reference.feedreader.uitests; GENERATE_INFOPLIST_FILE = YES; TARGETED_DEVICE_FAMILY = "1,2"; SUPPORTED_PLATFORMS = "iphoneos iphonesimulator"; TEST_TARGET_NAME = FeedFullTextReference;')
    proxy = obj('host-proxy', 'PBXContainerItemProxy', f'containerPortal = {uid("project")}; proxyType = 1; remoteGlobalIDString = {uid("app")}; remoteInfo = FeedFullTextReference;')
    dependency = obj('host-dependency', 'PBXTargetDependency', f'target = {uid("app")}; targetProxy = {proxy};')
    app = obj('app', 'PBXNativeTarget', f'buildConfigurationList = {app_config}; buildPhases = ({app_sources}, {frameworks}, {app_resources},); buildRules = (); dependencies = (); packageProductDependencies = ({package_product},); name = FeedFullTextReference; productName = FeedFullTextReference; productReference = {app_product}; productType = "com.apple.product-type.application";')
    test = obj('test', 'PBXNativeTarget', f'buildConfigurationList = {test_config}; buildPhases = ({test_sources}, {test_resources},); buildRules = (); dependencies = ({dependency},); name = FeedFullTextUITests; productName = FeedFullTextUITests; productReference = {test_product}; productType = "com.apple.product-type.bundle.ui-testing";')
    obj('project', 'PBXProject', f'attributes = {{ LastUpgradeCheck = 2600; TargetAttributes = {{ {test} = {{ TestTargetID = {app}; }}; }}; }}; buildConfigurationList = {project_config}; compatibilityVersion = "Xcode 15.0"; developmentRegion = en; hasScannedForEncodings = 0; knownRegions = (en, Base, ar, de, es, fr, ja, ko, "zh-Hans", "zh-Hant",); mainGroup = {group}; packageReferences = ({local_package},); productRefGroup = {products}; projectDirPath = ""; projectRoot = ""; targets = ({app}, {test},);')
    project = output / 'FeedFullTextReference.xcodeproj'; project.mkdir()
    (project / 'project.pbxproj').write_text('// !$*UTF8*$!\n{ archiveVersion = 1; classes = {}; objectVersion = 60; objects = {\n' + '\n'.join(objects) + f'\n}}; rootObject = {uid("project")}; }}\n')
    def buildable(name, file, identifier):
        return f'<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{identifier}" BuildableName="{file}" BlueprintName="{name}" ReferencedContainer="container:FeedFullTextReference.xcodeproj"/>'
    host = buildable('FeedFullTextReference', 'FeedFullTextReference.app', app)
    tests = buildable('FeedFullTextUITests', 'FeedFullTextUITests.xctest', test)
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
    (schemes / 'FeedFullTextReference.xcscheme').write_text(scheme)
    return provenance


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--upstream', required=True, type=Path); parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    print(json.dumps(prepare(args.upstream.resolve(), args.output.resolve()), indent=2))
