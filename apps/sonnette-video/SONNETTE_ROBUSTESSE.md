# Sonnette — Robustesse « l'app ne doit JAMAIS mourir » (v0.5.1)

> **Session 2026-07-15.** Correctifs de fond : l'app Android cessait de sonner et de recevoir les
> alertes livreur parce que **son processus mourait en arrière-plan** (MIUI). Cette doc décrit la
> cause racine (prouvée), les correctifs déployés, comment lire les logs des **deux téléphones sans
> câble**, le diagnostic de la latence 5G, et le test réel prévu le **vendredi 2026-07-17**.
>
> APK : **v0.5.1 (versionCode 17)**. Détection livreur v2 : voir `DETECTION_LIVREUR.md` §9.

---

## 1. Le symptôme

- Le téléphone **ne sonnait plus** de façon fiable, et le **mode livreur ne notifiait pas**.
- Philippe (à juste titre) : « *c'est pas MIUI, c'est un problème de code — WhatsApp, Telegram, Aqara,
  Teams ne meurent JAMAIS* ».

## 2. La cause racine (PROUVÉE, pas supposée)

Diagnostic via `adb` sur le téléphone de test (Redmi 12T Pro, modèle `22081212UG`, série `c614e0cc`) :

1. **Le processus de l'app était MORT** (`ps -A | grep` → aucun processus).
2. **Le log du téléphone était figé depuis 2 jours** : dernière ligne `07-13 16:03`, puis plus rien
   (le rafraîchissement de token tournait toutes les 15 min *jusqu'à* 16:03, puis silence total).
3. **Les réglages système étaient pourtant tous bons** :
   - `appops RUN_ANY_IN_BACKGROUND: allow` (arrière-plan autorisé),
   - batterie **exemptée de Doze** (`dumpsys deviceidle whitelist` contient l'app),
   - **Autostart MIUI déjà activé** (confirmé par Philippe, « déjà fait 100 fois »),
   - `stopped=false` (pas de force-stop).

**Conclusion** : sans **service de premier plan permanent**, le processus est classé « en cache » par
Android → **premier tué** sous pression mémoire ; sur MIUI, une fois mort, le système **empêche FCM de
le relancer**. C'est **corrigeable par le code** — il manquait le service permanent.

### Pourquoi WhatsApp/Aqara ne meurent pas
- **WhatsApp, Facebook, Teams…** : Xiaomi les met **d'office dans sa liste blanche** MIUI + service
  permanent. Notre app sideloadée n'a pas ce passe-droit automatique.
- **Aqara** : partenaire Xiaomi → utilise **MiPush** (push natif système, impossible à tuer).
- **Notre réponse** : on **compense** — service permanent (comme elles) + autostart/batterie (déjà
  faits par Philippe) + heartbeat de contrôle.

## 3. Le correctif — `KeepAliveService`

Fichier : `app/src/main/java/com/philhome/sonnettevideo/KeepAliveService.kt`.

Service de premier plan **permanent (24/7)** : notif « ongoing » discrète (canal
`sonnette_keepalive`, importance MIN, sans son ni vibration) qui garde le **processus toujours vivant**
→ le push FCM est **toujours** livré. Type FGS Android 14 = `specialUse` (permission
`FOREGROUND_SERVICE_SPECIAL_USE`, sous-type `doorbell_keepalive_push`).

**Couches de survie (« ceinture + bretelles ») :**
| Mécanisme | Rôle |
|---|---|
| `startForeground(specialUse)` | processus non « en cache » → MIUI ne le recycle plus |
| `START_STICKY` | le système relance le service s'il est tué |
| `onTaskRemoved` → AlarmManager +1,5 s | relance si l'app est **balayée** des récentes |
| `scheduleBackupAlarm` (inexact, 15 min) | ré-arme le service si START_STICKY échoue |
| `BootReceiver` → `KeepAliveService.start` | relance au **redémarrage** du téléphone |
| `SonnetteMessagingService` → `start` à chaque push | **auto-réparation** : réveillé par un push = on redevient permanent |

Démarré aussi à l'ouverture de l'app (`MainActivity.onCreate`).

> ⚠️ **Aucune app Android n'est garantie immortelle à 100 %** (même WhatsApp tombe sur les MIUI les
> plus agressifs si force-stop). La vraie garantie « ne jamais RATER » = les couches ci-dessus **+**
> le heartbeat/alerte ci-dessous : si ça meurt quand même, on est prévenu en minutes.

## 4. Le battement de cœur (heartbeat) — « ce qui donne la continuité »

Le service ping HA **toutes les 10 min** (thread daemon dans `KeepAliveService`), POST vers le webhook
`sv_heartbeat_9f3b71c0a2e84d55`.

- Automatisation HA : **`automation.sonnette_heartbeat_telephone_app_vivante`** — son
  **`last_triggered` = « dernière fois où le téléphone était vivant »**. Journalise device + uptime.
- **Chien de garde** : `automation.sonnette_heartbeat_watchdog` — si pas de heartbeat depuis
  **> 30 min** → **notif persistante + SMS Free** (« app morte, dernière vie à telle heure »).
  → Si l'app remeurt malgré tout, on connaît **l'heure exacte** et on est alerté (fini le diagnostic
  à l'aveugle).

## 5. Lire les logs des DEUX téléphones SANS câble USB

`DebugLog.push(tag, msg)` journalise **en local** ET **remonte la ligne à HA** (webhook
`sv_log_4c1e9a7b26f0d833`, fire-and-forget). Câblé sur les événements importants : **FCM reçu**,
**décision RingPlayer** (sonnerie normale vs mode réunion), **vie du service**, **heartbeat**.

- Automatisation HA : `automation.sonnette_log_distant` → logbook, entrée **`LOG <modèle>`**.
- **Lecture** : interroger le logbook HA et filtrer les entrées dont le `name` commence par `LOG `.
  Chaque ligne est taguée par appareil (`22081212UG` = Philippe, `22101316G` = 2ᵉ tél).

Exemple réel capté pendant les tests :
```
17:08:22  LOG 22101316G  [RingPlayer] SONNERIE reçue → sonnerie normale (son + vibration)
17:08:23  LOG 22081212UG [RingPlayer] SONNERIE reçue → MODE RÉUNION : silencieux + vibration seule
17:08:23  LOG 22081212UG [FCM] push reçu type=ring
```

## 6. Preuve terrain (tests du 15/07)

- **2 appuis sonnette** → **les deux téléphones reçoivent** le push (WiFi : < 1 s). L'app est vivante
  et reçoit → le correctif « app morte » est **validé en réel**.
- **Mode réunion** testé dans les deux sens → **fonctionne** (silencieux + vibration).

## 7. Mode réunion — attention sécurité

`Prefs.meeting_mode_enabled` (bouton dans l'app). Activé = **vibration sans son** (discret en réunion).
Lu par `RingPlayer` au moment de sonner.
> ⚠️ **Le téléphone de maman doit rester en « sonnerie normale »** (mode réunion **désactivé**) — c'est
> le téléphone de sécurité, il doit sonner fort. Ne mettre le mode réunion que sur le tél de Philippe.

## 8. Latence 5G — diagnostic (ce n'est PAS le code)

Test réel : les deux tél au même endroit ; en **5G FREE**, maman (WiFi) sonne **immédiatement**,
Philippe (5G) après **~7 s**. Retrouvé dans les logs : ring reçu à `17:10:02` (maman) vs `17:10:16`
(Philippe, +14 s).

- Le retard est dans la **livraison FCM** (gérée par Google Play Services), **pas dans l'app** : le
  son/vibration partent dès la réception FCM.
- **Cause = DNS privé AdGuard** (`dns.adguard.com`, DNS-over-TLS). À chaque bascule réseau sur 5G, la
  **connexion FCM doit se reconnecter** → poignée de main TLS vers un serveur AdGuard lent/lointain.
  Test **sans** DNS privé = **1 s**. Mesure : la *requête* AdGuard elle-même = 13 ms (donc pas un
  blocage, mais le **coût de connexion DoT**).
- **Mitigation en gardant l'anti-pub** : passer le **DNS privé Android** à **`dns.quad9.net`**
  (bloque pub + malware, mesuré 2× plus rapide, 7 ms). À tester sur le tél en 5G.
- **Affranchissement total** (DNS/FCM sans importance) = l'app garde **sa propre connexion permanente**
  et reçoit la sonnerie en direct (objectif « indépendance de HA », `APP_SANS_HA.md`) — chantier, pas
  un réglage.

## 9. Fichiers touchés (app)

- **Nouveau** : `KeepAliveService.kt` (service permanent + heartbeat + relances).
- `AndroidManifest.xml` : permission `FOREGROUND_SERVICE_SPECIAL_USE` + `<service>` KeepAlive.
- `DebugLog.kt` : `push()` (log distant vers HA).
- `Config.kt` : `HEARTBEAT_WEBHOOK_ID` / `heartbeatUrl()`, `LOG_WEBHOOK_ID` / `logUrl()`.
- `MainActivity.kt`, `BootReceiver.kt`, `SonnetteMessagingService.kt` : appels `KeepAliveService.start`.
- `RingPlayer.kt` : décision réunion remontée en `push` (log distant).
- `build.gradle.kts` : versionCode 17, versionName 0.5.1.

## 10. Automatisations HA créées

| id | rôle |
|---|---|
| `sonnette_heartbeat_telephone` | reçoit le ping 10 min ; `last_triggered` = dernière vie |
| `sonnette_heartbeat_watchdog` | SMS + notif si pas de heartbeat > 30 min |
| `sonnette_log_distant` | reçoit les lignes de log des tél (webhook `sv_log`) → logbook `LOG <modèle>` |

## 11. Déploiement (rappel)

- Build/publish : `bash HA/publish.sh "notes"` → copie l'APK sur HA + génère `sonnette-version.json`.
  (Nécessite `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.)
- Téléphone branché : `adb install -r HA/sonnette-video.apk`.
- 2ᵉ téléphone : bouton **« Mettre à jour l'app »** (tire la version depuis HA `/local`).
- **État au 15/07** : les **deux** tél confirmés en **v0.5.1** et vivants (preuve : les deux envoient
  du log distant, qui n'existe que dans cette version).

## 12. À FAIRE — test réel livreur : **vendredi 2026-07-17**

Philippe attend un vrai livreur. Rien à préparer : **tout est journalisé** (verdict IA + alerte + push).
Ce jour-là, **relire le logbook HA** autour de la livraison :
- verdict IA exact (`personne_arretee` / `passant` / …) et l'heure,
- si l'alerte a déclenché (`persistent_notification` + `shell_command.sonnette_alert`),
- si le push `type=motion` est arrivé sur les 2 tél (`LOG …[FCM] push reçu type=motion`).

Ajuster la consigne IA (voir `DETECTION_LIVREUR.md` §9) si faux négatif / positif, en s'appuyant sur
la photo réelle.
