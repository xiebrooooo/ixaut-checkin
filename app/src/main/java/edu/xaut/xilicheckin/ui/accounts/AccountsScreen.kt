package edu.xaut.xilicheckin.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import edu.xaut.xilicheckin.domain.Account
import edu.xaut.xilicheckin.domain.AccountTest
import edu.xaut.xilicheckin.domain.ShareBag
import edu.xaut.xilicheckin.ui.scan.ScannerScreen

@Composable
fun AccountsScreen(vm: AccountsViewModel) {
    val accounts by vm.accounts.collectAsState()
    val test by vm.test.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }

    // 分享 / 扫码导入
    val share by vm.share.collectAsState()
    val shareImport by vm.shareImport.collectAsState()
    var scanShares by remember { mutableStateOf(false) }
    var pendingShare by remember { mutableStateOf<String?>(null) }
    var scanTip by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    "${accounts.size} 个账号 · ${accounts.count { it.enabled }} 个启用",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "密码用 Android Keystore 加密存在本机",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row {
                IconButton(onClick = { vm.prepareShare() }, enabled = accounts.isNotEmpty()) {
                    Icon(Icons.Filled.Share, contentDescription = "分享账号")
                }
                IconButton(onClick = { scanShares = true }) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = "扫描并导入")
                }
                IconButton(onClick = { showImport = true }) {
                    Icon(Icons.Filled.FileDownload, contentDescription = "批量导入")
                }
                IconButton(onClick = { showAdd = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "添加账号")
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { vm.testAll() },
                enabled = !test.running && accounts.isNotEmpty(),
            ) {
                Text(if (test.running) "测试中…" else "测试密码")
            }
            Text(
                test.summary.ifEmpty { "只登录验号，不签到、不产生记录" },
                style = MaterialTheme.typography.bodySmall,
                color = if (test.results.values.any { !it.ok }) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (accounts.isEmpty()) {
            Text(
                "还没有账号。点右上角 + 添加，或批量粘贴「学号 密码」一行一个。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp),
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(accounts, key = { it.account }) { acc ->
                AccountRow(
                    acc = acc,
                    result = test.results[acc.account],
                    onToggle = { vm.setEnabled(acc.account, it) },
                    onDelete = { vm.delete(acc.account) },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showAdd) {
        AddAccountDialog(
            onDismiss = { showAdd = false },
            onConfirm = { a, p, n ->
                vm.upsert(a, p, n)
                showAdd = false
            },
        )
    }

    if (showImport) {
        ImportDialog(
            onDismiss = { showImport = false },
            onConfirm = { text ->
                vm.importText(text)
                showImport = false
            },
        )
    }

    // ---- 分享：我的二维码 ----
    share?.let { st ->
        ShareQrDialog(state = st, onDismiss = { vm.clearShare() })
    }

    // ---- 导入：扫码 → 输口令 → 预览 → 确认 ----
    if (scanShares) {
        ScannerScreen(
            onDecoded = { text ->
                scanShares = false
                if (ShareBag.looksLikeShare(text)) {
                    vm.clearShareImport()
                    pendingShare = text
                } else {
                    scanTip = "这张码不是账号分享码。签到二维码请到「签到」页扫。"
                }
            },
            onClose = { scanShares = false },
        )
    }

    val decoded = shareImport
    if (pendingShare != null && decoded?.accounts.isNullOrEmpty()) {
        SharePasscodeDialog(
            error = decoded?.error,
            onDismiss = {
                pendingShare = null
                vm.clearShareImport()
            },
            onConfirm = { code -> pendingShare?.let { vm.decodeShare(it, code) } },
        )
    }

    if (decoded != null && decoded.accounts.isNotEmpty()) {
        ShareImportPreviewDialog(
            state = decoded,
            onDismiss = {
                pendingShare = null
                vm.clearShareImport()
            },
            onConfirm = {
                vm.confirmShareImport()
                pendingShare = null
            },
        )
    }

    scanTip?.let { msg ->
        AlertDialog(
            onDismissRequest = { scanTip = null },
            title = { Text("扫到的不是分享码") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { scanTip = null }) { Text("好") } },
        )
    }
}

@Composable
private fun AccountRow(
    acc: Account,
    result: AccountTest?,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(acc.account, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (acc.name.isNotBlank()) {
                    Text(
                        acc.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (result != null) {
                    Text(
                        (if (result.ok) "✓ " else "✗ ") + result.detail + " · " + result.ms + "ms",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (result.ok) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                }
            }
            Switch(checked = acc.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun AddAccountDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit,
) {
    var account by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加账号") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = account,
                    onValueChange = { account = it },
                    label = { Text("学号") },
                    placeholder = { Text("统一身份认证账号（学号）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    placeholder = { Text("统一身份认证密码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("备注（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(account, password, name) },
                enabled = account.isNotBlank() && password.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ImportDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量导入") },
        text = {
            Column {
                Text(
                    "一行一个：账号 密码 [备注]",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    placeholder = { Text("2023010001 密码 张三\n2023010002 密码 李四") },
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("导入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
