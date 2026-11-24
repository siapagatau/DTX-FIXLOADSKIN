package com.kendang.realpads

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * YOUTUBE IN-APP (v1.15) - sumber musik tambahan selain file lokal, tanpa perlu mengunduh lagu.
 *
 * Satu WebView PERMANEN (dibuat sekali, tidak pernah dilepas dari layar selama app hidup) menuju
 * youtube.com apa adanya (tanpa pemblokir iklan, seperti browser biasa). Menyalakan/mematikan MODE YouTube hanya mengubah
 * ukuran tampilannya (penuh <-> 1dp), BUKAN membuang/membuat ulang WebView - itulah yang mencegah musik
 * "main sebentar lalu berhenti" (WebView yang di-detach/di-pause/disembunyikan dijeda oleh Chromium).
 *
 * Panel musik kanan (slider menit, volume, play/stop) otomatis terhubung ke sini selama YouTube yang
 * terakhir dimainkan (sourceActive). Audio YouTube TIDAK ikut terekam: REC dan REC LAYAR dikunci selama
 * YouTube diputar / mode YouTube terbuka (lihat recordBlocked).
 */
object YouTubeState {
    private const val TAG = "YouTubeState"

    private val main = Handler(Looper.getMainLooper())

    // --- State UI (dibaca Compose) ---
    var modeOn by mutableStateOf(false)
        private set
    // WebView baru dibuat saat mode YouTube pertama kali dibuka (tidak membebani startup app).
    var everOpened by mutableStateOf(false)
        private set
    var hasMedia by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set
    var positionSec by mutableStateOf(0.0)
        private set
    var durationSec by mutableStateOf(0.0)
        private set
    var title by mutableStateOf("")
        private set
    var loading by mutableStateOf(false)
        private set
    var progress by mutableStateOf(100)
        private set
    var canGoBack by mutableStateOf(false)
        private set
    var canGoForward by mutableStateOf(false)
        private set
    // Naik tiap WebView memuat dokumen baru -> MainActivity menyetel ulang volume ke halaman baru.
    var pageGeneration by mutableStateOf(0)
        private set

    // True kalau YouTube adalah sumber musik yang sedang "dipegang" panel musik.
    private var claimed by mutableStateOf(false)
    val sourceActive: Boolean get() = claimed && hasMedia

    /**
     * REC audio-saja tidak boleh DIMULAI selagi YouTube diputar atau layar YouTube terbuka:
     * audio WebView tidak lewat engine dan REC audio-saja tidak punya MediaProjection untuk
     * AudioPlaybackCapture. Untuk merekam YouTube pakai REC LAYAR (lihat screenRecordBlocked).
     */
    val recordBlocked: Boolean get() = modeOn || playing

    /**
     * REC LAYAR: di Android 10+ audio YouTube ditangkap lewat AudioPlaybackCapture
     * (YouTubeAudioCapture), jadi TIDAK diblokir. Di Android 9 ke bawah tetap dikunci seperti dulu.
     */
    val screenRecordBlocked: Boolean
        get() = !YouTubeAudioCapture.isSupported && (modeOn || playing)

    private var webView: WebView? = null
    private var lastVolume = 1f
    private var docStartSupported = false

    // ---------------------------------------------------------------------------------------------
    // Mode (tombol di tab kanan, sama seperti mode kit / edit / mixer)
    // ---------------------------------------------------------------------------------------------
    fun enter() { everOpened = true; modeOn = true }
    fun exit() { modeOn = false }
    fun toggle() { if (modeOn) exit() else enter() }

    // ---------------------------------------------------------------------------------------------
    // Kendali musik (dipanggil MainActivity lewat panel musik / skin)
    // ---------------------------------------------------------------------------------------------
    fun togglePlay() { if (playing) pause() else play() }

    fun play() {
        claimed = true
        js("window.__dtx&&window.__dtx.play()")
    }

    fun pause() { js("window.__dtx&&window.__dtx.pause()") }

    /** Lepas kepemilikan sumber musik (dipanggil saat lagu LOKAL mulai dipakai) dan jeda YouTube. */
    fun releaseSource() {
        if (playing) pause()
        claimed = false
    }

    fun seek(seconds: Double) {
        positionSec = seconds // optimistis, supaya slider tidak "loncat balik" menunggu laporan berikutnya
        js("window.__dtx&&window.__dtx.seek(${seconds})")
    }

    fun setVolume(v: Float) {
        lastVolume = v
        js("window.__dtx&&window.__dtx.vol(${v})")
    }

    fun goBack() { webView?.let { if (it.canGoBack()) it.goBack() } }
    fun goForward() { webView?.let { if (it.canGoForward()) it.goForward() } }
    fun reload() { webView?.reload() }
    fun goHome() { webView?.loadUrl(YouTubeBridge.HOME_URL) }

    /** Jeda saat app benar-benar ke background (onStop). Tidak dilanjutkan otomatis. */
    fun onAppStopped() { if (playing) pause() }

