# Orbit Mail signing and update safety

**Current debug preview (v0.9.18-dev): NOT guaranteed to overwrite v0.9.17-dev.** The GitHub Actions preview workflow uses its own CI-generated Android debug keystore; its private key is ephemeral and has not been preserved. Matching package ID `de.kango99w.orbitadmin` does not establish certificate identity. This repo does not carry a usable prior Android debug signing key. Never claim a same-signature update without comparing `apksigner verify --print-certs` certificate SHA-256 digests.

For reproducible, future stable upgrades use `.github/workflows/android-stable-release.yml` on protected `main`, with GitHub Environment **Production** secrets:
- `ORBIT_RELEASE_KEYSTORE_BASE64` = a securely stored, offline-backed-up stable Android keystore encoded as base64.
- `ORBIT_RELEASE_KEYSTORE_PASSWORD` = keystore password.
- `ORBIT_RELEASE_KEY_ALIAS` = exact key alias.
- `ORBIT_RELEASE_KEY_PASSWORD` = key password.

The protected release workflow must also meet native ordinary-account/login, administrator mailbox, Cloudflare safe_links, Firebase push, file transfer, and real-phone acceptance gates. It rejects a `-dev` version and rejects missing secrets. Never copy secrets into git, issue comments, PRs, logs, or chat.

If the stable key has never been used to sign an installed Orbit Mail, the **first** stable APK cannot update existing debug APKs in place. Before uninstalling an old app, back up data according to supported export mechanisms and verify all account recovery options. Uninstalling clears locally stored browser sessions and Android Keystore entries.

The debug workflow is for isolated functional testing only. It never uses production signing keys and must not be described as fixed-signature.
