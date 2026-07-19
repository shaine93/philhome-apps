package com.philhome.sonnettevideo

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Vidéo live de la sonnette via WebRTC (go2rtc/embed AlexxIT) dans une [WebView].
 *
 * WebRTC = fluide + basse latence + décodage natif, et marche en 5G **sans TURN** dès lors que les
 * deux bouts ont une IPv6 (cas fibre Free + Free 5G). Validé : 1600x1200 fluide en ~2 s sur cellulaire.
 *
 * Injecte un peu de JS pour forcer la lecture (muted+play, l'autoplay non-muet est bloqué) et tracer
 * l'état réel du <video> (dans le shadow DOM du composant) dans le debug-log.
 */
class WebrtcVideo(private val web: WebView, private val onPlaying: () -> Unit = {}) {

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    fun setup() {
        web.setBackgroundColor(Color.BLACK)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false   // autoplay vidéo
        }
        web.addJavascriptInterface(Bridge(), "AndroidDbg")
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                DebugLog.log("Video", "js ${m.message()}")
                return true
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript(PLAY_JS, null)
            }
            override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                if (req.isForMainFrame) DebugLog.log("Video", "ERREUR ${err.errorCode} ${err.description}")
            }
        }
    }

    /** Charge le flux. [link]=door_lo/mid/hi ; [mode]=webrtc/mse ; [media]=video ou video+audio. */
    fun play(link: String, mode: String = "webrtc", media: String = "video") {
        val url = Config.webrtcUrl(link, mode, media)
        DebugLog.log("Video", "play $link [$mode] media=$media")
        web.loadUrl(url)
    }

    /** Coupe le son du flux (aperçu pendant la sonnerie → autoplay autorisé). */
    fun mute() = injectMute(true)

    /** Rétablit le son du visiteur (à appeler sur le geste « Répondre » → autoplay non-muet autorisé). */
    fun unmute() = injectMute(false)

    private fun injectMute(muted: Boolean) {
        DebugLog.log("Video", if (muted) "mute" else "unmute (audio visiteur)")
        // L'enforcer (dans PLAY_JS) applique ce drapeau en continu → robuste au remplacement du <video>.
        web.post { web.evaluateJavascript("window.__wantSound=${!muted};", null) }
    }

    fun destroy() {
        try { web.loadUrl("about:blank"); web.destroy() } catch (_: Exception) {}
    }

    inner class Bridge {
        @JavascriptInterface
        fun log(msg: String) {
            DebugLog.log("Video-JS", msg)
            if (msg.startsWith("PLAYING")) web.post { onPlaying() }
        }
    }

    companion object {
        // Enforcer : trouve le <video> (shadow DOM), maintient l'état son voulu (window.__wantSound)
        // et l'autoplay en continu → robuste au remplacement du <video> par le composant. Trace PLAYING.
        private const val PLAY_JS = """
(function(){
  if(window.__camInit)return; window.__camInit=true;
  if(typeof window.__wantSound==='undefined') window.__wantSound=false;
  function fv(){var v=document.querySelector('video');if(v)return v;
    var a=document.querySelectorAll('*');for(var i=0;i<a.length;i++){if(a[i].shadowRoot){var s=a[i].shadowRoot.querySelector('video');if(s)return s;}}return null;}
  var logged=false, tick=0, lastT=-1;
  setInterval(function(){
    var v=fv(); if(!v)return;
    try{ v.setAttribute('playsinline',''); }catch(e){}
    var want=!window.__wantSound;
    if(v.muted!==want){ try{v.muted=want;}catch(e){} }
    if(v.paused){ try{var p=v.play(); if(p&&p.catch)p.catch(function(){});}catch(e){} }
    if(!logged && v.videoWidth>0){ logged=true; try{AndroidDbg.log('PLAYING '+v.videoWidth+'x'+v.videoHeight);}catch(e){} }
    if(++tick%6===0){ var adv=(v.currentTime>lastT+0.05)?'OK':'FIGE'; lastT=v.currentTime;
      var ai='nosrc'; try{var s=v.srcObject; if(s&&s.getAudioTracks){var at=s.getAudioTracks();
        ai='aTracks='+at.length; if(at[0])ai+='('+at[0].enabled+'/'+at[0].muted+'/'+at[0].readyState+')';}}catch(e){}
      try{AndroidDbg.log('t='+v.currentTime.toFixed(1)+' '+adv+' muted='+v.muted+' '+ai);}catch(e){} }
  },500);
})();
"""
    }
}