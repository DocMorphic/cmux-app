#!/usr/bin/env node
import { provisionPushOwnerKeys } from '../push/owner-keys.mjs';
const args = process.argv.slice(2);
try {
  if (args.length !== 2 || args[0] !== '--directory') throw Error();
  const result = provisionPushOwnerKeys(args[1]);
  // No private key or credential contents are printed.
  console.log(result.created ? 'Private push owner keys provisioned' : 'Existing private push owner keys verified and preserved');
} catch {
  console.error('Private push owner keys unavailable; select an absolute private directory with a private owned parent. Existing incomplete material is preserved.');
  process.exitCode = 1;
}
