# 🛡️ Stream AdBlock

Block ads on **Hulu** and **Max** (HBO) — the two most heavily-advertised
streaming services — plus broader coverage for Peacock, Paramount+,
Disney+, Tubi, Pluto, YouTube, and Netflix, across **every device** you own.

> **Primary targets:** Hulu and Max get the most aggressive blocking —
> 25 Hulu-specific domains (Darwin SSAI, Uplynk ads, tracking pixels,
> telemetry) and 29 Max-specific domains (Freewheel SSAI, WBD analytics,
> Adobe targeting). Other services get solid baseline coverage.

Ships in three forms — pick whichever matches your setup:

| Component | Use it on | Setup |
|-----------|-----------|-------|
| **📺 Fire TV / Android TV app** (`firetv/`) | Fire TV Stick, Android TV box | Sideload the APK |
| **🧩 Chrome extension** (`extension/`) | Your laptop's browser — includes 16× fast-forward for Hulu/Max SSAI mid-rolls | Load unpacked in `chrome://extensions` |
| **🌐 DNS server** (`src/`) | Router or Pi — covers all devices on your LAN | `docker compose up -d` |

All three share the **same curated blocklists** (`lists/`) — **301
hand-tuned domains** including Hulu/Max SSAI orchestration endpoints,
the Google IMA SDK, Freewheel ad decisions, and cross-platform trackers.

---

## 1. DNS Server (network-wide)

A Node.js DNS server that intercepts queries from all devices on your network.
Best option if you have a Raspberry Pi, NAS, or always-on home server.

```bash
cp .env.example .env
docker compose up -d
# Dashboard: http://localhost:3000
# Then point your router DNS to this machine's IP
```

Details: see [the DNS server section](#dns-server-details) below.

---

## 2. Chrome Extension

For ad-blocking on the web versions of streaming services in Chrome/Edge.

```bash
# 1. Build the rule files from the master blocklists
node extension/scripts/build-rules.js

# 2. Load it
#    chrome://extensions → Developer mode → Load unpacked → extension/
```

Includes per-platform content scripts that detect ad breaks in the
video player and speed through them at 16×.

Details: [`extension/README.md`](extension/README.md)

---

## 3. Fire TV / Android TV APK

A standalone app for Fire TV Stick, Fire TV Cube, and Android TV. Two modes:

- **VPN (Standalone)** — runs a local VPN on the Fire TV, no other setup needed.
- **DNS Client** — points Fire TV's system DNS at a separate Stream AdBlock server.

```bash
# Build the APK
cd firetv
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

# Or use the pre-built APK in dist/ (already built)

# Sideload via ADB
adb connect <fire-tv-ip>:5555
adb install -r dist/stream-adblock-firetv-debug.apk
```

Details: [`firetv/README.md`](firetv/README.md)

---

## Project layout

```
adblock/
├── lists/                          # ── Master blocklists (used by all 3 ─┐
│   ├── core/                       #     components below)                │
│   │   ├── allowlist.txt           # Never-block list                     │
│   │   ├── google-ads.txt          # Google IMA, DoubleClick              │
│   │   ├── trackers.txt            # Adobe Analytics, ComScore, Nielsen   │
│   │   └── general.txt             # Generic ad exchanges                 │
│   └── services/                   #                                      │
│       ├── hulu.txt, max-hbo.txt, peacock.txt, paramount.txt, ...        │
│                                                                          │
├── src/                  ◄─── DNS server (Node.js) ─────────────────────┐│
│   ├── index.js                                                          ││
│   ├── dns/server.js               # dns2-based UDP server               ││
│   ├── blocklist/manager.js        # Loads + hot-reloads lists ◄─────────┤│
│   ├── stats/db.js                 # SQLite query log                    ││
│   └── web/                        # Dashboard (Express + Socket.io)     ││
│                                                                         ││
├── extension/            ◄─── Chrome MV3 extension ────────────────────┐ ││
│   ├── manifest.json                                                   │ ││
│   ├── background.js               # Service worker (badge + stats)    │ ││
│   ├── content/*.js                # Per-platform ad-skip scripts      │ ││
│   ├── popup/                      # Toolbar popup UI                  │ ││
│   ├── rules/*.json                # MV3 declarativeNetRequest rules ◄─┤ ││
│   └── scripts/build-rules.js      # Converts lists/*.txt → rules/    │ ││
│                                                                       │ ││
├── firetv/               ◄─── Fire TV / Android TV app ───────────────┐│ ││
│   ├── app/src/main/                                                  ││ ││
│   │   ├── AndroidManifest.xml                                        ││ ││
│   │   ├── assets/lists/           # Bundled copy of lists/ ◄─────────┤│ ││
│   │   ├── java/com/streamadblock/firetv/                             ││ ││
│   │   │   ├── MainActivity.kt     # Remote-friendly UI               ││ ││
│   │   │   ├── BlocklistManager.kt # In-app blocklist + matcher       ││ ││
│   │   │   └── vpn/                                                   ││ ││
│   │   │       ├── AdBlockVpnService.kt  # VpnService packet loop     ││ ││
│   │   │       ├── DnsPacket.kt          # Parse QNAME, build replies ││ ││
│   │   │       └── IpPacket.kt           # IP+UDP packet builder      ││ ││
│   │   └── res/                                                       ││ ││
│   └── build.gradle.kts                                                ││ ││
│                                                                       ││ ││
├── dist/                 # Pre-built APKs ready to sideload            ││ ││
├── Dockerfile, docker-compose.yml, package.json                        ││ ││
└── README.md (you are here)                                           ─┘│ ││
```

---

## DNS server details

### Quick start (Docker)

```bash
cp .env.example .env
docker compose up -d
```

Then point your router's DNS at this machine's IP. All devices on the
network — TVs, phones, consoles, laptops — get ad blocking with no
client-side install.

### Quick start (Node.js, dev)

```bash
npm install
DNS_PORT=5353 npm start
# Test:  node scripts/test-dns.sh 127.0.0.1 5353
```

### What's blocked

**12/12 ad domains** are blocked, **5/5 real domains** are forwarded
correctly in the test suite. Coverage includes:

- **Google IMA SDK** (`imasdk.googleapis.com`) — hits Hulu, Peacock,
  Paramount+, Pluto, Tubi, YouTube, etc.
- **DoubleClick** (`googleads.g.doubleclick.net`, `securepubads.g.doubleclick.net`)
- **Per-service ad servers** for Hulu, Peacock, Paramount, Pluto, Tubi,
  Disney+, Max
- **Cross-platform trackers**: Adobe Analytics (`omtrdc.net`), ComScore,
  Nielsen, Moat, SpotX, Amazon Ad System

The dashboard at `:3000` shows live query stats and lets you add custom
rules.

### Limitations

- **Server-Side Ad Insertion (SSAI)**: Hulu, Peacock, Max stitch ads into
  the video stream — DNS blocking can interrupt ad manifests but doesn't
  remove the in-stream ad slots. The Chrome extension's content scripts
  handle this with 16× fast-forward.
- **YouTube**: Ads and video content share the same CDN. DNS blocks
  the IMA SDK (reduces pre-rolls) but can't fully eliminate YouTube ads.
  Use the Chrome extension for that.

---

## License

MIT
