import Foundation

/// Owns original captures across encrypted storage and the private helper queue.
/// Fresh native scope gates replay; persisted context never establishes a lease.
@MainActor
final class NativePushSourceCaptureController: NativePushSourceSink {
    enum Failure: Error { case unavailable }
    private struct Observation {
        let body: Data
        let scope: NativePushSourceScope?
        let epoch: String?
        let observedAt: Int64
        let validUntil: Int64
        let forwarding: Bool
        let hideContent: Bool
    }
    private struct Entry {
        var record: NativePushCaptureRepository.Record
        var persisted: Bool
        var submitted: Bool
    }
    private let repositoryFactory: @Sendable () async throws -> NativePushCaptureRepository
    private let now: () -> Int64
    private let report: (String) -> Void
    private var sink: NativePushSourceProcessSink?
    private var repository: NativePushCaptureRepository?
    private var work: Task<Void, Never>?
    private var enabled = false
    private var stopping = false
    private var observation: Observation?
    private var nativeEpoch: String?
    private var activeScope: NativePushSourceScope?
    private var wireEpoch: String?
    private var loaded: [NativePushCaptureRepository.Record]?
    private var retireUnloaded = false
    private var entries: [Entry] = []
    private var removals: Set<UUID> = []
    private var revision: UInt64 = 0
    private var sentRevision: UInt64?

    init(repositoryFactory: @escaping @Sendable () async throws -> NativePushCaptureRepository,
         executable: URL, arguments: [String], directory: URL,
         prepareChild: @escaping @Sendable () async throws -> Void,
         now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
         onState: @escaping (String) -> Void = { _ in }) throws {
        self.repositoryFactory = repositoryFactory; self.now = now; self.report = onState
        sink = try NativePushSourceProcessSink(executable: executable, arguments: arguments, directory: directory,
            now: now, prepareChild: prepareChild, acknowledgedCapture: { [weak self] in self?.acknowledged($0) },
            onState: { [weak self] in self?.transportState($0) })
    }
    var isRunning: Bool { sink?.isRunning == true }
    var retainedCount: Int { entries.count + removals.count + (loaded?.count ?? 0) }

