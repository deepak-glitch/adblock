'use strict';

const fs = require('fs');
const path = require('path');
const { parseFile } = require('./parser');
const logger = require('../logger');

const LISTS_DIR = path.join(__dirname, '../../lists');

// Shared state — accessed by DNS handler on every query (hot path)
let blockSet = new Set();
let allowSet = new Set();
let _stats = { totalDomains: 0, totalAllowed: 0, lastLoaded: null, listCounts: {} };

/**
 * Load all .txt files from lists/ subdirectories into the block/allow Sets.
 * This is the only function that mutates blockSet/allowSet.
 * It builds new sets and atomically swaps them in.
 */
async function loadAll() {
  const newBlock = new Set();
  const newAllow = new Set();
  const listCounts = {};

  // Load allowlist first (takes priority)
  const allowFile = path.join(LISTS_DIR, 'core', 'allowlist.txt');
  if (fs.existsSync(allowFile)) {
    const content = fs.readFileSync(allowFile, 'utf8');
    const { domains } = parseFile(content);
    domains.forEach(d => newAllow.add(d));
    listCounts['allowlist'] = domains.size;
  }

  // Walk all subdirectories under lists/
  const subdirs = fs.readdirSync(LISTS_DIR, { withFileTypes: true })
    .filter(e => e.isDirectory())
    .map(e => e.name);

  for (const subdir of subdirs) {
    const dirPath = path.join(LISTS_DIR, subdir);
    let files;
    try {
      files = fs.readdirSync(dirPath).filter(f => f.endsWith('.txt') && f !== 'allowlist.txt');
    } catch {
      continue;
    }
    for (const file of files) {
      const filePath = path.join(dirPath, file);
      try {
        const content = fs.readFileSync(filePath, 'utf8');
        const { domains, count } = parseFile(content);
        const key = `${subdir}/${file}`;
        listCounts[key] = count;
        domains.forEach(d => {
          if (!newAllow.has(d)) newBlock.add(d);
        });
        logger.debug(`Loaded ${count} domains from ${key}`);
      } catch (err) {
        logger.warn(`Failed to load ${file}: ${err.message}`);
      }
    }
  }

  // Atomic swap
  blockSet = newBlock;
  allowSet = newAllow;
  _stats = {
    totalDomains: newBlock.size,
    totalAllowed: newAllow.size,
    lastLoaded: new Date().toISOString(),
    listCounts,
  };

  logger.info(`Blocklists loaded: ${newBlock.size} blocked domains, ${newAllow.size} allowlisted domains`);
  return _stats;
}

/**
 * Check if a domain (or any of its parent domains) is blocked.
 * Allowlist takes priority.
 *
 * Example: 'a.b.ads.doubleclick.net'
 *   → check 'a.b.ads.doubleclick.net'
 *   → check 'b.ads.doubleclick.net'
 *   → check 'ads.doubleclick.net'  ← HIT
 */
function isBlocked(domain) {
  if (!domain) return false;
  const d = domain.toLowerCase().replace(/\.$/, ''); // strip trailing dot

  // Allowlist wins
  if (allowSet.has(d)) return false;

  // Walk up domain labels
  const labels = d.split('.');
  for (let i = 0; i < labels.length - 1; i++) {
    const candidate = labels.slice(i).join('.');
    if (allowSet.has(candidate)) return false;
    if (blockSet.has(candidate)) return true;
  }
  return false;
}

function getStats() {
  return { ..._stats };
}

function getBlockSetSize() {
  return blockSet.size;
}

/**
 * Add a single domain to the in-memory block set (custom user block).
 * Changes are in-memory only; caller is responsible for persisting if needed.
 */
function addBlock(domain) {
  blockSet.add(domain.toLowerCase());
}

/**
 * Add a single domain to the in-memory allow set (custom user allowlist).
 */
function addAllow(domain) {
  allowSet.add(domain.toLowerCase());
  blockSet.delete(domain.toLowerCase());
}

function removeBlock(domain) {
  blockSet.delete(domain.toLowerCase());
}

module.exports = { loadAll, isBlocked, getStats, getBlockSetSize, addBlock, addAllow, removeBlock };
