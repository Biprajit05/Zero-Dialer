# Quiet Dialer
Basic Android dialer: keypad, call log, in-call screen.
- Proximity sensor is held ONLY while the in-call screen is visible (InCallActivity.onResume/onPause).
- Active call lives in a non-dismissable CallStyle foreground notification until you hang up.

Push to GitHub -> Actions -> "Build APK" -> download the artifact, install, then set it as default phone app.
