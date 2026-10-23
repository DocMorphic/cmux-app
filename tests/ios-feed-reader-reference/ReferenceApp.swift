import CmuxMobileShellUI
import SwiftUI
import UIKit

private struct ReferenceInput: Decodable { let markdown: String; let scenes: [ReferenceScene] }
private struct ReferenceScene: Decodable {
    let id: String; let language: String; let style: String
    let category: String; let orientation: String; let device: String
}
private func resource(_ name: String) -> Data { try! Data(contentsOf: Bundle.main.url(forResource: name, withExtension: nil)!) }
@main struct FeedFullTextReferenceApp: App {
    var body: some Scene { WindowGroup { ReferenceRoot() } }
}
@MainActor private final class ReferenceState: ObservableObject {
    var started = false; var attempts = 0; var cancellations = 0; var dismissed = false
    var interceptedURLs: [String] = []
}
private enum ReferenceFailure: Error { case expectedOfflineFailure }
@MainActor private struct ReferenceRoot: View {
    @StateObject private var state = ReferenceState()
    @State private var presented = false
    private let input = try! JSONDecoder().decode(ReferenceInput.self, from: resource("References.json"))
    private var scene: ReferenceScene { input.scenes.first { $0.id == ProcessInfo.processInfo.environment["CMUX_FEED_READER_SCENE"] }! }
    private var loading: Bool { ProcessInfo.processInfo.environment["CMUX_FEED_READER_MODE"] == "loading" }
    var body: some View {
        Button("Open reference sheet") { presented = true }
            .accessibilityIdentifier("reference.feed.open")
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color(uiColor: .systemBackground))
            .background(ReferenceProbe(scene: scene, state: state, kind: "root", mode: loading ? "loading" : "retry").frame(width: 1, height: 1))
            .preferredColorScheme(scene.style == "dark" ? .dark : .light)
            .onAppear { if !state.started { state.started = true; presented = true } }
            .sheet(isPresented: $presented, onDismiss: { state.dismissed = true }) {
                ReferenceOriginalFullText(load: {
                    state.attempts += 1
                    if loading {
                        do { try await Task.sleep(for: .seconds(600)) }
                        catch { if Task.isCancelled { state.cancellations += 1 }; throw error }
                    }
                    if state.attempts == 1 { throw ReferenceFailure.expectedOfflineFailure }
                    return input.markdown
                })
                .environment(\.openURL, OpenURLAction { url in state.interceptedURLs.append(url.absoluteString); return .handled })
                .background(ReferenceProbe(scene: scene, state: state, kind: "sheet", mode: loading ? "loading" : "retry").frame(width: 1, height: 1))
            }
    }
}
/// Public UIKit/SwiftUI traits in the actual presented sheet. Compositor pixels
/// are captured only by the independent XCUITest process, never by this probe.
@MainActor private struct ReferenceProbe: UIViewRepresentable {
    let scene: ReferenceScene; let state: ReferenceState; let kind: String; let mode: String
    @Environment(\.layoutDirection) private var direction
    @Environment(\.dynamicTypeSize) private var size
    func makeCoordinator() -> Observer { Observer() }
    func makeUIView(context: Context) -> UILabel {
        let view = UILabel(); view.backgroundColor = .clear; view.alpha = 0.02
        view.isAccessibilityElement = true; view.isUserInteractionEnabled = false
        view.accessibilityIdentifier = "reference.feed." + kind; view.accessibilityTraits = .staticText
        context.coordinator.start(view); return view
    }
    func updateUIView(_ view: UILabel, context: Context) {
        context.coordinator.record = { [scene, state, kind, mode, direction, size] label in
            guard let window = label.window else { return }
            let traits = label.traitCollection
            func rgba(_ color: UIColor) -> [CGFloat] {
                var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
                color.resolvedColor(with: traits).getRed(&r, green: &g, blue: &b, alpha: &a); return [r,g,b,a]
            }
            let category = UIApplication.shared.preferredContentSizeCategory
            let matches = category == (scene.category == "large" ? .large : .accessibilityMedium)
            // Observe public UIKit labels in the unchanged original navigation
            // bar. AX bounds alone cannot establish the actual toolbar font.
            var navigationLabels: [[String: Any]] = []
            var navigationBars: [[CGFloat]] = []
            func observe(_ view: UIView, inNavigationBar: Bool = false) {
                guard !view.isHidden, view.alpha > 0, view.window === window else { return }
                let navigation = inNavigationBar || view is UINavigationBar
                let frame = view.convert(view.bounds, to: window)
                if view is UINavigationBar { navigationBars.append([frame.minX, frame.minY, frame.width, frame.height]) }
                if navigation, let text = view as? UILabel, let content = text.text, !content.isEmpty,
                   frame.width > 0, frame.height > 0, frame.intersects(window.bounds) {
                    navigationLabels.append(["text": content, "pointSize": text.font.pointSize,
                        "lineHeight": text.font.lineHeight, "fontName": text.font.fontName,
                        "adjustsForContentSizeCategory": text.adjustsFontForContentSizeCategory,
                        "frame": [frame.minX, frame.minY, frame.width, frame.height]])
                }
                for child in view.subviews { observe(child, inNavigationBar: navigation) }
            }
            for child in window.subviews { observe(child) }
            let value: [String: Any] = [
                "schema": 1, "referenceKind": "originalFeedFullTextObservation", "context": "presentedOriginalFullTextSheet",
                "scene": scene.id, "mode": mode, "probe": kind, "provenance": try! JSONSerialization.jsonObject(with: resource("Provenance.json")),
                "platform": "iOS", "runtimeVersion": UIDevice.current.systemVersion,
                "preferredLanguages": Locale.preferredLanguages, "currentLocale": Locale.current.identifier,
                "shellLocalizations": ReferenceOriginalFullText.localizations,
                "viewport": [window.bounds.width, window.bounds.height], "scale": window.screen.scale,
                "safeArea": [window.safeAreaInsets.top, window.safeAreaInsets.left, window.safeAreaInsets.bottom, window.safeAreaInsets.right],
                "category": category.rawValue, "categoryMatches": matches,
                "swiftUIDirection": direction == .rightToLeft ? "rtl" : "ltr", "swiftUIAccessibilitySize": size.isAccessibilitySize,
                "device": UIDevice.current.userInterfaceIdiom == .pad ? "tablet" : "phone",
                "style": traits.userInterfaceStyle == .dark ? "dark" : "light", "interfaceLevel": traits.userInterfaceLevel.rawValue,
                "bodyPointSize": UIFont.preferredFont(forTextStyle: .body, compatibleWith: traits).pointSize,
                "navigationLabels": navigationLabels, "navigationBars": navigationBars,
                "colors": ["background": rgba(.systemBackground), "label": rgba(.label), "secondaryLabel": rgba(.secondaryLabel), "separator": rgba(.separator)],
                "loadAttempts": state.attempts, "loadCancellations": state.cancellations, "dismissed": state.dismissed,
                "sourceSessionCreated": false, "interceptedURLs": state.interceptedURLs,
                "ready": UIApplication.shared.applicationState == .active && !window.isHidden && matches && ((window.bounds.width > window.bounds.height) == (scene.orientation == "landscape"))
            ]
            let payload = String(data: try! JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]), encoding: .utf8)
            if label.accessibilityLabel != payload { label.accessibilityLabel = payload }
        }
    }
    static func dismantleUIView(_ view: UILabel, coordinator: Observer) { coordinator.stop() }
    @MainActor final class Observer {
        var record: ((UILabel) -> Void)?; private var task: Task<Void, Never>?
        func start(_ label: UILabel) { task = Task { [weak self, weak label] in
            while !Task.isCancelled {
                if let label { self?.record?(label) }
                try? await Task.sleep(for: .milliseconds(150))
            }
        } }
        func stop() { task?.cancel(); task = nil }
    }
}
