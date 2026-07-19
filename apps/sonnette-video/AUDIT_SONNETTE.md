# AUDIT SONNETTE — Fiabilité de l'alerte & protocole de test

> **But** : sonnette Aqara G410 → alerter Philippe (22081212UG) et maman (22101316G, handicapée) de
> façon **fiable pour de la sécurité**. Ce doc = audit complet + cause racine + correctifs + **protocole
> du test unique** (maman dort → un seul essai, sans la réveiller). État au **2026-07-09**.
> Docs liés : `ETAT_TALKBACK_SONNETTE.md`, `DETECTION_LIVREUR.md`, `APP_SANS_HA.md`.

---

## 0. TL;DR — état vérifié (audit live 2026-07-09)
- **Symptôme** : le vrai appui sonne chez maman mais **pas** chez Philippe. Le **test direct** (FCM au
  token de Philippe) sonne. ⇒ la **livraison marche**, le problème est **côté liste de tokens HA**.
- **CAUSE RACINE TROUVÉE** (dans `/config/fcm_send_ha.py`) : `send()` **supprimait l'appareil** du fichier
  `sonnette_tokens.json` dès une réponse FCM `UNREGISTERED`. Le token de **Philippe tourne** (réinstalls
  dev + renouvellement Firebase ~mensuel) → il devient périmé → supprimé → **il ne sonne plus**. Le token
  de **maman ne tourne jamais** → jamais supprimée → **sonne toujours**. ⇒ pourquoi UNIQUEMENT son tél.
- **CORRIGÉ** : `fcm_send_ha.py` durci **déployé** (ne supprime plus l'appareil ; tolère les pépins réseau ;
  écriture atomique). Backup `/config/fcm_send_ha.py.bak`.
- **REDONDANCE ajoutée** (assurance vie) : notif **Companion** (`notify.mobile_app_*`) en 2ᵉ chemin
  indépendant → même si le custom rate, l'alerte arrive.
- **App v0.3.6 (code 11)** : sonnerie unique, vibration (permission VIBRATE ajoutée), « couper le son » qui
  marche, ré-inscription auto du token **toutes les 15 min** (plancher Android ; + onNewToken instantané).
- **Nettoyage fiabilité (2026-07-09)** : `browser_mod.popup` **RETIRÉ** de l'auto de sonnerie (il était en
  1er → pouvait BLOQUER la sonnerie) ; les 3 actions restantes en **`continue_on_error`** → alerte isolée.
- **Cap indépendance HA** : **Raspberry Pi 3+** installé → futur **CHEMIN 3** (appui détecté en local via
  HomeKit + FCM direct, sans HA) = supprime le dernier point de panne unique. Voir §10 + `APP_SANS_HA.md`.
- **Vérifs** : token de Philippe **…VQ8Js6cw** présent dans le fichier (2 appareils) ; script durci
  confirmé actif ; auto de sonnerie = 4 actions (redondance) survit au redémarrage HA.

---

## 0bis. ✅ VALIDÉ EN RÉEL — 2 appuis de livreurs (2026-07-09)
Preuve dans le log du tél de Philippe (c614e0cc) — **les DEUX vrais appuis ont sonné chez lui** :
| Appui | Heure | Log |
|---|---|---|
| Livreur 1 | 17:32:08 | `push reçu type=ring` → `RingPlayer vibrate() lancé (amp)` → `onCreate action=RINGING` (call_id 1783611127) |
| Livreur 2 | 18:51:31 | `push reçu type=ring` → `RingPlayer vibrate() lancé (amp)` → `onCreate action=RINGING` (call_id 1783615889) |
⇒ **écran d'appel + son + VIBRATION** sur les deux → le correctif fonctionne en conditions réelles. 🎉

**Anomalie relevée (inoffensive, à nettoyer)** : chaque appui envoie **2 push `type=ring`** (même
`call_id`) — l'un `image_url=…camera_proxy/…?token=<signé>`, l'autre `image_url=…camera_proxy/…` **sans**
token. Le « sans token » = `fcm_send_ha.py` (IMAGE_URL fixe). Le « ?token=signé » = **un 2ᵉ envoyeur non
identifié** (ni `sonnette_pop_up_plein_ecran_portail`, ni `cast_nest_hub` — vérifiés). Sans danger :
l'écran d'appel ne s'ouvre **qu'une fois** (même `call_id`). À traquer/supprimer pour propreté.
**NB** : 2 entités « bouton » existent — `event.doorbell_repeater_74a8_video_doorbell` (HomeKit, CH-C11E)
ET `binary_sensor.aqara_video_doorbell_g4_button` (autre intégration) → plusieurs autos en parallèle
(ring, cast Nest Hub, popup portail, détection livreur).

## 1. Chaîne d'alerte réelle (bout-en-bout)
```
Appui bouton
  → event.doorbell_repeater_74a8_video_doorbell (HomeKit, via répéteur CH-C11E)
  → automatisation HA 1780680857647 « Sonnette G410… » (mode single, anti-rebond 30 s, not_from unavailable)
      │  ⚠️ v0.3.6 : browser_mod.popup RETIRÉ (il était en 1er → s'il échouait, il BLOQUAIT la sonnerie
      │             qui suivait). Les 3 actions restantes sont chacune en `continue_on_error: true`
      │             → ALERTE ISOLÉE : aucune action ne peut plus en bloquer une autre.
      ├─ shell_command.sonnette_ring → python3 /config/fcm_send_ha.py ring         [continue_on_error]
      │     → lit /config/sonnette_tokens.json {device: token} → FCM v1 type=ring aux 2 tél   [CHEMIN 1 : app custom]
      ├─ notify.mobile_app_22081212ug  (Companion, canal « Sonnette secours », prio haute)     [CHEMIN 2 : secours, continue_on_error]
      └─ notify.mobile_app_22101316g   (Companion)                                             [CHEMIN 2 : secours, continue_on_error]
  → Téléphone :
      • CHEMIN 1 (FCM type=ring) → SonnetteMessagingService → CallForegroundService → écran d'appel plein
        écran + RingPlayer (son alarme + vibration). Le canal de notif est SILENCIEUX (source son unique).
      • CHEMIN 2 (Companion) → notif « 🔔 Quelqu'un sonne » (son + heads-up), gère seul les tokens.
```
**Enregistrement du token** (pour le CHEMIN 1) :
```
App (TokenRegistrar : à l'ouverture, au boot, à chaque push reçu, + WorkManager toutes les 12 h)
  → webhook HA sv_register_c367889464589909d360a941
  → automatisation « Sonnette Video - Enregistrer token FCM » (sonnettevideo_register)
      ├─ input_text.set_value → input_text.sonnette_fcm_token = "device|token"   (1 seul, écrasé, = affichage/debug)
      └─ shell_command.sonnette_register → python3 /config/fcm_send_ha.py register <device> <token>
            → t = load_tokens(); t[device] = token; save_tokens(t)   (ÉCRASE bien, par appareil)
```

---

## 2. LE BUG « sonne pas chez moi » — cause racine + preuve
**Preuves collectées (API + adb, pas de suppositions) :**
1. `test_ring.py` (FCM direct au token de Philippe lu dans `input_text`) → **`onCreate action=RINGING`**,
   écran d'appel s'ouvre. ⇒ **livraison FCM OK, app OK.**
2. Automatisation réelle `1780680857647` : actions = popup + `sonnette_ring` (à l'origine **aucun**
   `notify.mobile_app_*`). ⇒ le custom dépend **à 100 %** de `sonnette_ring` → fichier de tokens.
3. `input_text.sonnette_fcm_token` = `22081212UG|…VQ8Js6cw` (token courant valide, maj par le worker 12 h).
4. Aucune erreur `register/ring` dans `/api/error_log`.

