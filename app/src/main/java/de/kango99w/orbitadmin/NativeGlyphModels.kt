package de.kango99w.orbitadmin

import org.json.JSONObject

data class NativeGlyphItem(
    val id: String, val name: String, val symbol: String, val rarity: String,
    val owned: Boolean, val equipped: Boolean, val price: Int, val description: String
)
data class NativeGlyphSnapshot(
    val checkedIn: Boolean, val streak: Int, val points: Int, val draws: Int,
    val equipped: NativeGlyphItem?, val owned: Int, val total: Int,
    val catalog: List<NativeGlyphItem>, val shop: List<NativeGlyphItem>,
    val collections: List<String>, val sets: List<String>
)

object NativeGlyphModels {
    private val idPattern = Regex("^[a-zA-Z0-9_-]{1,72}$")
    fun validId(id: String): Boolean = idPattern.matches(id)

    private fun glyph(row: JSONObject, price: Int = 0): NativeGlyphItem? {
        val id = row.optString("id")
        if (!validId(id)) return null
        return NativeGlyphItem(
            id=id, name=row.optString("zh").ifBlank {
                row.optString("name").ifBlank { id }
            }.take(72),
            symbol=row.optString("symbol").take(12),
            rarity=row.optString("rarity").take(25),
            owned=row.optBoolean("owned",false),equipped=row.optBoolean("equipped",false),
            price=price.coerceIn(0,100_000),
            description=row.optString("wish_zh").ifBlank {
                row.optString("lore_zh")
            }.take(120)
        )
    }

    fun read(status: JSONObject, collectionStatus: JSONObject? = null): NativeGlyphSnapshot {
        val catalog = status.optJSONArray("catalog")
        val catalogItems=(0 until minOf(catalog?.length() ?: 0, 130)).mapNotNull {
            catalog?.optJSONObject(it)?.let { item -> glyph(item) }
        }
        val shop = status.optJSONObject("shop")?.optJSONArray("items")
        val shopItems=(0 until minOf(shop?.length() ?: 0, 12)).mapNotNull {
            shop?.optJSONObject(it)?.let { row -> glyph(row,row.optInt("price_points",0)) }
        }
        val equipped = status.optJSONObject("equipped_glyph")?.let { glyph(it) }
        val collection=status.optJSONObject("collection")
        val extras = arrayListOf<String>()
        val sets = arrayListOf<String>()
        val events=collectionStatus?.optJSONArray("event_items")
        val variants=collectionStatus?.optJSONArray("variants")
        val setRows=collectionStatus?.optJSONArray("sets")
        for ((group,rows) in listOf("节日角色" to events,"外观变体" to variants)) {
            for (i in 0 until minOf(rows?.length() ?: 0, 40)) {
                val row=rows?.optJSONObject(i) ?: continue
                extras.add(group+" · "+row.optString("zh").take(65)+
                    if(row.optBoolean("owned")) " · 已拥有" else " · 未解锁")
            }
        }
        for(i in 0 until minOf(setRows?.length() ?: 0, 30)) {
            val row=setRows?.optJSONObject(i) ?: continue
            sets.add(row.optString("zh").ifBlank{row.optString("id")}.take(80)+
                if(row.optBoolean("claimed")) " · 已领取" else " · 未完成")
        }
        return NativeGlyphSnapshot(
            checkedIn=status.optBoolean("checked_in_today"),
            streak=status.optInt("streak").coerceIn(0,5000),
            points=status.optInt("points").coerceAtLeast(0),
            draws=status.optInt("available_draws").coerceAtLeast(0),
            equipped=equipped,
            owned=collection?.optInt("owned")?.coerceAtLeast(0) ?: 0,
            total=collection?.optInt("total")?.coerceAtLeast(0) ?: catalogItems.size,
            catalog=catalogItems,shop=shopItems,collections=extras.take(80),sets=sets.take(30)
        )
    }
}
