package edu.xaut.xilicheckin.domain

import edu.xaut.xilicheckin.data.net.AdpApi
import edu.xaut.xilicheckin.data.net.CasAuth
import edu.xaut.xilicheckin.data.net.HttpSession
import edu.xaut.xilicheckin.data.net.Resp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class SignEvent {
    data class Log(val text: String) : SignEvent()
    data class Result(val result: SignResult) : SignEvent()
    data class Progress(val done: Int, val total: Int) : SignEvent()
}

/** 表单里的一个字段（来自服务端 schema） */
data class FieldInfo(
    val field: String,
    val label: String,
    val component: String,
    val value: String,
    val required: Boolean = false,
    val hidden: Boolean = false,
    // show=false（不渲染但仍会提交）与 ifShow=false（连提交都不该有）是两回事。
    // 浏览器实测：schema 里 5 个 label 带「（不生效）」的字段 ifShow=false，真实 POST
    // 里**没有**它们；而 inputField_oljsq26s 等 show=false / ifShow=true 的字段照常提交。
    // 所以 buildModel 只按 ifShow 过滤，不能按 hidden 过滤。
    val ifShow: Boolean = true,
) {
    val isSelect: Boolean get() = component.contains("Select") || component.contains("Radio") || component.contains("Checkbox")
}

/** 按 label 认出来的关键字段，避免写死雪花 id */
data class KeyFields(
    val recordIdField: String = "",
    val recordSelectField: String = "",
    val typeField: String = "",
    val roundField: String = "",
    val dateField: String = "",
    val deadlineField: String = "",
    // 缓存 key（inputField_oljsq26s）：schema 里 show=false 但 ifShow=true，
    // 值是二维码 URL 的 keyCur。浏览器确实会提交它，我们照做。
    val cacheKeyField: String = "",
)

data class FormSnapshot(
    val fields: List<FieldInfo>,
    val model: JSONObject,
    val keys: KeyFields,
)

/**
 * 签到引擎：一次扫码 → N 个账号各打卡一次。
 *
 * 真实链路（2026-10 实测，不是猜的）：
 *   1. CAS 登录（账号密码 → ids.xaut.edu.cn → kqtj 的 SESSION）
 *   2. GET  /runtime/{appId}/page/{pageId}/form                        → 表单 schema，服务端已按账号预填姓名/学号/院系/专业/班级
 *   3. POST /runtime/{appId}/page/{pageId}/component/{recordSelect}/correlationData
 *          body {"form":{"<考勤上报id字段>":"<二维码里的 id>"}}        → 回填课程/日期/老师/学期/类型/轮次/截止时间
 *   4. POST /runtime/{appId}/page/{pageId}/form  （扁平 body）          → 打卡
 *
 * 二维码形状：
 *   https://kqtj.xaut.edu.cn/adp/runtime/685488207969587200/form/690161299459346432?id=878186883417313280&keyCur=…
 *   id = 考勤上报记录 id（必填）
 */
object SignEngine {

    private val recordIdLabel = Regex("考勤上报\\s*id", RegexOption.IGNORE_CASE)
    private val recordSelectLabel = Regex("考勤上报记录")
    private val roundLabel = Regex("考勤轮次")
    private val deadlineLabel = Regex("截止时间点")

    /** 把 /form 返回的 schema 摊平成字段清单 + 默认值 */
    fun parseFormModel(body: String): FormSnapshot? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val resp = root.optJSONObject("response") ?: root
        val form = resp.optJSONObject("form") ?: resp
        val schemas = form.optJSONArray("schemas") ?: JSONArray()
        val fields = mutableListOf<FieldInfo>()

        fun walk(arr: JSONArray) {
            for (i in 0 until arr.length()) {
                val n = arr.optJSONObject(i) ?: continue
                val f = n.optString("field", "")
                if (f.isNotEmpty() && n.has("defaultValueProps")) {
                    val dv = n.optJSONObject("defaultValueProps")
                    val raw = dv?.opt("value")
                    val v = when (raw) {
                        null, JSONObject.NULL -> ""
                        is JSONObject, is JSONArray -> ""
                        else -> raw.toString()
                    }
                    fields += FieldInfo(
                        field = f,
                        label = n.optString("label", ""),
                        component = n.optString("component", ""),
                        value = v,
                        required = n.optBoolean("required", false),
                        hidden = !n.optBoolean("show", true) || !n.optBoolean("ifShow", true),
                        ifShow = n.optBoolean("ifShow", true),
                    )
                }
                n.optJSONArray("children")?.let { walk(it) }
            }
        }
        walk(schemas)

