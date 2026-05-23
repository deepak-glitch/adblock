# 🛡️ Stream AdBlock

A DNS-level ad blocker for streaming services — **Hulu, Netflix, Max (HBO), Peacock, Paramount+, Disney+, Tubi, Pluto TV, YouTube**, and more.

Works on **all devices** on your network: Smart TVs, Rokus, Fire Sticks, phones, game consoles, Chromecasts — anything that connects to your router.

## How it works

Stream AdBlock runs a DNS server that intercepts domain name lookups. When your TV asks "where is `imasdk.googleapis.com`?" (Google's ad server), the blocker responds instantly with "that domain doesn't exist" — so the ad never loads.

```
Your TV → DNS query for ads.hulu.com → Stream AdBlock → BLOCKED ✗
Your TV → DNS query for hulu.com     → Stream AdBlock → forwarded → 104.16.x.x ✓
```

---

## Quick Start (Docker — recommended)

```bash
# 1. Clone the repo
git clone https://github.com/deepak-glitch/adblock.git
cd adblock

# 2. Copy config (edit if needed)
cp .env.example .env

# 3. Start
docker compose up -d

# 4. Open dashboard
open http://localhost:3000
```

Then point your router's DNS to this machine's IP — all devices get ad blocking automatically.

---

## Quick Start (Node.js)

```bash
npm install
npm start
```

> **Linux note:** Binding port 53 requires root or `CAP_NET_BIND_SERVICE`.  
> For development, set `DNS_PORT=5353` in your `.env` to avoid this.

```bash
DNS_PORT=5353 npm start
# Then test: dig @127.0.0.1 -p 5353 imasdk.googleapis.com
```

---

## Point your devices to this DNS

### Option A — Router (recommended, covers ALL devices)

1. Log into your router admin page (usually `192.168.1.1` or `192.168.0.1`)
2. Find DNS settings (usually under "WAN" or "Internet" settings)
3. Set **Primary DNS** to this machine's local IP (e.g. `192.168.1.50`)
4. Set **Secondary DNS** to `8.8.8.8` (fallback if this server is down)
5. Save and reboot router

### Option B — Single device

**macOS:** System Preferences → Network → Advanced → DNS  
**Windows:** Control Panel → Network → IPv4 Properties → DNS  
**Android:** Wi-Fi → Long press → Modify → Advanced → DNS  
**iOS:** Settings → Wi-Fi → ⓘ → Configure DNS → Manual

---

## Dashboard

Open **http://[your-server-ip]:3000** to see:

- 📊 Real-time query stats (total, blocked, allowed, block rate)
- 📈 Timeline chart (blocked vs allowed over 24h)
- ⚡ Live feed of DNS queries
- 🏆 Top blocked domains
- 🔧 Domain lookup tool (check if any domain is blocked)
- ➕ Add custom block/allow rules

---

## What gets blocked

| Service | What's blocked |
|---------|----------------|
| **Hulu** | Ad manifest endpoints, tracking |
| **Peacock** | NBCUniversal ad servers, Auditude SSAI |
| **Paramount+** | CBS/Paramount ad servers, Viacom |
| **Pluto TV** | Pluto ad delivery and targeting |
| **Tubi** | Adrise ad network (Tubi's ad platform) |
| **Disney+** | Disney ad delivery, ESPN ads |
| **Max (HBO)** | Warner/HBO ad servers, Freewheel endpoints |
| **YouTube** | Google IMA SDK (pre-roll ads reduced) |
| **All services** | Google IMA SDK, DoubleClick, Adobe Analytics, Moat, Nielsen, Comscore, SpotX, and 20+ more ad networks |

### ⚠️ Limitations

- **Server-Side Ad Insertion (SSAI)**: Hulu and some others stitch ads into the video stream at the server level. DNS blocking interrupts the ad request but some buffering/pausing may occur between ad slots.
- **YouTube**: Ads and video content share the same CDN. DNS blocking reduces ads (via IMA SDK) but won't eliminate them entirely. Use uBlock Origin in your browser for YouTube.
- **Netflix**: Netflix's ad tier (Basic with Ads) is relatively new; its ad infrastructure is still evolving. Some ad domains may not yet be in the lists.

---

## Configuration

Edit `.env` (copy from `.env.example`):

```bash
DNS_PORT=53            # DNS listen port (use 5353 for dev without root)
DNS_UPSTREAM=8.8.8.8   # Upstream DNS for non-blocked queries
DNS_BLOCK_MODE=nxdomain # 'nxdomain' or 'null_ip'
WEB_PORT=3000          # Dashboard port
LOG_LEVEL=info         # error | warn | info | debug
```

---

## Custom rules

**Via dashboard:** Use the "Block a domain" and "Allow a domain" inputs.

**Via blocklist files:** Add domains to files in `lists/services/` or `lists/core/`.  
Run `npm run reload` or click "Reload Lists" in the dashboard to apply changes.

**Allowlist** (`lists/core/allowlist.txt`): Domains here are never blocked, regardless of other lists. Use this to un-block a domain that breaks a service.

---

## Remote blocklists

By default, Stream AdBlock uses only the curated local lists. To enable automatic downloads from community blocklists (HaGeZi, Steven Black):

```bash
# .env
BLOCKLIST_REMOTE=true
BLOCKLIST_UPDATE_CRON=0 3 * * *  # update daily at 3 AM
```

Or trigger manually from the dashboard → "Fetch Remote Lists".

Edit `src/blocklist/sources.js` to add/remove sources.

---

## Blocked domains count

| List | Domains |
|------|---------|
| Google Ads (core) | ~20 |
| Trackers (core) | ~40 |
| General ad networks | ~30 |
| All streaming services | ~80 |
| **Total (local lists)** | **~170** |
| + HaGeZi Normal (remote) | +~800,000 |
| + Steven Black (remote) | +~100,000 |

The local lists are hand-curated for zero false positives on streaming services. Remote lists add broad coverage.

---

## Testing

```bash
# Run the test suite (requires dig)
npm run test:dns

# Or with a custom host/port
bash scripts/test-dns.sh 127.0.0.1 5353
```

---

## Project structure

```
adblock/
├── src/
│   ├── index.js           # Entry point
│   ├── config.js          # Configuration
│   ├── logger.js          # Winston logger
│   ├── dns/
│   │   ├── server.js      # DNS server (dns2)
│   │   ├── resolver.js    # Upstream forwarding
│   │   └── cache.js       # LRU response cache
│   ├── blocklist/
│   │   ├── manager.js     # Load + hot-reload blocklists
│   │   ├── parser.js      # Parse hosts/plain/adblock formats
│   │   ├── updater.js     # Remote list downloader
│   │   └── sources.js     # Remote source registry
│   ├── stats/
│   │   ├── db.js          # SQLite setup
│   │   ├── recorder.js    # Batched query logging
│   │   └── queries.js     # Aggregation queries
│   └── web/
│       ├── server.js      # Express + Socket.io
│       ├── routes/api.js  # REST API
│       └── public/        # Dashboard (HTML/CSS/JS)
├── lists/
│   ├── core/              # Google ads, trackers, general, allowlist
│   └── services/          # Per-service lists (hulu, peacock, max, etc.)
├── Dockerfile
├── docker-compose.yml
└── .env.example
```

---

## License

MIT
