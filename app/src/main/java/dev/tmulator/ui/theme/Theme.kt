package dev.tmulator.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

val PrinterBlue = Color(0xFF68ADFF)
val Ink = Color(0xFF151719)
val Paper = Color(0xFFFDF9F0)
val Moss = Color(0xFF8BAEA0)

private val PrinterColors = darkColorScheme(
    primary = PrinterBlue,
    onPrimary = Color(0xFF071B30),
    primaryContainer = Color(0xFF20344B),
    onPrimaryContainer = Color(0xFFCAE2FF),
    secondary = Color(0xFFB3BCC5),
    onSecondary = Color(0xFF15191D),
    secondaryContainer = Color(0xFF2B3036),
    onSecondaryContainer = Color(0xFFE1E6EB),
    tertiary = Moss,
    onTertiary = Color(0xFF14251E),
    background = Color(0xFF101214),
    onBackground = Color(0xFFEDF0F2),
    surface = Color(0xFF191C1F),
    onSurface = Color(0xFFEDF0F2),
    surfaceVariant = Color(0xFF252A2F),
    onSurfaceVariant = Color(0xFFA5AEB7),
    outline = Color(0xFF65707A),
    outlineVariant = Color(0xFF343A40),
    error = Color(0xFFFF9C97),
    onError = Color(0xFF3F0809),
    errorContainer = Color(0xFF502629),
    onErrorContainer = Color(0xFFFFDAD7),
    inverseSurface = Color(0xFFDEE3E7),
    inverseOnSurface = Color(0xFF1C2228),
    inversePrimary = Color(0xFF205E9F),
)

private val AppTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Medium),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Medium),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
        bodyLarge = base.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp),
        bodySmall = base.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
        labelSmall = base.labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.sp),
    )
}

@Composable
fun TMulatorTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = PrinterColors.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
    MaterialTheme(
        colorScheme = PrinterColors,
        typography = AppTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp),
            extraLarge = RoundedCornerShape(24.dp),
        ),
        content = content,
    )
}

object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
}
