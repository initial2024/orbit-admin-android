# Existing website features available in the Android client

Orbit Admin is intentionally a native **shell**, not a copy of the Worker backend. The actual admin HTML/JS is loaded from the existing HTTPS website; therefore security patches and ordinary administrative capabilities are not forked.

| Native entry | Original website | Responsibility |
| --- | --- | --- |
| 总览 | /admin/ | invites, admin overview and current sessions |
| 邮箱 | /admin/?adminTab=tempMail | administrator ordinary mailbox and private mailbox, OTP & safe link extraction, list, search and mailbox actions already present in the website |
| 域名 | /admin/domain-control/ | existing DNS provider connections, domains and renewal controls |
| 记录 | /admin/records/ | existing administrator records |
| 安全 | /admin/#admin-screen=admin-settings | website-owned credentials, TOTP, trusted devices and security |
| 更多 | /admin/?adminTab=advanced | existing advanced administrator tools |

Back, forward, reload and jump-to-home are native conveniences. All website actions remain website actions requiring valid cookies and server checks.

## Deliberate limits

- Native quick navigation may reload a page when changing admin paths. It does not promise seamless session-local UI state preservation across full navigations; existing website tabs remain available.
- Verification URL copying is implemented by the existing website (Clipboard API + fallback). The APK does **not** intercept mail content or transmit OTPs to native code.
- File input uses Android document picker; attachment **downloads** are still disabled for the initial test build to avoid leaking a privileged cookie or access token through an external download manager.
- Android local browser storage can be cleared by explicit confirmation. This is **not** equivalent to revoking website sessions/trusted devices: revoke them from the website security center.
- Third-party website or OAuth sign-in flows are not embedded: user-initiated external HTTPS links open in the system browser with a distinct cookie jar.

## Real-device acceptance

1. All six native entry points open correct authenticated existing pages after the current password and TOTP flow.
2. Switching shortcuts does not introduce another user database or automatic credential reuse.
3. In a received mail, copy OTP/login link via the website action. Neither value is logged or passed to a JS bridge.
4. Uploading through a file chooser must require local unlock after returning from the system picker when the app was backgrounded.
5. Clear local browser data; verify reauthentication is required. Then separately revoke server sessions/trusted-device grants.
6. Verify correct icon appearance in launcher and Android settings at multiple densities.
