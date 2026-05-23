#!/usr/bin/env node
/**
 * Convert blocklists from ../lists/ into Chrome declarativeNetRequest rule files.
 *
 * Chrome MV3 rule format:
 *   { id: N, priority: 1, action: { type: 'block' }, condition: { urlFilter: '||domain.com^', resourceTypes: [...] } }
 *
 * Outputs three files:
 *   rules/streaming_rules.json  (per-service ad domains)
 *   rules/google_rules.json     (Google IMA, DoubleClick)
 *   rules/tracker_rules.json    (Adobe, ComScore, Nielsen, etc.)
 */

'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '../..');
const LISTS_DIR = path.join(ROOT, 'lists');
const OUT_DIR = path.join(__dirname, '../rules');

const RESOURCE_TYPES = [
  'main_frame', 'sub_frame', 'stylesheet', 'script', 'image',
  'font', 'object', 'xmlhttprequest', 'ping', 'media', 'websocket', 'other'
];

// Use the same parser as the DNS server
const { parseFile } = require(path.join(ROOT, 'src/blocklist/parser'));

function loadDomains(filePath) {
  if (!fs.existsSync(filePath)) return new Set();
  const content = fs.readFileSync(filePath, 'utf8');
  return parseFile(content).domains;
}

function loadAllowlist() {
  return loadDomains(path.join(LISTS_DIR, 'core', 'allowlist.txt'));
}

function domainsToRules(domains, startId, allowlist) {
  const rules = [];
  let id = startId;
  for (const domain of domains) {
    if (allowlist.has(domain)) continue;
    rules.push({
      id: id++,
      priority: 1,
      action: { type: 'block' },
      condition: {
        urlFilter: `||${domain}^`,
        resourceTypes: RESOURCE_TYPES,
      },
    });
  }
  return rules;
}

function build() {
  const allowlist = loadAllowlist();
  console.log(`Loaded allowlist: ${allowlist.size} domains`);

  // ── Streaming services ────────────────────────────────────────────────
  const streamingDomains = new Set();
  const servicesDir = path.join(LISTS_DIR, 'services');
  for (const file of fs.readdirSync(servicesDir)) {
    if (!file.endsWith('.txt')) continue;
    const domains = loadDomains(path.join(servicesDir, file));
    domains.forEach(d => streamingDomains.add(d));
  }
  const streamingRules = domainsToRules(streamingDomains, 1, allowlist);

  // ── Google ads ────────────────────────────────────────────────────────
  const googleDomains = loadDomains(path.join(LISTS_DIR, 'core', 'google-ads.txt'));
  const googleRules = domainsToRules(googleDomains, 100000, allowlist);

  // ── Trackers + general ────────────────────────────────────────────────
  const trackerDomains = new Set();
  loadDomains(path.join(LISTS_DIR, 'core', 'trackers.txt')).forEach(d => trackerDomains.add(d));
  loadDomains(path.join(LISTS_DIR, 'core', 'general.txt')).forEach(d => trackerDomains.add(d));
  const trackerRules = domainsToRules(trackerDomains, 200000, allowlist);

  // ── Write outputs ─────────────────────────────────────────────────────
  if (!fs.existsSync(OUT_DIR)) fs.mkdirSync(OUT_DIR, { recursive: true });

  fs.writeFileSync(
    path.join(OUT_DIR, 'streaming_rules.json'),
    JSON.stringify(streamingRules, null, 2)
  );
  fs.writeFileSync(
    path.join(OUT_DIR, 'google_rules.json'),
    JSON.stringify(googleRules, null, 2)
  );
  fs.writeFileSync(
    path.join(OUT_DIR, 'tracker_rules.json'),
    JSON.stringify(trackerRules, null, 2)
  );

  const total = streamingRules.length + googleRules.length + trackerRules.length;
  console.log('');
  console.log(`✅ Built Chrome MV3 rules:`);
  console.log(`   streaming_rules.json   ${streamingRules.length} rules`);
  console.log(`   google_rules.json      ${googleRules.length} rules`);
  console.log(`   tracker_rules.json     ${trackerRules.length} rules`);
  console.log(`   ─────────────────────────────────`);
  console.log(`   Total:                 ${total} rules`);
}

build();