**Le code fautif (`/config/fcm_send_ha.py`, fonction `send`) :**
```python
    except urllib.error.HTTPError as e:
        msg = e.read().decode()[:200]
        print(f"[{device}] FCM {e.code}: {msg}")
        if e.code in (400, 404) and "UNREGISTERED" in msg:
            t = load_tokens(); t.pop(device, None); save_tokens(t)   # ← SUPPRIME l'appareil
```
**Mécanisme** : token de Philippe périmé (rotation) → un vrai appui envoie sur l'ancien token → FCM répond
`UNREGISTERED` → `t.pop(device)` **retire Philippe du fichier** → les appuis suivants le sautent, jusqu'à
la prochaine ré-inscription. **Le token de maman ne tourne jamais → jamais retirée → sonne toujours.**
2ᵉ fragilité : `send` n'attrapait **que** `HTTPError` → une `URLError` (timeout/DNS) sur un tél levait une
exception non gérée → **tout l'envoi plantait** (aucun tél ne sonnait).
(`register` était **correct** : `t[device] = token` écrase bien → pas le bug.)

---

## 3. LE CORRECTIF (déployé) — diff exact
Fichier `/config/fcm_send_ha.py` (backup : `/config/fcm_send_ha.py.bak`).
```diff
 def save_tokens(d):
-    json.dump(d, open(TOKENS_PATH, "w"))
+    tmp = TOKENS_PATH + ".tmp"                       # écriture ATOMIQUE (jamais de fichier corrompu)
+    with open(tmp, "w") as f: json.dump(d, f)
+    os.replace(tmp, TOKENS_PATH)

     except urllib.error.HTTPError as e:
         msg = e.read().decode()[:200]
         print(f"[{device}] FCM {e.code}: {msg}")
-        if e.code in (400, 404) and "UNREGISTERED" in msg:
-            t = load_tokens(); t.pop(device, None); save_tokens(t)
+        # NE RETIRE PLUS l'appareil : le prochain register écrasera le token périmé.
+        # (le retirer = LE bug « sonne pas chez moi »)
+    except Exception as e:
+        print(f"[{device}] erreur reseau (ignoree, n'affecte pas les autres): {e}")
```
**Preuve du déploiement** : `sonnette_register` via API renvoie `token enregistre: 22081212UG
(2 appareil(s))` — **« enregistre » sans accent** = c'est la version durcie (l'ancienne écrivait
« enregistré »). rc 0, 2 appareils, token de Philippe = …VQ8Js6cw à jour.
**Import** : `urllib.error` ajouté aux imports (sinon `except urllib.error.HTTPError` planterait).

---

## 4. REDONDANCE (2ᵉ chemin, ne peut pas « périmer »)
Ajouté à l'automatisation `1780680857647` (via API `POST /api/config/automation/config/1780680857647`) :
```yaml
- action: notify.mobile_app_22081212ug   # + notify.mobile_app_22101316g
  data:
    title: "🔔 Quelqu'un sonne à la porte"
    message: "Sonnette — appui détecté à {{ now().strftime('%H:%M') }}"
    data: { priority: high, ttl: 0, importance: high, channel: "Sonnette secours",
            tag: sonnette_ring, notification_icon: mdi:doorbell-video, color: "#D32F2F", sticky: "true" }
```
Le canal Companion **gère seul le renouvellement des tokens** (Google/Nabu Casa) → il **ne peut pas**
subir le bug du token périmé. Testé vers le tél de Philippe seul : reçu, canal importance HIGH, `isNoisy=true`.
⇒ **deux chemins indépendants** : si l'un tombe, l'autre alerte.

---

## 5. Corrections APP (v0.3.0 → v0.3.5) — cf `fiabilite-sonnerie-fixes` (mémoire)
- **Token périmé** → `TokenRefreshWorker` (WorkManager, ré-inscription **toutes les 12 h même app fermée**)
  + ré-inscription à l'ouverture/boot/chaque push. Dépendance `androidx.work`.
- **Double sonnerie** → canal de notif **silencieux** (`sonnette_incoming_v3`) + **source unique**
  `RingPlayer` (détenu par `CallForegroundService`).
- **Écran allumé = muet** → le **service** joue le son (via `RingPlayer.start`) dès l'appel (allumé ou éteint).
- **« Couper le son » injoignable écran allumé** → action **« Couper le son »** dans la notif
  (`CallActionReceiver.ACTION_MUTE` → `RingPlayer.stop()`) + taper la notif ouvre l'écran d'appel.
- **Pas de vibration** → **permission `VIBRATE` MANQUANTE au manifeste** (exception `SecurityException`
  avalée). Ajoutée. Vibration = `VibratorManager` + amplitude + `VibrationAttributes.USAGE_RINGTONE`.
- Fichiers app clés : `RingPlayer.kt`, `TokenRefreshWorker.kt`, `IncomingCallNotifier.kt` (canal silencieux
  + action mute), `IncomingCallActivity.kt` (délègue à RingPlayer), `CallForegroundService.kt` (RingPlayer.start),
  `Config.kt` (canal v3), `AndroidManifest.xml` (VIBRATE). **v0.3.5 = versionCode 10.**

---

## 6. 🔔 PROTOCOLE DU TEST UNIQUE (ce soir, sans réveiller maman)
La correction est **vérifiée maillon par maillon** (livraison OK + token dans le fichier + script durci +
redondance) → le test = **confirmation**. Deux façons, choisis :

### Option A — SANS déranger maman du tout (recommandée ce soir)
Ça teste TON téléphone (réception + sonnerie + vibration + « couper le son ») **sans toucher celui de maman** :
1. Garde ton tél **branché en USB** (pour que je lise le log) OU rebranche-le juste après.
2. Dis-moi **« teste mon tél »** → je déclenche une sonnerie **uniquement vers ton token** (maman n'est PAS
   sonnée) → tu dois avoir : écran d'appel + son + vibration. Puis je **lis ton log** = preuve.
> ⚠️ Ça ne teste PAS l'automatisation HA elle-même (l'appui physique), mais on a déjà prouvé qu'elle se
> déclenche (maman sonne sur les vrais appuis). Donc A confirme le maillon qui te manquait.

### Option B — vrai bout-en-bout (sonne maman UNE fois)
Le test complet réel, mais il sonnera **une fois** chez maman (bref) :
1. **Débranche l'USB**, laisse ton tél se **verrouiller** (condition réelle).
2. **Presse la sonnette UNE fois.**
3. Sur ton tél, tu dois voir **LES DEUX** : (1) écran d'appel plein écran + son + vibration, **(2)** notif
   « 🔔 Quelqu'un sonne à la porte » (Companion).
4. **Coupe vite** (Raccrocher) pour ne pas déranger maman, puis **rebranche l'USB** → je pulle le log
   (`DEBUG/pull-log.sh` ou adb) = enregistrement définitif de ce que ton tél a reçu.

### Interprétation (les deux options)
| Résultat sur ton tél | Signification |
|---|---|
| Écran d'appel **+** Companion | ✅ tout marche, redondance active |
| Companion **seul** | le secours te sauve (tu es alerté), le custom a encore un souci → je relis le log |
| Écran d'appel **seul** | custom OK, souci Companion → je vérifie le canal |
| Rien | improbable (livraison prouvée) → je diagnostique via le log + logbook HA |

**Recommandation** : fais **l'Option A ce soir** (zéro dérangement, confirme ton tél), et l'**Option B
demain** quand maman est réveillée (vrai bout-en-bout).

---

## 7. Vérifs / debug SANS déranger maman (read-only)
- Token de Philippe enregistré : `GET /api/states/input_text.sonnette_fcm_token` (device|token, …8 derniers).
- Script durci actif : `POST /api/services/shell_command/sonnette_register?return_response` avec
  `{device, token}` → doit dire **« enregistre »** (sans accent) + « N appareil(s) ».
- Auto sonnerie intacte : `GET /api/config/automation/config/1780680857647` → 4 actions.
- Log tél : `adb -s c614e0cc shell "cat /sdcard/Android/data/com.philhome.sonnettevideo/files/debug/sonnette-debug.log"`
  → chercher `push reçu type=ring`, `onCreate action=RINGING`, `TokenRefresh`, `RingPlayer`.
- Déclencher une sonnerie **vers ton tél seul** (sans maman) : `test_ring.py` (scratchpad) envoie `type=ring`
  au token (clé Firebase `~/Downloads/home-assistant-305916-firebase-adminsdk-*.json`).
- **NE PAS** appeler `shell_command.sonnette_ring` ni `sonnette_alert` pour tester → ça sonne les 2 tél.

---

