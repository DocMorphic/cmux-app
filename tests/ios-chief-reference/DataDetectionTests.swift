import XCTest
import UIKit
import CryptoKit
import CmuxConversationCore

private struct DetectorCases: Decodable {
    var schema: Int
    var referenceEpochMillis: Int64
    var timeZone: String
    var samples: [DetectorSample]
}
private struct DetectorSample: Decodable {
    var id: String
    var language: String
    var locale: String
    var text: String
}
private func detectorNullable<T>(_ value: T?) -> Any { value.map { $0 as Any } ?? NSNull() }

/// Platform detector observations using the exact pinned source configuration
/// and action mapping. No URL is opened, and no backend is constructed.
final class CmuxChiefDataDetectionTests: XCTestCase {
    @MainActor func testCaptureSourceDataDetection() throws {
        let bundle = Bundle(for: Self.self)
        let input = try Data(contentsOf: XCTUnwrap(bundle.url(forResource: "DataDetectionCases", withExtension: "json")))
        let fixture = try JSONDecoder().decode(DetectorCases.self, from: input)
        XCTAssertEqual(fixture.schema, 1)
        let provenanceData = try Data(contentsOf: XCTUnwrap(bundle.url(forResource: "Provenance", withExtension: "json")))
        let provenance = try XCTUnwrap(JSONSerialization.jsonObject(with: provenanceData) as? [String: Any])
        let digest = SHA256.hash(data: input).map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(provenance["dataDetectionCasesSha256"] as? String, digest)
        let previousZone = NSTimeZone.default
        NSTimeZone.default = try XCTUnwrap(TimeZone(identifier: fixture.timeZone))
        defer { NSTimeZone.default = previousZone }
        let detector = try NSDataDetector(types: ConversationDataDetection.types)
        var records: [[String: Any]] = []
        for sample in fixture.samples {
            let text = sample.text as NSString
            let before = Date().timeIntervalSince1970 * 1000
            let matches = detector.matches(in: sample.text, options: [], range: NSRange(location: 0, length: text.length))
            let after = Date().timeIntervalSince1970 * 1000
            var observed: [[String: Any]] = []
            for match in matches {
                XCTAssertGreaterThan(match.range.length, 0)
                XCTAssertLessThanOrEqual(NSMaxRange(match.range), text.length)
                let kind: String
                switch match.resultType {
                case .date: kind = "date"
                case .address: kind = "address"
                case .phoneNumber: kind = "phone"
                case .link: kind = "link"
                default: XCTFail("Unexpected detector type"); continue
                }
                var item: [String: Any] = [
                    "start": match.range.location, "end": NSMaxRange(match.range),
                    "substring": text.substring(with: match.range), "type": kind,
                    "rawType": match.resultType.rawValue,
                    "sourceActionURL": detectorNullable(ConversationDataDetection.actionURL(for: match)?.absoluteString)
                ]
                switch match.resultType {
                case .date:
                    item["epochMillis"] = detectorNullable(match.date.map { $0.timeIntervalSince1970 * 1000 })
                    item["secondsSinceAppleReferenceDate"] = detectorNullable(match.date?.timeIntervalSinceReferenceDate)
                    item["matchedTimeZone"] = detectorNullable(match.timeZone?.identifier)
                    item["duration"] = match.duration
                case .address:
                    let components = match.addressComponents.map { Dictionary(uniqueKeysWithValues: $0.map { ($0.key.rawValue, $0.value) }) }
                    item["addressComponents"] = detectorNullable(components)
                case .phoneNumber: item["phoneNumber"] = detectorNullable(match.phoneNumber)
                case .link: item["matchedURL"] = detectorNullable(match.url?.absoluteString)
                default: break
                }
                observed.append(item)
            }
            records.append(["id": sample.id, "language": sample.language, "locale": sample.locale,
                            "source": sample.text, "referenceBeforeEpochMillis": before,
                            "referenceAfterEpochMillis": after, "matches": observed])
        }
        let output: [String: Any] = [
            "schema": 1, "referenceKind": "chiefDataDetection", "fixtureSha256": digest,
            "provenance": provenance, "records": records,
            "detectorTypeMask": ConversationDataDetection.types,
            "platform": UIDevice.current.systemName, "runtimeVersion": UIDevice.current.systemVersion,
            "os": ProcessInfo.processInfo.operatingSystemVersionString,
            "locale": Locale.current.identifier, "preferredLanguages": Locale.preferredLanguages,
            "defaultTimeZone": NSTimeZone.default.identifier,
            "systemTimeZone": NSTimeZone.system.identifier,
            "fixtureReferenceEpochMillis": fixture.referenceEpochMillis,
            "referenceMode": "capturedCurrentClock", "actionOpened": false,
            "scope": "Pinned source detector types/actionURL, platform API observations only. Controlled process time zone; real current clock cannot be injected into NSDataDetector. No bubble rendering, tap, physical or full app parity acceptance."
        ]
        let attachment = XCTAttachment(data: try JSONSerialization.data(withJSONObject: output, options: [.prettyPrinted, .sortedKeys]), uniformTypeIdentifier: "public.json")
        attachment.name = "chief-data-detection.json"; attachment.lifetime = .keepAlways
        add(attachment)
    }
}
