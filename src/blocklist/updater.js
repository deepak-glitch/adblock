'use strict';

const https = require('https');
const http = require('http');
const fs = require('fs');
const path = require('path');
const cron = require('node-cron');
const config = require('../config');
const sources = require('./sources');
const manager = require('./manager');
const logger = require('../logger');

const REMOTE_DIR = path.join(__dirname, '../../lists/remote');

/**
 * Download a URL to a local file path.
 */
function download(url, destPath) {
  return new Promise((resolve, reject) => {
    const proto = url.startsWith('https') ? https : http;
    const file = fs.createWriteStream(destPath);
    const req = proto.get(url, { timeout: 30000 }, (res) => {
      if (res.statusCode !== 200) {
        file.close();
        reject(new Error(`HTTP ${res.statusCode} for ${url}`));
        return;
      }
      res.pipe(file);
      file.on('finish', () => { file.close(); resolve(); });
    });
    req.on('error', (err) => { file.close(); fs.unlink(destPath, () => {}); reject(err); });
    req.on('timeout', () => { req.destroy(); reject(new Error(`Timeout fetching ${url}`)); });
  });
}

/**
 * Download all enabled remote sources and reload blocklists.
 */
async function updateAll() {
  logger.info('Updating remote blocklists...');
  let updated = 0;
  let failed = 0;

  // Ensure remote dir exists
  if (!fs.existsSync(REMOTE_DIR)) fs.mkdirSync(REMOTE_DIR, { recursive: true });

  for (const source of sources) {
    if (!source.enabled) continue;
    const destPath = path.join(REMOTE_DIR, `${source.name}.txt`);
    try {
      logger.debug(`Downloading ${source.name} from ${source.url}`);
      await download(source.url, destPath);
      updated++;
      logger.info(`Updated ${source.name}`);
    } catch (err) {
      failed++;
      logger.warn(`Failed to update ${source.name}: ${err.message}`);
    }
  }

  // Reload blocklists after download
  if (updated > 0) {
    await manager.loadAll();
    logger.info(`Remote update complete: ${updated} updated, ${failed} failed`);
  } else {
    logger.warn('No remote lists updated');
  }

  return { updated, failed };
}

/**
 * Schedule automatic daily updates.
 */
function schedule() {
  if (!config.blocklist.remoteEnabled) {
    logger.info('Remote blocklist updates disabled (BLOCKLIST_REMOTE=false)');
    return;
  }
  const cronExpr = config.blocklist.updateCron;
  if (!cron.validate(cronExpr)) {
    logger.warn(`Invalid cron expression: ${cronExpr}, using default '0 3 * * *'`);
  }
  cron.schedule(cronExpr, async () => {
    try {
      await updateAll();
    } catch (err) {
      logger.error('Scheduled blocklist update failed:', err);
    }
  });
  logger.info(`Blocklist auto-update scheduled: ${cronExpr}`);
}

module.exports = { updateAll, schedule };
