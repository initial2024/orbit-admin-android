# AndroidX instrumentation is built in a separate APK. Its classes reference
# Kotlin standard library entry points that R8 cannot see from the target app.
# Keep the runtime entrypoints rather than silently passing unit tests while
# the minified package fails on a real Android emulator.
-keep class kotlin.** { *; }

# The separate test APK invokes synthetic Kotlin default-argument methods of
# this class (e.g. login$default). Preserve its public/tested surface until
# an explicit consumer-rule audit proves a narrower safe rule.
-keep class de.kango99w.orbitadmin.NativeDeviceClient { *; }
-keep class de.kango99w.orbitadmin.NativeDeviceClient$* { *; }

# Do not turn off R8, resource shrinking, TLS policy, Keystore constraints,
# or native account isolation to achieve a smaller APK.
