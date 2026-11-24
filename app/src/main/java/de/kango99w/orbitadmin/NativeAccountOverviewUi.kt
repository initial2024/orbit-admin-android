package de.kango99w.orbitadmin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private fun quotaLabel(quota: NativeAccountQuota): String =
    if (quota.unit == "bytes") {
        String.format(Locale.ROOT, "%.1f / %.1f MiB",
            quota.used / 1048576.0, quota.limit / 1048576.0)
    } else quota.used.toString()+" / "+quota.limit+" · 剩余 "+maxOf(0,quota.limit-quota.used)

private fun localTime(timestamp: Long?): String {
    if(timestamp == null || timestamp !in 946684800000L..4102444800000L)
        return "未提供"
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT)
        .format(Date(timestamp))
}

/** Advisory snapshot only; creation stays under server transaction and human check. */
@Composable
fun NativeMailboxQuotaSummary(data: NativeAccountOverview?, loading: Boolean) {
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text("创建额度 · 服务器快照",style=MaterialTheme.typography.titleSmall)
        if(loading) {
            Text("正在核实额度…",style=MaterialTheme.typography.bodySmall)
            return@Column
        }
        if (data == null || !data.quotaAvailable) {
            Text("当前无法核实账号额度；不可将未核实视为有余量。",
                style=MaterialTheme.typography.bodySmall)
            return@Column
        }
        Text("网页登录账号："+data.label.ifBlank { data.email.ifBlank { "已认证" } },
            style=MaterialTheme.typography.labelSmall,maxLines=1)
        val active = data.quotas.firstOrNull { it.key=="active_mailboxes" }
        val created = data.quotas.firstOrNull { it.key=="mailbox_create_today" }
        val custom = data.quotas.firstOrNull { it.key=="custom_prefix_today" }
        if(active != null) Text("活跃邮箱  "+quotaLabel(active),
            style=MaterialTheme.typography.bodySmall)
        if(created != null) Text("今日创建  "+quotaLabel(created),
            style=MaterialTheme.typography.bodySmall)
        if(custom != null) Text("今日自定义  "+quotaLabel(custom),
            style=MaterialTheme.typography.bodySmall)
        val cap=data.mailboxCapacity
        if(cap != null) {
            Text("全站同时占用  "+cap.globalUsed+" / "+cap.globalLimit+
                " · 余 "+cap.globalRemaining,style=MaterialTheme.typography.bodySmall)
            if(cap.pool=="free")
                Text("Free 池 "+cap.freeUsed+" / "+cap.freeLimit+
                    " · 非付费合计 "+cap.nonpaidUsed+" / "+cap.nonpaidLimit,
                    style=MaterialTheme.typography.bodySmall)
            else if(cap.pool=="trial")
                Text("非付费池 "+cap.nonpaidUsed+" / "+cap.nonpaidLimit,
                    style=MaterialTheme.typography.bodySmall)
            Text("该套餐容量池预估余 "+cap.estimatedAvailable+
                "；域名服务商剩余尚未校验。",style=MaterialTheme.typography.bodySmall)
        } else {
            Text("全站总容量：未核验（服务器暂未发布该字段）。",
                style=MaterialTheme.typography.bodySmall)
        }
        Text("本数据属于网页登录账号，不证明与当前原生设备绑定的是同一账号；实际创建仍以服务器校验为准。",
            style=MaterialTheme.typography.labelSmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Compact native account center with independent auth and no fabricated state. */
@Composable
fun NativeAccountOverviewDialog(
    data: NativeAccountOverview?, loading: Boolean, error: String,
    onRefresh: () -> Unit, onDismiss: () -> Unit, onClosure: () -> Unit
) {
    var showQuota by remember { mutableStateOf(false) }
    var showBenefits by remember { mutableStateOf(false) }
    var showSecurity by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest=onDismiss,
        title={Text("账户 · 权益与安全",style=MaterialTheme.typography.titleLarge)},
        text={
            Column(modifier=Modifier.fillMaxWidth().heightIn(max=470.dp)
                .verticalScroll(rememberScrollState()),
                verticalArrangement=Arrangement.spacedBy(9.dp)) {
                if(loading) Text("正在读取账号状态…")
                if(error.isNotBlank()) Text(error.take(160),
                    color=MaterialTheme.colorScheme.error,
                    style=MaterialTheme.typography.bodySmall)
                if(data==null) {
                    Text("没有可核验的网页登录账号资料。原生设备凭据不能代替会员、额度或注销权限。",
                        style=MaterialTheme.typography.bodySmall)
                } else {
                    Text(data.label.ifBlank { data.email.ifBlank { "普通账号" } },
                        style=MaterialTheme.typography.titleMedium,maxLines=1)
                    Text("会员："+(data.planLabel ?: "未核验")+" · "+
                        (data.planExpiresAt?.let { "至 "+localTime(it) } ?: "无可核验到期日"),
                        style=MaterialTheme.typography.bodySmall)
                    if(data.expiryReminder)
                        Text("会员临近到期；可展开查看可核验的未使用权益。",
                            style=MaterialTheme.typography.bodySmall,
                            color=MaterialTheme.colorScheme.error)
                    if(data.latestExpiredAt!=null && data.planKey=="free")
                        Text("历史会员于 "+localTime(data.latestExpiredAt)+
                            " 到期。当前 Free 数据不能反推过去是否使用过哪些权益。",
                            style=MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    OutlinedButton(onClick={showQuota=!showQuota},modifier=Modifier.fillMaxWidth()) {
                        Text("邮箱与文件额度 "+(if(showQuota)"▴" else "▾"))
                    }
                    if(showQuota) {
                        NativeMailboxQuotaSummary(data,false)
                        data.quotas.filter { it.key !in setOf("active_mailboxes",
                            "mailbox_create_today","custom_prefix_today") }.forEach { quota ->
                                Text(quota.title+"  "+quotaLabel(quota),
                                    style=MaterialTheme.typography.bodySmall)
                            }
                    }
                    OutlinedButton(onClick={showBenefits=!showBenefits},modifier=Modifier.fillMaxWidth()) {
                        Text("会员权益使用记录 "+(if(showBenefits)"▴" else "▾"))
                    }
                    if(showBenefits) {
                        if(data.unusedBenefits.isNotEmpty() && data.planKey!="free") {
                            data.unusedBenefits.forEach { key ->
                                Text("未使用："+NativeAccountOverviewModels.benefitLabel(key),
                                    style=MaterialTheme.typography.bodySmall)
                            }
                            if(data.usageCoverage!="full")
                                Text("记录覆盖可能不完整；未使用仅指可见审计区间。",
                                    style=MaterialTheme.typography.bodySmall)
                        } else Text(
                            "当前没有可核验的未使用权益；过期套餐必须有历史快照，不能猜测。",
                            style=MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick={showSecurity=!showSecurity},modifier=Modifier.fillMaxWidth()) {
                        Text("账号详情 / 注销 "+(if(showSecurity)"▴" else "▾"))
                    }
                    if(showSecurity) {
                        Text("账号："+data.email.ifBlank { data.label.ifBlank{"未提供"} },
                            style=MaterialTheme.typography.bodySmall)
                        Text("会话有效期："+localTime(data.sessionExpiresAt),
                            style=MaterialTheme.typography.bodySmall)
                        Text("注销申请："+(data.closureState ?: "未提交或未核验"),
                            style=MaterialTheme.typography.bodySmall)
                        Text("注销需服务端二次验证、准确邮箱和不可逆操作确认；不会点击一次就删除账号。",
                            style=MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick=onClosure,enabled=data.closureAvailable,
                            modifier=Modifier.fillMaxWidth()) {
                            Text("查看注销验证要求")
                        }
                    }
                    Text("网页登录账号与管理员、原生设备身份不可自动合并。",
                        style=MaterialTheme.typography.labelSmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton={TextButton(onClick=onRefresh,enabled=!loading){Text("刷新")}},
        dismissButton={TextButton(onClick=onDismiss){Text("关闭")}}
    )
}