## 8. État déployé (2026-07-09 soir) — ✅ v0.3.6 sur les 2 tél
| Élément | État |
|---|---|
| Tél Philippe 22081212UG (`c614e0cc`) | ✅ **v0.3.6** (code 11) · token `…VQ8Js6cw` inscrit |
| Tél maman 22101316G | ✅ **v0.3.6** (OTA « avec difficulté » = installeur MIUI) · token `…yirUZ8X4` **ré-inscrit après MAJ** (19:51) |
| HA sert (OTA) | ✅ code 11 / v0.3.6 (manifeste + APK cohérents, signature debug identique `b949e33d…`) |
| Auto sonnerie `1780680857647` | ✅ browser_mod retiré, 3 actions en `continue_on_error` |
| `fcm_send_ha.py` durci | ✅ ne supprime plus les appareils · atomique |
| Redondance Companion | ✅ 2ᵉ chemin sur les 2 tél |
| Worker token | ✅ 15 min sur les 2 tél |

**Piège OTA rencontré** (résolu, à retenir) : ne jamais installer une version par **adb** sans **publier la
même sur HA** — sinon le tél a un code > celui servi par HA → OTA = **downgrade** → MIUI dit « package
invalide ». Règle : après tout `adb install`, lancer `HA/publish.sh` pour aligner HA. Vérifier la **signature**
(`apksigner verify --print-certs`) avant de pousser vers maman (doit être identique à l'installé, sinon
« package invalide » aussi). → **La suite = feuille de route §12.**

---

## 10. 🏁 AUDIT CERTIFIÉ ROBUSTESSE (v0.3.6, 2026-07-09) — « robuste quoi qu'il arrive »

### 10.1 Audit device réel (tél Philippe c614e0cc / 22081212UG) — TOUT VERT
| Facteur | Vérif | État |
|---|---|---|
| Toutes permissions (VIBRATE, FULL_SCREEN, RECORD, BOOT, INSTALL…) | `dumpsys package` | ✅ accordées |
| Optimisation batterie | ignore-list | ✅ exemptée |
| **App Standby bucket** | `am get-standby-bucket` = **5 (EXEMPTED)** | ✅ aucune restriction (le mieux) |
| Arrière-plan | non restreint | ✅ |
| Services FCM (`SonnetteMessagingService` + Firebase) | `dumpsys package` | ✅ déclarés |
| Google Play Services | `26.24.34` | ✅ récent |
| **Réveil FCM prouvé en réel** | 2 appuis livreurs 17:32 + 18:51 | ✅ a sonné (son+vibration) |
| Worker token-refresh | JobScheduler `SystemJobService` NET, période 15 min | ✅ programmé & actif |
| Version installée | `versionCode=11 versionName=0.3.6` | ✅ |

**Conclusion device : l'app est au plafond de robustesse. Le réveil marche même app fermée / tél verrouillé (prouvé).**

### 10.2 Les 3 chemins d'alerte (redondance)
| Chemin | Voie | Résiste à | Ne résiste PAS à |
|---|---|---|---|
| **1. App custom** | Aqara→HA→`fcm_send_ha.py`→FCM→écran d'appel plein écran + `RingPlayer` | token périmé (script durci + worker 15 min + onNewToken) | **HA down**, FCM/GMS down |
| **2. Companion** | Aqara→HA→`notify.mobile_app_*`→FCM→notif | token périmé (Google gère le token **en continu**, jamais périmé) | **HA down**, FCM/GMS down |
| **3. Pi 3+** *(à finir)* | Aqara→**Pi (HomeKit local)**→FCM→tél, **sans HA** | **HA down** ✅ | FCM/GMS down, Pi down |

### 10.3 Matrice de pannes — que se passe-t-il si… ?
| Panne | Sonne ? | Grâce à |
|---|---|---|
| App tuée par MIUI | ✅ | FCM réveille (bucket EXEMPTED, batterie exemptée — prouvé) |
| Tél verrouillé / en veille | ✅ | full-screen intent + service phoneCall (prouvé 18:51) |
| Token FCM tourne (réinstall/rotation) | ✅ | script HA ne supprime plus + `onNewToken` instantané + worker 15 min |
| `fcm_send_ha.py` plante sur 1 tél | ✅ | `except Exception` par tél (n'affecte plus les autres) + `continue_on_error` |
| Chemin custom KO | ✅ | Chemin 2 (Companion) indépendant |
| `browser_mod`/Mac indisponible | ✅ | **retiré** de l'alerte (ne peut plus bloquer) |
| Reboot du tél (coupure courant) | ✅ | `BootReceiver` re-programme worker + ré-inscrit token |
| Fichier tokens corrompu (write coupé) | ✅ | `save_tokens` atomique (tmp + `os.replace`) |
| **HA tombe en panne** | ❌ *(aujourd'hui)* → ✅ **avec CHEMIN 3 (Pi 3+)** | c'est le dernier SPOF, adressé par le Pi |
| FCM / Google Play Services down (mondial, très rare) | ❌ | inhérent à toute app push (Aqara compris) |
| Panne courant/box (tél sans réseau) | ❌ | inhérent (fibre coupée) |

### 10.4 Verdict
**Sur tout ce qui dépend de nous, l'alerte est increvable** (device maxé, 2 chemins indépendants, token auto-réparant, actions isolées). **Le seul trou = HA en panne** → le **Pi 3+ (Chemin 3)** le bouche. Restent 2 pannes *inhérentes à toute solution* (FCM mondial down, courant/réseau coupé) — non contournables sans matériel dédié cellulaire.

---

## 11. 🔧 CORRIGER SANS CHERCHER — table des patches (où toucher quoi)
> But : à la prochaine correction, **ne rien re-chercher** — tout est ci-dessous.

| Si le problème est… | Fichier / endroit exact | Quoi faire |
|---|---|---|
| Sonne pas sur 1 tél | `/config/fcm_send_ha.py` (HA) fn `send` | vérifier qu'il **ne fait PLUS** `t.pop(device)` ; token dans `/config/sonnette_tokens.json` |
| Token à ré-inscrire vite | `TokenRefreshWorker.kt` `schedule()` | intervalle (plancher **15 min**) ; policy `UPDATE` (pas KEEP) sinon l'ancien intervalle reste |
| Pas de vibration | `AndroidManifest.xml` | présence de `<uses-permission … VIBRATE />` (ligne 15) ; source = `RingPlayer.kt` |
| Double son / son en boucle | canal `sonnette_incoming_v3` (`IncomingCallNotifier.kt`) + `RingPlayer.kt` | canal SILENCIEUX ; **source son unique** = RingPlayer (via `CallForegroundService`) |
| « Couper le son » KO | `CallActionReceiver.kt` `ACTION_MUTE` → `RingPlayer.stop()` | action présente dans la notif |
| Écran allumé = muet | `CallForegroundService.kt` | c'est le **service** qui `RingPlayer.start()` (pas l'activité) |
| Une action HA bloque les autres | auto `1780680857647` | chaque action doit avoir `continue_on_error: true` ; **pas** de `browser_mod` en 1er |
| Ajouter/retirer un tél | HA `POST shell_command.sonnette_register {device,token}` (écrase) | jamais éditer le JSON à la main pendant un envoi |
| Déployer nouvelle APK | bump `app/build.gradle.kts` (versionCode+1, versionName) → `bash HA/publish.sh "notes"` | puis « Mettre à jour » sur les tél |
| Modifier une auto HA | `POST /api/config/automation/config/<id>` (SSL `cafile=/etc/ssl/cert.pem`) | **montrer le diff d'abord** (règle Philippe) |
| Rendre indépendant de HA | `APP_SANS_HA.md` + Pi 3+ | Chemin 3 : `aiohomekit` (appui) + `fcm_send_ha.py` (push) sur le Pi |

**Constantes clés** (pour ne plus chercher) : Firebase projet `home-assistant-305916` · clé `/config/fcm-service-account.json` ·
tokens `/config/sonnette_tokens.json` · HA `192.168.1.76` (`philhomeassist.duckdns.org`) · Mac deploy `192.168.1.30` ·
tél Philippe adb `c614e0cc` (22081212UG) · maman `22101316G` · pkg `com.philhome.sonnettevideo` ·
auto sonnerie `1780680857647` · auto register `sonnettevideo_register` (webhook `sv_register_c367889464589909d360a941`) ·
log tél `/sdcard/Android/data/com.philhome.sonnettevideo/files/debug/sonnette-debug.log`.

---

## 16. 🔢 COMPTEUR DE SONNERIES (2026-07-11) — vérifier que le tél reçoit bien
> Besoin Philippe : maman n'est pas un témoin fiable → il veut être **certain** que son tél reçoit chaque
> appui. Le compteur doit **compter les vrais appuis même si un token est cassé** → donc **2 compteurs** :
> une **vérité terrain** (à la détection, indépendante du token) + le **reçu réel** sur le tél, à confronter.

### 16.1 Côté APP — FAIT (v0.3.7, code 12)
- **`RingCounter.kt`** (nouveau) : compte les sonneries **reçues sur CE tél**, par jour, en SharedPreferences
  (`sonnette_ring_counter`), **clé = date `yyyy-MM-dd`**. Garde ~14 jours (prune).
- **Incrément** dans `SonnetteMessagingService` sur `type=ring` (donc **seulement les vrais push FCM** — pas
  le bouton local « Tester la sonnerie »).
- **RESET À MINUIT = automatique** : le compteur étant **indexé par date**, « aujourd'hui » repart à 0 à
  00:00 (nouveau jour = nouvelle clé). Aucune tâche de reset nécessaire côté app.
- **Affichage** : carte « Compteur des sonneries » sur `MainActivity` (`refreshCounter()` en `onResume`) :
  « Reçues sur ce tél aujourd'hui : N » + historique 7 jours + rappel « comparer au compteur HA ».

### 16.1bis Côté PI — compteur local (FAIT 2026-07-11, pour la bascule)
`hk_listen.py` : `bump_local_count()` écrit `~/sonnette-hk/ring_count.json` (`{ "YYYY-MM-DD": n }`), appelé
sur **chaque appui détecté** (même en OBSERVATION, même HA/FCM down) → **vérité terrain locale au Pi**,
indépendante de HA ET du token. Clé = date → **reset auto à minuit**. Garde ~14 jours. Après la bascule,
c'est LE compteur fiable quand HA est down (l'app pourra le lire sur le LAN plus tard).
⚙️ **Outillage d'accès Pi (après nettoyage du scratchpad 2026-07-11)** : venv **`scratchpad/sshenv`**
(paramiko 5.0) + `scratchpad/sshrun.py` + `scratchpad/pi_creds.json` (192.168.1.66/philippe). Le venv `hkenv`
a été purgé → utiliser **`sshenv/bin/python scratchpad/sshrun.py "cmd"`**. Recréer si purgé :
`python3 -m venv sshenv && sshenv/bin/pip install paramiko`.

