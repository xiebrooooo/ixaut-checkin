package edu.xaut.xilicheckin.data.repo

import android.content.Context
import edu.xaut.xilicheckin.domain.QrParser
import edu.xaut.xilicheckin.domain.ScanTarget
import org.json.JSONObject

/** 记住上次扫码目标 + 字段覆盖项（不存任何密钥） */
class ConfigRepository(context: Context) {

    private val sp = context.getSharedPreferences("xili_config", Context.MODE_PRIVATE)

    fun saveTarget(t: ScanTarget) {
        sp.edit()
            .putString("target_raw", t.raw)
            .putString("appId", t.appId)
            .putString("pageId", t.pageId)
            .apply()
    }

    fun loadTarget(): ScanTarget? {
        val raw = sp.getString("target_raw", null) ?: return null
        val parsed = QrParser.parse(raw)
        if (parsed.ok) return parsed
        val appId = sp.getString("appId", null).orEmpty()
        val pageId = sp.getString("pageId", null).orEmpty()
        return if (appId.isNotEmpty() && pageId.isNotEmpty()) parsed.copy(appId = appId, pageId = pageId) else null
    }

    fun saveOverrides(map: Map<String, String>) {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        sp.edit().putString("overrides", obj.toString()).apply()
    }

    fun loadOverrides(): Map<String, String> {
        val s = sp.getString("overrides", null) ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        runCatching {
            val obj = JSONObject(s)
            obj.keys().forEach { k -> out[k] = obj.optString(k) }
        }
        return out
    }
}
