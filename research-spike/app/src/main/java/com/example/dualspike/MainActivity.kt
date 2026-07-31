package com.example.dualspike

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Research-spike: два WebView с YouTube IFrame API на одном экране.
 * Никакого AudioManager.requestAudioFocus() здесь намеренно нет —
 * это вариант №1 из ТЗ: проверить, звучат ли оба WebView одновременно
 * без какого-либо вмешательства в audio focus со стороны Activity.
 */
class MainActivity : AppCompatActivity() {

    private val tag = "DualSpike"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val statusText = findViewById<TextView>(R.id.statusText)
        statusText.text = "status: loading two WebViews, no audio focus requested by app"

        val webTop = findViewById<WebView>(R.id.webViewTop)
        val webBottom = findViewById<WebView>(R.id.webViewBottom)

        setupWebView(webTop, "TOP")
        setupWebView(webBottom, "BOTTOM")

        webTop.loadUrl("file:///android_asset/player_top.html")
        webBottom.loadUrl("file:///android_asset/player_bottom.html")
    }

    private fun setupWebView(webView: WebView, label: String) {
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                Log.d(tag, "[$label][console] ${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})")
                return true
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        findViewById<WebView>(R.id.webViewTop).destroy()
        findViewById<WebView>(R.id.webViewBottom).destroy()
    }
}