        val model = JSONObject()
        fields.forEach { if (it.value.isNotBlank()) model.put(it.field, it.value) }
        return FormSnapshot(fields, model, pickKeys(fields))
    }

    fun pickKeys(fields: List<FieldInfo>): KeyFields {
        fun byLabel(re: Regex) = fields.firstOrNull { re.containsMatchIn(it.label) }?.field ?: ""
        fun exact(label: String) = fields.firstOrNull { it.label.trim() == label }?.field ?: ""
        return KeyFields(
            recordIdField = byLabel(recordIdLabel),
            recordSelectField = byLabel(recordSelectLabel),
            typeField = exact("考勤类型"),
            roundField = byLabel(roundLabel),
            dateField = exact("考勤日期"),
            deadlineField = byLabel(deadlineLabel),
            // 真实 schema 的 label 是「不可见-缓存key」（带"不可见-"前缀），
            // 所以不能 exact("缓存key")，否则抓不到 → keyCur 永远不提交。
            cacheKeyField = fields.firstOrNull {
                it.label.replace(Regex("^不可见-"), "").trim() == "缓存key"
            }?.field ?: "",
        )
    }

    /** 二维码里的考勤上报记录 id */
    fun recordIdOf(target: ScanTarget): String {
        val q = target.query
        return q["id"] ?: q["recordId"] ?: q["signId"] ?: q["kaoqinId"] ?: ""
    }

    /** 统一入口：登录时若服务端要图形验证码，就把图弹给用户填 */
    private suspend fun loginWithCaptcha(
        session: HttpSession,
        acc: Account,
        onStep: (String) -> Unit = {},
    ): Map<String, String> = CasAuth.login(session, acc.account, acc.password, onStep) { img ->
        CaptchaBridge.ask(img, "账号 ${acc.account} 需要图形验证码")
    }

    /**
     * 密码登录成功后，把顺手拿到的 CASTGC（「7天免登录」票据）交出去落库。
     * SignEngine 是 object，没有构造注入，所以用这个钩子在 AppModule 里接到 repository。
     */
    @Volatile
    var onCastgc: (suspend (account: String, castgc: String) -> Unit)? = null

    /** 服务端给 14 天，按 13 天算，留一天余量 */
    private const val CASTGC_MAX_AGE_MS = 13L * 24 * 3600 * 1000

    /**
     * 登录优先用「7天免登录」票据，票据没了再回落账号密码。
     *
     * 收益不只是快：密码从「每次签到都用一次」变成「两周一次」，
     * 也就把「连续输错触发图形验证码」的概率压到近乎没有。
     */
    private suspend fun ensureLogin(
        session: HttpSession,
        acc: Account,
        onStep: (String) -> Unit = {},
    ): Map<String, String> {
        if (acc.hasCastgc) {
            val age = if (acc.castgcAt <= 0) 0 else System.currentTimeMillis() - acc.castgcAt
            if (age < CASTGC_MAX_AGE_MS) {
                try {
                    onStep("免登录（用 7 天票据换会话）…")
                    return CasAuth.loginByCastgc(session, acc.castgc, onStep)
                } catch (e: Exception) {
                    // 票据过期 / 在网页主动退出 / 被改密码 —— 都回落密码登录。
                    // 清掉旧 cookie，免得残留的 CASTGC 干扰这次真正登录。
                    session.jar.clear()
                    onStep("免登录已失效，改用密码登录…")
                }
            } else {
                onStep("免登录票据已超 13 天，改用密码登录…")
            }
        }

        val user = loginWithCaptcha(session, acc, onStep)
        val ticket = CasAuth.castgcOf(session)
        if (ticket.isNotEmpty()) {
            runCatching { onCastgc?.invoke(acc.account, ticket) }
        }
        return user
    }

    /** 登录并读取表单（界面「读取表单字段」用） */
    suspend fun loadForm(
        account: Account,
        target: ScanTarget,
        onStep: (String) -> Unit = {},
    ): FormSnapshot = withContext(Dispatchers.IO) {
        val s = HttpSession()
        ensureLogin(s, account, onStep)
        val r = AdpApi.formSchema(s, target.appId, target.pageId)
        parseFormModel(r.body) ?: throw IllegalStateException("表单解析失败 HTTP ${r.status}")
    }

    /**
     * 合并提交体：
     *   服务端默认值（按账号） → 剔除 ifShow=false → 考勤回填值（覆盖）
     *   → 必填三项 → 用户显式覆盖
     *
     * @param keyCur 二维码 URL 里的 keyCur，回填到缓存 key 字段（inputField_oljsq26s）。
     *               ！这条**必须**在剔除之后执行 —— 该字段在 schema 里是 show=false
     *               （但 ifShow=true），浏览器不渲染却照常提交，正是本流程要复刻的行为。
     */
    fun buildModel(
        template: JSONObject,
        row: JSONObject?,
        keys: KeyFields,
        recordId: String,
        overrides: Map<String, String>,
        fields: List<FieldInfo>,
        keyCur: String = "",
    ): Pair<JSONObject, List<String>> {
        val filled = mutableListOf<String>()

        // ① 浏览器实测：ifShow=false 的字段（label 带「（不生效）」）根本不出现在
        //    真实 POST body 里。剔除它们，只剔 ifShow，不看 hidden ——
        //    show=false / ifShow=true 的字段（缓存 key、上报记录等）是要提交的。
        val dead = fields.filterNot { it.ifShow }.map { it.field }.toSet()
        val model = JSONObject()
        template.keys().forEach { k ->
            if (k !in dead) model.put(k, template.opt(k))
        }

        // ② 缓存 key 来自二维码 URL 的 keyCur，不是服务端默认值
        val cacheKeyField = keys.cacheKeyField.ifEmpty { "inputField_oljsq26s" }
        if (keyCur.isNotBlank() && cacheKeyField !in dead) {
            model.put(cacheKeyField, keyCur)
            filled += "$cacheKeyField←keyCur"
        }

        if (row != null) {
            row.keys().forEach { k ->
                if (k == "label" || k == "value") return@forEach
                if (k in dead) return@forEach
                val v = row.opt(k)
                if (v != null && v != JSONObject.NULL && v.toString().isNotEmpty()) {
                    model.put(k, v)
                    filled += "$k←回填"
                }
            }
        }

        if (keys.recordSelectField.isNotEmpty()) {
            model.put(
                keys.recordSelectField,
                JSONObject().put("label", row?.optString("label", "") ?: "").put(
                    "value",
                    row?.optString("value", "")?.takeIf { it.isNotEmpty() } ?: recordId,
                ),
            )
            filled += "${keys.recordSelectField}←考勤记录"
        }
        if (keys.recordIdField.isNotEmpty() && recordId.isNotEmpty()) {
            model.put(keys.recordIdField, recordId)
        }
        if (keys.typeField.isNotEmpty() && !model.has(keys.typeField)) {
            model.put(keys.typeField, JSONObject().put("label", "正常签到").put("value", "ZCQD"))
            filled += "${keys.typeField}←默认正常签到"
        }

        val selectIds = fields.filter { it.isSelect }.map { it.field }.toSet()
        overrides.forEach { (k, v) ->
            if (v.isBlank()) return@forEach
            val resolved = when (v) {
                "__user.account" -> v
                "__user.name" -> v
                "__user.id" -> v
                "__now" -> SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
                else -> v
            }
            if (k in selectIds && !resolved.startsWith("__user")) {
                val parts = resolved.split("|")
                model.put(
                    k,
                    if (parts.size >= 2) JSONObject().put("label", parts[0]).put("value", parts[1])
                    else JSONObject().put("label", resolved).put("value", resolved),
                )
            } else if (!resolved.startsWith("__user")) {
                model.put(k, resolved)
            }
            filled += "$k←配置"
        }
        return model to filled
    }

    /** 单个账号签到 */
    suspend fun signOnce(
        account: Account,
        target: ScanTarget,
        overrides: Map<String, String>,
        dry: Boolean,
        dedupe: Boolean = false,
        onStep: (String) -> Unit = {},
    ): SignResult = withContext(Dispatchers.IO) {
        try {
            val recordId = recordIdOf(target)
            if (!target.ok) return@withContext SignResult(account.account, SignStatus.FAIL, "二维码里没解析出 appId / pageId")
            if (recordId.isEmpty()) {
                return@withContext SignResult(
                    account.account, SignStatus.FAIL, "二维码里没有考勤记录 id（应为 …?id=xxxx），这不像签到码",
                )
            }

            val s = HttpSession()
            onStep("登录…")
            ensureLogin(s, account, onStep)

            if (dedupe) {
                val listed = AdpApi.listRecords(s, target.pageId)
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
                if (listed.status == 200 &&
                    (listed.body.contains(today) || listed.body.contains(today.replace("-", "/")))
                ) {
                    return@withContext SignResult(account.account, SignStatus.SKIP, "今天已有记录，跳过")
                }
            }

            onStep("读取表单…")
            val form = AdpApi.formSchema(s, target.appId, target.pageId)
            val snap = parseFormModel(form.body)
                ?: return@withContext SignResult(account.account, SignStatus.FAIL, "表单读取失败 HTTP ${form.status}")
            if (snap.fields.isEmpty()) {
                return@withContext SignResult(account.account, SignStatus.FAIL, "表单里没有字段：${form.body.take(120)}")
            }

            onStep("读取考勤信息…")
            val corrForm = JSONObject()
            if (snap.keys.recordIdField.isNotEmpty()) corrForm.put(snap.keys.recordIdField, recordId)
            val corr = AdpApi.correlate(s, target.appId, target.pageId, snap.keys.recordSelectField, corrForm)
            val row = runCatching {
                JSONObject(corr.body).optJSONArray("response")?.optJSONObject(0)
            }.getOrNull()
            if (row == null) {
                return@withContext SignResult(
                    account.account, SignStatus.FAIL, "查不到这条考勤：${corr.body.take(120)}",
                )
            }

            val (model, filled) =
                buildModel(
                    snap.model,
                    row,
                    snap.keys,
                    recordId,
                    overrides,
                    snap.fields,
                    // 缓存 key 只来自二维码 URL：…/form/690161299459346432?id=…&keyCur=1792454469500
                    keyCur = target.query["keyCur"] ?: "",
                )
            val summary = listOfNotNull(
                row.optString("label", "").takeIf { it.isNotEmpty() },
                if (snap.keys.deadlineField.isNotEmpty()) model.optString(snap.keys.deadlineField, "") else "",
            ).filter { it.isNotEmpty() }.joinToString(" / ")

            if (dry) {
                return@withContext SignResult(
                    account.account, SignStatus.OK,
                    "演练（未提交）${if (summary.isEmpty()) "" else "：$summary"}",
                )
            }

            onStep("提交打卡…")
            val r = AdpApi.submit(s, target.appId, target.pageId, model)
            val j = runCatching { JSONObject(r.body) }.getOrNull()
            val code = j?.optString("code", "") ?: ""
            if (r.status == 200 && (code == "0" || j?.optBoolean("success") == true)) {
                SignResult(account.account, SignStatus.OK, "签到成功${if (summary.isEmpty()) "" else "：$summary"}")
            } else {
                SignResult(
                    account.account, SignStatus.FAIL,
                    "打卡失败：${j?.optString("msg", "")?.takeIf { it.isNotEmpty() } ?: r.body.take(120)}",
                )
            }
        } catch (e: CasAuth.CasException) {
            SignResult(account.account, SignStatus.FAIL, "${e.code}: ${e.message}")
        } catch (e: Exception) {
            SignResult(account.account, SignStatus.FAIL, "网络异常: ${e.message}")
        }
    }

    /** 一次扫码 → 多账号并发（默认 3 路，互不串号） */
    suspend fun signAll(
        accounts: List<Account>,
        target: ScanTarget,
        overrides: Map<String, String>,
        dry: Boolean,
        dedupe: Boolean = false,
        concurrency: Int = 3,
        onEvent: (SignEvent) -> Unit = {},
    ): List<SignResult> = coroutineScope {
        val sem = Semaphore(concurrency.coerceIn(1, 5))
        var done = 0
        onEvent(SignEvent.Progress(0, accounts.size))

        val results = accounts.map { acc ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    val r = signOnce(acc, target, overrides, dry, dedupe) { step ->
                        onEvent(SignEvent.Log("${acc.account} · $step"))
                    }
                    synchronized(this@coroutineScope) {
                        done += 1
                        onEvent(SignEvent.Progress(done, accounts.size))
                    }
                    onEvent(SignEvent.Result(r))
                    r
                }
            }
        }
        results.awaitAll()
    }

    /**
     * 只验账号密码，不碰签到：每个账号独立会话走一遍 CAS 登录，
     * 再用 /adp/api/currentUser 确认拿到的身份是谁。
     */
    suspend fun testAccounts(
        accounts: List<Account>,
        concurrency: Int = 3,
        onResult: (AccountTest) -> Unit = {},
    ): List<AccountTest> = coroutineScope {
        val sem = Semaphore(concurrency.coerceIn(1, 5))
        accounts.map { acc ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    val t0 = System.currentTimeMillis()
                    val r = if (acc.account.isBlank() || acc.password.isBlank()) {
                        AccountTest(acc.account, false, "", "没有填密码（或账号为空）", 0)
                    } else {
                        try {
                            val s = HttpSession()
                            val user = ensureLogin(s, acc) {}
                            val name = user["name"].orEmpty()
                            AccountTest(
                                account = acc.account,
                                ok = true,
                                name = name,
                                detail = if (name.isNotEmpty()) "登录成功，身份：$name" else "登录成功",
                                ms = System.currentTimeMillis() - t0,
                            )
                        } catch (e: CasAuth.CasException) {
                            AccountTest(
                                account = acc.account,
                                ok = false,
                                name = "",
                                detail = e.message ?: "登录失败",
                                ms = System.currentTimeMillis() - t0,
                            )
                        } catch (e: Exception) {
                            AccountTest(
                                account = acc.account,
                                ok = false,
                                name = "",
                                detail = "登录失败：${e.message ?: e.javaClass.simpleName}",
                                ms = System.currentTimeMillis() - t0,
                            )
                        }
                    }
                    onResult(r)
                    r
                }
            }
        }.awaitAll()
    }

    /** 接口体检 */
    suspend fun probe(
        account: Account,
        target: ScanTarget,
        onStep: (String) -> Unit = {},
    ): List<ProbeRow> = withContext(Dispatchers.IO) {
        val rows = mutableListOf<ProbeRow>()
        val s = HttpSession()
        try {
            ensureLogin(s, account, onStep)
            rows += ProbeRow("登录", "ok 登录成功", "")
        } catch (e: Exception) {
            rows += ProbeRow("登录", "fail " + (e.message ?: "失败"), "")
            return@withContext rows
        }

        fun add(name: String, r: Resp) {
            val verdict = if (r.status == 200 && !r.body.contains("pwdEncryptSalt")) "ok HTTP ${r.status}"
            else "fail HTTP ${r.status} ${r.body.take(60)}"
            rows += ProbeRow(name, verdict, r.body.take(300))
        }

        add("currentUser", AdpApi.currentUser(s))
        val form = AdpApi.formSchema(s, target.appId, target.pageId)
        add("form", form)
        val snap = parseFormModel(form.body)
        if (snap != null) {
            rows += ProbeRow(
                "表单字段",
                "ok ${snap.fields.size} 个字段",
                snap.fields.joinToString("\n") { "${it.field}\t${it.label}\t${it.component}${if (it.required) "\t必填" else ""}" },
            )
            val recordId = recordIdOf(target)
            if (recordId.isNotEmpty() && snap.keys.recordSelectField.isNotEmpty()) {
                val f = JSONObject().put(snap.keys.recordIdField, recordId)
                add("考勤回填", AdpApi.correlate(s, target.appId, target.pageId, snap.keys.recordSelectField, f))
            }
        }
        add("valid", AdpApi.validRules(s, target.appId, target.pageId))
        rows
    }
}
