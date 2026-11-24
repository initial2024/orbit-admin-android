package de.kango99w.orbitadmin

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class StableSigningWorkflowContractTest {
    @Test fun protectedReleasePinsCertificateAndRefusesDebugVersion() {
        val path=sequenceOf(
            File("../.github/workflows/android-stable-release.yml"),
            File(".github/workflows/android-stable-release.yml")
        ).first { it.isFile }
        val workflow=path.readText()
        assertTrue(workflow.contains("environment: Production"))
        assertTrue(workflow.contains("vars.ORBIT_RELEASE_CERT_SHA256"))
        assertTrue(workflow.contains("ORBIT_EXPECTED_CERT_SHA256"))
        assertTrue(workflow.contains("ACTUAL_CERT"))
        assertTrue(workflow.contains("EXPECTED_CERT"))
        assertTrue(workflow.contains("test \"\$ACTUAL_CERT\" = \"\$EXPECTED_CERT\""))
        assertTrue(workflow.contains("apksigner") || workflow.contains("APK_SIGNER"))
        assertTrue("Unreleased FCM must be disabled unless explicitly approved",
            workflow.contains("vars.ORBIT_ENABLE_PRODUCTION_PUSH == 'true'"))
        assertTrue("Push requires a separate backend approval gate",
            workflow.contains("ORBIT_PUSH_BACKEND_APPROVED"))
        assertTrue("Push must be absent when not approved",
            workflow.contains("FCM_CLIENT_DISABLED"))
        assertTrue("Stable build must test its own minified Release variant",
            workflow.contains(":app:testReleaseUnitTest"))
        assertTrue("APK package must match the original installation ID",
            workflow.contains("package: name='de.kango99w.orbitadmin'"))
        assertTrue("APK manifest version must match the source version",
            workflow.contains("versionCode='\$VERSION_CODE' versionName='\$VERSION'"))
        assertTrue("Unexpected additional APK signers must fail closed",
            workflow.contains("Unexpected additional signer; stable release denied"))
        assertTrue("Protected release must verify all APK signatures",
            workflow.contains("verify --verbose --print-certs"))
        assertTrue(workflow.contains("Refuse unsigned/unreviewed -dev version"))
        assertTrue(workflow.contains("refusing overwrite"))
        assertFalse(workflow.contains("--insecure"))
    }
}
