import SwiftUI
import UIKit
import CmuxSignInMethodsReference

struct SignInReferenceScenes: Decodable, Sendable { var schema: Int; var scenes: [SignInReferenceScene] }
struct SignInReferenceScene: Decodable, Sendable {
    var id: String; var language: String; var style: String; var category: String; var orientation: String; var device: String; var mode: String
}
private func resource(_ name: String) -> Data {
    try! Data(contentsOf: Bundle.main.url(forResource: name, withExtension: nil)!)
}
private func selected() -> SignInReferenceScene {
    let input = try! JSONDecoder().decode(SignInReferenceScenes.self, from: resource("References.json"))
    return input.scenes.first { $0.id == ProcessInfo.processInfo.environment["CMUX_SIGNIN_METHODS_REFERENCE"] } ?? input.scenes[0]
}

@main struct SignInMethodsReferenceApp: App {
    var body: some Scene { WindowGroup { ReferenceHost().ignoresSafeArea() } }
}

@MainActor private final class ReferenceState: ObservableObject {
    @Published var completed = false
    var completions = 0
    var swiftUIAccessibilitySize: Bool?
    var swiftUIReduceMotion: Bool?
    var swiftUIDirection: String?
}

@MainActor private struct ReferenceRoot: View {
    @ObservedObject var state: ReferenceState
    let scene: SignInReferenceScene
    var body: some View {
        SignInMethodsReferenceRoot(mode: scene.mode)
        .preferredColorScheme(scene.style == "dark" ? .dark : .light)
        .background(ReferenceTraits(state: state))
    }
}

@MainActor private struct ReferenceTraits: View {
    let state: ReferenceState
    @Environment(\.dynamicTypeSize) var size
    @Environment(\.accessibilityReduceMotion) var reduceMotion
    @Environment(\.layoutDirection) var direction
    private func record() {
        state.swiftUIAccessibilitySize = size.isAccessibilitySize
        state.swiftUIReduceMotion = reduceMotion
        state.swiftUIDirection = direction == .rightToLeft ? "rtl" : "ltr"
    }
    var body: some View {
        Color.clear.onAppear { record() }
            .onChange(of: size) { _, _ in record() }
            .onChange(of: reduceMotion) { _, _ in record() }
            .onChange(of: direction) { _, _ in record() }
            .accessibilityHidden(true)
    }
}

@MainActor private struct ReferenceHost: UIViewControllerRepresentable {
    func makeCoordinator() -> ReferenceObserver { ReferenceObserver() }
    func makeUIViewController(context: Context) -> UIViewController { context.coordinator.start(); return context.coordinator.controller }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
    static func dismantleUIViewController(_ uiViewController: UIViewController, coordinator: ReferenceObserver) { coordinator.stop() }
}

/// Public traits and a one-point AX observation channel only; pixels are captured
/// from the actual UI-test process/compositor, never synthesized by this observer.
@MainActor private final class ReferenceObserver {
    let scene = selected()
    let state = ReferenceState()
    let controller: UIHostingController<ReferenceRoot>
    let observation = UILabel()
    private var task: Task<Void, Never>?
    private let provenance = try! JSONSerialization.jsonObject(with: resource("Provenance.json")) as! [String: Any]
    init() {
        controller = UIHostingController(rootView: ReferenceRoot(state: state, scene: scene))
        controller.overrideUserInterfaceStyle = scene.style == "dark" ? .dark : .light
        controller.loadViewIfNeeded()
        observation.backgroundColor = .clear; observation.isUserInteractionEnabled = false
        observation.isAccessibilityElement = true; observation.accessibilityTraits = .staticText
        observation.accessibilityIdentifier = "reference.signin.observation"
        observation.alpha = 0.02
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
            var r: CGFloat = 0; var g: CGFloat = 0; var b: CGFloat = 0; var a: CGFloat = 0
            color.resolvedColor(with: traits).getRed(&r, green: &g, blue: &b, alpha: &a)
            return [r, g, b, a]
        }
        let expected: UIContentSizeCategory = scene.category == "large" ? .large : .accessibilityMedium
        let category = UIApplication.shared.preferredContentSizeCategory
        let orientation = window.windowScene?.interfaceOrientation ?? .unknown
        let orientationMatches = orientation != .unknown && orientation.isLandscape == (scene.orientation == "landscape")
        let windowOnScreen = window.convert(window.bounds, to: window.screen.coordinateSpace)
        let payload: [String: Any] = [
            "schema": 1, "referenceKind": "signinMethods", "scene": scene.id, "provenance": provenance,
            "platform": "iOS", "runtimeVersion": UIDevice.current.systemVersion,
            "preferredLanguages": Locale.preferredLanguages, "currentLocale": Locale.current.identifier,
            "bundleLocalizations": Bundle.main.preferredLocalizations,
            "viewport": [window.bounds.width, window.bounds.height], "scale": window.screen.scale,
            "screenPoints": [window.screen.bounds.width, window.screen.bounds.height],
            "windowOnScreen": [windowOnScreen.minX, windowOnScreen.minY, windowOnScreen.width, windowOnScreen.height],
            "screenFixedPoints": [window.screen.fixedCoordinateSpace.bounds.width, window.screen.fixedCoordinateSpace.bounds.height],
            "screenNativePixels": [window.screen.nativeBounds.width, window.screen.nativeBounds.height],
            "nativeScale": window.screen.nativeScale,
            "interfaceOrientation": orientation.rawValue, "orientationMatches": orientationMatches,
            "safeArea": [window.safeAreaInsets.top, window.safeAreaInsets.left, window.safeAreaInsets.bottom, window.safeAreaInsets.right],
            "category": category.rawValue, "categoryMatches": category == expected,
            "swiftUIAccessibilitySize": state.swiftUIAccessibilitySize as Any? ?? NSNull(),
            "swiftUIReduceMotion": state.swiftUIReduceMotion as Any? ?? NSNull(),
            "swiftUIDirection": state.swiftUIDirection as Any? ?? NSNull(),
            "horizontalSizeClass": traits.horizontalSizeClass.rawValue, "verticalSizeClass": traits.verticalSizeClass.rawValue,
            "device": UIDevice.current.userInterfaceIdiom == .pad ? "tablet" : "phone",
            "style": traits.userInterfaceStyle == .dark ? "dark" : "light",
            "mode": scene.mode, "fixtureAuthService": true,
            "sourceSessionCreated": false, "sourceVPNControllerCreated": false,
            "ready": UIApplication.shared.applicationState == .active && !window.isHidden && window.alpha == 1 && category == expected && state.swiftUIAccessibilitySize != nil && orientationMatches,
            "fonts": ["body": UIFont.preferredFont(forTextStyle: .body, compatibleWith: traits).pointSize,
                "largeTitle": UIFont.preferredFont(forTextStyle: .largeTitle, compatibleWith: traits).pointSize,
                "subheadline": UIFont.preferredFont(forTextStyle: .subheadline, compatibleWith: traits).pointSize],
            "colors": ["blue": rgba(.systemBlue), "green": rgba(.systemGreen), "purple": rgba(.systemPurple),
                "background": rgba(.systemBackground), "gray2": rgba(.systemGray2), "gray4": rgba(.systemGray4)]
        ]
        let value = String(data: try! JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys]), encoding: .utf8)
        if observation.accessibilityLabel != value { observation.accessibilityLabel = value }
    }
}
