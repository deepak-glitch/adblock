// Stream AdBlock — Service Worker (MV3)
// Tracks blocked-request counts and exposes them to the popup.

const STORAGE_KEY = 'stats';

const defaultStats = {
  totalBlocked: 0,
  todayBlocked: 0,
  todayDate: new Date().toISOString().slice(0, 10),
  perSite: {}, // hostname -> count
  recentBlocked: [], // last 50 blocked URLs
};

async function getStats() {
  const data = await chrome.storage.local.get(STORAGE_KEY);
  return data[STORAGE_KEY] || { ...defaultStats };
}

async function setStats(stats) {
  await chrome.storage.local.set({ [STORAGE_KEY]: stats });
}

function rollDayIfNeeded(stats) {
  const today = new Date().toISOString().slice(0, 10);
  if (stats.todayDate !== today) {
    stats.todayDate = today;
    stats.todayBlocked = 0;
  }
  return stats;
}

function hostnameFromUrl(url) {
  try {
    return new URL(url).hostname;
  } catch {
    return null;
  }
}

// ── Listen for blocked requests ─────────────────────────────────────────────
// Note: requires "declarativeNetRequestFeedback" permission
chrome.declarativeNetRequest.onRuleMatchedDebug?.addListener(async (info) => {
  await onBlocked(info);
});

async function onBlocked(info) {
  const stats = rollDayIfNeeded(await getStats());
  stats.totalBlocked++;
  stats.todayBlocked++;

  const initiator = info.request?.initiator || info.request?.url;
  const host = hostnameFromUrl(initiator);
  if (host) {
    stats.perSite[host] = (stats.perSite[host] || 0) + 1;
  }

  const blockedUrl = info.request?.url;
  if (blockedUrl) {
    stats.recentBlocked.unshift({
      url: blockedUrl,
      onSite: host,
      time: Date.now(),
    });
    if (stats.recentBlocked.length > 50) stats.recentBlocked.length = 50;
  }

  await setStats(stats);
  updateBadge(stats.todayBlocked);
}

// ── Popup messaging ─────────────────────────────────────────────────────────
chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (msg.type === 'GET_STATS') {
    getStats().then((s) => sendResponse(rollDayIfNeeded(s)));
    return true; // async response
  }
  if (msg.type === 'RESET_STATS') {
    setStats({ ...defaultStats, todayDate: new Date().toISOString().slice(0, 10) })
      .then(() => sendResponse({ ok: true }));
    return true;
  }
  if (msg.type === 'GET_RULESET_INFO') {
    chrome.declarativeNetRequest.getEnabledRulesets().then((enabled) => {
      sendResponse({ enabled });
    });
    return true;
  }
  if (msg.type === 'TOGGLE_RULESET') {
    chrome.declarativeNetRequest.updateEnabledRulesets({
      [msg.enable ? 'enableRulesetIds' : 'disableRulesetIds']: [msg.rulesetId],
    }).then(() => sendResponse({ ok: true }));
    return true;
  }
});

// ── Badge ────────────────────────────────────────────────────────────────────
function updateBadge(count) {
  let text = '';
  if (count > 0) {
    text = count > 999 ? Math.floor(count / 1000) + 'k' : String(count);
  }
  chrome.action.setBadgeText({ text });
  chrome.action.setBadgeBackgroundColor({ color: '#667eea' });
}

// Initialize badge from storage on startup
getStats().then((s) => updateBadge(rollDayIfNeeded(s).todayBlocked));

// ── Install hook ─────────────────────────────────────────────────────────────
chrome.runtime.onInstalled.addListener(({ reason }) => {
  if (reason === 'install') {
    setStats({ ...defaultStats });
  }
});
