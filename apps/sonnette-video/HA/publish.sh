#!/usr/bin/env bash
# Publie une nouvelle version de l'app Sonnette Vidéo.
#   1) build l'APK   2) la copie dans HA/ (servi par le Mac)   3) génère le manifeste de version.
# Source de vérité du versionning = app/build.gradle.kts (versionCode / versionName).
# Avant de publier : incrémente versionCode (+1) et versionName dans build.gradle.kts.
#
# Usage :  bash HA/publish.sh "Notes de version courtes"
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE="$ROOT/app/build.gradle.kts"
HA_DIR="$ROOT/HA"
NOTES="${1:-Mise a jour Sonnette Video}"

VCODE=$(grep -E 'versionCode = ' "$GRADLE" | grep -oE '[0-9]+' | head -1)
VNAME=$(grep -E 'versionName = ' "$GRADLE" | sed -E 's/.*"([^"]+)".*/\1/' | head -1)
echo "== Publication v$VNAME (code $VCODE) =="
echo "   notes: $NOTES"

export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
( cd "$ROOT" && ./gradlew assembleDebug -q )

cp "$ROOT/app/build/outputs/apk/debug/app-debug.apk" "$HA_DIR/sonnette-video.apk"
cat > "$HA_DIR/sonnette-version.json" <<JSON
{ "versionCode": $VCODE, "versionName": "$VNAME", "notes": "$NOTES" }
JSON

echo "== Généré =="
echo "   $HA_DIR/sonnette-video.apk ($(wc -c < "$HA_DIR/sonnette-video.apk") octets)"
echo -n "   sonnette-version.json : "; cat "$HA_DIR/sonnette-version.json"
echo ""

# ============================================================================
# Déploiement AUTOMATIQUE vers HA (plus de wget manuel).
# Pré-requis (à faire UNE SEULE FOIS) : un shell_command HA `sonnette_pull_apk`
# qui tire les 2 fichiers depuis ce Mac. Voir HA/README_DEPLOY.md.
# ============================================================================
HA_BASE="https://philhomeassist.duckdns.org"
# Token lu depuis ~/.ha_token (JAMAIS en dur / dans git).
HA_TOKEN="$(cat ~/.ha_token 2>/dev/null)"
MAC_IP="192.168.1.30"
PORT=8771

# 1) S'assurer que le Mac sert HA/ sur le LAN (démarre le serveur si besoin).
if ! curl -s -m 3 -o /dev/null "http://$MAC_IP:$PORT/sonnette-version.json"; then
  echo "== Démarrage du serveur HTTP local ($PORT) =="
  ( cd "$HA_DIR" && nohup python3 -m http.server "$PORT" >/tmp/sonnette_http$PORT.log 2>&1 & )
  sleep 1
fi

# 2) Demander à HA de tirer les 2 fichiers (aucun terminal HA requis).
echo "== HA récupère l'APK + le manifeste =="
curl -s -m 40 -X POST "$HA_BASE/api/services/shell_command/sonnette_pull_apk?return_response" \
  -H "Authorization: Bearer $HA_TOKEN" -H "Content-Type: application/json" -d '{}' \
  | python3 -c "import sys,json;
try:
    d=json.load(sys.stdin); r=d.get('service_response',{})
    print('   ', (r.get('stdout') or '').strip(), '(rc', r.get('returncode'), ')')
except Exception as e:
    print('   (pas de réponse JSON —', e, ')')" 2>/dev/null

# 3) Vérifier que HA sert bien la nouvelle version en /local.
served=$(curl -s -m 8 "$HA_BASE/local/sonnette-version.json")
if echo "$served" | grep -q "\"versionCode\": *$VCODE"; then
  echo "✅ v$VNAME (code $VCODE) déployée sur HA. Sur les téléphones : bouton « Mettre à jour l'app »."
else
  echo "⚠️  HA ne sert pas encore le code $VCODE."
  echo "    Reçu: $served"
  echo "    → Vérifie le shell_command 'sonnette_pull_apk' (cf HA/README_DEPLOY.md) et que le serveur $PORT tourne."
fi
