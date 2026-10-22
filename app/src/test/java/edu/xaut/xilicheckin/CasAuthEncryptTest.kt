package edu.xaut.xilicheckin

import edu.xaut.xilicheckin.data.net.CasAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * CAS 密码加密自检 —— 「iv 用 salt 不是 bug，别再改」（任务 B 回归测试）。
 *
 * 背景
 * ----
 * 金智 CAS 页面真实 JS（/authserver/cusTheme/static/common/encrypt.js）是：
 *
 *     plain = randomString(64) + password     // 64 个随机可打印字符 + 真密码
 *     key   = salt                             // pwdEncryptSalt，UTF-8，16 字节
 *     iv    = randomString(16)                 // ← 页面用的是随机 iv
 *     out   = base64(AES-128-CBC-PKCS7(plain))
 *
 * 我们的 [CasAuth.encryptPassword] 用的是 **iv = salt**，不是随机 iv。
 * 这**不是 bug**，原因：
 *
 *   AES-CBC 解密时  P₁ = D(C₁) ⊕ IV，而  Pᵢ = D(Cᵢ) ⊕ Cᵢ₋₁ (i ≥ 2)。
 *   也就是说 **IV 只影响第 1 个 16 字节块**，从第 2 块起与 IV 完全无关。
 *
 *   明文里 64 字符的随机前缀正好占满前 4 块（4×16 = 64），密码从
 *   **字节偏移 64** 开始，落在第 5 块。所以 IV 取错只会毁掉前缀，
 *   密码部分照样完整解出。服务端只要「切掉前 64 个字符」。
 *
 * 一个容易踩的坑（本测试会覆盖）
 * -----------------------------
 * 上面「IV 只影响第 1 块」成立的前提是**按字节切片**：
 *
 *     ok  = bytes.copyOfRange(64, bytes.size).toString(UTF_8)
 *     bad = String(bytes, UTF_8).substring(64)   // ← 先解码再切字符
 *
 * 如果 IV 错了导致第 1 块产生非法 UTF-8 字节，`bad` 那种写法会因为
 * U+FFFD 替换字符而**吞掉密码开头的字符** —— 实测见过 "b20070313"（少 1 位）
 * 和 "070313"（少 3 位），具体少几位取决于随机前缀碰巧产生的坏字节。
 *
 * 我们的 iv = salt 恰好让第 1 块也完全正确，因此两种切法都安全 —— 这是
 * 选择 iv = salt 的额外好处，**没有任何理由去「修正」它**。
 *
 * 历史：2026-10 曾有人把这里误判为 bug 并大改了两个客户端，回滚时还留下
 *       语法残骸。此测试就是防止再次发生。
 *
 * 纯 JVM 测试，不依赖 Android framework（只用到 java.util.Base64）。
 * 运行：gradlew :app:testDebugUnitTest --tests "*CasAuthEncryptTest*"
 */
class CasAuthEncryptTest {

    private val salt = "Xwb2mZ8vGkN0pQrT" // 16 字节，形状与真实 pwdEncryptSalt 一致
    private val pwd = "TestPwd1234"

