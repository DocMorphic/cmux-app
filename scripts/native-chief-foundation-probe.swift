import Foundation
import Darwin

/// Reference-only provider facts from this process's CoreFoundation. These APIs
/// and property-page layout follow Swift CoreFoundation CFUniChar.h, licensed
/// under Apache-2.0 with the Swift Runtime Library Exception. Never linked into
/// the Android app or used to access account, transport or filesystem state.
func sourceFoundationProviderFacts() throws -> [String: Any] {
    typealias MapCase = @convention(c) (UInt32, UnsafeMutablePointer<UInt16>?, Int, UInt32, UInt32, UnsafePointer<UInt8>?) -> Int
    typealias IsMember = @convention(c) (UInt32, UInt32) -> Bool
    typealias PropertyPlane = @convention(c) (UInt32, UInt32) -> UnsafeRawPointer?
    guard let handle = dlopen(nil, RTLD_NOW) else {
        throw NSError(domain: "cmux.reference.foundation", code: 1)
    }
    defer { dlclose(handle) }
    func symbol(_ name: String) throws -> UnsafeMutableRawPointer {
        guard let value = dlsym(handle, name) else {
            throw NSError(domain: "cmux.reference.foundation", code: 2,
                          userInfo: [NSLocalizedDescriptionKey: "Missing provider symbol: \(name)"])
        }
        return value
    }
    let mapCase = unsafeBitCast(try symbol("CFUniCharMapCaseTo"), to: MapCase.self)
    let isMember = unsafeBitCast(try symbol("CFUniCharIsMemberOf"), to: IsMember.self)
    let propertyPlane = unsafeBitCast(try symbol("CFUniCharGetUnicodePropertyDataForPlane"), to: PropertyPlane.self)
    let combiningPlanes = (0...16).map { propertyPlane(0, UInt32($0))?.assumingMemoryBound(to: UInt8.self) }
    var buffer = [UInt16](repeating: 0, count: 32)
    var fold: [String: String] = [:], decompositions: [String: String] = [:]
    var combining: [String: Int] = [:], letters: [Int] = [], extends: [Int] = [], whitespace: [Int] = []
    var queries = 0
    for cp in UInt32(0)..<UInt32(0x110000) where !(0xD800...0xDFFF).contains(cp) {
        let original = String(Unicode.Scalar(cp)!)
        let count = buffer.withUnsafeMutableBufferPointer { mapCase(cp, $0.baseAddress, $0.count, 3, 0, nil) }
        guard count >= 0 && count <= buffer.count else {
            throw NSError(domain: "cmux.reference.foundation", code: 3)
        }
        let mapped = count == 0 ? original : String(decoding: buffer.prefix(count), as: UTF16.self)
        let key = String(cp, radix: 16)
        // Swift String equality is canonical; provider facts need exact units.
        if !mapped.utf16.elementsEqual(original.utf16) { fold[key] = mapped }
        let nfd = original.decomposedStringWithCanonicalMapping
        if !nfd.utf16.elementsEqual(original.utf16) { decompositions[key] = nfd }
        if isMember(cp, 5) { letters.append(Int(cp)) }
        if isMember(cp, 110) { extends.append(Int(cp)) }
        if isMember(cp, 3) { whitespace.append(Int(cp)) }
        if let plane = combiningPlanes[Int(cp >> 16)] {
            let unit = Int(cp & 0xFFFF), page = Int(plane[unit >> 8])
            if page != 0 {
                let value = Int(plane[256 + (page - 1) * 256 + (unit & 255)])
                if value != 0 { combining[key] = value }
            }
        }
        queries += 1
    }
    func ranges(_ values: [Int]) -> [[Int]] {
        var result: [[Int]] = []
        for value in values {
            if let last = result.last, value == last[1] + 1 {
                result[result.count - 1][1] = value
            } else { result.append([value, value]) }
        }
        return result
    }
    return ["schema": 1, "scalarQueries": queries, "caseMapType": 3, "flags": 0,
            "language": NSNull(), "characterSets": ["letter": 5, "graphemeExtend": 110, "whitespaceAndNewline": 3],
            "caseFold": fold, "canonicalDecomposition": decompositions,
            "combiningClass": combining, "letterRanges": ranges(letters), "graphemeExtendRanges": ranges(extends),
            "whitespaceAndNewlineRanges": ranges(whitespace),
            "scope": "All valid Unicode scalars; omitted mappings are identity and omitted combining classes are zero. Native CoreFoundation provider facts, not a matcher, a production asset or full parity."]
}
