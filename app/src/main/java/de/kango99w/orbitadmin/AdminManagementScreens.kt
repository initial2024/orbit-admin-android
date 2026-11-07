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
import androidx.compose.foundation.shape.CircleShape

/** Mobile-first domain list: search stays visible; the costly filter rows stay folded. */
@Composable
fun AdminDomainManagement(
    rows: List<AdminManagedDomain>, search: String, setSearch: (String)->Unit,
    source: String, setSource: (String)->Unit, state: String, setState: (String)->Unit,
    expanded: Boolean, onExpand: ()->Unit, busy: Boolean,
    inventoryStatus: String, onSelect: (AdminManagedDomain)->Unit
) {
    Column(modifier=Modifier.fillMaxSize()) {
        OutlinedTextField(value=search,onValueChange={setSearch(it.take(100))},
            label={Text("搜索域名、来源与接入")},singleLine=true,
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
            if(source!="全部"||state!="全部")
                TextButton(onClick={setSource("全部");setState("全部")}){Text("清空筛选")}
        }
        val q=search.trim()
        val filtered=rows.filter { item ->
            (q.isBlank()||listOf(item.domain,item.source,item.ingress).any{
                it.contains(q,ignoreCase=true)}) &&
                (source=="全部"||item.source==source) &&
                (state=="全部"||item.state==state)
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
            items(filtered) { item ->
                AdminSummaryCard(glyph=item.domain.take(1).uppercase(),
                    primary=item.domain,secondary=item.source+" · "+item.state,
                    detail=if(item.ingress.isBlank()) "查看域名详情 ›"
                        else item.ingress+" · 详情 ›",
                    onClick={onSelect(item)})
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
        val q=search.trim()
        val filtered=rows.filter { item ->
            (q.isBlank()||item.address.contains(q,ignoreCase=true)||
                item.source.contains(q,ignoreCase=true)) &&
                (source=="全部"||item.source==source) &&
                (state=="全部"||(state=="已保留"&&item.reserved)||
                    (state=="已释放"&&!item.reserved))
        }
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
            items(filtered) { item ->
                AdminSummaryCard(item.address.take(1).uppercase(),item.address,
                    item.source+" · "+if(item.reserved)"已保留" else "已释放")
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
    Column(modifier=Modifier.fillMaxSize()) {
        Text("当前管理员："+admin.ifBlank{"已验证"},
            modifier=Modifier.padding(horizontal=15.dp,vertical=4.dp),
            style=MaterialTheme.typography.titleSmall)
        Text("以下仅为邮箱关联的平台注册记录，不等于全部普通平台用户。",
            modifier=Modifier.padding(horizontal=15.dp,vertical=2.dp),
            style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value=search,onValueChange={setSearch(it.take(90))},
            label={Text("搜索服务、账户标签、关联邮箱")},singleLine=true,
            modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp))
        AdminFilterFold(label="登记来源筛选",expanded=expanded,
            activeSummary="来源："+source,onToggle=onExpand) {
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf("全部","固定邮箱","临时邮箱").forEach { value ->
                    FilterChip(selected=source==value,onClick={setSource(value)},
                        label={Text(value,maxLines=1)})
                }
            }
        }
        val q=search.trim()
        val filtered=rows.filter { item ->
            (q.isBlank()||listOf(item.service,item.accountLabel,item.address).any{
                it.contains(q,ignoreCase=true)}) &&
            (source=="全部"||(source=="固定邮箱"&&item.source=="pieterj")||
                (source=="临时邮箱"&&item.source=="ordinary"))
        }
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
            items(filtered,key={it.id}) { item ->
                AdminSummaryCard(glyph=item.service.take(1).uppercase().ifBlank{"@"},
                    primary=item.service.ifBlank{"未命名服务"},
                    secondary=item.accountLabel.ifBlank{item.address},
                    detail=(if(item.source=="pieterj") "固定邮箱" else "临时邮箱")+
                        " · "+item.status+"  · 查看详情 ›",
                    onClick={onSelect(item)})
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
