// Local TLS interoperability test only. Uses published synthetic fixture keys and never contacts FCM.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { PushRegistrations } from '../push/registrations.mjs';
import { PushEnrollment } from '../push/enrollment.mjs';
import { PushMaintenance } from '../push/maintenance.mjs';
import { createPushEnrollmentServer } from '../push/enrollment-http.mjs';
import { cryptoKitSealer } from '../push/crypto.mjs';
const root = fileURLToPath(new URL('../', import.meta.url)), directory = process.argv[2];
if (!directory) throw Error('Missing fixture directory');
const fixture = JSON.parse(readFileSync(join(root, 'app/src/test/resources/push/helper-enrollment.json')));
const vector = JSON.parse(readFileSync(join(root, 'app/src/test/resources/push/apple-hpke-v2.json')))[0];
const registrations = new PushRegistrations({ directory: join(directory, 'registrations'), key: Buffer.alloc(32, 99) });
const seal = cryptoKitSealer({ executable: join(root, 'build/push/cmux-push-seal'), senderKeyID: fixture.offer.helper.keyID,
  senderPublicKey: fixture.offer.helper.publicKey, privateKey: async () => Buffer.from(vector.senderPrivateKeyBase64, 'base64') });
const enrollment = new PushEnrollment({ registrations, seal, permits: offer => offer.mac.accountID === fixture.offer.mac.accountID,
  now: () => fixture.now });
let endpoint, maintenance;
const server = createPushEnrollmentServer({ key: readFileSync(join(directory, 'key.pem')), cert: readFileSync(join(directory, 'cert.pem')),
  enrollment, maintenance: { begin: body => maintenance.begin(body), finish: body => maintenance.finish(body), abort: body => maintenance.abort(body) }, endpoint: () => endpoint });
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
endpoint = `https://127.0.0.1:${server.address().port}/v1/push/enroll`;
maintenance = new PushMaintenance({ registrations, seal, endpoint, senderPublicKey: fixture.offer.helper.publicKey, senderKeyID: fixture.offer.helper.keyID,
  permits: binding => binding.recipient.tuple.accountID === fixture.offer.mac.accountID, now: () => fixture.now });
const { version, offerID, expiresAt, secret, ...input } = fixture.offer;
process.stdout.write(JSON.stringify(enrollment.issue({ ...input, endpoint })) + '\n');
process.once('SIGTERM', () => { server.closeAllConnections(); server.close(() => { enrollment.close(); maintenance.close(); registrations.close(); process.exit(0); }); });
