package de.kango99w.orbitadmin

import java.text.Normalizer
import java.util.Locale

/** Search only already-authorized rows, never synthesize a mailbox or a profile. */
object AdminRecordFilters {
    private fun normalized(input:String)=
        Normalizer.normalize(input.take(120),Normalizer.Form.NFKC)
            .trim().lowercase(Locale.ROOT)
    fun registrations(rows:List<AdminRegisteredAccount>,query:String,
        source:String="全部",lifecycle:String="全部",status:String="全部",
        order:String="新到旧"):List<AdminRegisteredAccount> {
        val q=normalized(query)
        val filtered=rows.filter { row ->
            (q.isEmpty() || listOf(row.service,row.accountLabel,row.address,row.note)
                .any { normalized(it).contains(q) }) &&
            (source=="全部"||row.source==source) &&
            (lifecycle=="全部"||row.lifecycle==lifecycle) &&
            (status=="全部"||row.status==status)
        }
        return when(order) {
            "旧到新"->filtered.sortedWith(compareBy<AdminRegisteredAccount>
                { it.registeredAt }.thenBy { it.id })
            "平台名称"->filtered.sortedWith(compareBy<AdminRegisteredAccount>
                { normalized(it.service) }.thenByDescending { it.registeredAt })
            else->filtered.sortedWith(compareByDescending<AdminRegisteredAccount>
                { it.registeredAt }.thenBy { it.id })
        }
    }
    fun external(rows:List<AdminExternalEmail>,query:String,
        provider:String="全部",status:String="全部",
        order:String="最近更新"):List<AdminExternalEmail> {
        val q=normalized(query)
        val filtered=rows.filter { row ->
            (q.isEmpty() || listOf(row.address,row.label,row.purpose,row.note,row.provider)
                .any { normalized(it).contains(q) }) &&
            (provider=="全部"||row.provider==provider) &&
            (status=="全部"||row.status==status)
        }
        return when(order) {
            "注册量"->filtered.sortedWith(compareByDescending<AdminExternalEmail>
                { it.registrationCount }.thenBy { it.address })
            "地址"->filtered.sortedBy { normalized(it.address) }
            else->filtered.sortedWith(compareByDescending<AdminExternalEmail>
                { it.updatedAt }.thenBy { it.address })
        }
    }
    fun addresses(rows:List<AdminRecordedAddress>,query:String,
        source:String="全部",state:String="全部"):List<AdminRecordedAddress> {
        val q=normalized(query)
        return rows.filter { row ->
            (q.isEmpty() || normalized(row.address).contains(q) ||
                normalized(row.source).contains(q)) &&
            (source=="全部"||row.source==source) &&
            (state=="全部"||(state=="已保留"&&row.reserved)||
                (state=="已释放"&&!row.reserved))
        }.sortedBy { normalized(it.address) }
    }
}
