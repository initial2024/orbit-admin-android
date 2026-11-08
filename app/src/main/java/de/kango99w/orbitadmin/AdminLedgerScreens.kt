package de.kango99w.orbitadmin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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

/** The same three underlying record types as the website, with reserved
 * addresses clearly separated from platform-registration history. */
@Composable
fun AdminLedgerTabStrip(selected:String,onSelect:(String)->Unit) {
    Row(modifier=Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        .padding(horizontal=12.dp,vertical=2.dp),
        horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        listOf("internal" to "平台注册","external" to "外部邮箱",
            "reserved" to "地址保留").forEach { (id,label) ->
            FilterChip(selected=selected==id,onClick={onSelect(id)},
                label={Text(label,maxLines=1)})
        }
    }
}

/** Condensed record list. Full usage details are fetched on user tap. */
@Composable
fun AdminExternalEmailLedger(rows:List<AdminExternalEmail>,busy:Boolean,
    onSelect:(AdminExternalEmail)->Unit,
    recorded:List<AdminRecordedAddress>,allowedDomains:Set<String>,
    activeMailboxes:List<AdminNativeMailbox>,
    onCopy:(String)->Unit,onActivate:(AdminMailboxActivationIntent)->Unit
) {
    var search by remember { mutableStateOf("") }
    var filters by remember { mutableStateOf(false) }
    var provider by remember { mutableStateOf("全部") }
    var status by remember { mutableStateOf("全部") }
    var order by remember { mutableStateOf("最近更新") }
    var shown by remember { mutableStateOf(20) }
    val filtered=AdminRecordFilters.external(rows,search,provider,status,order)
    Column(modifier=Modifier.fillMaxSize()) {
        OutlinedTextField(value=search,onValueChange={search=it.take(100)},
            label={Text("搜索邮箱、用途、服务商、备注")},singleLine=true,
            modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=3.dp))
        AdminFilterFold(label="外部邮箱筛选",expanded=filters,
            activeSummary="服务商："+provider+" · "+status+" · "+order,
            onToggle={filters=!filters}) {
            Text("服务商",style=MaterialTheme.typography.labelSmall)
            Row(modifier=Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                (listOf("全部")+rows.map { it.provider }.distinct().sorted()).forEach { value ->
                    FilterChip(selected=provider==value,onClick={provider=value},
                        label={Text(if(value=="全部")value
                            else AdminExternalEmailModels.providerLabel(value),maxLines=1)})
                }
            }
            Text("状态 / 排序",style=MaterialTheme.typography.labelSmall)
            Row(modifier=Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf("全部","active","disabled","unknown").forEach { value ->
                    FilterChip(selected=status==value,onClick={status=value},
                        label={Text(if(value=="全部")value
                            else AdminExternalEmailModels.statusLabel(value),maxLines=1)})
                }
            }
            Row(modifier=Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf("最近更新","注册量","地址").forEach { value ->
                    FilterChip(selected=order==value,onClick={order=value},
                        label={Text(value,maxLines=1)})
                }
            }
            TextButton(onClick={provider="全部";status="全部";order="最近更新";search="";shown=20}){
                Text("清除筛选")
            }
        }
        Text("已读取 "+rows.size+" 个外部邮箱 · 匹配 "+filtered.size+
            " · 仅台账，不代表已开通收件",
            modifier=Modifier.padding(horizontal=14.dp,vertical=2.dp),
            style=MaterialTheme.typography.labelSmall)
        LazyColumn(modifier=Modifier.fillMaxSize(),
            contentPadding=androidx.compose.foundation.layout.PaddingValues(
                horizontal=12.dp,vertical=6.dp),
            verticalArrangement=Arrangement.spacedBy(6.dp)) {
            if(filtered.isEmpty()&&!busy)item {
                Text("未找到匹配台账，或服务器未提供记录。",
                    modifier=Modifier.padding(12.dp))
            }
            items(filtered.take(shown),key={it.id}) { mail ->
                val reserved=recorded.firstOrNull { it.externalId==mail.id &&
                    it.address.equals(mail.address,ignoreCase=true) }
                val activation=reserved?.let {
                    AdminMailboxActivationPolicy.forRecord(it,allowedDomains,activeMailboxes)
                }
                Card(modifier=Modifier.fillMaxWidth().clickable { onSelect(mail) }) {
                    Column(modifier=Modifier.padding(horizontal=12.dp,vertical=9.dp),
                        verticalArrangement=Arrangement.spacedBy(2.dp)) {
                        Text(mail.address,maxLines=1,style=MaterialTheme.typography.titleSmall)
                        Text(AdminExternalEmailModels.providerLabel(mail.provider)+" · "+
                            AdminExternalEmailModels.statusLabel(mail.status)+" · 注册 "+
                            mail.registrationCount+" 条",
                            maxLines=1,style=MaterialTheme.typography.labelSmall)
                        if(mail.label.isNotBlank()||mail.purpose.isNotBlank())
                            Text((mail.label+" · "+mail.purpose).trim(' ','·'),
                                maxLines=1,style=MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick={onCopy(mail.address)}) { Text("复制邮箱") }
                            if(activation!=null)
                                TextButton(enabled=!busy,onClick={onActivate(activation)}) {
                                    Text(activation.label)
                                }
                        }
                    }
                }
            }
            if(shown<filtered.size)item {
                TextButton(modifier=Modifier.fillMaxWidth(),
                    onClick={shown+=20}) { Text("显示更多 · 剩余 "+(filtered.size-shown)) }
            }
        }
    }
}

