package com.philhome.sonnettevideo

/**
 * Configuration centrale Sonnette Vidéo.
 * Les valeurs marquées TODO sont à fixer quand l'infra côté HA/VM est en place.
 */
object Config {
    // URL externe de Home Assistant (fonctionne en WiFi via NAT loopback + en 5G)
    const val HA_BASE_URL = "https://philhomeassist.duckdns.org"

    // Webhook HA dédié "ouvrir le portail" — impulsion START (relais eWeLink).
    // Lu depuis ~/.sonnette_video_secrets.properties au build (BuildConfig) — JAMAIS en dur
    // dans le code / git (dépôt public : un ancien ID committé en clair a été révoqué et régénéré
    // côté HA le 2026-08-21 après exposition publique).
    val GATE_WEBHOOK_ID: String = BuildConfig.GATE_WEBHOOK_ID

    // Webhook HA d'enregistrement du token FCM de cette app (la VM lira ce token pour pousser).
    val FCM_REGISTER_WEBHOOK_ID: String = BuildConfig.FCM_REGISTER_WEBHOOK_ID

    // Battement de cœur : le service permanent ping ce webhook toutes les 10 min. Le
    // `last_triggered` de l'automatisation HA = « dernière fois où le téléphone était vivant ».
    // Si le ping s'arrête, on sait à la minute près quand l'app est morte (diagnostic MIUI).
    val HEARTBEAT_WEBHOOK_ID: String = BuildConfig.HEARTBEAT_WEBHOOK_ID
    fun heartbeatUrl() = "$HA_BASE_URL/api/webhook/$HEARTBEAT_WEBHOOK_ID"

    // Log distant : l'app pousse ses lignes importantes vers HA → lecture des logs des DEUX
    // téléphones SANS câble USB (via le logbook HA, tagué par modèle d'appareil).
    val LOG_WEBHOOK_ID: String = BuildConfig.LOG_WEBHOOK_ID
    fun logUrl() = "$HA_BASE_URL/api/webhook/$LOG_WEBHOOK_ID"

    // Caméra de la sonnette (pour info / fallback ; l'image arrive normalement dans le push).
    const val CAMERA_ENTITY = "camera.doorbell_repeater_74a8"

    // Talk-back : IP LAN de la sonnette Aqara G400 (ports contrôle 54324 / audio 54323).
    // IP résolue dynamiquement depuis HA — voir [DoorbellIp]. Ne plus utiliser cette constante
    // directement (gardée en interne par [DoorbellIp] comme repli avant le premier rafraîchissement).

    // Identifiants RTSP « LAN Preview » de la sonnette (saisis dans l'app Aqara), pour la VIDÉO EN
    // DIRECT sans HA : rtsp://<user>:<pass>@<ip>:8554/ch1 (ch1=1200p, ch2=960p, ch3=480p).
    // Lu depuis ~/.sonnette_video_secrets.properties au build — JAMAIS en dur dans le code / git.
    val RTSP_USER: String = BuildConfig.RTSP_USER
    val RTSP_PASS: String = BuildConfig.RTSP_PASS
    const val RTSP_CONTROL_PORT = 54324   // port contrôle talk = sonde « sonnette joignable en LAN »

    // Talk-back RELAIS via HA (marche en 5G ET en WiFi). L'app envoie l'AAC en WebSocket à HA,
    // qui le pousse à la sonnette sur le LAN. Connexion sortante → pas de serveur TURN.
    // OkHttp s'occupe de la mise à niveau https→wss.
    const val HA_TALK_WS_BASE = "$HA_BASE_URL/api/aqara_talk"   // + "/<doorbell_ip>"
    fun talkWsUrl(doorbellIp: String) = "$HA_TALK_WS_BASE/$doorbellIp"

    // Jeton longue durée Home Assistant (Profil → Jetons d'accès longue durée).
    // REQUIS pour le relais. TODO : coller le jeton ici (ou mieux : le stocker hors-source).
    // Lu depuis ~/.ha_token au build (BuildConfig) — JAMAIS en dur dans le code / git.
    val HA_LONG_LIVED_TOKEN: String = BuildConfig.HA_TOKEN

