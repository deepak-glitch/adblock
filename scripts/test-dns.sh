#!/usr/bin/env node
// Stream AdBlock — DNS smoke test
// Usage: node scripts/test-dns.sh [host] [port]
//        bash scripts/test-dns.sh [host] [port]  (shebang handles it)

'use strict';

const dns2 = require(require('path').join(__dirname, '../node_modules/dns2'));

const HOST = process.argv[2] || '127.0.0.1';
const PORT = parseInt(process.argv[3]) || 5353;

const GREEN  = '\x1b[32m';
const RED    = '\x1b[31m';
const RESET  = '\x1b[0m';

let pass = 0, fail = 0;

const client = dns2.UDPClient({ dns: HOST, port: PORT });

async function check(domain, expectBlocked) {
  const label = expectBlocked ? 'BLOCKED' : 'ALLOWED';
  try {
    const result = await Promise.race([
      client(domain, 'A'),
      new Promise((_, r) => setTimeout(() => r(new Error('timeout')), 3000)),
    ]);
    const answers = result.answers || [];
    const ip = answers[0]?.address;
    const isBlocked = !ip || ip === '0.0.0.0' || answers.length === 0;

    if (isBlocked === expectBlocked) {
      const detail = isBlocked ? '(NXDOMAIN)' : `→ ${ip}`;
      console.log(`  ${GREEN}✓ ${label}${RESET}  ${domain}  ${detail}`);
      pass++;
    } else {
      console.log(`  ${RED}✗ Expected ${label} but got ${isBlocked ? 'BLOCKED' : 'ALLOWED'}${RESET}  ${domain}  ${ip || ''}`);
      fail++;
    }
  } catch (err) {
    // Timeout or NXDOMAIN error = blocked
    if (expectBlocked) {
      console.log(`  ${GREEN}✓ ${label}${RESET}  ${domain}  (NXDOMAIN)`);
      pass++;
    } else {
      console.log(`  ${RED}✗ Expected ${label} but got error:${RESET}  ${domain}  ${err.message}`);
      fail++;
    }
  }
}

(async () => {
  console.log(`\n🧪  Stream AdBlock DNS Test`);
  console.log(`    Server: ${HOST}:${PORT}\n`);

  console.log('━━━ Should be BLOCKED ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━');
  await check('imasdk.googleapis.com',           true);
  await check('googleads.g.doubleclick.net',     true);
  await check('ads-e-darwin.hulustream.com',     true);
  await check('ads.peacocktv.com',               true);
  await check('ads.paramount.com',               true);
  await check('ads.pluto.tv',                    true);
  await check('ads.adrise.tv',                   true);
  await check('moatads.com',                     true);
  await check('scorecardresearch.com',           true);
  await check('omtrdc.net',                      true);
  await check('demdex.net',                      true);
  await check('securepubads.g.doubleclick.net',  true);

  console.log('\n━━━ Should be ALLOWED ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━');
  await check('google.com',    false);
  await check('netflix.com',   false);
  await check('hulu.com',      false);
  await check('disneyplus.com',false);
  await check('youtube.com',   false);

  console.log('\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━');
  console.log(`Results: ${GREEN}${pass} passed${RESET}, ${fail > 0 ? RED : ''}${fail} failed${RESET}\n`);

  process.exit(fail > 0 ? 1 : 0);
})();