@Composable
fun AdminExternalEmailDetailDialog(
    selected:AdminExternalEmail,detail:AdminExternalEmailDetail?,
    loading:Boolean,error:String,onDismiss:()->Unit,onRetry:()->Unit
) {
    var visible by remember(selected.id) { mutableStateOf(12) }
    val mail=detail?.email ?: selected
    AlertDialog(onDismissRequest=onDismiss,
        title={Text("外部邮箱详情",style=MaterialTheme.typography.titleMedium)},
        text={Column(modifier=Modifier.fillMaxWidth().heightIn(max=410.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement=Arrangement.spacedBy(7.dp)) {
            Text(mail.address,style=MaterialTheme.typography.titleSmall)
            Text(AdminExternalEmailModels.providerLabel(mail.provider)+" · "+
                AdminExternalEmailModels.statusLabel(mail.status),
                style=MaterialTheme.typography.labelSmall)
            if(mail.label.isNotBlank())Text("标签："+mail.label)
            if(mail.purpose.isNotBlank())Text("用途："+mail.purpose)
            if(mail.note.isNotBlank())Text("备注："+mail.note,
                style=MaterialTheme.typography.bodySmall)
            Text("平台注册 "+mail.registrationCount+" 条 · 外部邮箱并不归原生临时收信系统托管",
                style=MaterialTheme.typography.labelSmall)
            HorizontalDivider()
            if(loading)Text("正在核验此邮箱的平台注册记录…")
            else if(error.isNotBlank())
                Text(error.take(140),color=MaterialTheme.colorScheme.error)
            else if(detail!=null) {
                if(detail.registrations.isEmpty())Text("暂无登记的平台使用记录")
                detail.registrations.take(visible).forEach { item ->
                    Text(item.service.ifBlank{"未命名平台"}+" · "+item.lifecycle,
                        style=MaterialTheme.typography.titleSmall,maxLines=1)
                    if(item.accountLabel.isNotBlank())
                        Text("账号："+item.accountLabel,maxLines=1)
                    if(item.note.isNotBlank())
                        Text("用途："+item.note,maxLines=2,
                            style=MaterialTheme.typography.bodySmall)
                    if(item.registeredAt>0)
                        Text("登记："+DateFormat.getDateInstance().format(
                            Date(item.registeredAt)),style=MaterialTheme.typography.labelSmall)
                    HorizontalDivider()
                }
                if(visible<detail.registrations.size)
                    TextButton(onClick={visible+=12}) {
                        Text("继续查看 · 余 "+(detail.registrations.size-visible)+" 条")
                    }
            }
        }},
        confirmButton={TextButton(onClick=onRetry,enabled=!loading){Text("刷新详情")}},
        dismissButton={TextButton(onClick=onDismiss){Text("关闭")}}
    )
}