    /** 用任意 iv 解密，返回**原始密文解密后的字节**（不切前缀） */
    private fun decryptRaw(cipherB64: String, saltStr: String, iv: ByteArray): ByteArray {
        val key = saltStr.toByteArray(Charsets.UTF_8)
        require(key.size == 16)
        require(iv.size == 16)
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.IvParameterSpec(iv),
        )
        return cipher.doFinal(Base64.getDecoder().decode(cipherB64))
    }

    @Test
    fun `输出是 base64 且为 80 字节密文`() {
        val out = CasAuth.encryptPassword(pwd, salt)
        assertTrue("应是 base64：$out", out.matches(Regex("^[A-Za-z0-9+/]+=*$")))
        // 明文 64 + 11 = 75 字节，PKCS7 补到 80 字节 → 108 字符 base64
        assertEquals(108, out.length)
        assertEquals(80, Base64.getDecoder().decode(out).size)
    }

    @Test
    fun `用 iv=salt 能解回 64 位前缀加密码`() {
        val out = CasAuth.encryptPassword(pwd, salt)
        val plain = decryptRaw(out, salt, salt.toByteArray(Charsets.UTF_8))
            .toString(Charsets.UTF_8)
        assertEquals(75, plain.length)
        assertEquals(pwd, plain.substring(64))
    }

    @Test
    fun `错 iv 只损坏第 1 块，按字节切尾部密码仍完全正确`() {
        val cipherB64 = CasAuth.encryptPassword(pwd, salt)
        val good = decryptRaw(cipherB64, salt, salt.toByteArray(Charsets.UTF_8))

        val wrongIvs = mapOf(
            "全 0" to ByteArray(16),
            "全 0xFF" to ByteArray(16) { 0xFF.toByte() },
            "随机" to java.security.SecureRandom().run { ByteArray(16).also { nextBytes(it) } },
        )

        for ((name, iv) in wrongIvs) {
            val buf = decryptRaw(cipherB64, salt, iv)

            // 第 1 块必然不同
            assertNotEquals(
                "iv=$name 时第 1 块竟然没变，测试前提不成立",
                good.copyOfRange(0, 16).toList(),
                buf.copyOfRange(0, 16).toList(),
            )
            // 第 2 块起必须逐字节相同（注意 buf 已去掉 PKCS7 填充，长度 75 字节，
            // 所以按实际长度比较，不能假设满 80 字节）
            for (i in 1 until 5) {
                val from = i * 16
                val to = minOf((i + 1) * 16, buf.size, good.size)
                assertEquals(
                    "iv=$name 时第 ${i + 1} 块被破坏了 —— CBC 只该影响第 1 块",
                    good.copyOfRange(from, to).toList(),
                    buf.copyOfRange(from, to).toList(),
                )
            }
            // 关键断言：按字节切 64，密码完好
            assertEquals(
                "iv=$name 时尾部不是密码",
                pwd,
                buf.copyOfRange(64, buf.size).toString(Charsets.UTF_8),
            )
        }
    }

    @Test
    fun `iv=salt 时两种切法都能拿到密码`() {
        val out = CasAuth.encryptPassword(pwd, salt)
        val buf = decryptRaw(out, salt, salt.toByteArray(Charsets.UTF_8))
        // 按字节切
        assertEquals(pwd, buf.copyOfRange(64, buf.size).toString(Charsets.UTF_8))
        // 先解码再按字符切 —— 只有第 1 块完好时才安全，iv=salt 正是如此
        assertEquals(pwd, String(buf, Charsets.UTF_8).substring(64))
    }

    @Test
    fun `说明性反例：错 iv 加先解码再切字符，会吞掉密码首字符`() {
        val out = CasAuth.encryptPassword(pwd, salt)
        val buf = decryptRaw(out, salt, ByteArray(16) { 0xFF.toByte() })
        val byBytes = buf.copyOfRange(64, buf.size).toString(Charsets.UTF_8)
        val byChars = String(buf, Charsets.UTF_8).substring(64)
        assertEquals("按字节切应该完好", pwd, byBytes)
        // 不断言一定坏（取决于随机前缀产生的坏字节），出现了就记录现象。
        // 实测见过 "b20070313"（少 1 位）和 "070313"（少 3 位），都是 U+FFFD 吃字符所致。
        if (byChars != pwd) {
            println("预期现象：按字符切得到 \"$byChars\"，密码被吞了 ${pwd.length - byChars.length} 个字符")
        }
    }

    @Test
    fun `salt 长度非法时抛异常`() {
        runCatching { CasAuth.encryptPassword(pwd, "short") }
            .onSuccess { throw AssertionError("salt 非 16 字节竟然没抛异常") }
        runCatching { CasAuth.encryptPassword(pwd, "") }
            .onSuccess { throw AssertionError("salt 为空竟然没抛异常") }
    }

    @Test
    fun `同一密码两次加密结果不同但都能解出`() {
        val a = CasAuth.encryptPassword(pwd, salt)
        val b = CasAuth.encryptPassword(pwd, salt)
        assertNotEquals("两次密文相同，说明前缀没随机", a, b)
        assertEquals(
            pwd,
            decryptRaw(a, salt, salt.toByteArray(Charsets.UTF_8))
                .copyOfRange(64, 75).toString(Charsets.UTF_8),
        )
        assertEquals(
            pwd,
            decryptRaw(b, salt, salt.toByteArray(Charsets.UTF_8))
                .copyOfRange(64, 75).toString(Charsets.UTF_8),
        )
    }
}
