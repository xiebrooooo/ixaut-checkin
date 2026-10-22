package edu.xaut.xilicheckin.data.net

import java.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 西安理工大学统一身份认证（金智 authserver）—— **真实实现**（2026-10 用真实账号验证）。
 *
 * 链路：GET kqtj/adp/api/login → 302 → ids/authserver/login → POST 账号密码
 *      → 302 回 kqtj/adp/authserver/caslogin?ticket= → 下发 SESSION → GET /adp/api/currentUser
 *
 * 密码加密（权威算法，来自 ids/authserver/cusTheme/static/common/encrypt.js）：
 *   encryptPassword(pw, salt) = AES-CBC(key=salt, iv=salt, 明文 = 64位随机前缀 + 真密码)
 *   CryptoJS 只输出 base64 密文，**不带 IV**（108 字符 / 80 字节）。
 *   由于随机前缀正好 4 个 block，服务端取后 16 字节再解，IV 取什么都能对上。
 *   （真实 JS 的 iv 是 randomString(16)，但 CBC 下 iv 只影响首块，故此处用 salt 等价可用。
 *    详见 encryptPassword 下方的 CRITICAL 注释 —— 不要当成 bug 去改。）
 *
 * 三个必须记住的坑（都踩过）：
 *   1) 登录页 DOM 里永远有一个隐藏的 name="captcha"，不能拿它判断要不要验证码；
 *      真正的开关是脚本变量 `var needCaptcha = "";`（空串 = 不用验证码）。
 *   2) 页面上并排 5 个 form（qrLoginForm×2 / loginFromId / phoneFromId / pwdFromId），
 *      必须只解析 id="pwdFromId" 那一块，否则会拿到 cllt=dynamicLogin。
 *   3) 登录成功后落的 /adp/api/login 本身返回 **404 + 「温馨提示」HTML**，
 *      所以不能拿 HTTP 200 当成功判据 —— 要用 SESSION cookie。
 *
 * 关于「7 天免登录」（2026-10 实测对拍）：
 *   登录页密码框下面有个 <input type="checkbox" name="rememberMe" value="true">
 *   文案「7天免登录」，悬停提示「开启后7天内无需再次登录」。
 *   parseForm 会把它一起读进表单（value 恒为 "true"），所以我们本来就在提交它：
 *     · 不提交 rememberMe → CASTGC 是**会话级**（无 Max-Age/Expires，关掉就没了）
 *     · 提交   rememberMe → CASTGC 带 Max-Age=1209600（14 天）+ Expires
 *   拿到这张长期票后，**不用再提交账号密码**，只带 CASTGC 走
 *     kqtj/adp/api/login → /adp/authserver/login → /adp/authserver/caslogin
 *     → ids/authserver/login?service=… → 302 回 kqtj 带 ticket → SESSION
 *   就能拿到业务 SESSION（loginByCastgc 做的就是这件事）。
 *   副作用很实在：密码从「每次签到用一次」变成「两周一次」，
 *   也就把「连续错密码触发图形验证码」的概率压到近乎没有。
 */
object CasAuth {

    const val GATEWAY = "https://kqtj.xaut.edu.cn/adp/api/login"
    const val IDS = "https://ids.xaut.edu.cn"
    const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/122.0.0.0 Mobile Safari/537.36"

    private val ERR_HINTS = listOf(
        "您提供的用户名或者密码有误", "密码错误", "用户名或密码", "账号或密码",
        "验证码错误", "用户不存在", "账号已锁定", "登录失败",
    )

    class CasException(message: String, val code: String = "ERR") : Exception(message)

    /** 金智自己的随机前缀字母表（照抄 encrypt.js） */
    private const val RANDOM_ALPHABET = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678"

    // ---------------------------------------------------------------- 加密

    private fun aesCbc(key: ByteArray, iv: ByteArray, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(plain)
    }

    /** 64 位随机前缀（金智 encrypt.js 里的 randomString(64)） */
    private fun randomPrefix(len: Int = 64): String {
        val rnd = SecureRandom()
        val sb = StringBuilder(len)
        repeat(len) { sb.append(RANDOM_ALPHABET[rnd.nextInt(RANDOM_ALPHABET.length)]) }
        return sb.toString()
    }

