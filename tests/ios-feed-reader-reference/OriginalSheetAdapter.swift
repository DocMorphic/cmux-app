#if os(iOS)
import CmuxMobileShellModel
import SwiftUI

/// Authored entry point only: original item/view/renderer declarations stay whole.
@MainActor public struct ReferenceOriginalFullText: View {
    private let load: @MainActor () async throws -> String
    public init(load: @escaping @MainActor () async throws -> String) { self.load = load }
    public var body: some View {
        AgentFeedFullTextView(item: MobileAgentFeedItem(macDeviceID: "reference-mac", macDisplayName: "Offline reference",
            itemID: "reference-item", workstreamID: "reference-turn", source: "claude", kind: .stop, status: .telemetry,
            createdAt: Date(timeIntervalSince1970: 198), updatedAt: Date(timeIntervalSince1970: 198),
            fullTextTruncated: true, connectionStatus: .connected), load: { _ in try await load() })
    }
    public static var localizations: [String] { Bundle.module.preferredLocalizations }
}
#endif
