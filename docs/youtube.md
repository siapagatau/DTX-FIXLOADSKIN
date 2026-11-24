# Mode YouTube (v1.15)

Putar musik langsung dari YouTube di dalam app (tanpa unduh lagu), dengan slider menit + volume musik yang ikut
mengendalikannya. YouTube dimuat **apa adanya seperti browser biasa** (tidak ada pemblokir iklan).

## Cara pakai
- Tombol **ikon video** di tab kanan (di antara mode mixer dan Kit) - cara kerjanya sama seperti mode edit/mixer/kit:
  tekan = area pad diganti layar YouTube, tekan lagi (atau tombol TUTUP) = kembali ke pad.
- Pintu kedua: ikon video di dialog **Pilih Lagu** (di samping ikon folder).
- Pilih/putar lagu di YouTube, lalu TUTUP mode: musik tetap jalan di belakang pad. Panel musik kanan
  (play/stop, slider menit, slider MUSIK) otomatis mengendalikan YouTube selama YouTube yang terakhir dimainkan.
  Memilih lagu lokal (GANTI/LAGU) atau memutarnya mengembalikan panel ke lagu lokal dan menjeda YouTube
  (dan sebaliknya) - tidak pernah dua musik sekaligus.

## Rekaman dikunci selama YouTube dipakai
Audio WebView tidak lewat engine kendang, jadi REC / REC LAYAR tidak bisa merekamnya dengan baik (suara patah-patah,
tidak sinkron dengan kendang, banyak bug di Android). Karena itu:
- **REC** dan **REC LAYAR** tidak bisa dipencet (tombol redup; tombol skin ikut terkunci) selama YouTube **sedang diputar**
  atau **layar YouTube sedang terbuka** (`YouTubeState.recordBlocked`). Jeda + tutup YouTube dulu untuk merekam.
- Rekaman yang sudah jalan tetap bisa dihentikan. Kalau YouTube tiba-tiba main saat merekam, otomatis dijeda.
  Membuka layar YouTube saat merekam ditolak (ada toast).
- Jalur tangkap audio YouTube (Web Audio -> PCM -> native) sudah dibuang. Rekaman kendang normal, tanpa penundaan tambahan.
  (Fungsi native `setExternalRecording`/`feedExternalRecording` masih ada tapi tidak dipanggil.)

## Kenapa tidak lagi "klik play lalu berhenti"
| Penyebab umum | Penanganan |
|---|---|
| WebView dilepas/dibuat ulang saat mode ditutup atau layar direkomposisi | Satu WebView permanen (`YouTubeState`), dikomposisi di satu posisi tetap (`YouTubeLayer`); ditutup = digeser ke luar layar, bukan dihapus |
| Halaman dianggap "tersembunyi" -> YouTube menjeda | `document.hidden/visibilityState` dipalsukan + event `visibilitychange` dibuang |
| Autoplay perlu gestur | `mediaPlaybackRequiresUserGesture = false` |
| Activity dibuat ulang (keyboard/uiMode/density berubah) | `android:configChanges` di AndroidManifest |
| "Video dijeda. Lanjut menonton?" | `window._lact` diperbarui + dialog dikonfirmasi otomatis |

App ke background (`onStop`) menjeda YouTube (tidak dilanjutkan otomatis). Dialog sistem (izin, rekam layar,
file picker) hanya memicu `onPause` dan TIDAK menjeda musik.

## Tampilan saat memuat
WebView pertama kali dibuat SETELAH frame pertama tergambar, dan lapisan YouTube dipotong di batasnya
(`clipToBounds` + `FrameLayout.clipChildren`), sehingga panel preset kiri dan toolbar tidak lagi tertutup area hitam
/ hilang sesaat saat YouTube dimuat.

## Navigasi (`YouTubeBridge.kt`)
Domain YouTube/Google (login, consent, gambar, video) dimuat di dalam app. Link ke situs lain dibuka di browser HP
(seperti browser biasa). Skema yang tidak bisa dimuat WebView (`intent://`, `vnd.youtube:`, `market:`) diabaikan.

## Slider menit / volume / play (jembatan `window.DtxYt`)
Script (`YouTubeBridge.SCRIPT`) mengirim `state(posisi, durasi, playing, judul, valid)` tiap 400 ms ke `YouTubeState`;
perintah kembali lewat `window.__dtx.{play,pause,seek,vol}`. Slider MUSIK adalah satu slider untuk dua sumber
(`onMusicVolumeChange` mengatur engine lokal dan YouTube sekaligus). Script hanya membaca status & memberi perintah ke
elemen `<video>`; tidak menyentuh iklan maupun mengalihkan audio.

## File
`YouTubeState.kt` (WebView, jembatan, kendali), `YouTubeMode.kt` (layar + toolbar), `YouTubeBridge.kt`
(navigasi + script jembatan), `SkinToggleTab.kt` (tombol tab), `MusicLibraryDialog.kt` (pintu kedua), `MainActivity.kt`
(routing panel musik, kunci rekaman, lifecycle).
