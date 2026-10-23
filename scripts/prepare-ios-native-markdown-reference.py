#!/usr/bin/env python3
"""Prepare an isolated host of the pinned original native Markdown SwiftUI leaf."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
TEMPLATES = ROOT / 'tests/ios-native-markdown-reference'


def prepare(upstream, output):
    manifest = json.loads((ROOT / 'tests/native-markdown-reference/References.json').read_text())
    pin = manifest['upstream']
    def source(path, expected):
        raw = subprocess.check_output(['git', '-C', str(upstream), 'show', pin + ':' + path])
        if hashlib.sha256(raw).hexdigest() != expected: raise ValueError('Unreviewed original source: ' + path)
        return raw
    originals = {path: source(path, sha) for path, sha in manifest['files'].items()}
    leaf_source = source(manifest['inlineSource']['path'], manifest['inlineSource']['sha256'])
    marker = b'struct ChatArtifactMarkdownNativeView: View {'
    if leaf_source.count(marker) != 1: raise ValueError('Ambiguous native leaf declaration')
    # Copy the entire declaration to EOF, byte for byte. Only the independent
    # Foundation/SwiftUI import prefix is authored; the web wrapper is excluded.
    declaration = leaf_source[leaf_source.index(marker):]
    if not declaration.endswith(b'\n}\n'): raise ValueError('Unexpected native leaf boundary')
    reviewed = json.loads((TEMPLATES / 'Source.json').read_text())
    if hashlib.sha256(declaration).hexdigest() != reviewed['nativeDeclarationSha256']: raise ValueError('Changed native declaration extraction')
    output.mkdir(parents=True, exist_ok=False)
    source_names = []
    for path, raw in originals.items():
        name = Path(path).name; (output / name).write_bytes(raw); source_names.append(name)
    name = 'ChatArtifactMarkdownNativeView.swift'
    (output / name).write_bytes(b'import Foundation\nimport SwiftUI\n\n' + declaration); source_names.append(name)
    for name in ('ReferenceApp.swift', 'ReferenceUITests.swift', 'References.json', 'Localizable.xcstrings'):
        shutil.copy2(TEMPLATES / name, output / name)
    (output / 'LICENSE').write_bytes(subprocess.check_output(['git', '-C', str(upstream), 'show', pin + ':LICENSE']))
    provenance = dict(upstreamRevision=pin, sourceFiles=manifest['files'], leafSource=manifest['inlineSource'],
        nativeDeclarationSha256=hashlib.sha256(declaration).hexdigest(),
        compiledLeafSha256=hashlib.sha256((output / 'ChatArtifactMarkdownNativeView.swift').read_bytes()).hexdigest(),
        projectSourceCommit=subprocess.check_output(['git', '-C', str(ROOT), 'rev-parse', 'HEAD'], text=True).strip(),
        projectSourceDirty=bool(subprocess.check_output(['git', '-C', str(ROOT), 'status', '--porcelain', '--untracked-files=normal']).strip()),
        templateHashes={name: hashlib.sha256((TEMPLATES / name).read_bytes()).hexdigest() for name in ('References.json', 'ReferenceApp.swift', 'ReferenceUITests.swift', 'Localizable.xcstrings')},
        preparerSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        context='standaloneBaseWindow',
        scope='Five whole unchanged pure Swift files and the whole unchanged native SwiftUI declaration, with authored imports and an isolated base-window host. No original Feed chrome, accounts, transport, network fetch or production URL router.')
    (output / 'Provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
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
    app_product = obj('app-product', 'PBXFileReference', 'explicitFileType = wrapper.application; path = NativeMarkdownReference.app; sourceTree = BUILT_PRODUCTS_DIR;')
    test_product = obj('test-product', 'PBXFileReference', 'explicitFileType = wrapper.cfbundle; path = NativeMarkdownUITests.xctest; sourceTree = BUILT_PRODUCTS_DIR;')
    products = obj('products', 'PBXGroup', f'children = ({app_product}, {test_product},); name = Products; sourceTree = "<group>";')
    group = obj('main-group', 'PBXGroup', f'children = ({", ".join(references.values())}, {products},); sourceTree = "<group>";')
    frameworks = obj('app-frameworks', 'PBXFrameworksBuildPhase', f'buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0;')
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
    app_config = configurations('app', 'PRODUCT_NAME = "$(TARGET_NAME)"; PRODUCT_BUNDLE_IDENTIFIER = io.github.docmorphic.cmuxapp.reference.markdown; GENERATE_INFOPLIST_FILE = YES; INFOPLIST_KEY_UIApplicationSceneManifest_Generation = YES; INFOPLIST_KEY_UILaunchScreen_Generation = YES; INFOPLIST_KEY_UISupportedInterfaceOrientations = "UIInterfaceOrientationPortrait UIInterfaceOrientationLandscapeLeft UIInterfaceOrientationLandscapeRight"; TARGETED_DEVICE_FAMILY = "1,2"; SUPPORTED_PLATFORMS = "iphoneos iphonesimulator";')
    test_config = configurations('test', 'PRODUCT_NAME = "$(TARGET_NAME)"; PRODUCT_BUNDLE_IDENTIFIER = io.github.docmorphic.cmuxapp.reference.markdown.uitests; GENERATE_INFOPLIST_FILE = YES; TARGETED_DEVICE_FAMILY = "1,2"; SUPPORTED_PLATFORMS = "iphoneos iphonesimulator"; TEST_TARGET_NAME = NativeMarkdownReference;')
    proxy = obj('host-proxy', 'PBXContainerItemProxy', f'containerPortal = {uid("project")}; proxyType = 1; remoteGlobalIDString = {uid("app")}; remoteInfo = NativeMarkdownReference;')
    dependency = obj('host-dependency', 'PBXTargetDependency', f'target = {uid("app")}; targetProxy = {proxy};')
    app = obj('app', 'PBXNativeTarget', f'buildConfigurationList = {app_config}; buildPhases = ({app_sources}, {frameworks}, {app_resources},); buildRules = (); dependencies = (); packageProductDependencies = (); name = NativeMarkdownReference; productName = NativeMarkdownReference; productReference = {app_product}; productType = "com.apple.product-type.application";')
    test = obj('test', 'PBXNativeTarget', f'buildConfigurationList = {test_config}; buildPhases = ({test_sources}, {test_resources},); buildRules = (); dependencies = ({dependency},); name = NativeMarkdownUITests; productName = NativeMarkdownUITests; productReference = {test_product}; productType = "com.apple.product-type.bundle.ui-testing";')
    obj('project', 'PBXProject', f'attributes = {{ LastUpgradeCheck = 2600; TargetAttributes = {{ {test} = {{ TestTargetID = {app}; }}; }}; }}; buildConfigurationList = {project_config}; compatibilityVersion = "Xcode 15.0"; developmentRegion = en; hasScannedForEncodings = 0; knownRegions = (en, Base, ar, de, es, fr, ja, ko, "zh-Hans", "zh-Hant",); mainGroup = {group}; packageReferences = (); productRefGroup = {products}; projectDirPath = ""; projectRoot = ""; targets = ({app}, {test},);')
    project = output / 'NativeMarkdownReference.xcodeproj'; project.mkdir()
    (project / 'project.pbxproj').write_text('// !$*UTF8*$!\n{ archiveVersion = 1; classes = {}; objectVersion = 60; objects = {\n' + '\n'.join(objects) + f'\n}}; rootObject = {uid("project")}; }}\n')
    def buildable(name, file, identifier):
        return f'<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{identifier}" BuildableName="{file}" BlueprintName="{name}" ReferencedContainer="container:NativeMarkdownReference.xcodeproj"/>'
    host = buildable('NativeMarkdownReference', 'NativeMarkdownReference.app', app)
    tests = buildable('NativeMarkdownUITests', 'NativeMarkdownUITests.xctest', test)
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
    (schemes / 'NativeMarkdownReference.xcscheme').write_text(scheme)
    return provenance


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--upstream', required=True, type=Path); parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    print(json.dumps(prepare(args.upstream.resolve(), args.output.resolve()), indent=2))
