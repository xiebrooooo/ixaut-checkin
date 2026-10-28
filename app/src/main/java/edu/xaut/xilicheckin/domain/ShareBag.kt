package edu.xaut.xilicheckin.domain

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 「账号分享包」：把若干账号编码成一段能塞进二维码的字符串，让对方扫码导入。
 *
 * 安全模型（重要，别改坏）：
 *  - 二维码里**绝不放明文密码**。用分享者设定的口令 + PBKDF2 派生 AES-256-GCM 密钥，
 *    口令当面/口头告给接收方。二维码被旁人拍到、被截图转发，也解不出密码。
 *  - **不分享 CASTGC 免登录票据**：那是绑定本机 Keystore 的一次性授权，不该跟着账号走。
 *  - 只编码 account / password / name 三个字段，别的什么都不带。
 *
 * 传输格式：`XILISHARE1:` + Base64( salt16 | iv12 | AES-GCM( gzip( json ) ) )
 * 明文 JSON：{"v":1,"ts":时间戳,"accounts":[{"a":"学号","p":"密码","n":"备注"}]}
 */
object ShareBag {

    const val PREFIX = "XILISHARE1:"

    private const val ITERATIONS = 50_000
    private const val KEY_BITS = 256
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    /** 扫到的东西像不像一个分享码（扫码页据此分流） */
    fun looksLikeShare(text: String): Boolean =
        text.trim().startsWith("XILISHARE", ignoreCase = true)

    /** 口令只要数字，长度 6–12；返回规范化后的口令 */
    fun normalizePasscode(raw: String): String = raw.filter { it.isDigit() }.take(12)

    fun isPasscodeOk(passcode: String): Boolean = normalizePasscode(passcode).length >= 6

    /** 把账号列表加密成一个二维码字符串 */
    fun encode(accounts: List<Account>, passcode: String): String {
        val pwd = normalizePasscode(passcode)
        require(pwd.length >= 6) { "口令至少 6 位数字" }

        val arr = JSONArray()
        accounts.forEach { a ->
            if (a.account.isBlank() || a.password.isEmpty()) return@forEach
            arr.put(
                JSONObject().apply {
                    put("a", a.account)
                    put("p", a.password)
                    if (a.name.isNotBlank()) put("n", a.name)
                }
            )
        }
        require(arr.length() > 0) { "没有可分享的账号" }

        val json = JSONObject().apply {
            put("v", 1)
            put("ts", System.currentTimeMillis())
            put("accounts", arr)
        }.toString()

        val rnd = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { rnd.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { rnd.nextBytes(it) }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyOf(pwd, salt), GCMParameterSpec(TAG_BITS, iv))
        val ct = cipher.doFinal(gzip(json.toByteArray(Charsets.UTF_8)))

        val blob = salt + iv + ct
        return PREFIX + Base64.getEncoder().encodeToString(blob)
    }

    /**
     * 解出账号列表。
     * @throws IllegalArgumentException 口令错 / 分享码被改动 / 内容不完整
     */
    fun decode(payload: String, passcode: String): List<Account> {
        val pwd = normalizePasscode(passcode)
        if (pwd.length < 6) throw IllegalArgumentException("口令至少 6 位数字")

        val body = payload.trim().let {
            if (it.startsWith(PREFIX, ignoreCase = true)) it.substring(PREFIX.length) else it
        }
        val blob = try {
            Base64.getDecoder().decode(body)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("分享码不是有效的二维码内容")
        }
        if (blob.size <= SALT_LEN + IV_LEN) throw IllegalArgumentException("分享码不完整")

        val salt = blob.copyOfRange(0, SALT_LEN)
        val iv = blob.copyOfRange(SALT_LEN, SALT_LEN + IV_LEN)
        val ct = blob.copyOfRange(SALT_LEN + IV_LEN, blob.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyOf(pwd, salt), GCMParameterSpec(TAG_BITS, iv))
        val plain = try {
            cipher.doFinal(ct)
        } catch (e: AEADBadTagException) {
            throw IllegalArgumentException("口令不对，或者分享码被改过")
        }

        val obj = JSONObject(String(gunzip(plain), Charsets.UTF_8))
        val arr = obj.optJSONArray("accounts") ?: JSONArray()
        val out = ArrayList<Account>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val acc = o.optString("a").trim()
            val password = o.optString("p")
            if (acc.isEmpty() || password.isEmpty()) continue
            out.add(Account(account = acc, password = password, name = o.optString("n")))
        }
        if (out.isEmpty()) throw IllegalArgumentException("这个分享包里没有账号")
        return out
    }

    private fun keyOf(passcode: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passcode.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream(data.size / 2 + 64)
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun gunzip(data: ByteArray): ByteArray {
        return GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
    }
}
