package com.ownerlink.agent.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ThemeMode { SYSTEM, LIGHT, DARK }

object PrivatChatTokens {
    val SpaceXs = 4.dp
    val SpaceSm = 8.dp
    val SpaceMd = 16.dp
    val SpaceLg = 24.dp
    val RadiusSm = 10.dp
    val RadiusMd = 18.dp
    val RadiusLg = 28.dp
    val TouchTarget = 48.dp
    val Avatar = 48.dp
}

private val LightScheme = lightColorScheme(
    primary = Color(0xFF315EEB), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE4FF), onPrimaryContainer = Color(0xFF10236D),
    secondary = Color(0xFF53618A), background = Color(0xFFF9FAFE), surface = Color(0xFFF9FAFE),
    surfaceVariant = Color(0xFFE8EAF2), onSurface = Color(0xFF191B22),
    onSurfaceVariant = Color(0xFF454650), outline = Color(0xFF767781), error = Color(0xFFBA1A1A),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFB8C4FF), onPrimary = Color(0xFF002B9B),
    primaryContainer = Color(0xFF1745D2), onPrimaryContainer = Color(0xFFDDE4FF),
    secondary = Color(0xFFBCC6F0), background = Color(0xFF101116), surface = Color(0xFF101116),
    surfaceVariant = Color(0xFF292A32), onSurface = Color(0xFFE4E2E9),
    onSurfaceVariant = Color(0xFFC6C5D0), outline = Color(0xFF90909A), error = Color(0xFFFFB4AB),
)

private val PrivatTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 39.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 27.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
)

private val PrivatShapes = Shapes(
    small = RoundedCornerShape(PrivatChatTokens.RadiusSm),
    medium = RoundedCornerShape(PrivatChatTokens.RadiusMd),
    large = RoundedCornerShape(PrivatChatTokens.RadiusLg),
)

@Composable
fun PrivatChatTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        shapes = PrivatShapes,
        typography = PrivatTypography,
        content = content,
    )
}
