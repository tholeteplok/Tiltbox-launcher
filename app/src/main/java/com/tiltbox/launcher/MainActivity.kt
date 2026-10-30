package com.tiltbox.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.SearchManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognizerIntent
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import android.annotation.TargetApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var bridge: TiltboxBridge
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "TiltboxMain"
        private const val WALLPAPER_PICK_REQUEST = 1002
        private const val WALLPAPER_MAX_W = 1280
        private const val WALLPAPER_MAX_H = 720
        const val WALLPAPER_FILE = "wallpaper.jpg"
        private const val ASSET_URL = "file:///android_asset/index.html"
        private const val ASSET_PREFIX = "file:///android_asset/"
    }

    // Daftar aplikasi dimuat ulang dengan jeda (debounce): satu update aplikasi memicu
    // beberapa broadcast berurutan (REMOVED -> ADDED -> REPLACED).
    private val refreshAppsRunnable = Runnable {
        bridge.invalidateAppCache()
        webView.evaluateJavascript(
            "if(typeof refreshInstalledApps==='function') refreshInstalledApps();", null
        )
    }

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            mainHandler.removeCallbacks(refreshAppsRunnable)
            mainHandler.postDelayed(refreshAppsRunnable, 800)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        hideSystemUI()

        // Debugging WebView lewat chrome://inspect hanya untuk build debug.
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        webView = WebView(this).apply {
            setBackgroundColor(0xFF07090A.toInt())
            isFocusable = true
            isFocusableInTouchMode = true

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                // Halaman dimuat dari file:///android_asset (tidak terpengaruh setting ini).
                // Menutupnya mencegah halaman membaca file lain / content:// lewat WebView.
                allowFileAccess = false
                allowContentAccess = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                loadWithOverviewMode = true
                useWideViewPort = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mediaPlaybackRequiresUserGesture = false
            }

            webViewClient = object : WebViewClient() {
                // Bridge hanya boleh terpapar ke halaman lokal. Blokir semua navigasi lain.
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return true
                    return !url.startsWith(ASSET_PREFIX)
                }

                // Proses renderer bisa dimatikan sistem saat RAM TV habis; pulihkan, jangan crash.
                @TargetApi(Build.VERSION_CODES.O)
                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    Log.e(TAG, "Renderer WebView berhenti (crash=${detail?.didCrash()}), memuat ulang")
                    mainHandler.post { recreate() }
                    return true
                }
            }
        }

        bridge = TiltboxBridge(this)
        webView.addJavascriptInterface(bridge, "TiltboxBridge")
        webView.loadUrl(ASSET_URL)

        setContentView(webView)
        registerPackageChangeReceiver()
    }

    // ============ Wallpaper lokal (native, tanpa <input type=file>) ============

    /** Dipanggil dari bridge (thread JavaBridge), jadi pindah ke UI thread dulu. */
    fun startWallpaperPicker() {
        runOnUiThread {
            var started = false
            for (action in arrayOf(Intent.ACTION_GET_CONTENT, Intent.ACTION_OPEN_DOCUMENT)) {
                try {
                    val picker = Intent(action).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "image/*"
                    }
                    @Suppress("DEPRECATION")
                    startActivityForResult(picker, WALLPAPER_PICK_REQUEST)
                    started = true
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Pemilih gambar $action tidak tersedia", e)
                }
            }
            if (!started) notifyWallpaperResult(false, "nopicker")
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != WALLPAPER_PICK_REQUEST) return

        val uri = if (resultCode == RESULT_OK) data?.data else null
        if (uri == null) {
            notifyWallpaperResult(false, "cancel")
            return
        }
        // Decode gambar di thread terpisah agar UI tidak macet.
        Thread {
            val ok = saveWallpaper(uri)
            runOnUiThread { notifyWallpaperResult(ok, if (ok) "" else "error") }
        }.start()
    }

    private fun notifyWallpaperResult(ok: Boolean, reason: String) {
        webView.evaluateJavascript(
            "if(typeof onWallpaperPicked==='function') onWallpaperPicked($ok,'$reason');", null
        )
    }

    /** Decode hemat memori (inSampleSize), putar sesuai EXIF, skala maks 1280x720, simpan JPEG. */
    private fun saveWallpaper(uri: Uri): Boolean {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

            var sample = 1
            while (bounds.outWidth / (sample * 2) >= WALLPAPER_MAX_W &&
                bounds.outHeight / (sample * 2) >= WALLPAPER_MAX_H
            ) sample *= 2

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp: Bitmap = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return false

            val rotation = readExifRotation(uri)
            if (rotation != 0) {
                val m = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (rotated !== bmp) { bmp.recycle(); bmp = rotated }
            }

            val ratio = Math.min(WALLPAPER_MAX_W.toFloat() / bmp.width, WALLPAPER_MAX_H.toFloat() / bmp.height)
            if (ratio < 1f) {
                val scaled = Bitmap.createScaledBitmap(
                    bmp, Math.round(bmp.width * ratio), Math.round(bmp.height * ratio), true
                )
                if (scaled !== bmp) { bmp.recycle(); bmp = scaled }
            }

            val tmp = File(filesDir, "wallpaper.tmp")
            FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            bmp.recycle()

            val dest = File(filesDir, WALLPAPER_FILE)
            if (dest.exists()) dest.delete()
            return tmp.renameTo(dest)
        } catch (t: Throwable) { // termasuk OutOfMemoryError
            Log.e(TAG, "Gagal menyimpan wallpaper", t)
            return false
        }
    }

    private fun readExifRotation(uri: Uri): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return 0
        return try {
            contentResolver.openInputStream(uri)?.use { stream ->
                when (ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (e: Exception) {
            0
        }
    }

    // ============ Lifecycle ============

    private fun registerPackageChangeReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        registerReceiver(packageChangeReceiver, filter)
    }

    // Tombol HOME saat launcher sudah aktif (singleTask): kembali ke posisi awal.
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        webView.evaluateJavascript("if(typeof goHome==='function') goHome();", null)
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        webView.onResume()
        webView.resumeTimers()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        webView.pauseTimers()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(refreshAppsRunnable)
        try {
            unregisterReceiver(packageChangeReceiver)
        } catch (ignored: Exception) {
        }
        webView.removeJavascriptInterface("TiltboxBridge")
        webView.destroy()
        super.onDestroy()
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
        val dir = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
            KeyEvent.KEYCODE_DPAD_LEFT -> "left"
            KeyEvent.KEYCODE_DPAD_DOWN -> "down"
            KeyEvent.KEYCODE_DPAD_UP -> "up"
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "enter"
            KeyEvent.KEYCODE_BACK -> "back"
            else -> null
        }
        if (dir != null) {
            // Tombol Enter/Back yang ditahan tidak boleh memicu aksi berulang.
            val isRepeat = (event?.repeatCount ?: 0) > 0
            if (!(isRepeat && (dir == "enter" || dir == "back"))) {
                webView.evaluateJavascript("window.handleDpad('$dir');", null)
            }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_PROG_RED || keyCode == KeyEvent.KEYCODE_MENU) {
            if ((event?.repeatCount ?: 0) == 0) {
                webView.evaluateJavascript("if(typeof boostRamAction==='function') boostRamAction();", null)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    class TiltboxBridge(private val activity: MainActivity) {
        private val TAG = "TiltboxBridge"
        private val context: Context = activity

        @Volatile
        private var cachedAppsJson: String? = null

        fun invalidateAppCache() {
            cachedAppsJson = null
        }

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
                } else false
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
                false
            }
        }

        @JavascriptInterface
        fun launchMedia(): Boolean {
            val pm = context.packageManager
            val mediaPkgs = arrayOf("com.lonelycatgames.Xplore", "com.tcl.mediacenter", "com.tcl.ui_mediaCenter", "com.tcl.videoplayer")
            for (pkg in mediaPkgs) {
                try {
                    val intent = pm.getLaunchIntentForPackage(pkg) ?: continue
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return true
                } catch (e: Exception) {
                    Log.w(TAG, "Gagal membuka pemutar media $pkg", e)
                }
            }
            return false
        }

        @JavascriptInterface
        fun launchHdmi(port: String): Boolean {
            return try {
                val pm = context.packageManager
                val tclSources = arrayOf("com.tcl.tvinput", "com.tcl.sourcemanager", "com.tcl.tv")
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
                    try {
                        val katniss = context.packageManager.getLaunchIntentForPackage("com.google.android.katniss")
                        if (katniss != null) {
                            katniss.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(katniss)
                            true
                        } else false
                    } catch (e3: Exception) {
                        false
                    }
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
        fun pickWallpaper(): Boolean {
            activity.startWallpaperPicker()
            return true
        }

        @JavascriptInterface
        fun getWallpaperDataUrl(): String {
            val f = File(context.filesDir, WALLPAPER_FILE)
            if (!f.exists()) return ""
            return try {
                "data:image/jpeg;base64," + Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
            } catch (e: Throwable) {
                Log.e(TAG, "Gagal membaca wallpaper", e)
                ""
            }
        }

        @JavascriptInterface
        fun clearWallpaper(): Boolean {
            return try {
                val f = File(context.filesDir, WALLPAPER_FILE)
                !f.exists() || f.delete()
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun boostRam(): String {
            return try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val pm = context.packageManager
                val myPkg = context.packageName

                val beforeMi = ActivityManager.MemoryInfo()
                am?.getMemoryInfo(beforeMi)

                if (am != null) {
                    for (app in pm.getInstalledApplications(0)) {
                        if (app.packageName != myPkg && !app.packageName.startsWith("com.android.systemui")) {
                            am.killBackgroundProcesses(app.packageName)
                        }
                    }
                }
                System.gc()

                val afterMi = ActivityManager.MemoryInfo()
                am?.getMemoryInfo(afterMi)

                val freedMb = Math.max(12L, (afterMi.availMem - beforeMi.availMem) / (1024 * 1024))
                val availMb = afterMi.availMem / (1024 * 1024)
                val totalMb = afterMi.totalMem / (1024 * 1024)
                val msg = "RAM Booster: +${freedMb} MB dibebaskan (Tersedia ${availMb} / ${totalMb} MB)"

                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
                msg
            } catch (e: Exception) {
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
        fun getInstalledApps(): String {
            cachedAppsJson?.let { return it }

            val jsonArray = JSONArray()
            val pm = context.packageManager

            try {
                val intentLeanback = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                val intentStandard = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

                val seenPackages = mutableSetOf(
                    context.packageName,
                    "com.google.android.tvlauncher",
                    "com.google.android.leanbacklauncher",
                    "com.amazon.tv.leanbacklauncher",
                    "com.tcl.cyberui",
                    "com.spocky.projengmenu",
                    "com.tcl.initsetup",
                    "com.tcl.rc.ota",
                    "com.tcl.tcl_bt_rcu_service",
                    "com.tcl.framework.custom",
                    "com.tcl.tv"
                )

                val allResolveInfos = mutableListOf<ResolveInfo>()
                allResolveInfos.addAll(pm.queryIntentActivities(intentLeanback, 0))
                allResolveInfos.addAll(pm.queryIntentActivities(intentStandard, 0))

                for (info in allResolveInfos) {
                    val pkgName = info.activityInfo?.packageName ?: continue
                    if (seenPackages.contains(pkgName)) continue
                    seenPackages.add(pkgName)

                    val appName = info.loadLabel(pm).toString()
                    val iconDrawable = info.loadIcon(pm)
                    val iconBase64 = drawableToBase64(iconDrawable)

                    val appObj = JSONObject().apply {
                        put("packageName", pkgName)
                        put("name", appName)
                        put("icon", iconBase64)
                    }
                    jsonArray.put(appObj)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gagal mengambil daftar aplikasi", e)
            }

            val result = jsonArray.toString()
            cachedAppsJson = result
            return result
        }

        @Suppress("DEPRECATION")
        private fun drawableToBase64(drawable: Drawable): String {
            return try {
                val maxDim = 96
                val rawBitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
                    val b = drawable.bitmap
                    if (b.width > maxDim || b.height > maxDim) {
                        Bitmap.createScaledBitmap(b, maxDim, maxDim, true)
                    } else {
                        b
                    }
                } else {
                    val w = if (drawable.intrinsicWidth > 0) Math.min(drawable.intrinsicWidth, maxDim) else maxDim
                    val h = if (drawable.intrinsicHeight > 0) Math.min(drawable.intrinsicHeight, maxDim) else maxDim
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                    bmp
                }
                val stream = ByteArrayOutputStream()
                val fmt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    Bitmap.CompressFormat.WEBP
                }
                rawBitmap.compress(fmt, 80, stream)
                val byteArray = stream.toByteArray()
                "data:image/webp;base64," + Base64.encodeToString(byteArray, Base64.NO_WRAP)
            } catch (e: Exception) {
                ""
            }
        }
    }
}
