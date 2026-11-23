# Orbit Mail — one-time stable APK signing from Android (Termux)

This procedure is for the **owner of the app only**. Never put the keystore,
its base64 representation, or its password in a chat, GitHub commit, issue,
pull request, website, or public build artifact.

## Policy

- Fixed package identity: `de.kango99w.orbitadmin`.
- Fixed signing certificate: generate **once**, back up offline, reuse for every
  subsequent stable release. Never create a fresh key for each APK.
- Existing CI `debug` APKs are signed using disposable debug keys and cannot be
  assumed to update a currently installed APK.
- First use of the new stable key may require uninstalling an old debug APK,
  which **removes local sessions, Android Keystore grants and app storage**.
  Confirm account recovery, and export what the app legitimately supports,
  before doing that.
- Production key is accessible only to protected GitHub environment
  `Production`; never expose it to pull-request jobs.
- Do not publish from the development branch. The protected workflow only
  releases reviewed `main` builds with a non-`-dev` version and pinned cert.

## 1. Generate locally, preferably on a trusted device

For Android-only setup, install Termux from an official supported channel.
In Termux:

```sh
pkg update
pkg install -y openjdk-17 gh coreutils
umask 077
mkdir -p "$HOME/orbit-signing"
test ! -e "$HOME/orbit-signing/orbit-mail-release.p12" || {
  echo "Existing signing key found. Do not overwrite it."; exit 1;
}
keytool -genkeypair \
  -keystore "$HOME/orbit-signing/orbit-mail-release.p12" \
  -storetype PKCS12 \
  -alias orbit-mail-stable \
  -keyalg RSA -keysize 3072 -validity 10000 \
  -dname "CN=Orbit Mail Release"
```

Enter a long, unique keystore password in the interactive prompt and save it
in a trusted password manager. PKCS12 normally uses the same key and store
password. **The password is never included in the commands.**

Back up the resulting encrypted `.p12` file to a separate secure location,
then retain at least one additional offline backup. Do not rely solely on
Termux app-private storage. Losing the keystore prevents future ordinary
updates with this signing identity.

## 2. Configure GitHub Production environment

On GitHub open:
https://github.com/initial2024/orbit-admin-android/settings/environments

Create `Production` if not already present. Enable release approval/
deployment protection as available for the repository.

In Termux, authenticate the owner's GitHub account:

```sh
gh auth login --web --hostname github.com
gh auth status
```

Then upload the encrypted keystore into an **environment secret** directly
from the local file without printing a base64 blob on screen:

```sh
REPO=initial2024/orbit-admin-android
KEY="$HOME/orbit-signing/orbit-mail-release.p12"
base64 < "$KEY" | tr -d '\n' |
  gh secret set ORBIT_RELEASE_KEYSTORE_BASE64 --repo "$REPO" --env Production
printf '%s' 'orbit-mail-stable' |
  gh secret set ORBIT_RELEASE_KEY_ALIAS --repo "$REPO" --env Production
```

Enter the password interactively, without echoing it or writing it to shell
history, and upload the same value under both expected secret names:

```sh
read -srp "Signing keystore password: " KEY_PASSWORD
echo
printf '%s' "$KEY_PASSWORD" |
  gh secret set ORBIT_RELEASE_KEYSTORE_PASSWORD --repo "$REPO" --env Production
printf '%s' "$KEY_PASSWORD" |
  gh secret set ORBIT_RELEASE_KEY_PASSWORD --repo "$REPO" --env Production
unset KEY_PASSWORD
```

Now pin the **public certificate fingerprint** in an environment variable:

```sh
CERT_SHA256="$(keytool -list -v -keystore "$KEY" -alias orbit-mail-stable |
  sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' |
  tr -d ':' | tr '[:upper:]' '[:lower:]' | head -n 1)"
test "${#CERT_SHA256}" -eq 64 || { echo "Certificate digest missing"; exit 1; }
printf '%s' "$CERT_SHA256" |
  gh variable set ORBIT_RELEASE_CERT_SHA256 --repo "$REPO" --env Production
echo "Certificate SHA-256 (PUBLIC fingerprint): $CERT_SHA256"
```

The expected release environment has exactly these signing entries:
- **Secrets:** `ORBIT_RELEASE_KEYSTORE_BASE64`,
  `ORBIT_RELEASE_KEYSTORE_PASSWORD`, `ORBIT_RELEASE_KEY_ALIAS`,
  `ORBIT_RELEASE_KEY_PASSWORD`.
- **Variable:** `ORBIT_RELEASE_CERT_SHA256` (64 lowercase hex characters).

GitHub CLI locally encrypts uploaded Actions secrets. Never upload a
private signing key to repository Contents, Releases, or PR attachments.

## 3. Stable release requirements

The existing `.github/workflows/android-stable-release.yml` on `main`
refuses release when the key entries, pinned fingerprint, Firebase production
identifiers, or stable `versionName` are missing.

The first release from the new stable signing key requires:
1. Review / merge application features and tests to protected `main`,
   without merging competing implementations twice.
2. Verify all production `ORBIT_FIREBASE_*` identifiers are present as
   environment variables, or separately review the release gate.
3. Use a higher `versionCode` and a stable `versionName` without `-dev`.
4. Complete real-device login, admin session, mailbox, AVENQIR admin, backup,
   link confirmation, and screen-lock checks.
5. Manually run **Orbit Mail stable signed APK (protected manual)** on
   `main` with the acknowledgement `PUBLISH_SIGNED_STABLE`.
6. Compare `apksigner verify --print-certs` certificate fingerprint
   against the pinned `ORBIT_RELEASE_CERT_SHA256` before installing.
   Record APK SHA-256 and preserve the immutable GitHub Release.

For every following version, reuse the same Production keystore.
A changed signing fingerprint is a **release-blocking failure**, never
a reason to silently create or replace the key.

## Android security notes

The first-run biometric chooser does not grant admin authentication.
AVENQIR admin and feedback admin continue to use their own HTTPS session,
CSRF and independent passwords in a separate `:avenqir` WebView process.
The development preview does not imply the app is production-safe or that
signing compatibility with previously installed debug versions exists.
