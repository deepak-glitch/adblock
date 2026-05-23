# 🛡️ Stream AdBlock — Chrome Extension

A Manifest V3 Chrome extension that blocks ads on streaming web apps and skips video ads in the player UI.

## Features

- **199 ad-domain rules** (Hulu, Max, Peacock, Paramount+, Pluto, Tubi, Disney+, YouTube + Google IMA + trackers)
- **Per-platform ad skipping** — content scripts that detect ad breaks and fast-forward through them
- **Toggle categories** — turn off streaming/Google/tracker blocking individually
- **Live counter** — badge shows today's blocked count
- **Recent log** — see exactly what was blocked

## Install (Developer Mode)

1. Open Chrome → `chrome://extensions`
2. Toggle **Developer mode** on (top right)
3. Click **Load unpacked**
4. Select the `extension/` folder
5. Done — pin the icon for easy access

## Rebuild the rules

The MV3 rules JSON files are generated from the master DNS blocklists.
If you edit `lists/*.txt`, rebuild them:

```bash
node extension/scripts/build-rules.js
```

## Per-platform content scripts

Each streaming site gets its own script that watches for ad indicators
in the DOM. When detected, the script speeds up the video element to
16× and mutes audio, then restores normal playback when the ad ends.

| Platform | File | Strategy |
|----------|------|----------|
| Hulu | `content/hulu.js` | Detect `AdIndicator` → 16× speed |
| Max | `content/max.js` | Detect ad-marker → 16× speed |
| Peacock | `content/peacock.js` | Detect ad-badge → 16× speed |
| Paramount+ | `content/paramount.js` | Detect ad controls → 16× speed |
| Pluto TV | `content/pluto.js` | Detect ad pod → 16× speed |
| Tubi | `content/tubi.js` | Detect ad indicator → 16× speed |
| YouTube | `content/youtube.js` | Click skip-ad + hide ad slots |

## Limitations

- **Server-Side Ad Insertion (SSAI)**: Hulu, Peacock, Max stitch ads into
  the video stream. Network blocking helps, but the fast-forward content
  script is what makes ads tolerable.
- **YouTube**: Ads and video share the same CDN. The extension hides
  static ads and auto-skips skippable pre-rolls. Unskippable ads are
  fast-forwarded.
- **Chrome MV3 limit**: 30,000 dynamic rules max. We use ~200 static
  rules, well under the cap.

## File map

```
extension/
├── manifest.json           # MV3 manifest
├── background.js           # Service worker (badge + stats)
├── content/                # Per-platform skip scripts
├── popup/                  # Toolbar popup UI
├── rules/                  # MV3 declarativeNetRequest JSON
├── icons/                  # Placeholder icons (replace if desired)
└── scripts/
    ├── build-rules.js      # Convert lists/*.txt → rules/*.json
    └── make-icons.js       # Generate placeholder icons
```
