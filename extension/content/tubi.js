// Tubi — fast-forward through ad pods
'use strict';

(() => {
  const log = (...a) => console.debug('[StreamAdBlock/Tubi]', ...a);

  function trySkipAd() {
    // Tubi shows "Advertisement" text and an ad counter
    const adBadge = document.querySelector(
      '[data-testid*="ad-badge"], [class*="ad-indicator"], [class*="AdIndicator"]'
    );
    if (!adBadge) return false;

    const video = document.querySelector('video');
    if (video && video.playbackRate < 16) {
      video.playbackRate = 16;
      video.muted = true;
      log('Ad — fast forward');
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
