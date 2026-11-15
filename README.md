# Quiet Dialer
Basic Android dialer with a clean Dial / Contacts / Favorites layout.

**Update 2 – new look + animations**
- UI redesigned to match the reference: big bold titles, light-grey page with white floating buttons, floating pill navigation bar (Dial / Contacts / Favourites) with a green action button beside it
- Recents: VoLTE / HD / Wi-Fi tags, round chevron buttons, highlighted red missed-call rows, sliding All / Missed switch
- Contacts: search pill, Profile / Groups rows, big green letter badges, Google "G" badge, full # A-Z index
- Keypad is now a floating white card; the green dots button brings it back
- Every button has a press animation (spring "bounce" + ripple, see Motion.kt `bounceClick`); nav highlight and All/Missed highlight slide, tabs slide/fade, list items animate when they move, selection checks pop in

**Update 1**
- New Dial screen: large title, recents on top, always-visible keypad below (hide/show it with the dots button)
- Recents show name, HD / Wi-Fi tags, number, time (today / Yesterday / date), outgoing-call icon, and a details button
- Select mode (checkbox icon, or long-press a call) to delete several calls; search; menu with "missed only" and "clear call log"
- Smart dial: type digits, or letters on the keypad, to see matching contacts
- Contacts tab (A-Z) and Favorites tab (starred contacts)
- Keypad tones follow the system "dial pad tones" setting; long-press 0 for +, long-press backspace to clear
- Call button with an empty number redials the last outgoing call

- In-call screen redesigned: dark glass look, swipe-up Answer/Decline, SMS reply, Mute, Hold, Add call, Speaker, in-call dial pad, swap between two calls
- Banner warns when the app is not the default phone app

Proximity sensor is held ONLY while the in-call screen is visible (InCallActivity.onResume/onPause).
Active call lives in a non-dismissable CallStyle foreground notification until you hang up.

Push to GitHub -> Actions -> "Build APK" -> download the artifact, install, then set it as default phone app.
