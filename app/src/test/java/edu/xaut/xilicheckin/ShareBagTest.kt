package edu.xaut.xilicheckin

import edu.xaut.xilicheckin.domain.Account
import edu.xaut.xilicheckin.domain.ShareBag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 账号分享包：加解密闭环 + 抗篡改 + 体积能塞进二维码 */
class ShareBagTest {

    private val list = listOf(
        Account(account = "2023010001", password = "p@ss1", name = "张三"),
        Account(account = "2023010002", password = "中文密码#2", name = ""),
    )

    @Test
    fun roundTripKeepsData() {
        val payload = ShareBag.encode(list, "123456")
        assertTrue(ShareBag.looksLikeShare(payload))

        val back = ShareBag.decode(payload, "123456")
        assertEquals(2, back.size)
        assertEquals("2023010001", back[0].account)
        assertEquals("p@ss1", back[0].password)
        assertEquals("张三", back[0].name)
        assertEquals("中文密码#2", back[1].password)
    }

    @Test
    fun payloadNeverContainsPlaintextPassword() {
        val payload = ShareBag.encode(list, "123456")
        assertFalse("二维码里出现了明文密码", payload.contains("p@ss1"))
        assertFalse("二维码里出现了明文密码", payload.contains("中文密码"))
    }

    @Test
    fun wrongPasscodeFails() {
        val payload = ShareBag.encode(list, "123456")
        val e = runCatching { ShareBag.decode(payload, "654321") }.exceptionOrNull()
        assertNotNull("错误的验证码居然解开了", e)
        assertTrue(e is IllegalArgumentException)
    }

    @Test
    fun tamperedPayloadFails() {
        val payload = ShareBag.encode(list, "123456")
        val cut = payload.dropLast(6)
        assertNotNull(runCatching { ShareBag.decode(cut, "123456") }.exceptionOrNull())
    }

    @Test
    fun fiveAccountsFitInOneQr() {
        val five = (1..5).map { Account(account = "202301000$it", password = "Passw0rd!$it", name = "同学$it") }
        val payload = ShareBag.encode(five, "520520")
        assertTrue("payload 太长（${payload.length} 字符），二维码会太密扫不动", payload.length < 900)
        assertEquals(5, ShareBag.decode(payload, "520520").size)
    }

    @Test
    fun skipsEmptyEntries() {
        val dirty = listOf(
            Account(account = "", password = "x"),
            Account(account = "2023010001", password = ""),
            Account(account = "2023010003", password = "ok"),
        )
        val back = ShareBag.decode(ShareBag.encode(dirty, "111111"), "111111")
        assertEquals(1, back.size)
        assertEquals("2023010003", back[0].account)
    }
}
