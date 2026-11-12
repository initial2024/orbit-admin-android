# Authentication and threat model — v0

## Existing server owns identity
The Cloudflare-hosted admin site already implements password login, TOTP challenge/recovery codes, server session cookies, and trusted-device cookies. The APK does not fork these rules or accept a locally unlocked state as proof of administrator identity. Website `HttpOnly` cookies are received inside the app-private WebView, not copied from a browser or read through a JavaScript bridge.

## Local unlocking
AndroidX BiometricPrompt authenticates a strong enrolled biometric or Android device credential. Lock on leaving the foreground; user must authenticate again. No biometric template is read or stored, and app unlock does not mint a session. Fail closed when the device cannot authenticate securely. Device PIN fallback is explicit; we do not require fingerprint availability on every device.

## WebView boundary
Only top-level HTTPS `inbox.kango99w.de` navigation stays in the embedded view. Other HTTPS navigation opens the system browser only when `WebResourceRequest.hasGesture` is true. Block all other schemes and unauthorized automatic redirects. No cleartext, no third-party cookies, no file/content access, no JS bridge, no web debugger. JavaScript is enabled solely because the existing first-party site requires it. Disabling backups and screenshots is defense-in-depth, not a guarantee against rooted OS compromise.

## Known limitations (block production release)
- BiometricPrompt protects app presentation, not server tokens already stored in the device's private WebView data directory. A compromised/rooted device is outside this gate.
- A website-origin XSS remains capable of actions as the signed-in session; local biometric authentication cannot prevent it.
- OAuth and external account logins may reject embedded WebViews; use system browser/Custom Tabs based auth if those flows are introduced.
- The host may use cross-origin resources in subframes; the top-level allowlist must not be misrepresented as an origin sandbox.
- App cannot enforce remote session invalidation when clear-local-data is selected; backend revocation is authoritative.
- Need connected real-device tests for device-credential prompt, rotation, interrupted upload, renderer crash, file selection and trusted-device flow.

## Release criteria
1. Existing website password+2FA flow works identically in APK.
2. Biometric UI unlock does not skip or forge existing website 2FA.
3. Background to foreground requires a fresh system credential.
4. No app-private cookie or token leaks into logs, exported files, or GitHub artifacts.
5. URL allowlist negative tests pass; unknown deep links are not accepted.
6. Signed distribution uses controlled, private signing keys only after review.

## Local new-mail notifications (experimental)

The trusted website may emit a constant `new_mail` notification signal after observing a new message ID compared with its previous successful poll. AndroidX WebKit provides an **origin allowlisted, main-frame only**, one-way web message listener. The native listener refuses any other payload and enforces a throttle. It does not expose authentication state or privileged native actions to JavaScript. On Android 13+ the app must receive the user's notification permission. All notices hide addresses, subject lines, codes, login links, and content.

This is **not background push**: the WebView polls only while the page is active. When Android suspends or terminates the app there is no reliable mailbox signal. A future push service must have server-authorized per-device registration, revocation, payload minimization, rate limits, and a Firebase/FCM or equivalent delivery configuration. Do not describe this build as capable of guaranteed delivery in the background.
