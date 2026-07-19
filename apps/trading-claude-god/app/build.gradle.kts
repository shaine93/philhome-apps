// app/build.gradle.kts — Trading Claude GOD
// App de SIMULATION (argent fictif) : apprendre la bourse sans risque. Pas de conseil financier.
import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.philhome.tradingclaudegod"
    compileSdk = 34

    // Clé API Anthropic lue depuis ~/.claude_api_key (JAMAIS dans le code / git).
    // Compilée dans l'APK au build (choix assumé : app perso non distribuée + plafond 10 €/mois).
    val claudeKey = File(System.getProperty("user.home"), ".claude_api_key")
        .let { if (it.exists()) it.readText().trim() else "" }

    buildFeatures { buildConfig = true }

    defaultConfig {
        applicationId = "com.philhome.tradingclaudegod"
        minSdk = 26            // Android 8+ (icônes adaptatives, largement suffisant)
        targetSdk = 34
        // ⭐ SOURCE DE VÉRITÉ du versionning (comme la sonnette). Bump à chaque publication.
        versionCode = 10
        versionName = "0.9.1"

        buildConfigField("String", "CLAUDE_API_KEY", "\"$claudeKey\"")
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // Appels aux API de cours de bourse (données marché).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Vérification périodique en arrière-plan (alertes de chute).
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // Tirer vers le bas pour rafraîchir (pull-to-refresh).
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}
