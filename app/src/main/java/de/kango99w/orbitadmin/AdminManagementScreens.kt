package de.kango99w.orbitadmin

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.shape.CircleShape

/** Mobile-first domain list: search stays visible; the costly filter rows stay folded. */
@Composable
fun AdminDomainManagement(
    rows: List<AdminManagedDomain>, search: String, setSearch: (String)->Unit,
    source: String, setSource: (String)->Unit, state: String, setState: (String)->Unit,
    expanded: Boolean, onExpand: ()->Unit, busy: Boolean,
    inventoryStatus: String, inventory: List<AdminInventoryDomain> = emptyList(),
    onSelect: (AdminManagedDomain)->Unit
) {
    var provider by remember { mutableStateOf("全部") }
    var order by remember { mutableStateOf("名称") }
    var shown by remember { mutableStateOf(25) }
    val inventoryByName = inventory.associateBy { it.domain }
    Column(modifier=Modifier.fillMaxSize()) {
        OutlinedTextField(value=search,onValueChange={setSearch(it.take(100))},
            label={Text("搜索域名、来源、注册商、DNS")},singleLine=true,
            modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp))
        AdminFilterFold(label="域名筛选",expanded=expanded,
            activeSummary="来源："+source+" · 状态："+state,onToggle=onExpand) {
            Text("域名来源",style=MaterialTheme.typography.labelMedium)
            Row(modifier=Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf("全部","管理员邮箱","网站域名","域名申请","临时邮箱","托管库存").forEach { value ->
                    FilterChip(selected=source==value,onClick={setSource(value)},
                        label={Text(value,maxLines=1)})
                }
            }
            Text("登记状态",style=MaterialTheme.typography.labelMedium)
            Row(modifier=Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                (listOf("全部")+rows.map{it.state}.distinct().sorted()).forEach { value ->
                    FilterChip(selected=state==value,onClick={setState(value)},
                        label={Text(value,maxLines=1)})
                }
            }
            Text("注册商 / DNS 服务商",style=MaterialTheme.typography.labelMedium)
            Row(modifier=Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                (listOf("全部")+inventory.flatMap { listOf(it.registrar,it.dnsProvider) }
                    .filter { it.isNotBlank() }.distinct().sorted()).forEach { value ->
                    FilterChip(selected=provider==value,onClick={provider=value;shown=25},
                        label={Text(value,maxLines=1)})
                }
            }
            Row(modifier=Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf("名称","状态","来源").forEach { v ->
                    FilterChip(selected=order==v,onClick={order=v;shown=25},
                        label={Text("排序："+v,maxLines=1)})
                }
            }
            if(source!="全部"||state!="全部"||provider!="全部"||order!="名称")
                TextButton(onClick={setSource("全部");setState("全部");provider="全部";order="名称";shown=25}){
                    Text("清空筛选")
                }
        }
        val q=search.trim()
        val matching=rows.filter { item ->
            val inv=inventoryByName[item.domain]
            (q.isBlank()||listOf(item.domain,item.source,item.ingress,
                inv?.registrar.orEmpty(),inv?.dnsProvider.orEmpty(),
                inv?.websiteHost.orEmpty(),inv?.emailProvider.orEmpty())
                .any { it.contains(q,ignoreCase=true) }) &&
            (source=="全部"||item.source==source) &&
            (state=="全部"||item.state==state) &&
            (provider=="全部"||inv?.registrar==provider||inv?.dnsProvider==provider)
        }
        val filtered=when(order) {
            "状态"->matching.sortedWith(compareBy<AdminManagedDomain>{it.state}.thenBy{it.domain})
            "来源"->matching.sortedWith(compareBy<AdminManagedDomain>{it.source}.thenBy{it.domain})
            else->matching.sortedWith(compareBy<AdminManagedDomain>{it.domain}.thenBy{it.source})
        }
        Text("匹配 "+filtered.size+"/"+rows.size+" 个域名 · 仅代表登记及观测数据",
            modifier=Modifier.padding(horizontal=15.dp,vertical=3.dp),
            style=MaterialTheme.typography.labelSmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(inventoryStatus.isNotBlank())
            Text(inventoryStatus,maxLines=2,
                modifier=Modifier.padding(horizontal=15.dp,vertical=3.dp),
                style=MaterialTheme.typography.labelSmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(modifier=Modifier.fillMaxSize(),
            contentPadding=androidx.compose.foundation.layout.PaddingValues(
                horizontal=12.dp,vertical=8.dp),
            verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(filtered.isEmpty()&&!busy) item {
                Text("没有匹配的域名，请清除搜索或筛选。",modifier=Modifier.padding(14.dp))
            }
            items(filtered.take(shown)) { item ->
                val evidence=inventoryByName[item.domain]
                AdminSummaryCard(glyph=item.domain.take(1).uppercase(),
                    primary=item.domain,
                    secondary=item.source+" · "+item.state,
                    detail=listOfNotNull(evidence?.dnsProvider?.takeIf{it.isNotBlank()}
                        ?.let{"DNS："+it},evidence?.registrar?.takeIf{it.isNotBlank()}
                        ?.let{"注册商："+it}).joinToString(" · ")
                        .ifBlank{item.ingress.ifBlank{"查看详情 ›"}},
                    onClick={onSelect(item)})
            }
            if(shown<filtered.size)item {
                TextButton(onClick={shown+=25},modifier=Modifier.fillMaxWidth()) {
                    Text("显示更多域名 · 剩余 "+(filtered.size-shown))
                }
            }
        }
    }
}

@Composable
fun AdminAddressRecords(
    rows: List<AdminRecordedAddress>,search:String,setSearch:(String)->Unit,
    source:String,setSource:(String)->Unit,state:String,setState:(String)->Unit,
    expanded:Boolean,onExpand:()->Unit,busy:Boolean
) {
    var shown by remember { mutableStateOf(25) }
    Column(modifier=Modifier.fillMaxSize()) {
        OutlinedTextField(value=search,onValueChange={setSearch(it.take(100))},
            label={Text("搜索邮箱地址、来源")},singleLine=true,
            modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp))
        AdminFilterFold(label="记录筛选",expanded=expanded,
            activeSummary="状态："+state+" · 来源："+source,onToggle=onExpand) {
            Row(modifier=Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf("全部","已保留","已释放").forEach { value ->
                    FilterChip(selected=state==value,onClick={setState(value)},
                        label={Text(value,maxLines=1)})
                }
            }
            Row(modifier=Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                (listOf("全部")+rows.map{it.source}.filter{it.isNotBlank()}.distinct()).forEach { value ->
                    FilterChip(selected=source==value,onClick={setSource(value)},
                        label={Text(value,maxLines=1)})
                }
            }
            if(source!="全部"||state!="全部")
                TextButton(onClick={setSource("全部");setState("全部")}){Text("清空筛选")}
        }
        val filtered=AdminRecordFilters.addresses(rows,search,source,state)
        Text("匹配 "+filtered.size+"/"+rows.size+" 条 · 记录不代表仍可收件",
            modifier=Modifier.padding(horizontal=15.dp,vertical=3.dp),
            style=MaterialTheme.typography.labelSmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(modifier=Modifier.fillMaxSize(),
            contentPadding=androidx.compose.foundation.layout.PaddingValues(
                horizontal=12.dp,vertical=8.dp),
            verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(filtered.isEmpty()&&!busy) item {
                Text("没有匹配的使用记录。",modifier=Modifier.padding(14.dp))
            }
            items(filtered.take(shown)) { item ->
                AdminSummaryCard(item.address.take(1).uppercase(),item.address,
                    item.source+" · "+if(item.reserved)"已保留" else "已释放")
            }
            if(shown<filtered.size)item {
                TextButton(onClick={shown+=25},modifier=Modifier.fillMaxWidth()){
                    Text("显示更多 · 剩余 "+(filtered.size-shown))
                }
            }
        }
    }
}

@Composable
fun AdminAccountRegistrations(
    rows:List<AdminRegisteredAccount>,admin:String,search:String,setSearch:(String)->Unit,
    source:String,setSource:(String)->Unit,expanded:Boolean,onExpand:()->Unit,
    busy:Boolean,onSelect:(AdminRegisteredAccount)->Unit
) {
    var lifecycle by remember { mutableStateOf("全部") }
    var status by remember { mutableStateOf("全部") }
    var sort by remember { mutableStateOf("新到旧") }
    var shown by remember { mutableStateOf(20) }
    Column(modifier=Modifier.fillMaxSize()) {
        Text("管理员："+admin.ifBlank{"已验证"}+" · 仅登记的使用记录",
            modifier=Modifier.padding(horizontal=15.dp,vertical=2.dp),
            style=MaterialTheme.typography.labelSmall,maxLines=1,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value=search,onValueChange={setSearch(it.take(90))},
            label={Text("搜索服务、账户标签、关联邮箱")},singleLine=true,
            modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp))
        AdminFilterFold(label="登记来源筛选",expanded=expanded,
            activeSummary="来源："+source,onToggle=onExpand) {
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf("全部","固定邮箱","临时邮箱").forEach { value ->
                    FilterChip(selected=source==value,onClick={setSource(value);shown=20},
                        label={Text(value,maxLines=1)})
                }
            }
            Text("生命周期与状态",style=MaterialTheme.typography.labelMedium)
            Row(modifier=Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                (listOf("全部")+listOf("registered","active","attention","closed")).forEach { v ->
                    FilterChip(selected=lifecycle==v,onClick={lifecycle=v;shown=20},
                        label={Text(v,maxLines=1)})
                }
            }
            Row(modifier=Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf("全部","active","closed","unknown").forEach { v ->
                    FilterChip(selected=status==v,onClick={status=v;shown=20},
                        label={Text(v,maxLines=1)})
                }
            }
            Row(modifier=Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf("新到旧","旧到新","平台名称").forEach { v ->
                    FilterChip(selected=sort==v,onClick={sort=v;shown=20},
                        label={Text(v,maxLines=1)})
                }
            }
            TextButton(onClick={setSource("全部");lifecycle="全部";status="全部";
                sort="新到旧";shown=20}){Text("清空筛选")}
        }
        val nativeSource=when(source) {
            "固定邮箱"->"pieterj"
            "临时邮箱"->"ordinary"
            else->"全部"
        }
        val filtered=AdminRecordFilters.registrations(
            rows,search,nativeSource,lifecycle,status,sort)
        Text("最近 "+rows.size+" 条登记 · 匹配 "+filtered.size+" 条",
            modifier=Modifier.padding(horizontal=15.dp,vertical=3.dp),
            style=MaterialTheme.typography.labelSmall)
        LazyColumn(modifier=Modifier.fillMaxSize(),
            contentPadding=androidx.compose.foundation.layout.PaddingValues(
                horizontal=12.dp,vertical=8.dp),
            verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(filtered.isEmpty()&&!busy) item {
                Text("没有可展示的登记记录，不会伪造用户信息。",
                    modifier=Modifier.padding(14.dp))
            }
            items(filtered.take(shown),key={it.id}) { item ->
                AdminSummaryCard(glyph=item.service.take(1).uppercase().ifBlank{"@"},
                    primary=item.service.ifBlank{"未命名服务"},
                    secondary=item.accountLabel.ifBlank{item.address},
                    detail=(if(item.source=="pieterj") "固定邮箱" else "临时邮箱")+
                        " · "+item.status+"  · 查看详情 ›",
                    onClick={onSelect(item)})
            }
            if(shown<filtered.size)item {
                TextButton(onClick={shown+=20},modifier=Modifier.fillMaxWidth()){
                    Text("显示更多 · 剩余 "+(filtered.size-shown))
                }
            }
        }
    }
}

@Composable
private fun AdminSummaryCard(
    glyph:String, primary:String, secondary:String, detail:String="",
    onClick:(()->Unit)?=null
) {
    Card(modifier=Modifier.fillMaxWidth().then(
        if(onClick!=null)Modifier.clickable(onClick=onClick)else Modifier),
        shape=RoundedCornerShape(16.dp),
        colors=CardDefaults.cardColors(
            containerColor=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.44f))) {
        Row(modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=12.dp),
            verticalAlignment=Alignment.CenterVertically) {
            Surface(shape=CircleShape,color=MaterialTheme.colorScheme.primaryContainer) {
                androidx.compose.foundation.layout.Box(
                    modifier=Modifier.size(38.dp),contentAlignment=Alignment.Center) {
                    Text(glyph.take(2),style=MaterialTheme.typography.titleMedium,
                        color=MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Spacer(Modifier.size(10.dp))
            Column(modifier=Modifier.weight(1f),
                verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(primary,maxLines=1,style=MaterialTheme.typography.titleSmall)
                Text(secondary,maxLines=1,style=MaterialTheme.typography.bodySmall)
                if(detail.isNotBlank())Text(detail,maxLines=2,
                    style=MaterialTheme.typography.labelSmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
