import AppKit
import SwiftUI

@MainActor
final class NativePushPairingClipboard {
    private let board: NSPasteboard
    private var changeCount: Int?
    init(board: NSPasteboard = .general) { self.board = board }
    func copy(_ data: Data) {
        guard let text = String(data: data, encoding: .utf8) else { return }
        board.clearContents(); board.setString(text, forType: .string); changeCount = board.changeCount
    }
    func clear() {
        if let count = changeCount, board.changeCount == count { board.clearContents() }
        changeCount = nil
    }
}

/// AppDelegate owns this one auxiliary window and its menu target. The existing
/// app Scene API is outside the patch boundary; only window lifecycle uses AppKit.
@MainActor
final class NativePushPairingPresentation: NSObject, NSWindowDelegate {
    private let request: NativePushPairingModel.Request
    private lazy var model = NativePushPairingModel(request: request, onClear: { [weak self] in self?.clipboard.clear() })
    private var window: NSWindow?
    private var item: NSMenuItem?
    private let clipboard = NativePushPairingClipboard()

    init(request: @escaping NativePushPairingModel.Request) {
        self.request = request
        super.init()
        // SwiftUI can replace the app menu after the auth composition is made.
        // Do not depend on that replacement retaining AppDelegate as its delegate.
        NotificationCenter.default.addObserver(self, selector: #selector(menuWillTrack(_:)),
            name: NSMenu.didBeginTrackingNotification, object: nil)
    }
    deinit { NotificationCenter.default.removeObserver(self) }
    @objc private func menuWillTrack(_ notification: Notification) { installMenu() }
    func installMenu() {
        guard let menu = NSApp.mainMenu?.items.first?.submenu, item?.menu !== menu else { return }
        let item = NSMenuItem(title: String(localized: "androidPush.pair.title", defaultValue: "Pair Android Notifications"),
            action: #selector(show), keyEquivalent: "")
        item.target = self
        let index = menu.items.firstIndex { $0.action == #selector(NSApplication.terminate(_:)) } ?? menu.items.count
        menu.insertItem(.separator(), at: index); menu.insertItem(item, at: index); self.item = item
    }
    @objc func show() {
        if let window { window.makeKeyAndOrderFront(nil); return }
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 588, height: 660),
            styleMask: [.titled, .closable], backing: .buffered, defer: false)
        window.title = String(localized: "androidPush.pair.title", defaultValue: "Pair Android Notifications")
        window.isReleasedWhenClosed = false; window.sharingType = .none; window.delegate = self
        window.contentView = NSHostingView(rootView: NativePushPairingView(model: model,
            close: { [weak self] in self?.window?.close() }, copy: { [weak self] in self?.copyOffer() }))
        self.window = window; window.center(); window.makeKeyAndOrderFront(nil)
    }
    func invalidateOffer() { model.clear() }
    func windowWillClose(_ notification: Notification) {
        model.clear(); window?.contentView = nil; window = nil
    }
    private func copyOffer() {
        model.expire()
        guard let offer = model.offer else { return }
        clipboard.copy(offer.body)
    }
}
