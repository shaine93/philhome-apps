package com.philhome.sonnettevideo

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Écran DEV pour tester la vidéo MSE (go2rtc via embed AlexxIT) sans aller à la sonnette.
 *
 * WebView qui charge le flux MSE ; boutons LO/MID/HI + AUTO (stratégie low→high).
 * Comme `adb screencap` NE capture PAS la vidéo matérielle, on **injecte du JS** qui surveille
 * l'élément <video> (dimensions, currentTime qui avance, erreurs) et écrit dans le debug-log
 * → les logs disent si la vidéo joue vraiment. Lecture via `bash DEBUG/pull-log.sh`.
 *
 * Lançable via adb : adb shell am start -n com.philhome.sonnettevideo/.DevVideoActivity --es link door_hi
 */
class DevVideoActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var status: TextView
    private var loadStart = 0L

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLog.init(applicationContext)
        Net.prewarm()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        setContentView(root)

        status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6)); text = "DEV vidéo MSE"
        }
        root.addView(status)

        web = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            setBackgroundColor(Color.BLACK)
            addJavascriptInterface(JsBridge(), "AndroidDbg")
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    DebugLog.log("WebVideo", "js[${m.messageLevel()}] ${m.message()}")
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    val ms = SystemClock.elapsedRealtime() - loadStart
                    DebugLog.log("WebVideo", "pageFinished (${ms}ms)")
                    runOnUiThread { status.text = "chargé en ${ms}ms" }
                    view.evaluateJavascript(MONITOR_JS, null)
                }
                override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                    if (req.isForMainFrame) {
                        DebugLog.log("WebVideo", "ERREUR ${err.errorCode} ${err.description} url=${req.url}")
                    }
                }
            }
        }
        root.addView(web)

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(64))
        }
        bar.addView(navBtn("RTC LO") { load(Config.CAM_LINK_LO, "webrtc") })
        bar.addView(navBtn("RTC HI") { load(Config.CAM_LINK_HI, "webrtc") })
        bar.addView(navBtn("MSE LO") { load(Config.CAM_LINK_LO, "mse") })
        bar.addView(navBtn("AUTO ↑") { autoRamp() })
        root.addView(bar)

        load(
            intent.getStringExtra("link") ?: Config.CAM_LINK_LO,
            intent.getStringExtra("mode") ?: "webrtc"
        )
    }

    private fun load(link: String, mode: String) {
        val url = Config.webrtcUrl(link, mode)
        loadStart = SystemClock.elapsedRealtime()
        DebugLog.log("WebVideo", "load $link [$mode] → $url")
        runOnUiThread { status.text = "chargement $link ($mode)…"; web.loadUrl(url) }
    }

    /** Stratégie « chaînes TV » : accroche en LO (480p) puis monte en HI (1200p), en WebRTC. */
    private fun autoRamp() {
        DebugLog.log("WebVideo", "AUTO ramp WebRTC: LO puis HI")
        load(Config.CAM_LINK_LO, "webrtc")
        web.postDelayed({ load(Config.CAM_LINK_HI, "webrtc") }, 3000)
    }

    /** Pont JS → debug-log : c'est ce qui fait « parler » les logs sur l'état réel de la vidéo. */
    inner class JsBridge {
        @JavascriptInterface
        fun log(msg: String) = DebugLog.log("WebVideo-JS", msg)
    }

    override fun onDestroy() {
        try { web.loadUrl("about:blank"); web.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun navBtn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; textSize = 13f; isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f)
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT

        // Surveille l'élément <video> et rapporte au debug-log si la lecture avance réellement.
        private const val MONITOR_JS = """
(function(){
  window.onerror=function(m){try{AndroidDbg.log('jserr '+m);}catch(e){}};
  function findVideo(){
    var v=document.querySelector('video'); if(v) return v;
    var all=document.querySelectorAll('*');
    for(var i=0;i<all.length;i++){ if(all[i].shadowRoot){ var sv=all[i].shadowRoot.querySelector('video'); if(sv) return sv; } }
    return null;
  }
  try{ AndroidDbg.log('tags: '+Array.prototype.map.call(document.body.children,function(e){return e.tagName;}).join(',')); }catch(e){}
  try{ var txt=(document.body.innerText||'').trim().slice(0,140); if(txt) AndroidDbg.log('texte page: '+txt); }catch(e){}
  var tries=0;
  function attach(){
    var v=findVideo();
    if(!v){ if(tries++<12){setTimeout(attach,800);} else {AndroidDbg.log('aucun <video> (meme shadow) apres ~10s');} return; }
    AndroidDbg.log('video trouve '+v.videoWidth+'x'+v.videoHeight+' rs='+v.readyState+' paused='+v.paused);
    try{ v.muted=true; v.setAttribute('playsinline',''); var pr=v.play();
      if(pr&&pr.then){ pr.then(function(){AndroidDbg.log('play() OK');}).catch(function(e){AndroidDbg.log('play() rejet '+e.name+' '+e.message);}); }
    }catch(e){ AndroidDbg.log('play() exc '+e); }
    v.addEventListener('playing',function(){AndroidDbg.log('PLAYING '+v.videoWidth+'x'+v.videoHeight);});
    v.addEventListener('error',function(){AndroidDbg.log('VIDEO ERROR code='+(v.error&&v.error.code));});
    var n=0, prev=-1;
    var iv=setInterval(function(){
      n++;
      var adv=(v.currentTime>prev+0.01)?'OK':'FIGE'; prev=v.currentTime;
      AndroidDbg.log('t='+v.currentTime.toFixed(2)+' lecture='+adv+' rs='+v.readyState+' '+v.videoWidth+'x'+v.videoHeight);
      if(n>=10)clearInterval(iv);
    },1500);
  }
  attach();
})();
"""
    }
}