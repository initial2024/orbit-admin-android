# Security policy

This is a **public source** repository for a client that will access a private administrator website. Report security issues privately to the repository maintainers; never post credentials, cookies, TOTP secrets, or screenshots of administrator data in public issues.

## Non-negotiable security boundaries

- AndroidX BiometricPrompt unlocks only local app UI. It never authorizes a Cloudflare Worker request and never replaces password/TOTP or server-side session validation.
- The app opens only `https://inbox.kango99w.de` inside its WebView. External HTTPS links are handed to the device browser only after a user-initiated navigation; blocked schemes are never executed.
- No `addJavascriptInterface`, `postWebMessage`, JavaScript evaluation bridge, Cloudflare secrets, signing keys, static admin tokens, or hard-coded account passwords.
- Cookies remain in the Android application's private WebView storage and are established by existing website authentication. Do not read or log cookie values.
- The app is locked again when it is no longer visible. Screenshots and non-secure recent-app thumbnails are disabled through `FLAG_SECURE`.
- There is no remote biometric enrollment, fingerprint template collection, or biometric database.
- Clearing local WebView cookies is not server-side revocation; administrator sessions and trusted devices must be revocable on the website.

## Review before release

Evaluate authentication redirects, WebView renderer crash/restart, locked-screen recents, trusted-device lifetime, cookie persistence on real devices, file chooser scope, accessibility, and server-side logout. Never call a green build a security audit.
