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
        assertTrue(workflow.contains("if [[ \"$ACTUAL_CERT\" != \"$EXPECTED_CERT\" ]]"))
        assertTrue(workflow.contains("apksigner") || workflow.contains("APK_SIGNER"))
        assertTrue(workflow.contains("Refuse unsigned/unreviewed -dev version"))
        assertTrue(workflow.contains("Refusing overwrite"))
        assertFalse(workflow.contains("--insecure"))
    }
}