    func start() throws {
        guard !stopping else { throw Failure.unavailable }
        enabled = true; pump()
    }
    func offer(_ messages: [Data]) -> Bool { offer(messages, scope: nil) }
    func offer(_ messages: [Data], scope: NativePushSourceScope?) -> Bool {
        do {
            guard !stopping, revision < UInt64.max else { return false }
            let probe = try NativePushSourceDeliveryQueue()
            guard probe.offer(messages, now: now()) != .rejected else { return false }
            let control = try object(messages[0])
            if control["type"] as? String == "invalidate" { invalidate(); return true }
            guard let snapshot = control["snapshot"] as? [String: Any],
                  let observed = snapshot["observedAt"] as? NSNumber,
                  let until = snapshot["validUntil"] as? NSNumber else { return false }
            let next: Observation
            if snapshot["state"] as? String == "signed-out" {
                guard scope == nil else { return false }
                next = .init(body: messages[0], scope: nil, epoch: nil, observedAt: observed.int64Value,
                    validUntil: until.int64Value, forwarding: false, hideContent: true)
            } else {
                guard let scope, let authority = snapshot["authority"] as? [String: Any],
                      authority["accountID"] as? String == scope.accountID,
                      authority["macDeviceID"] as? String == scope.macDeviceID,
                      authority["macInstanceTag"] as? String == scope.macInstanceTag,
                      authority["macBuildID"] as? String == scope.macBuildID,
                      authority["teamID"] is NSNull,
                      let epoch = snapshot["epoch"] as? String, let settings = snapshot["settings"] as? [String: Any],
                      let forwarding = settings["forwardingEnabled"] as? Bool, let hide = settings["hideContent"] as? Bool else { return false }
                next = .init(body: messages[0], scope: scope, epoch: epoch, observedAt: observed.int64Value,
                    validUntil: until.int64Value, forwarding: forwarding, hideContent: hide)
            }
            var accepted: NativePushCaptureRepository.Record?
            if messages.count == 2 {
                guard next.forwarding, let scope = next.scope, let epoch = next.epoch,
                      let request = try object(messages[1])["request"] as? [String: Any],
                      let expiry = request["expiresAt"] as? NSNumber,
                      expiry.int64Value - next.observedAt > 0, expiry.int64Value - next.observedAt <= 120_000 else { return false }
                if expiry.int64Value > now() {
                    // Pending admissions and restored disk work have separate bounded
                    // budgets. No accepted work is evicted when a full disk loads.
                    let pending = entries.filter { !$0.persisted }
                    guard pending.count < 128, pending.reduce(0, { $0 + $1.record.body.count }) + messages[1].count <= 8 * 1_048_576 else { return false }
                    accepted = .init(id: UUID(), context: context(scope, epoch: epoch), admittedAt: next.observedAt,
                        expiresAt: expiry.int64Value, body: messages[1])
                }
            }
            if next.epoch == nil || (nativeEpoch != nil && (nativeEpoch != next.epoch || activeScope != next.scope)) || !next.forwarding {
                retireScope()
            } else if observation?.hideContent == false && next.hideContent {
                sink?.invalidate(); sentRevision = nil
            }
            nativeEpoch = next.epoch; activeScope = next.scope; observation = next
            revision += 1
            if let accepted { entries.append(.init(record: accepted, persisted: false, submitted: false)) }
            pump(); return true
        } catch { return false }
    }
    func invalidate() {
        observation = nil; sentRevision = nil; sink?.invalidate()
    }
    func retireScope() {
        invalidate(); retireUnloaded = true; wireEpoch = nil; nativeEpoch = nil; activeScope = nil
        sink?.retireScope()
        removals.formUnion(entries.map { $0.record.id }); entries.removeAll()
        if let loaded { removals.formUnion(loaded.map(\.id)); self.loaded = [] }
        pump()
    }
    func stop() async {
        stopping = true; enabled = false; invalidate()
        report("capture-stopping")
        pump(flush: true)
        await work?.value
        await sink?.stop()
        stopping = false
    }
    private func acknowledged(_ body: Data) {
        guard let index = entries.firstIndex(where: { $0.submitted && $0.record.body == body }) else { return }
        removals.insert(entries[index].record.id); entries.remove(at: index)
        report("capture-receipt-durable")
        pump()
    }
    private func transportState(_ phase: String) {
        if ["unavailable", "receipt-rejected", "bootstrap-timeout", "preparation-unavailable"].contains(phase) {
            // A malformed receipt may have advanced volatile state before a
            // transport guard failed. Durable originals own the next retry.
            sink?.retireScope(); sentRevision = nil
            for index in entries.indices { entries[index].submitted = false }
        }
        report(phase)
    }
    private func pump(flush: Bool = false) {
        guard work == nil, (enabled || flush), observation != nil || !entries.isEmpty || !removals.isEmpty || retireUnloaded else { return }
        work = Task { @MainActor [weak self] in
            guard let self else { return }
            do { try await self.drain() }
            catch { self.sink?.invalidate(); self.sentRevision = nil; self.report("capture-storage-unavailable") }
            self.work = nil
        }
    }
    private func drain() async throws {
        if repository == nil {
            let prepared = try await repositoryFactory()
            let saved = try await prepared.load(now: now())
            repository = prepared; loaded = saved
        }
        guard let repository else { throw Failure.unavailable }
        while true {
            if let saved = loaded, retireUnloaded || (activeScope != nil && nativeEpoch != nil) {
                let matching = retireUnloaded ? [] : saved.filter { matches($0.context, activeScope!) }
                let epochs = Set(matching.map { $0.context.sourceEpoch })
                let restored = epochs.count <= 1 ? matching : []
                wireEpoch = restored.first?.context.sourceEpoch ?? nativeEpoch
                let keep = Set(restored.map(\.id))
                removals.formUnion(saved.filter { !keep.contains($0.id) }.map(\.id))
                entries.insert(contentsOf: restored.map { .init(record: $0, persisted: true, submitted: false) }, at: 0)
                loaded = nil; retireUnloaded = false
            } else if loaded == nil {
                retireUnloaded = false
                if wireEpoch == nil { wireEpoch = nativeEpoch }
            }
            if !removals.isEmpty {
                let ids = removals
                try await repository.remove(ids, now: now())
                removals.subtract(ids)
                continue
            }
            // Expired originals cannot produce another delivery. The transport
            // still preserves any uncertain in-flight frame until its receipt.
            let expired = entries.filter { $0.record.expiresAt <= now() }
            if !expired.isEmpty {
                let ids = Set(expired.map { $0.record.id }); removals.formUnion(ids)
                entries.removeAll { ids.contains($0.record.id) }; continue
            }
            try submitAvailable()
            if let index = entries.firstIndex(where: { !$0.persisted }) {
                var record = entries[index].record
                let persisted = entries.filter { $0.persisted }
                guard persisted.count < 128, persisted.reduce(0, { $0 + $1.record.body.count }) + record.body.count <= 8 * 1_048_576 else { return }
                if let wireEpoch { record = try rebound(record, epoch: wireEpoch) }
                let id = record.id
                _ = try await repository.append(record, now: now())
                guard let current = entries.firstIndex(where: { $0.record.id == id }) else {
                    removals.insert(id); continue
                }
                entries[current].record = record; entries[current].persisted = true
                report("capture-committed")
                continue
            }
            return
        }
    }
    private func submitAvailable() throws {
        guard enabled, loaded == nil, let observation, observation.observedAt <= now(), now() < observation.validUntil else { return }
        let host = try reboundHost(observation.body, epoch: wireEpoch)
        if sentRevision != revision {
            guard sink?.offer([host]) == true else { throw Failure.unavailable }
            sentRevision = revision
        }
        try sink?.start()
        for index in entries.indices where entries[index].persisted && !entries[index].submitted {
            guard sink?.offer([host, entries[index].record.body]) == true else { return }
            entries[index].submitted = true
        }
    }
    private func context(_ scope: NativePushSourceScope, epoch: String) -> NativePushCaptureRepository.Context {
        .init(accountID: scope.accountID, teamID: scope.teamID, macDeviceID: scope.macDeviceID,
            macInstanceTag: scope.macInstanceTag, macBuildID: scope.macBuildID, sourceEpoch: epoch)
    }
    private func matches(_ c: NativePushCaptureRepository.Context, _ s: NativePushSourceScope) -> Bool {
        c.accountID == s.accountID && c.teamID == s.teamID && c.macDeviceID == s.macDeviceID && c.macInstanceTag == s.macInstanceTag && c.macBuildID == s.macBuildID
    }
    private func object(_ data: Data) throws -> [String: Any] {
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw Failure.unavailable }
        return object
    }
    private func rebound(_ record: NativePushCaptureRepository.Record, epoch: String) throws -> NativePushCaptureRepository.Record {
        if record.context.sourceEpoch == epoch { return record }
        var message = try object(record.body)
        guard var request = message["request"] as? [String: Any] else { throw Failure.unavailable }
        request["sourceEpoch"] = epoch; message["request"] = request
        let s = NativePushSourceScope(accountID: record.context.accountID, teamID: record.context.teamID,
            macDeviceID: record.context.macDeviceID, macInstanceTag: record.context.macInstanceTag, macBuildID: record.context.macBuildID)
        return .init(id: record.id, context: context(s, epoch: epoch), admittedAt: record.admittedAt, expiresAt: record.expiresAt,
            body: try JSONSerialization.data(withJSONObject: message, options: [.sortedKeys]))
    }
    private func reboundHost(_ data: Data, epoch: String?) throws -> Data {
        guard let epoch else { return data }
        var message = try object(data)
        guard var snapshot = message["snapshot"] as? [String: Any], snapshot["state"] as? String == "ready" else { return data }
        snapshot["epoch"] = epoch; message["snapshot"] = snapshot
        return try JSONSerialization.data(withJSONObject: message, options: [.sortedKeys])
    }
}
