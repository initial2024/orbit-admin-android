package de.kango99w.orbitadmin

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Html
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** Shared native reader for ordinary and administrator mail; no embedded browser. */
@Composable
fun NativeMailReadingContent(
    messageKey: String,
    plainText: String,
    htmlBody: String,
    safeLinks: List<NativeSafeMailLink>
) {
    val context=LocalContext.current
    var formatted by remember(messageKey) { mutableStateOf(htmlBody.isNotBlank()) }
    var confirmLink by remember(messageKey) { mutableStateOf<NativeSafeMailLink?>(null) }
    var notice by remember(messageKey) { mutableStateOf("") }
    val color=MaterialTheme.colorScheme.onSurface.toArgb()
    val html=htmlBody.take(16000)
    val text=plainText.take(16000)

    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            if(html.isNotBlank()) {
                OutlinedButton(onClick={formatted=true}) {
                    Text(if(formatted)"✓ 排版正文" else "排版正文")
                }
                if(text.isNotBlank()) OutlinedButton(onClick={formatted=false}) {
                    Text(if(!formatted)"✓ 纯文本" else "纯文本")
                }
            } else Text("纯文本正文",style=MaterialTheme.typography.labelMedium)
        }
        if(formatted && html.isNotBlank()) {
            AndroidView(modifier=Modifier.fillMaxWidth(),
                factory={ctx -> TextView(ctx).apply {
                    textSize=16f
                    setTextIsSelectable(true)
                    autoLinkMask=0
                    linksClickable=false
                    movementMethod=null
                }},
                update={view ->
                    view.setTextColor(color)
                    // Sanitized first-party HTML: native spans only, never a WebView.
                    view.text=try { Html.fromHtml(html,Html.FROM_HTML_MODE_COMPACT) }
                        catch (_: Exception) { text }
                }
            )
        } else SelectionContainer {
            Text(text.ifBlank { "此邮件没有可读取的文字正文。" },
                style=MaterialTheme.typography.bodyLarge)
        }
        Text("仅在本机排版；远程图片与脚本不会自动加载。",
            style=MaterialTheme.typography.labelSmall)
        HorizontalDivider()
        Text("提取的链接 · "+safeLinks.size,style=MaterialTheme.typography.titleMedium)
        if(safeLinks.isEmpty()) {
            Text("未找到有效 HTTPS 链接。附件、二维码及未升级服务端的隐藏超链接可能无法识别。",
                style=MaterialTheme.typography.bodySmall)
        } else safeLinks.take(12).forEach { link ->
            Column(modifier=Modifier.fillMaxWidth().padding(vertical=4.dp),
                verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(link.label,style=MaterialTheme.typography.titleSmall)
                SelectionContainer { Text(link.url,style=MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={
                        val manager=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        manager.setPrimaryClip(ClipData.newPlainText("邮件链接",link.url))
                        notice="链接已复制；请注意剪贴板隐私。"
                    }) { Text("复制链接") }
                    OutlinedButton(onClick={confirmLink=link}) { Text("查看链接") }
                }
                HorizontalDivider()
            }
        }
        if(notice.isNotBlank()) Text(notice,style=MaterialTheme.typography.bodySmall)
    }
    confirmLink?.let { target ->
        AlertDialog(
            onDismissRequest={confirmLink=null},
            title={Text("确认打开外部链接？")},
            text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("链接来自邮件，不代表发送方可信。请先核对域名：")
                Text(target.host,style=MaterialTheme.typography.titleMedium)
                SelectionContainer { Text(target.url,style=MaterialTheme.typography.bodySmall) }
                Text("确认后才会交给外部浏览器，不在 APK 内自动登录。",
                    style=MaterialTheme.typography.bodySmall)
            }},
            confirmButton={TextButton(onClick={
                confirmLink=null
                val safe=NativeMailLinks.approved(target.url)
                if(safe==null) notice="链接已被安全校验拒绝。"
                else try {
                    val intent=Intent(Intent.ACTION_VIEW,Uri.parse(safe.url))
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    notice="设备没有可用的外部浏览器。"
                } catch (_: SecurityException) {
                    notice="浏览器调用受到系统限制。"
                }
            }) { Text("用浏览器打开") }},
            dismissButton={TextButton(onClick={confirmLink=null}) { Text("取消") }}
        )
    }
}
