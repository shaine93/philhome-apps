#!/usr/bin/env bash
# Rapatrie le log de debug du téléphone vers DEBUG/sonnette-debug.log puis l'affiche.
# Usage : branche le téléphone en USB, puis :  bash DEBUG/pull-log.sh
set -e

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG="com.philhome.sonnettevideo"
REMOTE="/sdcard/Android/data/$PKG/files/debug/sonnette-debug.log"
HERE="$(cd "$(dirname "$0")" && pwd)"
LOCAL="$HERE/sonnette-debug.log"

echo "== appareils =="
"$ADB" devices

# Choisit un appareil EN LIGNE ('device'), en préférant la connexion sans-fil (TLS) si l'USB est 'offline'.
DEV="$("$ADB" devices | awk '$2=="device" && /_adb-tls-connect/ {print $1; exit}')"
[ -z "$DEV" ] && DEV="$("$ADB" devices | awk '$2=="device" {print $1; exit}')"
SEL=""; [ -n "$DEV" ] && SEL="-s $DEV"
echo "== appareil sélectionné : ${DEV:-(défaut)} =="

echo "== pull $REMOTE =="
if "$ADB" $SEL pull "$REMOTE" "$LOCAL" 2>/dev/null; then
  echo "---------- CONTENU ($LOCAL) ----------"
  cat "$LOCAL"
else
  echo "❌ Fichier introuvable. Ouvre l'app au moins une fois (DebugLog.init), refais un test, puis relance."
fi