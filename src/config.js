'use strict';

require('dotenv').config({ path: '.env' });

module.exports = {
  dns: {
    port: parseInt(process.env.DNS_PORT) || 5353,
    address: process.env.DNS_BIND || '0.0.0.0',
    upstream: process.env.DNS_UPSTREAM || '8.8.8.8',
    upstreamPort: parseInt(process.env.DNS_UPSTREAM_PORT) || 53,
    upstreamFallback: process.env.DNS_UPSTREAM_FALLBACK || '8.8.4.4',
    // 'nxdomain' returns RCODE 3 (no such domain)
    // 'null_ip'  returns 0.0.0.0 A record (prevents TCP retry)
    blockMode: process.env.DNS_BLOCK_MODE || 'nxdomain',
    timeout: parseInt(process.env.DNS_TIMEOUT_MS) || 3000,
    cacheSize: parseInt(process.env.DNS_CACHE_SIZE) || 10000,
  },
  web: {
    port: parseInt(process.env.WEB_PORT) || 3000,
    address: process.env.WEB_BIND || '0.0.0.0',
  },
  db: {
    path: process.env.DB_PATH || './data/adblock.db',
    retentionDays: parseInt(process.env.DB_RETENTION_DAYS) || 30,
    batchSize: parseInt(process.env.DB_BATCH_SIZE) || 200,
    batchInterval: parseInt(process.env.DB_BATCH_MS) || 2000,
  },
  blocklist: {
    updateCron: process.env.BLOCKLIST_UPDATE_CRON || '0 3 * * *',
    remoteEnabled: process.env.BLOCKLIST_REMOTE !== 'false',
  },
  log: {
    level: process.env.LOG_LEVEL || 'info',
  },
};
