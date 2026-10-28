package com.delirium.deliriumboot

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.delirium.deliriumboot.ui.screens.HomeScreen
import com.delirium.deliriumboot.ui.screens.IsoManagerScreen
import com.delirium.deliriumboot.ui.screens.ThemeManagerScreen
import com.delirium.deliriumboot.ui.theme.DeliriumBackground
import com.delirium.deliriumboot.ui.theme.DeliriumBootTheme
import com.delirium.deliriumboot.viewmodel.DeliriumViewModel

private enum class Screen { HOME, THEME_MANAGER, ISO_MANAGER }

class MainActivity : ComponentActivity() {

    private val viewModel: DeliriumViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DeliriumBootTheme {
                Surface(modifier = Modifier.fillMaxSize().background(DeliriumBackground)) {
                    DeliriumApp(viewModel)
                }
            }
        }
    }
}

@Composable
private fun DeliriumApp(viewModel: DeliriumViewModel) {
    var screen by remember { mutableStateOf(Screen.HOME) }

    val usbState by viewModel.usbState.collectAsState()
    val options by viewModel.options.collectAsState()
    val theme by viewModel.theme.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val isoList by viewModel.isoList.collectAsState()

    val pickFilesLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) viewModel.addIsoFiles(uris)
    }

    when (screen) {
        Screen.HOME -> HomeScreen(
            usbState = usbState,
            options = options,
            progress = progress,
            logs = logs,
            onRequestUsbAccess = { viewModel.requestUsbAccess() },
            onOptionsChange = { newOptions -> viewModel.updateOptions { newOptions } },
            onInstall = { viewModel.install() },
            onUpdateBootloader = { viewModel.updateBootloader() },
            onOpenIsoManager = { screen = Screen.ISO_MANAGER },
            onOpenThemeManager = { screen = Screen.THEME_MANAGER }
        )
        Screen.THEME_MANAGER -> ThemeManagerScreen(
            theme = theme,
            onThemeChange = { newTheme -> viewModel.updateTheme { newTheme } },
            onApply = { viewModel.applyTheme() },
            onBack = { screen = Screen.HOME }
        )
        Screen.ISO_MANAGER -> IsoManagerScreen(
            isoList = isoList,
            progress = progress,
            onPickFiles = { pickFilesLauncher.launch(arrayOf("*/*")) },
            onBack = { screen = Screen.HOME }
        )
    }
}
