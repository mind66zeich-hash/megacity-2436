package com.megacity.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    fmt: (Float) -> String = { "%.2f".format(it) },
    hint: String? = null,
    onChange: (Float) -> Unit
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(fmt(value), fontWeight = FontWeight.Bold)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun IntSliderRow(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    step: Int = 1,
    suffix: String = "",
    hint: String? = null,
    onChange: (Int) -> Unit
) {
    val steps = ((max - min) / step - 1).coerceAtLeast(0)
    SliderRow(
        label = label,
        value = value.toFloat(),
        range = min.toFloat()..max.toFloat(),
        steps = steps,
        fmt = { "${it.roundToInt()}$suffix" },
        hint = hint
    ) { v ->
        val snapped = (((v - min) / step).roundToInt() * step + min).coerceIn(min, max)
        onChange(snapped)
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, hint: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (hint != null) {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Pulsante a pillola per scegliere un personaggio. Spento se il personaggio non è nel luogo attuale. */
@Composable
fun CharChip(label: String, color: Color, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val bg = if (selected) color else color.copy(alpha = 0.18f)
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .background(bg, RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Text(
            label,
            color = if (selected) Color.Black else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

fun formatDuration(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%dh %02dm".format(h, m) else "%dm %02ds".format(m, s)
}
