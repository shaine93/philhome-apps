#!/usr/bin/env bash
# Banc de test hors téléphone pour le filtre anti-vent (VoiceFilter).
# Compile VoiceFilter.kt + Biquad.kt DIRECTEMENT depuis le code de l'app (pas de copie —
# toujours exactement le même code que ce qui tourne sur les téléphones) + le pilote WAV.
#
# Usage : bash tools/dsp-bench/run.sh <entree.wav> <sortie.wav> [sensibilite]
#   entree.wav  : enregistrement réel (vent près de la sonnette, ou tentative de Larsen)
#   sortie.wav  : résultat après passage dans VoiceFilter — à écouter
#   sensibilite : 0.7 (léger) / 1.0 (normal, défaut) / 1.4 (fort) — voir Prefs.windSensitivity
#
# Pré-requis : kotlinc (fourni avec Android Studio, pas besoin d'installer quoi que ce soit
# de plus). Détecté automatiquement ci-dessous.
set -euo pipefail

# Pas de JDK système sur ce Mac (même contrainte que ./gradlew, voir README du projet) —
# réutilise le JBR fourni par Android Studio si JAVA_HOME n'est pas déjà positionné.
if [ -z "${JAVA_HOME:-}" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="$ROOT/app/src/main/java/com/philhome/sonnettevideo"
BUILD_DIR="$ROOT/tools/dsp-bench/.build"
JAR="$BUILD_DIR/wind-bench.jar"

if [ "$#" -lt 2 ]; then
  echo "Usage: $0 <entree.wav> <sortie.wav> [sensibilite=1.0]" >&2
  exit 1
fi

KOTLINC="$(command -v kotlinc || true)"
KOTLIN_RUN="$(command -v kotlin || true)"
if [ -z "$KOTLINC" ]; then
  KOTLINC="/Applications/Android Studio.app/Contents/plugins/Kotlin/kotlinc/bin/kotlinc"
  KOTLIN_RUN="/Applications/Android Studio.app/Contents/plugins/Kotlin/kotlinc/bin/kotlin"
fi
if [ ! -x "$KOTLINC" ]; then
  echo "kotlinc introuvable. Attendu dans le PATH ou fourni par Android Studio." >&2
  exit 1
fi

mkdir -p "$BUILD_DIR"

# Recompile seulement si le jar n'existe pas ou si les sources ont changé depuis.
if [ ! -f "$JAR" ] || [ "$SRC/VoiceFilter.kt" -nt "$JAR" ] || [ "$SRC/Biquad.kt" -nt "$JAR" ]; then
  echo "== Compilation (VoiceFilter.kt + Biquad.kt + pilote WAV) =="
  "$KOTLINC" \
    "$SRC/AqaraTalkProtocol.kt" \
    "$SRC/Biquad.kt" \
    "$SRC/VoiceFilter.kt" \
    "$ROOT/tools/dsp-bench/WindBenchMain.kt" \
    -include-runtime -d "$JAR"
fi

echo "== Exécution =="
"$KOTLIN_RUN" -cp "$JAR" WindBenchMainKt "$@"
