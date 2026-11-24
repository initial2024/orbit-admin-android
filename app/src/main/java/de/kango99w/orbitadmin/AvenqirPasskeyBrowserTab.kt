package de.kango99w.orbitadmin

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent

/** Fixed destinations only. The browser owns all authentication and navigation. */
internal enum class AvenqirBrowserDestination(val url: String) {
    HOME(AvenqirUrlPolicy.HOME),
    PASSKEY(AvenqirNativePasskeyPolicy.PAGE)
}

/** A separate preference for AVENQIR, never a shared mail session or URL history. */
internal class AvenqirPasskeyBrowserTab(
    private val activity: Activity,
    private val canOpen: () -> Boolean
) {
    private val prefs = activity.getSharedPreferences("avenqir_first_party_browser_v1", Context.MODE_PRIVATE)
    private val pm = activity.packageManager
    private data class Browser(val pkg: String, val label: String, val supportsTab: Boolean) {
        fun display() = label + if (supportsTab) " · 安全标签页" else " · 外部浏览器"
    }
    private fun supportsCustomTabs(pkg: String) = pm.resolveService(
        Intent("android.support.customtabs.action.CustomTabsService").setPackage(pkg), 0) != null
    private fun allowed() = canOpen() && !activity.isFinishing && !activity.isDestroyed
    private fun view(destination: AvenqirBrowserDestination) =
        Intent(Intent.ACTION_VIEW, Uri.parse(destination.url)).addCategory(Intent.CATEGORY_BROWSABLE)

    private fun installedBrowsers(): List<Browser> {
        val declared = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER), 0)
        val handlers = pm.queryIntentActivities(view(AvenqirBrowserDestination.HOME), PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }.toSet()
        // OEM browsers may handle arbitrary HTTPS without CATEGORY_APP_BROWSER.
        // Resolve a reserved probe URI locally; never open it or send a request.
        // Require an unrestricted HTTPS filter rather than a site-specific deep link.
        val generic = pm.queryIntentActivities(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.invalid/"))
                .addCategory(Intent.CATEGORY_BROWSABLE),
            PackageManager.MATCH_DEFAULT_ONLY or PackageManager.GET_RESOLVED_FILTER
        ).filter { candidate ->
            candidate.filter?.let { filter ->
                filter.hasDataScheme("https") &&
                    filter.countDataAuthorities() == 0 && filter.countDataPaths() == 0
            } == true
        }
        return (declared + generic).asSequence()
            .filter { it.activityInfo.packageName != activity.packageName && it.activityInfo.packageName in handlers }
            .distinctBy { it.activityInfo.packageName }
            .map { Browser(it.activityInfo.packageName, it.loadLabel(pm).toString().take(80),
                supportsCustomTabs(it.activityInfo.packageName)) }
            .sortedWith(compareByDescending<Browser> { it.supportsTab }.thenBy { it.label.lowercase() }).toList()
    }

    fun settings() {
        if (!allowed()) return
        val browsers = installedBrowsers()
        val chosen = prefs.getString("browser", null)
        val selected = browsers.indexOfFirst { it.pkg == chosen } + 1
        AlertDialog.Builder(activity)
            .setTitle("AVENQIR 浏览器偏好")
            .setSingleChoiceItems((listOf("每次询问") + browsers.map { "${it.display()} (${it.pkg})" }).toTypedArray(), selected) { dialog, index ->
                if (allowed()) {
                    if (index == 0) prefs.edit().remove("browser").apply()
                    else prefs.edit().putString("browser", browsers[index-1].pkg).apply()
                }
                dialog.dismiss()
            }.setNeutralButton("仅本次选择并打开") { _, _ ->
                chooseAndOpen(AvenqirBrowserDestination.HOME, forceChooser = true)
            }.setNegativeButton("取消", null).show()
    }

    fun chooseAndOpen(destination: AvenqirBrowserDestination = AvenqirBrowserDestination.PASSKEY, forceChooser: Boolean = false) {
        if (!allowed()) return
        val browsers = installedBrowsers()
        if (browsers.isEmpty()) {
            Toast.makeText(activity, "未找到 HTTPS 浏览器；可返回选择兼容模式。", Toast.LENGTH_LONG).show()
            return
        }
        val preferred = browsers.firstOrNull { it.pkg == prefs.getString("browser", null) }
        if (!forceChooser && preferred != null) {
            if (launch(preferred.pkg, destination)) return
            prefs.edit().remove("browser").apply()
        }
        AlertDialog.Builder(activity)
            .setTitle("选择 AVENQIR 浏览器")
            .setItems(browsers.map { "${it.display()} (${it.pkg})" }.toTypedArray()) { _, index ->
                if (!allowed()) return@setItems
                val chosen = browsers[index]
                AlertDialog.Builder(activity).setTitle("使用 ${chosen.label}")
                    .setMessage((if (chosen.supportsTab) "优先在 Orbit 上方打开浏览器安全标签页，关闭即可返回。" else "此浏览器未声明安全标签页服务，将在完整浏览器界面打开。") + "\n浏览器负责通行密钥及会话；支持标签页不代表支持通行密钥。如认证失败，可改选兼容浏览器。不会将网页登录状态传给 Orbit Mail。")
                    .setNeutralButton("取消", null)
                    .setNegativeButton("仅本次") { _, _ -> launch(chosen.pkg, destination) }
                    .setPositiveButton("设为默认并打开") { _, _ ->
                        if (launch(chosen.pkg, destination)) prefs.edit().putString("browser", chosen.pkg).apply()
                    }.show()
            }.setNegativeButton("取消", null).show()
    }

    private fun launch(pkg: String, destination: AvenqirBrowserDestination): Boolean {
        if (!allowed() || installedBrowsers().none { it.pkg == pkg }) return false
        val supportsTab = supportsCustomTabs(pkg)
        if (supportsTab) {
            try {
                val tab = CustomTabsIntent.Builder().setShowTitle(true).build()
                tab.intent.setPackage(pkg)
                tab.launchUrl(activity, Uri.parse(destination.url))
                return true
            } catch (_: ActivityNotFoundException) {
                // Fall back only to the same user-selected browser.
            } catch (_: SecurityException) {
                // No change in destination or account scope.
            }
        }
        return try {
            Toast.makeText(activity, "正在打开所选完整浏览器；可返回 Orbit", Toast.LENGTH_SHORT).show()
            activity.startActivity(view(destination).setPackage(pkg))
            true
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, "所选浏览器不可用", Toast.LENGTH_SHORT).show()
            false
        } catch (_: SecurityException) {
            Toast.makeText(activity, "系统拒绝启动所选浏览器", Toast.LENGTH_SHORT).show()
            false
        }
    }
}
