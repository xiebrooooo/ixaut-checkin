package edu.xaut.xilicheckin.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import edu.xaut.xilicheckin.data.net.UpdateChecker
import kotlinx.coroutines.launch

private data class UpdateUi(
    val checking: Boolean = false,
    val info: UpdateChecker.Info? = null,
    val downloading: Boolean = false,
    val done: Long = 0,
    val total: Long = 0,
    val error: String? = null,
    val message: String? = null,
)

/**
 * 应用内更新：启动时静默查一次 GitHub Release，点顶栏图标手动再查。
 * 发现新版 → 弹窗问「是否下载并更新」→ 下载 → 拉起系统安装器。
 *
 * @param requestKey 每次自增表示「用户手动点了一次检查」
 */
@Composable
fun UpdateHost(requestKey: Int = 0) {
    val ctx = LocalContext.current
    val checker = remember { UpdateChecker(ctx) }
    val scope = rememberCoroutineScope()
    var ui by remember { mutableStateOf(UpdateUi()) }
    val current = remember { checker.installedVersion() }

    fun check(manual: Boolean) {
        if (ui.checking || ui.downloading) return
        ui = ui.copy(checking = true, error = null, message = null, info = null)
        scope.launch {
            val r = checker.latest(current)
            ui = r.fold(
                onSuccess = { info ->
                    if (info == null) {
                        ui.copy(
                            checking = false,
                            message = if (manual) "已是最新版（$current）" else null,
                        )
                    } else {
                        ui.copy(checking = false, info = info)
                    }
                },
                onFailure = { e ->
                    ui.copy(
                        checking = false,
                        // 自动检查失败就闭嘴，别拿网络问题烦人
                        error = if (manual) "检查更新失败：" + (e.message ?: e.javaClass.simpleName) else null,
                    )
                },
            )
        }
    }

    LaunchedEffect(Unit) { check(manual = false) }
    LaunchedEffect(requestKey) { if (requestKey > 0) check(manual = true) }

    // 「已是最新」这类小提示 3 秒后自动消失
    LaunchedEffect(ui.message) {
        if (ui.message != null) {
            kotlinx.coroutines.delay(3000)
            ui = ui.copy(message = null)
        }
    }

    val info = ui.info
    if (info != null) {
        AlertDialog(
            onDismissRequest = { if (!ui.downloading) ui = ui.copy(info = null) },
            title = { Text("发现新版本 ${info.version}") },
            text = {
                Column {
                    Text("当前版本 $current", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    if (info.notes.isNotBlank()) {
                        Text(
                            info.notes.take(1200),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    if (ui.downloading) {
                        val p = if (ui.total > 0) (ui.done * 100 / ui.total).toInt() else 0
                        Text("正在下载… $p%", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { if (ui.total > 0) ui.done.toFloat() / ui.total else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    val err = ui.error
                    if (err != null) {
                        Text(err, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !ui.downloading,
                    onClick = {
                        ui = ui.copy(downloading = true, done = 0, total = info.size, error = null)
                        scope.launch {
                            runCatching {
                                val f = checker.download(info) { d, t ->
                                    ui = ui.copy(done = d, total = t)
                                }
                                checker.install(f)
                            }.onFailure { e ->
                                ui = ui.copy(
                                    downloading = false,
                                    error = "下载失败：" + (e.message ?: e.javaClass.simpleName) +
                                        "（可直接去 GitHub Releases 手动下载）",
                                )
                            }.onSuccess {
                                ui = ui.copy(downloading = false, info = null)
                            }
                        }
                    },
                ) { Text(if (ui.downloading) "下载中…" else "下载并更新") }
            },
            dismissButton = {
                TextButton(onClick = { ui = ui.copy(info = null) }, enabled = !ui.downloading) {
                    Text("以后再说")
                }
            },
        )
    }

    val msg = ui.message ?: ui.error
    if (msg != null) {
        AlertDialog(
            onDismissRequest = { ui = ui.copy(message = null, error = null) },
            title = { Text("检查更新") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { ui = ui.copy(message = null, error = null) }) { Text("好") }
            },
        )
    }
}
