#!/usr/bin/env python3
"""Execute pinned push source in a local Swift executable, without the SDK.

The production push actor, SQLite store and HTTP policy are unchanged.
Auth provider replies and HTTP use the original test seams. Coordinator/SDK
composition and auth diagnostics are explicit seams, not iOS runtime evidence.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

PIN = "a5911a1f92f3eeb11c4d7f3e2ef89204718c5009"
AUTH = "Packages/Shared/CmuxAuthRuntime/"
CORE = "Packages/Shared/CMUXMobileCore/Sources/CMUXMobileCore/"
FEATURE = "ios/cmuxPackage/Sources/cmuxFeature/"
PRODUCTION = ["Push/PushRegistrationService.swift", "Push/PushRegistrationSnapshot.swift", "Push/PushRegistering.swift",
              "Push/PendingUnregisterStore.swift", "Push/RedirectMethodPreservingDelegate.swift",
              "Coordinator/AuthPhase.swift", "Coordinator/AuthPhaseTimeout.swift", "Coordinator/AuthPhaseTimeoutRegistry.swift",
              "Coordinator/AuthPhaseTimedOutState.swift", "Coordinator/AuthTokenDeadline.swift", "Coordinator/AuthTrackedTokenWork.swift"]
SUPPORT = ["PushRegistrationURLProtocol.swift",
         "ManualTestClock.swift", "CancellationIgnoringPushTokenProvider.swift", "TestPhaseSignal.swift", "TestContinuationBlocker.swift"]
MANIFEST = '''// swift-tools-version: 6.0
import PackageDescription
let package = Package(name: "UpstreamPushAudit", platforms: [.macOS(.v14)], targets: [
    .target(name: "CMUXMobileCore", swiftSettings: [.swiftLanguageMode(.v6)]),
    .executableTarget(name: "CmuxAuthRuntime", dependencies: ["CMUXMobileCore"], swiftSettings: [
        .swiftLanguageMode(.v6), .enableUpcomingFeature("ExistentialAny"), .enableUpcomingFeature("InternalImportsByDefault")])
])
'''
SEAMS = '''import Foundation
public enum AuthError: Error, Equatable {case timedOut, unauthorized}
struct AuthDebugLog: Sendable {func log(_ message: String) {}}
@MainActor final class AuditCoordinator {
    let clock: any Clock<Duration>
    let log = AuthDebugLog()
    var activePostSignInHooks: [UUID: AuthTrackedTokenWork] = [:]
    init(clock: any Clock<Duration>) {self.clock = clock}
}
'''
MAIN = r'''
import Foundation

@MainActor struct HookOwnershipAuditTests {
    func timedOutHookKeepsActualOwnershipThroughTheSignOutJoin() async {
        let clock = ManualTestClock(), owner = AuditCoordinator(clock: clock)
        let entered = TestPhaseSignal(), release = TestContinuationBlocker(), canceled = TestPhaseSignal()
        let waiting = Task {await owner.runPostSignInHook(timeout: .seconds(5)) {
            await withTaskCancellationHandler {
                await entered.markStarted(); await release.wait()
            } onCancel: {Task {await canceled.markStarted()}}
        }}
        await entered.waitUntilStarted(); await clock.waitUntilSleepers(); clock.advance(by: .seconds(5))
        await waiting.value; await canceled.waitUntilStarted()
        expect(owner.activePostSignInHooks.count == 1)
        let actual = Array(owner.activePostSignInHooks.values)
        owner.cancelPostSignInHooksForSignOut()
        let join = Task {await owner.waitForPostSignInHooksAfterSignOut(timeout: .seconds(5))}
        await clock.waitUntilSleepers(); expect(owner.activePostSignInHooks.count == 1)
        await release.release(); await join.value
        for work in actual {await work.completion.value}
        while !owner.activePostSignInHooks.isEmpty {await Task.yield()}
    }

    func boundedSignOutJoinDoesNotForgetItsStillRunningHook() async {
        let clock = ManualTestClock(), owner = AuditCoordinator(clock: clock)
        let entered = TestPhaseSignal(), release = TestContinuationBlocker()
        let waiting = Task {await owner.runPostSignInHook(timeout: .seconds(5)) {
            await entered.markStarted(); await release.wait()
        }}
        await entered.waitUntilStarted(); await clock.waitUntilSleepers(); clock.advance(by: .seconds(5)); await waiting.value
        let actual = Array(owner.activePostSignInHooks.values); expect(actual.count == 1)
        let join = Task {await owner.waitForPostSignInHooksAfterSignOut(timeout: .seconds(2))}
        await clock.waitUntilSleepers(); clock.advance(by: .seconds(2)); await join.value
        expect(owner.activePostSignInHooks.count == 1)
        await release.release(); for work in actual {await work.completion.value}
        while !owner.activePostSignInHooks.isEmpty {await Task.yield()}
    }

    func deferredRegistrationStaysOwnedAndRepairsItsLatePostWithCapturedCredentials() async throws {
        let clock = ManualTestClock(), owner = AuditCoordinator(clock: clock), hook = DeferredSignInHook()
        let entered = TestPhaseSignal(), release = TestContinuationBlocker()
        let provider = MutablePushTokenProvider(accountID: "old-account", accessToken: "old-access", refreshToken: "old-refresh")
        let suite = "push-hook-audit-" + UUID().uuidString
        let defaults = UserDefaults(suiteName: suite)!
        defaults.set(true, forKey: "cmux.notifications.pushEnabled")
        defaults.set("aa", forKey: "cmux.notifications.deviceTokenHex")
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(suite)
        defer {defaults.removePersistentDomain(forName: suite); try? FileManager.default.removeItem(at: folder)}
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [PushRegistrationURLProtocol.self]
        let session = URLSession(configuration: configuration)
        defer {session.invalidateAndCancel()}
        let service = PushRegistrationService(tokenProvider: provider, apiBaseURL: "https://example.test", bundleID: "fixture.hook",
            apnsEnvironment: "sandbox", suiteName: suite, pendingUnregisterStoreURL: folder.appendingPathComponent("cleanup.sqlite3"),
            session: session, retryDelays: [])
        await PushRegistrationURLProtocol.script.reset([
            .gatedResponse(200, started: entered, blocker: release), .response(200)
        ])
        hook.set {await service.syncTokenIfPossible()}
        let waiting = Task {await owner.runPostSignInHook(timeout: .seconds(5)) {await hook.run()}}
        await entered.waitUntilStarted(); await clock.waitUntilSleepers(); clock.advance(by: .seconds(5)); await waiting.value
        expect(owner.activePostSignInHooks.count == 1)
        let actual = Array(owner.activePostSignInHooks.values)
        await provider.clearSession(); owner.cancelPostSignInHooksForSignOut()
        let join = Task {await owner.waitForPostSignInHooksAfterSignOut(timeout: .seconds(5))}
        await clock.waitUntilSleepers(); await release.release(); await join.value
        for work in actual {await work.completion.value}
        while !owner.activePostSignInHooks.isEmpty {await Task.yield()}
        let requests = await PushRegistrationURLProtocol.script.requests
        expect(requests.map { $0.httpMethod } == ["POST", "DELETE"])
        expect(requests.allSatisfy {$0.value(forHTTPHeaderField: "Authorization") == "Bearer old-access"})
        expect(requests.allSatisfy {$0.value(forHTTPHeaderField: "X-Stack-Refresh-Token") == "old-refresh"})
    }
}

@MainActor func expect(_ condition: Bool) {precondition(condition, "Source audit assertion failed")}
@main struct Main {
    @MainActor static func main() async throws {
        var rows: [[String: Any]] = []
        let harness = HookOwnershipAuditTests()
        await harness.timedOutHookKeepsActualOwnershipThroughTheSignOutJoin()
        rows.append(["case": "owned-timeout-signout-join", "passed": true])
        await harness.boundedSignOutJoinDoesNotForgetItsStillRunningHook()
        rows.append(["case": "bounded-join-retains-actual-hook", "passed": true])
        try await harness.deferredRegistrationStaysOwnedAndRepairsItsLatePostWithCapturedCredentials()
        rows.append(["case": "deferred-late-post-signout", "passed": true, "methods": ["POST", "DELETE"], "capturedOwnerUsed": true])
        for name in ["rotation-readiness-before-cleanup", "late-account-switch"] {
            let suite = "push-source-audit-" + UUID().uuidString
            let defaults = UserDefaults(suiteName: suite)!
            let folder = FileManager.default.temporaryDirectory.appendingPathComponent(suite)
            defer {defaults.removePersistentDomain(forName: suite); try? FileManager.default.removeItem(at: folder)}
            defaults.set(true, forKey: "cmux.notifications.pushEnabled")
            defaults.set("aa", forKey: "cmux.notifications.deviceTokenHex")
            defaults.set("account-a", forKey: "cmux.notifications.registeredAccountID")
            let configuration = URLSessionConfiguration.ephemeral
            configuration.protocolClasses = [PushRegistrationURLProtocol.self]
            let session = URLSession(configuration: configuration)
            defer {session.invalidateAndCancel()}
            let provider = MutablePushTokenProvider(accountID: "account-a", accessToken: "access-a", refreshToken: "refresh-a")
            let store = folder.appendingPathComponent("cleanup.sqlite3")
            let service = PushRegistrationService(tokenProvider: provider, apiBaseURL: "https://example.test", bundleID: "fixture.audit",
                apnsEnvironment: "sandbox", suiteName: suite, pendingUnregisterStoreURL: store, session: session, retryDelays: [])
            if name == "rotation-readiness-before-cleanup" {
                await PushRegistrationURLProtocol.script.reset([.response(200), .failure(.notConnectedToInternet)])
                await service.register(deviceToken: Data([0xbb]))
                let first = await PushRegistrationURLProtocol.script.requests
                expect(first.map(\.httpMethod) == ["POST", "DELETE"])
                expect(await service.snapshot.backendState == .registered)
                let pending = try PendingUnregisterStore(databaseURL: store).batch(accountID: "account-a", limit: 100)
                expect(pending == [PendingUnregister(tokenHex: "aa", accountID: "account-a")])
                await PushRegistrationURLProtocol.script.reset([.response(200), .response(200)])
                await service.syncTokenIfPossible()
                let requests = await PushRegistrationURLProtocol.script.requests
                let bodies = await PushRegistrationURLProtocol.script.requestBodies.map {data -> String in
                    guard let data, let body = try? JSONSerialization.jsonObject(with: data) as? [String: String] else {return "invalid"}
                    return body["deviceToken"] ?? "missing"
                }
                expect(requests.map(\.httpMethod) == ["POST", "DELETE"]); expect(bodies == ["bb", "aa"])
                expect(try PendingUnregisterStore(databaseURL: store).batch(accountID: "account-a", limit: 100).isEmpty)
                rows.append(["case": name, "passed": true, "methods": ["POST", "DELETE"], "tokens": bodies, "readyDespiteCleanupFailure": true])
            } else {
                let entered = TestPhaseSignal(), release = TestContinuationBlocker()
                await PushRegistrationURLProtocol.script.reset([.gatedResponse(200, started: entered, blocker: release), .response(200), .response(200)])
                let work = Task {await service.syncTokenIfPossible()}
                await entered.waitUntilStarted()
                await provider.switchSession(accountID: "account-b", accessToken: "access-b", refreshToken: "refresh-b")
                await release.release(); await work.value
                let requests = await PushRegistrationURLProtocol.script.requests
                expect(requests.map(\.httpMethod) == ["POST", "DELETE", "POST"])
                expect(requests.map {$0.value(forHTTPHeaderField: "Authorization")} == ["Bearer access-a", "Bearer access-a", "Bearer access-b"])
                expect(defaults.string(forKey: "cmux.notifications.registeredAccountID") == "account-b")
                expect(await service.snapshot.backendState == .registered)
                rows.append(["case": name, "passed": true, "methods": ["POST", "DELETE", "POST"], "finalOwner": "account-b"])
            }
        }
        let result = try JSONSerialization.data(withJSONObject: rows, options: [.sortedKeys])
        print("AUDIT_RESULT:" + String(decoding: result, as: UTF8.self))
    }
}
'''

def declaration(source, signature):
    start = source.index(signature)
    end = source.index("{", start) + 1
    depth = 1
    while depth:
        if source[end] == "{": depth += 1
        if source[end] == "}": depth -= 1
        end += 1
    return source[start:end]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=False)
    sources = {}
    def original(path):
        text = subprocess.check_output(["git", "-C", str(args.upstream), "show", PIN + ":" + path]).decode()
        sources[path] = text
        return text
    units = {"Package.swift": MANIFEST, "Sources/CmuxAuthRuntime/Seams.swift": SEAMS,
             "Sources/CmuxAuthRuntime/AuditMain.swift": MAIN}
    for path in PRODUCTION:
        units["Sources/CmuxAuthRuntime/" + Path(path).name] = original(AUTH + "Sources/CmuxAuthRuntime/" + path)
    for name in ["CmxRetryAfterPolicy.swift", "CmxRetryAfterProviding.swift"]:
        units["Sources/CMUXMobileCore/" + name] = original(CORE + name)
    for name in SUPPORT:
        units["Sources/CmuxAuthRuntime/" + name] = original(AUTH + "Tests/CmuxAuthRuntimeTests/" + name).replace("@testable import CmuxAuthRuntime", "")
    units["Sources/CmuxAuthRuntime/ProviderFixtures.swift"] = original(AUTH + "Tests/CmuxAuthRuntimeTests/PushRegistrationServiceTests.swift").split("@Suite(.serialized)")[0].replace("import Testing", "").replace("@testable import CmuxAuthRuntime", "")
    units["Sources/CmuxAuthRuntime/TokenProviding.swift"] = original(AUTH + "Sources/CmuxAuthRuntime/Coordinator/TokenProviding.swift").split("extension AuthCoordinator:")[0]
    units["Sources/CmuxAuthRuntime/AuthenticatedSessionSnapshot.swift"] = "import Foundation\n" + declaration(
        original(AUTH + "Sources/CmuxAuthRuntime/Coordinator/AuthCoordinator+Tokens.swift"), "public struct AuthenticatedSessionSnapshot:")
    units["Sources/CmuxAuthRuntime/DeferredSignInHook.swift"] = original(FEATURE + "DeferredSignInHook.swift")
    units["Sources/CmuxAuthRuntime/PostSignIn.swift"] = original(AUTH + "Sources/CmuxAuthRuntime/Coordinator/AuthCoordinator+PostSignInTimeout.swift").replace(
        "extension AuthCoordinator", "extension AuditCoordinator")
    project = args.out / "project"
    for name, text in units.items():
        path = project / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_text(text)
    receipt = dict(upstream=PIN, generatorSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                   wholeOriginalFilesSha256={p: hashlib.sha256(s.encode()).hexdigest() for p, s in sources.items()},
                   generatedUnitsSha256={p: hashlib.sha256(s.encode()).hexdigest() for p, s in units.items()},
                   compiler=subprocess.check_output(["swiftc", "--version"], text=True).strip(),
                   scope="Original push actor, SQLite store, retry/HTTP policy, protocols and deadline registry compile unchanged in Swift6. Five Foundation executable cases assert original readiness ordering, late account repair and post-hook ownership; the original Swift Testing suite is not executed. TokenProviding protocol and snapshot are complete original declarations; AuthCoordinator conformance is omitted. Complete original post-sign-in extension only renames its enclosing type and executes with a clock/registry owner seam; original DeferredSignInHook links it to the actual push service. AuthError cases and auth diagnostics are inert seams. Original scripted URLProtocol/providers/clocks and fresh UUID test defaults/SQLite are used; self-module test imports are removed and provider declarations are extracted before the original test suite. The Foundation harness replaces the unavailable Testing framework, not production actor methods. This does not execute MobileAuthComposition, Stack SDK, UIKit/APNs/Firebase, Android workers or real-device parity.")
    (args.out / "inputs.json").write_text(json.dumps(receipt, indent=2) + "\n")
    command = ["swift", "run", "--package-path", str(project), "--scratch-path", str(args.out / "build"), "-j", "2", "CmuxAuthRuntime"]
    with (args.out / "swift-run.log").open("w") as log:
        result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=240)
    (args.out / "completion.json").write_text(json.dumps(dict(exitCode=result.returncode, command=command), indent=2) + "\n")
    print((args.out / "swift-run.log").read_text()[-4000:])
    if result.returncode: raise SystemExit(result.returncode)
    line = next(line for line in (args.out / "swift-run.log").read_text().splitlines() if line.startswith("AUDIT_RESULT:"))
    rows = json.loads(line.removeprefix("AUDIT_RESULT:"))
    assert len(rows) == 5 and all(row["passed"] for row in rows)
    (args.out / "cases.json").write_text(json.dumps(rows, indent=2) + "\n")

if __name__ == "__main__":
    main()
