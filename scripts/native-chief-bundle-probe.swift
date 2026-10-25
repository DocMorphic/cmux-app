import Foundation
import CryptoKit

private func bundleDigest(_ url: URL) throws -> String {
    let file = try FileHandle(forReadingFrom: url)
    defer { try? file.close() }
    var hash = SHA256()
    while let chunk = try file.read(upToCount: 1_048_576), !chunk.isEmpty { hash.update(data: chunk) }
    return hash.finalize().map { String(format: "%02x", $0) }.joined()
}

/// This sidecar runs in the unmodified original Simulator app. OriginalL10n.swift
/// and the literal calls in CaptureCalls.swift are generated from pinned sources.
@_cdecl("cmuxChiefBundleCapture")
public func cmuxChiefBundleCapture() {
    do {
        let requested = ProcessInfo.processInfo.environment["CMUX_REFERENCE_BUNDLE_LOCALE"] ?? "missing"
        let missing = "__CMUX_REFERENCE_MISSING__"
        let main = Bundle.main
        let candidates = (FileManager.default.enumerator(at: main.bundleURL, includingPropertiesForKeys: nil)?.allObjects as? [URL] ?? [])
            .filter { $0.pathExtension == "bundle" && $0.lastPathComponent.contains("CmuxMobileShellUI") }
        let rootCandidates = candidates.filter { $0.deletingLastPathComponent().path == main.bundleURL.path }
        let selected = rootCandidates.count == 1 ? rootCandidates.first : (candidates.count == 1 ? candidates.first : nil)
        let module = selected.flatMap(Bundle.init(url:))
        let keys = sourceBundleKeys()
        let mainRaw = Dictionary(uniqueKeysWithValues: keys.map {
            ($0, main.localizedString(forKey: $0, value: missing, table: nil))
        })
        let moduleRaw = Dictionary(uniqueKeysWithValues: keys.map {
            ($0, module?.localizedString(forKey: $0, value: missing, table: nil) ?? missing)
        })
        let resourceFiles = (FileManager.default.enumerator(at: main.bundleURL, includingPropertiesForKeys: nil)?.allObjects as? [URL] ?? [])
            .filter { ["Localizable.strings", "Localizable.stringsdict"].contains($0.lastPathComponent) }
        var resourceDigests: [String: String] = [:]
        for file in resourceFiles { resourceDigests[String(file.path.dropFirst(main.bundleURL.path.count + 1))] = try bundleDigest(file) }
        let report: [String: Any] = [
            "schema": 1, "requested": requested,
            "bundleID": main.bundleIdentifier ?? "missing",
            "executableSha256": try bundleDigest(main.executableURL!),
            "infoSha256": try bundleDigest(main.bundleURL.appendingPathComponent("Info.plist")),
            "resourceSha256": resourceDigests,
            "mainBundle": main.bundleURL.path,
            "locale": Locale.current.identifier,
            "preferredLanguages": Locale.preferredLanguages,
            "mainPreferred": main.preferredLocalizations,
            "modulePreferred": module?.preferredLocalizations ?? [],
            "moduleCandidates": candidates.map(\.path),
            "selectedModule": selected?.path as Any? ?? NSNull(),
            "mainValues": sourceMainBundleValues(),
            "defaults": sourceBundleDefaults(),
            "mainRaw": mainRaw, "moduleRaw": moduleRaw,
            "os": ProcessInfo.processInfo.operatingSystemVersionString,
            "scope": "Actual original app Bundle.main and packaged module resources. A read-only injected probe calls unmodified source L10n; no sign-in, native Home/question presentation or backend static-property execution."
        ]
        let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let data = try JSONSerialization.data(withJSONObject: report, options: [.prettyPrinted, .sortedKeys])
        try data.write(to: directory.appendingPathComponent("cmux-chief-bundle-\(requested).json"), options: .atomic)
    } catch {
        FileHandle.standardError.write(Data("CMUX_BUNDLE_PROBE_FAILED \(error)\n".utf8))
    }
}
