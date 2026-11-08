package de.kango99w.orbitadmin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Admin's own server profile; does not render ordinary account glyph state. */
@Composable
fun NativeAdminGlyphGallery(
    state:NativeGlyphSnapshot?, busy:Boolean, error:String,
    onRefresh:()->Unit, onCheckin:()->Unit, onDraw:()->Unit,
    onEquip:(String)->Unit,onBuy:(NativeGlyphItem)->Unit
) {
    var onlyOwned by remember { mutableStateOf(false) }
    Column(modifier=Modifier.fillMaxSize()) {
        Row(modifier=Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=7.dp),
            verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.SpaceBetween) {
            Column(modifier=Modifier.weight(1f)) {
                Text("✦ 管理员字符",style=MaterialTheme.typography.titleMedium)
                Text("独立管理身份 · 不借用普通用户收藏",
                    style=MaterialTheme.typography.labelSmall)
            }
            TextButton(enabled=!busy,onClick=onRefresh) {Text("刷新")}
        }
        if(error.isNotBlank()) Text(error.take(180),modifier=Modifier.padding(14.dp),
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
        if(state==null) {
            Column(modifier=Modifier.padding(18.dp),
                verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(if(busy)"正在核验字符资料…" else "没有可核验的管理员字符资料。",
                    style=MaterialTheme.typography.bodyMedium)
                Text("尚未部署独立字符 API 时，不会显示虚假积分或库存。",
                    style=MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled=!busy,onClick=onRefresh) {Text("重试")}
            }
        } else {
            Column(modifier=Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text("当前佩戴："+(state.equipped?.let { it.symbol+" "+it.name }
                    ?: "尚未佩戴"),style=MaterialTheme.typography.titleMedium)
                Text("已收集 "+state.owned+" / "+state.total+" · "+state.points+
                    " 积分 · 连续签到 "+state.streak+" 天",
                    style=MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(enabled=!busy && !state.checkedIn,onClick=onCheckin) {
                        Text(if(state.checkedIn)"已签到" else "签到")
                    }
                    OutlinedButton(enabled=!busy && state.draws>0,onClick=onDraw) {
                        Text("抽取 "+state.draws)
                    }
                    TextButton(onClick={onlyOwned=!onlyOwned}) {
                        Text(if(onlyOwned)"全部" else "已拥有")
                    }
                }
            }
            HorizontalDivider()
            val shown=if(onlyOwned)state.catalog.filter{it.owned} else state.catalog
            LazyColumn(modifier=Modifier.fillMaxSize(),
                contentPadding=PaddingValues(bottom=12.dp)) {
                items(shown,key={it.id}) { item ->
                    Row(modifier=Modifier.fillMaxWidth()
                        .padding(horizontal=14.dp,vertical=6.dp),
                        verticalAlignment=Alignment.CenterVertically) {
                        Box(modifier=Modifier.size(48.dp)
                            .background(MaterialTheme.colorScheme.secondaryContainer,CircleShape),
                            contentAlignment=Alignment.Center) {
                            Text(item.symbol.ifBlank{"✧"},
                                style=MaterialTheme.typography.titleLarge)
                        }
                        Spacer(Modifier.padding(horizontal=5.dp))
                        Column(modifier=Modifier.weight(1f)) {
                            Text(item.name,maxLines=1,
                                style=MaterialTheme.typography.titleSmall)
                            Text(item.rarity+" · "+(if(item.owned)"已拥有" else "未解锁"),
                                style=MaterialTheme.typography.labelSmall)
                        }
                        if(item.owned && !item.equipped)
                            TextButton(enabled=!busy,onClick={onEquip(item.id)}) {
                                Text("佩戴")
                            }
                        else if(!item.owned) {
                            val offer=state.shop.firstOrNull{it.id==item.id}
                            if(offer!=null) TextButton(enabled=!busy,onClick={onBuy(offer)}) {
                                Text("兑换")
                            }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
