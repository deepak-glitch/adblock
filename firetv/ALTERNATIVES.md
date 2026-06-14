# Ad blockers that work on Fire TV (and why uBlock Origin isn't one)

People reach for "uBlock Origin on Firestick" because uBO is the gold standard
on desktop. **It cannot run on Fire TV**, and it's worth understanding why
before you go looking — the reason points you at the tools that *do* work.

## Why uBlock Origin can't run on Fire TV

uBlock Origin is a **browser extension**. Two hard blockers:

1. **There's no extension-capable browser on Fire TV.** Amazon's Silk browser
   has no add-on support, and Mozilla discontinued Firefox for Fire TV. There's
   simply nowhere to install the extension.
2. **Even if there were, it wouldn't help.** A browser extension only affects
   pages *inside that browser*. On a Fire TV you watch ads in the **native Hulu
   / Max / YouTube apps**, which a browser extension can never see.

The category of tool that *does* work on a TV is a **device-wide DNS/VPN
blocker** — and that's exactly what the Stream AdBlock app in this folder is.
This project's [`FilterListUpdater`](app/src/main/java/com/streamadblock/firetv/FilterListUpdater.kt)
even pulls uBlock Origin's *filter lists* down and feeds the host-blockable
subset into the on-device blocker, so you get uBO's intelligence without the
(impossible) extension.

## Off-the-shelf options (all require sideloading)

If you'd rather use an existing app than this one, these are the real
equivalents — all DNS/VPN based, all sideloaded (none are in the Amazon
Appstore):

| App | Open source? | Notes |
|-----|--------------|-------|
| **AdGuard for Android** | Core is open source | Closest to "uBO for the whole device." Uses the **same filter lists** (EasyList, EasyPrivacy), local VPN, optional HTTPS filtering (needs a CA cert). Best all-rounder. |
| **Blokada 5** | ✅ Yes (FOSS) | Free local VPN DNS blocker. Get **v5** — Blokada 6 moved to a paid cloud-DNS model. |
| **ReThinkDNS** | ✅ Yes | VPN + DNS + per-app firewall, supports many blocklists. |
| **AdGuard DNS / NextDNS** | Service | No big app to install — just set the device's private DNS. Simplest, but least control. |

## How to sideload on Fire TV

### Option A — "Downloader" app (no computer needed)

1. **Enable unknown apps:** Settings → My Fire TV → Developer Options →
   *Install unknown apps* → enable for **Downloader**.
   (On older Fire OS: turn on *Apps from Unknown Sources*.)
2. Install **Downloader** from the Amazon Appstore.
3. Open Downloader, enter the APK URL for the app you want:
   - AdGuard: `https://adguard.com/en/adguard-android/overview.html` (grab the
     standalone APK link)
   - Blokada 5: from the official Blokada GitHub releases
4. Let it download, then **Install**.
5. Open the app and **grant the VPN permission** when prompted (this is the
   local on-device VPN — it does not send your traffic anywhere).

### Option B — ADB from a computer

```bash
# On Fire TV: Settings → My Fire TV → Developer Options → ADB Debugging: ON
# Note the Fire TV's IP (Settings → My Fire TV → About → Network).

adb connect <fire-tv-ip>:5555
adb install -r path/to/adguard-or-blokada.apk
```

## The ceiling every one of these shares

None of them — AdGuard, Blokada, this app, or a hypothetical uBO port — can
remove **server-side-inserted (SSAI)** ads: the ones Hulu, Max, YouTube and
Twitch stitch directly into the video stream from the same servers as the
content. There's no separate request to block. See [`SSAI.md`](SSAI.md) for the
full explanation and what (little) can be done about it.

**Bottom line:** DNS/VPN blockers kill the *network* ads (banners, beacons,
pre-roll fetched from ad servers, telemetry) device-wide. In-stream ads inside
the big streaming apps are a different, much harder problem.
