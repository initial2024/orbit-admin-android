package de.kango99w.orbitadmin

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NativeAdminCredentialEntryContractTest {
    @Test fun nativePasswordPickerFailureExplainsNoCredentialAndProviderDisabled() {
        val f=sequenceOf(
            File("src/main/java/de/kango99w/orbitadmin/NativeAdminPasswordCredentials.kt"),
            File("app/src/main/java/de/kango99w/orbitadmin/NativeAdminPasswordCredentials.kt")
        ).first(File::isFile).readText()
        assertTrue(f.contains("is NoCredentialException"))
        assertTrue(f.contains("is GetCredentialProviderConfigurationException"))
        assertTrue(f.contains("is CreateCredentialProviderConfigurationException"))
        assertTrue(f.contains("系统没有找到本 APK 可用的账号密码"))
        assertFalse(f.contains("未选取系统凭据；可以使用管理员密码登录。"))
    }
}
