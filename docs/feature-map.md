# Orbit Mail navigation and website feature map

The Android app now has a real **Kotlin/Jetpack Compose mail-first shell**, with five native destinations: 收件箱, 邮箱, 文件, 管理, 设置. The mailbox and message content are still shown by the trusted website inside a single WebView, not yet rendered in native Compose lists. Do **not** call v0.3 a fully native message renderer.

- 收件箱: `/?orbit_app=android#orbit-inbox`
- 邮箱: `/?orbit_app=android#orbit-mailboxes` (shows existing and newly created mailboxes; creation stays in the website)
- 文件: `/transfer/` (existing website)
- 管理: `/admin/?adminTab=tempMail` (ordinary and private admin mail)
- 设置: native controls for notification test, website 2FA/trusted-device entry, local-data cleanup and local lock
- More menu: original admin domain center, records, security, and overview

All routes are checked against the exact HTTPS hostname. The native application does not read a session cookie or mailbox access token, save 2FA seeds or create a new mailbox API. Mainframe WebView is reused across native tabs, maintaining the same website login session. A simple account can still perform its authorized website actions; the server remains authoritative.

## Experimental notification

The website signals the Android client only when a later successful mailbox poll detects a new message ID; it sends the constant `new_mail`, never the subject, address, code or link. There is **no** backend push or guaranteed notification after the APK is stopped or suspended.

## Release gates

- On a real Android device test navigation, back/forward, email creation, mailbox visibility, link copying, 2FA/trusted-device behavior, document picking, re-locking on app background, safe modal overlays, and notification permission.
- No production-signed APK until live login/2FA and UI interaction pass; CI compilation alone does not close this gate.
- Webapp `?orbit_app=android` is presentation-only, does not change auth or access policy.
