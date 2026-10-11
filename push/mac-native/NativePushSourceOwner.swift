import Foundation

/// Optional composition-root owner for the explicitly configured Android helper.
/// Account authority remains in PhonePushClient's real native producer hooks.
@MainActor
final class NativePushSourceOwner: NativePushSourceSink {
    enum Failure: Error { case invalidConfiguration }
    private let sink: NativePushSourceProcessSink

    init?(environment: [String: String], loadDescriptor: @escaping @Sendable () throws -> Data,
          onState: @escaping (String) -> Void = { _ in }) throws {
        guard let configuration = environment["CMUX_ANDROID_PUSH_CONFIG"] else { return nil }
        guard let node = environment["CMUX_ANDROID_PUSH_NODE"], let helper = environment["CMUX_ANDROID_PUSH_HELPER"],
              [configuration, node, helper].allSatisfy({ $0.hasPrefix("/") && !$0.contains("\0") }) else { throw Failure.invalidConfiguration }
        let repository = NativePushOwnerDescriptorRepository(configurationURL: URL(fileURLWithPath: configuration), loadDescriptor: loadDescriptor)
        sink = try NativePushSourceProcessSink(executable: URL(fileURLWithPath: node),
            arguments: [helper, "--config", configuration], directory: URL(fileURLWithPath: helper).deletingLastPathComponent(),
            prepareChild: { try await repository.publish() }, onState: onState)
    }
    func start() throws { try sink.start() }
    func offer(_ messages: [Data]) -> Bool { sink.offer(messages) }
    func invalidate() { sink.invalidate() }
    func stop() async { await sink.stop() }
    var isRunning: Bool { sink.isRunning }
    var retainedCount: Int { sink.retainedCount }
}
