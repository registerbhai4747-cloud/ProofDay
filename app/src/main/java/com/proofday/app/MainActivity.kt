package com.proofday.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

const val APP_URL = "https://appassets.androidplatform.net/assets/index.html"
const val SYNC_JS = "(function(){function s(){try{Android.sync(localStorage.getItem('proofday_v1')||'{}')}catch(e){}}s();setInterval(s,5000)})();"
const val TEST_JS = "(function(){if(document.getElementById('pdt'))return;var w=document.createElement('div');w.id='pdt';w.style.cssText='position:fixed;top:6px;right:6px;z-index:99999;display:flex;gap:6px';function m(t,f){var b=document.createElement('button');b.textContent=t;b.style.cssText='padding:7px 9px;font-size:11px;border-radius:10px;border:1px solid #cfd4e2;background:#fff;color:#12141D';b.onclick=f;w.appendChild(b)}m('Test alarm',function(){Android.testAlarm()});m('Info',function(){Android.info()});document.body.appendChild(w)})();"

class Bridge(private val ctx: Context) {
    private fun toast(t: String) = Handler(Looper.getMainLooper()).post {
        Toast.makeText(ctx, t, Toast.LENGTH_LONG).show()
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

    @JavascriptInterface
    fun testAlarm() {
        val i = Intent(ctx, AlarmReceiver::class.java)
            .putExtra("id", "test").putExtra("name", "Test alarm").putExtra("time", "00:00")
        val pi = PendingIntent.getBroadcast(
            ctx, 777, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val show = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        ctx.getSystemService(AlarmManager::class.java)
            .setAlarmClock(AlarmManager.AlarmClockInfo(System.currentTimeMillis() + 20000, show), pi)
        toast("Test alarm set for 20 seconds. Lock your phone or press Home now.")
    }

    @JavascriptInterface
    fun info() {
        var total = 0
        var set = 0
        try {
            val s = ctx.getSharedPreferences("proofday", Context.MODE_PRIVATE).getString("tasks", null)
            if (s != null) {
                val a = JSONObject(s).optJSONArray("tasks")
                if (a != null) {
                    total = a.length()
                    for (k in 0 until a.length()) {
                        val id = a.getJSONObject(k).getString("id")
                        val pi = PendingIntent.getBroadcast(
                            ctx, id.hashCode(), Intent(ctx, AlarmReceiver::class.java),
                            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                        )
                        if (pi != null) set++
                    }
                }
            }
        } catch (e: Exception) {
        }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val notif = if (nm.areNotificationsEnabled()) "ON" else "OFF"
        toast("Tasks: $total | Alarms set: $set | Notifications: $notif")
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
                view.evaluateJavascript(SYNC_JS, null)
                view.evaluateJavascript(TEST_JS, null)
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
        getSystemService(NotificationManager::class.java).cancelAll()
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
