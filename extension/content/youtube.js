// YouTube — auto-click skip-ad button + speed through unskippable ads
// (Network blocking via IMA SDK happens at the request level too)
'use strict';

(() => {
  const log = (...a) => console.debug('[StreamAdBlock/YouTube]', ...a);
  let lastSkipTime = 0;

  function tryClickSkip() {
    // Skip button selectors (YouTube changes these periodically)
    const skipBtn = document.querySelector(
      '.ytp-ad-skip-button, .ytp-ad-skip-button-modern, ' +
      '.ytp-skip-ad-button, [class*="skip-button"]'
    );
    if (skipBtn && Date.now() - lastSkipTime > 1000) {
      skipBtn.click();
      lastSkipTime = Date.now();
      log('Clicked skip-ad button');
      return true;
    }
    return false;
  }

  function fastForwardAd() {
    // Detect unskippable ad and speed through it
    const adShowing = document.querySelector('.ad-showing, .ytp-ad-player-overlay');
    const video = document.querySelector('video');
    if (adShowing && video) {
      if (video.playbackRate < 16) {
        video.playbackRate = 16;
        video.muted = true;
        log('Unskippable ad — fast forward 16x');
      }
      return true;
    }
    if (video && video.playbackRate !== 1 && !adShowing) {
      video.playbackRate = 1;
      video.muted = false;
    }
    return false;
  }

  function hideAdElements() {
    // Hide static ad slots (banners, promoted videos)
    const selectors = [
      '#player-ads', '.ytd-promoted-sparkles-web-renderer',
      'ytd-display-ad-renderer', 'ytd-promoted-video-renderer',
      'ytd-ad-slot-renderer', '#masthead-ad',
    ];
    selectors.forEach(sel => {
      document.querySelectorAll(sel).forEach(el => { el.style.display = 'none'; });
    });
  }

  setInterval(() => {
    if (!tryClickSkip()) fastForwardAd();
    hideAdElements();
  }, 500);
})();
