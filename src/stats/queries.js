'use strict';

const { getDb } = require('./db');

/**
 * Summary stats for the last 24 hours.
 */
function getStats24h() {
  const db = getDb();
  const since = Date.now() - 24 * 60 * 60 * 1000;
  const row = db.prepare(`
    SELECT
      COUNT(*) AS total,
      SUM(CASE WHEN action = 'BLOCKED' THEN 1 ELSE 0 END) AS blocked,
      SUM(CASE WHEN action = 'ALLOWED' OR action = 'CACHED' THEN 1 ELSE 0 END) AS allowed,
      AVG(CASE WHEN latency_ms IS NOT NULL THEN latency_ms END) AS avgLatency
    FROM queries
    WHERE timestamp >= ?
  `).get(since);

  const total = row.total || 0;
  const blocked = row.blocked || 0;
  const allowed = row.allowed || 0;
  return {
    total,
    blocked,
    allowed,
    blockPercent: total > 0 ? Math.round((blocked / total) * 100) : 0,
    avgLatency: row.avgLatency ? Math.round(row.avgLatency) : null,
  };
}

/**
 * Hourly query counts for the last N hours (for line chart).
 */
function getQueryTimeline(hours = 24) {
  const db = getDb();
  const since = Date.now() - hours * 60 * 60 * 1000;
  const rows = db.prepare(`
    SELECT
      (timestamp / 3600000) * 3600000 AS hour_bucket,
      SUM(CASE WHEN action = 'BLOCKED' THEN 1 ELSE 0 END) AS blocked,
      SUM(CASE WHEN action != 'BLOCKED' THEN 1 ELSE 0 END) AS allowed
    FROM queries
    WHERE timestamp >= ?
    GROUP BY hour_bucket
    ORDER BY hour_bucket ASC
  `).all(since);

  return rows.map(r => ({
    time: new Date(r.hour_bucket).toISOString(),
    blocked: r.blocked,
    allowed: r.allowed,
  }));
}

/**
 * Top N most frequently blocked domains.
 */
function getTopBlocked(limit = 20) {
  const db = getDb();
  const since = Date.now() - 24 * 60 * 60 * 1000;
  return db.prepare(`
    SELECT domain, COUNT(*) AS count
    FROM queries
    WHERE action = 'BLOCKED' AND timestamp >= ?
    GROUP BY domain
    ORDER BY count DESC
    LIMIT ?
  `).all(since, limit);
}

/**
 * Top N client IPs by query volume.
 */
function getTopClients(limit = 10) {
  const db = getDb();
  const since = Date.now() - 24 * 60 * 60 * 1000;
  return db.prepare(`
    SELECT
      client_ip,
      COUNT(*) AS total,
      SUM(CASE WHEN action = 'BLOCKED' THEN 1 ELSE 0 END) AS blocked
    FROM queries
    WHERE timestamp >= ? AND client_ip IS NOT NULL
    GROUP BY client_ip
    ORDER BY total DESC
    LIMIT ?
  `).all(since, limit);
}

/**
 * Most recent N query log entries.
 */
function getRecentQueries(limit = 100) {
  const db = getDb();
  return db.prepare(`
    SELECT id, timestamp, domain, type, action, client_ip, latency_ms
    FROM queries
    ORDER BY timestamp DESC
    LIMIT ?
  `).all(limit);
}

/**
 * Total all-time query count (for uptime stats).
 */
function getAllTimeTotal() {
  const db = getDb();
  const row = db.prepare('SELECT COUNT(*) AS total FROM queries').get();
  return row.total;
}

module.exports = {
  getStats24h,
  getQueryTimeline,
  getTopBlocked,
  getTopClients,
  getRecentQueries,
  getAllTimeTotal,
};
