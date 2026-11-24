package com.kendang.realpads.skin

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Gambar siap pakai. [active] diisi BERTAHAP di latar belakang (state map -> layar menggambar ulang sendiri saat ada yang siap). */
internal class LayerImages(
    val base: ImageBitmap,
    val rotary: Map<String, ImageBitmap>,
    val active: Map<String, ImageBitmap>
)

/**
 * Satu sesi render skin: hasil gambar + pekerjaan render-nya hidup di LEVEL APLIKASI, bukan di dalam komposisi SkinHost.
 *
 * Kenapa: dulu gambar skin disimpan lewat `remember` di SkinHost. Setiap pindah ke mode YouTube / Kit / Mixer, SkinHost
 * keluar dari komposisi -> gambar hilang dan render yang sedang jalan DIBATALKAN. Begitu kembali ke skin, semuanya
 * dimuat / dirender dari awal. Sekarang sesi tetap ada selama skin yang sama dipakai, jadi kembali ke skin = langsung
 * tampil (dan render yang belum selesai tetap dilanjutkan di latar belakang walau layar skin sedang tidak tampil).
 */
internal class SkinRenderSession(val key: String, val w: Int, val h: Int, val visiblePads: Int) {
    var layers by mutableStateOf<LayerImages?>(null)
    var error by mutableStateOf<String?>(null)
    var failed by mutableStateOf(false)
    var job: Job? = null
}

internal object SkinSessionStore {
    private const val TAG = "SkinSessionStore"
    /** Satu sesi saja yang disimpan (bitmap skin besar): skin lain / ukuran layar sangat beda menggantikan yang lama. */
    private const val MAX_SESSIONS = 1

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = ArrayList<SkinRenderSession>()

    /** Ambil sesi yang cocok (skin + jumlah pad + ukuran yang masih layak, lihat SkinRenderCache.fits) atau buat baru. Panggil dari main thread. */
    fun obtain(context: Context, skin: SkinDefinition, visiblePads: Int, w: Int, h: Int): SkinRenderSession {
        val app = context.applicationContext
        val key = SkinRenderCache.keyOf(skin, visiblePads)
        // sesi gagal dibuang supaya percobaan berikutnya (mis. pilih ulang skin) merender lagi
        sessions.removeAll { it.failed }
        val found = sessions.firstOrNull { it.key == key && SkinRenderCache.fits(it.w, it.h, w.toFloat(), h.toFloat()) }
        if (found != null) return found

        val s = SkinRenderSession(key, w, h, visiblePads)
        sessions.add(s)
        while (sessions.size > MAX_SESSIONS) {
            val old = sessions.removeAt(0)
            old.job?.cancel()
        }
        s.job = scope.launch { render(app, skin, s) }
        return s
    }

    /** Lepas semua gambar skin di memori (mis. sistem kekurangan memori). Sesi berikutnya memuat dari cache disk. */
    fun clear() {
        sessions.forEach { it.job?.cancel() }
        sessions.clear()
    }

    private suspend fun CoroutineScope.render(app: Context, skin: SkinDefinition, s: SkinRenderSession) {
        val spec = RenderSpec(s.w, s.h)
        val visiblePads = s.visiblePads

        // 1) cache disk: sudah pernah dirender -> tinggal memuat gambar
        val cached = withContext(Dispatchers.IO) { runCatching { SkinRenderCache.load(app, skin, visiblePads, spec) }.getOrNull() }
        if (cached != null) {
            s.layers = LayerImages(
                cached.core.base.asImageBitmap(),
                cached.core.rotary.mapValues { it.value.asImageBitmap() },
                cached.active.mapValues { it.value.asImageBitmap() }
            )
            return
        }

        // 2) render INTI (seluruh skin + knob): skin langsung tampil begitu ini selesai
        val core = try {
            SkinRenderer.buildCore(skin, spec, visiblePads)
        } catch (e: Throwable) {
            Log.e(TAG, "render gagal", e)
            s.error = "${e.javaClass.simpleName}: ${e.message}"
            null
        }
        if (core == null) { s.failed = true; return }
        val activeMap = mutableStateMapOf<String, ImageBitmap>()
        s.layers = LayerImages(
            core.base.asImageBitmap(),
            core.rotary.mapValues { it.value.asImageBitmap() },
            activeMap
        )

        // 3) state tekan pad/tombol, satu per satu di latar belakang (sebelum siap: highlight bawaan)
        val collected = HashMap<String, Bitmap>()
        SkinRenderer.buildDetails(skin, spec, visiblePads, { isActive }) { id, bmp ->
            collected[id] = bmp
            activeMap[id] = bmp.asImageBitmap()
        }

        // 4) simpan ke cache disk untuk pembukaan berikutnya (dilewati bila dibatalkan di tengah jalan)
        if (isActive) withContext(Dispatchers.IO) { runCatching { SkinRenderCache.save(app, skin, visiblePads, spec, core, collected) } }
    }
}
