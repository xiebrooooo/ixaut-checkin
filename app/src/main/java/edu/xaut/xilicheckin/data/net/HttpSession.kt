package edu.xaut.xilicheckin.data.net

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 每个账号一套 cookie（多账号并存的根本）。
 * cookie 本身只存内存；真正需要跨进程活下来的是 CAS 的 CASTGC，
 * 它由 CasAuth 单独取出、加密落库（见 AccountEntity.castgcCipher），
 * 下次签到直接塞回这里，省掉一次账号密码登录。
 */
class MemoryCookieJar : CookieJar {
    private val store = HashMap<String, Cookie>()

    private fun keyOf(c: Cookie) = c.domain + "|" + c.path + "|" + c.name

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (c in cookies) store[keyOf(c)] = c
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val dead = mutableListOf<String>()
        val out = mutableListOf<Cookie>()
        for ((k, c) in store) {
            if (c.expiresAt < now) dead += k
            else if (c.matches(url)) out += c
        }
        dead.forEach { store.remove(it) }
        return out
    }

    @Synchronized
    fun has(name: String): Boolean = store.values.any { it.name == name && it.expiresAt > System.currentTimeMillis() }

    /** 取某个 cookie 的值（用来在登录后捞出 CASTGC，存起来下次免密用） */
    @Synchronized
    fun value(name: String): String? =
        store.values.firstOrNull { it.name == name && it.expiresAt > System.currentTimeMillis() }?.value

    /**
     * 手动塞入一个 cookie。
     * 用途：下一次签到不再提交账号密码，而是把上次拿到的 CASTGC 直接放到
     * ids.xaut.edu.cn 头上，让 CAS 认这张票、直接回跳发 SESSION。
     */
    @Synchronized
    fun put(name: String, value: String, domain: String, path: String) {
        if (value.isBlank()) return
        val now = System.currentTimeMillis()
        val c = runCatching {
            Cookie.Builder()
                .name(name)
                .value(value)
                .domain(domain)
                .path(path)
                .expiresAt(now + 30L * 24 * 3600 * 1000)
                .httpOnly()
                .build()
        }.getOrNull() ?: return
        store[keyOf(c)] = c
    }

    @Synchronized
    fun clear() = store.clear()
}

data class Resp(
    val status: Int,
    val body: String,
    val url: String,
    val location: String = "",
)

/** 一个账号一条 HttpSession，内部持有独立的 cookie jar */
class HttpSession(val jar: MemoryCookieJar = MemoryCookieJar()) {

    private val jsonMedia = "application/json;charset=UTF-8".toMediaType()
    private val formMedia = "application/x-www-form-urlencoded".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(jar)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun exec(req: Request): Resp {
        client.newCall(req).execute().use { r ->
            val text = try {
                r.body?.string() ?: ""
            } catch (e: Exception) {
                ""
            }
            return Resp(
                status = r.code,
                body = text,
                url = r.request.url.toString(),
                location = r.header("Location") ?: "",
            )
        }
    }

    /** 抓二进制（图形验证码图片用） */
    fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray? {
        val b = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> b.header(k, v) }
        return try {
            client.newCall(b.build()).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.bytes()
            }
        } catch (e: Exception) {
            null
        }
    }

    fun get(url: String, headers: Map<String, String> = emptyMap()): Resp {
        val b = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> b.header(k, v) }
        return exec(b.build())
    }

    fun postForm(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()): Resp {
        val body = form.entries.joinToString("&") { (k, v) ->
            encode(k) + "=" + encode(v)
        }
        val b = Request.Builder().url(url).post(body.toRequestBody(formMedia))
        headers.forEach { (k, v) -> b.header(k, v) }
        return exec(b.build())
    }

    fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): Resp {
        val b = Request.Builder().url(url).post(json.toRequestBody(jsonMedia))
        headers.forEach { (k, v) -> b.header(k, v) }
        return exec(b.build())
    }

    private fun encode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")
}
