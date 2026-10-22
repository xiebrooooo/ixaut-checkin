package edu.xaut.xilicheckin.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** 一次图形验证码挑战：图片字节 + 提示文案 */
data class CaptchaChallenge(
    val image: ByteArray,
    val hint: String = "登录需要图形验证码",
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * 图形验证码「半自动」通道。
 *
 * 为什么需要它：金智 authserver 在**同一账号连续输错 3 次**之后会打开 needCaptcha，
 * 之后所有登录（哪怕密码正确）都必须带图形验证码。这不是我们的 bug，是服务端风控。
 *
 * 用法：登录时若页面要求验证码，就 fetchCaptcha 拿到图片 → here.ask() 把图交给界面 →
 * 用户在对话框里看图输入 → 返回 4 位码 → 带着 captcha 参数重新 POST。
 */
object CaptchaBridge {

    private val _state = MutableStateFlow<CaptchaChallenge?>(null)
    val state: StateFlow<CaptchaChallenge?> = _state

    @Volatile
    private var pending: CompletableDeferred<String?>? = null

    /** 挂起等待用户输入；超时或取消返回 null */
    suspend fun ask(image: ByteArray, hint: String = "登录需要图形验证码"): String? {
        val d = CompletableDeferred<String?>()
        pending = d
        _state.value = CaptchaChallenge(image, hint)
        return try {
            withTimeoutOrNull(180_000) { d.await() }
        } finally {
            _state.value = null
            pending = null
        }
    }

    /** 界面回填用户输入的验证码（传 null = 取消） */
    fun answer(code: String?) {
        pending?.complete(code)
    }

    val waiting: Boolean get() = pending?.isCompleted == false
}
