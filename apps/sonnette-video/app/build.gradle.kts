// app/build.gradle.kts — Sonnette Vidéo
// NOTE : vérifie/bump les versions dans Android Studio (sync Gradle signalera tout dépassement).
import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services") // nécessite google-services.json dans app/
}

android {
    namespace = "com.philhome.sonnettevideo"
    compileSdk = 34

    // Token HA long-lived lu depuis ~/.ha_token (JAMAIS dans le code / git). Compilé dans l'APK au build.
    val haToken = File(System.getProperty("user.home"), ".ha_token")
        .let { if (it.exists()) it.readText().trim() else "" }

    // Webhook IDs + identifiants RTSP lus depuis ~/.sonnette_video_secrets.properties
    // (JAMAIS dans le code / git — dépôt public). Compilés dans l'APK au build.
    val secretsFile = File(System.getProperty("user.home"), ".sonnette_video_secrets.properties")
    val secrets = Properties().apply {
        if (secretsFile.exists()) secretsFile.inputStream().use { load(it) }
    }
    fun secret(key: String) = secrets.getProperty(key, "")

    buildFeatures { buildConfig = true }

    defaultConfig {
        applicationId = "com.philhome.sonnettevideo"
        minSdk = 31            // Android 12 minimum (CallStyle + setCommunicationDevice)
        targetSdk = 34         // Android 14 (cible = Redmi Note 12 Pro 5G)
        // ⭐ SOURCE DE VÉRITÉ du versionning. À chaque publication : incrémenter versionCode (+1)
        // et versionName (semver), puis lancer HA/publish.sh (build + copie APK + génère le
        // manifeste sonnette-version.json depuis ces valeurs → l'updater intégré voit la MAJ).
        versionCode = 31
        versionName = "0.6.5"

        buildConfigField("String", "HA_TOKEN", "\"$haToken\"")
        buildConfigField("String", "GATE_WEBHOOK_ID", "\"${secret("GATE_WEBHOOK_ID")}\"")
        buildConfigField("String", "FCM_REGISTER_WEBHOOK_ID", "\"${secret("FCM_REGISTER_WEBHOOK_ID")}\"")
        buildConfigField("String", "CALL_EVENT_WEBHOOK_ID", "\"${secret("CALL_EVENT_WEBHOOK_ID")}\"")
        buildConfigField("String", "HEARTBEAT_WEBHOOK_ID", "\"${secret("HEARTBEAT_WEBHOOK_ID")}\"")
        buildConfigField("String", "LOG_WEBHOOK_ID", "\"${secret("LOG_WEBHOOK_ID")}\"")
        buildConfigField("String", "RTSP_USER", "\"${secret("RTSP_USER")}\"")
        buildConfigField("String", "RTSP_PASS", "\"${secret("RTSP_PASS")}\"")

        // Les 2 téléphones cibles sont arm64 → on ne package QUE cette ABI (libVLC bundle sinon
        // toutes les ABI → APK ~192 Mo). arm64 seul ≈ 50 Mo.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")          // NotificationCompat.CallStyle (>= 1.9.0)
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Firebase Cloud Messaging
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")

    // Ré-inscription périodique du token FCM (fiabilité : le token ne peut plus rester périmé).
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Talk-back relais 5G : WebSocket vers Home Assistant (l'app envoie l'AAC à HA).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // DNS-over-HTTPS : contourne le DNS du téléphone (AdGuard) qui résout mal duckdns en 5G.
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")

    // Vidéo EN DIRECT depuis la sonnette (RTSP LAN, SANS Home Assistant/go2rtc) : libVLC.
    // ExoPlayer/Media3 REFUSE la SDP du G400 (piste H264 sans ligne fmtp/sprop) ; libVLC — même
    // famille ffmpeg que l'ijkplayer d'Aqara — la tolère. Filtre anti-vent = égaliseur VLC (grave coupé).
    implementation("org.videolan.android:libvlc-all:3.6.0")
}
