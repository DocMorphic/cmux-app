import SwiftUI

/// Disposable test host. The XCTest bundle mounts the pinned upstream UIKit renderer.
@main
struct CmuxChiefReferenceHost: App {
    var body: some Scene { WindowGroup { Color.black } }
}
