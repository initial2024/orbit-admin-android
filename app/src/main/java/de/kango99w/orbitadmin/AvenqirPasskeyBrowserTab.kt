package de.kango99w.orbitadmin

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Opens only the fixed HTTPS Passkey page in a user-selected installed browser.
 * Custom Tabs use the browser's own session. They are not the application's
 * WebView and cannot authenticate the isolated Orbit session by themselves.
 */
internal class AvenqirPasskeyBrowserTab(private val activity: Activity) {
    private val destination = Uri.parse(AvenqirNativePasskeyPolicy.PAGE)
    private val pm = activity.packageManager
    private data class Browser(val pkg: String, val label: String)

    private fun view() = Intent(Intent.ACTION_VIEW, destination)
        .addCategory(Intent.CATEGORY_BROWSABLE)

    private fun installedBrowsers(): List<Browser> {
        val declared = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER), 0)
        val handlers = pm.queryIntentActivities(view(), PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }.toSet()
        return declared.asSequence()
            .filter { it.activityInfo.packageName != activity.packageName &&
                it.activityInfo.packageName in handlers }
            .distinctBy { it.activityInfo.packageName }
            .map { Browser(it.activityInfo.packageName,
                it.loadLabel(pm).toString().take(80)) }
            .sortedBy { it.label.lowercase() }.toList()
    }

    fun chooseAndOpen() {
        if (activity.isFinishing || activity.isDestroyed) return
        val browsers = installedBrowsers()
        if (browsers.isEmpty()) {
            Toast.makeText(activity, "未找到支持 HTTPS 的系统浏览器，请安装或启用浏览器。", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("选择可信的系统浏览器")
            .setItems(browsers.map { "${it.label} (${it.pkg})" }.toTypedArray()) { _, index ->
                launch(browsers[index].pkg)
            }.setNegativeButton("取消", null).show()
    }

    private fun launch(pkg: String) {
        if (activity.isFinishing || activity.isDestroyed ||
            installedBrowsers().none { it.pkg == pkg }) return
        val supportsTab = pm.resolveService(
            Intent("android.support.customtabs.action.CustomTabsService").setPackage(pkg), 0) != null
        if (supportsTab) {
            try {
                val tab = CustomTabsIntent.Builder().setShowTitle(true).build()
                tab.intent.setPackage(pkg)
                tab.launchUrl(activity, destination)
                return
            } catch (_: ActivityNotFoundException) {
                // Fall back to the same user-selected full browser.
            } catch (_: SecurityException) {
                // Fall back without changing the HTTPS destination.
            }
        }
        try {
            activity.startActivity(view().setPackage(pkg))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, "所选浏览器不可用", Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(activity, "系统拒绝启动所选浏览器", Toast.LENGTH_SHORT).show()
        }
    }
}
