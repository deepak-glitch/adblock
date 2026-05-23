'use strict';

const $ = (id) => document.getElementById(id);

async function loadStats() {
  const stats = await chrome.runtime.sendMessage({ type: 'GET_STATS' });
  $('today-count').textContent = stats.todayBlocked.toLocaleString();
  $('total-count').textContent = stats.totalBlocked.toLocaleString();

  // Count for current tab's hostname
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (tab?.url) {
    try {
      const host = new URL(tab.url).hostname;
      $('site-count').textContent = (stats.perSite[host] || 0).toLocaleString();
    } catch { /* file:// etc. */ }
  }

  renderRecent(stats.recentBlocked);
}

function renderRecent(recent) {
  const el = $('recent');
  if (!recent || recent.length === 0) {
    el.innerHTML = '<div class="empty">No blocks yet on this session</div>';
    return;
  }
  el.innerHTML = recent.slice(0, 30).map((r) => {
    let domain = r.url;
    try { domain = new URL(r.url).hostname; } catch {}
    return `<div class="entry" title="${r.url}">${domain}</div>`;
  }).join('');
}

async function loadRulesetState() {
  const { enabled } = await chrome.runtime.sendMessage({ type: 'GET_RULESET_INFO' });
  document.querySelectorAll('input[data-ruleset]').forEach((input) => {
    input.checked = enabled.includes(input.dataset.ruleset);
  });
}

async function toggleRuleset(rulesetId, enable) {
  await chrome.runtime.sendMessage({ type: 'TOGGLE_RULESET', rulesetId, enable });
}

document.querySelectorAll('input[data-ruleset]').forEach((input) => {
  input.addEventListener('change', () => {
    toggleRuleset(input.dataset.ruleset, input.checked);
  });
});

$('reset-btn').addEventListener('click', async () => {
  if (confirm('Reset all counters?')) {
    await chrome.runtime.sendMessage({ type: 'RESET_STATS' });
    loadStats();
  }
});

// Refresh every 2 seconds while popup is open
loadStats();
loadRulesetState();
setInterval(loadStats, 2000);