    /**
     * 生成要 POST 的 password 值。
     * 输出 base64 密文，长度 108 字符（80 字节 = 64 位前缀 + 10 位密码 → 5 个 block）。
     */
    fun encryptPassword(password: String, salt: String): String {
        val key = salt.toByteArray(Charsets.UTF_8)
        if (key.size != 16 && key.size != 24 && key.size != 32) {
            throw CasException("pwdEncryptSalt 长度异常 ${key.size}，金智应为 16", "BAD_SALT")
        }
        val plain = (randomPrefix() + password).toByteArray(Charsets.UTF_8)
        return Base64.getEncoder().encodeToString(aesCbc(key, key, plain))
    }

    // ==================== CRITICAL：iv 用 salt 不是 bug，别改 ====================
    // 真实页面 JS 是 getAesString(randomString(64)+pwd, salt, randomString(16))，
    // 即 iv 本应是随机 16 字符。我们这里用 iv=salt 同样能登录成功，原因：
    //   AES-CBC 解密时 IV 只参与第一个 block 的异或；之后每个 block 的明文只取决于
    //   前一块的密文，与 IV 无关。明文 = 64 位随机前缀（正好 4 个 block）+ 密码，
    //   密码落在第 5 个 block，所以 IV 取 salt / 全 0 / 0xFF 解出的尾部都是正确密码。
    // 实测（2026-10）：用真实浏览器抓到的密文分别以 iv=salt、iv=全0 解密，
    //   尾部均得到同一条密码，完全一致。
    // 结论：不要因为"iv 应该随机"就去改 encryptPassword，见 CasAuthEncryptTest。
    // =========================================================================

    /**
     * 等价于 encryptPassword，但允许注入固定前缀（仅供单元测试断言，生产不走这里）。
     * 输出 base64( AES-128-CBC(key=salt, iv=salt, plain=prefix+password) )。
     */
    fun encryptForTest(prefix: String, password: String, salt: String, iv: String): String {
        val key = salt.toByteArray(Charsets.UTF_8)
        require(key.size == 16) { "salt 必须 16 字节，实际 ${key.size}" }
        val ivBytes = iv.toByteArray(Charsets.UTF_8)
        require(ivBytes.size == 16) { "iv 必须 16 字节，实际 ${ivBytes.size}" }
        require(prefix.length == 64) { "前缀必须 64 字符，实际 ${prefix.length}" }
        val plain = (prefix + password).toByteArray(Charsets.UTF_8)
        return Base64.getEncoder().encodeToString(aesCbc(key, ivBytes, plain))
    }

