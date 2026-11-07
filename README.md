# Orbit Admin for Android

Native Android administrator shell for the existing [Orbit temporary-mail platform](https://inbox.kango99w.de/admin/).

> Development status: bootstrap only. No production APK or security certification yet.

Android app sources are public. The production backend, its credentials, TOTP secrets, and administrator cookies are **never** embedded in this repository.

## Authentication boundary

- AndroidX BiometricPrompt gates *local visibility* using a strong biometric or device screen-lock credential.
- Administrator authentication and two-factor verification remain entirely in the existing Cloudflare Worker-backed website.
- Biometric success does **not** create a server session, bypass 2FA, or prove server-side administrator identity.
- An app-private WebView cookie jar preserves only the session established by the website.
- Do not add API tokens, 2FA seeds, or static access keys to application source or Actions.

## Development

Architecture: Kotlin, Jetpack Compose, Android WebView, AndroidX Biometric.

Initial milestone: a locked-by-default admin shell, strict main-frame navigation policy, and a debug-only APK build workflow. Review and device testing are required before any distribution.

See the implementation PR for the detailed threat model and acceptance criteria.
