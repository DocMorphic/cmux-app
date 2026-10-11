import { X509Certificate, createPrivateKey, createPublicKey } from 'node:crypto';
import { rootCertificates, createSecureContext } from 'node:tls';
import { BlockList, isIP } from 'node:net';
import { isDeepStrictEqual } from 'node:util';
import { constants, lstatSync, mkdirSync, openSync, writeFileSync, fsyncSync, closeSync, realpathSync } from 'node:fs';
import { basename, dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { readPrivatePushFile, privateServiceAccountTokens } from './private-credentials.mjs';
import { provisionPushOwnerKeys } from './owner-keys.mjs';

export class PushRuntimeSetupError extends Error { constructor() { super('Private push runtime setup unavailable'); } }
const need = condition => { if (!condition) throw new PushRuntimeSetupError(); };
const packages = ['io.github.docmorphic.cmuxapp', 'io.github.docmorphic.cmuxapp.debug'];
const decode = bytes => JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
const within = (parent, child) => {
  const path = relative(parent, child);
  return !path || (!path.startsWith('..' + sep) && path !== '..' && !isAbsolute(path));
};
const unspecified = new BlockList(); unspecified.addAddress('0.0.0.0'); unspecified.addAddress('::', 'ipv6');
function directory(path) {
  const value = lstatSync(path);
  need(value.isDirectory() && value.uid === process.getuid() && !(value.mode & 0o077));
}

/** Offline public-root TLS validation. Tests inject their isolated CA; the CLI
 * always uses the runtime's public root set. No sockets or provider IO. */
export function verifyRuntimeTls({ key, cert, hostname, now = Date.now(), roots = rootCertificates }) {
  try {
    const blocks = cert.toString('utf8').match(/-----BEGIN CERTIFICATE-----[\s\S]*?-----END CERTIFICATE-----/g);
    need(blocks?.length > 0 && blocks.length <= 8);
    const chain = blocks.map(value => new X509Certificate(value)), leaf = chain[0];
    const live = certificate => Date.parse(certificate.validFrom) <= now && now < Date.parse(certificate.validTo);
    need(live(leaf) && !leaf.ca && leaf.checkHost(hostname, { subject: 'never' }) !== undefined);
    need(!leaf.keyUsage || leaf.keyUsage.includes('1.3.6.1.5.5.7.3.1'));
    const privateKey = createPrivateKey(key), actual = createPublicKey(privateKey).export({ format: 'der', type: 'spki' });
    need(actual.equals(leaf.publicKey.export({ format: 'der', type: 'spki' })));
    createSecureContext({ key, cert });
    let current = leaf;
    for (const issuer of chain.slice(1)) {
      need(issuer.ca && live(issuer) && current.checkIssued(issuer) && current.verify(issuer.publicKey));
      current = issuer;
    }
    need(roots.some(value => {
      const root = new X509Certificate(value);
      return root.ca && live(root) && ((current.fingerprint256 === root.fingerprint256) ||
        (current.checkIssued(root) && current.verify(root.publicKey)));
    }));
  } catch { throw new PushRuntimeSetupError(); }
}

/** Explicit local setup; output has public metadata/paths only. Existing
 * configuration must match exactly and is never silently replaced. */
export function provisionPushRuntime(input, { now = Date.now(), roots = rootCertificates } = {}) {
  const owned = [];
  try {
    const fields = ['keysDirectory', 'firebasePath', 'providerPath', 'tlsKeyPath', 'tlsCertPath', 'hostname',
      'listenHost', 'port', 'sealerExecutable', 'output'];
    need(input && typeof input === 'object' && !Array.isArray(input) &&
      Object.keys(input).length === fields.length && fields.every(k => Object.hasOwn(input, k)));
    const pathFields = fields.filter(k => !['hostname', 'listenHost', 'port'].includes(k));
    need(process.platform !== 'win32' && pathFields.every(k => typeof input[k] === 'string' &&
      isAbsolute(input[k]) && !input[k].includes('\0') && resolve(input[k]) === input[k]));
    need(typeof input.hostname === 'string' && input.hostname.includes('.') && input.hostname.length <= 253 &&
      input.hostname.split('.').every(label => label.length <= 63 && /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/.test(label)));
    const family = isIP(input.listenHost);
    need(family !== 0 && !unspecified.check(input.listenHost, family === 4 ? 'ipv4' : 'ipv6') &&
      Number.isInteger(input.port) && input.port > 0 && input.port <= 65535);
    const parent = dirname(input.output), stateDirectory = join(parent, 'state');
    need(Buffer.byteLength(join(stateDirectory, 'control/owner.sock')) <= 100 &&
      !within(parent, input.keysDirectory) && !within(input.keysDirectory, parent) &&
      input.output !== join(parent, 'native.json') &&
      pathFields.filter(k => k !== 'output' && k !== 'keysDirectory').every(k => input[k] !== input.output));
    directory(dirname(parent));
    directory(dirname(input.keysDirectory));
    const physicalParent = join(realpathSync(dirname(parent)), basename(parent));
    const physicalKeys = join(realpathSync(dirname(input.keysDirectory)), basename(input.keysDirectory));
    need(!within(physicalParent, physicalKeys) && !within(physicalKeys, physicalParent));
    const read = (path, maximum) => { const value = readPrivatePushFile(path, maximum); owned.push(value); return value; };
    const firebase = decode(read(input.firebasePath, 65_536));
    const project = firebase.project_info?.project_id, sender = firebase.project_info?.project_number;
    need(/^[a-z][a-z0-9-]{4,28}[a-z0-9]$/.test(project ?? '') && typeof sender === 'string' && /^[1-9][0-9]{4,19}$/.test(sender));
    need(Array.isArray(firebase.client));
    const clients = packages.map(phoneBuildID => {
      const matches = firebase.client.filter(row => row.client_info?.android_client_info?.package_name === phoneBuildID);
      need(matches.length === 1);
      const application = matches[0].client_info.mobilesdk_app_id;
      need(typeof application === 'string' && application.startsWith(`1:${sender}:android:`) && /^1:[0-9]+:android:[0-9a-f]+$/.test(application));
      return { phoneBuildID, application };
    });
    const provider = decode(read(input.providerPath, 16_384));
    need(provider.project_id === project && typeof provider.client_email === 'string' && provider.client_email.endsWith(`@${project}.iam.gserviceaccount.com`));
    privateServiceAccountTokens({ path: input.providerPath, projectID: project, accountEmail: provider.client_email });
    verifyRuntimeTls({ key: read(input.tlsKeyPath, 16_384), cert: read(input.tlsCertPath, 65_536), hostname: input.hostname, now, roots });
    const executable = lstatSync(input.sealerExecutable);
    need(executable.isFile() && !executable.isSymbolicLink() && executable.nlink === 1 &&
      [0, process.getuid()].includes(executable.uid) && (executable.mode & 0o111) && !(executable.mode & 0o022));
    const { helper, keys } = provisionPushOwnerKeys(input.keysDirectory);
    const configuration = { version: 1, stateDirectory, helper, nativeDescriptorPath: join(parent, 'native.json'), keys,
      tls: { key: input.tlsKeyPath, cert: input.tlsCertPath },
      provider: { path: input.providerPath, projectID: project, accountEmail: provider.client_email, sender }, clients,
      endpoint: `https://${input.hostname}${input.port === 443 ? '' : ':' + input.port}/v1/push/enroll`,
      listener: { host: input.listenHost, port: input.port }, sealerExecutable: input.sealerExecutable };
    const bytes = Buffer.from(JSON.stringify(configuration, null, 2) + '\n');
    try { mkdirSync(parent, { mode: 0o700 }); } catch (error) { if (error.code !== 'EEXIST') throw error; }
    directory(parent);
    let existing;
    try { existing = lstatSync(input.output); } catch (error) { if (error.code !== 'ENOENT') throw error; }
    if (existing) {
      const saved = decode(read(input.output, 65_536));
      // Full nested structure comparison; formatting-only differences preserve the file.
      need(isDeepStrictEqual(saved, configuration));
      return { created: false, configuration };
    }
    const fd = openSync(input.output, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW, 0o600);
    try { writeFileSync(fd, bytes); fsyncSync(fd); } finally { closeSync(fd); }
    const dir = openSync(parent, constants.O_RDONLY);
    try { fsyncSync(dir); } finally { closeSync(dir); }
    return { created: true, configuration };
  } catch { throw new PushRuntimeSetupError(); }
  finally { owned.forEach(value => value.fill(0)); }
}
