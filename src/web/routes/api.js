'use strict';

const express = require('express');
const router = express.Router();
const blocklist = require('../../blocklist/manager');
const updater = require('../../blocklist/updater');
const statsDb = require('../../stats/queries');

// ── Stats ──────────────────────────────────────────────────────────────────

router.get('/stats', (req, res) => {
  try {
    const stats = statsDb.getStats24h();
    const blocklistStats = blocklist.getStats();
    res.json({
      ...stats,
      blockedDomains: blocklist.getBlockSetSize(),
      lastUpdated: blocklistStats.lastLoaded,
    });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

router.get('/stats/timeline', (req, res) => {
  try {
    const hours = Math.min(parseInt(req.query.hours) || 24, 168);
    res.json(statsDb.getQueryTimeline(hours));
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

router.get('/stats/top-blocked', (req, res) => {
  try {
    const limit = Math.min(parseInt(req.query.limit) || 20, 100);
    res.json(statsDb.getTopBlocked(limit));
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

router.get('/stats/top-clients', (req, res) => {
  try {
    const limit = Math.min(parseInt(req.query.limit) || 10, 50);
    res.json(statsDb.getTopClients(limit));
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

router.get('/queries', (req, res) => {
  try {
    const limit = Math.min(parseInt(req.query.limit) || 100, 500);
    res.json(statsDb.getRecentQueries(limit));
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// ── Blocklist management ──────────────────────────────────────────────────

router.get('/blocklist', (req, res) => {
  res.json(blocklist.getStats());
});

router.post('/blocklist/reload', async (req, res) => {
  try {
    const stats = await blocklist.loadAll();
    res.json({ success: true, ...stats });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

router.post('/blocklist/update', async (req, res) => {
  try {
    const result = await updater.updateAll();
    res.json({ success: true, ...result });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

router.get('/blocklist/check', (req, res) => {
  const { domain } = req.query;
  if (!domain) return res.status(400).json({ error: 'domain parameter required' });
  const blocked = blocklist.isBlocked(domain);
  res.json({ domain, blocked });
});

// ── Custom rules ──────────────────────────────────────────────────────────

router.post('/custom/block', (req, res) => {
  const { domain } = req.body || {};
  if (!domain) return res.status(400).json({ error: 'domain required' });
  blocklist.addBlock(domain.toLowerCase().trim());
  res.json({ success: true, domain: domain.toLowerCase().trim() });
});

router.post('/custom/allow', (req, res) => {
  const { domain } = req.body || {};
  if (!domain) return res.status(400).json({ error: 'domain required' });
  blocklist.addAllow(domain.toLowerCase().trim());
  res.json({ success: true, domain: domain.toLowerCase().trim() });
});

router.delete('/custom/block/:domain', (req, res) => {
  blocklist.removeBlock(req.params.domain);
  res.json({ success: true });
});

module.exports = router;
