import AppKit
import CoreImage
import SwiftUI

@MainActor
struct NativePushPairingView: View {
    @Bindable var model: NativePushPairingModel
    let close: () -> Void
    let copy: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(String(localized: "androidPush.pair.title", defaultValue: "Pair Android Notifications"))
                .font(.title2).bold()
            Text(String(localized: "androidPush.pair.instructions", defaultValue: "Connect this Mac in cmux on Android, then open Settings → Push Alerts and scan this code. Compare the address and key fingerprint before pairing."))
                .fixedSize(horizontal: false, vertical: true)
            Picker(String(localized: "androidPush.pair.app", defaultValue: "Android app"), selection: $model.phoneBuildID) {
                Text(String(localized: "androidPush.pair.release", defaultValue: "Release app")).tag("io.github.docmorphic.cmuxapp")
                Text(String(localized: "androidPush.pair.debug", defaultValue: "Debug app")).tag("io.github.docmorphic.cmuxapp.debug")
            }.disabled(model.busy)
                .onChange(of: model.phoneBuildID) { _, _ in model.clear() }
            if let offer = model.offer {
                if let image = Self.qr(offer.body) {
                    Image(nsImage: image).interpolation(.none).resizable().scaledToFit().frame(width: 248, height: 248)
                        .frame(maxWidth: .infinity)
                        .accessibilityLabel(String(localized: "androidPush.pair.qr", defaultValue: "Private notification pairing code"))
                } else {
                    Text(String(localized: "androidPush.pair.paste", defaultValue: "Use Copy Pairing Offer and paste it into the Android app."))
                }
                Text(offer.endpoint).font(.caption).textSelection(.enabled)
                Text(offer.fingerprint).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
                Text(String(localized: "androidPush.pair.expires", defaultValue: "Expires at") + " " +
                    Date(timeIntervalSince1970: Double(offer.expiresAt) / 1000).formatted(date: .omitted, time: .standard))
                    .foregroundStyle(.secondary)
            } else if model.busy {
                ProgressView().frame(maxWidth: .infinity).accessibilityLabel(String(localized: "androidPush.pair.loading", defaultValue: "Preparing pairing code"))
            }
            if model.failed {
                Text(String(localized: "androidPush.pair.error", defaultValue: "Pairing is unavailable. Sign in on this Mac, enable phone notifications, and check the private helper setup. Then try again."))
                    .foregroundStyle(.red).fixedSize(horizontal: false, vertical: true)
            }
            HStack {
                Button(String(localized: "androidPush.pair.generate", defaultValue: "New Pairing Code")) {
                    model.generate()
                }.disabled(model.busy).keyboardShortcut(.defaultAction)
                Button(String(localized: "androidPush.pair.copy", defaultValue: "Copy Pairing Offer")) {
                    copy()
                }.disabled(model.offer == nil)
                Spacer()
                Button(String(localized: "androidPush.pair.close", defaultValue: "Close")) { close() }.keyboardShortcut(.cancelAction)
            }
        }.padding(24).frame(width: 540)
            .task {
                while !Task.isCancelled {
                    model.expire()
                    try? await Task.sleep(for: .seconds(1))
                }
            }
            .onDisappear { model.clear() }
    }
    static func qr(_ data: Data) -> NSImage? {
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(data, forKey: "inputMessage"); filter.setValue("L", forKey: "inputCorrectionLevel")
        guard let output = filter.outputImage,
              let image = CIContext().createCGImage(output, from: output.extent) else { return nil }
        // Add a white quiet zone; QR remains black/white in either appearance.
        let size = NSSize(width: image.width + 8, height: image.height + 8)
        let result = NSImage(size: size)
        result.lockFocus(); NSColor.white.setFill(); NSRect(origin: .zero, size: size).fill()
        NSGraphicsContext.current?.imageInterpolation = .none
        NSImage(cgImage: image, size: NSSize(width: image.width, height: image.height))
            .draw(in: NSRect(x: 4, y: 4, width: image.width, height: image.height))
        result.unlockFocus(); return result
    }
}
