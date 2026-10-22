package edu.xaut.xilicheckin.ui.sign

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import edu.xaut.xilicheckin.domain.FieldSpec
import edu.xaut.xilicheckin.domain.SignStatus
import edu.xaut.xilicheckin.ui.scan.ScannerScreen

@Composable
fun SignScreen(vm: SignViewModel) {
    var showAllFields by remember { mutableStateOf(false) }
    val state = vm.state
    val accounts by vm.accounts.collectAsState()

    if (state.scanning) {
        Dialog(
            onDismissRequest = vm::closeScanner,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            ScannerScreen(onDecoded = vm::onScanned, onClose = vm::closeScanner)
        }
        return
    }

    val enabled = accounts.count { it.enabled }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "一次扫码 · $enabled 个账号同时签到",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        // ------------------------------------------------ 扫码目标
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("① 扫码签到", style = MaterialTheme.typography.labelLarge)

                    if (state.target?.ok == true) {
                        Text(
                            "已识别这张签到码，可以签到全部账号。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "对准投影/屏幕上的签到二维码，扫到后会读出这门课的考勤信息。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.target?.warnings?.forEach {
                        Text(
                            "· $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::openScanner, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.QrCodeScanner, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (state.target?.ok == true) "重新扫码" else "扫码")
                        }
                    }
                }
            }
        }

        // ------------------------------------------------ 表单字段
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    var showDetail by remember { mutableStateOf(false) }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("② 表单字段", style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = { showDetail = !showDetail }) {
                            Text(if (showDetail) "收起说明" else "这栏是什么？")
                        }
                    }

                    if (showDetail) {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Text(
                                "这里显示的是学校服务器上「签到表单」本身的字段定义和它预填的值，" +
                                    "相当于把网页版的这个表单拆开给你看。它的用处有三个：\n" +
                                    "1）校验：能读出来，说明账号、会话、页面 id 都是对的，扫码扫到的确实是这张表单；" +
                                    "不是签到码时报错会停在这一步。\n" +
                                    "2）排错：真签到失败时，可以当场看到是哪个字段没拿到值" +
                                    "（比如考勤日期空、考勤轮次空），一眼定位是哪一环断了。\n" +
                                    "3）改值：少数情况下要手工补一个值，直接在这里改就会带到提交里。" +
                                    "一般不用动 —— 姓名/学号/院系这些是服务器按账号自动填的，改了反而会出错。\n\n" +
                                    "「读取字段」= 登录一个账号、把这张表单的字段拉回来。\n" +
                                    "「体检」= 把这套接口逐个点一遍（登录、currentUser、表单、考勤回填、校验），" +
                                    "每一步给出原始返回。它是排障工具：出问题时先点它，就能知道断在哪个接口。\n\n" +
                                    "点「签到全部账号」时并不需要你在这里填什么，" +
                                    "程序会按账号各自重新拉一遍表单再提交 —— 这一栏纯粹是给人看的。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }

                    if (state.fields.isEmpty()) {
                        Text(
                            "扫完码点「读取字段」，能读出表单说明账号和这张签到码都是好的。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = vm::readFields) { Text("读取字段") }
                            OutlinedButton(onClick = vm::probe) { Text("体检") }
                        }
                    } else {
                        val isNoise = { f: FieldSpec ->
                            f.label.contains("不生效") || f.label.startsWith("不可见-")
                        }
                        val noiseCount = state.fields.count(isNoise)
                        val shown = if (showAllFields) state.fields else state.fields.filterNot(isNoise)
                        if (noiseCount > 0) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "共 ${state.fields.size} 个字段，已折叠 $noiseCount 个不生效/不可见字段",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(onClick = { showAllFields = !showAllFields }) {
                                    Text(if (showAllFields) "只看要填的" else "全部展开")
                                }
                            }
                        }
                        shown.forEach { f ->
                            OutlinedTextField(
                                value = f.value,
                                onValueChange = { vm.onFieldChange(f.key, it) },
                                label = { Text(f.label) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = vm::readFields) { Text("重新读取") }
                            OutlinedButton(onClick = vm::probe) { Text("体检") }
                        }
                    }
                }
            }
        }

        // ------------------------------------------------ 操作
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { vm.sign(true) },
                    enabled = !state.busy && state.target?.ok == true,
                    modifier = Modifier.weight(1f),
                ) { Text("演练一次") }
                Button(
                    onClick = { vm.sign(false) },
                    enabled = !state.busy && state.target?.ok == true && enabled > 0,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Bolt, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (state.busy) "进行中…" else "签到全部账号")
                }
            }
            Text(
                "扫码后点这个按钮，$enabled 个账号会各自登录、各自提交，互不串号（并发 3 路）。" +
                    "提交那一下和你手机上点「考勤打卡」发的是同一个请求，不是模拟点击。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        if (state.busy) {
            item {
                Column {
                    LinearProgressIndicator(
                        progress = {
                            if (state.total == 0) 0f else state.progress.toFloat() / state.total
                        },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                    )
                    Text(
                        "${state.progress}/${state.total}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        state.message?.let { msg ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(msg, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = vm::clearMessage) { Text("知道了") }
                    }
                }
            }
        }

        // ------------------------------------------------ 结果
        if (state.results.isNotEmpty()) {
            item { Text("结果", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
            items(state.results) { r ->
                val color = when (r.status) {
                    SignStatus.OK -> MaterialTheme.colorScheme.primary
                    SignStatus.SKIP -> MaterialTheme.colorScheme.tertiary
                    SignStatus.FAIL -> MaterialTheme.colorScheme.error
                }
                val word = when (r.status) {
                    SignStatus.OK -> "成功"
                    SignStatus.SKIP -> "跳过"
                    SignStatus.FAIL -> "失败"
                }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .background(color.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(word, color = color, style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.account, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                r.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // ------------------------------------------------ 体检结果
        if (state.probeRows.isNotEmpty()) {
            item { Text("接口体检", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
            items(state.probeRows) { row ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(row.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                row.verdict,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (row.verdict.startsWith("ok")) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                            )
                        }
                        if (row.body.isNotBlank()) {
                            Text(
                                row.body,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
        }

        // ------------------------------------------------ 日志
        if (state.logs.isNotEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("过程日志", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            state.logs.takeLast(12).joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}
