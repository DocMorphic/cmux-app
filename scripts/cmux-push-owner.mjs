#!/usr/bin/env node
import { requestPushOwner } from '../push/owner-control.mjs';

// An offer contains a short-lived pairing secret. This explicit owner's command
// returns it for copying/scanning; the helper never logs it automatically.
try {
  const [action, socketOption, path, value] = process.argv.slice(2);
  if (process.argv.length !== 6 || socketOption !== '--socket' || !['offer', 'cancel', 'resume'].includes(action)) throw Error();
  const result = await requestPushOwner({ path, action, value });
  process.stdout.write(JSON.stringify(result) + '\n');
} catch { process.stderr.write('Push owner control unavailable\n'); process.exitCode = 1; }
