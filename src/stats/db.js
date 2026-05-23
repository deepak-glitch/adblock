'use strict';

const Database = require('better-sqlite3');
const path = require('path');
const fs = require('fs');
const config = require('../config');
const logger = require('../logger');

let db = null;

function getDb() {
  if (!db) throw new Error('Database not initialized. Call init() first.');
  return db;
}

function init() {
  const dbPath = path.resolve(config.db.path);
  const dbDir = path.dirname(dbPath);
  if (!fs.existsSync(dbDir)) fs.mkdirSync(dbDir, { recursive: true });

  db = new Database(dbPath);
  db.pragma('journal_mode = WAL');  // Write-Ahead Logging for better concurrency
  db.pragma('synchronous = NORMAL');
  db.pragma('temp_store = MEMORY');
  db.pragma('mmap_size = 30000000'); // 30MB memory map

  db.exec(`
    CREATE TABLE IF NOT EXISTS queries (
      id         INTEGER PRIMARY KEY AUTOINCREMENT,
      timestamp  INTEGER NOT NULL,
      domain     TEXT    NOT NULL,
      type       TEXT    NOT NULL,
      action     TEXT    NOT NULL,
      client_ip  TEXT,
      latency_ms INTEGER
    );

    CREATE INDEX IF NOT EXISTS idx_queries_timestamp ON queries(timestamp);
    CREATE INDEX IF NOT EXISTS idx_queries_action    ON queries(action);
    CREATE INDEX IF NOT EXISTS idx_queries_domain    ON queries(domain);
  `);

  logger.info(`Database initialized at ${dbPath}`);
  return db;
}

function close() {
  if (db) {
    db.close();
    db = null;
  }
}

module.exports = { init, getDb, close };
