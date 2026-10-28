package edu.xaut.xilicheckin.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import edu.xaut.xilicheckin.data.repo.AccountRepository
import edu.xaut.xilicheckin.domain.Account
import edu.xaut.xilicheckin.domain.AccountTest
import edu.xaut.xilicheckin.domain.SignEngine
import edu.xaut.xilicheckin.domain.ShareBag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/** 「测试密码」的结果状态 */
data class AccountTestState(
    val running: Boolean = false,
    val summary: String = "",
    val results: Map<String, AccountTest> = emptyMap(),
)

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val repo: AccountRepository,
) : ViewModel() {

    val accounts: StateFlow<List<Account>> = repo.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _test = MutableStateFlow(AccountTestState())
    val test: StateFlow<AccountTestState> = _test

    fun upsert(account: String, password: String, name: String) {
        viewModelScope.launch { repo.upsert(account, password, name) }
    }

    fun importText(text: String) {
        viewModelScope.launch { repo.importLines(text) }
    }

    fun delete(account: String) {
        viewModelScope.launch { repo.delete(account) }
    }

    fun setEnabled(account: String, enabled: Boolean) {
        viewModelScope.launch { repo.setEnabled(account, enabled) }
    }

    /**
     * 逐个登录验证学号密码对不对：只走 CAS 登录 + currentUser，
     * 不读表单、不提交，不产生任何签到记录。
     */
    fun testAll() {
        if (_test.value.running) return
        viewModelScope.launch {
            val list = repo.enabled()
            if (list.isEmpty()) {
                _test.value = AccountTestState(summary = "没有启用中的账号")
                return@launch
            }
            _test.value = AccountTestState(running = true, summary = "正在验证 ${list.size} 个账号…")
            val acc = ConcurrentHashMap<String, AccountTest>()
            val t0 = System.currentTimeMillis()
            runCatching {
                SignEngine.testAccounts(list, concurrency = 3) { r ->
                    acc[r.account] = r
                    _test.value = _test.value.copy(results = acc.toMap())
                }
            }.onFailure { e ->
                _test.value = AccountTestState(summary = "测试中断：" + (e.message ?: e.javaClass.simpleName))
                return@launch
            }
            val ok = acc.values.count { it.ok }
            val bad = list.size - ok
            val ms = System.currentTimeMillis() - t0
            _test.value = AccountTestState(
                running = false,
                results = acc.toMap(),
                summary = if (bad == 0) "全部可用：$ok/${list.size}，用时 ${ms}ms"
                else "可用 $ok / 不可用 $bad，用时 ${ms}ms",
            )
        }
    }

    fun clearTest() {
        _test.value = AccountTestState()
    }

    // ---------------------------------------------------------- 分享 / 导入

    /** 分享二维码状态：payload 是加密后的串，passcode 只显示给自己看 */
    private val _share = MutableStateFlow<ShareState?>(null)
    val share: StateFlow<ShareState?> = _share

    /** 扫到的分享码解开后的预览 */
    private val _shareImport = MutableStateFlow<ShareImport?>(null)
    val shareImport: StateFlow<ShareImport?> = _shareImport

    /** 生成分享二维码：默认只分享「启用中」的账号 */
    fun prepareShare() {
        viewModelScope.launch {
            val list = repo.enabled()
            if (list.isEmpty()) {
                _share.value = ShareState(payload = "", passcode = "", count = 0, error = "没有启用的账号")
                return@launch
            }
            // 每次分享换一个随机口令：上次的口令不该能解开这次的码
            val passcode = (100_000 + (Math.random() * 900_000).toInt()).toString()
            runCatching { ShareBag.encode(list, passcode) }
                .onSuccess { _share.value = ShareState(payload = it, passcode = passcode, count = list.size) }
                .onFailure {
                    _share.value = ShareState(
                        payload = "", passcode = "", count = 0,
                        error = it.message ?: "生成分享码失败",
                    )
                }
        }
    }

    fun clearShare() {
        _share.value = null
    }

    /** 扫到分享码 + 口令 → 解出账号列表（先不写库） */
    fun decodeShare(payload: String, passcode: String) {
        viewModelScope.launch {
            _shareImport.value = runCatching { ShareBag.decode(payload, passcode) }
                .fold(
                    onSuccess = { ShareImport(accounts = it) },
                    onFailure = { ShareImport(error = it.message ?: "解不开这个分享码") },
                )
        }
    }

    fun clearShareImport() {
        _shareImport.value = null
    }

    /** 确认导入：同号覆盖密码，新学号新增；票据/启用状态沿用本机已有设置 */
    fun confirmShareImport() {
        val list = _shareImport.value?.accounts ?: return
        viewModelScope.launch {
            list.forEach { repo.upsert(it.account, it.password, it.name) }
            _shareImport.value = null
        }
    }
}

/** 分享二维码 */
data class ShareState(
    val payload: String,
    val passcode: String,
    val count: Int,
    val error: String? = null,
)

/** 扫码导入的预览结果 */
data class ShareImport(
    val accounts: List<Account> = emptyList(),
    val error: String? = null,
)
