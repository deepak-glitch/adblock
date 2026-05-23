// Max (HBO) — skip ad break, hide ad UI
'use strict';

(() => {
  const log = (...a) => console.debug('[StreamAdBlock/Max]', ...a);

  function trySkipAd() {
    // Max shows "Ad" badges and an ad countdown
    const adBadge = document.querySelector(
      '[data-testid*="ad"], [class*="ad-marker"], [class*="AdMarker"], [class*="adIndicator"]'
    );
    if (!adBadge) return false;

    const video = document.querySelector('video');
    if (video && video.playbackRate < 16) {
      video.playbackRate = 16;
      video.muted = true;
      log('Ad detected — fast forward');
      return true;
    }
    return false;
  }

  function restoreNormal() {
    const video = document.querySelector('video');
    if (video && video.playbackRate !== 1) {
      video.playbackRate = 1;
      video.muted = false;
    }
  }

  setInterval(() => {
    if (!trySkipAd()) restoreNormal();
  }, 500);
})();
