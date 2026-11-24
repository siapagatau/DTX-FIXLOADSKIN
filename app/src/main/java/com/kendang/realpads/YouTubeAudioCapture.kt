package com.kendang.realpads

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Menangkap audio YouTube (WebView) lewat AudioPlaybackCapture (Android 10+ / API 29+) lalu
 * menyuapkannya ke jalur "masukan eksternal rekaman" di engine native
 * (AudioEngine.nativeFeedExternalRecording) - jalur yang sudah ada sejak v1.15 tapi belum dipakai.
 *
 * Dipakai oleh REC LAYAR (ScreenRecordService), karena MediaProjection-nya sudah dimiliki di sana.
 *
 * URUTAN PEMAKAIAN (penting):
 *   1. prepare(context)   -> SEBELUM AudioEngine.nativeStartRecording()
 *   2. AudioEngine.nativeStartRecording()
 *   3. begin()            -> SESUDAH nativeStartRecording (feed hanya diterima engine saat recording_ aktif)
 *   4. ... rekam ...
 *   5. stop()             -> SESUDAH AudioEngine.nativeStopRecording()
 *
 * Hanya audio milik app ini (UID sendiri) dengan usage MEDIA yang diambil. Suara engine kendang
 * memakai usage GAME (lihat AudioBackends.cpp & AudioBackendManager.kt), jadi TIDAK ikut tertangkap
 * dan tidak terekam dobel.
 */
class YouTubeAudioCapture(private val projection: MediaProjection) {

    companion object {
        private const val TAG = "YouTubeAudioCapture"
        private const val SAMPLE_RATE = 48_000
        private const val CHUNK_FRAMES = 960 // 20 ms

        /**
         * Nilai AWAL penundaan (ms) yang dipakai engine: jitter buffer audio YouTube SEKALIGUS penundaan
         * jalur kendang di file rekaman (menjaga sinkron). Maks 400 (kExtMaxDelayMs di PadEngine.h).
         * Nilai yang dipakai sebenarnya diatur user lewat tombol SYNC di toolbar YouTube
         * (YouTubeSyncDialog / YouTubeSyncStorage); ini hanya default-nya.
         * YouTube terdengar LEBIH DULU dari kendang -> naikkan; KETINGGALAN -> turunkan.
         */
        const val DEFAULT_DELAY_MS = 120

        val isSupported: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    @Volatile private var running = false

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var externalEnabled = false

    /** Siapkan AudioRecord + aktifkan jalur eksternal engine. Return false kalau tidak bisa (rekaman tetap jalan tanpa suara YouTube). */
    @SuppressLint("MissingPermission")
    fun prepare(context: Context, delayMs: Int = DEFAULT_DELAY_MS): Boolean {
        if (!isSupported) return false
        if (!hasPermission(context)) {
            Log.w(TAG, "izin RECORD_AUDIO belum diberikan - rekaman tanpa suara YouTube")
            return false
        }
        return try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .addMatchingUid(Process.myUid())
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            // ~200 ms cadangan supaya tidak ada audio yang jatuh kalau thread telat sebentar.
            val bytes = maxOf(minBytes * 2, SAMPLE_RATE * 2 * 2 / 5)
            val rec = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bytes)
                .setAudioPlaybackCaptureConfig(config)
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                Log.w(TAG, "AudioRecord tidak terinisialisasi")
                return false
            }
            record = rec
            // Minimal 10 ms: delay 0 membuat engine melewati pencampuran audio eksternal sama sekali.
            AudioEngine.nativeSetExternalRecording(true, delayMs.coerceAtLeast(10))
            externalEnabled = true
            true
        } catch (t: Throwable) {
            Log.e(TAG, "prepare gagal", t)
            record?.release()
            record = null
            false
        }
    }

    /** Mulai membaca & menyuapkan PCM ke engine. Panggil SESUDAH AudioEngine.nativeStartRecording(). */
    fun begin() {
        val rec = record ?: return
        if (running) return
        running = true
        thread = Thread({
            val buf = ShortArray(CHUNK_FRAMES * 2)
            try {
                rec.startRecording()
                while (running) {
                    val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                    if (n > 0) {
                        AudioEngine.nativeFeedExternalRecording(buf, n / 2)
                    } else if (n < 0) {
                        Log.w(TAG, "AudioRecord.read error=$n")
                        break
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "thread capture gagal", t)
            }
        }, "YtAudioCapture").also { it.start() }
    }

    /** Hentikan & lepas semuanya. Aman dipanggil berkali-kali. */
    fun stop() {
        running = false
        val t = thread
        thread = null
        try { t?.join(1_000) } catch (_: InterruptedException) {}
        val rec = record
        record = null
        if (rec != null) {
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
        if (externalEnabled) {
            externalEnabled = false
            runCatching { AudioEngine.nativeSetExternalRecording(false, 0) }
        }
    }
}
