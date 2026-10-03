#!/usr/bin/env python3
"""Verify Android reply fixtures with pinned, unmodified cmux Swift crypto and TypeScript relay code.

Only fixed public test keys/messages are used. No Keychain, cloud APIs or user account data.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

REF = "204a11dfcc76280205e50406ab94270a1c152155"
CRYPTO = "Packages/macOS/CmuxPhonePush/Sources/CmuxPhonePush/PhonePushCrypto.swift"
RELAY = "workers/presence/src/replies.ts"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", type=Path, required=True)
    parser.add_argument("--requests", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("captures/runtime/phone-reply-relay/interop"))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    sources = {}
    for path, name in [(CRYPTO, "upstream-PhonePushCrypto.swift"), (RELAY, "replies.ts")]:
        data = subprocess.check_output(["git", "-C", str(args.upstream.resolve()), "show", f"{REF}:{path}"])
        (output / name).write_bytes(data)
        sources[path] = hashlib.sha256(data).hexdigest()
    crypto = (output / "upstream-PhonePushCrypto.swift").read_bytes()
    marker = b"/// Separate application key material."
    assert crypto.count(marker) == 1
    (output / "PhonePushCrypto.swift").write_bytes(crypto.split(marker)[0])
    compiled = subprocess.run(["/usr/bin/swiftc", str(output / "PhonePushCrypto.swift"),
        str(root / "scripts/phone-reply-fixture.swift"), "-o", str(output / "fixture")], capture_output=True, text=True)
    (output / "swift-build.txt").write_text(compiled.stdout + compiled.stderr)
    compiled.check_returncode()
    result = subprocess.check_output([str(output / "fixture"), str(args.requests.resolve())], text=True)
    (output / "apple-open-replies.txt").write_text(result); print(result.strip())
    verifier = output / "verify-relay.mjs"
    verifier.write_text('''import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {parsePhoneReply, enqueuePhoneReply} from './replies.ts';
const fixtures = JSON.parse(readFileSync(process.argv[2], 'utf8'));
assert.equal(fixtures.length, 2);
for (const {request} of fixtures) {
  const context = {accountID: 'test-account', target: {macDeviceId: 'test-mac', macInstanceTag: 'test-instance', macBuildID: 'dev.cmux.app'}};
  const parsed = parsePhoneReply(request, context);
  assert.equal(parsed.ok, true);
  assert.equal(parsePhoneReply(request, {...context, accountID: 'other'}).ok, false);
  const map = new Map();
  const storage = {get: async k => map.get(k), put: async(k,v) => {map.set(k,v)}, delete: async k => map.delete(k), list: async () => new Map(map)};
  const first = await enqueuePhoneReply(storage, parsed.reply, 1800000000000);
  assert.equal(first.ok, true); assert.equal(first.duplicate, false);
  const repeated = await enqueuePhoneReply(storage, parsed.reply, 1800000000001);
  assert.equal(repeated.ok, true); assert.equal(repeated.duplicate, true);
  const changed = structuredClone(parsed.reply); changed.encryptedPayload.senderKeyID += '-changed';
  const conflict = await enqueuePhoneReply(storage, changed, 1800000000002);
  assert.equal(conflict.ok, false); assert.equal(conflict.error, 'reply_id_conflict');
  assert.equal(map.size, 1);
}
console.log('Upstream relay accepted both Android requests, deduplicated exact retries and rejected changed envelopes');
''')
    result = subprocess.check_output(["node", str(verifier), str(args.requests.resolve())], text=True)
    (output / "relay-parse-enqueue.txt").write_text(result); print(result.strip())
    (output / "source.json").write_text(json.dumps({"ref": REF, "sha256": sources,
        "fixture": "fixed public keys and generated test messages only"}, indent=2))

if __name__ == "__main__":
    main()
