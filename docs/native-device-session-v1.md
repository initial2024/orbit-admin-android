# Orbit Mail — native device-bound account session v1 (design gate)

Status: DESIGN ONLY; not deployed and not implemented by v0.6.1-dev. This protocol is **not** the existing website "trusted device" cookie. Existing app/browser sessions and administrator authorization remain separate.

## User-visible contract

1. User signs into the **first-party website inside Orbit Mail** once (password + any required TOTP) and explicitly authorizes "Bind this device".
2. Android creates a non-exportable P-256 key pair in Android Keystore; public key is sent over an authenticated first-party pairing flow. The server binds the public key to the **actual account principal**, not an account ID supplied by the app.
3. After binding, the Compose UI makes HTTPS JSON calls to a narrowly scoped native mailbox API. No WebView needs to remain loaded to fetch/render ordinary account-owned inbox data; the WebView is used only for initial enrollment, recovery and site-only operations.
4. A local biometric/device-credential unlock gates on-device access. It does not substitute for the initial server login/2FA and must not be accepted by the server as proof of account identity.
5. Device revocation, account closure or entitlement revocation immediately blocks new native requests. 401 means reconnect; 403 means denied. The app must not endlessly retry or silently spawn the website login page.
6. Sensitive admin actions require a **separately authorized admin scope** with server-side step-up, narrow grants and explicit revocation. Ordinary account enrollment never creates an admin session.

## Server implementation requirements

- Pairing begins via a fresh, same-origin account session **and an explicit step-up**, with anti-CSRF protections and one-time, short-lived pairing challenge; do not treat a web trusted-device cookie as an Android API token.
- Bind the public key to the server-resolved subject and a randomly generated device ID; cap active devices, keep a bounded expiry, provide list/revoke/revoke-all, and disallow account closure without revoking devices.
- Authenticate API requests via detached signatures over a canonical request: protocol version, method, **exact host/path/query**, SHA-256 body digest, server-issued nonce, and expiry. Validate signature against bound P-256 public key; reject replay, stale timestamps, wrong audience, unknown keys, non-canonical inputs, revoked/expired devices, and account mismatch.
- Never issue non-expiring bearer secrets and never copy website HttpOnly cookies into Kotlin. Never place credentials in URL, logs, crash reports, notifications, or Android backups.
- Reuse existing *server-owned* mailbox authorization (account-owned mailbox ID, protected mailbox lock, per-mailbox quotas, Turnstile when required), not client-provided mailbox identity or merely a known message ID. Reject arbitrary mailbox/message access even if the device signature is valid.
- Use exact TLS origin https://inbox.kango99w.de, no redirects or cleartext downgrade, no cross-origin cookie injection. Rate limit pairing, signatures and reads, and avoid broad admin privileges.
- Native read-only endpoints first: account identity, owned mailboxes, authorized message list, bounded sanitized text detail. Add mutation routes only with separate server checks and explicit UI confirmation.
- Do not conflate user-requested device binding with separate FCM push registration.

## Android implementation requirements

- Non-exportable key via Android Keystore; verify alias continuity, wipe binding on key invalidation and reinstall, prefer hardware-backed protection when available without claiming all phones provide StrongBox.
- Persistent metadata must exclude mailbox passwords, TOTP seeds and website cookies. Keep sensitive mail details in memory unless encrypted offline storage is explicitly designed and tested.
- Network requests use a **native** HTTP client on background dispatcher; strict response caps, cancellation, token/scope errors surfaced in Compose, no WebView/JavaScript rendering of mail data.
- Binding and native API are feature-gated and fail closed until the server deploys matching reviewed protocol version. Existing web UI remains an explicit user-selected fallback.

## Negative tests and release gates

- Cross-account, cross-mailbox and admin escalation; wrong origin and pairing CSRF; replayed/expired nonce; altered method/path/body; fake or swapped public key; revoked device; account disabled/closed; unauthorized protected mailbox.
- Process restart/offline/re-login, Android background lock, biometric cancellation, Keystore reset, reinstall, device lost/revoked, site session expiry, cookie clearing, page renderer crash.
- CI + independent security review of backend and Android; staged server release; real-device account and admin tests; no stable/production label before passing.

## v0.6.1-dev limited mitigation

Native mailbox selection uses an existing authenticated in-place message command, and unsupported native text reading shows an explicit prompt instead of automatically navigating to a login page. **This does not enroll a device or eliminate separate web sessions.**
