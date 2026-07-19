# Trading Claude GOD — documentation complète

> App Android de **simulation boursière pédagogique** pour Philippe, **débutant total, sans courtier**.
> Argent **fictif** (portefeuille virtuel), zéro risque réel. But : **apprendre** la bourse et les niveaux
> de risque, avec des cours **réels** en direct. **Ce n'est pas un conseil financier ; aucun ordre réel
> n'est passé.** Projet démarré le **2026-07-18**. État : **v0.8.0** (Claude analyste à finir).

---

## 1. Philosophie & garde-fous (non négociables)

- **100 % simulation.** Portefeuille virtuel de départ = **10 000 € fictifs**. On n'engage jamais d'argent réel.
- **Honnêteté d'expert.** Aucun outil ne « fait gagner de l'argent » ; le marché n'est pas prévisible.
  La valeur de l'app = **discipline + gestion du risque + structure**, pas des prédictions.
- **« Presque sans risque » → « risque plus faible ».** Rien n'est sans risque en bourse ; c'est écrit partout.
- **Profil visé** (choisi par Philippe) : investisseur prudent + swing (semaines→long terme), valeurs
  solides, **pas de spéculation intraday**.
- Chaque écran affiche un **avertissement** : outil éducatif, pas un conseil, risque de perte.

## 2. Repère technique

| | |
|---|---|
| Chemin projet | `~/AndroidStudioProjects/TradingClaudeGOD/` (séparé de la sonnette) |
| Package | `com.philhome.tradingclaudegod` |
| Toolchain | Gradle 8.11.1, JDK 17 (`Android Studio.app/.../jbr`), AGP 8.10.1, Kotlin 2.0.21 |
| SDK | minSdk **26**, targetSdk 34, compileSdk 34 |
| UI | 100 % **programmatique** (pas de XML de layout), thème sombre `Ui.kt` |
| Poids APK | ~6 Mo (pas de natif) |
| Version actuelle | **0.8.0** (versionCode 8) — source de vérité : `app/build.gradle.kts` |

## 3. Fichiers du code (`app/src/main/java/com/philhome/tradingclaudegod/`)

