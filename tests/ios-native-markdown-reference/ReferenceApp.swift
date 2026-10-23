import SwiftUI
import UIKit

private struct ReferenceInput: Decodable {
    let markdown: String
    let scenes: [ReferenceScene]
}
private struct ReferenceScene: Decodable {
    let id: String; let language: String; let style: String
    let category: String; let orientation: String; let device: String
}
private func resource(_ name: String) -> Data {
    try! Data(contentsOf: Bundle.main.url(forResource: name, withExtension: nil)!)
}
@main struct NativeMarkdownReferenceApp: App {
    var body: some Scene { WindowGroup { ReferenceHost().ignoresSafeArea() } }
}
@MainActor private final class ReferenceState: ObservableObject {
    var direction: String?
    var accessibilitySize: Bool?
    var reduceMotion: Bool?
    var interceptedURLs: [String] = []
}
@MainActor private struct ReferenceRoot: View {
    let markdown: String; let scene: ReferenceScene; let state: ReferenceState
    var body: some View {
        // Only this original leaf is under examination. No Feed sheet, account,
        // transport, toolbar or production URL router is represented by the host.
        ChatArtifactMarkdownNativeView(markdown: markdown)
            .background(Color(uiColor: .systemBackground))
            .environment(\.openURL, OpenURLAction { url in
                state.interceptedURLs.append(url.absoluteString)
                return .handled
            })
            .preferredColorScheme(scene.style == "dark" ? .dark : .light)
            .background(ReferenceTraits(state: state))
    }
}
@MainActor private struct ReferenceTraits: View {
    let state: ReferenceState
    @Environment(\.layoutDirection) var direction
    @Environment(\.dynamicTypeSize) var size
    @Environment(\.accessibilityReduceMotion) var reduceMotion
    private func record() {
        state.direction = direction == .rightToLeft ? "rtl" : "ltr"
        state.accessibilitySize = size.isAccessibilitySize
        state.reduceMotion = reduceMotion
    }
    var body: some View {
        Color.clear.onAppear { record() }
            .onChange(of: direction) { _, _ in record() }
            .onChange(of: size) { _, _ in record() }
            .onChange(of: reduceMotion) { _, _ in record() }
            .accessibilityHidden(true)
    }
}
@MainActor private struct ReferenceHost: UIViewControllerRepresentable {
    func makeCoordinator() -> ReferenceObserver { ReferenceObserver() }
    func makeUIViewController(context: Context) -> UIViewController {
        context.coordinator.start(); return context.coordinator.controller
    }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}
    static func dismantleUIViewController(_ controller: UIViewController, coordinator: ReferenceObserver) { coordinator.stop() }
}
/// Public UIKit traits and a one-point AX observation channel. Source pixels
/// come exclusively from XCUIScreen in the independent UI-test process.
@MainActor private final class ReferenceObserver {
    let controller: UIHostingController<ReferenceRoot>
    let scene: ReferenceScene
    let state = ReferenceState()
    let observation = UILabel()
    private var task: Task<Void, Never>?
    private let provenance = try! JSONSerialization.jsonObject(with: resource("Provenance.json"))
    init() {
        let input = try! JSONDecoder().decode(ReferenceInput.self, from: resource("References.json"))
        scene = input.scenes.first { $0.id == ProcessInfo.processInfo.environment["CMUX_NATIVE_MARKDOWN_REFERENCE"] }!
        controller = UIHostingController(rootView: ReferenceRoot(markdown: input.markdown, scene: scene, state: state))
        controller.overrideUserInterfaceStyle = scene.style == "dark" ? .dark : .light
        controller.loadViewIfNeeded()
        observation.backgroundColor = .clear; observation.isUserInteractionEnabled = false
        observation.isAccessibilityElement = true; observation.accessibilityTraits = .staticText
        observation.accessibilityIdentifier = "reference.markdown.observation"; observation.alpha = 0.02
    }
    func start() {
        task = Task { [weak self] in
            while !Task.isCancelled {
                if let self, let window = self.controller.view.window { self.record(window) }
                try? await Task.sleep(for: .milliseconds(150))
            }
        }
    }
    func stop() { task?.cancel(); task = nil }
    private func record(_ window: UIWindow) {
        if observation.superview !== controller.view { controller.view.addSubview(observation) }
        observation.frame = CGRect(x: 4, y: 4, width: 1, height: 1)
        let traits = controller.traitCollection
        func rgba(_ color: UIColor) -> [CGFloat] {
            var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
            color.resolvedColor(with: traits).getRed(&r, green: &g, blue: &b, alpha: &a)
            return [r, g, b, a]
        }
        let styles: [(String, UIFont.TextStyle)] = [("body", .body), ("title", .title1), ("title2", .title2), ("title3", .title3), ("headline", .headline), ("caption2", .caption2)]
        let fonts = Dictionary(uniqueKeysWithValues: styles.map { name, style in
            let font = UIFont.preferredFont(forTextStyle: style, compatibleWith: traits)
            return (name, ["pointSize": font.pointSize, "lineHeight": font.lineHeight, "ascender": font.ascender, "descender": font.descender])
        })
        let category = UIApplication.shared.preferredContentSizeCategory
        let matches = category == (scene.category == "large" ? .large : .accessibilityMedium)
        let payload: [String: Any] = [
            "schema": 1, "referenceKind": "nativeMarkdownObservation", "scene": scene.id, "context": "standaloneBaseWindow",
            "provenance": provenance, "platform": "iOS", "runtimeVersion": UIDevice.current.systemVersion,
            "preferredLanguages": Locale.preferredLanguages, "currentLocale": Locale.current.identifier,
            "bundleLocalizations": Bundle.main.preferredLocalizations,
            "viewport": [window.bounds.width, window.bounds.height], "scale": window.screen.scale,
            "safeArea": [window.safeAreaInsets.top, window.safeAreaInsets.left, window.safeAreaInsets.bottom, window.safeAreaInsets.right],
            "category": category.rawValue, "categoryMatches": matches,
            "swiftUIDirection": state.direction as Any? ?? NSNull(), "swiftUIAccessibilitySize": state.accessibilitySize as Any? ?? NSNull(),
            "swiftUIReduceMotion": state.reduceMotion as Any? ?? NSNull(),
            "device": UIDevice.current.userInterfaceIdiom == .pad ? "tablet" : "phone",
            "style": traits.userInterfaceStyle == .dark ? "dark" : "light", "interfaceLevel": traits.userInterfaceLevel.rawValue,
            "fonts": fonts, "fontScope": "Public preferred UIKit text styles; actual source glyphs are in compositor images.",
            "colors": ["background": rgba(.systemBackground), "label": rgba(.label), "secondaryLabel": rgba(.secondaryLabel), "tertiaryLabel": rgba(.tertiaryLabel), "separator": rgba(.separator)],
            "interceptedURLs": state.interceptedURLs, "sourceSessionCreated": false,
            "ready": UIApplication.shared.applicationState == .active && !window.isHidden && window.alpha == 1 && matches && state.direction != nil && ((window.bounds.width > window.bounds.height) == (scene.orientation == "landscape"))
        ]
        let value = String(data: try! JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys]), encoding: .utf8)
        if observation.accessibilityLabel != value { observation.accessibilityLabel = value }
    }
}
