package edu.xaut.xilicheckin.domain

import java.net.URI
import java.net.URLDecoder

/**
 * 扫码内容解析。
 *
 * 现场二维码没规定格式，所以不假设：URL / JSON / 裸 token 三种形态全解，
 * 再把 query 里的参数摊平当成表单预填值。
 *
 * 已知形态（用户样例）：
 *   https://kqtj.xaut.edu.cn/adp/runtime/685488207969587200/manage/685488208372240391
 *                                    └── appId ──┘        └──── pageId ────┘
 */
object QrParser {

    private val LONG_NUM = Regex("^\\d{9,25}\$")

    private val TOKEN_KEYS = listOf(
        "token", "sign", "signToken", "qrToken", "key", "code", "ticket", "t", "s", "sid",
        "sessionKey", "scanCode",
    )
    private val APP_KEYS = listOf("appId", "applicationId", "app_id")
    private val FORM_KEYS = listOf("formId", "form_id", "activityId", "taskId", "pageId", "id")

    private fun pick(map: Map<String, String>, keys: List<String>): String {
        for (k in keys) {
            for ((kk, vv) in map) {
                if (kk.equals(k, ignoreCase = true) && vv.isNotBlank()) return vv
            }
        }
        return ""
    }

    fun parse(raw: String): ScanTarget {
        val text = raw.trim()
        if (text.isEmpty()) return ScanTarget(raw = "", warnings = listOf("空内容"))

        if (text.startsWith("{") || text.startsWith("[")) {
            runCatching { parseJson(text) }.getOrNull()?.let { return it }
        }

        val urlMatch = Regex("""https?://[^\s"'<>\\]+""").find(text)
        if (urlMatch != null) return parseUrl(urlMatch.value)

        return ScanTarget(
            raw = text,
            kind = "token",
            token = text,
            warnings = if (Regex("""^[A-Za-z0-9_\-.:=+/]{6,}$""").matches(text)) emptyList()
            else listOf("既不是 URL 也不是标准 token，按纯文本处理"),
        )
    }

    private fun parseJson(text: String): ScanTarget {
        val obj = org.json.JSONObject(text)
        val params = LinkedHashMap<String, String>()
        obj.keys().forEach { k ->
            val v = obj.opt(k)
            if (v != null && v !is org.json.JSONObject && v !is org.json.JSONArray) params[k] = v.toString()
        }
        return ScanTarget(
            raw = text,
            kind = "json",
            appId = pick(params, APP_KEYS),
            pageId = pick(params, FORM_KEYS),
            token = pick(params, TOKEN_KEYS),
            query = params,
        )
    }

    private fun parseUrl(url: String): ScanTarget {
        val warnings = mutableListOf<String>()
        val uri = runCatching { URI(url) }.getOrNull()
            ?: return ScanTarget(raw = url, kind = "url", warnings = listOf("URL 解析失败"))

        val params = LinkedHashMap<String, String>()
        uri.rawQuery?.split("&")?.forEach { pair ->
            val i = pair.indexOf('=')
            if (i > 0) {
                val k = URLDecoder.decode(pair.substring(0, i), "UTF-8")
                val v = URLDecoder.decode(pair.substring(i + 1), "UTF-8")
                params[k] = v
            }
        }
        uri.rawFragment?.let { frag ->
            val q = frag.substringAfter('?', "")
            if (q.isNotBlank()) {
                q.split("&").forEach { pair ->
                    val i = pair.indexOf('=')
                    if (i > 0) params[URLDecoder.decode(pair.substring(0, i), "UTF-8")] =
                        URLDecoder.decode(pair.substring(i + 1), "UTF-8")
                }
            }
        }

        val segs = uri.path?.split("/")?.filter { it.isNotBlank() } ?: emptyList()
        var appId = ""
        var pageId = ""
        var verb = ""

        val i = segs.indexOf("runtime")
        if (i >= 0) {
            val rest = segs.subList(i + 1, segs.size)
            val nums = rest.filter { LONG_NUM.matches(it) }
            if (nums.size >= 2) {
                appId = nums.first()
                pageId = nums.last()
                val a = rest.indexOf(nums.first())
                val b = rest.lastIndexOf(nums.last())
                verb = if (b > a + 1) rest.subList(a + 1, b).joinToString("/") else ""
            } else if (nums.size == 1) {
                if (rest.indexOf(nums[0]) == 0) appId = nums[0] else pageId = nums[0]
            }
        } else {
            val nums = segs.filter { LONG_NUM.matches(it) }
            if (nums.size == 1) pageId = nums[0]
            if (nums.size >= 2) {
                appId = nums.first()
                pageId = nums.last()
            }
        }

        if (appId.isBlank()) appId = pick(params, APP_KEYS)
        if (pageId.isBlank()) pageId = pick(params, FORM_KEYS)
        if (appId.isBlank() && pageId.isBlank()) {
            warnings += "没能从二维码里取到 appId / pageId"
        }
        if (!params.keys.any { it.equals("token", true) }) {
            warnings += "二维码里没有 token 参数，可能是静态入口"
        }

        return ScanTarget(
            raw = url,
            kind = "url",
            appId = appId,
            pageId = pageId,
            verb = verb,
            token = pick(params, TOKEN_KEYS),
            query = params,
            warnings = warnings,
        )
    }
}
