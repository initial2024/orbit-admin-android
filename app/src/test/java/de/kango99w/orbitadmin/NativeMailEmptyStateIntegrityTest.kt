package de.kango99w.orbitadmin

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NativeMailEmptyStateIntegrityTest {
    private val src=sequenceOf(File("src/main/java/de/kango99w/orbitadmin/MainActivity.kt"),
        File("app/src/main/java/de/kango99w/orbitadmin/MainActivity.kt"))
        .first(File::isFile).readText()

    @Test fun nativeMailboxReadMustNotPassOffErrorsAsEmpty() {
        assertTrue(src.contains("val messageListVerified=messageResult?.status==200 && messages!=null"))
        assertTrue(src.contains("nativeApiActive = true"))
        assertTrue(src.contains("nativeLastSuccessfulSyncMs=if(messageListVerified"))
        assertTrue(src.contains("nativeReadSupported = messageListVerified && !locked"))
        assertTrue(src.contains("未核实最新邮件"))
        assertTrue(src.contains("响应缺少 messages"))
        assertTrue(src.contains("邮件列表未核验"))
        assertTrue(src.contains("nativeSearchQuery=\"\""))
    }

    @Test fun adminInboxSupportsRefreshAndVerifiedEmptyState() {
        assertTrue(src.contains("adminMessagesLoaded=false"))
        assertTrue(src.contains("response.value?.optJSONArray(\"messages\")!=null"))
        assertTrue(src.contains("onClick={openNativeAdminMailbox(selected)}"))
        assertTrue(src.contains("adminSearchQuery=\"\""))
        assertTrue(src.contains("搜索过滤了当前邮件"))
        assertTrue(src.contains("selected.messageCount>0"))
    }
}
