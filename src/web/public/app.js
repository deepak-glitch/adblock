/* Stream AdBlock Dashboard — Vanilla JS */
'use strict';

// ── Socket.io connection ─────────────────────────────────────────────────────
const socket = io();
const dot = document.getElementById('live-dot');

socket.on('connect', () => { dot.className = 'dot live'; });
socket.on('disconnect', () => { dot.className = 'dot'; });

// ── Stat card helpers ────────────────────────────────────────────────────────
function $(id) { return document.getElementById(id); }
function fmt(n) { return typeof n === 'number' ? n.toLocaleString() : '—'; }

// ── Fetch + render summary stats ─────────────────────────────────────────────
async function loadStats() {
  try {
    const r = await fetch('/api/stats');
    const d = await r.json();
    $('stat-total').textContent    = fmt(d.total);
    $('stat-blocked').textContent  = fmt(d.blocked);
    $('stat-allowed').textContent  = fmt(d.allowed);
    $('stat-percent').textContent  = (d.blockPercent || 0) + '%';
    $('stat-domains').textContent  = fmt(d.blockedDomains);
    $('stat-latency').textContent  = d.avgLatency ? d.avgLatency + 'ms' : '—';
  } catch (e) {
    console.error('Failed to load stats', e);
  }
}

// ── Timeline chart ───────────────────────────────────────────────────────────
let chart = null;

async function loadChart() {
  try {
    const r = await fetch('/api/stats/timeline');
    const data = await r.json();
    const labels = data.map(d => {
      const t = new Date(d.time);
      return t.getHours() + ':00';
    });
    const blocked = data.map(d => d.blocked);
    const allowed = data.map(d => d.allowed);

    if (chart) {
      chart.data.labels = labels;
      chart.data.datasets[0].data = blocked;
      chart.data.datasets[1].data = allowed;
      chart.update('none');
      return;
    }

    const ctx = $('timeline-chart').getContext('2d');
    chart = new Chart(ctx, {
      type: 'line',
      data: {
        labels,
        datasets: [
          {
            label: 'Blocked',
            data: blocked,
            borderColor: '#f56565',
            backgroundColor: 'rgba(245,101,101,0.15)',
            fill: true,
            tension: 0.4,
            pointRadius: 2,
          },
          {
            label: 'Allowed',
            data: allowed,
            borderColor: '#48bb78',
            backgroundColor: 'rgba(72,187,120,0.10)',
            fill: true,
            tension: 0.4,
            pointRadius: 2,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        animation: false,
        scales: {
          x: {
            ticks: { color: '#8892a4', maxTicksLimit: 8, font: { size: 11 } },
            grid: { color: 'rgba(255,255,255,0.04)' },
          },
          y: {
            ticks: { color: '#8892a4', font: { size: 11 } },
            grid: { color: 'rgba(255,255,255,0.04)' },
          },
        },
        plugins: {
          legend: { labels: { color: '#e2e8f0', font: { size: 12 } } },
        },
      },
    });
  } catch (e) {
    console.error('Failed to load chart', e);
  }
}

// ── Top blocked domains table ─────────────────────────────────────────────────
async function loadTopBlocked() {
  try {
    const r = await fetch('/api/stats/top-blocked?limit=15');
    const data = await r.json();
    const tbody = $('top-blocked-body');
    tbody.innerHTML = data.map(row => `
      <tr>
        <td class="domain-cell" title="${row.domain}">${row.domain}</td>
        <td class="count">${fmt(row.count)}</td>
      </tr>
    `).join('') || '<tr><td colspan="2" style="color:var(--muted);text-align:center;padding:20px">No blocked queries yet</td></tr>';
  } catch (e) {
    console.error('Failed to load top blocked', e);
  }
}

// ── Top clients table ────────────────────────────────────────────────────────
async function loadTopClients() {
  try {
    const r = await fetch('/api/stats/top-clients');
    const data = await r.json();
    const tbody = $('top-clients-body');
    tbody.innerHTML = data.map(row => `
      <tr>
        <td class="domain-cell">${row.client_ip || 'unknown'}</td>
        <td class="count">${fmt(row.total)}</td>
        <td style="color:var(--blocked)">${fmt(row.blocked)}</td>
      </tr>
    `).join('') || '<tr><td colspan="3" style="color:var(--muted);text-align:center;padding:20px">No data yet</td></tr>';
  } catch (e) {
    console.error('Failed to load top clients', e);
  }
}

// ── Live feed ────────────────────────────────────────────────────────────────
const feedEl = $('live-feed');
const MAX_FEED = 200;

function addFeedEntry(event) {
  const t = new Date(event.timestamp);
  const time = t.getHours().toString().padStart(2,'0') + ':' +
               t.getMinutes().toString().padStart(2,'0') + ':' +
               t.getSeconds().toString().padStart(2,'0');
  const actionClass = event.action.toLowerCase();
  const entry = document.createElement('div');
  entry.className = `feed-entry ${actionClass}`;
  entry.innerHTML = `
    <span class="time">${time}</span>
    <span class="action">${event.action}</span>
    <span class="domain" title="${event.domain}">${event.domain}</span>
    <span class="client">${event.clientIp || ''}</span>
  `;
  feedEl.prepend(entry);

  // Prune old entries
  while (feedEl.children.length > MAX_FEED) {
    feedEl.removeChild(feedEl.lastChild);
  }
}

socket.on('query', addFeedEntry);

// Refresh stats and chart every 30s
let statsInterval = setInterval(() => {
  loadStats();
  loadChart();
  loadTopBlocked();
  loadTopClients();
}, 30000);

// ── Domain lookup tool ───────────────────────────────────────────────────────
$('check-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const domain = $('check-input').value.trim();
  if (!domain) return;
  const resultEl = $('check-result');
  resultEl.className = 'result-box';
  resultEl.textContent = 'Checking...';
  try {
    const r = await fetch('/api/blocklist/check?domain=' + encodeURIComponent(domain));
    const d = await r.json();
    if (d.blocked) {
      resultEl.className = 'result-box blocked-result';
      resultEl.textContent = `🚫 BLOCKED: ${domain}`;
    } else {
      resultEl.className = 'result-box allowed-result';
      resultEl.textContent = `✅ ALLOWED: ${domain}`;
    }
  } catch (err) {
    resultEl.textContent = 'Error: ' + err.message;
  }
});

// ── Custom block form ────────────────────────────────────────────────────────
$('block-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const domain = $('block-input').value.trim();
  if (!domain) return;
  try {
    const r = await fetch('/api/custom/block', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ domain }),
    });
    const d = await r.json();
    if (d.success) {
      $('block-result').className = 'result-box blocked-result';
      $('block-result').textContent = `🚫 Now blocking: ${domain}`;
      $('block-input').value = '';
    }
  } catch (err) {
    $('block-result').textContent = 'Error: ' + err.message;
  }
});