| Fichier | Rôle |
|---|---|
| `MainActivity.kt` | Écran d'accueil : portefeuille virtuel (valeur totale + P&L), liste de valeurs (cours live), positions détenues, bouton **Idées**, bouton **➕ Ajouter**, programmation des alertes + demande de permission notif. |
| `Ui.kt` | Palette (fond `#0B0E14`, vert hausse, rouge baisse, or accent) + fabriques de fonds arrondis. |
| `Assets.kt` | Sélection **par défaut** (LVMH, Air Liquide, TotalEnergies, Apple, Microsoft, ETF Monde CW8.PA, BTC-EUR, ETH-EUR, CAC40, S&P500). |
| `MarketApi.kt` | **Moteur données** Yahoo Finance : `fetch()` (prix+tendance+vol+historique), `fetchSeries()` (graphique), `search()` (ajout manuel), `toEur()` (change USD via `EURUSD=X`), `annualVolPct()` (volatilité annualisée). |
| `SparklineView.kt` | Mini-graphique (courbe des derniers cours), vert/rouge selon le mois. |
| `AssetDetailActivity.kt` | Détail d'une valeur : grand graphique + périodes (1sem/1mois/3mois/1an), zoom pincement (WebView), stats, **AVIS AUTOMATIQUE**, boutons **Acheter/Vendre** (fictif). |
| `Advisor.kt` | **Avis automatique** = lecture technique objective (tendance MM50/MM200, volatilité, position dans l'année, momentum) → synthèse honnête, **jamais « achète »**. |
| `PortfolioStore.kt` | **Portefeuille persistant** (filesDir/portfolio.json) : cash + positions (qty, coût €), `buy()`/`sell()`/`reset()`. |
| `Ideas.kt` + `IdeasActivity.kt` | **Idées par niveau de risque** (3 tiers) + « pourquoi » + **volatilité mesurée** par valeur. |
| `WatchlistStore.kt` | Valeurs ajoutées manuellement (filesDir/watchlist.json) : `add/remove/all`. |
| `AlertWorker.kt` | Tâche de fond (WorkManager 30 min) : **notification si chute** (−6 % jour ou position < −10 %). Anti-spam 12 h. |

## 4. Source de données — Yahoo Finance (gratuite, sans clé)

- Cours + historique : `GET https://query1.finance.yahoo.com/v8/finance/chart/<symbole>?interval=1d&range=<1y|1mo|5d…>`
  (header `User-Agent` navigateur obligatoire). On lit `meta.regularMarketPrice`, `meta.previousClose`,
  `meta.currency`, et le tableau `indicators.quote[0].close` (pour MM50/MM200, volatilité, graphique).
- Recherche (ajout manuel) : `GET /v1/finance/search?q=<nom>` → symbole + nom + type.
- **Change €** : `EURUSD=X` (cache 1 h) pour convertir les valeurs US en euros ; le portefeuille est **compté
  en euros**.
- Tickers : actions FR = `.PA` (Paris, EUR), US = symbole nu (AAPL), ETF EUR = `CW8.PA`/`ESE.PA`/`IWDA.AS`,
  crypto = `BTC-EUR`/`ETH-EUR`, indices = `^FCHI`/`^GSPC`.

## 5. Logique métier

### Tendance de fond (`MarketApi.Quote.trend`)
- HAUSSE si prix > MM50 ≥ MM200 · BAISSE si prix < MM50 ≤ MM200 · sinon NEUTRE (INCONNU si < 200 clôtures).

### Avis automatique (`Advisor.analyse`)
Combine : tendance (au-dessus/sous MM200), **volatilité annualisée** (`stdev(rendements)·√252`), **position
dans la fourchette 52 sem.** (proche du haut = cher / du bas = décoté), **momentum 1 mois**. Rend une
synthèse colorée (ex. *« Contexte plutôt favorable — reste discipliné sur la taille et le stop »*,
*« déjà cher à court terme — patience »*, *« tendance mal orientée — prudence »*). Toujours suivi de
*« Lecture technique automatique — pas un conseil ni une prédiction »*.

### Gestion du risque (à l'achat, `AssetDetailActivity.buyDialog`)
- Calcule **parts** + **% du portefeuille** ; **alerte si > 20 %** sur une seule ligne.
- Propose un **stop de protection −8 %** (niveau de prix).

### Volatilité = risque objectif (`Ideas`)
- Repères : < 18 % faible · 18–35 % modérée · 35–70 % élevée · > 70 % très élevée. Affichée par valeur.

### Idées par risque (`Ideas.kt`)
- 🟢 **Faible** : ETF larges (Monde, S&P 500) — diversification = base débutant.
- 🟡 **Moyen** : grandes actions (LVMH, Air Liquide, Apple, Microsoft) — solides mais une seule société.
- 🔴 **Élevé** : Bitcoin, Ethereum, Tesla, Nvidia — très volatil, petite part seulement.

### Portefeuille (`PortfolioStore`)
- Tout en **€**. Achat : `qty = montant€ / prix€`, `cash -= montant`, `costEur += montant`.
- Vente : proportionnelle (qty et coût réduits au prorata), `cash += produit`. « Tout vendre » = solde la ligne.
- P&L position = valeur actuelle (qty × prix€) − coût investi.

### Alertes de chute (`AlertWorker`)
- WorkManager périodique (~30 min). Pour chaque position : notif si **variation jour ≤ −6 %** ou **P&L ≤ −10 %**.
- Anti-spam 12 h (SharedPreferences `alerts`). Permission `POST_NOTIFICATIONS` demandée au lancement.
- Limite connue : en arrière-plan, Android impose ~15 min mini et MIUI peut retarder (OK pour simulation).

## 6. Build & déploiement

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd ~/AndroidStudioProjects/TradingClaudeGOD
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- ⚠️ **1ʳᵉ installation d'une app NEUVE bloquée par MIUI** (`INSTALL_FAILED_USER_RESTRICTED`). Solutions :
  accepter le popup MIUI sur le téléphone, **ou** télécharger via navigateur :
  `http://192.168.1.30:8771/TradingClaudeGOD.apk` (le Mac sert le dossier `HA/` de la sonnette sur :8771).
  Ensuite les mises à jour `adb install -r` passent normalement.
- L'APK est aussi copiée dans `…/Sonnettevideo/HA/TradingClaudeGOD.apk` (backup téléchargeable).
- Dev phone : `22081212UG` (adb `c614e0cc`).

## 7. Historique des versions

| Version | Apport |
|---|---|
| 0.1.0 | Squelette (écran d'accueil, portefeuille 10 000 € fictif) — prouve build+install |
| 0.2.0 | Liste de valeurs + **cours en direct** (Yahoo) + tendance |
| 0.3.0 | Historique **7j/30j** + **mini-graphique** par valeur |
| 0.4.0 | Écran **détail** : grand graphique + périodes + **zoom** |
| 0.5.0 | **Achat/vente fictif** + portefeuille dynamique + gestion du risque (taille + stop) |
| 0.6.0 | **Idées par niveau de risque** (3 tiers) + volatilité mesurée |
| 0.7.0 | **Ajout manuel** de valeurs (recherche) + **AVIS automatique** sur le détail |
| 0.8.0 | **Alertes de chute** en notification (WorkManager) |
| 0.9.0 | **🤖 Claude analyste** (API Anthropic, clé dans l'app + plafond 10 €/mois) |

## 8. Claude analyste (🤖 FAIT — v0.9.0)

- **Décision** : la clé Anthropic est **dans l'app** (pas de serveur OVH), **plafond 10 €/mois** réglé par
  Philippe dans la console Anthropic. (Option A « service isolé sur OVH » écartée : `lolufe` n'a pas de vrai
  sudo — seulement restart/status de 3 services — et le seedbox est déjà chargé.)
- **Clé (jamais dans git)** : rangée dans `~/.claude_api_key` (chmod 600). Le build la lit et l'injecte :
  ```kotlin
  // app/build.gradle.kts
  val claudeKey = File(System.getProperty("user.home"), ".claude_api_key")
      .let { if (it.exists()) it.readText().trim() else "" }
  buildFeatures { buildConfig = true }
  defaultConfig { buildConfigField("String", "CLAUDE_API_KEY", "\"$claudeKey\"") }
  ```
- **`ClaudeApi.kt`** : `POST https://api.anthropic.com/v1/messages`, en-têtes `x-api-key` +
  `anthropic-version: 2023-06-01`, modèle **`claude-haiku-4-5-20251001`** (éco), `max_tokens=700`.
- **`AssetDetailActivity`** : bouton **« 🤖 Demander l'analyse de Claude »** → `buildPrompt()` assemble les
  faits (prix, tendance MM200, volatilité, position 52 sem., 7j/30j) → Claude rédige une analyse débutant
  (5-8 phrases). **Prompt bridé** : interdit achat/vente/prédiction ; finit par « Ceci n'est pas un conseil
  financier. » Coût ~fraction de centime/analyse.

## 9. À FAIRE / prochaines pistes
- Ajuster le **ton** de l'analyse Claude selon le retour de Philippe.
- Résumé **hebdomadaire** de Claude sur tout le portefeuille (notification).
- Écran **« Comprendre »** (lexique débutant).
- Réglages : seuils d'alerte, montant de départ, thème.
- Journal des trades fictifs + statistiques.

## 10. Dépôt git global + OTA (à mettre en place — voir plan en cours)
Objectif Philippe : **un dépôt git global** contenant **toutes les apps cloisonnées** (Sonnette, Trading…),
lisible/corrigeable par **Claude web ET Claude Code**, avec **mise à jour OTA**. Architecture proposée
(à valider) : voir la section dédiée ci-dessous une fois arrêtée. Principe pressenti :
- 1 repo GitHub, 1 dossier par app (`apps/sonnette-video/`, `apps/trading-claude-god/`) = cloisonnement.
- APK livrées via **GitHub Releases** (binaires hors git) + manifeste de version par app.
- Updater in-app (déjà présent sur la sonnette : `AppUpdater.kt`) pointant sur le manifeste GitHub →
  bouton « Mettre à jour » = OTA sans câble.

### Pistes ultérieures (non engagées)
- Résumé **hebdomadaire** de Claude sur tout le portefeuille (push).
- Réglages : seuils d'alerte configurables, montant de départ, thème.
- Écran **« Comprendre »** (lexique débutant : action, ETF, tendance, stop, volatilité…).
- Journal des trades fictifs (historique + statistiques pour progresser).

## 9. Notes d'accès serveur (acquis cette session, plus requis pour l'option B)

- OVH `deploy_server` joignable via tunnel Cloudflare (URL sur ntfy `assistantia-deploy-8501-secret`),
  secret dans **`~/.deploy_secret`** (chmod 600 sur le Mac). **SSH = port 6969** (pas 22). `lolufe` : sudo
  NOPASSWD limité (restart/status assistant/deploy_server/cloudflared). Serveur = **swizzin seedbox**
  (nginx 443, rtorrent, filebrowser…). Détail procédure : voir la mémoire `acces-ovh-ha`.

---
*Le nom « Trading Claude GOD » est le choix de Philippe. « Claude » étant une marque Anthropic, à renommer
si un jour l'app était publiée sur un store — sans objet en usage personnel non distribué.*
