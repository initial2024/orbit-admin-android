# Compact/R8 must retain Kotlin lazy entrypoints used by the AndroidX
# instrumentation runner's runtime classes. They are loaded from the app's
# classpath at runtime and R8 cannot infer that cross-APK dependency.
# Keep this narrow: do not disable shrinking for the app or all Kotlin classes.
-keep class kotlin.LazyKt** { *; }

# Do not suppress WebView/biometric warnings or introduce sweeping keep rules.
