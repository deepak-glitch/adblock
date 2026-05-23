'use strict';

const DOMAIN_RE = /^[a-z0-9]([a-z0-9\-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9\-]{0,61}[a-z0-9])?)+$/i;
const IP_RE = /^(?:\d{1,3}\.){3}\d{1,3}$|^::1$|^0\.0\.0\.0$/;

/**
 * Parse a single line from a blocklist file.
 * Supports:
 *   - Hosts format:    0.0.0.0 domain.com  or  127.0.0.1 domain.com
 *   - Adblock syntax:  ||domain.com^
 *   - Plain domain:    domain.com
 *   - Allowlist:       ! domain.com  or  @@ domain.com  (returns null — caller skips)
 *
 * Returns lowercase domain string, or null if line should be skipped.
 */
function parseLine(line) {
  line = line.trim();

  // Skip empty lines and comments
  if (!line || line.startsWith('#') || line.startsWith(';')) return null;

  // Allowlist markers — treat as skip
  if (line.startsWith('!') || line.startsWith('@@')) return null;

  // Adblock syntax: ||ads.example.com^...
  if (line.startsWith('||')) {
    const domain = line.slice(2).replace(/[\^\/].*$/, '').toLowerCase();
    return isValidDomain(domain) ? domain : null;
  }

  // Hosts file: 0.0.0.0 domain.com  or  127.0.0.1 domain.com
  const parts = line.split(/\s+/);
  if (parts.length >= 2 && IP_RE.test(parts[0])) {
    const domain = parts[1].toLowerCase();
    // Skip localhost entries
    if (domain === 'localhost' || domain === 'localhost.localdomain') return null;
    return isValidDomain(domain) ? domain : null;
  }

  // Plain domain (single token)
  if (parts.length === 1) {
    const domain = parts[0].toLowerCase();
    return isValidDomain(domain) ? domain : null;
  }

  return null;
}

function isValidDomain(domain) {
  return DOMAIN_RE.test(domain) && domain.length <= 253;
}

/**
 * Parse an entire blocklist file content.
 * Returns { domains: Set<string>, count: number, skipped: number }
 */
function parseFile(content) {
  const domains = new Set();
  let skipped = 0;
  for (const line of content.split('\n')) {
    const domain = parseLine(line);
    if (domain) {
      domains.add(domain);
    } else {
      skipped++;
    }
  }
  return { domains, count: domains.size, skipped };
}

module.exports = { parseLine, parseFile, isValidDomain };
