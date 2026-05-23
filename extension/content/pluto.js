// Pluto TV — mute and speed up during ad slots
'use strict';

(() => {
  const log = (...a) => console.debug('[StreamAdBlock/Pluto]', ...a);

  function trySkipAd() {
    // Pluto shows an ad badge ("AD") near the timeline
    const adBadge = document.querySelector(
      '[class*="ad-pod"], [class*="AdPod"], [data-testid*="ad-marker"]'
    );
    if (!adBadge) return false;

    const video = document.querySelector('video');
    if (video && video.playbackRate < 16) {
      video.playbackRate = 16;
      video.muted = true;
      log('Ad pod — fast forward');
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
