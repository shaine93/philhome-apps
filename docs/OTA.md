# OTA & déploiement — philhome-apps

Comment **corriger, builder et déployer** les apps, avec **mise à jour OTA** (sans câble), depuis ce
dépôt public. Pensé pour que **Claude web** (lecture/analyse) et **Claude Code** (build/deploy) travaillent
sur la même base.

## Principe

- **Code source** : dans ce dépôt (`apps/<app>/`), public, **sans aucun secret**.
- **Binaires APK** : dans les **GitHub Releases** (tags roulants `sonnette-latest`, `trading-latest`),
  PAS dans git.
- **Manifeste de version** : `apps/<app>/version.json` (servi en brut par GitHub) donne `versionCode`,
  `versionName`, `notes`, `apkUrl`.
- **Updater intégré** : chaque app lit son `version.json` → si `versionCode` distant > installé →
  télécharge `apkUrl` → lance l'installateur. Bouton **« Mettre à jour »**.

## URLs

| App | version.json (raw) | APK (Release) |
|---|---|---|
| Sonnette | `raw.githubusercontent.com/shaine93/philhome-apps/main/apps/sonnette-video/version.json` | `github.com/shaine93/philhome-apps/releases/download/sonnette-latest/sonnette-video.apk` |
| Trading | `.../apps/trading-claude-god/version.json` | `.../releases/download/trading-latest/trading-claude-god.apk` |

## 🔐 Secrets (JAMAIS dans git — lus au build)

| Secret | Fichier local (chmod 600) | BuildConfig |
|---|---|---|
| Token Home Assistant | `~/.ha_token` | `Config.HA_LONG_LIVED_TOKEN` (Sonnette) |
| Clé API Anthropic | `~/.claude_api_key` | `BuildConfig.CLAUDE_API_KEY` (Trading) |

`google-services.json`, `.claude/`, `*.apk` sont gitignorés. Pour builder ailleurs, re-fournir ces fichiers.

## Publier une nouvelle version (workflow type)

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd apps/<app>

# 1. bump versionCode (+1) + versionName dans app/build.gradle.kts
# 2. build
./gradlew :app:assembleDebug

# 3. publier l'APK sur la Release roulante (remplace l'asset)
cp app/build/outputs/apk/debug/app-debug.apk /tmp/<nom>.apk
gh release upload <app>-latest /tmp/<nom>.apk --clobber --repo shaine93/philhome-apps

# 4. mettre à jour apps/<app>/version.json (versionCode/versionName/notes) puis
git add -A && git commit -m "..." && git push
```
→ Les téléphones voient la maj au prochain tap sur « Mettre à jour ».

Noms d'asset attendus (doivent matcher `apkUrl` du version.json) :
`sonnette-video.apk` et `trading-claude-god.apk`.

## Créer les releases la 1ʳᵉ fois (assets initiaux)

```bash
gh release create sonnette-latest /tmp/sonnette-video.apk --repo shaine93/philhome-apps \
  -t "Sonnette Vidéo (dernière)" -n "APK OTA"
gh release create trading-latest /tmp/trading-claude-god.apk --repo shaine93/philhome-apps \
  -t "Trading Claude GOD (dernière)" -n "APK OTA"
```

## Transition (une fois)

- **Sonnette** : les téléphones tournaient sur un updater pointant Home Assistant. La **v0.6.0** (qui pointe
  GitHub) a été publiée **une dernière fois sur HA** → les téléphones tapent « Mettre à jour » pour y passer.
  Ensuite, tout est OTA GitHub.
- **Trading** : la v0.9.1 n'avait pas d'updater → installer la **v0.9.2** une fois manuellement
  (adb ou navigateur), ensuite l'OTA GitHub prend le relais.

## Historique des versions

Voir `apps/sonnette-video/SONNETTE_ROBUSTESSE.md` et `apps/trading-claude-god/DOC_TRADING_CLAUDE_GOD.md`.
