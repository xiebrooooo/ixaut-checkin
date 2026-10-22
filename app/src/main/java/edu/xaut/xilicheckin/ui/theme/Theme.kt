package edu.xaut.xilicheckin.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 校徽蓝做主色，深浅两套
private val Blue = Color(0xFF1B5FB0)
private val BlueLight = Color(0xFF8AB4F8)
private val Amber = Color(0xFFE8710A)

private val LightScheme = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF001B3D),
    secondary = Color(0xFF00639B),
    tertiary = Amber,
    background = Color(0xFFFAFAFC),
    surface = Color.White,
    surfaceVariant = Color(0xFFEEF1F7),
    error = Color(0xFFBA1A1A),
)

private val DarkScheme = darkColorScheme(
    primary = BlueLight,
    onPrimary = Color(0xFF002F65),
    primaryContainer = Color(0xFF00458F),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFF7FCBFF),
    tertiary = Color(0xFFFFB77C),
    background = Color(0xFF111318),
    surface = Color(0xFF1A1C22),
    surfaceVariant = Color(0xFF23262E),
    error = Color(0xFFFFB4AB),
)

@Composable
fun XiliTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        content = content,
    )
}