### 16.2 Côté HA — VÉRITÉ TERRAIN (À FAIRE, dans le diff à autoriser à la reprise)
Compteur **indépendant du token** (compté à la DÉTECTION de l'appui, pas à la réception tél) :
- **`counter.sonnette_appuis_jour`** (helper `counter`, ou `input_number` + `utility_meter` daily).
- **Incrément** : ajouter `counter.increment` (a) dans l'auto de sonnerie `1780680857647` (détection HA
  actuelle) ET (b) dans la future auto webhook `sonnette_pi_ring_backup` (détection Pi après bascule).
  → le compteur reste juste avant ET après la bascule.
- **Reset à 00:00** : automatisation `trigger: time "00:00:00"` → `action: counter.reset`.
- **Consultation** : Philippe voit ce compteur **dans l'app HA** quand il veut (pull = robuste aux tokens).
- (Étape suivante app) l'app **lira** ce compteur pour afficher « détectés N / reçus M » et **alerter en cas
  d'écart** — soit via un token d'accès HA longue durée (GET `/api/states/counter.sonnette_appuis_jour`),
  soit via un webhook-réponse. À trancher (éviter d'embarquer un token HA dans l'APK si possible).

---

## 17. 🔬 DEBUG ULTRA-DÉTAILLÉ + checklist « être CERTAIN que tout marche »
> But Philippe : tester d'abord sur HA, et **quand tout fonctionne**, basculer en test sur le Pi — avec la
> **certitude** que rien n'est raté. Debug des DEUX côtés, corrélable par horodatage.

### 17.1 Les 3 journaux (unifiés)
| Côté | Journal | Contenu | Comment lire |
|---|---|---|---|
| **App** | `sonnette-debug.log` (`/sdcard/Android/data/com.philhome.sonnettevideo/files/debug/`) | réception FCM (`push reçu type=ring`), ring, token, RingPlayer, compteur | **dans l'app** (« 📋 Voir le log debug ») ou `adb pull` |
| **HA / Pi** | `sonnette_debug.log` (HA `/config/` · Pi `~/sonnette-hk/`) | **chaque ring/register + résultat PAR APPAREIL** (`-> 22081212UG (…tok) : FCM OK 200`), horodaté, préfixé `[HA]`/`[sonnette-pi]` | HA : terminal/File Editor · Pi : `tail -f` |
| **Pi service** | `journalctl -u sonnette-hk -f` | détection HomeKit (`APPUI detecte`), compteur Pi, webhooks | sur le Pi |

`fcm_send_ha.py` (2026-07-11) journalise via `dbg()` (env `SEND_LOG`, défensif = ne casse jamais l'envoi ;
`SONNETTE_HOST` distingue HA vs Pi). Déploiement HA : servi par le Mac `http://192.168.1.30:8772/fcm_send_ha.py`.

### 17.2 Checklist de corrélation d'UN appui (la preuve que « tout marche »)
Un appui doit laisser une trace **cohérente** dans les 3 journaux (mêmes secondes) :
1. **HA** `sonnette_debug.log` : `RING call_id=… -> 2 appareil(s) [...]` puis `-> 22081212UG (…) : FCM OK 200` (idem maman).
2. **App** `sonnette-debug.log` (chaque tél) : `push reçu type=ring …` → `onCreate action=RINGING` (+ compteur reçu +1).
3. **Compteurs** : HA `counter.sonnette_appuis_jour` +1 ; app « reçues aujourd'hui » +1. **Égaux = OK. Écart = raté.**
Après bascule : idem mais l'étape 1 est côté **Pi** (`journalctl` : `APPUI detecte` + `FCM OK`) au lieu de HA.

### 17.3 Idées d'amélioration (plus tard)
- Bouton in-app « comparer détectés/reçus » (fetch `counter.sonnette_appuis_jour`).
- Exposer `sonnette_debug.log` HA à l'app (lecture) pour tout voir depuis le tél.

---

## 9. Références rapides
- **Tokens** : `/config/sonnette_tokens.json` = `{"22081212UG":"<tok>", "22101316G":"<tok>"}`. Sender =
  `/config/fcm_send_ha.py` (register/ring/cancel), clé `/config/fcm-service-account.json`, projet Firebase
  `home-assistant-305916`. `register` ÉCRASE par appareil ; `ring` envoie à tous (le durci ne supprime plus).
