// Offline interoperability fixture ONLY: published synthetic keys, reserved hostname, no provider requests.
import { readFileSync, writeFileSync, mkdtempSync, rmSync } from 'node:fs';
import { createPrivateKey, createPublicKey, createHash, createHmac } from 'node:crypto';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { PushEnrollment, enrollmentFrame, enrollmentOfferDigest } from '../push/enrollment.mjs';
import { PushRegistrations } from '../push/registrations.mjs';
import { cryptoKitSealer } from '../push/crypto.mjs';
const root = fileURLToPath(new URL('../', import.meta.url));
const vector = JSON.parse(readFileSync(join(root, 'app/src/test/resources/push/apple-hpke-v2.json')))[0];
const directory = mkdtempSync(join(tmpdir(), 'cmux-enroll-fixture-'));
const registrations = new PushRegistrations({ directory, key: Buffer.alloc(32, 42) });
const nativePrivate = createPrivateKey({ key: Buffer.concat([Buffer.from('302e020100300506032b656e04220420', 'hex'), Buffer.alloc(32, 9)]), type: 'pkcs8', format: 'der' });
const nativePublic = createPublicKey(nativePrivate).export({ type: 'spki', format: 'der' }).subarray(-32).toString('base64');
const descriptor = (installationID, keyID, publicKey) => ({ version: 1, algorithm: 'rawX25519', installationID, keyID, publicKey });
const helper = descriptor('test-helper', vector.envelope.senderKeyID, vector.senderPublicKeyBase64);
const phone = descriptor(vector.envelope.installationID, vector.envelope.keyID, vector.recipientPublicKeyBase64);
const sealer = cryptoKitSealer({ executable: join(root, 'build/push/cmux-push-seal'), senderKeyID: helper.keyID,
  senderPublicKey: helper.publicKey, privateKey: async () => Buffer.from(vector.senderPrivateKeyBase64, 'base64') });
let challenge;
const now = 1_800_000_000_000;
const service = new PushEnrollment({ registrations, now: () => now, permits: () => true,
  seal: async args => { challenge = JSON.parse(args.plaintext); return sealer(args); } });
const mac = Object.fromEntries(['accountID', 'teamID', 'macDeviceID', 'macInstanceTag', 'macBuildID'].map(k => [k, vector.envelope.tuple[k] ?? null]));
const hmac = (key, domain, fields) => createHmac('sha256', key).update(enrollmentFrame(domain, fields)).digest('base64url');
try {
  const offer = service.issue({ endpoint: 'https://helper.invalid/v1/push/enroll', project: { project: 'fixture-project', application: 'fixture-app', sender: '123456' },
    phoneBuildID: vector.envelope.tuple.iosBuildID, mac, native: descriptor('test-native/λ中', 'test-native-key', nativePublic), helper });
  const offerDigest = enrollmentOfferDigest(offer), requestID = '00000000-0000-4000-8000-000000000001', token = 'public-fixture-fcm-token';
  const requestDigest = createHash('sha256').update(enrollmentFrame('cmux-app.helper.request.v1', [offerDigest, requestID,
    phone.installationID, phone.keyID, phone.publicKey, token])).digest('hex');
  const begin = { offerID: offer.offerID, requestID, phone, token,
    proof: hmac(Buffer.from(offer.secret, 'base64url'), 'cmux-app.helper.begin.v1', [offerDigest, requestDigest]) };
  const response = await service.begin(begin);
  const finish = { offerID: offer.offerID, requestID, proof: hmac(Buffer.from(challenge.challenge, 'base64url'), 'cmux-app.helper.finish.v1', [offerDigest, requestDigest]) };
  const ack = service.finish(finish);
  const fixture = { notice: 'PUBLIC SYNTHETIC TEST KEYS ONLY. No live helper, account, project or FCM token.', now,
    phonePrivateKey: vector.recipientPrivateKeyBase64, offerDigest, requestDigest, offer, begin, response, finish, ack };
  writeFileSync(join(root, 'app/src/test/resources/push/helper-enrollment.json'), JSON.stringify(fixture, null, 2) + '\n');
  process.stdout.write('Wrote public helper enrollment interoperability fixture.\n');
} finally { service.close(); registrations.close(); rmSync(directory, { recursive: true, force: true }); }
