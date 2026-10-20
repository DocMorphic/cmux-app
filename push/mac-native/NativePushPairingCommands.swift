import SwiftUI

/// Keep the production action in SwiftUI's command model as its menus rebuild.
@MainActor
struct NativePushPairingCommands: Commands {
    let configured: Bool
    let show: () -> Void
    var body: some Commands {
        CommandGroup(after: .appSettings) {
            if configured {
                Button(String(localized: "androidPush.pair.title", defaultValue: "Pair Android Notifications"), action: show)
            }
        }
    }
}