    /** Dipanggil MainActivity.onDestroy: bongkar WebView supaya tidak menahan Activity lama. */
    fun release() {
        val wv = webView ?: return
        webView = null
        runCatching { (wv.parent as? ViewGroup)?.removeView(wv) }
        runCatching { wv.stopLoading() }
        runCatching { wv.destroy() }
        hasMedia = false; playing = false; claimed = false; modeOn = false
        positionSec = 0.0; durationSec = 0.0; title = ""
    }

    private fun js(code: String) {
        val wv = webView ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching { wv.evaluateJavascript(code, null) }
        } else {
            main.post { runCatching { wv.evaluateJavascript(code, null) } }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // WebView
    // ---------------------------------------------------------------------------------------------
    private fun activityOf(ctx: Context): Context {
        var c = ctx
        while (c is ContextWrapper) {
            if (c is android.app.Activity) return c
            c = c.baseContext
        }
        return ctx
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun ensureWebView(context: Context): WebView {
        webView?.let { return it }
        val wv = WebView(activityOf(context))
        wv.setBackgroundColor(Color.BLACK)
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true
        val st = wv.settings
        st.javaScriptEnabled = true
        st.domStorageEnabled = true
        st.mediaPlaybackRequiresUserGesture = false   // tanpa ini YouTube butuh ketukan ekstra & sering "main lalu berhenti"
        st.javaScriptCanOpenWindowsAutomatically = false
        st.setSupportMultipleWindows(false)
        st.cacheMode = WebSettings.LOAD_DEFAULT
        st.loadWithOverviewMode = true
        st.useWideViewPort = true
        st.builtInZoomControls = false
        st.displayZoomControls = false
        st.allowFileAccess = false
        st.allowContentAccess = false

        runCatching {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(wv, true)
        }

        wv.addJavascriptInterface(Bridge(), "DtxYt")

        // Suntik script jembatan (slider/volume/play) di AWAL dokumen. Cadangan untuk WebView lama:
        // evaluateJavascript di bawah.
        docStartSupported = runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        }.getOrDefault(false)
        if (docStartSupported) {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(wv, YouTubeBridge.SCRIPT, setOf("https://*.youtube.com"))
            }.onFailure { docStartSupported = false; Log.e(TAG, "addDocumentStartJavaScript gagal", it) }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return when (YouTubeBridge.classify(request.url)) {
                    YouTubeBridge.Nav.LOAD_IN_APP -> false
                    // Seperti browser biasa: link ke situs lain (mis. iklan/deskripsi video) dibuka di browser HP.
                    YouTubeBridge.Nav.OPEN_EXTERNAL -> {
                        if (request.hasGesture() || request.isForMainFrame) openExternal(view.context, request.url)
                        true
                    }
                    YouTubeBridge.Nav.IGNORE -> true
                }
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                loading = true
                if (!docStartSupported) view.evaluateJavascript(YouTubeBridge.SCRIPT, null)
                refreshNav(view)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                loading = false
                if (!docStartSupported) view.evaluateJavascript(YouTubeBridge.SCRIPT, null)
                refreshNav(view)
                pageGeneration += 1
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                refreshNav(view)
            }
        }
        // WebChromeClient default (tanpa onPermissionRequest/onShowCustomView): izin kamera/mik/lokasi
        // ditolak, dan tombol layar-penuh YouTube tidak melakukan apa-apa. Cukup untuk fungsi musik.
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
        }

        webView = wv
        wv.loadUrl(YouTubeBridge.HOME_URL)
        return wv
    }

    private fun openExternal(ctx: Context, uri: Uri) {
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Toast.makeText(ctx, "Tidak bisa membuka link", Toast.LENGTH_SHORT).show() }
    }

    private fun refreshNav(view: WebView) {
        canGoBack = view.canGoBack()
        canGoForward = view.canGoForward()
    }

    // ---------------------------------------------------------------------------------------------
    // Jembatan JS -> native (thread "JavaBridge", bukan UI thread)
    // ---------------------------------------------------------------------------------------------
    private class Bridge {
        private var wasPlaying = false

        @JavascriptInterface
        fun state(pos: Double, dur: Double, isPlaying: Boolean, ttl: String?, ok: Boolean) {
            YouTubeState.main.post {
                YouTubeState.hasMedia = ok
                YouTubeState.positionSec = if (pos.isNaN()) 0.0 else pos
                YouTubeState.durationSec = if (dur.isNaN()) 0.0 else dur
                YouTubeState.playing = isPlaying && ok
                YouTubeState.title = ttl ?: ""
                // YouTube mulai berbunyi (dari mana pun: ketukan di halaman atau tombol panel) -> jadi sumber
                // musik, dan lagu lokal dijeda supaya tidak ada dua musik sekaligus.
                if (isPlaying && ok && !wasPlaying) {
                    YouTubeState.claimed = true
                    runCatching { AudioEngine.nativeStopMusic() }
                }
                wasPlaying = isPlaying && ok
            }
        }
    }
}
