# 🛡️ Stream AdBlock — Fire TV App

A standalone Android TV / Fire TV app that blocks ads at the DNS level. Two modes:

| Mode | How it works | Setup |
|------|--------------|-------|
| **VPN (Standalone)** | App runs a local VPN that intercepts all DNS queries on the device. No external server required. | Tap "Start" → grant VPN permission once. |
| **DNS Client** | Fire TV's system DNS is pointed at your separate Stream AdBlock server. App is just a status dashboard. | Set Fire TV Wi-Fi DNS → your server's IP. |

The app bundles **all the curated blocklists** (199 streaming-specific ad/tracker domains, plus the Google IMA SDK that hits almost every streamer), and can **download the full uBlock Origin / EasyList ecosystem** on top — see [Filter Lists](#filter-lists-ubo--easylist) below.

> **"Why not just install uBlock Origin?"** You can't — uBO is a *browser
> extension*, and Fire TV has no extension-capable browser (and an extension
> couldn't see native streaming apps anyway). This app is the right shape for a
> TV: a device-wide DNS blocker. It borrows uBO's *filter lists* instead of its
> (impossible) extension. Full explanation + off-the-shelf alternatives:
> [`ALTERNATIVES.md`](ALTERNATIVES.md).

---

## Filter Lists (uBO / EasyList)

The app ships with the curated bundled lists so it works offline out of the
box, and additionally pulls community filter lists at runtime to widen
coverage far beyond the ~199 bundled domains:

- uBlock Origin — `badware`, `privacy`, `quick-fixes`
- EasyList + EasyPrivacy
- AdGuard DNS filter

Only the **host-blockable** rules are used (this is a DNS-layer blocker), so
cosmetic `##` and scriptlet `##+js` rules in those lists are ignored — they
require running inside a web page, which doesn't exist on a TV. See
[`SSAI.md`](SSAI.md) for why in-stream ads remain out of reach regardless.

**In the app:** the *Filter Lists* card shows the current domain count and last
update. Lists auto-refresh once a day (toggleable), or tap **⟳ Update Filter
Lists** to refresh now. Downloads are cached in app-private storage and fall
back to the bundled lists if the network is unavailable, so the blocker is
never left empty.

Implementation: [`FilterListUpdater.kt`](app/src/main/java/com/streamadblock/firetv/FilterListUpdater.kt)
(dependency-free `HttpURLConnection`) feeding
[`BlocklistManager.kt`](app/src/main/java/com/streamadblock/firetv/BlocklistManager.kt).

---

## Install on Fire TV

### Option 1 — Sideload via ADB (recommended)

```bash
# On your Fire TV: Settings → My Fire TV → Developer Options → ADB Debugging: ON
# Note the Fire TV's IP address.

# From your computer:
adb connect <fire-tv-ip>:5555
adb install -r app/build/outputs/apk/release/app-release.apk
```

After install, find **Stream AdBlock** in the Fire TV app drawer (Your Apps & Channels).

### Option 2 — Sideload via "Downloader" app

1. Install **Downloader** from the Amazon Appstore on your Fire TV.
2. Host the APK somewhere accessible (e.g. via `python3 -m http.server` on your laptop).
3. In Downloader, enter the URL → install the APK.

---

## Build the APK

Requires JDK 17+ and Android SDK with API 34.

```bash
cd firetv
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

Or just open the `firetv/` folder in Android Studio and click ▶.

---

## Project layout

```
firetv/
├── app/
│   ├── build.gradle.kts            # App module dependencies
│   └── src/main/
│       ├── AndroidManifest.xml     # Permissions + VPN service + TV launcher intent
│       ├── assets/lists/           # Bundled blocklists (mirror of root lists/)
│       ├── java/com/streamadblock/firetv/
│       │   ├── MainActivity.kt     # UI — remote-friendly buttons & stats
│       │   ├── BlocklistManager.kt # Parses + matches lists (uBO/EasyList/hosts)
│       │   ├── FilterListUpdater.kt # Downloads uBO/EasyList lists, caches them
│       │   ├── Stats.kt            # Persistent counters
│       │   ├── Settings.kt         # Mode + auto-start + list-update prefs
│       │   ├── BootReceiver.kt     # Auto-start on boot
│       │   └── vpn/
│       │       ├── AdBlockVpnService.kt  # VpnService — main packet loop
│       │       ├── DnsPacket.kt          # Parse QNAME, build NXDOMAIN
│       │       └── IpPacket.kt           # Build IP/UDP response packets
│       └── res/                    # Layouts, drawables, strings, colors
├── build.gradle.kts                # Project plugins
├── settings.gradle.kts
├── gradle.properties
└── gradlew                         # Gradle wrapper
```

---

## How the VPN mode works

```
Fire TV app  ─DNS query──►  Local VPN tun  ─►  AdBlockVpnService
                                                    │
                                  blocklist.match?  │
                                  ─────────────────┤
                                                    ▼
                            ┌──── blocked ────┐    ┌── allowed ──┐
                            │ NXDOMAIN reply  │    │ forward to  │
                            │ written to tun  │    │ 8.8.8.8     │
                            └─────────────────┘    └─────────────┘
```

The app establishes a tiny private-IP VPN (`10.111.0.0/24`) and registers
itself as the DNS server (`10.111.0.2`). Android routes only DNS to it; all
non-DNS traffic flows normally over the real network.

Each DNS query packet is:

1. **Parsed** — the `QNAME` is extracted from the question section.
2. **Looked up** — against the bundled blocklists (199 domains by default).
3. **Blocked or forwarded** — blocked names get an NXDOMAIN reply; others
   are forwarded to your upstream DNS (8.8.8.8 by default).

All packet handling is in `vpn/AdBlockVpnService.kt`, `vpn/DnsPacket.kt`,
and `vpn/IpPacket.kt`. No external dependencies — just raw `DatagramSocket`
and `ParcelFileDescriptor`.

---

## Why "Both modes"?

- **VPN mode** is the easiest for a casual user — install the APK, tap
  Start, done. But Android only allows one VPN at a time, which means it
  conflicts with VPN apps like ExpressVPN/NordVPN.
- **DNS Client mode** is great if you already have the Stream AdBlock
  server running on your network (e.g. on a Raspberry Pi). Then this app
  is just a dashboard — no VPN conflict, slightly lower CPU usage on the
  Fire TV.

---

## Known limitations

- **Single VPN slot**: Android only permits one active `VpnService` at a
  time. If another VPN app is on, switch to DNS Client mode.
- **DNS-over-HTTPS apps bypass us**: If an app uses DoH (e.g. Cloudflare's
  1.1.1.1 app, Firefox), its DNS won't be visible to us. Most streaming
  apps still use plain DNS though.
- **Fire TV gen 1**: The minimum SDK is 22 (Lollipop). Original Fire TV
  Stick (gen 1) ran KitKat — not supported.
- **In-stream (SSAI) ads can't be removed**: ads stitched into the video
  stream by Hulu / Max / YouTube / Twitch share a domain with the content, so
  no DNS/VPN blocker can touch them. Full analysis: [`SSAI.md`](SSAI.md).

---

## Permissions explained

| Permission | Why |
|------------|-----|
| `INTERNET` | Forward DNS queries to upstream resolver |
| `ACCESS_NETWORK_STATE` | Detect Wi-Fi changes |
| `BIND_VPN_SERVICE` | Required to host a VPN tun device |
| `FOREGROUND_SERVICE` | Keep the VPN alive in the background |
| `RECEIVE_BOOT_COMPLETED` | Auto-start on boot (only if user opts in) |
| `POST_NOTIFICATIONS` | Show the persistent "running" notification |

No telemetry, no analytics. The app never makes any outbound HTTP request
on its own — only the DNS forwards specified by the user.
