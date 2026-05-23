'use strict';

const dns2 = require('dns2');
const { Packet } = dns2;
const EventEmitter = require('events');

const config = require('../config');
const blocklist = require('../blocklist/manager');
const resolver = require('./resolver');
const cache = require('./cache');
const logger = require('../logger');

// Internal event bus — web server subscribes to 'blocked' and 'allowed' events
const events = new EventEmitter();
events.setMaxListeners(50);

/**
 * Build a blocked response for a query.
 * - 'nxdomain': RCODE 3 (NXDOMAIN) — domain does not exist
 * - 'null_ip':  0.0.0.0 A record — avoids TCP retry on some clients
 */
function buildBlockedResponse(request) {
  const response = Packet.createResponseFromRequest(request);
  const question = request.questions[0];

  if (config.dns.blockMode === 'null_ip') {
    response.answers.push({
      name: question.name,
      type: Packet.TYPE.A,
      class: Packet.CLASS.IN,
      ttl: 3600, // long TTL so client caches the block
      address: '0.0.0.0',
    });
  } else {
    // NXDOMAIN
    response.header.rcode = 3;
  }

  return response;
}

/**
 * Core DNS query handler.
 * Called by dns2 for every incoming query.
 */
async function handleQuery(request, send, rinfo) {
  const question = request.questions[0];
  if (!question) {
    // Malformed query
    const response = Packet.createResponseFromRequest(request);
    response.header.rcode = 1; // FORMERR
    send(response);
    return;
  }

  const domain = question.name.toLowerCase().replace(/\.$/, '');
  const typeNum = question.type;
  const clientIp = rinfo ? rinfo.address : 'unknown';

  // ── BLOCKED ──────────────────────────────────────────────────────────────
  if (blocklist.isBlocked(domain)) {
    const response = buildBlockedResponse(request);
    send(response);

    const event = { domain, type: resolver.typeToString(typeNum), clientIp, timestamp: Date.now(), action: 'BLOCKED' };
    events.emit('query', event);
    logger.debug(`BLOCKED  ${domain} (${resolver.typeToString(typeNum)}) from ${clientIp}`);
    return;
  }

  // ── CACHE HIT ─────────────────────────────────────────────────────────────
  const cached = cache.get(domain, typeNum);
  if (cached) {
    // Rebuild response from cached answers with a fresh request ID
    const response = Packet.createResponseFromRequest(request);
    response.answers = cached.answers || [];
    response.authorities = cached.authorities || [];
    response.additionals = cached.additionals || [];
    send(response);

    const event = { domain, type: resolver.typeToString(typeNum), clientIp, timestamp: Date.now(), action: 'CACHED', latency: 0 };
    events.emit('query', event);
    return;
  }

  // ── FORWARD TO UPSTREAM ───────────────────────────────────────────────────
  try {
    const upstream = await resolver.forward(request);
    const response = Packet.createResponseFromRequest(request);
    response.answers = upstream.answers || [];
    response.authorities = upstream.authorities || [];
    response.additionals = upstream.additionals || [];
    send(response);

    // Cache using minimum TTL from answers
    const ttl = upstream.answers && upstream.answers.length > 0
      ? Math.min(...upstream.answers.map(a => a.ttl || 60))
      : 60;
    cache.set(domain, typeNum, upstream, ttl);

    const event = {
      domain,
      type: resolver.typeToString(typeNum),
      clientIp,
      timestamp: Date.now(),
      action: 'ALLOWED',
      latency: upstream._latency,
    };
    events.emit('query', event);
    logger.debug(`ALLOWED  ${domain} (${resolver.typeToString(typeNum)}) from ${clientIp} → ${upstream._latency}ms`);

  } catch (err) {
    logger.warn(`Failed to forward query for ${domain}: ${err.message}`);
    // Return SERVFAIL
    const response = Packet.createResponseFromRequest(request);
    response.header.rcode = 2; // SERVFAIL
    send(response);
  }
}

let dnsServer = null;

function start() {
  return new Promise((resolve, reject) => {
    dnsServer = dns2.createServer({ udp: true, handle: handleQuery });

    dnsServer.on('error', (err) => {
      logger.error('DNS server error:', err);
      reject(err);
    });

    dnsServer.listen({
      udp: { port: config.dns.port, address: config.dns.address },
    });

    // dns2 listen is synchronous for UDP; emit resolve after a tick
    setImmediate(() => {
      logger.info(`DNS server listening on ${config.dns.address}:${config.dns.port}/udp (block mode: ${config.dns.blockMode})`);
      resolve(dnsServer);
    });
  });
}

function stop() {
  return new Promise((resolve) => {
    if (dnsServer) {
      dnsServer.close(() => resolve());
    } else {
      resolve();
    }
  });
}

module.exports = { start, stop, events };
