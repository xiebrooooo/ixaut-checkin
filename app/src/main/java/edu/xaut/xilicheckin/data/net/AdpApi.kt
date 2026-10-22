package edu.xaut.xilicheckin.data.net

import org.json.JSONArray
import org.json.JSONObject

/**
 * kqtj（考勤统计）ADP 表单接口 —— **真实契约**（2026-10 用真实账号抓包确认）。
 *
 * 登录后打开真实签到页一共 13 个 XHR，其中关键的：
 *   GET  /adp/api/currentUser                                    → {code:"0",response:{uid,name,roles}}
 *   GET  /adp/api/runtime/{appId}/page/{pageId}/form             → 表单 schema（含每个字段 defaultValueProps.value）
 *   GET  /adp/api/runtime/{appId}/page/{pageId}/valid            → {code:"0",response:{defaultValue:"校验通过"}}
 *   POST /adp/api/runtime/{appId}/page/{pageId}/component/{cid}/correlationData
 *                                                                → 选中「考勤上报记录」后回填整条考勤
 *   POST /adp/api/runtime/{appId}/page/{pageId}/form             → ★ 提交 = 打卡（扁平 body）
 *
 * 三个坑（都踩过）：
 *   1) 提交是 /runtime/…/form，不是 /app/…/form。
 *   2) body 必须是扁平字典，不能包 {formData:…} / {data:…}。
 *   3) 下拉值必须是 {"label":…,"value":…} 对象。
 */
object AdpApi {

    const val BASE = "https://kqtj.xaut.edu.cn"

    private fun headers(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["accept"] = "application/json, text/plain, */*"
        h["accept-language"] = "zh-CN,zh;q=0.9"
        h["x-requested-with"] = "XMLHttpRequest"
        h["referer"] = "$BASE/adp/"
        h["user-agent"] = CasAuth.UA
        h.putAll(extra)
        return h
    }

    private fun url(path: String) = BASE + path

    fun currentUser(s: HttpSession): Resp = s.get(url("/adp/api/currentUser"), headers())

    /** 表单 schema：字段定义 + 每个字段的服务端默认值（姓名/学号/院系/专业/班级 都按登录账号给） */
    fun formSchema(s: HttpSession, appId: String, pageId: String): Resp =
        s.get(url("/adp/api/runtime/$appId/page/$pageId/form"), headers())

    fun validRules(s: HttpSession, appId: String, pageId: String): Resp =
        s.get(url("/adp/api/runtime/$appId/page/$pageId/valid"), headers())

    fun details(s: HttpSession, appId: String, pageId: String, id: String): Resp =
        s.get(url("/adp/api/runtime/$appId/page/$pageId/form/$id/details"), headers())

    /** @deprecated 真实页面返回 {"code":"-1","msg":"非法的访问"}，只为体检保留 */
    fun addTemplate(s: HttpSession, appId: String, pageId: String): Resp =
        s.get(url("/adp/api/runtime/$appId/page/$pageId/add"), headers())

    /** 查重（平台可能不开放，失败就当没查到） */
    fun listRecords(s: HttpSession, pageId: String, pageNo: Int = 1, pageSize: Int = 20): Resp {
        val shapes = listOf(
            """{"pageNo":$pageNo,"pageSize":$pageSize,"searchData":{},"params":{}}""",
            """{"pageNo":$pageNo,"pageSize":$pageSize,"data":{}}""",
            """{"pageNo":$pageNo,"pageSize":$pageSize}""",
        )
        var last = Resp(0, "", "")
        for (body in shapes) {
            last = s.postJson(url("/adp/api/runtime/data/$pageId/list/mobile"), body, headers())
            if (last.status == 200 && last.body.trimStart().startsWith("{")) return last
        }
        return last
    }

    /**
     * 选中「考勤上报记录」后让服务端回填整条考勤信息。
     * 返回数组，通常一个元素：{label:"英语B3-…", value:"878…", datepickerField_a96wke0u:"2026-10-20", …}
     */
    fun correlate(
        s: HttpSession,
        appId: String,
        pageId: String,
        componentId: String,
        form: JSONObject,
    ): Resp {
        val body = JSONObject().apply {
            put("appId", appId)
            put("pageId", pageId)
            put("componentId", componentId)
            put("table", JSONArray())
            put("form", form)
        }
        return s.postJson(
            url("/adp/api/runtime/$appId/page/$pageId/component/$componentId/correlationData"),
            body.toString(),
            headers(),
        )
    }

    /** 提交一条表单记录 —— 这就是「打卡」那一下。扁平 body，字段 id → 值 */
    fun submit(s: HttpSession, appId: String, pageId: String, model: JSONObject): Resp =
        s.postJson(url("/adp/api/runtime/$appId/page/$pageId/form"), model.toString(), headers())
}
