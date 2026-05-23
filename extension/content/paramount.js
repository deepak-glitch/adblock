// Paramount+ — skip ads via fast-forward
'use strict';

(() => {
  const log = (...a) => console.debug('[StreamAdBlock/Paramount]', ...a);

  function trySkipAd() {
    const adBadge = document.querySelector(
      '.ad-controls, [class*="ad-marker"], [class*="AdMarker"], [data-tracking-element*="ad"]'
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
