package de.kango99w.orbitadmin

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source contract only; real administrator/password/TOTP acceptance needs phone testing. */
class NativeRegistrationControlContractTest {
 private fun source(name:String):String=listOf(
  File("src/main/java/de/kango99w/orbitadmin/$name"),
  File("app/src/main/java/de/kango99w/orbitadmin/$name")
 ).first(File::isFile).readText()

 @Test fun signupIsFirstPartyUserInitiatedAndCannotBypassTurnstile() {
  val ui=source("MainActivity.kt")
  val login=ui.substringAfter("private fun NativeLoginContent()")
    .substringBefore("private fun NativeMailboxLanding(")
  assertTrue(login.contains("https://inbox.kango99w.de/?register=1"))
  assertTrue(login.contains("Intent.ACTION_VIEW"))
  assertTrue(login.contains("CATEGORY_BROWSABLE"))
  assertFalse(login.contains("nativeDeviceClient.registerPush"))
  assertFalse(login.contains("/api/account/register"))
 }
 @Test fun policyUsesExactAdminSessionRoutesAndTwoFactor() {
  val client=source("NativeAdminClient.kt")
  val ui=source("MainActivity.kt")
  assertTrue(client.contains("\"/api/admin/registration-control\""))
  assertTrue(client.contains("if (path.any"))
  assertTrue(client.contains("adminCookies("))
  assertTrue(ui.contains("nativeAdminClient.get(\"/api/admin/registration-control\",epoch)"))
  assertTrue(ui.contains("nativeAdminClient.post(\"/api/admin/registration-control\",body,epoch)"))
  assertTrue(ui.contains("\"current_password\",adminRegPassword"))
  assertTrue(ui.contains("\"current_totp\",adminRegTotp"))
  assertTrue(ui.contains("showAdminRegConfirm=true"))
  assertFalse(ui.contains("ordinaryAccountGrantForAdmin"))
 }
 @Test fun registrationStepUpClearsAfterLogoutExpiryAndNavigation() {
  val ui=source("MainActivity.kt")
  val logout=ui.substringAfter("private fun logoutNativeAdmin()")
    .substringBefore("private fun refreshAdminGlyphs()")
  val pane=ui.substringAfter("private fun openAdminNativePane(")
    .substringBefore("private fun updateNativeAdminMailbox(")
  assertTrue(logout.contains("adminRegPassword = \"\""))
  assertTrue(logout.contains("adminRegTotp = \"\""))
  assertTrue(logout.contains("adminRegPolicy = null"))
  assertTrue(pane.contains("if (pane != \"registration\")"))
  assertTrue(pane.contains("adminRegPassword = \"\""))
  assertTrue(pane.contains("adminRegTotp = \"\""))
  assertTrue(pane.contains("adminRegPolicy = null"))
  assertTrue(pane.contains("服务器读取失败") || pane.contains("服务器读取失败（"))
  assertTrue(pane.contains("尚未上线或数据库尚未迁移"))
 }
 @Test fun credentialsClearedOnLockAndNoUnverifiedSaveClaim() {
  val ui=source("MainActivity.kt")
  val lock=ui.substringAfter("private fun lock() {").substringBefore("@SuppressLint")
  assertTrue(lock.contains("adminRegPassword = \"\""))
  assertTrue(lock.contains("adminRegTotp = \"\""))
  assertTrue(lock.contains("adminRegPolicy = null"))
  assertTrue(ui.contains("if(result.status==200 && result.value!=null)"))
  assertTrue(ui.contains("if(result.status==401) {"))
  assertTrue(ui.contains("adminAuthenticated=false"))
  assertTrue(ui.contains("adminRegPolicy=null"))
 }
}
