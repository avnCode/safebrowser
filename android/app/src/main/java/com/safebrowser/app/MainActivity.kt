package com.safebrowser.app

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Minimal SafeBrowser v1.0.2 — a bare-bones WebView activity, no Compose,
 * no DataStore, no ad-blocker. This exists to isolate the Android 16 /
 * One UI 8 crash. If THIS runs, we add features back one at a time.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        installCrashLogger()
        super.onCreate(savedInstanceState)

        // Show any crash from a previous run as a dialog (no log readers needed).
        readAndClearLastCrash()?.let { trace -> showCrashDialog(trace) }

        try {
            webView = WebView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    builtInZoomControls = true
                    displayZoomControls = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                }
                webViewClient = WebViewClient()
                loadUrl("https://www.google.com")
            }
            setContentView(webView)
        } catch (t: Throwable) {
            saveCrashTrace(t)
            Toast.makeText(this, "WebView init failed: ${t.message}", Toast.LENGTH_LONG).show()
            android.util.Log.e("SafeBrowser", "WebView init failed", t)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && this::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ----- crash logging ----------------------------------------------------

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

    /** Show the previous crash trace in a dialog so the user can screenshot it. */
    private fun showCrashDialog(trace: String) {
        AlertDialog.Builder(this)
            .setTitle("Previous crash (tap Copy and paste it back)")
            .setMessage(trace)
            .setPositiveButton("Copy") { _, _ ->
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("crash", trace))
                Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Dismiss", null)
            .show()
    }
}
