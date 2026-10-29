package com.tiltbox.launcher

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognizerIntent
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val TAG = "TiltboxLauncher"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        hideSystemUI()

        webView = WebView(this).apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            setBackgroundColor(0xFF07090A.toInt())
            isFocusable = true
            isFocusableInTouchMode = true

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                loadWithOverviewMode = true
                useWideViewPort = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                mediaPlaybackRequiresUserGesture = false
            }
            clearCache(true)

            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    return false
                }
            }

            addJavascriptInterface(TiltboxBridge(this@MainActivity, this), "TiltboxBridge")
            loadUrl("file:///android_asset/index.html")
        }

        setContentView(webView)
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                webView.evaluateJavascript("window.handleDpad('right');", null)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                webView.evaluateJavascript("window.handleDpad('left');", null)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                webView.evaluateJavascript("window.handleDpad('down');", null)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                webView.evaluateJavascript("window.handleDpad('up');", null)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                webView.evaluateJavascript("window.handleDpad('enter');", null)
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                webView.evaluateJavascript("window.handleDpad('back');", null)
                return true
            }
            KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_MENU -> {
                webView.evaluateJavascript("if(window.TiltboxBridge) window.TiltboxBridge.boostRam();", null)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    class TiltboxBridge(private val context: Context, private val webView: WebView) {

        private val TAG = "TiltboxBridge"

        @JavascriptInterface
        fun launchApp(packageName: String): Boolean {
            return try {
                val pm = context.packageManager
                val intent = pm.getLeanbackLaunchIntentForPackage(packageName)
                    ?: pm.getLaunchIntentForPackage(packageName)

                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    true
                } else {
                    Log.w(TAG, "Tidak dapat menemukan launch intent untuk: $packageName")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gagal meluncurkan aplikasi $packageName", e)
                false
            }
        }

        @JavascriptInterface
        fun launchLiveTv(): Boolean {
            return try {
                val tclIntent = context.packageManager.getLaunchIntentForPackage("com.tcl.tv")
                if (tclIntent != null) {
                    tclIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(tclIntent)
                    return true
                }
                val tvIntent = Intent(Intent.ACTION_VIEW).apply {
                    setClassName("com.tcl.tv", "com.tcl.tv.TVActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(tvIntent)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Gagal meluncurkan TVActivity TCL", e)
                false
            }
        }

        @JavascriptInterface
        fun launchMedia(): Boolean {
            val pm = context.packageManager
            val mediaPkgs = arrayOf("com.lonelycatgames.Xplore", "com.tcl.ui_mediaCenter", "com.tcl.videoplayer")
            for (pkg in mediaPkgs) {
                val intent = pm.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return true
                }
            }
            return false
        }

        @JavascriptInterface
        fun launchHdmi(port: String): Boolean {
            return try {
                val pm = context.packageManager
                val tclSources = arrayOf("com.tcl.tvinput", "com.tcl.sourcemananger", "com.tcl.tv")
                for (pkg in tclSources) {
                    val intent = pm.getLaunchIntentForPackage(pkg)
                    if (intent != null) {
                        intent.putExtra("input_source", port)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        return true
                    }
                }
                val intent = Intent("android.media.tv.action.VIEW_INPUTS").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Gagal beralih port HDMI $port", e)
                false
            }
        }

        @JavascriptInterface
        fun launchSettings(): Boolean {
            return try {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Gagal membuka setelan TV", e)
                false
            }
        }

        @JavascriptInterface
        fun launchVoiceSearch(): Boolean {
            return try {
                val intent = Intent(RecognizerIntent.ACTION_WEB_SEARCH).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            } catch (e: Exception) {
                try {
                    val intent2 = Intent(Intent.ACTION_VOICE_COMMAND).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent2)
                    true
                } catch (e2: Exception) {
                    val katniss = context.packageManager.getLaunchIntentForPackage("com.google.android.katniss")
                    if (katniss != null) {
                        katniss.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(katniss)
                        true
                    } else false
                }
            }
        }

        @JavascriptInterface
        fun launchSearch(query: String): Boolean {
            return try {
                val intent = Intent(Intent.ACTION_SEARCH).apply {
                    putExtra(SearchManager.QUERY, query)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            } catch (e: Exception) {
                launchVoiceSearch()
            }
        }

        @JavascriptInterface
        fun launchWifiSettings(): Boolean {
            return try {
                val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            } catch (e: Exception) {
                launchSettings()
            }
        }

        @JavascriptInterface
        fun launchAppsSettings(): Boolean {
            return try {
                val intent = Intent(Settings.ACTION_APPLICATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            } catch (e: Exception) {
                launchSettings()
            }
        }

        @JavascriptInterface
        fun launchTclPictureAudioSettings(): Boolean {
            return try {
                val intent = context.packageManager.getLaunchIntentForPackage("com.tcl.settings")
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    true
                } else {
                    launchSettings()
                }
            } catch (e: Exception) {
                launchSettings()
            }
        }

        @JavascriptInterface
        fun boostRam(): String {
            return try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val pm = context.packageManager
                val myPkg = context.packageName

                if (am != null) {
                    for (app in pm.getInstalledApplications(0)) {
                        if (app.packageName != myPkg && !app.packageName.startsWith("com.android.systemui")) {
                            am.killBackgroundProcesses(app.packageName)
                        }
                    }
                }
                System.gc()

                val mi = ActivityManager.MemoryInfo()
                am?.getMemoryInfo(mi)
                val availMb = mi.availMem / (1024 * 1024)
                val totalMb = mi.totalMem / (1024 * 1024)
                val msg = "RAM Booster: Tersedia ${availMb} MB / ${totalMb} MB"

                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
                msg
            } catch (e: Exception) {
                Log.e(TAG, "Gagal membersihkan RAM", e)
                "Gagal membersihkan RAM"
            }
        }

        @JavascriptInterface
        fun getAvailableRam(): String {
            return try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val mi = ActivityManager.MemoryInfo()
                am?.getMemoryInfo(mi)
                val availMb = mi.availMem / (1024 * 1024)
                "${availMb} MB Bebas"
            } catch (e: Exception) {
                "450 MB Bebas"
            }
        }

        @JavascriptInterface
        fun getRamDetails(): String {
            return try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val mi = ActivityManager.MemoryInfo()
                am?.getMemoryInfo(mi)
                val availMb = mi.availMem / (1024 * 1024)
                val totalMb = mi.totalMem / (1024 * 1024)
                val pct = if (totalMb > 0) ((totalMb - availMb).toFloat() / totalMb * 100).toInt() else 60
                val json = JSONObject().apply {
                    put("availableMb", availMb)
                    put("totalMb", totalMb)
                    put("usedPercent", pct)
                    put("display", "${availMb} MB Bebas")
                }
                json.toString()
            } catch (e: Exception) {
                "{\"availableMb\":450,\"totalMb\":1500,\"usedPercent\":65,\"display\":\"450 MB Bebas\"}"
            }
        }

        @JavascriptInterface
        fun getInstalledApps(): String {
            val jsonArray = JSONArray()
            val pm = context.packageManager

            try {
                val tvIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                val appList = pm.queryIntentActivities(tvIntent, 0)

                for (info in appList) {
                    val pkgName = info.activityInfo.packageName
                    if (pkgName == context.packageName) continue

                    val appObj = JSONObject().apply {
                        put("packageName", pkgName)
                        put("name", info.loadLabel(pm).toString())
                    }
                    jsonArray.put(appObj)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gagal membaca aplikasi terinstal", e)
            }

            return jsonArray.toString()
        }
    }
}
