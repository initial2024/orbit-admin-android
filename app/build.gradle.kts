plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Signing keys are supplied only at protected release time. They are never checked in.
val orbitReleaseKeystoreFile = System.getenv("ORBIT_RELEASE_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
val orbitReleaseKeystorePassword = System.getenv("ORBIT_RELEASE_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
val orbitReleaseKeyAlias = System.getenv("ORBIT_RELEASE_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val orbitReleaseKeyPassword = System.getenv("ORBIT_RELEASE_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
val orbitReleaseSigningReady = listOf(
    orbitReleaseKeystoreFile, orbitReleaseKeystorePassword,
    orbitReleaseKeyAlias, orbitReleaseKeyPassword
).all { it != null }

android {
    namespace = "de.kango99w.orbitadmin"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.kango99w.orbitadmin"
        minSdk = 30
        targetSdk = 35
        versionCode = 33
        versionName = "0.9.16-dev"
        // Firebase mobile config values are public identifiers, not service-account secrets.
        // All four must be provided or the optional background push client stays off.
        val fcmNames = listOf("ORBIT_FIREBASE_APP_ID", "ORBIT_FIREBASE_API_KEY",
            "ORBIT_FIREBASE_SENDER_ID", "ORBIT_FIREBASE_PROJECT_ID")
        for (name in fcmNames) {
            val value = (System.getenv(name) ?: "").replace("\\", "\\\\").replace("\"", "\\\"")
            buildConfigField("String", name, "\"${value}\"")
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("orbitRelease") {
            if (orbitReleaseSigningReady) {
                storeFile = file(requireNotNull(orbitReleaseKeystoreFile))
                storePassword = requireNotNull(orbitReleaseKeystorePassword)
                keyAlias = requireNotNull(orbitReleaseKeyAlias)
                keyPassword = requireNotNull(orbitReleaseKeyPassword)
            }
        }
    }
    buildTypes {
        // Debug uses the same application ID as the user's installed app.
        // Its ephemeral CI signature requires uninstalling the old APK first.
        // No signing keys are generated, copied, or claimed to be reusable.
        release {
            // No environment secrets = unsigned release; publishing must reject it.
            if (orbitReleaseSigningReady)
                signingConfig = signingConfigs.getByName("orbitRelease")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.fragment:fragment:1.8.6")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.webkit:webkit:1.18.0")
    implementation(platform("com.google.firebase:firebase-bom:35.1.0"))
    implementation("com.google.firebase:firebase-messaging")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
