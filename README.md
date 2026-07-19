# philhome-apps — dépôt global des apps Android de Philippe

Monorepo **cloisonné** : chaque app est indépendante dans son dossier, avec son propre build et sa
propre mise à jour **OTA** (par manifeste JSON + GitHub Release). Lisible/corrigeable par **Claude web**
et **Claude Code**.

## Apps

| App | Dossier | Rôle |
|---|---|---|
| **Sonnette Vidéo** | [`apps/sonnette-video/`](apps/sonnette-video/) | Interphone vidéo pour la mère de Philippe (sonnerie fiable, talk-back, détection livreur, galerie). Doc : `apps/sonnette-video/SONNETTE_ROBUSTESSE.md`, `DETECTION_LIVREUR.md`. |
| **Trading Claude GOD** | [`apps/trading-claude-god/`](apps/trading-claude-god/) | Simulateur boursier pédagogique (argent fictif) + analyse Claude. Doc : `apps/trading-claude-god/DOC_TRADING_CLAUDE_GOD.md`. |

## 🔐 Secrets — JAMAIS dans ce dépôt

Les secrets sont lus **au build** depuis des fichiers locaux (hors git), et compilés dans l'APK :

| Secret | Fichier local | Utilisé par |
|---|---|---|
| Token Home Assistant | `~/.ha_token` | Sonnette (`BuildConfig.HA_TOKEN`) |
| Clé API Anthropic | `~/.claude_api_key` | Trading (`BuildConfig.CLAUDE_API_KEY`) |

`google-services.json` (FCM) et `.claude/` sont gitignorés. Pour builder ailleurs, il faut re-fournir
ces fichiers locaux.

## 🔄 Mise à jour OTA (par app)

- Chaque app publie un **`version.json`** (dans ce dépôt) : `{ versionCode, versionName, apkUrl, notes }`.
- L'**APK** est jointe à une **GitHub Release** (hors git).
- L'**updater intégré** de l'app lit `version.json` → si version plus récente → télécharge l'APK → installe.
  Bouton « Mettre à jour » = OTA sans câble.

## Build (local)

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd apps/<app>
./gradlew :app:assembleDebug
```
