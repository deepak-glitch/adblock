// Peacock — skip ad break by speeding up video
'use strict';

(() => {
  const log = (...a) => console.debug('[StreamAdBlock/Peacock]', ...a);

  function trySkipAd() {
    // Peacock uses an ad badge in the player controls
    const adBadge = document.querySelector(
      '[class*="ad-badge"], [class*="AdBadge"], [data-testid*="ad-marker"]'
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
