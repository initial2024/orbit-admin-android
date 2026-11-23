# Orbit Mail × AVENQIR 0.9.30 · protected signing & acceptance

**Source branch:** `release-candidate/orbit-avenqir-v0.9.30-20261123`. **Target**: `main` only after code/security and user-device acceptance. No public keystore and no signature change.

## Status (must be rechecked at release time)

| Gate | Evidence / action | Fail closed |
| --- | --- | --- |
| Source preflight | `android-stable-candidate-verify.yml` succeeds for exact final HEAD | No unsigned test treated as signed production |
| Emulator Keystore | `android-keystore-instrumentation.yml` succeeds on exact final HEAD | Emulator is not a Vivo physical-device test |
| Release variant | Protected signing runs `:app:testReleaseUnitTest` in addition to Debug tests | Test failure blocks publication |
| Original package | Exact `de.kango99w.orbitadmin` with source `versionCode=47` / `versionName=0.9.30` checked via `aapt` | No unexpected package/suffix |
| Signing certificate | Real `apksigner verify --print-certs` certificate SHA-256 equals `ORBIT_RELEASE_CERT_SHA256` in protected Production Variables | Do not generate a new signing key if missing |
| Installed-app upgrade | **N/A for the owner's reported first install:** no Orbit Mail APK is currently installed on the Vivo phone. For all future updates compare installed APK certificate SHA-256 against the new official APK. | Do not invent an installed-app signer, rotate the new stable keystore, or claim future upgrade compatibility without pin evidence |
| User phone | On the user's Vivo phone, check password/2FA, account Inbox, mailbox creation, admin entry, biometric lock, AVENQIR isolated profile, external HTTPS handoff, file picker/export, back/foreground navigation | Do not infer from emulator or static unit tests |
| Server | Separate PRs `kango99w-temp-mail#620/#621/#625` need their own security/rollout acceptance; app must not claim undeployed native enrollment/push/registration features work | Existing supported website fallback only; do not bypass server gates |
| Push | Off by default. Production `ORBIT_ENABLE_PRODUCTION_PUSH=true` additionally requires `ORBIT_PUSH_BACKEND_APPROVED=true` and all four Firebase app identifiers | Never ship FCM client enabled against an unverified backend |
| Human approval | GitHub protected `Production` environment and an explicit manual dispatch acknowledgment | Do not auto-run signed release from a PR or an untrusted branch |
| Publication | GitHub Release APK, ZIP, checksums, certificate pin, commit SHA published; download SHA-256 verified | No Release URL invented before asset exists |

## Operator procedure

1. Review and merge the actual source and necessary server changes only after their own CI/security checks and user-device tests; do not merge Draft PRs as a substitute for acceptance. Confirm `main` contains the reviewed app source, because it currently contains only the repository introduction.
2. In the GitHub repository's protected Production environment, confirm `ORBIT_RELEASE_KEYSTORE_BASE64`, `ORBIT_RELEASE_KEYSTORE_PASSWORD`, `ORBIT_RELEASE_KEY_ALIAS`, `ORBIT_RELEASE_KEY_PASSWORD` and `ORBIT_RELEASE_CERT_SHA256` are set **without printing or sharing their values**. The pin is a 64-hex-character certificate SHA-256, **not** the keystore file checksum.
3. If the optional push backend has **not** been approved, leave `ORBIT_ENABLE_PRODUCTION_PUSH` unset or `false`. In this mode no Firebase ID is bundled and native notifications stay disabled. Keep unimplemented account API paths disabled or show explicit supported-site fallback.
4. After a successful protected **signing-key readiness** test and the required real-device/backend acceptance (first installation: no previous installed signer to compare), manually run `Android stable signed APK (protected manual)` **on main** with acknowledgment `PUBLISH_SIGNED_STABLE`; complete any required Production approval.
5. Verify the job's manifest version / single signer / fixed certificate pin / SHA-256 output, then download exactly the URL of the GitHub Release asset the job produced. APK ZIP uses no resigning and only compresses the distribution wrapper.
6. On the first install, record the **new** pinned stable certificate for all future updates. If any future installed certificate differs, **stop**; preserve installation and data. An APK cannot silently preserve data from a differently signed app.

## Phone-only acceptance record

Do not send passwords, OTP, personal messages, keystore bytes, token values or private inbox screenshots.

- Installation type: FIRST INSTALL (no Orbit Mail currently installed on device) / UPDATE
- Installed version (after installation): __
- Installed certificate SHA-256 (from an independent package signature inspector or previously archived official signed APK): __
- Android version and WebView version: __
- Device unlock/return to prior page: PASS / FAIL
- Main inbox, mailbox create, message view: PASS / FAIL
- Admin login/2FA, no accidental privilege transfer: PASS / FAIL
- AVENQIR admin menu, safe external link handling: PASS / FAIL
- AVENQIR file pick and JSON export: PASS / FAIL
- Network offline/error recovery: PASS / FAIL
- Confirmed production backend feature flags: __

Key readiness can now be verified without publishing an APK using the protected [manual Production signing-key check](https://github.com/initial2024/orbit-admin-android/actions/workflows/orbit-signing-key-readiness.yml). Setup instructions: [mobile key checklist](https://github.com/initial2024/orbit-admin-android/blob/main/docs/MOBILE_KEY_SETUP_FIRST_INSTALL.md).

**Never label a release as ready merely because a branch's unsigned CI completed successfully.**
