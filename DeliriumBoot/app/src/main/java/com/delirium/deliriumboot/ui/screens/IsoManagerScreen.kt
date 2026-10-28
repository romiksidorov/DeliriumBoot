package com.delirium.deliriumboot.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.delirium.deliriumboot.model.IsoEntry
import com.delirium.deliriumboot.model.TaskProgress
import com.delirium.deliriumboot.ui.components.PrimaryActionButton
import com.delirium.deliriumboot.ui.components.ProgressSection
import com.delirium.deliriumboot.ui.components.SecondaryActionButton
import com.delirium.deliriumboot.ui.components.SectionCard

@Composable
fun IsoManagerScreen(
    isoList: List<IsoEntry>,
    progress: TaskProgress,
    onPickFiles: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Менеджер ISO-образов", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Выберите один или несколько ISO/IMG-файлов из памяти телефона — они будут " +
                "записаны напрямую на основной раздел накопителя.",
            color = Color(0xFFB9A8CE)
        )

        PrimaryActionButton("ВЫБРАТЬ ФАЙЛЫ ISO/IMG", enabled = !progress.running) { onPickFiles() }

        if (progress.running) {
            SectionCard("Копирование") {
                ProgressSection(progress.title, progress.progress, progress.detail)
            }
        }

        SectionCard("Записанные образы (${isoList.size})") {
            if (isoList.isEmpty()) {
                Text("Пока ничего не записано.", color = Color(0xFFB9A8CE))
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(isoList) { entry ->
                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                            Text(entry.name, style = MaterialTheme.typography.bodyLarge)
                            Text("${entry.sizeBytes / (1024 * 1024)} МиБ", color = Color(0xFFB9A8CE))
                        }
                    }
                }
            }
        }

        SecondaryActionButton("НАЗАД") { onBack() }
    }
}
