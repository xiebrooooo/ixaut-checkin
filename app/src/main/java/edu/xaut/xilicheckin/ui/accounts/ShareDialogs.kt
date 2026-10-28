package edu.xaut.xilicheckin.ui.accounts

import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import edu.xaut.xilicheckin.domain.Account

/**
 * 把一段文本画成二维码。
 * 用 ZXing 的编码器（只编码，不解码 —— 解码走 ML Kit），
 * ECC 用 L 让同样的尺寸能塞下更多字符。
 */
private fun qrBitmap(text: String, sizePx: Int = 720): Bitmap {
    val hints = mapOf(
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.MARGIN to 1,
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
    )
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val w = m.width
    val h = m.height
    val px = IntArray(w * h)
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            px[row + x] = if (m.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        setPixels(px, 0, w, 0, 0, w, h)
    }
}

/** 我的分享二维码 */
@Composable
fun ShareQrDialog(
    state: ShareState,
    onDismiss: () -> Unit,
) {
    val bmp = remember(state.payload) {
        runCatching { qrBitmap(state.payload) }.getOrNull()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分享 ${state.count} 个账号") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (state.error != null) {
                    Text(state.error, color = MaterialTheme.colorScheme.error)
                    return@Column
                }
                Text(
                    "让同学在「账号」页点「扫描并导入」，扫这张码",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                Box(
                    Modifier
                        .size(260.dp)
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "账号分享二维码",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text("二维码生成失败", color = Color.Black)
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text("口令", style = MaterialTheme.typography.labelMedium)
                Text(
                    state.passcode,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "二维码里的密码是加密的，必须把这个口令当面告诉对方，否则解不开。" +
                        "别截图发群里 —— 口令和二维码凑在一起就等于明文密码。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("关掉") } },
    )
}

/** 扫到分享码后，输入口令 */
@Composable
fun SharePasscodeDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    error: String? = null,
) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("输入分享口令") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "对方屏幕上那串 6 位数字。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { v -> code = v.filter { it.isDigit() }.take(12) },
                    label = { Text("口令") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(code) }, enabled = code.length >= 6) { Text("解开") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 解开之后先预览，确认了才写入 */
@Composable
fun ShareImportPreviewDialog(
    state: ShareImport,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val list: List<Account> = state.accounts
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.error != null) "解不开" else "导入 ${list.size} 个账号？") },
        text = {
            Column {
                if (state.error != null) {
                    Text(state.error, color = MaterialTheme.colorScheme.error)
                    return@Column
                }
                Text(
                    "同号账号密码会被覆盖，新学号直接加进来。票据不会跟着过来，导入后会重新登录一次。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                list.take(20).forEach { a ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(
                            a.account + if (a.name.isBlank()) "" else "  " + a.name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text("密码已带入", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (list.size > 20) Text("…还有 ${list.size - 20} 个", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = state.error == null && list.isNotEmpty()) { Text("导入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