    /** 仅供自检：按给定 iv 解密，返回切掉 64 位前缀后的密码。 */
    fun decryptForSelfTest(cipherB64: String, salt: String, iv: String): String {
        val key = salt.toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(iv.toByteArray(Charsets.UTF_8)),
        )
        val raw = cipher.doFinal(Base64.getDecoder().decode(cipherB64)).toString(Charsets.UTF_8)
        require(raw.length >= 64) { "明文短于 64 位前缀：$raw" }
        return raw.substring(64)
    }

    /** 金智字母表 randomString，供单测复现 */
    fun randomStringForTest(len: Int) = randomPrefix(len)

    // ---------------------------------------------------------------- 页面解析

    private val RE_NAME_VALUE =
        Regex("""<input[^>]*\bname="([^"]+)"[^>]*\bvalue="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val RE_VALUE_NAME =
        Regex("""<input[^>]*\bvalue="([^"]*)"[^>]*\bname="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val RE_SALT = listOf(
        Regex("""id="pwdEncryptSalt"[^>]*value="([^"]*)"""", RegexOption.IGNORE_CASE),
        Regex("""name="pwdEncryptSalt"[^>]*value="([^"]*)"""", RegexOption.IGNORE_CASE),
        Regex("""id="pwdDefaultEncryptSalt"[^>]*value="([^"]*)"""", RegexOption.IGNORE_CASE),
    )
    private val RE_NEED_CAPTCHA = Regex("""var\s+needCaptcha\s*=\s*"([^"]*)"""")
    private val RE_CAPTCHA_SWITCH = Regex("""var\s+captchaSwitch\s*=\s*"([^"]*)"""")

    /** 只取 id="pwdFromId" 那个 form 块，避免拿到「动态码登录」的隐藏域 */
    private fun pickLoginForm(html: String): String {
        val i = html.indexOf("id=\"pwdFromId\"")
        if (i < 0) return html
        val start = html.lastIndexOf('<', i)
        val end = html.indexOf("</form>", i)
        if (start < 0 || end < 0) return html
        return html.substring(start, end)
    }

    fun parseForm(html: String): MutableMap<String, String> {
        val scope = pickLoginForm(html)
        val form = LinkedHashMap<String, String>()
        RE_NAME_VALUE.findAll(scope).forEach {
            if (!form.containsKey(it.groupValues[1])) form[it.groupValues[1]] = it.groupValues[2]
        }
        RE_VALUE_NAME.findAll(scope).forEach {
            if (!form.containsKey(it.groupValues[2])) form[it.groupValues[2]] = it.groupValues[1]
        }
        for (re in RE_SALT) {
            val m = re.find(html) ?: continue
            form["pwdEncryptSalt"] = m.groupValues[1]
            break
        }
        form.remove("passwordText")
        form.remove("password")
        form.remove("captcha")
        return form
    }

    /** 页面脚本里的 needCaptcha 才是真开关；空串 = 不需要验证码 */
    fun needsCaptcha(html: String): Boolean {
        val m = RE_NEED_CAPTCHA.find(html)
        if (m != null) return m.groupValues[1].isNotBlank()
        // 没有该变量时保守判断：出现验证码 div 且没有 hide
        return html.contains("captchaDiv") && !html.contains("captchaDiv\" class=\"hide")
    }

    fun captchaSwitch(html: String): String = RE_CAPTCHA_SWITCH.find(html)?.groupValues?.get(1) ?: ""

    fun extractError(html: String): String {
        val pats = listOf(
            Regex("""id="showErrorTip"[^>]*>([^<]{2,80})<""", RegexOption.IGNORE_CASE),
            Regex("""id="showErrorTip"[^>]*>\s*<span>([^<]{2,80})<""", RegexOption.IGNORE_CASE),
            Regex("""id="formErrorTips"[^>]*>([^<]{2,80})<""", RegexOption.IGNORE_CASE),
            Regex("""class="auth_error"[^>]*>([^<]{2,80})<""", RegexOption.IGNORE_CASE),
            Regex("""id="msg"[^>]*>([^<]{2,60})<""", RegexOption.IGNORE_CASE),
        )
        for (p in pats) {
            val m = p.find(html)
            if (m != null) {
                val t = m.groupValues[1].trim()
                if (t.isNotEmpty()) return t
            }
        }
        return ERR_HINTS.firstOrNull { html.contains(it) } ?: ""
    }

    // ---------------------------------------------------------------- 登录

    /**
     * 登录并返回用户信息（uid / name / roles）。失败抛 CasException。
     * 成功判据：kqtj 侧拿到 SESSION cookie（不是 HTTP 状态）。
     */
    suspend fun login(
        session: HttpSession,
        username: String,
        password: String,
        onStep: (String) -> Unit = {},
        captcha: (suspend (ByteArray) -> String?)? = null,
    ): Map<String, String> {
        var attempts = 0
        var wantCaptcha = false
        var lastErr = ""

        // 为什么要循环：金智的 needCaptcha 靠页面里的 checkUserCaptcha() 异步判定，
        // 静态 HTML 里可能还是 `needCaptcha = ""`；只有 POST 之后才回「验证码错误」。
        // 所以「POST 被判定要验证码」时，回头带码重试，而不是直接失败。
        while (attempts < 3) {
            attempts++
            onStep(if (attempts == 1) "打开统一身份认证…" else "服务端要求验证码，重新获取（第 $attempts 次）…")

            val page = session.get(
                GATEWAY,
                mapOf(
                    "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "user-agent" to UA,
                ),
            )
            val html = page.body
            if (!html.contains("pwdEncryptSalt") && !html.contains("统一身份认证")) {
                throw CasException("没有落到 CAS 登录页：${page.url}（HTTP ${page.status}）", "NO_CAS_PAGE")
            }
            val loginUrl = page.url
            val form = parseForm(html)
            val salt = form["pwdEncryptSalt"]
                ?: throw CasException("登录页没有 pwdEncryptSalt，页面结构可能变了", "NO_SALT")

            var captchaCode = ""
            if (needsCaptcha(html) || wantCaptcha) {
                if (captcha == null) {
                    throw CasException("服务端要求图形验证码，但当前入口没有提供输入框", "CAPTCHA")
                }
                onStep("需要图形验证码…")
                val img = session.getBytes(
                    "$IDS/authserver/getCaptcha.htl?${System.currentTimeMillis()}",
                    mapOf("accept" to "image/*,*/*", "referer" to loginUrl),
                ) ?: throw CasException("验证码图片下载失败（网络问题）", "CAPTCHA_IMG")
                val code = captcha(img)?.trim().orEmpty()
                if (code.isEmpty()) throw CasException("已取消输入验证码", "CAPTCHA_CANCEL")
                captchaCode = code
            }

            onStep("提交账号密码…")
            val body = LinkedHashMap<String, String>()
            form.forEach { (k, v) -> if (v.isNotEmpty()) body[k] = v }
            body["username"] = username
            body["password"] = encryptPassword(password, salt)
            if (captchaCode.isNotEmpty()) body["captcha"] = captchaCode
            body["_eventId"] = "submit"
            if (!body.containsKey("cllt")) body["cllt"] = "userNameLogin"
            if (!body.containsKey("dllt")) body["dllt"] = "generalLogin"
            if (!body.containsKey("rmShown")) body["rmShown"] = "1"
            // 「7天免登录」：勾上它，服务端下发的 CASTGC 才带 Max-Age（14 天）。
            // parseForm 通常已经把它读进来了，这里兜底一次，免得页面结构一变就退回会话级。
            if (!body.containsKey("rememberMe")) body["rememberMe"] = "true"

            val r = session.postForm(
                loginUrl,
                body,
                mapOf(
                    "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "content-type" to "application/x-www-form-urlencoded",
                    "referer" to loginUrl,
                    "origin" to IDS,
                    "user-agent" to UA,
                ),
            )

            val err = extractError(r.body)
            if (err.contains("验证码") || err.contains("动态码")) {
                wantCaptcha = true
                lastErr = err
                continue
            }
            if (err.isNotEmpty()) throw CasException("登录失败：$err", "BAD_CREDENTIAL")
            if (!session.jar.has("SESSION") && r.body.contains("pwdEncryptSalt")) {
                throw CasException("登录失败：回到登录页（HTTP ${r.status}），账号或密码不对", "BAD_CREDENTIAL")
            }

            onStep("登录成功，读取身份…")
            return fetchUser(session)
        }
        throw CasException("登录失败：${lastErr.ifEmpty { "多次尝试仍未通过" }}", "LOGIN_RETRY")
    }

    /**
     * 从登录成功后的会话里捞出 CASTGC（「7天免登录」那张长期票），交给上层加密存库。
     * 拿不到就返回空串（说明服务端没给长期票，下次照常走密码）。
     */
    fun castgcOf(session: HttpSession): String = session.jar.value("CASTGC").orEmpty()

    /**
     * **免密登录**：只用上次存下的 CASTGC 换业务 SESSION，全程不提交账号密码。
     *
     * 实测链路（2026-10，6 跳，全部 302）：
     *   kqtj/adp/api/login → /adp/authserver/login → /adp/authserver/caslogin
     *   → ids/authserver/login?service=…&token=… → 302 回 kqtj 的 caslogin?ticket=ST-…
     *   → /adp/api/login（404，正常）→ kqtj 下发 SESSION
     *
     * 失败（票据过期 / 在网页主动退出 / 换设备）抛 CAS_LOGIN_EXPIRED，调用方应回落密码登录。
     */
    suspend fun loginByCastgc(
        session: HttpSession,
        castgc: String,
        onStep: (String) -> Unit = {},
    ): Map<String, String> {
        if (castgc.isBlank()) throw CasException("没有免登录凭证", "CAS_LOGIN_EXPIRED")

        // CAS 只认 ids.xaut.edu.cn 主机上、路径 /authserver 的这张 cookie
        session.jar.put("CASTGC", castgc, "ids.xaut.edu.cn", "/authserver")

        onStep("用免登录凭证换会话…")
        val page = session.get(
            GATEWAY,
            mapOf(
                "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "user-agent" to UA,
            ),
        )

        if (!session.jar.has("SESSION")) {
            throw CasException(
                "免登录已失效（票据过期或已在网页退出），需要重新输密码",
                "CAS_LOGIN_EXPIRED",
            )
        }
        // 万一仍然被弹回登录页，也当失效处理
        if (page.body.contains("pwdEncryptSalt")) {
            throw CasException("免登录已失效：被重定向回登录页", "CAS_LOGIN_EXPIRED")
        }

        onStep("免登录成功，读取身份…")
        return fetchUser(session)
    }

    /** GET /adp/api/currentUser → {uid, name, roles} */
    fun fetchUser(session: HttpSession): Map<String, String> {
        val r = AdpApi.currentUser(session)
        val out = LinkedHashMap<String, String>()
        try {
            val j = JSONObject(r.body)
            val resp = j.optJSONObject("response") ?: j.optJSONObject("data") ?: j
            resp.keys().forEach { k ->
                val v = resp.opt(k)
                if (v != null && v !is JSONObject && v !is org.json.JSONArray) out[k] = v.toString()
            }
        } catch (e: Exception) {
            // 不是 JSON（多半是 302 到登录页）——保持空
        }
        return out
    }
}
