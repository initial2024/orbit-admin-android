# Orbit Admin for Android

Public Android administrator client for the existing [Orbit temporary-mail admin website](https://inbox.kango99w.de/admin/).

**Status: experimental debug build; not a production release.** The existing Cloudflare backend is unchanged.

## Authentication

- AndroidX BiometricPrompt (stable `1.1.0`) unlocks the local screen using a **strong biometric or Android device credential**.
- The website continues to enforce the **same password, TOTP 2FA, recovery codes, trusted-device setting, and server-side sessions** as the existing web administrator UI. The APK does not create a second 2FA system.
- Local unlock is not server login. It does not bypass a TOTP challenge. The website sets its normal cookies in the app-private WebView profile.
- App UI locks again when the activity is no longer visible. WebView state is preserved during an ordinary in-app lock; a process kill/restart may reload the website.
- If Android has no secure lock configured, the app fails closed and directs users to device security settings.

## Mobile design and safety

Kotlin + Jetpack Compose for the native shell, Android WebView for the existing administrator UI. External HTTPS links open in the system browser only when explicitly clicked; other schemes/automatic external redirects are blocked. File upload uses the Android document picker without storage permission. Attachments **download is intentionally not available** in this initial build. No JavaScript-native bridge or embedded Cloudflare/API secrets.

See [SECURITY.md](SECURITY.md) and [docs/security-model.md](docs/security-model.md) for limitations and release gates.

## Build a debug APK

JDK 17, Android SDK API 36, Android Gradle Plugin 8.11.1 and Gradle 8.13 are used. The project's workflow sets these up on GitHub runners.

```bash
gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

After CI passes, use the **Android debug build** Actions workflow artifact `orbit-admin-unsigned-debug` for an installable **debug/test APK only**. It is not production-signed. Review the source and perform real-device security checks before distributing it. Never upload signing keys, TOTP seeds, admin passwords, or cookie values to a public repository.