// ── Custom allow form ────────────────────────────────────────────────────────
$('allow-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const domain = $('allow-input').value.trim();
  if (!domain) return;
  try {
    const r = await fetch('/api/custom/allow', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ domain }),
    });
    const d = await r.json();
    if (d.success) {
      $('allow-result').className = 'result-box allowed-result';
      $('allow-result').textContent = `✅ Now allowing: ${domain}`;
      $('allow-input').value = '';
    }
  } catch (err) {
    $('allow-result').textContent = 'Error: ' + err.message;
  }
});

// ── Blocklist actions ─────────────────────────────────────────────────────────
$('btn-reload').addEventListener('click', async () => {
  $('btn-reload').textContent = 'Reloading...';
  try {
    const r = await fetch('/api/blocklist/reload', { method: 'POST' });
    const d = await r.json();
    $('btn-reload').textContent = '↺ Reload Lists';
    alert(`Reloaded! ${d.totalDomains?.toLocaleString()} domains blocked`);
    loadStats();
  } catch (err) {
    $('btn-reload').textContent = '↺ Reload Lists';
    alert('Error: ' + err.message);
  }
});

$('btn-update').addEventListener('click', async () => {
  $('btn-update').textContent = 'Updating...';
  try {
    const r = await fetch('/api/blocklist/update', { method: 'POST' });
    const d = await r.json();
    $('btn-update').textContent = '⬇ Fetch Remote Lists';
    alert(`Update complete! ${d.updated} lists updated, ${d.failed} failed`);
    loadStats();
  } catch (err) {
    $('btn-update').textContent = '⬇ Fetch Remote Lists';
    alert('Error: ' + err.message);
  }
});

// ── Initial load ─────────────────────────────────────────────────────────────
loadStats();
loadChart();
loadTopBlocked();
loadTopClients();
