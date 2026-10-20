#!/usr/bin/env python3
"""Prepare an isolated XCTest host around the exact pinned, unmodified conversation package."""
import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tarfile


def git(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args])


def prepare(upstream, output):
    root = Path(__file__).resolve().parents[1]
    manifest = json.loads((root / 'third_party/cmux-chief/manifest.json').read_text())
    revision = manifest['revision']; package = manifest['referencePackage']
    actual_tree = git(upstream, 'rev-parse', revision + ':' + package['path']).decode().strip()
    if actual_tree != package['tree']: raise ValueError('Unreviewed reference package tree')
    output.mkdir(parents=True, exist_ok=False)
    package_root = output / 'UpstreamPackage'; package_root.mkdir()
    archive = git(upstream, 'archive', revision, package['path'])
    count = 0
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        prefix = package['path'] + '/'
        for member in tar:
            if member.isdir(): continue
            if not member.isfile() or not member.name.startswith(prefix): raise ValueError('Unsupported package archive member')
            relative = PurePosixPath(member.name[len(prefix):])
            if relative.is_absolute() or '..' in relative.parts: raise ValueError('Unsafe package archive path')
            target = package_root / str(relative); target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(tar.extractfile(member).read()); count += 1
    (package_root / 'UPSTREAM_LICENSE').write_bytes(git(upstream, 'show', revision + ':LICENSE'))
    templates = root / 'tests/ios-chief-reference'
    for name in ('ReferenceApp.swift', 'ReferenceTests.swift', 'Scenes.json'):
        shutil.copyfile(templates / name, output / name)
    avatar = next(p for p in manifest['behaviorFiles'] if p.endswith('/ChiefAvatar.png'))
    pixels = git(upstream, 'show', revision + ':' + avatar)
    if hashlib.sha256(pixels).hexdigest() != manifest['behaviorFiles'][avatar]: raise ValueError('Unreviewed reference avatar')
    (output / 'ChiefAvatar.png').write_bytes(pixels)
    provenance = {'upstreamRevision': revision, 'packageTree': actual_tree, 'packageFiles': count,
                  'projectSourceCommit': git(root, 'rev-parse', 'HEAD').decode().strip(),
                  'projectSourceDirty': bool(git(root, 'status', '--porcelain', '--untracked-files=normal')),
                  'scenesSha256': hashlib.sha256((templates / 'Scenes.json').read_bytes()).hexdigest(),
                  'scope': 'Offline UIKit reference host; no account, pairing, network transport or production backend'}
    (output / 'Provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')

    def uid(name): return hashlib.sha256(name.encode()).hexdigest()[:24].upper()
    def q(value): return json.dumps(value)
    objects = []
    def obj(name, isa, body):
        objects.append(f'{uid(name)} = {{ isa = {isa}; {body} }};')
        return uid(name)
    refs = {}
    for filename, kind in [('ReferenceApp.swift', 'sourcecode.swift'), ('ReferenceTests.swift', 'sourcecode.swift'),
                           ('Scenes.json', 'text.json'), ('Provenance.json', 'text.json'), ('ChiefAvatar.png', 'image.png')]:
        refs[filename] = obj(filename, 'PBXFileReference', f'lastKnownFileType = {q(kind)}; path = {q(filename)}; sourceTree = "<group>";')
    app_product = obj('app-product', 'PBXFileReference', 'explicitFileType = wrapper.application; path = CmuxChiefReferenceHost.app; sourceTree = BUILT_PRODUCTS_DIR;')
    test_product = obj('test-product', 'PBXFileReference', 'explicitFileType = wrapper.cfbundle; path = CmuxChiefReferenceTests.xctest; sourceTree = BUILT_PRODUCTS_DIR;')
    products = obj('products', 'PBXGroup', f'children = ({app_product}, {test_product},); name = Products; sourceTree = "<group>";')
    group = obj('main-group', 'PBXGroup', f'children = ({", ".join(refs.values())}, {products},); sourceTree = "<group>";')
    package_ref = obj('package', 'XCLocalSwiftPackageReference', 'relativePath = UpstreamPackage;')
    frameworks = []
    package_products = []
    for product in ('CmuxConversationCore', 'CmuxConversationUI'):
        reference = obj(product, 'XCSwiftPackageProductDependency', f'package = {package_ref}; productName = {product};')
        package_products.append(reference)
        frameworks.append(obj(product + '-build', 'PBXBuildFile', f'productRef = {reference};'))
    source_builds = {}
    for name in ('ReferenceApp.swift', 'ReferenceTests.swift'):
        source_builds[name] = obj(name + '-build', 'PBXBuildFile', f'fileRef = {refs[name]};')
    resource_builds = [obj(name + '-build', 'PBXBuildFile', f'fileRef = {refs[name]};') for name in ('Scenes.json', 'Provenance.json', 'ChiefAvatar.png')]
    app_sources = obj('app-sources', 'PBXSourcesBuildPhase', f'buildActionMask = 2147483647; files = ({source_builds["ReferenceApp.swift"]},); runOnlyForDeploymentPostprocessing = 0;')
    test_sources = obj('test-sources', 'PBXSourcesBuildPhase', f'buildActionMask = 2147483647; files = ({source_builds["ReferenceTests.swift"]},); runOnlyForDeploymentPostprocessing = 0;')
    test_frameworks = obj('test-frameworks', 'PBXFrameworksBuildPhase', f'buildActionMask = 2147483647; files = ({", ".join(frameworks)},); runOnlyForDeploymentPostprocessing = 0;')
    resources = obj('test-resources', 'PBXResourcesBuildPhase', f'buildActionMask = 2147483647; files = ({", ".join(resource_builds)},); runOnlyForDeploymentPostprocessing = 0;')
    # Match SwiftPM's Debug products to the selected simulator architecture.
    # Building a second host-test slice cannot import an active-architecture-only package module.
    common = 'SDKROOT = iphoneos; IPHONEOS_DEPLOYMENT_TARGET = 17.0; SWIFT_VERSION = 6.0; CLANG_ENABLE_MODULES = YES; CODE_SIGNING_ALLOWED = NO; ONLY_ACTIVE_ARCH = YES;'
    def configurations(name, settings):
        values = []
        for configuration in ('Debug', 'Release'):
            values.append(obj(name + configuration, 'XCBuildConfiguration', f'buildSettings = {{ {settings} }}; name = {configuration};'))
        return obj(name + '-configurations', 'XCConfigurationList', f'buildConfigurations = ({", ".join(values)},); defaultConfigurationIsVisible = 0; defaultConfigurationName = Debug;')
    project_configs = configurations('project', common)
    app_configs = configurations('app', 'PRODUCT_NAME = "$(TARGET_NAME)"; PRODUCT_BUNDLE_IDENTIFIER = io.github.docmorphic.cmuxapp.reference.ios; GENERATE_INFOPLIST_FILE = YES; INFOPLIST_KEY_UIApplicationSceneManifest_Generation = YES; INFOPLIST_KEY_UILaunchScreen_Generation = YES; TARGETED_DEVICE_FAMILY = 1; SUPPORTED_PLATFORMS = "iphoneos iphonesimulator";')
    test_configs = configurations('test', 'PRODUCT_NAME = "$(TARGET_NAME)"; PRODUCT_BUNDLE_IDENTIFIER = io.github.docmorphic.cmuxapp.reference.ios.tests; GENERATE_INFOPLIST_FILE = YES; TARGETED_DEVICE_FAMILY = 1; SUPPORTED_PLATFORMS = "iphoneos iphonesimulator"; TEST_HOST = "$(BUILT_PRODUCTS_DIR)/CmuxChiefReferenceHost.app/CmuxChiefReferenceHost"; BUNDLE_LOADER = "$(TEST_HOST)"; LD_RUNPATH_SEARCH_PATHS = ("$(inherited)", "@executable_path/Frameworks", "@loader_path/Frameworks",);')
    proxy = obj('host-proxy', 'PBXContainerItemProxy', f'containerPortal = {uid("project")}; proxyType = 1; remoteGlobalIDString = {uid("app")}; remoteInfo = CmuxChiefReferenceHost;')
    dependency = obj('host-dependency', 'PBXTargetDependency', f'target = {uid("app")}; targetProxy = {proxy};')
    app = obj('app', 'PBXNativeTarget', f'buildConfigurationList = {app_configs}; buildPhases = ({app_sources},); buildRules = (); dependencies = (); name = CmuxChiefReferenceHost; productName = CmuxChiefReferenceHost; productReference = {app_product}; productType = "com.apple.product-type.application";')
    test = obj('test', 'PBXNativeTarget', f'buildConfigurationList = {test_configs}; buildPhases = ({test_sources}, {test_frameworks}, {resources},); buildRules = (); dependencies = ({dependency},); name = CmuxChiefReferenceTests; packageProductDependencies = ({", ".join(package_products)},); productName = CmuxChiefReferenceTests; productReference = {test_product}; productType = "com.apple.product-type.bundle.unit-test";')
    obj('project', 'PBXProject', f'attributes = {{ LastUpgradeCheck = 2600; }}; buildConfigurationList = {project_configs}; compatibilityVersion = "Xcode 15.0"; developmentRegion = en; hasScannedForEncodings = 0; knownRegions = (en, Base,); mainGroup = {group}; packageReferences = ({package_ref},); productRefGroup = {products}; projectDirPath = ""; projectRoot = ""; targets = ({app}, {test},);')
    project = output / 'CmuxChiefReference.xcodeproj'; project.mkdir()
    (project / 'project.pbxproj').write_text('// !$*UTF8*$!\n{ archiveVersion = 1; classes = {}; objectVersion = 60; objects = {\n' + '\n'.join(objects) + f'\n}}; rootObject = {uid("project")}; }}\n')
    def buildable(target, product, identifier):
        return f'<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{identifier}" BuildableName="{product}" BlueprintName="{target}" ReferencedContainer="container:CmuxChiefReference.xcodeproj"/>'
    host = buildable('CmuxChiefReferenceHost', 'CmuxChiefReferenceHost.app', app)
    tests = buildable('CmuxChiefReferenceTests', 'CmuxChiefReferenceTests.xctest', test)
    scheme = f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="2600" version="1.3">
 <BuildAction parallelizeBuildables="NO" buildImplicitDependencies="YES"><BuildActionEntries>
  <BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="NO" buildForArchiving="NO" buildForAnalyzing="YES">{host}</BuildActionEntry>
  <BuildActionEntry buildForTesting="YES" buildForRunning="NO" buildForProfiling="NO" buildForArchiving="NO" buildForAnalyzing="YES">{tests}</BuildActionEntry>
 </BuildActionEntries></BuildAction>
 <TestAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv="YES"><MacroExpansion>{host}</MacroExpansion><Testables><TestableReference skipped="NO" parallelizable="NO">{tests}</TestableReference></Testables></TestAction>
 <LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugDocumentVersioning="YES"><BuildableProductRunnable runnableDebuggingMode="0">{host}</BuildableProductRunnable></LaunchAction>
 <AnalyzeAction buildConfiguration="Debug"/>
</Scheme>
'''
    schemes = project / 'xcshareddata/xcschemes'; schemes.mkdir(parents=True)
    (schemes / 'CmuxChiefReference.xcscheme').write_text(scheme)
    print(json.dumps({'output': str(output), **provenance}, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--upstream', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    prepare(args.upstream.resolve(), args.output.resolve())
