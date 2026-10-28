package edu.xaut.xilicheckin.domain

/** 一个账号（密码从本地加密库解出后才会到这里，内存中出现，不落盘明文） */
data class Account(
    val account: String,
    val password: String,
    val name: String = "",
    val enabled: Boolean = true,
    /**
     * CAS 的长期票据（「7天免登录」）。非空时签到不再提交密码，
     * 只拿它换 SESSION；过期了会自动回落密码登录并刷新这一项。
     */
    val castgc: String = "",
    /** 拿到 castgc 的时间，用来判断是不是快过期（服务端给 14 天，我们按 13 天算保险） */
    val castgcAt: Long = 0,
) {
    val hasCastgc: Boolean get() = castgc.isNotBlank()
}

/** 扫码解析出来的签到目标 */
data class ScanTarget(
    val raw: String,
    val kind: String = "text",
    val appId: String = "",
    val pageId: String = "",
    val verb: String = "",
    val token: String = "",
    val query: Map<String, String> = emptyMap(),
    val warnings: List<String> = emptyList(),
) {
    val ok: Boolean get() = appId.isNotBlank() && pageId.isNotBlank()

    fun summary(): String = buildString {
        append(if (kind == "url") "入口 URL" else if (kind == "json") "JSON 载荷" else "文本")
        if (appId.isNotBlank()) append(" · appId=").append(appId)
        if (pageId.isNotBlank()) append(" · pageId=").append(pageId)
        if (verb.isNotBlank()) append(" · 动作=").append(verb)
        if (token.isNotBlank()) append(" · token=").append(token.take(10)).append('…')
    }
}

/** 表单里的一个字段（服务端模板 + 用户改动） */
data class FieldSpec(
    val key: String,
    val label: String,
    val value: String,
    /** 服务端给的原始值：只有和它不一样才算「用户改过」，避免把 A 的姓名套到 B 头上 */
    val original: String = "",
    val fromServer: Boolean = true,
)

enum class SignStatus { OK, SKIP, FAIL }

data class SignResult(
    val account: String,
    val status: SignStatus,
    val detail: String,
    val at: Long = System.currentTimeMillis(),
    /**
     * true = 界面预览用的假结果。
     * 只为看清「成功态」长什么样，**从未向服务端提交任何请求**，
     * 也不会写入记录表。真实签到结果永远以 status + 服务端响应为准。
     */
    val simulated: Boolean = false,
)

/** 接口体检的一行结果 */
data class ProbeRow(
    val name: String,
    val verdict: String,
    val body: String,
)

/** 账号密码测试结果（只登录，不签到） */
data class AccountTest(
    val account: String,
    val ok: Boolean,
    val name: String = "",
    val detail: String = "",
    val ms: Long = 0,
)
