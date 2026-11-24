package com.kendang.realpads

import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Lapisan YouTube (v1.15): menggantikan area pad selama MODE YouTube nyala (tombol di tab kanan, sejajar
 * mode edit / mixer / kit). Panel preset, panel musik, dan strip tombol tetap berfungsi seperti biasa.
 *
 * PENTING (inti perbaikan "klik play lalu berhenti"): composable ini dipasang di SATU posisi tetap dan
 * WebView-nya TIDAK PERNAH dilepas dari layar. Saat mode ditutup, lapisan cuma digeser jauh ke luar layar
 * (offset) - WebView tetap menempel & terlihat bagi Chromium, jadi musiknya lanjut jalan di belakang pad.
 * Kalau dilepas (if/else biasa) Chromium menjeda media-nya.
 */
@Composable
fun YouTubeLayer(
    visible: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // WebView dibuat SETELAH frame pertama tergambar (pembuatan WebView pertama kali berat di main thread).
    // Dulu dibuat langsung saat komposisi -> layar sempat membeku/hitam dan panel kiri + toolbar "hilang"
    // sampai WebView selesai dimuat.
    var wv by remember { mutableStateOf<WebView?>(null) }
    var showSync by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (wv == null) {
            withFrameNanos { }
            wv = YouTubeState.ensureWebView(context)
        }
    }

    // Tombol kembali: mundur di riwayat YouTube dulu, baru tutup mode.
    BackHandler(enabled = visible) {
        if (YouTubeState.canGoBack) YouTubeState.goBack() else onClose()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            // Tidak tampil = digeser jauh keluar layar (bukan dihapus dari komposisi, bukan ukuran 0).
            .then(if (visible) Modifier else Modifier.offset(x = (-20000).dp))
            // Potong gambar di batas lapisan ini: WebView tidak boleh menggambar area hitam di luar
            // kotaknya (menutupi panel preset kiri, toolbar, dst) saat sedang dimuat.
            .clipToBounds()
            .background(Color.Black)
    ) {
        if (visible) {
            YouTubeToolbar(onClose = onClose, onSync = { showSync = true })
        }
        if (visible && showSync) {
            YouTubeSyncDialog(onDismiss = { showSync = false })
        }
        val web = wv
        if (web != null) {
            AndroidView(
                factory = { ctx ->
                    // Wadah yang memotong anak-anaknya (clipChildren) -> WebView tidak bisa menggambar di luar kotaknya.
                    FrameLayout(ctx).apply {
                        clipChildren = true
                        clipToPadding = true
                        setBackgroundColor(android.graphics.Color.BLACK)
                        // Jaga-jaga: WebView yang sama tidak boleh punya dua induk.
                        (web.parent as? ViewGroup)?.removeView(web)
                        addView(
                            web,
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds()
            )
        } else {
            Box(modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black))
        }
    }
}

@Composable
private fun YouTubeToolbar(onClose: () -> Unit, onSync: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().background(CtrlPanelColor)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(34.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BarIcon(Icons.Filled.ArrowBack, "Kembali", enabled = YouTubeState.canGoBack) { YouTubeState.goBack() }
            BarIcon(Icons.Filled.ArrowForward, "Maju", enabled = YouTubeState.canGoForward) { YouTubeState.goForward() }
            BarIcon(Icons.Filled.Home, "Beranda YouTube", enabled = true) { YouTubeState.goHome() }
            BarIcon(Icons.Filled.Refresh, "Muat ulang", enabled = true) { YouTubeState.reload() }
            Text(
                text = if (YouTubeState.title.isNotBlank()) YouTubeState.title else "YouTube",
                color = PadTextColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
            )
            // Penyetelan sinkron suara YouTube <-> kendang untuk hasil rekaman (lihat YouTubeSyncDialog).
            Box(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(CtrlPanelColor)
                    .clickable { onSync() }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("SYNC", color = PadTextColor, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            Box(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(ThemeState.accentColor)
                    .clickable { onClose() }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Tutup mode YouTube",
                        tint = ThemeState.accentTextColor,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        " TUTUP",
                        color = ThemeState.accentTextColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
        }
        // Garis kemajuan muat halaman (tipis, hanya saat memuat).
        Box(modifier = Modifier.fillMaxWidth().height(2.dp)) {
            if (YouTubeState.loading && YouTubeState.progress < 100) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(YouTubeState.progress / 100f)
                        .background(ThemeState.accentColor)
                )
            }
        }
    }
}

@Composable
private fun BarIcon(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(34.dp)
            .fillMaxHeight()
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) PadTextColor else TextDim.copy(alpha = 0.45f),
            modifier = Modifier.size(16.dp)
        )
    }
}
