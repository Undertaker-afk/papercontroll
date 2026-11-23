package com.papercontrol.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.papercontrol.app.R

/** Colors measured from the reference animation. */
object Ink {
    val Black = Color(0xFF000000)
    val Camera = Color(0xFF0B0B0B)
    val Surface = Color(0xFF191919)
    val SurfaceHigh = Color(0xFF2B2B2B)
    val Divider = Color(0xFF2A2A2A)
    val Text = Color(0xFFFFFFFF)
    val Secondary = Color(0xFF8E8E93)
    val Tertiary = Color(0xFF5C5C61)
    val ButtonDisabled = Color(0xFF4C4C4C)
    val ButtonDisabledText = Color(0xFF2E2E2E)
    val Danger = Color(0xFFFF5C5C)

    /** The iridescent scan glow, in hue order. */
    val Iris = listOf(
        Color(0xFFFF4FD1), Color(0xFF9A5CFF), Color(0xFF4F7CFF), Color(0xFF4FE3FF),
        Color(0xFF5CFFB0), Color(0xFFFFE45C), Color(0xFFFF9A4F), Color(0xFFFF4FD1),
    )
}

object Metrics {
    val Gutter = 20.dp
    val CardRadius = 24.dp
    val ButtonHeight = 54.dp
    val IconButton = 40.dp
}

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

object Type {
    val LargeTitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, letterSpacing = (-0.6).sp, color = Ink.Text)
    val Title = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, letterSpacing = (-0.2).sp, color = Ink.Text)
    val Headline = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, letterSpacing = (-0.2).sp, color = Ink.Text)
    val Body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 15.sp, letterSpacing = (-0.1).sp, color = Ink.Text)
    val BodySecondary = Body.copy(color = Ink.Secondary)
    val Caption = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 13.sp, color = Ink.Secondary)
    val Button = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, letterSpacing = (-0.2).sp)
    val Amount = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, letterSpacing = (-0.8).sp, color = Ink.Text)
    val Section = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 0.2.sp, color = Ink.Secondary)
}

@Composable
fun PaperTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Ink.Text,
            onPrimary = Ink.Black,
            secondary = Ink.Secondary,
            background = Ink.Black,
            onBackground = Ink.Text,
            surface = Ink.Surface,
            onSurface = Ink.Text,
            surfaceVariant = Ink.SurfaceHigh,
            onSurfaceVariant = Ink.Secondary,
            surfaceContainer = Ink.Surface,
            surfaceContainerHigh = Ink.Surface,
            surfaceContainerHighest = Ink.SurfaceHigh,
            surfaceContainerLow = Ink.Surface,
            outline = Ink.Divider,
            outlineVariant = Ink.Divider,
            error = Ink.Danger,
        ),
        typography = Typography(
            bodyLarge = Type.Body,
            bodyMedium = Type.Body,
            bodySmall = Type.Caption,
            titleLarge = Type.Title,
            titleMedium = Type.Headline,
            labelLarge = Type.Button,
            labelMedium = Type.Caption,
        ),
        content = content,
    )
}
