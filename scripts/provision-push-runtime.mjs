#!/usr/bin/env node
import { provisionPushRuntime } from '../push/runtime-setup.mjs';
const mapping = { '--keys': 'keysDirectory', '--firebase': 'firebasePath', '--provider': 'providerPath',
  '--tls-key': 'tlsKeyPath', '--tls-cert': 'tlsCertPath', '--hostname': 'hostname', '--listen': 'listenHost',
  '--port': 'port', '--sealer': 'sealerExecutable', '--output': 'output' };
try {
  const args = process.argv.slice(2), input = {};
  if (args.length !== Object.keys(mapping).length * 2) throw Error();
  for (let index = 0; index < args.length; index += 2) {
    const field = mapping[args[index]];
    if (!field || Object.hasOwn(input, field)) throw Error();
    input[field] = field === 'port' ? (/^[1-9][0-9]*$/.test(args[index + 1]) ? Number(args[index + 1]) : NaN) : args[index + 1];
  }
  const result = provisionPushRuntime(input);
  console.log(result.created ? 'Private push runtime configured; no listener started' : 'Existing private push runtime verified and preserved');
} catch {
  console.error('Private push runtime setup unavailable. Check the selected private files, matching Firebase/provider project, valid public hostname certificate, executable and private output parent. Existing configuration is preserved.');
  process.exitCode = 1;
}
