package com.philhome.sonnettevideo

/**
 * Configuration centrale Sonnette Vidéo.
 * Les valeurs marquées TODO sont à fixer quand l'infra côté HA/VM est en place.
 */
object Config {
    // URL externe de Home Assistant (fonctionne en WiFi via NAT loopback + en 5G)
    const val HA_BASE_URL = "https://philhomeassist.duckdns.org"

    // Webhook HA dédié "ouvrir le portail" — impulsion START (relais eWeLink).
    // TODO : créer ce webhook côté HA et reporter l'ID aléatoire ici.
    const val GATE_WEBHOOK_ID = "sv_portail_d20fa0aac3413c266940a3f0"

    // Webhook HA d'enregistrement du token FCM de cette app (la VM lira ce token pour pousser).
    // TODO : créer ce webhook côté HA et reporter l'ID ici.
    const val FCM_REGISTER_WEBHOOK_ID = "sv_register_c367889464589909d360a941"

    // Battement de cœur : le service permanent ping ce webhook toutes les 10 min. Le
    // `last_triggered` de l'automatisation HA = « dernière fois où le téléphone était vivant ».
    // Si le ping s'arrête, on sait à la minute près quand l'app est morte (diagnostic MIUI).
    const val HEARTBEAT_WEBHOOK_ID = "sv_heartbeat_9f3b71c0a2e84d55"
    fun heartbeatUrl() = "$HA_BASE_URL/api/webhook/$HEARTBEAT_WEBHOOK_ID"

    // Log distant : l'app pousse ses lignes importantes vers HA → lecture des logs des DEUX
    // téléphones SANS câble USB (via le logbook HA, tagué par modèle d'appareil).
    const val LOG_WEBHOOK_ID = "sv_log_4c1e9a7b26f0d833"
    fun logUrl() = "$HA_BASE_URL/api/webhook/$LOG_WEBHOOK_ID"

    // Caméra de la sonnette (pour info / fallback ; l'image arrive normalement dans le push).
    const val CAMERA_ENTITY = "camera.doorbell_repeater_74a8"

    // Talk-back : IP LAN de la sonnette Aqara G400 (ports contrôle 54324 / audio 54323).
    // TODO : rendre découvrable (mDNS/HA) plutôt qu'en dur si l'IP peut changer.
    const val DOORBELL_IP = "192.168.1.38"

    // Identifiants RTSP « LAN Preview » de la sonnette (saisis dans l'app Aqara), pour la VIDÉO EN
    // DIRECT sans HA : rtsp://<user>:<pass>@<ip>:8554/ch1 (ch1=1200p, ch2=960p, ch3=480p).
    const val RTSP_USER = "697"
    const val RTSP_PASS = "363"
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
    const val APK_URL = "$HA_BASE_URL/local/sonnette-video.apk"
    const val APK_VERSION_URL = "$HA_BASE_URL/local/sonnette-version.json"

    // Coordination multi-appareils : quand un téléphone décroche, il le signale à HA, qui pousse
    // un "cancel" (même call_id) aux AUTRES téléphones → leur écran/sonnerie s'arrête.
    // TODO : créer ce webhook côté HA + l'automatisation de relais (voir doc projet).
    const val CALL_EVENT_WEBHOOK_ID = "sv_call_event_a7f3c1e9b85d4206"
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