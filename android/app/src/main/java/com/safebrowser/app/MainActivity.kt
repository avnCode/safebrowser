package com.safebrowser.app

import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class MainActivity : ComponentActivity() {

    private val vm: BrowserViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install crash logger BEFORE anything else, so any crash is captured
        // and visible on next launch.
        installCrashLogger()

        super.onCreate(savedInstanceState)

        // Surface any crash from a previous run.
        readAndClearLastCrash()?.let { trace ->
            Toast.makeText(this, "Previous crash logged \u2014 see Logcat / file", Toast.LENGTH_LONG).show()
            android.util.Log.e("SafeBrowser", "Previous crash:\n$trace")
        }

        // Edge-to-edge can throw on some OEM skins / API 36 builds. Don't crash.
        runCatching { enableEdgeToEdge() }
            .onFailure { android.util.Log.w("SafeBrowser", "enableEdgeToEdge failed", it) }

        runCatching { WebView.setWebContentsDebuggingEnabled(false) }

        runCatching { handleViewIntent(intent) }

        try {
            setContent {
                val settings by vm.settings.collectAsState()
                SafeBrowserTheme(themeChoice = settings.theme) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        BrowserScreen(vm)
                    }
                }
            }
        } catch (t: Throwable) {
            // Last-resort fallback so the user sees something instead of an OS crash dialog.
            android.util.Log.e("SafeBrowser", "setContent failed", t)
            saveCrashTrace(t)
            setContent {
                MaterialTheme {
                    Surface(Modifier.fillMaxSize()) {
                        Text("SafeBrowser failed to start. Crash log saved.\n\n${t.javaClass.simpleName}: ${t.message}")
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        runCatching { handleViewIntent(intent) }
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.dataString?.takeIf { it.isNotBlank() }?.let { url ->
                vm.newTab(url, activate = true)
            }
        }
    }

    // ----- Crash logging --------------------------------------------------

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            saveCrashTrace(throwable, thread.name)
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun saveCrashTrace(t: Throwable, threadName: String = Thread.currentThread().name) {
        try {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            pw.println("---- SafeBrowser crash ----")
            pw.println("time: " + java.util.Date())
            pw.println("thread: $threadName")
            pw.println("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            pw.println("android: ${android.os.Build.VERSION.RELEASE} (api ${android.os.Build.VERSION.SDK_INT})")
            pw.println()
            t.printStackTrace(pw)
            pw.flush()
            File(filesDir, "last_crash.txt").writeText(sw.toString())
        } catch (_: Throwable) { /* don't loop */ }
    }

    private fun readAndClearLastCrash(): String? {
        val f = File(filesDir, "last_crash.txt")
        if (!f.exists()) return null
        return runCatching {
            val txt = f.readText()
            f.delete()
            txt
        }.getOrNull()
    }
}

@Composable
fun SafeBrowserTheme(themeChoice: ThemeChoice, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeChoice) {
        ThemeChoice.System -> systemDark
        ThemeChoice.Dark   -> true
        ThemeChoice.Light  -> false
    }
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}
