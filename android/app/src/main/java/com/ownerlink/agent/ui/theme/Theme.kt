package com.ownerlink.agent.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Scheme = darkColorScheme(
    primary = Color(0xFF4C8DFF),
    onPrimary = Color(0xFF071018),
    background = Color(0xFF101218),
    surface = Color(0xFF1C2230),
    onBackground = Color(0xFFE8EDF5),
    onSurface = Color(0xFFE8EDF5),
    error = Color(0xFFE25C6A),
)

@Composable
fun OwnerLinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
