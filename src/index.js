'use strict';

const logger = require('./logger');
const config = require('./config');
const db = require('./stats/db');
const recorder = require('./stats/recorder');
const blocklist = require('./blocklist/manager');
const updater = require('./blocklist/updater');
const dnsServer = require('./dns/server');
const webServer = require('./web/server');
const cron = require('node-cron');

async function main() {
  logger.info('🛡️  Stream AdBlock starting...');
  logger.info(`Block mode: ${config.dns.blockMode} | DNS port: ${config.dns.port} | Dashboard port: ${config.web.port}`);

  // 1. Initialize database
  db.init();

  // 2. Schedule daily DB purge
  cron.schedule('0 4 * * *', () => recorder.purgeOld());

  // 3. Load blocklists from disk
  const blStats = await blocklist.loadAll();
  logger.info(`Loaded ${blStats.totalDomains.toLocaleString()} blocked domains`);

  // 4. Start web dashboard
  await webServer.start(dnsServer.events);

  // 5. Wire DNS events → stats recorder
  dnsServer.events.on('query', (event) => {
    recorder.record(event);
  });

  // 6. Start DNS server
  await dnsServer.start();

  // 7. Schedule remote blocklist updates
  updater.schedule();

  logger.info('✅ Stream AdBlock is running!');
  logger.info(`📊 Dashboard: http://localhost:${config.web.port}`);
  logger.info(`🔧 To use: set your DNS server to this machine's IP address`);
  logger.info(`🧪 Test:    dig @127.0.0.1 -p ${config.dns.port} imasdk.googleapis.com`);
}

// Graceful shutdown
async function shutdown(signal) {
  logger.info(`Received ${signal}, shutting down gracefully...`);
  recorder.shutdown(); // flush pending DB writes
  await dnsServer.stop();
  await webServer.stop();
  db.close();
  logger.info('Shutdown complete');
  process.exit(0);
}

process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));
process.on('unhandledRejection', (err) => {
  logger.error('Unhandled rejection:', err);
});

main().catch((err) => {
  logger.error('Startup failed:', err);
  process.exit(1);
});
