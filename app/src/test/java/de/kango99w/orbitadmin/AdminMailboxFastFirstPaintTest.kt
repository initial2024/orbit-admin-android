package de.kango99w.orbitadmin

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AdminMailboxFastFirstPaintTest {
    @Test fun domainMetadataCannotBlockFirstMailboxPaint() {
        val src=sequenceOf(
            File("src/main/java/de/kango99w/orbitadmin/MainActivity.kt"),
            File("app/src/main/java/de/kango99w/orbitadmin/MainActivity.kt")
        ).first(File::isFile).readText()
        val code=src.substringAfter("private fun refreshNativeAdmin()")
            .substringBefore("private fun probeNativeAdminPasskeys()")
        val paint=code.indexOf("adminMailboxes = mailboxes")
        val deferred=code.indexOf("val domainRows=receive(tempDomainFuture)")
        assertTrue(paint>0 && deferred>paint)
        assertTrue(code.contains("if(authorized) adminReadIo.execute"))
        assertTrue(code.contains("generation==adminGeneration.get()"))
    }
}
