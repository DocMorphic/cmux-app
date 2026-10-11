// Adversarial local sockets only: no credentials, provider or public listener.
import { createServer } from 'node:net';
import { chmodSync } from 'node:fs';
const [path, mode] = process.argv.slice(2);
const server = createServer(socket => {
  socket.on('error', () => {});
  if (mode === 'hold') return;
  socket.once('data', () => socket.end(mode === 'oversized' ? 'x'.repeat(32769) : '{"ok":false,"error":"unavailable"}\n'));
});
server.listen(path, () => { chmodSync(path, mode === 'public' ? 0o666 : 0o600); process.stdout.write('ready\n'); });
