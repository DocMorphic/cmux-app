import Darwin
import Foundation
import Security

/// Supervises one explicitly enabled, private helper child. Runtime/configuration
/// are supplied by the trusted app owner, never a phone request. Construction is
/// passive; secrets belong in private pipes/credential storage, not arguments.
@MainActor
final class NativePushSourceProcessSink: NativePushSourceSink {
    enum Failure: Error { case invalidConfiguration, unavailable }
    private final class Child {
        let ticket = UUID()
        let process: Process
        let reader: DispatchIO
        let writer: DispatchIO
        var retiring = false
        var receipt = Data()
        var writeTicket: UUID?
        var writeFinished = false
        var receiptAction: NativePushSourceDeliveryQueue.ReceiptAction?
        init(process: Process, reader: DispatchIO, writer: DispatchIO) {
            self.process = process; self.reader = reader; self.writer = writer
        }
        deinit {
            // An owner being released must not leave its private helper alive.
            // No wait/blocking IO occurs here; Foundation owns child reaping.
            reader.close(flags: .stop); writer.close(flags: .stop)
            if process.isRunning { _ = Darwin.kill(process.processIdentifier, SIGKILL) }
        }
    }
    private let executable: URL
    private let arguments: [String]
    private let directory: URL
    private let now: () -> Int64
    private let report: (String) -> Void
    private let receiptDelay: Duration
    private let retryDelay: Duration
    private let queue: NativePushSourceDeliveryQueue
    private let io = DispatchQueue(label: "cmux.android-push.private-pipes")
    private var child: Child?
    private var enabled = false
    private var timer: Task<Void, Never>?
    private var stopWaiters: [CheckedContinuation<Void, Never>] = []

    init(executable: URL, arguments: [String], directory: URL,
         now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
         receiptDelay: Duration = .seconds(5), retryDelay: Duration = .seconds(5),
         onState: @escaping (String) -> Void = { _ in }) throws {
        guard executable.isFileURL, executable.path.hasPrefix("/"), directory.isFileURL,
              directory.path.hasPrefix("/"), receiptDelay > .zero, retryDelay > .zero else {
            throw Failure.invalidConfiguration
        }
        self.executable = executable; self.arguments = arguments; self.directory = directory
        self.now = now; self.report = onState; self.receiptDelay = receiptDelay; self.retryDelay = retryDelay
        self.queue = try NativePushSourceDeliveryQueue()
    }
    var isRunning: Bool { child?.process.isRunning == true && child?.retiring == false }
    var retainedCount: Int { queue.retainedCount }

