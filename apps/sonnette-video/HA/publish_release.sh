#!/usr/bin/env bash
# Publication OTA réelle (celle que l'app vérifie via Config.APK_VERSION_URL / APK_URL) —
# distincte de publish.sh, qui ne fait que la copie locale vers HA (mécanisme séparé).
#
# GESTE ATOMIQUE, dans cet ordre précis, jamais inversé :
#   1) build + vérifications (taille, checksum) — RIEN n'est encore visible d'aucun téléphone
#   2) upload de l'APK sur la release GitHub, avec le nom final dès le départ (pas de
#      renommage après coup — c'est exactement la séquence qui a cassé un téléphone le
#      2026-08-28 : un tél. a tapé "Mettre à jour" pendant qu'un asset mal nommé était
#      supprimé puis réuploadé)
#   3) SEULEMENT APRÈS que l'asset soit confirmé en place : version.json est poussé sur
#      main — c'est ce fichier que l'app lit pour savoir qu'une mise à jour existe, donc
#      c'est le "top départ" et il doit être la toute dernière chose écrite.
#
# Déploiement échelonné : ce script ne fait QUE publier sur GitHub. Le téléphone de maman
# (22101316G) ne doit JAMAIS taper "Mettre à jour" en premier — vérifier manuellement sur le
# téléphone de test (adb install -r du même APK, ou "Mettre à jour l'app" dans l'app) AVANT de
# dire à Philippe que c'est bon pour le téléphone de maman.
#
# Usage : bash HA/publish_release.sh "Notes de version courtes"
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE="$ROOT/app/build.gradle.kts"
REPO="shaine93/philhome-apps"
RELEASE_TAG="sonnette-latest"
ASSET_NAME="sonnette-video.apk"
NOTES="${1:?Usage: bash HA/publish_release.sh \"Notes de version\"}"

if [ -z "${JAVA_HOME:-}" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi

VCODE=$(grep -E 'versionCode = ' "$GRADLE" | grep -oE '[0-9]+' | head -1)
VNAME=$(grep -E 'versionName = ' "$GRADLE" | sed -E 's/.*"([^"]+)".*/\1/' | head -1)
echo "== Publication v$VNAME (code $VCODE) =="
echo "   notes: $NOTES"
echo "   (versionCode/versionName lus depuis build.gradle.kts — incrémente-les AVANT de lancer ce script)"

# ---------------------------------------------------------------------------
# 1) Build + vérifications — rien de visible pour un téléphone à ce stade.
# ---------------------------------------------------------------------------
echo ""
echo "== [1/3] Build =="
( cd "$ROOT" && ./gradlew assembleDebug -q )

APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || { echo "ERREUR: APK introuvable après build ($APK)" >&2; exit 1; }

APK_SIZE=$(wc -c < "$APK" | tr -d ' ')
APK_SHA=$(shasum -a 256 "$APK" | cut -d' ' -f1)
echo "   APK: $APK_SIZE octets, sha256=$APK_SHA"

# Garde-fou basique : un APK de sonnette-video fait ~55-70 Mo (libVLC + Firebase inclus).
# Un fichier bien plus petit = téléchargement/build tronqué, ne PAS publier ça.
if [ "$APK_SIZE" -lt 30000000 ]; then
  echo "ERREUR: APK anormalement petit ($APK_SIZE octets, attendu >30 Mo) — build probablement cassé. Publication annulée." >&2
  exit 1
fi

# Le fichier LOCAL porte déjà le nom final (pas de préfixe point/staged, pas de suffixe
# "#label" côté gh) — un essai précédent avec la syntaxe "chemin#label" a produit un asset
# nommé "default.staged-sonnette-video.apk" au lieu de "sonnette-video.apk" (mal interprétée
# par gh, en particulier avec un nom de fichier local commençant par un point). Un fichier
# local déjà nommé correctement, uploadé sans suffixe, est la seule méthode vérifiée fiable.
STAGED_DIR=$(mktemp -d)
STAGED="$STAGED_DIR/$ASSET_NAME"
cp "$APK" "$STAGED"

# ---------------------------------------------------------------------------
# 2) Upload de l'asset — UNE seule commande, nom final dès le départ.
# ---------------------------------------------------------------------------
echo ""
echo "== [2/3] Upload de l'APK sur la release GitHub ($RELEASE_TAG) =="
# --clobber remplace l'asset existant en une seule opération atomique côté GitHub — jamais de
# suppression puis réupload en deux temps (fenêtre d'incohérence exploitable par un téléphone
# qui vérifie une mise à jour au mauvais moment).
gh release upload "$RELEASE_TAG" "$STAGED" --repo "$REPO" --clobber
rm -rf "$STAGED_DIR"

# Vérification post-upload : re-télécharger le sha256 distant et comparer, avant de toucher
# à version.json. Si ça ne correspond pas, on s'arrête — mieux vaut aucune mise à jour visible
# qu'une mise à jour cassée visible.
echo "   Vérification de l'asset publié…"
# Query de cache-busting nécessaire : un essai précédent a montré une réponse mise en cache
# (probablement un cache intermédiaire sur le chemin réseau) renvoyant le sha256 de l'ANCIEN
# asset juste après un --clobber réussi, faisant échouer cette vérification à tort.
REMOTE_SHA=$(curl -sL -H "Cache-Control: no-cache" "https://github.com/$REPO/releases/download/$RELEASE_TAG/$ASSET_NAME?cachebust=$(date +%s)" | shasum -a 256 | cut -d' ' -f1)
if [ "$REMOTE_SHA" != "$APK_SHA" ]; then
  echo "ERREUR: le sha256 distant ($REMOTE_SHA) ne correspond pas au build local ($APK_SHA)." >&2
  echo "        version.json N'A PAS été touché — aucun téléphone ne verra cette publication." >&2
  exit 1
fi
echo "   OK — asset confirmé identique au build local."

# ---------------------------------------------------------------------------
# 3) version.json en dernier — c'est le signal que les téléphones vont voir.
# ---------------------------------------------------------------------------
echo ""
echo "== [3/3] Mise à jour de version.json + commit + push =="
cat > "$ROOT/version.json" <<JSON
{
  "versionCode": $VCODE,
  "versionName": "$VNAME",
  "notes": "$NOTES",
  "apkUrl": "https://github.com/$REPO/releases/download/$RELEASE_TAG/$ASSET_NAME"
}
JSON

cd "$ROOT/../.."   # racine du monorepo
git add apps/sonnette-video/version.json
git commit -m "Sonnette: publication v$VNAME (code $VCODE) — $NOTES"
git push origin main

echo ""
echo "== Publié =="
echo "   v$VNAME (code $VCODE) est maintenant visible par 'Mettre à jour l'app'."
echo "   RAPPEL : vérifier sur le téléphone de TEST d'abord. Ne rien dire à propos du"
echo "   téléphone de maman tant que ce n'est pas confirmé bon là-bas."
