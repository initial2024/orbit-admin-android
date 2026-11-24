# Orbit Mail native Passkey: release gate and server contract

Status: client implementation is **dormant / fail-closed** until all server, signing and Digital Asset Links requirements are satisfied. Never treat this as deployed or usable in the current Compact debug APK.

## Immutable identifiers
- Website and WebAuthn RP ID: `inbox.kango99w.de` (HTTPS)
- Stable release Android application ID: `de.kango99w.orbitadmin`
- Test preview application ID: `de.kango99w.orbitadmin.compactpreview`; **not** accepted as the release app
- Use a stable, protected release signing key. Do not publish or store private keys in the repository. Production WebAuthn origins must explicitly allow only the release certificate's `android:apk-key-hash:<base64url(SHA256(cert DER))>`.
- Publish `https://inbox.kango99w.de/.well-known/assetlinks.json` with `delegate_permission/common.get_login_creds` and the app package/signing SHA-256 fingerprint; HTTP 200, JSON, no redirects. Test DNS / TLS / Google provider verification. The installed APK also references this URL in `asset_statements`.
- No production passkeys for unsigned, untrusted, or ephemeral CI/debug APKs.

## Public capability endpoint (GET)
`/api/admin/passkeys/capabilities`: unauthenticated, `Cache-Control: no-store`.
Example reply only **after successful independent verification and rollout**:
```json
{"enabled":true,"protocol":"webauthn-v1","rp_id":"inbox.kango99w.de","android_package":"de.kango99w.orbitadmin","assetlinks_ready":true,"signing_ready":true,"sign_in":true,"registration":true}
```
For unavailable server, endpoint returns 404/503 or `enabled:false`; **never** return true optimistically. The native app re-probes when entering the administrator workspace (at most once per 5 minutes). No passkey UI is shown before a matching capability.

## Native login: challenge and verification
- `POST /api/admin/passkeys/authentication/options`: return HTTP 200, `{"request_id":"<opaque 20..128 characters>","public_key":{"challenge":"<base64url>","rpId":"inbox.kango99w.de",...}}`.
- `CredentialManager.getCredential(GetPublicKeyCredentialOption(...))` supplies a `PublicKeyCredential.authenticationResponseJson`.
- `POST /api/admin/passkeys/authentication/verify` with `{"request_id":"...","credential":{...}}`. Server must verify challenge, RP ID hash, exact registered credential and associated admin account, approved web/native origin, UV flag, signature, expiry/replay, and counter/backup characteristics before issuing the **existing** admin session cookie and returning HTTP 200 `{"authenticated":true}`.
- The app *then* runs its existing `GET /api/admin/status` to validate the new HttpOnly server session. A client-provided `authenticated` boolean without a server-issued valid session does not authorize the admin dashboard.

## Native registration
- Requires an existing verified administrator session and appropriate reauthentication / step-up.
- `POST /api/admin/passkeys/registration/options` returns the same request ID wrapper and a WebAuthn registration `public_key` options object (RP, user, challenge, algs, authenticatorSelection, user verification).
- `CredentialManager.createCredential(CreatePublicKeyCredentialRequest(...))` returns `registrationResponseJson`.
- `POST /api/admin/passkeys/registration/verify` receives the request ID and credential object. HTTP 201 `{"registered":true}` **only after** the server checks origin, challenge, RP, authenticator data, attestation policy, credential uniqueness, admin ownership and persisted public key. Never store a passkey private key in the app or backend.

## Security and validation
- All endpoints require exact allowlisted URLs/HTTP methods, first-party HTTPS, rate limits and replay-resistant challenge IDs. Registration and revocation require administrator step-up.
- Expire challenges quickly (e.g. 5 minutes), atomically consume them, reject wrong accounts, origins, RP IDs, stale counters and duplicate credential registration.
- Support listing/revoking enrolled credentials from the authenticated administrator security center with 2FA/recovery fallback.
- Keep passwords/2FA active as explicit recovery options; do not enable Passkey solely by changing the mobile UI.
- Test separately: valid release signer + DAL, wrong signer, wrong origin, cross-account credential, replayed challenge, expired challenge, revoked credential, Android work profile, offline mode and interrupted process.
- Deploy and inspect the Worker independently of GitHub Actions if the site repository lacks CI minutes; **deployment is not required merely to ship the dormant native client**.
