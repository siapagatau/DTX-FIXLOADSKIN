package com.kendang.realpads

import android.content.Context

private const val YT_SYNC_PREFS = "realpads_yt_sync"
private const val YT_SYNC_KEY = "delay_ms"

// Menyimpan penyetelan sinkron suara YouTube <-> kendang di hasil REKAMAN (lihat YouTubeSyncDialog).
// Nilainya = delayMs yang diberikan ke AudioEngine.nativeSetExternalRecording saat REC LAYAR dimulai,
// jadi baru berlaku di rekaman BERIKUTNYA (tidak mengubah rekaman yang sedang berjalan).
object YouTubeSyncStorage {
    const val MIN_MS = 10
    const val MAX_MS = 400 // = kExtMaxDelayMs di PadEngine.h
    const val STEP_MS = 1
    const val DEFAULT_MS = YouTubeAudioCapture.DEFAULT_DELAY_MS

    fun get(context: Context): Int =
        context.getSharedPreferences(YT_SYNC_PREFS, Context.MODE_PRIVATE)
            .getInt(YT_SYNC_KEY, DEFAULT_MS)
            .coerceIn(MIN_MS, MAX_MS)

    fun set(context: Context, ms: Int) {
        context.getSharedPreferences(YT_SYNC_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(YT_SYNC_KEY, ms.coerceIn(MIN_MS, MAX_MS))
            .apply()
    }
}
