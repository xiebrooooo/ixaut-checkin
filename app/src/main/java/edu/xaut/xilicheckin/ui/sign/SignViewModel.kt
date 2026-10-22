package edu.xaut.xilicheckin.ui.sign

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import edu.xaut.xilicheckin.data.repo.AccountRepository
import edu.xaut.xilicheckin.data.repo.ConfigRepository
import edu.xaut.xilicheckin.data.repo.RecordRepository
import edu.xaut.xilicheckin.domain.Account
import edu.xaut.xilicheckin.domain.FieldSpec
import edu.xaut.xilicheckin.domain.ProbeRow
import edu.xaut.xilicheckin.domain.QrParser
import edu.xaut.xilicheckin.domain.ScanTarget
import edu.xaut.xilicheckin.domain.SignEngine
import edu.xaut.xilicheckin.domain.SignEvent
import edu.xaut.xilicheckin.domain.SignResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SignUiState(
    val raw: String = "",
    val target: ScanTarget? = null,
    val fields: List<FieldSpec> = emptyList(),
    val results: List<SignResult> = emptyList(),
    val logs: List<String> = emptyList(),
    val probeRows: List<ProbeRow> = emptyList(),
    val busy: Boolean = false,
    val dry: Boolean = false,
    val progress: Int = 0,
    val total: Int = 0,
    val message: String? = null,
    val scanning: Boolean = false,
)

@HiltViewModel
class SignViewModel @Inject constructor(
    private val accountRepo: AccountRepository,
    private val recordRepo: RecordRepository,
    private val config: ConfigRepository,
) : ViewModel() {

    private val saved = config.loadTarget()

    var state by mutableStateOf(
        SignUiState(raw = saved?.raw ?: "", target = saved)
    )
        private set

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts

    init {
        viewModelScope.launch {
            accountRepo.observe().collect { _accounts.value = it }
        }
    }

    // ------------------------------------------------------------ 输入

    /** 保留给未来「粘贴链接」入口；界面已只留扫码 */
    fun onRawChange(v: String) {
        state = state.copy(raw = v)
    }

    fun parse() {
        val t = QrParser.parse(state.raw)
        state = state.copy(
            target = t,
            fields = if (t.ok) state.fields else emptyList(),
            message = if (t.ok) null else "没解析出 appId / pageId，确认一下粘进来的是不是签到二维码",
        )
        if (t.ok) config.saveTarget(t)
    }

    fun onScanned(text: String) {
        state = state.copy(raw = text, scanning = false)
        parse()
        if (state.target?.ok == true) readFields()
    }

    fun openScanner() { state = state.copy(scanning = true) }
    fun closeScanner() { state = state.copy(scanning = false) }
    fun clearMessage() { state = state.copy(message = null) }

    // ------------------------------------------------------------ 字段

    fun readFields() {
        val t = state.target ?: return
        val acc = _accounts.value.firstOrNull { it.enabled }
        if (acc == null) {
            state = state.copy(message = "先在「账号」页加一个账号")
            return
        }
        viewModelScope.launch {
            state = state.copy(busy = true, message = null, probeRows = emptyList())
            try {
                val snap = SignEngine.loadForm(acc, t) { step -> addLog("${acc.account} · $step") }
                if (snap.fields.isEmpty()) {
                    state = state.copy(busy = false, message = "读不到表单字段：先点「体检」看看卡在哪一环")
                    return@launch
                }
                val fields = snap.fields.map {
                    FieldSpec(
                        key = it.field,
                        label = if (it.label.isBlank()) it.field else it.label,
                        value = it.value,
                        original = it.value,
                        fromServer = true,
                    )
                }
                val rid = SignEngine.recordIdOf(t)
                state = state.copy(
                    busy = false,
                    fields = fields,
                    message = if (rid.isEmpty()) {
                        "拿到 ${fields.size} 个字段，但二维码里没有考勤记录 id，多半不是签到码"
                    } else {
                        "拿到 ${fields.size} 个字段 · 考勤记录 $rid"
                    },
                )
            } catch (e: Exception) {
                state = state.copy(busy = false, message = "读取字段失败：${e.message}")
            }
        }
    }

    fun onFieldChange(key: String, value: String) {
        state = state.copy(
            fields = state.fields.map { if (it.key == key) it.copy(value = value) else it }
        )
    }

    /** 只把用户真正改过的字段作为覆盖项 —— 服务端预填的姓名/学号各账号不同，绝不能互串 */
    private fun overrides(): Map<String, String> =
        state.fields
            .filter { it.value != it.original }
            .associate { it.key to it.value }

    // ------------------------------------------------------------ 签到

    fun sign(dry: Boolean) {
        val t = state.target
        if (t == null || !t.ok) {
            state = state.copy(message = "先粘二维码 / 扫一次码")
            return
        }
        val list = _accounts.value.filter { it.enabled }
        if (list.isEmpty()) {
            state = state.copy(message = "没有启用的账号")
            return
        }
        val ov = overrides()
        config.saveOverrides(ov)

        viewModelScope.launch {
            state = state.copy(
                busy = true, dry = dry, results = emptyList(), logs = emptyList(),
                progress = 0, total = list.size, message = null, probeRows = emptyList(),
            )
            val results = SignEngine.signAll(list, t, ov, dry) { ev ->
                when (ev) {
                    is SignEvent.Log -> addLog(ev.text)
                    is SignEvent.Progress -> state = state.copy(progress = ev.done, total = ev.total)
                    is SignEvent.Result -> {
                        state = state.copy(results = state.results + ev.result)
                        viewModelScope.launch { recordRepo.save(ev.result, t.appId, t.pageId) }
                    }
                }
            }
            val ok = results.count { it.status == edu.xaut.xilicheckin.domain.SignStatus.OK }
            val skip = results.count { it.status == edu.xaut.xilicheckin.domain.SignStatus.SKIP }
            val fail = results.count { it.status == edu.xaut.xilicheckin.domain.SignStatus.FAIL }
            state = state.copy(
                busy = false,
                message = if (dry) "演练完成：$ok 可提交 / $skip 跳过 / $fail 失败（没有真的提交）"
                else "完成：成功 $ok / 跳过 $skip / 失败 $fail",
            )
        }
    }

    fun probe() {
        val t = state.target ?: return
        val acc = _accounts.value.firstOrNull { it.enabled }
        if (acc == null) {
            state = state.copy(message = "先在「账号」页加一个账号")
            return
        }
        viewModelScope.launch {
            state = state.copy(busy = true, message = null, probeRows = emptyList())
            val rows = SignEngine.probe(acc, t) { step -> addLog("${acc.account} · $step") }
            state = state.copy(busy = false, probeRows = rows)
        }
    }

    private fun addLog(line: String) {
        state = state.copy(logs = (state.logs + line).takeLast(80))
    }
}
