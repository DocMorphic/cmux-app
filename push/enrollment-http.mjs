import { createServer } from 'node:https';
import { PushEnrollmentError } from './enrollment.mjs';

const MAX_BODY = 32_768;
class HttpFailure extends Error { constructor(status) { super('Enrollment request rejected'); this.status = status; } }
const requireHttp = (condition, status) => { if (!condition) throw new HttpFailure(status); };

/** Returns an unbound HTTPS server. Enrollment/maintenance challenge steps only; offers/cancel stay host-local. */
export function createPushEnrollmentServer({ key, cert, enrollment, maintenance = null, endpoint, maxConcurrent = 8, requestTimeoutMs = 10_000 }) {
  if (!key || !cert || !enrollment || typeof endpoint !== 'function' || !Number.isInteger(maxConcurrent) ||
      maxConcurrent < 1 || maxConcurrent > 64 || !Number.isInteger(requestTimeoutMs) || requestTimeoutMs < 100 || requestTimeoutMs > 30_000)
    throw new TypeError('Invalid enrollment server configuration');
  let active = 0;
  const server = createServer({ key, cert, minVersion: 'TLSv1.2', maxHeaderSize: 8192, handshakeTimeout: requestTimeoutMs, connectionsCheckingInterval: 1000 }, async (request, response) => {
    let timer; let owned = false; let retired = false; let dispatching = false;
    const release = () => { if (owned) { active--; owned = false; } };
    const send = (status, body) => {
      if (response.destroyed || response.writableEnded) return;
      response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store',
        'X-Content-Type-Options': 'nosniff', 'Connection': 'close', ...(status === 429 || status === 503 ? { 'Retry-After': '5' } : {}) });
      response.end(JSON.stringify(body));
    };
    response.once('close', () => { retired = true; if (!dispatching) { release(); request.destroy(); } });
    try {
      requireHttp(request.socket.encrypted === true, 403);
      const expected = new URL(endpoint());
      requireHttp(expected.protocol === 'https:' && !expected.username && !expected.password && !expected.search && !expected.hash &&
        expected.pathname === '/v1/push/enroll', 503);
      requireHttp(request.headers.host === expected.host, 421);
      requireHttp(request.url === expected.pathname, 404);
      requireHttp(request.method === 'POST', 405);
      requireHttp(!request.headers.origin, 403);
      requireHttp(/^application\/json(?:\s*;\s*charset=utf-8)?$/i.test(request.headers['content-type'] ?? ''), 415);
      requireHttp(!request.headers['content-encoding'] || request.headers['content-encoding'] === 'identity', 415);
      requireHttp(active < maxConcurrent, 429); active++; owned = true;
      timer = setTimeout(() => {
        retired = true;
        // An incomplete body cannot reach dispatch after this point. Release its
        // slot now and close the reader after the timeout response has flushed.
        if (!dispatching) { release(); response.once('finish', () => request.destroy()); }
        send(408, { error: 'request-timeout' });
      }, requestTimeoutMs);
      const length = request.headers['content-length'];
      requireHttp(length === undefined || (/^\d+$/.test(length) && Number(length) <= MAX_BODY), 413);
      let size = 0; const chunks = [];
      for await (const chunk of request.iterator({ destroyOnReturn: false })) {
        if (retired) throw new HttpFailure(408);
        size += chunk.length; requireHttp(size <= MAX_BODY, 413); chunks.push(chunk);
      }
      requireHttp(!retired, 408);
      let body;
      try { body = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks))); }
      catch { throw new HttpFailure(400); }
      requireHttp(body && typeof body === 'object' && !Array.isArray(body) && Object.keys(body).length === 2 &&
        ['begin', 'finish', ...(maintenance ? ['maintain.begin', 'maintain.finish', 'maintain.abort'] : [])].includes(body.step) && Object.hasOwn(body, 'request'), 400);
      // Finish is deliberately synchronous through the registration commit. A lost
      // response remains an uncertain outcome; exact protocol retry recovers its receipt.
      dispatching = true;
      const service = body.step.startsWith('maintain.') ? maintenance : enrollment;
      const result = body.step.endsWith('begin') ? await service.begin(body.request) :
        body.step === 'maintain.abort' ? service.abort(body.request) : service.finish(body.request);
      requireHttp(!retired, 408);
      requireHttp(Buffer.byteLength(JSON.stringify(result)) <= MAX_BODY, 503);
      send(200, result);
    } catch (error) {
      const status = error instanceof HttpFailure ? error.status : error instanceof PushEnrollmentError ?
        error.kind === 'superseded' ? 409 : error.kind === 'challenge-required' ? 428 : error.kind === 'registration-unavailable' ? 503 : 403 : 503;
      send(status, { error: status === 503 ? 'temporarily-unavailable' : 'request-rejected' });
    } finally { clearTimeout(timer); release(); }
  });
  server.maxConnections = maxConcurrent * 8;
  server.requestTimeout = requestTimeoutMs;
  server.headersTimeout = Math.min(requestTimeoutMs, 5000);
  server.keepAliveTimeout = 1000;
  server.on('clientError', (_error, socket) => { if (socket.writable) socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n'); });
  return server;
}
