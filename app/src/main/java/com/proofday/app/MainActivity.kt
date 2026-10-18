package com.proofday.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

const val APP_URL = "https://appassets.androidplatform.net/assets/index.html"
const val SYNC_JS = "(function(){function s(){try{Android.sync(localStorage.getItem('proofday_v1')||'{}')}catch(e){}}s();setInterval(s,5000)})();"
const val PATCH_JS = "(function(){if(window.__pp)return;window.__pp=1;try{var oa=alarm,os=stop;oa(false);alarm=function(on){if(!on)oa(false)};stop=function(){try{os()}catch(e){}try{Android.stopAlarm()}catch(e){}}}catch(e){}})();"

class Bridge(private val ctx: Context) {
    @JavascriptInterface
    fun stopAlarm() {
        ctx.getSystemService(NotificationManager::class.java).cancelAll()
    }

    @JavascriptInterface
    fun sync(json: String) {
        val prefs = ctx.getSharedPreferences("proofday", Context.MODE_PRIVATE)
        val old = prefs.getString("tasks", null)
        if (old == json) return
        AlarmReceiver.cancelAll(ctx)
        prefs.edit().putString("tasks", json).apply()
        AlarmReceiver.scheduleAll(ctx)
    }
}

class MainActivity : Activity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.addJavascriptInterface(Bridge(this), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                return loader.shouldInterceptRequest(request.url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript(PATCH_JS, null)
                view.evaluateJavascript(SYNC_JS, null)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }
        }
        web.loadUrl(APP_URL)
        askPermissions()
    }

    override fun onResume() {
        super.onResume()
        web.evaluateJavascript("try{tick()}catch(e){}", null)
    }

    private fun askPermissions() {
        val need = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1) else checkFullScreen()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        checkFullScreen()
    }

    private fun checkFullScreen() {
        if (Build.VERSION.SDK_INT >= 34) {
            val nm = getSystemService(NotificationManager::class.java)
            if (!nm.canUseFullScreenIntent()) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            }
        }
    }
}
