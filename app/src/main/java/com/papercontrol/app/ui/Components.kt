package com.papercontrol.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.papercontrol.app.ui.theme.Ink
import com.papercontrol.app.ui.theme.Metrics
import com.papercontrol.app.ui.theme.Type
import java.io.File
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

/** Press feedback used by every tappable surface: a small, quick shrink. */
@Composable
fun Modifier.pressable(onClick: () -> Unit, enabled: Boolean = true): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    return this
        .scale(if (pressed) 0.97f else 1f)
        .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
}

@Composable
fun CircleButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = Metrics.IconButton, background: Color = Ink.Surface, tint: Color = Ink.Text) {
    Box(
        modifier.size(size).clip(CircleShape).background(background).pressable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, background: Color = Ink.Surface, contentColor: Color = Ink.Text) {
    Row(
        modifier.height(Metrics.IconButton).clip(CircleShape).background(background).pressable(onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = contentColor, modifier = Modifier.size(18.dp))
        Text(text, style = Type.Body.copy(color = contentColor))
    }
}

/** The big white button. Disabled it is a dim grey slab; it brightens when it becomes usable. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val bg by animateColorAsState(if (enabled) Color.White else Ink.ButtonDisabled, tween(450), label = "bg")
    val fg by animateColorAsState(if (enabled) Color(0xFF080808) else Ink.ButtonDisabledText, tween(450), label = "fg")
    Box(
        modifier.fillMaxWidth().height(Metrics.ButtonHeight).clip(CircleShape).background(bg).pressable(onClick, enabled),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.Button.copy(color = fg))
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier.fillMaxWidth().height(Metrics.ButtonHeight).clip(CircleShape).background(Ink.Surface).pressable(onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = Ink.Text, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = Type.Button.copy(color = Ink.Text))
    }
}

@Composable
fun PaperCard(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(20.dp), content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(Metrics.CardRadius)).background(Ink.Surface).padding(padding), content = content)
}

@Composable
fun HairLine(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Ink.Divider))
}

@Composable
fun StatusPill(text: String, modifier: Modifier = Modifier, dot: Color = Ink.Text) {
    Row(
        modifier.clip(CircleShape).background(Ink.Surface.copy(alpha = 0.9f)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(10.dp))
        Text(text, style = Type.Body.copy(color = Ink.Text.copy(alpha = 0.85f)))
    }
}

@Composable
fun TopBar(left: @Composable () -> Unit = {}, title: String? = null, right: @Composable () -> Unit = {}, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(horizontal = Metrics.Gutter).height(56.dp)) {
        Box(Modifier.align(Alignment.CenterStart)) { left() }
        if (title != null) Text(title, style = Type.Title, modifier = Modifier.align(Alignment.Center))
        Box(Modifier.align(Alignment.CenterEnd)) { right() }
    }
}

@Composable
fun KeyValue(left: String, right: String, style: TextStyle = Type.Body, rightStyle: TextStyle = style, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(left, style = style, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(12.dp))
        Text(right, style = rightStyle, maxLines = 1)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = Type.Section, modifier = modifier.padding(start = 4.dp, top = 24.dp, bottom = 10.dp))
}

/** A row inside a card: optional leading content, title, subtitle, trailing content. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Ink.Tertiary) },
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().then(if (onClick != null) Modifier.pressable(onClick) else Modifier).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { leading(); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = Type.Caption, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) { Spacer(Modifier.width(8.dp)); trailing() }
    }
}

@Composable
fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null) {
    ListRow(
        title = title,
        subtitle = subtitle,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Ink.Black, checkedTrackColor = Ink.Text,
                    uncheckedThumbColor = Ink.Secondary, uncheckedTrackColor = Ink.SurfaceHigh, uncheckedBorderColor = Ink.SurfaceHigh,
                ),
            )
        },
        onClick = { onChange(!checked) },
    )
}

@Composable
fun LabelChip(text: String, color: Color, selected: Boolean = false, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Row(
        modifier
            .clip(CircleShape)
            .background(if (selected) Ink.Text else Ink.Surface)
            .then(if (onClick != null) Modifier.pressable(onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(7.dp))
        Text(text, style = Type.Caption.copy(color = if (selected) Ink.Black else Ink.Text), maxLines = 1)
    }
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().height(46.dp).clip(CircleShape).background(Ink.Surface).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, null, tint = Ink.Secondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = Type.Body.copy(color = Ink.Tertiary))
            BasicTextField(value, onChange, singleLine = true, textStyle = Type.Body, cursorBrush = SolidColor(Ink.Text), modifier = Modifier.fillMaxWidth())
        }
        trailing()
    }
}

/** A text input in the card style. */
@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, singleLine: Boolean = true, number: Boolean = false, placeholder: String = "") {
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = Type.Caption)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.SurfaceHigh).padding(horizontal = 14.dp, vertical = 12.dp)) {
            if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = Type.Body.copy(color = Ink.Tertiary))
            BasicTextField(
                value, onChange, singleLine = singleLine, textStyle = Type.Body, cursorBrush = SolidColor(Ink.Text),
                keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun Thumb(path: String?, modifier: Modifier = Modifier, radius: Dp = 10.dp) {
    Box(modifier.clip(RoundedCornerShape(radius)).background(Ink.SurfaceHigh).border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(radius))) {
        if (path != null) AsyncImage(model = File(path), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
    }
}

@Composable
fun IconCircle(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 40.dp, tint: Color = Ink.Text) {
    Box(modifier.size(size).clip(CircleShape).background(Ink.SurfaceHigh), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

object Fmt {
    fun money(amount: Double?, currency: String?): String {
        if (amount == null) return ""
        val f = NumberFormat.getCurrencyInstance(if (currency == "USD") Locale.US else Locale.getDefault())
        runCatching { if (currency != null) f.currency = Currency.getInstance(currency) }
        return f.format(amount)
    }

    fun date(d: LocalDate?): String = d?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) ?: ""

    fun time(t: java.time.LocalTime?): String = t?.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)) ?: ""
}
