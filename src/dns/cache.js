'use strict';

const config = require('../config');

/**
 * Simple in-memory LRU DNS cache.
 * Key: 'domain:type'
 * Value: { response, expiresAt }
 *
 * Eviction: lazy (on get) + size cap (oldest-first on overflow).
 */
class DnsCache {
  constructor(maxSize = 10000) {
    this.maxSize = maxSize;
    this.cache = new Map(); // ordered by insertion time (oldest first)
  }

  _key(name, type) {
    return `${name.toLowerCase()}:${type}`;
  }

  get(name, type) {
    const key = this._key(name, type);
    const entry = this.cache.get(key);
    if (!entry) return null;
    if (Date.now() > entry.expiresAt) {
      this.cache.delete(key);
      return null;
    }
    // Move to end (most recently used)
    this.cache.delete(key);
    this.cache.set(key, entry);
    return entry.response;
  }

  set(name, type, response, ttlSeconds) {
    const key = this._key(name, type);
    const ttl = Math.max(ttlSeconds || 60, 10); // minimum 10s cache
    const expiresAt = Date.now() + ttl * 1000;

    // Remove existing entry if present
    this.cache.delete(key);

    // Evict oldest if at capacity
    if (this.cache.size >= this.maxSize) {
      const oldestKey = this.cache.keys().next().value;
      this.cache.delete(oldestKey);
    }

    this.cache.set(key, { response, expiresAt });
  }

  has(name, type) {
    return this.get(name, type) !== null;
  }

  clear() {
    this.cache.clear();
  }

  get size() {
    return this.cache.size;
  }

  getStats() {
    return {
      size: this.cache.size,
      maxSize: this.maxSize,
    };
  }
}

module.exports = new DnsCache(config.dns.cacheSize);
