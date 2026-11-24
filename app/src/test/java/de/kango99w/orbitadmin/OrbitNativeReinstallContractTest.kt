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
        val version = Regex("""versionName\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+(?:-dev)?)"(?=\s|$)""")
            .find(gradle)?.groupValues?.get(1)
        assertTrue("App version must be stable-semver or explicitly -dev", version != null)
        if (version != null && !version.endsWith("-dev")) {
            val stable = content(
                ".github/workflows/android-stable-release.yml",
                "../.github/workflows/android-stable-release.yml"
            )
            assertTrue("Stable releases require pinned Production certificate",
                stable.contains("ORBIT_RELEASE_CERT_SHA256"))
            assertTrue("Stable release must be restricted to main",
                stable.contains("github.ref == 'refs/heads/main'"))
            assertTrue("Stable release requires manual publish acknowledgement",
                stable.contains("PUBLISH_SIGNED_STABLE"))
            assertTrue("Stable release must retain R8 signed APK packaging",
                stable.contains("zip -9"))
        }
        assertTrue("Only one deliberate preview suffix may exist",
            gradle.lines().count { it.trimStart().startsWith("applicationIdSuffix = ") } == 1)
        assertTrue(gradle.contains("create(\"compactPreview\")"))
        assertTrue(gradle.contains("applicationIdSuffix = \".compactpreview\""))
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
