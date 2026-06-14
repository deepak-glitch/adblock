# Server-Side Ad Insertion (SSAI) on Fire TV — what's possible

This is the honest research write-up behind the "can we block in-stream ads on
Firestick?" question. Short answer: **a DNS/VPN blocker cannot, and the
alternatives range from impractical to impossible on a stock Fire TV.**

## What SSAI is

Older "client-side" ads (CSAI) work like this: the player asks an ad server
(Google IMA, FreeWheel, SpotX…) for an ad, gets back a separate video URL, and
plays it. **Block the ad server's domain and the ad never loads.** That's what
this app, AdGuard, Blokada, and Pi-hole all do well — and it's why the bundled
domain lists work.

SSAI is different. The ad is **stitched into the content stream on the
server**, before it ever reaches you:

```
HLS manifest (playlist.m3u8) the player receives:
   #EXTINF:6.0,   segment_4815.ts     ← show content
   #EXTINF:6.0,   segment_4816.ts     ← show content
   #EXT-X-DISCONTINUITY
   #EXTINF:6.0,   ad_0001.ts          ← AD  (same CDN, same domain)
   #EXTINF:6.0,   ad_0002.ts          ← AD
   #EXT-X-DISCONTINUITY
   #EXTINF:6.0,   segment_4817.ts     ← back to content
```

The ad segments come from the **same host** as the content (e.g. Hulu's Darwin
SSAI, Max via FreeWheel, YouTube, Twitch's `*.ttvnw.net`). There is:

- **no separate ad domain to block** — blocking it kills the whole stream;
- **no separate request** to cancel;
- **no DOM element** to hide (it's a native app, not a web page).

This is precisely why uBlock Origin struggles with Twitch even on desktop, and
why **DNS blocking is structurally the wrong tool** for SSAI.

## Where each blocking layer hits the wall

| Technique | Can it touch SSAI? | Why |
|-----------|--------------------|-----|
| DNS/VPN domain block (this app, AdGuard DNS, Pi-hole) | ❌ | Ad and content share a domain. |
| uBO cosmetic filters (`##`) | ❌ on Fire TV | No web page / DOM in a native app. |
| uBO scriptlets (`##+js`, `json-prune`) | ❌ on Fire TV | Need to run JS *inside* the player; native apps don't allow injection. |
| HTTPS filtering + manifest rewrite | ⚠️ in theory | Needs a MITM proxy **and** the app to trust your CA — defeated by certificate pinning (see below). |

## The only thing that could work — and why it usually doesn't

To strip SSAI ads you'd have to **intercept and rewrite the HLS/DASH manifest**
(delete the `#EXT-X-DISCONTINUITY` ad ranges) before the player parses it. To do
that you must read the manifest, which is fetched over **HTTPS**. That requires:

1. A man-in-the-middle TLS proxy on the device, **and**
2. The streaming app to trust your proxy's root certificate.

On a stock Fire TV both fail:

- **Certificate pinning.** Hulu/Max/Netflix/YouTube pin their certs, so even a
  trusted custom CA is rejected — the app refuses to connect through a proxy.
- **No user CA trust on modern Android/Fire OS** without root; apps targeting
  recent API levels ignore user-added CAs by default anyway.
- **DRM.** Premium streams are Widevine-encrypted; you can't edit segments you
  can't decrypt.

So the manifest-rewrite path needs a **rooted** device (or a custom build) plus
per-app pinning bypass (Frida/Xposed-style hooks) — fragile, app-version
specific, and out of scope for a sideloaded consumer APK.

## What actually exists in the wild

- **SponsorBlock-style tools (e.g. iSponsorBlockTV)** skip *creator-marked
  sponsor segments* in the YouTube app by driving the TV over its cast/remote
  API. That's crowd-sourced segment metadata — **not** SSAI ad removal, and
  YouTube-only.
- **Network/DNS blockers** (this project included) reduce ad *requests*,
  telemetry, and CSAI pre-rolls — a real, visible win on many apps — but leave
  SSAI in-stream ads intact.
- **Premium tiers** remain the only reliable way to remove SSAI ads inside the
  major apps.

## Recommendation for this project

1. **Keep doing what works:** maximise DNS-layer coverage. The new
   [`FilterListUpdater`](app/src/main/java/com/streamadblock/firetv/FilterListUpdater.kt)
   pulls uBO/EasyList lists so the app blocks the widest possible set of ad,
   tracker, and telemetry domains — this is the highest-value, fully-feasible
   work.
2. **Don't promise SSAI removal** in the app UI or README — it sets users up to
   be disappointed and isn't deliverable on stock hardware.
3. **If you want to experiment** with manifest rewriting, do it on a *rooted
   test device* against a *specific app version*, accept that pinning bypass is
   required, and treat it as research, not a shippable feature.
