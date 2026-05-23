'use strict';

const dns2 = require('dns2');
const config = require('../config');
const logger = require('../logger');

const { UDPClient } = dns2;

// Create persistent upstream clients
let primaryClient = null;
let fallbackClient = null;

function getClients() {
  if (!primaryClient) {
    primaryClient = UDPClient({
      dns: config.dns.upstream,
      port: config.dns.upstreamPort,
    });
  }
  if (!fallbackClient) {
    fallbackClient = UDPClient({
      dns: config.dns.upstreamFallback,
      port: config.dns.upstreamPort,
    });
  }
  return { primaryClient, fallbackClient };
}

/**
 * DNS record type number → string name.
 * dns2 UDPClient.resolve() takes a string type.
 */
function typeToString(typeNum) {
  const map = {
    1: 'A',
    2: 'NS',
    5: 'CNAME',
    6: 'SOA',
    12: 'PTR',
    15: 'MX',
    16: 'TXT',
    28: 'AAAA',
    33: 'SRV',
    255: 'ANY',
  };
  return map[typeNum] || 'A';
}

/**
 * Forward a DNS request to the upstream resolver.
 * Returns the DNS response or throws on timeout/error.
 */
async function forward(request) {
  const question = request.questions[0];
  const name = question.name;
  const typeStr = typeToString(question.type);
  const start = Date.now();

  const { primaryClient: primary, fallbackClient: fallback } = getClients();

  // Race the primary with a timeout
  const timeout = new Promise((_, reject) =>
    setTimeout(() => reject(new Error('DNS upstream timeout')), config.dns.timeout)
  );

  let result;
  try {
    result = await Promise.race([primary(name, typeStr), timeout]);
    const latency = Date.now() - start;
    logger.debug(`Forwarded ${name} (${typeStr}) → ${latency}ms`);
    result._latency = latency;
    return result;
  } catch (primaryErr) {
    logger.debug(`Primary upstream failed for ${name}: ${primaryErr.message}, trying fallback`);
    try {
      result = await Promise.race([
        fallback(name, typeStr),
        new Promise((_, reject) => setTimeout(() => reject(new Error('Fallback timeout')), config.dns.timeout)),
      ]);
      result._latency = Date.now() - start;
      return result;
    } catch (fallbackErr) {
      throw new Error(`Both upstreams failed for ${name}: ${fallbackErr.message}`);
    }
  }
}

module.exports = { forward, typeToString };
