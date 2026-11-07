package de.kango99w.orbitadmin

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-level release gate: single-package reinstall must not silently create a second app. */
class OrbitNativeReinstallContractTest {
    private fun content(vararg candidates: String): String =
        requireNotNull(candidates.map(::File).firstOrNull(File::isFile)) {
            "Missing release contract source: ${candidates.joinToString()}"
        }.readText()

    @Test fun debugPackageIsTheOriginalOrbitMailIdentifier() {
        val gradle = content("app/build.gradle.kts", "build.gradle.kts")
        assertTrue(gradle.contains("applicationId = \"de.kango99w.orbitadmin\""))
        assertTrue(
            "Debug release must use a semantic prerelease version",
            Regex("""versionName\s*=\s*"[0-9]+\.[0-9]+\.[0-9]+-dev"(?=\s|$)""")
                .containsMatchIn(gradle)
        )
        assertFalse("An unintended second Android application would confuse app upgrades",
            gradle.contains("applicationIdSuffix"))
        assertFalse(
            File("src/debug/res/values/strings.xml").isFile ||
                File("app/src/debug/res/values/strings.xml").isFile
        )
    }

    @Test fun administratorNativeUiCannotBeAnEmbeddedWebView() {
        val source = content(
            "src/main/java/de/kango99w/orbitadmin/MainActivity.kt",
            "app/src/main/java/de/kango99w/orbitadmin/MainActivity.kt"
        )
        assertTrue(source.contains("NativeAdminContent()"))
        assertTrue(source.contains("Administrator must use native Compose, not WebView"))
        assertFalse(source.contains("factory = { obtainBrowser(MailSection.ADMIN) }"))
        assertFalse(source.contains("adminWebVisible"))
        assertTrue(source.contains("openAdvancedAdminWebsite(url)"))
        assertTrue(source.contains("BuildConfig.VERSION_NAME"))
    }

    @Test fun publishedApkMustHaveSamePackageAndRejectOldSignatureAssumptions() {
        val workflow = content(
            ".github/workflows/android-preview-release.yml",
            "../.github/workflows/android-preview-release.yml"
        )
        assertTrue(workflow.contains("package: name='de.kango99w.orbitadmin'"))
        assertTrue(workflow.contains("apksigner"))
        assertTrue(workflow.contains("uninstall"))
        assertFalse(workflow.contains("de.kango99w.orbitadmin.nativepreview"))
    }
}
