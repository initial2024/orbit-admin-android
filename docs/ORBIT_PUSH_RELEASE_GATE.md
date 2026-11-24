# Orbit Mail — Android background notification gate

Status: code-only candidate in Android Draft PR #1. No claim of live background delivery.

## Existing roles and boundaries
- Native ordinary account: prior P-256 device pairing and signed
  `/api/account/native-device/push/register` and `push/revoke`.
- Administrator and public/anonymous mailboxes must not inherit ordinary
  account grants. No hidden WebView or cookie bridging is introduced.
- Worker Draft PR #621 has owner-bound registration and FCM HTTP v1 delivery
  code, but remains disabled until protected D1 migration and independent review.
- Never include message content, OTP, address, sender or subject in FCM payload.

## Client build identifiers
- Debug prerelease: public GitHub Actions variables
  `ORBIT_FIREBASE_PREVIEW_APP_ID`, `ORBIT_FIREBASE_PREVIEW_API_KEY`,
  `ORBIT_FIREBASE_PREVIEW_SENDER_ID`, `ORBIT_FIREBASE_PREVIEW_PROJECT_ID`.
  A missing complete set leaves Firebase disabled; partial sets fail builds.
- Protected stable Production environment: variables
  `ORBIT_FIREBASE_APP_ID`, `ORBIT_FIREBASE_API_KEY`,
  `ORBIT_FIREBASE_SENDER_ID`, `ORBIT_FIREBASE_PROJECT_ID`.
  Stable build refuses any missing value.
- Client-side Firebase identifiers are public config, **not** FCM service
  account credentials. `ORBIT_FCM_SERVICE_ACCOUNT_JSON` and the token
  encryption key belong only in Cloudflare Worker secrets.

## Retry and revocation
- Explicit opt-in and notification permission are mandatory.
- `onNewToken` schedules a network-constrained unique WorkManager request;
  the worker re-reads the token rather than storing it in work data.
- The worker checks current opt-in, revoke-pending, and Keystore device grant
  before sending; 401/403/409 never fall back to admin or website credentials.
- Explicit opt-out cancels deferred work and persists local disable before
  sending a signed revocation request. Backend revocation acknowledgment is
  still required. A concurrent in-flight registration/revocation race must be
  tested on device and server before production enablement.

## Release acceptance
1. PR #620 native login reviewed, deployed and independently tested; first
   ordinary login, TOTP, logout, account switch and grant revocation proven.
2. Worker PR #621 migration, FCM sender, ownership checks and delivery
   recheck independently audited; deployment flag stays OFF until approved.
3. Test token rotation, offline/restart retry, lock, opt-out/revoke race,
   Android 13+ permission denial, Google Play Services absence and expiry.
4. Send a real mail to an owned unprotected mailbox. Verify exactly one
   generic notification while app is foreground/background/process killed;
   verify no notification for revoked, protected or foreign mailbox.
5. Preserve all existing mail/UI features, stable signing certificate pin,
   downgrade prevention and real-phone installation acceptance.
