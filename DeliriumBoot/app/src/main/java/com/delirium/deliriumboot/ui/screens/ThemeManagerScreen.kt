package com.delirium.deliriumboot.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.delirium.deliriumboot.model.ThemeConfig
import com.delirium.deliriumboot.ui.components.PrimaryActionButton
import com.delirium.deliriumboot.ui.components.SecondaryActionButton
import com.delirium.deliriumboot.ui.components.SectionCard

private val PRESET_COLORS = listOf("#B621FE", "#00E5C7", "#FF4D6D", "#FFC14D", "#4D7CFF")

@Composable
fun ThemeManagerScreen(
    theme: ThemeConfig,
    onThemeChange: (ThemeConfig) -> Unit,
    onApply: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Менеджер тем DeliriumBoot", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Настройте, как будет выглядеть загрузочное меню Ventoy после установки. " +
                "Изменения применяются к файлу /ventoy/ventoy.json на накопителе.",
            color = Color(0xFFB9A8CE)
        )

        SectionCard("Название меню") {
            OutlinedTextField(
                value = theme.menuTitle,
                onValueChange = { onThemeChange(theme.copy(menuTitle = it)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        SectionCard("Акцентный цвет") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PRESET_COLORS.forEach { hex ->
                    val color = Color(android.graphics.Color.parseColor(hex))
                    val isSelected = theme.accentColorHex == hex
                    Box(
                        modifier = Modifier
                            .size(if (isSelected) 42.dp else 36.dp)
                            .clip(CircleShape)
                            .background(color)
                            .clickable { onThemeChange(theme.copy(accentColorHex = hex)) }
                    )
                }
            }
            Text("Текущий: ${theme.accentColorHex}", color = Color(0xFFB9A8CE))
        }

        SectionCard("Таймаут меню (секунды)") {
            Slider(
                value = theme.timeoutSeconds.toFloat(),
                onValueChange = { onThemeChange(theme.copy(timeoutSeconds = it.toInt())) },
                valueRange = 0f..60f
            )
            Text("${theme.timeoutSeconds} с")
        }

        SectionCard("Режим отображения") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("GUI", "CLI", "SERIAL").forEach { mode ->
                    SecondaryActionButton(mode, enabled = theme.displayMode != mode) {
                        onThemeChange(theme.copy(displayMode = mode))
                    }
                }
            }
        }

        PrimaryActionButton("ПРИМЕНИТЬ ТЕМУ НА НАКОПИТЕЛЬ") { onApply() }
        SecondaryActionButton("НАЗАД") { onBack() }
    }
}
