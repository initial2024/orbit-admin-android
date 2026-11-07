package de.kango99w.orbitadmin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The account selector is UI state, not an auth substitute. */
class WorkspaceNavigationContractTest {
    @Test fun ordinaryAndAdminHaveDistinctFiveSlotNavigation() {
        assertEquals(5,WorkspaceNavigation.ordinaryTabs.size)
        assertEquals(5,WorkspaceNavigation.adminTabs.size)
        assertTrue(WorkspaceNavigation.ordinaryTabs.contains(MailSection.DECOR))
        assertTrue(WorkspaceNavigation.ordinaryTabs.contains(MailSection.TRANSFER))
        assertFalse(WorkspaceNavigation.ordinaryTabs.contains(MailSection.ADMIN))
        assertFalse(WorkspaceNavigation.ordinaryTabs.contains(MailSection.PRIVATE))
        assertTrue(WorkspaceNavigation.adminTabs.contains(AdminDestinationTab.PRIVATE))
        assertTrue(WorkspaceNavigation.adminTabs.contains(AdminDestinationTab.SECURITY))
    }

    @Test fun selectorIsCollapsedByDefaultAndClosesAfterSelectionOrBack() {
        val source = listOf(
            File("src/main/java/de/kango99w/orbitadmin/MainActivity.kt"),
            File("app/src/main/java/de/kango99w/orbitadmin/MainActivity.kt")
        ).first(File::isFile).readText()
        assertTrue(source.contains("private var workspaceSwitcherExpanded by mutableStateOf(false)"))
        assertTrue(source.contains("onClick={workspaceSwitcherExpanded = !workspaceSwitcherExpanded}"))
        assertTrue(source.contains("if(workspaceSwitcherExpanded) {"))
        assertTrue(source.contains("workspaceSwitcherExpanded -> workspaceSwitcherExpanded = false"))
        val switcher = source.substringAfter("private fun switchWorkspace(next: MailWorkspace) {")
            .substringBefore("private fun selectAdminNavigation(")
        assertTrue(switcher.contains("workspaceSwitcherExpanded = false"))
        assertTrue(switcher.indexOf("workspaceSwitcherExpanded = false") <
            switcher.indexOf("if (workspace == next) return"))
        assertFalse(switcher.contains("nativeDeviceClient.logout"))
        assertFalse(switcher.contains("adminAuthenticated = true"))
    }

    @Test fun switchingWorkspaceMustNotReuseTheOtherAccountSession() {
        val source = listOf(
            File("src/main/java/de/kango99w/orbitadmin/MainActivity.kt"),
            File("app/src/main/java/de/kango99w/orbitadmin/MainActivity.kt")
        ).first(File::isFile).readText()
        val switcher=source.substringAfter("private fun switchWorkspace(next: MailWorkspace) {")
            .substringBefore("private fun selectAdminNavigation(")
        assertFalse(switcher.contains("kango_admin_session"))
        assertFalse(switcher.contains("kango_site_access"))
        assertFalse(switcher.contains("nativeDeviceClient.login"))
        assertFalse(switcher.contains("adminAuthenticated = true"))
        assertTrue(source.contains("WorkspaceNavigation.ordinaryTabs.forEach"))
        assertTrue(source.contains("WorkspaceNavigation.adminTabs.forEach"))
        assertTrue(source.contains("MailSection.DECOR"))
        assertTrue(source.contains("NativeGlyphContent()"))
    }
}
