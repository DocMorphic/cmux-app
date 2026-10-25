#!/usr/bin/env python3
"""Execute the complete pinned token-phase extension with controlled local seams.

This is an upstream behavior audit, not an Android equivalence oracle. The SDK
storage and presentation effects are explicit seams; no real account is used.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

PIN = "a5911a1f92f3eeb11c4d7f3e2ef89204718c5009"
BASE = "Packages/Shared/CmuxAuthRuntime/Sources/CmuxAuthRuntime/Coordinator/"
NAMES = ["AuthCoordinator+TokenTouchingPhaseTimeout.swift", "AuthTokenDeadline.swift",
         "AuthPhase.swift", "AuthPhaseTimedOutState.swift", "AuthTrackedTokenWork.swift",
         "AuthPhaseTimeout.swift"]

WRAPPER = r'''
import Foundation
enum AuthError: Error { case timedOut }
enum OperationError: Error { case failed }
struct Log { func log(_ text: String) {} }
final class DeadlineClock: @unchecked Sendable {
    func authTokenDeadline(after: Duration) -> AuthTokenDeadline {
        AuthTokenDeadline(hasExpired: {false}, remaining: {.seconds(60)}, wait: {
            try await Task.sleep(for: .seconds(60))
        })
    }
}
@MainActor final class Client {
    var refresh: String? = "stale"
    var reads = 0, clears = 0, appliedClears = 0
    var onRead: ((Int) async -> Void)?
    var onClear: (() async -> Void)?
    func refreshToken() async -> String? {
        reads += 1
        let value = refresh
        await onRead?(reads)
        return value
    }
    func clearLocalSession(ifRefreshTokenMatches expected: String) async {
        clears += 1
        await onClear?()
        if refresh == expected {refresh = nil; appliedClears += 1}
    }
}
@MainActor final class Coordinator {
    let clock = DeadlineClock(), log = Log(), client = Client()
    var sessionGeneration: UInt64 = 3, signOutEpoch: UInt64 = 2, tokenStoreWriteHighWater: UInt64 = 5
    var latestSignInRefreshToken: String?, isAuthenticated = false
    var isCapturingSignOutCredentials = false
    var signOutCredentialCaptureWaiters: [CheckedContinuation<Void, Never>] = []
    var activeTokenTouchingPhases: [UUID: AuthTrackedTokenWork] = [:]
    var timedOutTokenTouchingPhaseStates: [AuthPhase: AuthPhaseTimedOutState] = [:]
    let tokenTouchingTimedOutResetNanoseconds: UInt64 = 30_000_000_000
    var presentationClears = 0, preservedPendingCode = false
    func clearAuthState(preservePendingCode: Bool) {
        // A presentation seam: records the original method's invocation only.
        presentationClears += 1; preservedPendingCode = preservePendingCode
        isAuthenticated = false; sessionGeneration += 1
    }
}
@MainActor final class Gate {
    var entered = false, open = false
    var waiter: CheckedContinuation<Void, Never>?
    func park() async {
        entered = true
        if !open {await withCheckedContinuation {waiter = $0}}
    }
    func release() {open = true; waiter?.resume(); waiter = nil}
}
@MainActor func outcome(_ operation: () async throws -> Void) async -> String {
    do {try await operation(); return "success"}
    catch is CancellationError {return "cancelled"}
    catch AuthError.timedOut {return "timedOut"}
    catch is OperationError {return "operationFailure"}
    catch {return "unexpected:\(error)"}
}
@main struct Main {
    @MainActor static func main() async throws {
        var rows: [[String: Any]] = []
        for name in ["unchanged", "newer-write", "empty", "latest-sign-in", "stale-same-generation",
                     "stale-signed-out", "stale-authenticated", "replacement-at-clear",
                     "newer-write-during-read", "high-water-without-replacement", "replacement-after-clear",
                     "capture", "cancel-during-capture", "newer-write-during-capture"] {
            let c = Coordinator(); c.sessionGeneration = 4
            if name == "unchanged" {c.signOutEpoch = 1; c.tokenStoreWriteHighWater = 6; c.isAuthenticated = true}
            if name == "newer-write" {c.tokenStoreWriteHighWater = 6}
            if name == "empty" {c.client.refresh = nil; c.isAuthenticated = true}
            if name == "latest-sign-in" {c.latestSignInRefreshToken = "stale"; c.isAuthenticated = true}
            if name == "stale-same-generation" {c.sessionGeneration = 3; c.isAuthenticated = true}
            if name == "stale-authenticated" {c.isAuthenticated = true}
            if name == "replacement-at-clear" {
                c.client.onClear = {c.client.refresh = "replacement"; c.tokenStoreWriteHighWater = 6}
            }
            if name == "newer-write-during-read" || name == "high-water-without-replacement" {
                c.isAuthenticated = true
                c.client.onRead = {read in
                    if read == 1 {
                        c.tokenStoreWriteHighWater = 6
                        if name == "newer-write-during-read" {c.client.refresh = "replacement"}
                    }
                }
            }
            if name == "replacement-after-clear" {
                // Change storage after the second read captured nil. This
                // deliberately exposes the original's non-atomic raw-read seam.
                c.client.onRead = {read in if read == 2 {c.client.refresh = "replacement"}}
                c.isAuthenticated = true
            }
            let captured = name.contains("capture")
            c.isCapturingSignOutCredentials = captured
            let task = Task {await outcome {try await c.auditFinish()}}
            if captured {
                while c.signOutCredentialCaptureWaiters.isEmpty {await Task.yield()}
                precondition(c.client.reads == 0)
                if name == "cancel-during-capture" {task.cancel(); await Task.yield()}
                if name == "newer-write-during-capture" {c.tokenStoreWriteHighWater = 6}
                precondition(c.signOutCredentialCaptureWaiters.count == 1)
                c.finishSignOutCredentialCapture()
                c.finishSignOutCredentialCapture() // Original release is idempotent.
            }
            let result = await task.value
            let expectedClears = ["stale-same-generation", "stale-signed-out", "stale-authenticated",
                                  "replacement-at-clear", "newer-write-during-read", "high-water-without-replacement",
                                  "replacement-after-clear", "capture", "cancel-during-capture"].contains(name) ? 1 : 0
            precondition(result == (name == "unchanged" ? "success" : "cancelled"))
            precondition(c.client.clears == expectedClears)
            let expectedPresentation = ["stale-authenticated", "high-water-without-replacement", "replacement-after-clear"].contains(name) ? 1 : 0
            precondition(c.presentationClears == expectedPresentation)
            precondition(c.presentationClears == 0 || c.preservedPendingCode)
            let noRead = ["unchanged", "newer-write", "newer-write-during-capture"].contains(name)
            precondition(c.client.reads == (noRead ? 0 : expectedClears == 1 ? 2 : 1))
            let compareLost = ["replacement-at-clear", "newer-write-during-read"].contains(name)
            precondition(c.client.appliedClears == (compareLost ? 0 : expectedClears))
            if ["replacement-at-clear", "newer-write-during-read", "replacement-after-clear"].contains(name) {precondition(c.client.refresh == "replacement")}
            precondition(c.signOutCredentialCaptureWaiters.isEmpty && !c.isCapturingSignOutCredentials)
            rows.append(["case": name, "result": result, "taskCancelled": task.isCancelled,
                         "reads": c.client.reads, "compareClears": c.client.clears, "appliedClears": c.client.appliedClears,
                         "presentationClears": c.presentationClears, "preservedPendingCode": c.preservedPendingCode,
                         "refresh": c.client.refresh as Any? ?? NSNull(), "highWater": c.tokenStoreWriteHighWater])
        }
        // Exercise the entire unchanged extension as well as its private finish:
        // result preservation when current; retirement supersedes either result;
        // actual phase ownership survives a canceled, capture-parked operation.
        for retired in [false, true] {for fails in [false, true] {for cancel in [false, true] {
            let c = Coordinator(), gate = Gate()
            c.signOutEpoch = 1
            let task = Task {await outcome {
                _ = try await c.runTokenTouchingPhase(.accessToken, timeout: .seconds(60)) {
                    await gate.park()
                    if fails {throw OperationError.failed}
                    return "access"
                }
            }}
            while !gate.entered {await Task.yield()}
            let actual = Array(c.activeTokenTouchingPhases.values)
            precondition(actual.count == 1)
            if retired {c.signOutEpoch = 2; c.sessionGeneration = 4; c.isCapturingSignOutCredentials = true}
            if cancel {actual.forEach {$0.cancel()}}
            gate.release()
            if retired {
                while c.signOutCredentialCaptureWaiters.isEmpty {await Task.yield()}
                precondition(c.activeTokenTouchingPhases.count == 1 && c.client.reads == 0)
                c.finishSignOutCredentialCapture()
            }
            let result = await task.value
            for tracked in actual {await tracked.completion.value}
            precondition(c.activeTokenTouchingPhases.isEmpty)
            precondition(result == (retired || cancel ? "cancelled" : fails ? "operationFailure" : "success"))
            precondition(c.client.appliedClears == (retired ? 1 : 0))
            rows.append(["case": "whole-phase", "retired": retired, "operationFails": fails, "cancelActual": cancel,
                         "result": result, "reads": c.client.reads, "appliedClears": c.client.appliedClears,
                         "actualRetired": c.activeTokenTouchingPhases.isEmpty])
        }}}
        FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject: rows, options: [.sortedKeys]))
    }
}
'''

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    if args.out.exists():
        parser.error("Use a fresh output path; audit receipts are immutable")
    sources = {name: subprocess.check_output(
        ["git", "-C", str(args.upstream), "show", PIN + ":" + BASE + name]).decode() for name in NAMES}
    # Only the enclosing type is renamed. All original extension methods,
    # including finish, wait, timeout/cancel handling and cleanup, are unchanged.
    extension = sources[NAMES[0]].replace("extension AuthCoordinator", "extension Coordinator")
    extension += """\n@MainActor extension Coordinator {
        func auditFinish() async throws {
            try await finishTokenTouchingPhase(generation: 3, signOutEpoch: 1, storeWriteHighWater: 5)
        }
    }\n"""
    with tempfile.TemporaryDirectory(prefix="cmux-original-token-finish-") as folder:
        root = Path(folder)
        units = {"Main.swift": WRAPPER, "TokenPhase.swift": extension,
                 "Race.swift": sources["AuthPhaseTimeout.swift"].split("/// Race a prompt-only operation")[0]}
        units.update({name: sources[name] for name in NAMES[1:-1]})
        paths = []
        for name, content in units.items():
            path = root / name
            path.write_text(content)
            paths.append(str(path))
        exe = root / "audit"
        subprocess.run(["swiftc", "-parse-as-library", *paths, "-o", str(exe)], check=True, timeout=90)
        rows = json.loads(subprocess.check_output([str(exe)], timeout=30))
    assert len(rows) == 22
    result = dict(upstream=PIN, generatorSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                  compiler=subprocess.check_output(["swiftc", "--version"], text=True).strip(),
                  generatedUnitsSha256={name: hashlib.sha256(src.encode()).hexdigest() for name, src in units.items()},
                  wholeOriginalFiles={BASE + name: hashlib.sha256(src.encode()).hexdigest()
                  for name, src in sources.items()}, cases=rows,
                  scope="Complete unchanged original token-touching extension and original supporting deadline/phase/tracked/race types. Only enclosing coordinator type renamed; private finish invoked by same-file trampoline. SDK raw reads/conditional clears, presentation effects and deadline clock are controlled local seams. Fourteen finish paths and eight real Task/whole-phase paths assert cancellation, high-water, capture waits, compare-clear and actual ownership. The replacement-after-clear seam intentionally changes storage after the return value of the second raw read is captured: the original can request presentation clear based on that nil read even though a subsequent replacement exists. This is source behavior evidence, not proof that SDK production permits every artificial interleaving, Android equivalence, iOS scheduling/Keychain/UI or real-device parity.")
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({"cases": len(rows), "out": str(args.out)}, indent=2))

if __name__ == "__main__":
    main()
