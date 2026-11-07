# Orbit Admin for Android

Public Android administrator client for the existing [Orbit temporary-mail admin website](https://inbox.kango99w.de/admin/).

**Status: experimental debug build; not a production release. Android v0.4.1-dev adds an adaptive Orbit Admin launcher icon and six website shortcuts.** The existing Cloudflare backend is unchanged.

## Authentication

- AndroidX BiometricPrompt (stable `1.1.0`) unlocks the local screen using a **strong biometric or Android device credential**.
- The website continues to enforce the **same password, TOTP 2FA, recovery codes, trusted-device setting, and server-side sessions** as the existing web administrator UI. The APK does not create a second 2FA system.
- Local unlock is not server login. It does not bypass a TOTP challenge. The website sets its normal cookies in the app-private WebView profile.
- App UI locks again when the activity is no longer visible. WebView state is preserved during an ordinary in-app lock; a process kill/restart may reload the website.
- If Android has no secure lock configured, the app fails closed and directs users to device security settings.

## Android size optimization

v0.3.1 removes the full Compose material-icons-extended dependency and uses nine small local vector drawables instead. This avoids tens of megabytes of unused debug DEX resources while keeping the same mail-first navigation.

## v0.4.1: Native-first, single-session-safe navigation

The app now displays a native Compose mailbox landing even when the authenticated snapshot has not loaded. The user explicitly opens the first-party site for account/password/2FA authentication, then the trusted first-party read-only snapshot returns them to the native list. The app no longer silently places a website UI beneath the intended native mailbox landing.

Switching native Inbox/Mailboxes (or going back from native Settings) does not reload the same public-mail HTML document. The server continues to manage account and administrator identities as two **separate sessions**, and neither is upgraded by device biometric unlocking.

## Android v0.4 native inbox preview

Public mail now has native Compose **read-only mailbox and message preview lists**, populated with a bounded, strictly validated snapshot from the currently authenticated first-party website. The native app never copies cookies or API credentials. Message reading remains on the original website, selected only if the ID is in its already-authorized list. Admin mail and attachments remain web-based; real background push is not available.

## Android v0.3 mail-first layout

Native Compose navigation now organizes the app as **收件箱 / 邮箱 / 文件 / 管理 / 设置** with a mailbox-focused top bar and a compact Settings panel. Inbox, account-owned mailbox lists, OTP links and files still come from the existing **trusted website WebView**, not from a second authentication system or duplicate native mail database. The web URL `?orbit_app=android` only enables compact presentation and safe scroll targets.

This build is **not** a fully native message reader and does not add SMTP sending. Server-side push for delivery after the app is killed is still unimplemented.

## Existing website functions

The APK displays the current server-hosted administrator website; no second frontend, mailbox database or 2FA flow is created. Six native shortcuts open **总览, 邮箱, 域名, 记录, 安全, 更多** and bottom controls provide **返回, 前进, 刷新**. All administrator mailbox, code/link extraction, domain, and security features are the original website implementations. A user-confirmed **清理本机** command clears only this APK's cookies/site storage (it does not revoke server sessions). A separate compact adaptive launcher icon follows the teal/cyan orbital mailbox design. See [docs/feature-map.md](docs/feature-map.md).

On leaving the app for the document picker, the app relocks. The selected file is not delivered into the web form until the local unlock succeeds again. Native attachment downloads are disabled in this experimental build.

## Mobile design and safety

Kotlin + Jetpack Compose for the native shell, Android WebView for the existing administrator UI. External HTTPS links open in the system browser only when explicitly clicked; other schemes/automatic external redirects are blocked. File upload uses the Android document picker without storage permission. Attachments **download is intentionally not available** in this initial build. No JavaScript-native bridge or embedded Cloudflare/API secrets.

See [SECURITY.md](SECURITY.md) and [docs/security-model.md](docs/security-model.md) for limitations and release gates.

## Build a debug APK

JDK 17, Android SDK API 36, Android Gradle Plugin 8.11.1 and Gradle 8.13 are used. The project's workflow sets these up on GitHub runners.

```bash
gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

**Debug APK upgrade warning:** GitHub-hosted builds use ephemeral debug-signing keys. If Android refuses to install an updated test build over a previous debug version, uninstall the previous test build first; that clears the APK's local login session. Production signing must later use privately controlled persistent keys.

After CI passes, use the **Android debug build** Actions workflow artifact `orbit-admin-unsigned-debug` for an installable **debug/test APK only**. It is not production-signed. Review the source and perform real-device security checks before distributing it. Never upload signing keys, TOTP seeds, admin passwords, or cookie values to a public repository.