    // Canal de notification de l'appel entrant (importance haute, plein écran).
    // v3 = canal SILENCIEUX : le son + la vibration de la sonnerie sont désormais joués UNIQUEMENT
    // par IncomingCallActivity (MediaPlayer + Vibrator), pour n'avoir QU'UNE source de son
    // (fini les 2 sonneries décalées) et pour que « Couper le son » l'arrête vraiment.
    // (Un canal est immuable après création → changer le son impose un nouvel ID : v2 → v3.)
    const val INCOMING_CHANNEL_ID = "sonnette_incoming_v3"
    const val INCOMING_NOTIF_ID = 4101

    // Canal "Présence / Livreur" : notif DOUCE (silencieuse, PAS plein écran) quand l'IA détecte
    // une personne/un livreur qui s'approche SANS sonner. Distinct du canal d'appel (pas de sonnerie).
    // Le push correspondant a type="motion" (image_url = snapshot HA /local/…, label/desc = verdict IA).
    const val MOTION_CHANNEL_ID = "sonnette_presence_v2"   // v2 = son « dong » (v1 était silencieux)
    const val MOTION_NOTIF_ID = 4201

    fun gateWebhookUrl() = "$HA_BASE_URL/api/webhook/$GATE_WEBHOOK_ID"
    fun fcmRegisterUrl() = "$HA_BASE_URL/api/webhook/$FCM_REGISTER_WEBHOOK_ID"

    // Mise à jour intégrée (« Mettre à jour l'app ») : l'APK + un manifeste de version JSON sont
    // servis par HA via /local (public, sans jeton). Le manifeste porte le versionning :
    //   { "versionCode": 2, "versionName": "0.2.0", "notes": "…" }
    // L'app compare versionCode au sien (PackageManager) → propose la MAJ si plus récent.
    // OTA via le dépôt public GitHub : le manifeste (raw) donne versionCode + apkUrl (GitHub Release).
    const val APK_VERSION_URL =
        "https://raw.githubusercontent.com/shaine93/philhome-apps/main/apps/sonnette-video/version.json"
    const val APK_URL =
        "https://github.com/shaine93/philhome-apps/releases/download/sonnette-latest/sonnette-video.apk"

    // Coordination multi-appareils : quand un téléphone décroche, il le signale à HA, qui pousse
    // un "cancel" (même call_id) aux AUTRES téléphones → leur écran/sonnerie s'arrête.
    val CALL_EVENT_WEBHOOK_ID: String = BuildConfig.CALL_EVENT_WEBHOOK_ID
    fun callEventUrl() = "$HA_BASE_URL/api/webhook/$CALL_EVENT_WEBHOOK_ID"

    // Flux vidéo live de la sonnette (MJPEG via HA, marche en 5G avec le jeton longue durée).
    fun cameraStreamUrl() = "$HA_BASE_URL/api/camera_proxy_stream/$CAMERA_ENTITY"

    // Instantané (1 image) de la sonnette — affiché tout de suite à la place d'un écran noir
    // pendant que le flux vidéo WebRTC se connecte (~1-2 s).
    fun cameraSnapshotUrl() = "$HA_BASE_URL/api/camera_proxy/$CAMERA_ENTITY"

    // Vidéo via embed AlexxIT/go2rtc. mode = "webrtc" (fluide+natif, IPv6 sans TURN) ou "mse" (KO WebView).
    // Liens créés côté HA : door_lo (ch3 480p), door_mid (ch2 960p), door_hi (ch1 1200p).
    fun webrtcUrl(link: String, mode: String, media: String = "video") =
        "$HA_BASE_URL/webrtc/embed?url=$link&media=$media&mode=$mode"
    const val CAM_LINK_LO = "door_lo"
    const val CAM_LINK_MID = "door_mid"
    const val CAM_LINK_HI = "door_hi"
}