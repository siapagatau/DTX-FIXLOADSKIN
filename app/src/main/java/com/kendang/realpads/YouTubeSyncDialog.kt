package com.kendang.realpads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Pengaturan SINKRON suara YouTube <-> kendang di hasil rekaman layar.
 * Geser slider / tombol -10 -1 +1 +10 -> tersimpan otomatis, berlaku di rekaman berikutnya.
 */
@Composable
fun YouTubeSyncDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var ms by remember { mutableIntStateOf(YouTubeSyncStorage.get(context)) }

    fun update(v: Int) {
        val c = v.coerceIn(YouTubeSyncStorage.MIN_MS, YouTubeSyncStorage.MAX_MS)
        ms = c
        YouTubeSyncStorage.set(context, c)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(PanelColor)
                .padding(18.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("SINKRON SUARA YOUTUBE", color = PadTextColor, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "Dipakai saat REC LAYAR. Rekam sebentar, putar hasilnya, lalu setel di sini. " +
                    "Berlaku di rekaman berikutnya.",
                color = TextDim, fontSize = 11.sp, lineHeight = 15.sp
            )
            Spacer(Modifier.height(12.dp))

            Slider(
                value = ms.toFloat(),
                onValueChange = { update(Math.round(it)) }, // geser = per 1 ms
                valueRange = YouTubeSyncStorage.MIN_MS.toFloat()..YouTubeSyncStorage.MAX_MS.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = ThemeState.accentColor,
                    activeTrackColor = ThemeState.accentColor,
                    inactiveTrackColor = CtrlPanelColor
                ),
                modifier = Modifier.fillMaxWidth()
            )
            // Tombol tambah/kurang: 10 ms (cepat) dan 1 ms (pas).
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SyncStepButton("-10") { update(ms - 10) }
                SyncStepButton("-1") { update(ms - 1) }
                Text(
                    "$ms ms",
                    color = ThemeState.accentColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                SyncStepButton("+1") { update(ms + 1) }
                SyncStepButton("+10") { update(ms + 10) }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "• YouTube terdengar LEBIH DULU dari kendang di hasil rekaman -> NAIKKAN.\n" +
                    "• YouTube terdengar KETINGGALAN dari kendang -> TURUNKAN.\n" +
                    "• Mulai dari ${YouTubeSyncStorage.DEFAULT_MS} ms, ubah 20-50 ms dulu, lalu pas-kan dengan tombol 1 ms.",
                color = TextDim, fontSize = 11.sp, lineHeight = 16.sp
            )

            Spacer(Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { update(YouTubeSyncStorage.DEFAULT_MS) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CtrlPanelColor, contentColor = PadTextColor)
                ) { Text("RESET", fontWeight = FontWeight.SemiBold, fontSize = 12.sp) }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ThemeState.accentColor,
                        contentColor = ThemeState.accentTextColor
                    )
                ) { Text("TUTUP", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun SyncStepButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        colors = ButtonDefaults.buttonColors(containerColor = CtrlPanelColor, contentColor = PadTextColor),
        modifier = Modifier.height(34.dp).widthIn(min = 46.dp)
    ) { Text(label, fontWeight = FontWeight.SemiBold, fontSize = 12.sp) }
}
