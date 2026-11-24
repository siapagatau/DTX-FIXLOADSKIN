package com.kendang.realpads

import android.net.Uri

// Pendukung mode YouTube in-app (lihat YouTubeState). v1.15: TANPA pemblokir iklan - halaman YouTube
// dimuat apa adanya seperti browser biasa (iklan YouTube tampil normal). File ini hanya berisi:
//  1. HOME_URL + aturan navigasi (domain YouTube/Google dimuat di dalam app; link ke situs lain dibuka di
//     browser bawaan HP, seperti browser biasa).
//  2. SCRIPT: jembatan ke app (slider menit / volume / play-pause) + penjaga agar pemutaran tidak berhenti
//     saat mode YouTube ditutup (musik tetap jalan di belakang pad). Script TIDAK menyentuh iklan.
object YouTubeBridge {

    const val HOME_URL = "https://m.youtube.com/"

    // Domain yang dimuat di dalam WebView (YouTube, login/consent Google, gambar, video).
    private val IN_APP_SUFFIXES = listOf(
        "youtube.com", "youtu.be", "google.com", "gstatic.com", "ytimg.com", "googlevideo.com",
        "googleusercontent.com", "ggpht.com", "youtube-nocookie.com"
    )

    enum class Nav { LOAD_IN_APP, OPEN_EXTERNAL, IGNORE }

    fun classify(uri: Uri): Nav {
        val scheme = uri.scheme?.lowercase() ?: return Nav.IGNORE
        if (scheme == "about" || scheme == "blob" || scheme == "data") return Nav.LOAD_IN_APP
        if (scheme != "https" && scheme != "http") return Nav.IGNORE // intent://, vnd.youtube:, market: dst tidak bisa dimuat WebView
        val host = (uri.host ?: return Nav.IGNORE).lowercase()
        return if (IN_APP_SUFFIXES.any { host == it || host.endsWith(".$it") }) Nav.LOAD_IN_APP else Nav.OPEN_EXTERNAL
    }

    // Script yang disuntik di awal tiap dokumen YouTube (WebViewCompat.addDocumentStartJavaScript;
    // cadangan: evaluateJavascript saat onPageStarted/onPageFinished). Idempoten lewat window.__dtxInit.
    // Antarmuka native: window.DtxYt (lihat YouTubeState.Bridge).
    val SCRIPT: String = """
(function(){
  if (window.__dtxInit) return; window.__dtxInit = true;

  // 1) Halaman dianggap SELALU terlihat -> YouTube tidak menjeda saat WebView tersembunyi (mode YouTube
  //    ditutup, musik tetap jalan di belakang pad).
  try {
    Object.defineProperty(document,'hidden',{get:function(){return false},configurable:true});
    Object.defineProperty(document,'webkitHidden',{get:function(){return false},configurable:true});
    Object.defineProperty(document,'visibilityState',{get:function(){return 'visible'},configurable:true});
    Object.defineProperty(document,'webkitVisibilityState',{get:function(){return 'visible'},configurable:true});
    var stop=function(e){e.stopImmediatePropagation();};
    document.addEventListener('visibilitychange',stop,true);
    document.addEventListener('webkitvisibilitychange',stop,true);
    window.addEventListener('pagehide',stop,true);
  } catch(e){}

  function mainVideo(){
    return document.querySelector('video.html5-main-video') || document.querySelector('#movie_player video, .html5-video-player video') || document.querySelector('video');
  }

  // 2) Anti "Video dijeda. Lanjut menonton?" - anggap pengguna aktif + konfirmasi otomatis.
  function keepAlive(){
    try{ window._lact=Date.now(); }catch(e){}
    var n=document.querySelectorAll('yt-confirm-dialog-renderer #confirm-button button, ytm-confirm-dialog-renderer .dialog-buttons button:last-child');
    for(var i=0;i<n.length;i++){ try{ n[i].click(); }catch(e){} }
  }
  setInterval(keepAlive,2000);

  // 3) Jembatan ke aplikasi (slider menit/volume/play-pause). Hanya membaca status & memberi perintah ke
  //    elemen <video>; tidak ada pengalihan audio.
  var volTarget=1, volSet=false;
  function applyVol(v){
    if(!v) return;
    try{ var t=Math.min(1,volTarget); if(Math.abs(v.volume-t)>0.01) v.volume=t; }catch(e){}
  }
  function tick(){
    if(!window.DtxYt) return;
    var v=mainVideo();
    if(volSet) applyVol(v);
    var watch=(location.pathname.indexOf('/watch')===0)||(location.pathname.indexOf('/shorts')===0)||location.hostname==='youtu.be';
    var ok=!!(v && isFinite(v.duration) && v.duration>0 && (watch || (!v.paused && !v.muted)));
    var title=(document.title||'').replace(/\s*-\s*YouTube\s*${'$'}/,'');
    try{
      window.DtxYt.state(v&&isFinite(v.currentTime)?v.currentTime:0, v&&isFinite(v.duration)?v.duration:0,
        !!(v && !v.paused && !v.ended), title, ok);
    }catch(e){}
  }
  setInterval(tick,400);

  window.__dtx={
    play:function(){ var v=mainVideo(); if(v){ try{ v.play(); }catch(e){} } },
    pause:function(){ var v=mainVideo(); if(v){ try{ v.pause(); }catch(e){} } },
    seek:function(s){ var v=mainVideo(); if(v && isFinite(s)){ try{ v.currentTime=Math.max(0,s); }catch(e){} } },
    vol:function(x){ volTarget=Math.max(0,Math.min(1,x)); volSet=true; applyVol(mainVideo()); }
  };
})();
""".trimIndent()
}