- **HA** : `https://philhomeassist.duckdns.org` (local `192.168.1.76`). Mac (déploiement) : `192.168.1.30`.
  Add-on **Terminal & SSH** officiel, port 22, **publickey uniquement** (le terminal core-ssh **n'a pas
  python3** — seul HA Core l'a en `/usr/local/bin/python3` ; il a `wget`). Déploiement d'un fichier =
  `python3 -m http.server 87xx` sur le Mac + `wget -O /config/... http://192.168.1.30:87xx/...` collé dans
  le terminal HA. **API HA modifiable** : automations via `POST /api/config/automation/config/<id>`.
- **Auto sonnerie** : `1780680857647` (appui → popup + sonnette_ring + 2 notify Companion).
  **Auto register** : `sonnettevideo_register` (webhook `sv_register_c367889464589909d360a941`).
- **Tél** : Philippe 22081212UG (adb `c614e0cc`), maman 22101316G. App `com.philhome.sonnettevideo`.
- **Build/deploy app** : `bash HA/publish.sh "notes"` (bump `app/build.gradle.kts` d'abord) → bouton
  « Mettre à jour ». `JAVA_HOME` = JBR Android Studio pour builder en CLI.

---

## 12. 🗺️ FEUILLE DE ROUTE — priorités des séances de code
> Ordre = **valeur pour la sécurité de maman** (fiabilité d'abord, puis confort). Chaque séance = un
> livrable testable. Cocher au fur et à mesure.

### 🔴 P1 — Pi 3+ = CHEMIN 3 (indépendance HA) — *le vrai « 300 % »*
**Pourquoi** : dernier point de panne unique = HA down → plus de sonnerie. Le Pi rend l'appui **détectable
sans HA**. Réf : `APP_SANS_HA.md` §7. 
> 🛑 **RÈGLE DURE (Philippe)** : « pas de bascule HA VS Pi sans test ». Le Pi est un **3ᵉ chemin ADDITIF**.
> **HA reste le chemin principal ; on ne débranche RIEN, on ne remplace RIEN tant que le Pi n'a pas prouvé
> qu'il sonne HA-coupé.** L'appairage HomeKit du Pi ne doit **pas** perturber celui de HA (dont dépend la
> détection livreur = capteur mouvement du répéteur). Approche = **coexistence lecture seule**.
- [x] **1.0 — accès Pi** ✅ `sonnette-pi` 192.168.1.66, user `philippe`/sudo, **aarch64**, Debian 13,
      Python 3.13.5. Accès via `scratchpad/sshrun.py` (paramiko, mdp dans `pi_creds.json`, hors argv).
- [x] **1.1 prépa — aiohomekit** ✅ installé `~/sonnette-hk` (venv), **aiohomekit 3.2.20** import OK.
- [ ] **1.1 — rejoindre le pairing HomeKit SANS casser HA** (LE point délicat) : un **nouveau** pair-setup
      est BLOQUÉ (`TLV 0x06`, déjà appairé à HA — cf `APP_SANS_HA.md` §7.3). Solution = **réutiliser les
      clés d'appairage de HA** (`/config/.storage/core.config_entries`, entrée homekit_controller du
      répéteur `cc:dd:af:96:35:a9` : `iOSPairingId`/`iOSDeviceLTSK`/`iOSDeviceLTPK`/`AccessoryLTPK`/…) →
      les charger dans aiohomekit comme pairing existant. **HAP autorise plusieurs sessions sur le MÊME
      pairing** → Pi + HA en parallèle. ⚠️ **lecture seule** : ne jamais Remove/Add-Pairing (casserait HA).
- [ ] **1.2 — détecter l'appui (test non destructif)** : souscrire à la caractéristique bouton du répéteur,
      presser → voir l'event côté Pi **pendant que HA marche toujours** (vérifier `event.doorbell_repeater…`
      HA réagit encore). Preuve avant d'aller plus loin.
- [ ] **1.3 — push FCM depuis le Pi** : copier `fcm_send_ha.py` + `fcm-service-account.json` +
      `sonnette_tokens.json` sur le Pi ; au press → `ring`. **Zéro nouveau code**.
- [ ] **1.4 — service permanent** : `systemd` (auto-start boot, restart on-failure) + log.
- [ ] **1.5 — TEST HA-down (le seul qui autorise à considérer le Pi « bon »)** : **couper HA**, presser →
      les 2 tél sonnent **via le Pi**. Preuve = log tél `push reçu type=ring` + log Pi « appui → FCM ». 
      **Tant que ce test n'est pas vert : le Pi reste en observation, HA reste seul maître.**

### 🟠 P2 — Nettoyer les automatisations HA (anti-surcharge)
**Pourquoi** : « la surcharge mène à des conflits ». Réduire les chemins parallèles = moins de pannes.
- [ ] **Traquer le 2ᵉ envoyeur de `type=ring`** (l'`image_url …?token=<signé>`, cf §0bis) — origine non
      identifiée (ni cast, ni popup portail). Lister TOUTES les autos qui réagissent aux 2 entités bouton
      (`event.doorbell_repeater_74a8…` HomeKit **et** `binary_sensor.aqara_video_doorbell_g4_button`) → supprimer le doublon.
- [ ] **Consolider les 2 entités bouton** en une seule source de déclenchement fiable.
- [ ] **Confirmer les 2 vrais Nest Hub** de Philippe (parmi salon / chambre_michele / chambre_philippe) et
      **élaguer** l'auto de cast `sonnette_g410_cast_nest_hub_salon` en conséquence.

### 🟡 P3 — Vidéo + talk SANS HA à la maison (APP_SANS_HA Phase 1)
**Pourquoi** : voir/parler à la porte même HA down, et virer le vent. Réf : plan `wondrous-waddling-dragon`.
- [ ] **`RtspVideo.kt`** déjà présent (libVLC) → basculer `IncomingCallActivity`/`LiveViewActivity` sur le
      flux **RTSP direct** `rtsp://…@192.168.1.38:8554/chX` quand la sonnette est joignable en LAN.
- [ ] **Filtre anti-vent in-app** (`Biquad`/`VoiceFilter` passe-haut) sur l'audio décodé — sortir le filtre
      de go2rtc (donc de HA).
- [ ] **Talk-back direct** : `DoorbellTalk(useRelay=false)` (déjà codé, `DirectTransport` TCP 54324/UDP 54323).
- [ ] **Sélecteur réseau** `isDoorbellOnLan()` : LAN → direct, sinon secours HA.

### 🟢 P4 — Fiabiliser le mécanisme de MAJ (OTA)
**Pourquoi** : l'OTA de maman a « résisté » (installeur MIUI + downgrade). Rendre la MAJ indolore.
- [ ] Héberger l'APK **hors HA** (`Config.APK_URL`) — cohérent avec l'indépendance HA.
- [ ] Vérifier automatiquement la **signature** + le **sens de version** avant de proposer l'OTA (éviter
      le downgrade « package invalide »). Message clair si MIUI bloque « sources inconnues ».

### 🔵 P5 — « Comme à la maison » (confort, une fois P1–P4 stables)
- [ ] Vidéo/talk **à distance (5G)** hors HA : mini-relais non-HA ou P2P façon Aqara (RTSP LAN ne passe pas le NAT).
- [ ] Portail (`GateController`) : chemin direct eWeLink au lieu du webhook HA.
- [ ] Décider du sort de `browser_mod` (retiré de l'alerte ; à supprimer complètement ou garder ailleurs).

**Prochaine séance conseillée = P1.1** (rejoindre le pairing HomeKit en lecture seule — seul vrai inconnu ;
tout le reste est éprouvé). Démarche pas-à-pas ci-dessous (§13).

---

## 13. 🔬 DÉMARCHE PRÉCISE — P1.1 « le Pi entend l'appui sans casser HA »
> Objectif : le Pi reçoit l'événement « bouton pressé » du répéteur Aqara **en parallèle de HA**, en
> **lecture seule**, pour à terme pousser le FCM même HA down. **Aucune écriture HomeKit** (jamais
> Remove/Add-Pairing → casserait HA). Chaque étape a une **preuve** avant de passer à la suivante.

### Rappels durs (ne pas dévier)
- **HA reste maître.** Le Pi n'a **aucun rôle réel** tant que le test HA-down (§12 P1.5) n'est pas vert.
- **Répéteur cible** = `cc:dd:af:96:35:a9` (HomeKit accessory-id, entité HA `event.doorbell_repeater_74a8_video_doorbell`).
- **Matter = mort** pour l'appui (aggregator vide, `APP_SANS_HA.md` §7.4) → **HomeKit uniquement**.
- Un **nouveau** pair-setup est refusé (`TLV 0x06 [Unavailable]`, déjà appairé à HA, §7.3) → on **réutilise
  les clés de HA**, on n'en crée pas.

### Étape A — Extraire (LECTURE SEULE) les clés d'appairage depuis HA
⚠️ Ce sont des **secrets** (clé privée `iOSDeviceLTSK`) → **c'est Philippe qui lance** la commande (le
classifieur me protège la lecture des credentials) et me colle le JSON, ou le dépose dans un fichier
protégé sur le Pi (`chmod 600`).
- **Fichier HA** : `/config/.storage/core.config_entries` (JSON). Accès = terminal add-on HA (root/`Tarzan93*`).
- **Commande d'extraction** (dans le terminal HA) :
  ```bash
  jq '.data.entries[] | select(.domain=="homekit_controller") | select(.unique_id|test("CC:DD:AF:96:35:A9";"i")) | .data' /config/.storage/core.config_entries
  ```
- **Champs attendus** (format aiohomekit — HA utilise aiohomekit sous le capot, donc mêmes noms) :
  `AccessoryPairingID`, `AccessoryLTPK`, `iOSPairingId`, `iOSDeviceLTSK`, `iOSDeviceLTPK`,
  `AccessoryIP`, `AccessoryPort`, `Connection: "IP"`. (Si `unique_id` ne matche pas, lister d'abord
  `select(.domain=="homekit_controller") | {unique_id, title}` pour retrouver le bon.)

### Étape B — Charger ce pairing dans aiohomekit sur le Pi (venv `~/sonnette-hk`)
- Poser les clés dans `~/sonnette-hk/pairing.json` au format `{"aqara_doorbell": { …les 8 champs… }}` (chmod 600).
- Squelette (`~/sonnette-hk/hk_listen.py`) :
  ```python
  import asyncio, json
  from aiohomekit import Controller
  from aiohomekit.controller.ip import IpPairing
  DATA = json.load(open("/home/philippe/sonnette-hk/pairing.json"))["aqara_doorbell"]
  async def main():
      c = Controller()                      # zeroconf auto
      pairing = c.load_pairing("aqara_doorbell", DATA)   # PAS de pair-setup, on charge l'existant
      accs = await pairing.list_accessories_and_characteristics()
      # Étape C : trouver le bouton
      ...
  asyncio.run(main())
  ```

### Étape C — Trouver la caractéristique « bouton pressé »
- Dans `accs`, repérer le **service Doorbell** (type HAP `121` / `00000121`) **ou** *Stateless Programmable
  Switch* (type `89`), et sa caractéristique **Programmable Switch Event** (type `00000073`).
- Noter le couple **(aid, iid)** de cette caractéristique. Valeurs de l'event : `0`=simple, `1`=double, `2`=long.
- Preuve étape C = imprimer `(aid, iid)` trouvés.

### Étape D — Souscrire + DÉTECTER un appui — **TEST NON DESTRUCTIF (gate)**
  ```python
  await pairing.subscribe([(aid, iid)])
  def cb(data): print("APPUI HomeKit reçu:", data)
  pairing.dispatcher_connect(cb)
  await asyncio.Event().wait()
  ```
- **Presser la sonnette une fois.** Attendu : `APPUI HomeKit reçu` côté Pi.
- **🛑 GATE de coexistence** — VÉRIFIER *en même temps* que **HA marche toujours** :
  `GET /api/states/event.doorbell_repeater_74a8_video_doorbell` doit **encore changer** au même appui,
  et les entités homekit du répéteur restent `available`.
  - ✅ Les deux réagissent → coexistence OK, on continue.
  - ❌ HA décroche (le Pi « kicke » la session HA car même identité de pairing) → **STOP immédiat**,
    couper le Pi → **Fallback** ci-dessous. **Ne jamais laisser le Pi casser la détection livreur de HA.**
- **Risque connu à valider ici** : HAP peut n'autoriser qu'**une** session par identité de pairing ; réutiliser
  la MÊME identité que HA peut faire s'entre-couper les 2. Ce test tranche. (§7.3 le suppose OK — à PROUVER.)

### Étape E — (si D ✅) Au press → FCM, comme HA
- Copier sur le Pi `fcm_send_ha.py`, `fcm-service-account.json`, `sonnette_tokens.json` (chmod 600).
- Dans `cb` : `subprocess.run(["python3","fcm_send_ha.py","ring"])`. **Zéro nouveau code d'envoi.**

### Étape F — systemd + TEST HA-down (§12 P1.5 = le seul feu vert)
- `~/sonnette-hk` en service `systemd` (`Restart=on-failure`, `WantedBy=multi-user.target`).
- **Couper HA**, presser → les 2 tél sonnent via le Pi (log tél `push reçu type=ring`). Vert = Pi validé.

### Fallback si la coexistence échoue (Étape D ❌)
Le Pi ne se connecte au HomeKit **que quand HA est down** : heartbeat `GET http://192.168.1.76:8123`
toutes les ~10 s ; HA injoignable N fois → le Pi ouvre la session HomeKit (plus de conflit, HA absent) et
prend le relais ; HA revient → le Pi referme sa session. Plus complexe, mais **zéro conflit** avec HA.

### Priorités (rappel, ordre d'exécution)
**P1 (Pi/indépendance HA) → P2 (nettoyage autos HA) → P3 (vidéo/talk sans HA) → P4 (OTA fiable) → P5 (confort 5G/portail).**
Séance en cours = **P1.1 étapes A→D** (A = Philippe extrait les clés ; B→D = moi sur le Pi + gate coexistence).

---

## 14. 🕊️ MIGRATION DOUCE vers le Pi — **SANS RUPTURE DE SERVICE** (règle absolue Philippe)
> Principe : **HA ne s'arrête JAMAIS.** Le Pi s'ajoute **en parallèle**, franchit des **gates** de preuve,
> et ne devient un chemin réel qu'après validation. **À aucun moment un seul appui ne doit être manqué.**
> On n'enlève **rien** à HA — même à la fin, HA reste co-primaire (ceinture + bretelles).

### Phase 0 — AUJOURD'HUI (référence) : HA seul maître ✅
Les 2 tél sonnent via HA→FCM (custom + Companion). Validé. **C'est l'état de repli permanent** : à tout
moment, si le Pi pose souci, on le **coupe** (`systemctl stop`) et on est **exactement** ici. Zéro risque.

### Phase A — Pi en **OBSERVATION** (0 rôle, 0 risque)
- Le Pi charge le pairing HomeKit (clés de HA, §13 A/B), **écoute** l'appui, **LOGGE seulement** —
  `hk_listen.py` **sans** `--send`. **Aucun FCM envoyé.** HA continue de tout faire.
- **Gate A (coexistence)** : à un appui, on voit *à la fois* le log Pi **ET** `event.doorbell_repeater…`
  bouger dans HA (entités homekit `available`). ✅ → Phase B. ❌ (Pi kicke HA) → STOP + Fallback §13.
- **Preuve loggée** avant d'avancer. Durée conseillée : laisser tourner en observation **plusieurs appuis**
  (livreurs) sur 1-2 jours pour être sûr de la stabilité + non-régression HA.

### Phase B — Pi en **REDONDANCE ACTIVE** (additif, toujours sans rupture)
- Le Pi passe en `--send` : à l'appui, il pousse **aussi** le FCM (`fcm_send_ha.py ring`). HA le pousse
  toujours en parallèle. **Le tél dédoublonne par `call_id`** → l'écran d'appel ne s'ouvre qu'une fois
  (déjà prouvé §0bis : 2 push = 1 écran). Donc **redondance invisible**, HA intact.
- Prérequis Pi : `SA_PATH` (clé Firebase) + `TOKENS_PATH` (tokens) posés par Philippe (secrets).
  ⚠️ **Fraîcheur des tokens** : au début, `sonnette_tokens.json` copié de HA (peut périmer si un token
  tourne). Correctif propre (plus tard, P1.6) : l'app **s'inscrit AUSSI au Pi** (double register HA+Pi) →
  le Pi a toujours des tokens frais, comme HA. Tant que non fait : re-copier le fichier si un tél change.
- **Gate B** : à un appui réel, les 2 tél sonnent — et un `systemctl stop sonnette-hk` sur le Pi ne change
  RIEN (HA assure encore). Prouve que le Pi est **purement additif**.

### Phase C — Validation **HA-DOWN** (le seul feu vert d'indépendance)
- **Couper HA** (ou débrancher son réseau), presser → **les 2 tél sonnent via le Pi seul**
  (log tél `push reçu type=ring` + log Pi « appui → FCM »). Rallumer HA → tout revient.
- ✅ ici = le dernier point de panne unique est **bouché**. Le Pi est validé comme Chemin 3.

### Phase D — Régime permanent (PAS de coupure de HA)
- **HA et Pi restent tous les deux actifs, en parallèle, indéfiniment.** Le tél dédoublonne. On ne
  « bascule » pas : on a **deux cerveaux** qui sonnent, l'un survit à la panne de l'autre.
- (Option lointaine, jamais imposée) si un jour on veut alléger HA, on le fait action par action, chacune
  re-testée — mais **rien ne l'exige** : la redondance double est l'objectif « 300 % » lui-même.

### État de préparation (au 2026-07-10)
- ✅ Pi accessible (aarch64, Py3.13), venv `~/sonnette-hk`, `aiohomekit 3.2.20`, `cryptography 49.0.0`.
- ✅ `fcm_send_ha.py` **stagé** sur le Pi (`~/sonnette-hk/fcm_send_ha.py`, utilise `SA_PATH`/`TOKENS_PATH`).

---

## 15. 👑 OPTION A RETENUE (2026-07-10) — le Pi devient le SEUL contrôleur HomeKit
> **Décision Philippe** : la coexistence HomeKit (Pi + HA sur le même répéteur) est une **impasse**
> (§7.3 : `TLV 0x06`, et risque que le Pi « kicke » HA). On prend l'**endgame propre** : **transfert de
> propriété** du pairing HomeKit de HA → vers le Pi. Le répéteur `cc:dd:af:96:35:a9` (CH-C11E) n'a qu'UN
> contrôleur → HA et Pi ne peuvent PAS coexister → **fenêtre de bascule courte inévitable** (~2 min).

### Architecture cible (garde toute la redondance)
```
Répéteur CH-C11E ── appairé au PI (aiohomekit, seul contrôleur)
   ├─ event 'ring'  ─→ ① ~/sonnette-hk/fcm_send_ha.py ring   (les 2 tél sonnent, SANS HA)
   │                 └→ ② POST webhook HA pi_sonnette_ring    (HA renvoie sa notif custom+Companion si up)
   └─ motion        ─→ POST webhook HA pi_sonnette_motion     (détection livreur conservée)
```
Résultat : indépendance (HA down ⇒ ① sonne) **+** double redondance HA préservée (②). Contrepartie
assumée : le Pi devient **requis** pour la détection → mitigé par `systemd Restart` + Pi dédié minimal
(philosophie §7.5 : « petit relais increvable » à la place du gros HA fragile).

### Procédure de bascule — RÉVERSIBLE, fenêtre minimale (« sans rupture » au mieux possible)
**AVANT (rien de cassé, HA reste maître) :**
1. Poser les secrets sur le Pi (Philippe) : `fcm-service-account.json` + `sonnette_tokens.json` (chmod 600).
2. Écrire `~/sonnette-hk/hk_listen.py` (ring→FCM+webhook, motion→webhook) — **finalisé après appairage**
   (introspection réelle de la caractéristique). Ne PAS lancer encore.
3. Créer côté HA **2 webhooks + automations** (montrer le diff d'abord) :
   - `pi_sonnette_motion` → déclenche la détection livreur (remplace le trigger HomeKit direct).
   - `pi_sonnette_ring` → rejoue `shell_command.sonnette_ring` + notifs Companion (redondance quand HA up).
4. **Noter le code HomeKit** = `060-56-963` (§7.3) + savoir remettre `switch.doorbell_repeater_74a8_pairing_mode` ON.

**BASCULE (fenêtre ~2 min, moment calme) :**
5. HA : **supprimer l'intégration/le device homekit_controller** du répéteur (dépaire → libère l'accessoire).
6. Répéteur en **mode appairage** (switch pairing_mode ON, ou physique).
7. Pi : `~/sonnette-hk/bin/python -m aiohomekit pair -d cc:dd:af:96:35:a9 -p 060-56-963 -a aqara_doorbell -f ~/sonnette-hk/pairing/`
   → le pair-setup redevient dispo (plus appairé à HA) → **le Pi devient contrôleur**.
8. Lancer `hk_listen.py` (+ `systemd` ensuite).

**TEST (feu vert) :**
9. Presser → ① les 2 tél sonnent (log Pi « ring → FCM ») + ② HA reçoit le webhook (si up). Bouger → HA
   détection livreur via webhook. Couper HA → presser → ça sonne quand même (indépendance prouvée).

**ROLLBACK (~1 min) si échec :** Pi `... remove-pairing` → HA : ré-ajouter l'intégration HomeKit du
répéteur (code `060-56-963`) → retour **exact** à l'état d'aujourd'hui (HA seul maître, détection livreur OK).

### À fournir par Philippe (secrets, hors classifieur)
- `~/sonnette-hk/fcm-service-account.json` (local : `~/Downloads/home-assistant-305916-firebase-adminsdk-fbsvc-689fb69286.json`).
  Commande : `scp ~/Downloads/home-assistant-305916-firebase-adminsdk-fbsvc-689fb69286.json philippe@192.168.1.66:~/sonnette-hk/fcm-service-account.json`
- `~/sonnette-hk/sonnette_tokens.json` (copie depuis HA `/config/sonnette_tokens.json`). ⚠️ fraîcheur : plus
  tard, l'app s'inscrira AUSSI au Pi (double register) → tokens toujours frais côté Pi (P1.6).
- Confirmer le **code HomeKit** `060-56-963` (sinon le relire dans l'app Aqara / HA).

### 15.1 CODE EN PLACE SUR LE PI (fait 2026-07-10) — `~/sonnette-hk/`
| Fichier | Contenu / rôle |
|---|---|
| `hk_listen.py` (137 l., compile OK) | écouteur HomeKit. Charge `pairing.json`, **introspecte** l'accessoire, trouve la caractéristique **ring = INPUT_EVENT (HAP `0x73`)** et **motion = MOTION_DETECTED (`0x22`)**, `subscribe` + `dispatcher_connect`. Anti-rebond 3 s. |
| `fcm_send_ha.py` | envoi FCM réutilisé (register/ring/cancel), lit `SA_PATH`/`TOKENS_PATH` (env posés par le listener vers `~/sonnette-hk/`). |
| `sonnette-hk.service` | unit systemd (`User=philippe`, `ExecStart=…/python …/hk_listen.py --send`, `Restart=on-failure`). **Écrit mais PAS installé/activé** tant que le test n'est pas vert. |
| venv `~/sonnette-hk/bin/` | `aiohomekit 3.2.20` + `cryptography 49.0.0`. |

**API aiohomekit 3.2.20 (vérifiée sur le Pi)** : `Controller(async_zeroconf_instance=AsyncZeroconf())` ;
`controller.load_pairing(alias, pairing_data:dict) -> pairing` (charge un pairing existant, **sans**
pair-setup) ; `await pairing.list_accessories_and_characteristics()` (introspection) ;
`await pairing.subscribe([(aid,iid),…])` ; `pairing.dispatcher_connect(cb)` (cb reçoit `{(aid,iid):{"value":x}}`).
**CLI** : `python -m aiohomekit -f FICHIER {discover|pair|accessories|watch|unpair|remove-pairing}`.

**Modes du listener** : défaut = **OBSERVATION** (logge, n'envoie RIEN) ; `--send` = **ACTIF** (FCM + webhooks).
⚠️ En Option A (Pi seul contrôleur), après la bascule HA ne détecte plus rien → il faut lancer **`--send`**
(l'observation ne sert que si HA détecte encore, ce qui n'est PAS le cas ici : utile surtout pour un 1er
run « voit-on l'event ? » juste après appairage, avant de brancher le FCM).

### 15.2 DÉCISION DESIGN — pas de double écran d'appel (call_id)
Le Pi (`fcm_send_ha.py ring`) et HA (`sonnette_ring`) génèrent **chacun** un `call_id` différent → si les DEUX
envoyaient un `type=ring`, le tél ouvrirait **2 écrans d'appel**. **Solution retenue** : après la bascule,
**seul le Pi** envoie le `type=ring` (l'écran d'appel). HA, via le webhook `pi_sonnette_ring`, n'envoie que
la **notif Companion de secours** (`notify.mobile_app_*`, un heads-up, PAS un écran d'appel → aucun conflit
de `call_id`). L'ancienne auto `1780680857647` (déclenchée par l'event HomeKit) devient **inerte** après la
bascule (HA n'a plus l'event) → elle n'envoie plus `sonnette_ring` → pas de doublon. Redondance conservée
autrement : Pi = écran d'appel ; HA = heads-up Companion.

### 15.3 SÉQUENCE DE TEST (à exécuter ensemble, moment calme)
1. **Secrets** (Philippe) : `scp` la clé Firebase + poser `sonnette_tokens.json` (§ ci-dessus), `chmod 600`.
2. **PRÉ-TEST non destructif** (HA intact) : `~/sonnette-hk/bin/python ~/sonnette-hk/fcm_send_ha.py ring`
   → les 2 tél sonnent = **Pi→FCM→tél** validé avant de toucher au pairing.
3. **Diff HA** (montrer avant d'appliquer) : 2 autos webhook →
   - `pi_sonnette_ring` → `notify.mobile_app_22081212ug` + `_22101316g` (heads-up secours, PAS `sonnette_ring`).
   - `pi_sonnette_motion` → (ré)utiliser la logique **détection livreur** (ajouter le webhook comme trigger
     de l'auto livreur existante, pour garder l'IA/notif). Réf : `DETECTION_LIVREUR.md`.
4. **BASCULE** (fenêtre ~2 min) :
   a. HA : supprimer l'intégration/device homekit_controller du répéteur (dépaire).
   b. Répéteur en mode appairage (`switch.doorbell_repeater_74a8_pairing_mode` ON).
   c. Pi : `~/sonnette-hk/bin/python -m aiohomekit -f ~/sonnette-hk/pairing.json pair -d cc:dd:af:96:35:a9 -p 060-56-963 -a aqara`
   d. 1er run OBSERVATION : `~/sonnette-hk/bin/python ~/sonnette-hk/hk_listen.py` → presser → voir
      `caracteristiques -> ring = (aid,iid)` puis `APPUI detecte (OBSERVATION)`. Preuve détection.
   e. Passer ACTIF : relancer avec `--send` (ou installer le service).
5. **TEST RÉEL** : appui → 2 tél sonnent (log Pi `fcm_send_ha.py: [..] FCM OK`) + HA reçoit les webhooks.
   Bouger → détection livreur via webhook. **Couper HA** → appui → **ça sonne quand même** (= indépendance ✅).
6. **systemd** (une fois vert) : `sudo cp ~/sonnette-hk/sonnette-hk.service /etc/systemd/system/ &&
   sudo systemctl daemon-reload && sudo systemctl enable --now sonnette-hk`.

**ROLLBACK (~1 min)** : `python -m aiohomekit -f ~/sonnette-hk/pairing.json unpair` (ou `remove-pairing`) →
HA : ré-ajouter l'intégration HomeKit du répéteur (code `060-56-963`) → retour EXACT à aujourd'hui.

### 15.4 État au 2026-07-10 (avant bascule)
- ✅ Code Pi écrit + compilé (`hk_listen.py`, `fcm_send_ha.py`, `sonnette-hk.service`), venv complet.
- ✅ **Secrets posés sur le Pi** : `fcm-service-account.json` (transfert Mac→Pi par moi) + `sonnette_tokens.json`
  (scp HA→Pi par Philippe, 319 o, 2 appareils), chmod 600.
- ✅ **PRÉ-TEST Pi→FCM RÉUSSI (2026-07-10)** : `fcm_send_ha.py ring 22101316G` (exclut maman) → terminal
  `[22081212UG] FCM OK 200` + **le tél de Philippe a sonné** (écran d'appel + son). ⇒ **le chemin Pi→FCM→tél
  marche 100 % SANS HA.** Il ne manque que la DÉTECTION de l'appui (HomeKit) = la bascule.
- 🛑 HA **toujours inchangé** (répéteur encore appairé à HA = service normal).
- 📝 **Note classifieur** : (a) toute commande qui LIT `sonnette_tokens.json` (donc `fcm_send_ha.py ring`)
  est bloquée pour moi → **Philippe lance les rings** sur le Pi (je fournis la ligne). (b) La modif des autos
  HA est aussi bloquée en auto → **Philippe doit valider explicitement / autoriser** au moment d'appliquer.

### 15.5 ⏸️ POINT DE REPRISE (pause 2026-07-10) — tout est prêt, il reste 3 choses
**Fait :** code Pi complet, secrets posés, **pré-test Pi→FCM→tél RÉUSSI**. Diff HA **validé par Philippe**
mais **PAS encore appliqué** (classifieur). À la reprise, dans l'ordre :

**REPRISE 1 — appliquer les 2 webhooks HA** (additifs, sûrs ; montrer le diff puis pousser) :
- **① Nouvelle auto `sonnette_pi_ring_backup`** (« Sonnette Pi - Ring secours (Companion) ») :
  ```yaml
  triggers: [{ platform: webhook, webhook_id: pi_sonnette_ring, allowed_methods: [POST], local_only: true }]
  actions:
    - action: notify.mobile_app_22081212ug
      data: { title: "🔔 Quelqu'un sonne à la porte", message: "Sonnette — appui détecté",
              data: { priority: high, ttl: 0, channel: "Sonnette secours", tag: sonnette_ring, importance: high, color: "#D32F2F" } }
    - action: notify.mobile_app_22101316g   # idem
  ```
- **② Ajouter un trigger webhook à `sonnette_detection_livreur`** (GARDER l'existant `binary_sensor.doorbell_repeater_74a8_motion_sensor`→on) :
  ```yaml
  + { platform: webhook, webhook_id: pi_sonnette_motion, allowed_methods: [POST], local_only: true }
  ```
  (POST `/api/config/automation/config/<id>`, SSL `cafile=/etc/ssl/cert.pem` ; script prêt dans l'historique.)

**REPRISE 1.5 — FRAÎCHEUR DES TOKENS sur le Pi (OBLIGATOIRE avant prod)** :
Problème : la copie `~/sonnette-hk/sonnette_tokens.json` est **statique** → périmée dès qu'un token tourne
(l'app se ré-inscrit à HA, pas au Pi). Fix retenu = **le Pi resynchronise depuis HA** (HA = source de vérité,
tous les tél s'y inscrivent même en 5G) :
- Clé SSH Pi→HA (une fois) : `ssh-keygen` sur le Pi + clé publique dans HA `authorized_keys` (ou l'add-on).
- `~/sonnette-hk/sync_tokens.sh` : `scp root@192.168.1.76:/config/sonnette_tokens.json ~/sonnette-hk/sonnette_tokens.json.new && mv …new …json` (atomique).
- `sonnette-hk-sync.timer` (systemd) : toutes les **15 min**. → le Pi est toujours frais dès que HA est up.
- Trou résiduel (token tourne PENDANT une panne HA = ultra rare) → couvert par le **chemin Companion**.
- (Amélioration future P1.6 : l'app s'inscrit AUSSI au Pi sur WiFi maison → indépendant même de la sync HA.)

**REPRISE 2 — la BASCULE** (fenêtre ~2 min, ensemble, moment calme) :
- a. HA : Réglages → Appareils → **répéteur `Doorbell Repeater-74A8`** → supprimer l'intégration homekit_controller (dépaire).
- b. Répéteur en mode appairage : activer `switch.doorbell_repeater_74a8_pairing_mode`.
- c. Pi : `~/sonnette-hk/bin/python -m aiohomekit -f ~/sonnette-hk/pairing.json pair -d cc:dd:af:96:35:a9 -p 060-56-963 -a aqara`  (Philippe lance).
- d. 1er run OBSERVATION (Philippe) : `~/sonnette-hk/bin/python ~/sonnette-hk/hk_listen.py` → presser → doit logger `caracteristiques -> ring=(aid,iid)` puis `APPUI detecte (OBSERVATION)`. **Preuve détection.**

**REPRISE 3 — ACTIF + test HA-coupé** :
- e. Lancer `hk_listen.py --send` (ou installer le service : `sudo cp ~/sonnette-hk/sonnette-hk.service /etc/systemd/system/ && sudo systemctl daemon-reload && sudo systemctl enable --now sonnette-hk`).
- f. Appui → 2 tél sonnent + HA reçoit webhooks. **Couper HA** → appui → **ça sonne encore** = indépendance ✅.
- **ROLLBACK** si souci : `python -m aiohomekit -f ~/sonnette-hk/pairing.json unpair` → ré-ajouter l'intégration HomeKit du répéteur dans HA (code `060-56-963`) → retour exact à aujourd'hui.

**Rappels credentials pour la reprise** : Pi `philippe@192.168.1.66` / `Tarzan93320*` (accès via `scratchpad/sshrun.py`) ;
HA `root@192.168.1.76` / `Tarzan93*` ; code HomeKit répéteur `060-56-963` ; device HomeKit `cc:dd:af:96:35:a9`.
