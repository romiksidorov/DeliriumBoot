package com.delirium.deliriumboot.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.delirium.deliriumboot.model.LogEntry
import com.delirium.deliriumboot.model.LogLevel
import com.delirium.deliriumboot.ui.theme.DeliriumError
import com.delirium.deliriumboot.ui.theme.DeliriumPrimary
import com.delirium.deliriumboot.ui.theme.DeliriumSecondary
import com.delirium.deliriumboot.ui.theme.DeliriumSurface
import com.delirium.deliriumboot.ui.theme.DeliriumTextMuted
import com.delirium.deliriumboot.ui.theme.DeliriumWarning

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DeliriumSurface, RoundedCornerShape(18.dp))
            .border(1.dp, DeliriumPrimary.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = DeliriumSecondary)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
fun UsbStatusCard(
    connected: Boolean,
    hasPermission: Boolean,
    deviceName: String?,
    capacityMb: Long,
    onRequestAccess: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DeliriumSurface, RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val color = if (connected && hasPermission) DeliriumSecondary else DeliriumError
            Icon(Icons.Filled.Circle, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
            Spacer(Modifier.size(8.dp))
            Text(
                text = when {
                    connected && hasPermission -> "USB: подключено — ${deviceName ?: "накопитель"}"
                    connected && !hasPermission -> "USB: обнаружено, нет доступа"
                    else -> "USB: не подключено"
                },
                style = MaterialTheme.typography.titleMedium
            )
        }
        if (connected && hasPermission && capacityMb > 0) {
            Text("Объём: $capacityMb МиБ", color = DeliriumTextMuted, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onRequestAccess,
            colors = ButtonDefaults.buttonColors(containerColor = DeliriumPrimary),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("ЗАПРОСИТЬ ДОСТУП К USB")
        }
    }
}

@Composable
fun SegmentedOption(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x22FFFFFF), RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(2.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) DeliriumPrimary else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (selected) Color.Black else DeliriumTextMuted,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
fun OptionCheckbox(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(checkedColor = DeliriumPrimary)
        )
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun PrimaryActionButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = DeliriumPrimary, disabledContainerColor = DeliriumSurface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecondaryActionButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun ProgressSection(title: String, progress: Float, detail: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (detail.isNotBlank()) Text(detail, color = DeliriumTextMuted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = DeliriumSecondary,
            trackColor = Color(0x33FFFFFF)
        )
    }
}

@Composable
fun LogConsole(entries: List<LogEntry>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF05020A), RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        if (entries.isEmpty()) {
            Text("Журнал пуст. Выполните любое действие, чтобы увидеть лог.", color = DeliriumTextMuted)
        }
        entries.takeLast(60).forEach { entry ->
            val color = when (entry.level) {
                LogLevel.INFO -> DeliriumTextMuted
                LogLevel.WARN -> DeliriumWarning
                LogLevel.ERROR -> DeliriumError
                LogLevel.SUCCESS -> DeliriumSecondary
            }
            Text("· ${entry.message}", color = color, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
