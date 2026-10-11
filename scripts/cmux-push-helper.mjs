#!/usr/bin/env node
import { NativePushHelperRuntime } from '../push/helper-runtime.mjs';
import { fstatSync } from 'node:fs';

// stdout is exclusively the native owner's private receipt pipe.
const args = process.argv.slice(2);
let runtime;
const stop = () => { void runtime?.stop(); };
process.stdin.on('error', stop); process.stdout.on('error', stop);
process.on('SIGTERM', stop); process.on('SIGINT', stop);
try {
  if (args.length !== 2 || args[0] !== '--config') throw Error('invalid configuration');
  if ([0, 1].some(fd => { const stat = fstatSync(fd); return !stat.isFIFO() && !stat.isSocket(); })) throw Error('private pipes required');
  runtime = new NativePushHelperRuntime({ configurationPath: args[1], input: process.stdin, output: process.stdout });
  await runtime.start(); await runtime.finished;
} catch {
  await runtime?.stop(); process.stderr.write('Push helper unavailable\n'); process.exitCode = 1;
} finally {
  process.stdin.destroy();
  process.off('SIGTERM', stop); process.off('SIGINT', stop);
}
