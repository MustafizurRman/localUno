package com.mutsho.localuno

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mutsho.localuno.ui.navigation.AppNavigation
import com.mutsho.localuno.ui.theme.LocalUnoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Before setContent, so a crash while the UI is being built is still captured.
        CrashReporter.install(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LocalUnoTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavigation()
                    CrashReportPrompt()
                }
            }
        }
    }
}

/**
 * Offers the previous run's crash trace, once, on the next launch.
 *
 * The prompt is the whole point of recording it: a file in private storage that nobody is told
 * about is the same as no file. Sharing goes through a plain text share sheet rather than an
 * upload, so the trace travels only where the player sends it.
 */
@androidx.compose.runtime.Composable
private fun CrashReportPrompt() {
    val context = LocalContext.current
    var report by remember { mutableStateOf(CrashReporter.pendingReport(context)) }

    report?.let { text ->
        AlertDialog(
            onDismissRequest = { CrashReporter.clear(context); report = null },
            title = { Text("The game crashed last time") },
            text = {
                Text(
                    text = text,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Local UNO crash report")
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(Intent.createChooser(send, "Share crash report"))
                    CrashReporter.clear(context)
                    report = null
                }) { Text("Share") }
            },
            dismissButton = {
                TextButton(onClick = { CrashReporter.clear(context); report = null }) {
                    Text("Dismiss")
                }
            }
        )
    }
}
