# Orbit Mail v0.7.0-dev — current Android navigation

All five primary tabs are rendered as native Jetpack Compose surfaces. This is **not** a fully native API/authorization backend yet.

| Tab | Native content | Authorized data / operation | Website fallback |
| --- | --- | --- | --- |
| 收件箱 | sender/subject/timestamp lists, search, native plaintext detail | Strict first-party snapshot and read bridge | Explicit for login, HTML, attachment and missing native body API |
| 邮箱 | saved mailbox list, switching, creation, address copy | Site-authorized first-party mailbox command | Explicit for protected mailbox and advanced options |
| 文件 | native file center entry and status | Native upload/download API **not implemented** | User-initiated website transfer only |
| 管理 | native management category screen and search | Native admin API **not implemented**; ordinary account does not authorize admin | User-initiated website admin/2FA only |
| 设置 | local auth, notifications, trusted-device/2FA entry, data clearing | Android system controls | Explicit security website fallback |

The files and admin tabs do **not** instantiate or silently load their website WebViews when selected. Existing site scopes remain private and are reused only after explicit entry. Public mail currently still needs a hidden, first-party WebView for authorized snapshot/commands; this does not transfer cookies or tokens into native Kotlin code.

No one-time native device binding / proof-of-possession API, persistent native user token, guaranteed background FCM, or full native attachment handling exists yet. See [native API security plan](native-device-session-v1.md).

## Release gates

Android CI testDebugUnitTest + assembleDebug; verify real-device first login + 2FA, mailbox switching, native read and fallback, admin login separation, native transfer shell, website-return behavior, app resume/restart/lock, file picker security, push state and logout. Keep Draft and Debug; do not make stable claims.
