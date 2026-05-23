// Hulu — skip ad break overlay + speed up ad playback
'use strict';

(() => {
  const log = (...a) => console.debug('[StreamAdBlock/Hulu]', ...a);

  function trySkipAd() {
    // Hulu shows an "ad break" overlay
    const adIndicator = document.querySelector(
      '[data-automationid="AdIndicator"], .AdIndicator, [class*="AdIndicator"]'
    );
    if (!adIndicator) return false;

    // Speed up the video element when an ad is playing
    const video = document.querySelector('video');
    if (video && video.playbackRate < 16) {
      video.playbackRate = 16;
      video.muted = true;
      log('Ad detected — playback sped to 16x');
      return true;
    }
    return false;
  }

  function restoreNormal() {
    const video = document.querySelector('video');
    if (video && video.playbackRate !== 1) {
      video.playbackRate = 1;
      video.muted = false;
      log('Ad ended — playback restored');
    }
  }

  // Poll every 500ms for ad indicator
  setInterval(() => {
    if (!trySkipAd()) restoreNormal();
  }, 500);
})();
