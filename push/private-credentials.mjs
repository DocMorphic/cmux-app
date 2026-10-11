import { constants, openSync, fstatSync, lstatSync, readSync, closeSync } from 'node:fs';
import { dirname, isAbsolute } from 'node:path';
import { serviceAccountTokens } from './fcm.mjs';

export class PushCredentialError extends Error {
  constructor() { super('Push credentials unavailable'); }
}

/** Explicit owner-selected private file. Never discover credentials from env,
 * gcloud defaults, the repository or a phone request. Caller owns returned bytes. */
export function readPrivatePushFile(path, maximum = 65_536) {
  let fd, buffer;
  try {
    if (!isAbsolute(path ?? '') || !Number.isSafeInteger(maximum) || maximum < 1 || maximum > 1_048_576)
      throw new PushCredentialError();
    const parent = lstatSync(dirname(path));
    if (!parent.isDirectory() || (process.platform !== 'win32' &&
        (parent.uid !== process.getuid() || (parent.mode & 0o077)))) throw new PushCredentialError();
    if (lstatSync(path).isSymbolicLink()) throw new PushCredentialError();
    fd = openSync(path, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
    const file = fstatSync(fd);
    if (!file.isFile() || file.nlink !== 1 || !file.size || file.size > maximum ||
        (process.platform !== 'win32' && (file.uid !== process.getuid() || (file.mode & 0o077))))
      throw new PushCredentialError();
    // Bound the read even if the file grows after fstat.
    buffer = Buffer.alloc(maximum + 1);
    let length = 0, count;
    while (length <= maximum && (count = readSync(fd, buffer, length, buffer.length - length, null)) !== 0) length += count;
    if (!length || length > maximum) throw new PushCredentialError();
    return Buffer.from(buffer.subarray(0, length));
  } catch { throw new PushCredentialError(); }
  finally { buffer?.fill(0); if (fd !== undefined) closeSync(fd); }
}

/** Explicit credential activation; validates the exact provisioned project and
 * service-account identity before creating the provider. No OAuth until get(). */
export function privateServiceAccountTokens({ path, projectID, accountEmail, ...options }) {
  let bytes;
  try {
    if (!/^[a-z][a-z0-9-]{4,28}[a-z0-9]$/.test(projectID ?? '') ||
        typeof accountEmail !== 'string' || !accountEmail.endsWith(`@${projectID}.iam.gserviceaccount.com`))
      throw new PushCredentialError();
    bytes = readPrivatePushFile(path, 16_384);
    const credentials = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
    if (credentials.project_id !== projectID || credentials.client_email !== accountEmail) throw new PushCredentialError();
    return serviceAccountTokens(credentials, options);
  } catch { throw new PushCredentialError(); }
  finally { bytes?.fill(0); }
}