    func start() throws {
        guard stopWaiters.isEmpty else { throw Failure.unavailable }
        enabled = true
        pump()
    }
    func offer(_ messages: [Data]) -> Bool {
        switch queue.offer(messages, now: now()) {
        case .rejected: return false
        case .retireTransport: retire()
        case .accepted: break
        }
        pump()
        return true
    }
    func invalidate() {
        queue.invalidate()
        retire()
    }
    /// Stop provider work by retiring the owned child, then await its actual exit
    /// before the app closes credentials/stores or permits replacement startup.
    func stop() async {
        enabled = false
        invalidate()
        guard child != nil else { return }
        await withCheckedContinuation { stopWaiters.append($0) }
    }
    private func retire() {
        timer?.cancel(); timer = nil
        guard let child, !child.retiring else { return }
        child.retiring = true
        child.reader.close(flags: .stop); child.writer.close(flags: .stop)
        // Only the private Process created here is targeted. SIGTERM cannot
        // guarantee that an old helper stops accepting provider work immediately.
        if child.process.isRunning { _ = Darwin.kill(child.process.processIdentifier, SIGKILL) }
        report("retiring")
    }
    private func fail(_ ticket: UUID, kind: String = "unavailable") {
        guard child?.ticket == ticket, child?.retiring == false else { return }
        queue.invalidate()
        retire()
        report(kind)
    }
    private func exited(_ ticket: UUID) {
        guard let child, child.ticket == ticket else { return }
        timer?.cancel(); timer = nil
        if !child.retiring { queue.invalidate() }
        child.reader.close(flags: .stop); child.writer.close(flags: .stop)
        self.child = nil
        let waiters = stopWaiters; stopWaiters.removeAll()
        for waiter in waiters { waiter.resume() }
        report("reaped")
        pump()
    }
    private func launch() {
        guard enabled, child == nil else { return }
        do {
            var key = Data(count: 32)
            defer { key.resetBytes(in: 0..<key.count) }
            guard key.withUnsafeMutableBytes({ SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!) }) == errSecSuccess else {
                throw Failure.unavailable
            }
            try queue.begin(sessionID: UUID().uuidString.lowercased(), key: key, now: now())
            let input = Pipe(), output = Pipe(), process = Process()
            process.executableURL = executable; process.arguments = arguments
            process.currentDirectoryURL = directory
            process.environment = ["PATH": "/usr/bin:/bin"]
            process.standardInput = input; process.standardOutput = output
            process.standardError = FileHandle.nullDevice
            let writerFD = dup(input.fileHandleForWriting.fileDescriptor)
            let readerFD = dup(output.fileHandleForReading.fileDescriptor)
            guard writerFD >= 0, readerFD >= 0 else {
                if writerFD >= 0 { Darwin.close(writerFD) }
                if readerFD >= 0 { Darwin.close(readerFD) }
                throw Failure.unavailable
            }
            // Local pipe EPIPE must be an IO failure, never SIGPIPE in the app.
            _ = fcntl(writerFD, F_SETNOSIGPIPE, 1)
            let writer = DispatchIO(type: .stream, fileDescriptor: writerFD, queue: io) { @Sendable _ in Darwin.close(writerFD) }
            let reader = DispatchIO(type: .stream, fileDescriptor: readerFD, queue: io) { @Sendable _ in Darwin.close(readerFD) }
            reader.setLimit(lowWater: 1); reader.setLimit(highWater: 1024)
            let state = Child(process: process, reader: reader, writer: writer)
            let ticket = state.ticket
            process.terminationHandler = { @Sendable [weak self] _ in
                Task { @MainActor [weak self] in self?.exited(ticket) }
            }
            do { try process.run() }
            catch { reader.close(flags: .stop); writer.close(flags: .stop); throw error }
            child = state
            // The child inherited its own endpoints; DispatchIO owns duplicated
            // parent endpoints. Close all unused original Pipe handles.
            try? input.fileHandleForReading.close(); try? input.fileHandleForWriting.close()
            try? output.fileHandleForReading.close(); try? output.fileHandleForWriting.close()
            reader.read(offset: 0, length: Int.max, queue: io) { @Sendable [weak self] done, data, error in
                let bytes = data.map { Data($0) } ?? Data()
                Task { @MainActor [weak self] in
                    guard let self else { return }
                    self.read(bytes, ticket: ticket)
                    if error != 0 || done { self.fail(ticket) }
                }
            }
            report("started")
        } catch NativePushSourceDeliveryQueue.Failure.unavailable {
            // No fresh native observation yet. A subsequent host offer wakes us.
        } catch {
            queue.invalidate(); report("unavailable")
        }
    }
    private func read(_ bytes: Data, ticket: UUID) {
        guard let child, child.ticket == ticket, !child.retiring else { return }
        guard bytes.count <= 65_536 else { fail(ticket, kind: "receipt-rejected"); return }
        do {
            for byte in bytes {
                if byte == 10 {
                    let action = try queue.receive(child.receipt)
                    child.receipt.removeAll(keepingCapacity: true)
                    if action != .duplicate {
                        guard child.writeTicket != nil, child.receiptAction == nil else { throw Failure.unavailable }
                        child.receiptAction = action
                        advance(child)
                    }
                } else {
                    guard child.receipt.count < 512 else { throw Failure.unavailable }
                    child.receipt.append(byte)
                }
            }
        } catch { fail(ticket, kind: "receipt-rejected") }
    }
    private func advance(_ child: Child) {
        guard child.writeFinished, let action = child.receiptAction, !child.retiring else { return }
        timer?.cancel(); timer = nil
        child.writeTicket = nil; child.receiptAction = nil
        if action == .retryLater { schedule(child.ticket, delay: retryDelay) }
        else { pump() }
    }
    private func schedule(_ ticket: UUID, delay: Duration) {
        timer?.cancel()
        timer = Task { [weak self] in
            do { try await Task.sleep(for: delay) } catch { return }
            guard !Task.isCancelled, let self, let child = self.child,
                  child.ticket == ticket, !child.retiring else { return }
            self.timer = nil
            // Bootstrap has no capture side effects and the helper does not
            // accept a second bootstrap in one channel. Retire it rather than
            // sending that private key again as a sequenced source frame.
            guard !self.queue.isBootstrapping else { self.fail(ticket, kind: "bootstrap-timeout"); return }
            // A lost receipt retries the exact queue frame after completed write.
            guard child.writeFinished || child.writeTicket == nil else { self.fail(ticket); return }
            child.writeTicket = nil; child.receiptAction = nil
            self.pump()
        }
    }
    private func pump() {
        guard enabled else { return }
        if child == nil { launch() }
        guard let child, !child.retiring, child.writeTicket == nil, timer == nil else { return }
        do {
            guard var bytes = try queue.next(now: now()) else { return }
            bytes.append(10)
            let packet = UUID(), ticket = child.ticket
            child.writeTicket = packet; child.writeFinished = false; child.receiptAction = nil
            let wire = bytes.withUnsafeBytes { DispatchData(bytes: $0) }
            child.writer.write(offset: 0, data: wire, queue: io) { @Sendable [weak self] done, _, error in
                Task { @MainActor [weak self] in
                    guard let self, let child = self.child, child.ticket == ticket,
                          child.writeTicket == packet, !child.retiring else { return }
                    if error != 0 { self.fail(ticket); return }
                    if done { child.writeFinished = true; self.advance(child) }
                }
            }
            schedule(ticket, delay: receiptDelay)
        } catch { fail(child.ticket) }
    }
}
