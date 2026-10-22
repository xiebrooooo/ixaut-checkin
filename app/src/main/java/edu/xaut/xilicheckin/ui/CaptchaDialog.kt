package edu.xaut.xilicheckin.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import edu.xaut.xilicheckin.domain.CaptchaBridge

/**
 * 图形验证码输入框（全局只挂一个）。
 * 登录线程挂起等这里的结果，所以不用把「验证码」这个概念一路传进 ViewModel。
 */
@Composable
fun CaptchaHost() {
    val challenge by CaptchaBridge.state.collectAsState()
    val c = challenge ?: return
    var text by remember(c) { mutableStateOf("") }

    val bmp = remember(c) {
        runCatching {
            val o = BitmapFactory.Options().apply { inScaled = false }
            BitmapFactory.decodeByteArray(c.image, 0, c.image.size, o)?.asImageBitmap()
        }.getOrNull()
    }

    Dialog(onDismissRequest = { CaptchaBridge.answer(null) }) {
        Card {
            Column(
                Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("需要图形验证码", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "学校在连续输错 3 次密码后会要求验证码。看清图片输入即可，输对之后这次会话就恢复正常。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (bmp != null) {
                    val ratio = if (bmp.height > 0) bmp.width.toFloat() / bmp.height.toFloat() else 3.7f
                    Image(
                        bitmap = bmp,
                        contentDescription = "验证码",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().aspectRatio(ratio),
                    )
                } else {
                    Text("验证码图片加载失败", color = MaterialTheme.colorScheme.error)
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.trim() },
                    label = { Text("请输入图中的验证码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { CaptchaBridge.answer(null) }) { Text("取消") }
                    Button(
                        onClick = { CaptchaBridge.answer(text) },
                        enabled = text.isNotEmpty(),
                    ) { Text("确定") }
                }
            }
        }
    }
}
