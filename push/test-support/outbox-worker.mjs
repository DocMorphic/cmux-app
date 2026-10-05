import { PushOutbox } from '../outbox.mjs';

const [directory, time, mode] = process.argv.slice(2);
const queue = new PushOutbox({ directory, key: Buffer.alloc(32, 17), now: () => Number(time), random: () => 0 });
let count = 0;
await queue.drain({ limit: 64, permits: () => true, sender: { send: async () => {
  if (mode === 'hang') {
    process.send('sending');
    await new Promise(() => { setInterval(() => {}, 1000); });
  }
  count++;
  await new Promise(resolve => setTimeout(resolve, 10));
  return { kind: 'accepted' };
} } });
queue.close(); process.send(count); process.disconnect();
