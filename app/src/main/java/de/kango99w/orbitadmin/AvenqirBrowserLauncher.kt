package de.kango99w.orbitadmin

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast

/** Preference affects only approved external HTTPS destinations, never owner pages. */
object AvenqirBrowserPreference {
    const val ASK = "ask"
    const val SYSTEM = "system"
    const val BUILTIN = "builtin"
    const val PACKAGE = "package:"
    fun normalize(raw: String?, installed: Set<String>): String = when {
        raw == SYSTEM || raw == BUILTIN -> raw
        raw != null && raw.startsWith(PACKAGE) &&
            raw.removePrefix(PACKAGE) in installed -> raw
        else -> ASK
    }
}

class AvenqirBrowserLauncher(private val activity: Activity, private val canOpen: () -> Boolean) {
    private val prefs = activity.getSharedPreferences("avenqir_browser_choice_v1", Context.MODE_PRIVATE)
    private data class Choice(val key: String, val label: String)
    private fun view(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addCategory(Intent.CATEGORY_BROWSABLE)

    private fun browsers(): List<Choice> {
        val pm = activity.packageManager
        val declared = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_APP_BROWSER), 0)
        val web = pm.queryIntentActivities(view("https://example.com/"), PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }.toSet()
        return declared.asSequence()
            .filter { it.activityInfo.packageName in web && it.activityInfo.packageName != activity.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { Choice(AvenqirBrowserPreference.PACKAGE + it.activityInfo.packageName,
                it.loadLabel(pm).toString()) }
            .sortedBy { it.label.lowercase() }.toList()
    }
    private fun choices() = listOf(
        Choice(AvenqirBrowserPreference.ASK, "每次询问 / 系统选择器"),
        Choice(AvenqirBrowserPreference.SYSTEM, "系统默认浏览器"),
        Choice(AvenqirBrowserPreference.BUILTIN, "内置浏览器（独立临时浏览）")
    ) + browsers()

    fun settings() {
        if (!canOpen()) return
        val options = choices()
        val current = AvenqirBrowserPreference.normalize(prefs.getString("default", null),
            options.map { it.key.removePrefix(AvenqirBrowserPreference.PACKAGE) }.toSet())
        AlertDialog.Builder(activity).setTitle("默认打开第三方网址")
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(),
                options.indexOfFirst { it.key == current }.coerceAtLeast(0)) { dialog, i ->
                if (canOpen()) prefs.edit().putString("default", options[i].key).apply()
                dialog.dismiss()
            }.setNegativeButton("取消", null).show()
    }

    fun open(url: String) {
        if (!canOpen() || !AvenqirUrlPolicy.isExternalHttps(url)) return
        val options = choices()
        val current = AvenqirBrowserPreference.normalize(prefs.getString("default", null),
            options.map { it.key.removePrefix(AvenqirBrowserPreference.PACKAGE) }.toSet())
        if (current != AvenqirBrowserPreference.ASK && launch(url, current)) return
        val items = options.map { if (it.key == AvenqirBrowserPreference.ASK)
            it.copy(label = "选择其他已安装浏览器 / 应用") else it }
        AlertDialog.Builder(activity)
            .setTitle("打开 " + (Uri.parse(url).host ?: "第三方网址"))
            .setItems(items.map { it.label }.toTypedArray()) { _, i ->
                if (!canOpen()) return@setItems
                val selected = items[i]
                if (selected.key == AvenqirBrowserPreference.ASK) {
                    launch(url, selected.key)
                } else {
                    AlertDialog.Builder(activity).setTitle("使用 " + selected.label)
                        .setMessage("仅更改打开方式，不代表第三方网站通过安全认证。")
                        .setNeutralButton("取消", null)
                        .setNegativeButton("仅本次") { _, _ -> launch(url, selected.key) }
                        .setPositiveButton("设为默认并打开") { _, _ ->
                            if (launch(url, selected.key))
                                prefs.edit().putString("default", selected.key).apply()
                        }.show()
                }
            }.setNegativeButton("取消", null).show()
    }
    private fun launch(url: String, key: String): Boolean {
        if (!canOpen() || !AvenqirUrlPolicy.isExternalHttps(url) || activity.isFinishing || activity.isDestroyed) return false
        return try {
            when {
                key == AvenqirBrowserPreference.BUILTIN -> activity.startActivity(
                    Intent(activity, AvenqirPublicBrowserActivity::class.java).putExtra("url", url))
                key == AvenqirBrowserPreference.SYSTEM -> {
                    // Resolve the generic web handler rather than prefer an app-specific deep link.
                    val defaultBrowser = activity.packageManager.resolveActivity(
                        view("https://example.com/"), PackageManager.MATCH_DEFAULT_ONLY)
                        ?.activityInfo?.packageName
                    val isBrowser = browsers().any { it.key == AvenqirBrowserPreference.PACKAGE + defaultBrowser }
                    if (defaultBrowser != null && isBrowser) activity.startActivity(view(url).setPackage(defaultBrowser))
                    else activity.startActivity(Intent.createChooser(view(url), "选择浏览器"))
                }
                key.startsWith(AvenqirBrowserPreference.PACKAGE) -> activity.startActivity(
                    view(url).setPackage(key.removePrefix(AvenqirBrowserPreference.PACKAGE)))
                else -> activity.startActivity(Intent.createChooser(view(url), "选择打开方式"))
            }
            true
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, "浏览器不可用，请选择其他方式", Toast.LENGTH_SHORT).show()
            false
        } catch (_: SecurityException) {
            Toast.makeText(activity, "系统拒绝打开，请选择其他方式", Toast.LENGTH_SHORT).show()
            false
        }
    }
}
