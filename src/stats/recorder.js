'use strict';

const { getDb } = require('./db');
const config = require('../config');
const logger = require('../logger');

let buffer = [];
let flushTimer = null;
let insertStmt = null;

function getInsertStmt() {
  if (!insertStmt) {
    insertStmt = getDb().prepare(
      `INSERT INTO queries (timestamp, domain, type, action, client_ip, latency_ms)
       VALUES (@timestamp, @domain, @type, @action, @clientIp, @latency)`
    );
  }
  return insertStmt;
}

/**
 * Record a DNS query event. Buffered for batch insertion.
 */
function record(event) {
  buffer.push({
    timestamp: event.timestamp || Date.now(),
    domain: event.domain,
    type: event.type || 'A',
    action: event.action,
    clientIp: event.clientIp || null,
    latency: event.latency || null,
  });

  if (buffer.length >= config.db.batchSize) {
    flush();
  } else if (!flushTimer) {
    flushTimer = setTimeout(flush, config.db.batchInterval);
  }
}

function flush() {
  if (flushTimer) {
    clearTimeout(flushTimer);
    flushTimer = null;
  }
  if (buffer.length === 0) return;

  const batch = buffer.splice(0, buffer.length);
  try {
    const db = getDb();
    const insert = getInsertStmt();
    const insertMany = db.transaction((rows) => {
      for (const row of rows) insert.run(row);
    });
    insertMany(batch);
  } catch (err) {
    logger.error(`Failed to write ${batch.length} query records:`, err);
  }
}

/**
 * Delete records older than retention period. Call daily.
 */
function purgeOld() {
  try {
    const db = getDb();
    const cutoff = Date.now() - config.db.retentionDays * 24 * 60 * 60 * 1000;
    const result = db.prepare('DELETE FROM queries WHERE timestamp < ?').run(cutoff);
    if (result.changes > 0) {
      logger.info(`Purged ${result.changes} query records older than ${config.db.retentionDays} days`);
    }
  } catch (err) {
    logger.error('Failed to purge old records:', err);
  }
}

function shutdown() {
  flush(); // Final flush on graceful shutdown
}

module.exports = { record, flush, purgeOld, shutdown };
