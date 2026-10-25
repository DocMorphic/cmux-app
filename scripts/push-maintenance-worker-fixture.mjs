// Disposable localhost TLS fixture. Published test keys only; never contacts Firebase.
import { readFileSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:https';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { PushRegistrations } from '../push/registrations.mjs';
import { PushEnrollment } from '../push/enrollment.mjs';
import { PushMaintenance } from '../push/maintenance.mjs';
import { createPushEnrollmentServer } from '../push/enrollment-http.mjs';
import { cryptoKitSealer } from '../push/crypto.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
const directory = process.argv[2];
if (!directory) throw Error('A disposable fixture directory is required');
const host = process.argv.includes('--android') ? '10.0.2.2' : '127.0.0.1';
const fixture = JSON.parse(readFileSync(join(root, 'app/src/test/resources/push/helper-enrollment.json')));
const vector = JSON.parse(readFileSync(join(root, 'app/src/test/resources/push/apple-hpke-v2.json')))[0];
const registrations = new PushRegistrations({ directory: join(directory, 'registrations'), key: Buffer.alloc(32, 99) });
const seal = cryptoKitSealer({ executable: join(root, 'build/push/cmux-push-seal'), senderKeyID: fixture.offer.helper.keyID,
  senderPublicKey: fixture.offer.helper.publicKey, privateKey: async () => Buffer.from(vector.senderPrivateKeyBase64, 'base64') });
const enrollment = new PushEnrollment({ registrations, seal, permits: offer => offer.mac.accountID === fixture.offer.mac.accountID,
  now: Date.now });
let endpoint, maintenance, armed = false, commits = 0;
const events = [], gates = [];
function state() {
  return { commits, events, gates: gates.map(({ requestID, closed, released }) => ({ requestID, closed, released })),
    remote: registrations.recipients({ accountID: fixture.offer.mac.accountID, teamID: fixture.offer.mac.teamID ?? null })
      .map(row => ({ token: row.token, registration: row.registration })) };
}
function checkpoint() { writeFileSync(join(directory, 'state.json'), JSON.stringify(state(), null, 2), { mode: 0o600 }); }
const tls = { key: readFileSync(join(directory, 'key.pem')), cert: readFileSync(join(directory, 'cert.pem')) };
const server = createPushEnrollmentServer({ ...tls, enrollment, endpoint: () => endpoint, maintenance: {
  begin: async body => {
    events.push({ step: 'maintain.begin', requestID: body.requestID, action: body.action }); checkpoint();
    return maintenance.begin(body);
  },
  finish: body => {
    const previous = registrations.maintenanceReceipt(body.requestID);
    const ack = maintenance.finish(body);
    if (!previous) commits++;
    events.push({ step: 'maintain.finish', requestID: body.requestID, action: ack.action, committed: !previous }); checkpoint();
    return ack;
  },
  abort: body => {
    const result = maintenance.abort(body);
    events.push({ step: 'maintain.abort', requestID: body.requestID, recoveredCommittedAck: result.ack !== null }); checkpoint();
    return result;
  },
} });
// Hold only the response write, after the unmodified production HTTP handler
// and maintenance store have synchronously committed and built their ACK.
server.prependListener('request', (_request, response) => {
  const end = response.end.bind(response);
  response.end = (chunk, ...args) => {
    let ack;
    try { ack = JSON.parse(String(chunk)); } catch { return end(chunk, ...args); }
    if (!armed || response.statusCode !== 200 || ack.action !== 'renew') return end(chunk, ...args);
    armed = false;
    const gate = { requestID: ack.requestID, closed: false, released: false,
      release: () => { if (gate.released) return; gate.released = true; if (!response.destroyed) end(chunk, ...args); checkpoint(); } };
    gates.push(gate);
    response.once('close', () => { gate.closed = true; checkpoint(); });
    checkpoint();
    return response;
  };
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
endpoint = `https://${host}:${server.address().port}/v1/push/enroll`;
maintenance = new PushMaintenance({ registrations, seal, endpoint, senderPublicKey: fixture.offer.helper.publicKey,
  senderKeyID: fixture.offer.helper.keyID, permits: binding => binding.recipient.tuple.accountID === fixture.offer.mac.accountID,
  now: Date.now });
const control = createServer(tls, (request, response) => {
  let code = 200;
  if (request.method === 'POST' && request.url === '/arm') armed = true;
  else if (request.method === 'POST' && request.url === '/release') gates.forEach(gate => gate.release());
  else if (request.method !== 'GET' || request.url !== '/state') code = 404;
  response.writeHead(code, { 'Content-Type': 'application/json', 'Connection': 'close' });
  response.end(JSON.stringify(state()));
});
await new Promise(resolve => control.listen(0, '127.0.0.1', resolve));
const { version, offerID, expiresAt, secret, ...input } = fixture.offer;
const offer = enrollment.issue({ ...input, endpoint });
const config = { offer, now: Date.now(), control: `https://${host}:${control.address().port}` };
writeFileSync(join(directory, 'config.json'), JSON.stringify(config, null, 2), { mode: 0o600 });
checkpoint();
process.stdout.write(JSON.stringify(config) + String.fromCharCode(10));
process.once('SIGTERM', async () => {
  gates.forEach(gate => gate.release());
  server.closeAllConnections(); control.closeAllConnections();
  await Promise.all([new Promise(resolve => server.close(resolve)), new Promise(resolve => control.close(resolve))]);
  checkpoint(); enrollment.close(); maintenance.close(); registrations.close(); process.exit(0);
});
