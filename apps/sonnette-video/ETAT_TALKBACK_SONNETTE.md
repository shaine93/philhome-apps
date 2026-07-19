# État du projet « Talk-back Sonnette Aqara » — reprise de session

 > Dernière mise à jour : **2026-07-02**. Document de reprise (handoff) : tout ce
> qu'il faut pour continuer même après une perte de connexion.

---

## ✅✅ ÉTAT STABLE / FIGÉ — 2026-07-02 (« ON NE TOUCHE À RIEN »)

**L'interphone vidéo est COMPLET et OPÉRATIONNEL sur le téléphone de maman, Wi-Fi ET 5G.**
Confirmé par l'utilisateur : « les deux fonctionnent 5g et wifi sur tel mom's ».

| Fonction | Wi-Fi (maison) | 5G (Wi-Fi coupé) |
|---|---|---|
| Voir le visiteur | 🎥 WebRTC fluide 1200p | 📸 snapshot rafraîchi (~1,3 s) |
| Entendre le visiteur | ✅ (Opus) | (talk-back seul) |
| Parler dans la sonnette | ✅ | ✅ |
| Sonnerie app custom (2 tél) | ✅ | ✅ |
| Un décroche → coupe l'autre | ✅ | ✅ |

**Health-check 2026-07-02 (tout vert)** : go2rtc 4 flux **SANS `ice_servers`** (0), HA externe HTTP 200,
snapshot caméra 200 (27 Ko), frame live `door_hi` 200 (186 Ko, image réelle), webhook coordination 200.

**⚠️ RÈGLES POUR NE RIEN CASSER :**
- **NE JAMAIS remettre `webrtc.ice_servers` (TURN) dans go2rtc.yaml** → ça CASSE le WebRTC même en Wi-Fi
  (le client AlexxIT ne gère pas ces ice_servers). go2rtc doit rester **streams-only + opus**. coturn OVH
  reste installé mais INUTILISÉ (sans effet tant que go2rtc ne pointe pas dessus ; réserve pour plus tard).
- Serveur APK Mac (`http.server 8770`) arrêté (ménage) — l'APK est déjà installé sur le tél de maman.

**🔔 ENTITÉ « bouton d'appel de la sonnette » (identifiée 2026-07-02)** :
`event.doorbell_repeater_74a8_video_doorbell` — `device_class: doorbell`, `event_types: [single_press]`.
C'est un **`event`** (pas binary_sensor/button) : chaque appui met à jour l'horodatage. Trigger HA =
`platform: state, entity_id: event.doorbell_repeater_74a8_video_doorbell`. C'est déjà ce qui déclenche
l'automatisation `Sonnette G410 - Popup Mac + Notif Xiaomi + Redmi`. ⚠️ Ne PAS confondre avec
`button.aqara_smart_video_doorbell_g410_identifier` (bouton « identifier » LED, unavailable) ni
`binary_sensor.doorbell_repeater_74a8_motion_sensor` (mouvement).

**RESTE OPTIONNEL (seulement si demandé)** : ouvrir l'app 1× sur le tél de Philippe (22081212UG) pour que
son token entre dans `/config/sonnette_tokens.json` (→ les 2 sonnent) ; l'automatisation de démarrage HA
doit recréer LES 4 liens AlexxIT (door_lo/mid/hi/doorbell_talk), pas juste doorbell_talk.

---

## 0. ⭐ REPRISE DEMAIN — LIRE EN PREMIER (état au 2026-06-26 soir)

### 0.0 ⚠️ MÉTHODE OBLIGATOIRE — diagnostiquer PAR LE DEBUG-LOG (ne pas deviner)
**Règle :** devant TOUT bug, on **va lire les logs**, on ne suppose pas. L'app écrit un debug-log
persistant sur le téléphone (`DebugLog.kt`). Workflow :
1. **Reproduire** le bug : via l'app, OU sans déplacement via adb —
   `adb shell am start -a ANSWER -n com.philhome.sonnettevideo/.IncomingCallActivity` (écran d'appel
   complet : vidéo+audio+talk), ou `…/.DevVideoActivity --es link door_hi --es mode webrtc` (vidéo seule).
2. **Lire le log** : `bash DEBUG/pull-log.sh` (rapatrie + affiche) ou bouton « 📋 Voir le log debug ».
3. **Corriger** d'après ce que dit le log, **re-tester**, recommencer.

**Ce qui est tracé** : talk-back (mode, connexion relais, trames AAC, erreurs), vidéo WebRTC
(`PLAYING WxH`, `currentTime` qui avance ou FIGE, octets audio/vidéo décodés), FCM (push reçu, token),
DNS. **Astuces clés :**
- `adb shell screencap` **ne capture PAS** la vidéo matérielle (écran noir trompeur) → on **injecte du
  JS** dans la WebView (`JavascriptInterface`) pour que le `<video>` (dans le shadow DOM du composant
  `webrtc-camera`) **reporte son état** (`readyState`, `currentTime`, `webkitAudio/VideoDecodedByteCount`,
  erreurs) dans le debug-log. C'est CE qui « fait parler » la vidéo.
- Activités `exported=true` (IncomingCallActivity, DevVideoActivity) → lançables/mesurables via adb sans
  toucher le téléphone, donc sans aller à la sonnette.

### 0.1 Où on en est (résumé exécutif)
- **Diagnostic talk-back = RÉSOLU.** Le test décisif (Mac→sonnette direct, voix AAC propre) est sorti
  **CLAIR**. La sonnette + le protocole RTP/AAC sont parfaits. Le bruit/hachage des essais navigateur
  venait du **micro du portable** + du **G.711 8 kHz de go2rtc**. → On abandonne go2rtc pour le talk-back.
- **Talk-back IMPLÉMENTÉ dans l'app + RELAIS 5G IMPLÉMENTÉ (app + HA). Tout compile** (app BUILD
  SUCCESSFUL avec OkHttp ; `talk_ws.py` syntaxe OK). ⚠️ **L'utilisateur teste TOUJOURS en 5G** → le
  transport **RELAIS via HA est le défaut** (`DoorbellTalk(useRelay=true)`). Le « direct LAN » existe
  mais n'est PAS le chemin testé.
- **Icône de l'app refaite « pro »** (sonnette vidéo, dégradé bleu). Aperçu : `apercu_icone.png` (racine).
- **Relais 5G = ✅ DÉPLOYÉ + VALIDÉ DE BOUT EN BOUT EN CELLULAIRE (2026-06-28).** Endpoint HA
  `talk_ws.py` enregistré (`GET /api/aqara_talk/...` → 401), jeton HA en place (`Config.HA_LONG_LIVED_TOKEN`),
  et test réel app→HA→sonnette confirmé par le debug-log : `onOpen HTTP 101` (WS établi en LTE),
  `effets NS=true AEC=true`, `capture active`, `100 trames AAC envoyées`. Le tuyau fonctionne en 5G.
- **Infra DEBUG ajoutée** (sur demande) : `DebugLog.kt` écrit un log persistant sur le téléphone ;
  `DEBUG/pull-log.sh` le rapatrie (`adb pull`) ; boutons « Voir/Effacer le log » dans l'app. A permis
  de trouver+corriger en 3 essais automatisés : **RECORD_AUDIO non demandée** (corrigé) + **DNS 5G
  intermittent** (retry 3× ajouté dans `RelayTransport`).
- **VIDÉO LIVE ajoutée (2026-06-28)** : `MjpegView.kt` affiche le flux `camera.doorbell_repeater_74a8`
  (la caméra QUI MARCHE ; `camera.aqara_doorbell…`/go2rtc renvoie 0 octet car le stream go2rtc est perdu
  après les reboots HA) via `/api/camera_proxy_stream` + jeton. Démarre dès l'écran d'appel (sonnerie +
  décrochage). Validé : `1re image reçue (640x480)`.
- **DNS robuste (2026-06-28)** : `Net.kt` — le DNS duckdns est très instable en 5G (AdGuard +
  cellulaire → `UnknownHostException`/SERVFAIL, cassait talk-back ET vidéo en alternance). Résolu à
  3 niveaux : **système → DoH (Cloudflare 1.1.1.1) → cache (même périmé)**. Tout (talk-back, vidéo,
  FCM) passe par `Net.base`. Validé : vidéo+talk tiennent ensemble malgré le DNS qui tombe.
- **PRÉ-CHAUFFAGE ajouté (2026-06-28)** : `Net.prewarm()` (HEAD vers HA) appelé à l'ouverture de
  l'app, à la réception de la sonnerie (FCM ring) et à l'ouverture de l'écran d'appel → chauffe
  DNS(cache)+TLS(pool). **Mesuré via adb** : connexion talk **2 s → 0,25 s** (`onOpen 101`), talk
  « active » ~0,7 s après décrochage, vidéo 1ʳᵉ image ~1,3 s, 0 échec DNS. Le **délai 1ʳᵉ parole**
  tombe de ~4 s à ~1,5–2 s (reste = tampon interne firmware de la sonnette, incompressible).
- **VOYANT « PARLEZ MAINTENANT » ajouté (2026-06-28, accessibilité maman 82 ans)** : `talkIndicator`
  dans IncomingCallActivity, piloté par l'état du talk. Orange « Connexion… »/« Préparation… » puis
  **VERT « 🟢 PARLEZ MAINTENANT »** seulement ~1,3 s APRÈS l'état "active" (= on attend le tampon de la
  sonnette pour que la 1ʳᵉ parole sorte vraiment). Évite qu'elle parle trop tôt et se répète.
- **VALIDÉ via adb + capture d'écran** (`DEBUG/screen_talk.png`, téléphone LTE) : la **vidéo live de la
  rue s'affiche**, le **voyant vert s'affiche**, talk-back (100 trames) + prewarm + FCM register HTTP 200.
  Vidéo + voyant **confirmés visuellement** → plus besoin de les tester manuellement.
- **LATENCE optimisée (2026-06-28)** côté voix : buffer `AudioRecord` = minBuf (latence capture mini),
  `MediaCodec` KEY_LATENCY=1, connexion 0,2 s (prewarm). Voyant vert : délai réglable `SPEAK_READY_DELAY_MS`
  = 700 ms (avant 1300). Plancher restant = tampon firmware de la sonnette (incompressible).
- **VIDÉO — limite mesurée** : le MJPEG via `camera_proxy_stream` est **~1 fps** (`doorbell_repeater_74a8`
  = caméra à instantanés ~0,7 fps ; `aqara_doorbell…`/go2rtc = 0 en MJPEG). **Le MJPEG ne sera jamais
  réactif** (plancher HA). Pour une vidéo fluide/basse latence en 5G : **go2rtc en MSE** (fMP4 sur
  WebSocket via HA, marche en 5G SANS TURN) dans une **WebView** (`…/webrtc/embed?...&mode=mse`) →
  à construire (réintroduit WebView + dépendance stream go2rtc à fiabiliser). **DÉCISION en attente.**
- **VIDÉO MSE testée en DEV (2026-06-28) = ÉCHEC sur ce téléphone.** `DevVideoActivity` (WebView +
  embed AlexxIT `mode=mse` + monitor JS qui logue l'état du `<video>` car `adb screencap` ne capture
  pas la vidéo HW). Logs : player `<webrtc-camera>`, la vidéo arrive (rs=4, 640x480) MAIS `currentTime`
  reste FIGÉ puis `VIDEO ERROR code=4` (codec non décodable par le WebView Xiaomi/MIUI), même avec
  transcodage go2rtc. ⇒ **MSE-en-WebView non viable ici.**
- **✅ WebRTC = SUCCÈS COMPLET (2026-06-29).** Testé via DevVideoActivity + monitor JS : **WebRTC joue
  fluide en CELLULAIRE (IPv6, SANS TURN)** — door_lo 640x480 ET door_hi **1600x1200**, `currentTime`
  avance en temps réel, attache ~2 s. L'« écran noir » d'avant = `mode=webrtc` sans candidate joignable ;
  en **IPv6 bout-en-bout** (fibre Free + Free 5G) ça connecte en P2P direct, pas besoin de TURN.
- **✅ INTERPHONE VIDÉO COMPLET intégré dans l'écran d'appel (2026-06-29)** : `WebrtcVideo.kt` (WebView +
  embed `mode=webrtc&media=video+audio` + enforcer JS maintenant autoplay & état son). Dans
  `IncomingCallActivity` : `videoWeb` (WebView) remplace le MJPEG ; door_hi lancé **muet** dès la sonnerie
  (aperçu), `showAnswered()` → `webrtc.unmute()` (le geste « Répondre » autorise le son) → **on VOIT 1200p
  + on ENTEND le visiteur + on lui PARLE** (talk-back) + voyant vert + portail. Validé adb :
  `PLAYING 1600x1200` + `talk: active` + `100 trames AAC`, connecté en ~1 s. `MjpegView`/`cameraStreamUrl`
  plus utilisés (gardés en réserve). DEV : MainActivity → « 🎬 DEV vidéo MSE » (boutons RTC LO/HI/MSE/AUTO).
- **go2rtc** : 4 flux (aqara_door_lo ch3 / mid ch2 / hi ch1 / principal) + liens AlexxIT
  (door_lo/mid/hi/doorbell_talk). Les **flux** sont dans go2rtc.yaml (fichier → persistants). Les **liens
  AlexxIT** (`webrtc.create_link`) sont **EN MÉMOIRE** → **PERDUS à chaque redémarrage de HA** → la vidéo
  casse (DEV **et** l'appel, qui utilise le lien `door_hi`). ⚠️ **FIX DURABLE = l'automatisation de démarrage
  HA doit recréer LES 4 liens** (pas juste doorbell_talk). Réparation manuelle : `POST /api/services/webrtc/create_link`
  (×4) avec le jeton. Diag : `curl http://192.168.1.76:1984/api/frame.jpeg?src=aqara_door_hi` = source OK côté serveur.
- **PREMIER VRAI TEST AVEC LA MAMAN (2026-06-29) — 2 bugs, trouvés PAR LE LOG :**
  - ✅ **FCM marche** (presser la sonnette ouvre l'écran : `push reçu type=ring`).
  - ✅ **Talk-back marche** (il l'entend à la sonnette).
  - 🐞 **Elle n'entend PAS le visiteur** → log : **`aTracks=0`** (flux WebRTC SANS piste audio). Cause :
    WebRTC n'accepte pas l'AAC, il faut de l'**Opus**. **FIX (appliqué) : ajouter `ffmpeg:NAME#audio=opus`
    à chaque flux go2rtc door_*** → re-test log : **`aTracks=1 (enabled/live)`, `muted=false`** ✅.
    À CONFIRMER À L'OREILLE (et si elle n'entend toujours pas → suspect routage `MODE_IN_COMMUNICATION`
    qui duck le son média ; piste de repli = repasser AudioRouter en MODE_NORMAL).
  - 🐞 **Image figée** chez elle → en re-test adb la vidéo NE gèle PAS (`currentTime` avance). Donc
    **décrochage WebRTC transitoire** sur son réseau à ce moment. **Instrumenté** : le log trace
    maintenant `t=… OK/FIGE` toutes les 3 s → au prochain gel on le VERRA et on ajoutera un watchdog
    (reconnexion auto si `currentTime` stagne).
- **AMÉLIORATIONS 2026-06-30 (après « test OK ») :**
  - **🔒 Fiabilité token (priorité absolue)** : `BootReceiver` n'était **PAS déclaré** dans le manifeste
    (+ permission `RECEIVE_BOOT_COMPLETED` manquante) → après un reboot, le token ne se ré-enregistrait
    jamais = l'app cessait de sonner. **Corrigé** : receiver déclaré (BOOT_COMPLETED) + ré-enregistrement
    du token **à chaque push reçu** (SonnetteMessagingService) → auto-réparation (boot / ouverture / sonnerie).
  - **🖼️ Snapshot anti-écran-noir** : `IncomingCallActivity` a un `ImageView snapshot` par-dessus la WebView,
    chargé via `Config.cameraSnapshotUrl()` (`/api/camera_proxy/` + jeton, OkHttp DoH) ; masqué quand
    `WebrtcVideo.onPlaying` se déclenche (vidéo live arrivée). Validé : `snapshot OK` puis `PLAYING`.
  - **🌬️ Filtre vent** (VoiceFilter) : passe-haut 250→**320 Hz**, porte plus stricte (OPEN 700→850, FLOOR 0,04).
  - **🔊 Volume sonnette** : `MAKEUP_GAIN=2.2` (gain de sortie avant AAC) — montable si besoin.
- **MULTI-APPAREILS + COUPER LE SON codés (2026-06-30, app terminée) :**
  - 🔇 **« Couper le son »** (bouton discret en sonnerie) : `stopRinging()` sans raccrocher (écran + vidéo
    restent, on peut répondre ensuite).
  - 🤝 **Coordination** : `CallCoordinator.answered(callId)` POSTe `{call_id, device, action:answered}` au
    webhook `Config.callEventUrl()` (DoH) au décroché ; `IncomingCallActivity` enregistre un receiver
    interne `ACTION_CANCEL_CALL` → sur `cancel` (poussé par HA / FCM type=cancel → `SonnetteMessagingService`
    re-broadcast), l'écran se **ferme** (`endCall()`). `onNewIntent` gère « Répondre » notif écran déjà ouvert.
    Le `call_id` circule depuis le push (IncomingCallNotifier) jusqu'à l'activité. Validé adb : `CallCoord
    answered → HTTP 200`.
  - ✅ **CÔTÉ HA = FAIT ET VALIDÉ (2026-07-01)** — c'était LE gros manque : il n'y avait **aucun envoyeur
    FCM** pour l'app custom (la sonnette ne notifiait que via l'app **Companion HA**). Construit :
    `/config/fcm_send_ha.py` (envoi FCM v1, lib `cryptography`, cmds register/ring/cancel, tokens dans
    `/config/sonnette_tokens.json`) + clé `/config/fcm-service-account.json` (projet Firebase
    `home-assistant-305916`) + 3 `shell_command` (`sonnette_register/ring/cancel`). Automatisations câblées
    via l'API : register→multi-tokens, ring (`1780680857647`)→+action `sonnette_ring`, nouvelle
    `sonnette_video_cancel` (webhook `sv_call_event_a7f3c1e9b85d4206`, action==answered→cancel aux autres).
    **Testé OK** (Wi-Fi-adb tél maman) : ring→écran d'appel s'ouvre ; « autre décroche »→ push cancel→ fermeture.
  - **RESTE** : ouvrir l'app 1× sur le tél de **Philippe** (22081212UG) pour que son token entre aussi dans
    `sonnette_tokens.json` → les DEUX sonnent. Puis test réel en pressant la sonnette.
- **RESTE = re-test utilisateur (oreilles)** : (1) elle entend le visiteur ? (2) voix plus FORTE à la
  sonnette ? (3) vent mieux coupé ? (4) écho/larsen ? (5) si l'image regèle → log `FIGE` → watchdog.
  + valider token après reboot. + déployer sur le tél de maman + créer le webhook/automatisation HA ci-dessus.

### 0.2 Fichiers créés / modifiés aujourd'hui
**App Android** (`app/src/main/java/com/philhome/sonnettevideo/`) :
- `AqaraTalkProtocol.kt` — NOUVEAU. Protocole Aqara porté du Python (CRC-16/X-25, paquets contrôle,
  en-tête RTP 12 o, en-tête ADTS 7 o). Constantes ports/PT/format.
- `VoiceFilter.kt` — NOUVEAU. Filtre « uniquement la voix » (passe-haut + passe-bas + porte de bruit).
  **Tous les réglages sont en haut du fichier** (companion object).
- `DoorbellTalk.kt` — NOUVEAU. Le client talk-back : capture micro filtrée → AAC → RTP direct sonnette.
- `Config.kt` — MODIFIÉ. Ajout `DOORBELL_IP = "192.168.1.38"`.
- `IncomingCallActivity.kt` — MODIFIÉ. `startTalk()` au bouton « Répondre », `stopTalk()` au raccrochage.
- `res/drawable/ic_launcher_background.xml` + `ic_launcher_foreground.xml` — MODIFIÉS (nouvelle icône).

**Côté HA** (déjà déployé les jours précédents, `~/Downloads/aqara-doorbell-main 2/` → `/config/custom_components/aqara_doorbell/`) :
- `bridge.py`, `encoder.py` optimisés (cf §12). ⚠️ Ces fichiers concernent l'ancienne archi go2rtc
  (banc de test). Pour l'app, ce qui compte c'est `protocol.py` (réf. des octets) et `talk.py`
  (`AqaraLanTalkClient`) qu'on RÉUTILISERA pour le relais 5G.

### 0.3 Build & installation de l'app (commandes exactes)
```bash
cd /Users/michelemorandi/AndroidStudioProjects/Sonnettevideo
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug          # produit l'APK
# APK : app/build/outputs/apk/debug/app-debug.apk
# Avec le téléphone branché (USB, débogage activé) :
./gradlew installDebug
```

### 0.4 Test talk-back EN 5G (la vraie cible) — 3 étapes de déploiement puis test
**ÉTAPE A — déployer l'endpoint relais sur HA** (méthode wget, cf §12 ; sert le dossier depuis le Mac) :
```bash
# Sur le Mac (dans le dossier du composant) :
cd ~/Downloads/aqara-doorbell-main\ 2/custom_components/aqara_doorbell && python3 -m http.server 8765 --bind 0.0.0.0
# Dans le terminal HA :
cd /config/custom_components/aqara_doorbell
cp __init__.py __init__.py.bak
wget -O talk_ws.py  http://192.168.1.30:8765/talk_ws.py
wget -O __init__.py http://192.168.1.30:8765/__init__.py
```
Puis **redémarrer HA** (Paramètres → Système → Redémarrer) pour enregistrer la vue WebSocket.
Vérifier dans les journaux HA : `aqara_talk: endpoint /api/aqara_talk/{camera_ip} enregistré`.

**ÉTAPE B — créer le jeton HA et le mettre dans l'app** :
1. HA → ton profil (en bas à gauche) → **Jetons d'accès longue durée** → Créer → copier le jeton.
2. Le coller dans `app/src/main/java/.../Config.kt` → `HA_LONG_LIVED_TOKEN = "ey…"`.

**ÉTAPE C — build + test EN 5G** :
1. `./gradlew installDebug` (téléphone branché).
2. Couper le WiFi du téléphone (= **5G**), s'éloigner si besoin.
3. Déclencher un appel → **« Répondre »** → **parler** → écouter le haut-parleur de la sonnette.
4. Logs : `adb logcat | grep -i "DoorbellTalk\|RelayTransport\|IncomingCall"`. Attendu :
   `WebSocket HA ouvert vers 192.168.1.38`, `effets: NS=… AGC=… AEC=…`, `talk: active`.
   - Erreur `Jeton HA manquant` → étape B non faite.
   - Erreur `relais HA : …` → l'endpoint n'est pas déployé/HA pas redémarré (étape A), ou URL/token faux.
5. Juger : voix **claire** ? **vent/frottements** coupés ? latence ? → on ajuste le filtre (§0.5).

### 0.5 Régler le filtre micro (fichier `VoiceFilter.kt`, companion object)
Le filtre « uniquement la voix » a 3 étages, tous réglables :
| Constante | Rôle | Si... |
|---|---|---|
| `HIGHPASS_HZ` (250) | coupe les graves = **vent, frottements** des doigts, grondement | **monter à 300-350** s'il reste du vent/du « boum » de manipulation |
| `LOWPASS_HZ` (3800) | coupe le **souffle/sifflement aigu** | descendre vers 3400 si trop de souffle |
| `GATE_OPEN_LEVEL` (700) | seuil d'ouverture de la porte (ne laisse passer que quand ça parle) | **monter** si du bruit de fond passe entre les mots ; **descendre** si une voix douce est coupée |
| `GATE_CLOSE_LEVEL` (350) | seuil de fermeture (hystérésis) | garder < OPEN |
| `GATE_FLOOR` (0.06) | niveau résiduel porte fermée | **0.0** pour silence total fermé ; monter si coupures trop sèches |
| `GATE_HOLD_MS` (180) | maintien après un mot (anti-hachage) | monter (250-300) si les fins de mots sont coupées |

Workflow : modifier la constante → `./gradlew installDebug` → re-tester. (Les effets natifs NS/AGC/AEC
sont en plus, automatiques, non réglables.)

### 0.6 ⭐ RELAIS 5G — ✅ IMPLÉMENTÉ (le 2026-06-26 soir) — déploiement en §0.4
**Fichiers réels :** côté HA `talk_ws.py` (nouveau) + `__init__.py` (enregistre la vue) ; côté app
`DoorbellTalk.kt` (transport enfichable : `RelayTransport` OkHttp par défaut + `DirectTransport`),
`Config.kt` (`talkWsUrl()`, `HA_LONG_LIVED_TOKEN`), `build.gradle.kts` (dépendance OkHttp). Tout compile.
Reste juste à **déployer + créer le jeton** (§0.4). Description du fonctionnement ci-dessous (= ce qui est codé).

**But :** rendre le talk-back fonctionnel **hors WiFi (5G)**. Le téléphone ne peut pas joindre la
sonnette (IP privée) → il passe par **HA** (joignable via `https://philhomeassist.duckdns.org`), qui
est sur le LAN avec la sonnette. On garde le **filtre micro + l'AAC 16 kHz** de l'app ; on change juste
le *transport* : au lieu d'UDP→sonnette, l'app fait un **WebSocket→HA**, et HA pousse à la sonnette.
Avantage : connexion **sortante** du téléphone → **pas de serveur TURN** nécessaire (contrairement au WebRTC).

**(A) Côté HA — nouvel endpoint WebSocket dans le composant `aqara_doorbell`** :
- Ajouter un fichier `talk_ws.py` (ou dans `__init__.py`) qui enregistre une vue aiohttp :
  `hass.http.register_view(AqaraTalkWsView())`.
- `class AqaraTalkWsView(HomeAssistantView)` : `url = "/api/aqara_talk/{camera_ip}"`, `name = "api:aqara_talk"`,
  `requires_auth = True` (→ l'app envoie un **token longue durée HA** en `Authorization: Bearer …`).
- Handler `get()` (upgrade WebSocket) :
  1. `ws = web.WebSocketResponse(); await ws.prepare(request)`
  2. `client = AqaraLanTalkClient(camera_ip)` (réutiliser `talk.py`) ; `await client.connect()` (START_VOICE → ACK).
  3. boucle `async for msg in ws:` — si `msg.type == BINARY` : c'est **une trame AAC ADTS** →
     `client.send_audio_frame(msg.data, ts)` (gérer le ts : +1024 par trame, comme bridge.py).
  4. à la fermeture : `await client.disconnect()` (STOP_VOICE) + fermer.
- ⚠️ Réutiliser EXACTEMENT la logique RTP/contrôle de `talk.py`/`protocol.py` (déjà prouvée). Le heartbeat
  est géré côté HA par le client.
- Déploiement : même méthode wget que §12 (servir le dossier depuis le Mac, wget sur HA, recharger
  l'intégration ou redémarrer HA). Tester l'endpoint avec un petit client Python WS d'abord.

**(B) Côté app — mode « relais » dans `DoorbellTalk`** :
- Ajouter une dépendance WebSocket : `implementation("com.squareup.okhttp3:okhttp:4.12.0")` dans
  `app/build.gradle.kts` (java.net.http.WebSocket n'existe pas sur Android).
- Refactor `DoorbellTalk` : extraire l'envoi en une interface `Transport { fun sendFrame(adtsFrame: ByteArray) }`
  avec 2 implémentations :
  - `DirectTransport` (actuel) : UDP RTP → sonnette (contrôle TCP géré par l'app). **WiFi only.**
  - `RelayTransport` (nouveau) : OkHttp `WebSocket` vers
    `wss://philhomeassist.duckdns.org/api/aqara_talk/192.168.1.38` (+ header `Authorization: Bearer <token>`),
    `webSocket.send(ByteString.of(adtsFrame))` par trame. **Le contrôle START/STOP/heartbeat est fait par HA**,
    donc en mode relais l'app n'ouvre PAS le TCP 54324 ni le RTP : elle ne fait qu'envoyer les trames AAC.
- Choix du transport : détecter le réseau (WiFi vs cellulaire via `ConnectivityManager`) → `DirectTransport`
  en WiFi LAN, `RelayTransport` sinon. **OU plus simple pour commencer : toujours `RelayTransport`** (marche
  partout, un seul chemin ; on optimisera la latence WiFi après).
- Ajouter dans `Config.kt` : `HA_TALK_WS = "wss://philhomeassist.duckdns.org/api/aqara_talk"` + le **token
  longue durée HA** (à créer dans HA → Profil → Jetons d'accès longue durée). TODO ne pas committer le token en clair.

**(C) Tester** : d'abord en WiFi (relais), puis **en 5G** (priorité). Vérifier la latence et la qualité.

> NB VIDÉO : l'app n'affiche aujourd'hui qu'une **photo** (push FCM), pas de flux live. La vidéo live en
> 5G est un chantier séparé (Lot 2/3) qui passera aussi par HA (flux caméra HA / go2rtc). Pas bloquant
> pour le talk-back.

### 0.7 Diagnostics rapides
- App : `adb logcat | grep DoorbellTalk`.
- Sonnette joignable (LAN) : `ping 192.168.1.38` ; canal voix libre = aucun embed/appli ne parle déjà.
- go2rtc (ancienne archi, banc de test) : `curl -s http://192.168.1.76:1984/api/streams`.
- Banc de test Mac→sonnette direct (voix propre) : §13 (`run_talk.py + test_opt.aac`, embed fermé).

### 0.8 Limites connues / à vérifier (reprise)
- **Micro + service de premier plan (À VÉRIFIER au test).** Le talk-back démarre depuis
  `IncomingCallActivity` (au 1er plan, écran d'appel visible) → le micro est autorisé tant que l'écran
  d'appel est affiché. Permissions déjà déclarées : `RECORD_AUDIO`, `FOREGROUND_SERVICE_MICROPHONE`,
  `FOREGROUND_SERVICE_PHONE_CALL`. **MAIS** `CallForegroundService` est de type `phoneCall` seulement.
  ➜ Si le micro se **coupe quand l'écran se verrouille / l'app passe en arrière-plan** (Android 14),
  déplacer la capture talk DANS `CallForegroundService` et ajouter le type **microphone** :
  `android:foregroundServiceType="phoneCall|microphone"` (manifeste) + `startForeground(...)` avec ce type.
  Pour le test simple (écran d'appel affiché, on parle), ça marche tel quel.
- **Transport par défaut = RELAIS HA** (`DoorbellTalk(host)` → `useRelay=true`). Le `DirectTransport`
  (LAN/WiFi) existe mais n'est pas le chemin testé. Bascule auto WiFi/5G = amélioration future.
- **Jeton HA en clair** dans `Config.kt` (TODO sécuriser : `local.properties`/BuildConfig, hors VCS).
- **Vidéo live** vers le téléphone = pas encore faite (l'app montre une photo FCM). Chantier séparé,
  passera aussi par HA. Non bloquant pour le talk-back.
- **Latence relais** (app→HA→sonnette) à mesurer au test 5G ; si trop élevée, optimiser (buffers, ou
  chemin direct en WiFi).

---

## 1. Résumé en une phrase

On rend **bidirectionnel** l'audio de la sonnette **Aqara G400** (voir la rue + s'entendre,
ET **parler dans la sonnette** = « talk-back »), **100 % en local** (ni HomeKit, ni cloud),
via go2rtc + l'intégration Home Assistant `aqara_doorbell`.

**Au 2026-06-26 — DIAGNOSTIC RÉSOLU / "on a gagné" :** vidéo OK, écoute (rue→toi) OK, voix
talk-back OK. Le **test décisif** (Mac→sonnette direct via `run_talk.py + test_opt.aac`, sans
go2rtc/WebRTC/micro téléphone) sort **CLAIR** (confirmé à l'oreille). → PREUVE : la sonnette +
RTP + AAC = chemin propre. Le bruit parasite du live venait du **MICRO DU PORTABLE** (capté +
dégradé par le G.711 8 kHz de go2rtc). **PLAN VALIDÉ = archi C** : l'app Android capte le micro
**filtré** (source `VOICE_COMMUNICATION` = NS+AGC+AEC natifs) → AAC-LC 16 kHz → RTP direct à la
sonnette (54323 + contrôle 54324), **sans go2rtc/WebRTC/G.711**. Le navigateur/go2rtc n'était qu'un
banc de test. PROCHAINE ÉTAPE = implémenter le talk-back dans l'app (cf §13).

---

## 2. Le matériel et le réseau

| Élément | Valeur |
|---|---|
| Sonnette | **Aqara G400** (`lumi.camera.agl013`) |
| IP sonnette | **192.168.1.38** |
| RTSP sonnette | `rtsp://697:363@192.168.1.38:8554/chN` (activé via app Aqara → RTSP LAN Preview). **ch1**=1600×1200, **ch2**=1280×960, **ch3**=640×480. **Actuellement ch3** (libère le radio WiFi de la sonnette pour l'audio). |
| Port contrôle voix (TCP) | **54324** |
| Port audio retour (UDP RTP) | **54323** |
| Format audio sonnette | AAC-LC ADTS, **16 kHz mono 32 kbps** |
| Serveur go2rtc | **192.168.1.76:1984** (go2rtc v1.9.9, bundled dans HA Core) |
| HA (HTTPS externe) | https://philhomeassist.duckdns.org |
| Python de HA Core | **/usr/local/bin/python3** (HA Green, pas de docker accessible) |
| Box/HA | HA Green (HA OS) |

---

## 3. Architecture du talk-back

```
TOI (navigateur, micro)
  → WebRTC (Opus) → go2rtc (transcode en PCMA G.711 8 kHz)
  → exec: bridge.py (ffmpeg: alaw 8 kHz → AAC-LC 16 kHz mono)
  → session contrôle TCP 54324 (START_VOICE/ACK) + RTP/UDP 54323
  → HAUT-PARLEUR de la sonnette

SONNETTE (rue)
  → RTSP 8554 (H264 1600x1200 + AAC 16 kHz)
  → go2rtc (ffmpeg → Opus) → WebRTC → TES oreilles
```

**Code de l'intégration** : `~/Downloads/aqara-doorbell-main 2/` (copie « 2 » = la bonne).
- `custom_components/aqara_doorbell/bridge.py` ← le pont audio retour (lancé par go2rtc)
- `aqara_lan_talk.py` ← banc de test autonome (sans go2rtc/navigateur)
- Déployé sur HA dans `/config/custom_components/aqara_doorbell/`

---

## 4. La config go2rtc ACTUELLE (à reconstruire si elle disparaît)

Fichier `/config/go2rtc.yaml` (sur HA). On l'écrit/le lit à distance via l'API go2rtc.
Contenu actuel (avec logs debug pour le diagnostic) :

```yaml
log:
  level: debug
  exec: trace
streams:
  aqara_doorbell_192_168_1_38:
    - rtsp://697:363@192.168.1.38:8554/ch3
    - ffmpeg:aqara_doorbell_192_168_1_38#video=copy#audio=opus#audio=copy
    - "exec:/usr/local/bin/python3 /config/custom_components/aqara_doorbell/bridge.py 192.168.1.38#backchannel=1"
```

> `ch3` = 480P (le plus léger, max de fiabilité voix). Pour remonter la qualité image :
> remplacer `/ch3` par `/ch2` (960p) ou `/ch1` (1200P).
> Une fois le talk-back validé, remettre `log: level: info` (et retirer `exec: trace`).

---

## 5. API go2rtc utiles (depuis le Mac, même LAN)

```bash
# Voir les streams chargés
curl -s http://192.168.1.76:1984/api/streams

# Lire la config en mémoire / le fichier
curl -s http://192.168.1.76:1984/api/config

# Écrire la config (body = YAML brut) — RECONSTRUIT le stream
curl -s -X POST http://192.168.1.76:1984/api/config --data-binary "$CFG"

# Redémarrer go2rtc (obligatoire après changement de config, surtout source exec:)
curl -s -X POST http://192.168.1.76:1984/api/restart

# Logs (inclut le stderr de bridge.py si log.level=debug + log.exec=trace)
curl -s http://192.168.1.76:1984/api/log

# Découverte HomeKit (pour info ; on N'utilise PAS HomeKit)
curl -s http://192.168.1.76:1984/api/homekit
```

---

## 6. RÉPARER « stream not found »

Cause connue : quelqu'un a collé l'action HA `webrtc.create_link` **dans** `go2rtc.yaml`,
ce qui écrase les streams. **L'action `create_link` ne va JAMAIS dans go2rtc.yaml** : elle
se lance dans **HA → Outils de développement → Actions**.

Pour reconstruire le stream, POSTer la config de la section 4, puis redémarrer :

```bash
read -r -d '' CFG <<'YAML'
log:
  level: debug
  exec: trace
streams:
  aqara_doorbell_192_168_1_38:
    - rtsp://697:363@192.168.1.38:8554/ch1
    - ffmpeg:aqara_doorbell_192_168_1_38#video=copy#audio=opus#audio=copy
    - "exec:/usr/local/bin/python3 /config/custom_components/aqara_doorbell/bridge.py 192.168.1.38#backchannel=1"
YAML
curl -s -X POST http://192.168.1.76:1984/api/config --data-binary "$CFG"
curl -s -X POST http://192.168.1.76:1984/api/restart
sleep 5
curl -s http://192.168.1.76:1984/api/streams   # doit montrer 3 producers
```

---

## 7. TESTER le talk-back (étape qui reste : confirmation à l'oreille)

1. **Recréer le lien** (HA → Outils de développement → Actions → `webrtc.create_link`) :
   ```yaml
   action: webrtc.create_link
   data:
     link_id: doorbell_talk
     url: aqara_doorbell_192_168_1_38
     time_to_live: 0
     open_limit: 0
   ```
2. **Ouvrir en HTTPS** (le micro exige HTTPS — `http://192.168.1.76` est refusé par Chrome) :
   ```
   https://philhomeassist.duckdns.org/webrtc/embed?url=doorbell_talk&media=video+audio+microphone&mode=webrtc
   ```
3. Autoriser le micro, parler ~10 s, **écouter la sonnette**.
4. Vérifier les logs : `curl -s http://192.168.1.76:1984/api/log | tail -40`
   - Attendu (= pipeline OK) : `Bridge starting` → `Encoding pipeline active` →
     `First stdin data received` → `First AAC frame produced` (aucune erreur, pas de
     « Voice session rejected »).

---

## 8. Banc de test autonome depuis le Mac (sans go2rtc ni navigateur)

Isole le maillon final (protocole + RTP). Scripts dans le scratchpad de session
(`test_session.py`, `test_send.py`, `test.aac`). Le Mac n'a que Python 3.9 → le module
`aqara_lan_talk.py` utilise `dict | None` (3.10+), d'où l'astuce d'injecter
`from __future__ import annotations` avant `exec()`.

```bash
# Générer un fichier de test AAC au format sonnette (ffmpeg installé via brew)
say -v Amélie -o voix.aiff "Test de la sonnette. Un, deux, trois."
ffmpeg -y -i voix.aiff -ar 16000 -ac 1 -c:a aac -profile:a aac_low -b:a 32k -f adts test.aac
# Puis l'envoyer à la sonnette (voir test_send.py) — la sonnette doit émettre la voix.
```

Résultats déjà obtenus (2026-06-25) : session de contrôle voix **OK** (START_VOICE→ACK),
envoi de 69 frames AAC **sans erreur réseau**. **Confirmation auditive pas encore faite.**

---

## 9. PIÈGES / à NE PAS faire

- ❌ **Ne PAS coller l'action `webrtc.create_link` dans `go2rtc.yaml`** → casse les streams.
- ⚠️ **Ne PAS recharger l'intégration « Aqara Doorbell » ni redémarrer HA** tant que le stream
  est écrit à la main : `async_unload` appelle `remove_stream` (supprime le stream) et
  `register_stream` **échoue silencieusement** (cause non élucidée — logs HA jamais consultés).
  → le stream serait REPERDU. Si ça arrive : reconstruire (section 6).
- ❌ **HomeKit refusé** par l'utilisateur (l'intégration est 100 % LAN, c'est voulu).
- ℹ️ Le micro du navigateur **exige HTTPS** (passer par l'URL duckdns, pas l'IP locale).
- ℹ️ Sonnette Aqara G400 = **PAS de docker** sur HA Green ; Python HA = `/usr/local/bin/python3`.

---

## 10. PROCHAINES ÉTAPES

1. **[EN COURS]** Qualité audio (le son sort déjà — cf §12). Étapes : déployer `encoder.py` optimisé
   (chaîne anti-clip + débruiteur), retester sur **un seul appareil** (portable, onglet Mac fermé),
   juger bruit/volume. Puis attaquer **délai/hachage = chemin live** (pas le codec) ; cible finale =
   talk-back **dans l'app Android** (envoi RTP direct façon `aqara_lan_talk.py`, sans go2rtc/WebRTC).
2. **[Fiabilité]** Élucider pourquoi `register_stream` de l'intégration échoue (logs HA :
   Paramètres → Système → Journaux, chercher `aqara`/`go2rtc`), pour rendre la config durable.
3. **[Nettoyage]** Repasser go2rtc en `log: level: info`.

---

## 11. Autres chantiers du même projet (rappel)

- **App Android `Sonnettevideo`** (`com.philhome.sonnettevideo`) : écran d'appel entrant
  full-screen sur verrouillage (Lot 1 fait). Build : `export JAVA_HOME=".../Android Studio.app/Contents/jbr/Contents/Home"`
  puis `./gradlew assembleDebug`. Manifeste réel = `app/src/main/AndroidManifest.xml`.
- Conflit overlay : l'écran d'appel « s'ouvre 2 s puis se ferme » = app Aqara Home native, pas notre code.
- **Icône app (2026-06-26)** : refaite « pro », thème sonnette vidéo. Adaptive icon = `res/drawable/
  ic_launcher_background.xml` (dégradé bleu #2E6BD6→#0D3C8A) + `ic_launcher_foreground.xml` (corps blanc,
  objectif caméra ajouré sur le fond, reflet, bouton ambre #FFC107). Aperçu : `apercu_icone.png` (racine).
  Les `.webp` mipmap-*dpi (ancien robot vert) ne servent pas sur Android 12+ (adaptive XML utilisé).

---

## 14. Talk-back en 5G = PRIORITÉ (décision 2026-06-26)

**Problème :** en 5G le téléphone NE peut PAS joindre la sonnette (IP privée `192.168.1.38`). Le talk-back
direct (archi C, §13) ne marche donc qu'en **WiFi maison**. Pour la 5G il faut un **relais via Home
Assistant** (HA est joignable de partout via duckdns HTTPS, et il est sur le LAN avec la sonnette).

**DÉCISION = relais HA en gardant notre qualité (pas de WebRTC/G.711, pas de TURN) :**
- L'app garde sa capture **micro filtrée + AAC-LC 16 kHz** (VoiceFilter + DoorbellTalk déjà codés).
- Au lieu d'envoyer le RTP direct à la sonnette, l'app envoie les **trames AAC à HA via WebSocket sur
  HTTPS** (duckdns). Connexion **sortante** du téléphone → marche en 5G **sans serveur TURN** (pas de
  galère CGNAT, contrairement au WebRTC P2P).
- Côté HA : un **endpoint WebSocket** dans le composant `aqara_doorbell` reçoit les trames AAC et les
  pousse à la sonnette via le client LAN existant (`AqaraLanTalkClient` : contrôle 54324 + RTP 54323).
- Bonus : ce chemin marche AUSSI en WiFi → **un seul code** (l'app passe toujours par HA). Option hybride
  (direct en WiFi / relais en 5G) possible plus tard pour la latence mini.

**Reste à faire (prochaine étape de dév) :**
1. HA : ajouter l'endpoint WebSocket (aiohttp view dans le composant) qui lit les trames AAC et appelle
   le client talk LAN. Auth = token HA (la connexion est déjà HTTPS).
2. App : `DoorbellTalk` → mode « relais » : ouvrir un WebSocket `wss://philhomeassist.duckdns.org/...`
   et y envoyer les mêmes trames AAC (au lieu du `DatagramSocket` direct). Garder le filtre micro.
3. Tester en 5G ET en WiFi.

> NB : la VIDÉO de la sonnette vers le téléphone en 5G reste un sujet séparé (passera aussi par HA :
> flux caméra HA / go2rtc). L'app n'affiche pour l'instant qu'une photo (push FCM).

---

## 13. Investigation codec / autres architectures (2026-06-26) — « prendre de la hauteur »

**Question posée :** peut-on changer de codec compatible Aqara, car on tourne en rond sur les filtres ?

**RÉPONSE — le codec est IMPOSÉ, on ne peut pas en changer.** Le protocole a été reverse-engineeré
depuis l'app Aqara officielle (cf `aqara_lan_talk.py` docstring + README). La sonnette G400 n'accepte
pour le talk-back QUE : **AAC-LC ADTS, 16 kHz, mono, 32 kbps**, en RTP (PT 97, en-tête RTP 12 octets
minimal RFC3550, **payload = trame ADTS brute, SANS AU-headers RFC3640**) sur UDP **54323**, session de
contrôle TCP **54324** (paquets MAGIC `\xFE\xEF`, CRC-16/KERMIT, START_VOICE/ACK/HEARTBEAT). C'est
exactement ce que l'app Aqara envoie. Donc « essayer Opus/G.711/autre vers la sonnette » = impossible.

**DONC le problème de qualité n'est PAS le codec final, mais le CHEMIN d'ENTRÉE.** Trois architectures :

| # | Architecture | Chaîne | Qualité | Verdict |
|---|---|---|---|---|
| **A** | **Actuelle (go2rtc exec backchannel)** | micro→WebRTC Opus 48k→go2rtc→**G.711 PCMA 8 kHz**→bridge upsample→AAC 16k→sonnette | plafonnée : le **G.711 8 kHz** (bande étroite téléphone 300-3400 Hz + companding) est le goulot ET une source de bruit. go2rtc 1.9.9 **hardcode** PCMA/8000 sur le backchannel exec → non configurable. | tuner les filtres ffmpeg ne percera JAMAIS ce plafond. |
| **B** | **Upgrade go2rtc ≥1.9.14** | idem mais go2rtc envoie **PCM s16le 16 kHz** (large bande) au lieu de G.711 8k. `bridge.py` gère déjà ça (`AACEncoder(input_format="pcm")`, `FFMPEG_CMD_PCM`). | bien meilleure (large bande). | risqué : remplacer le binaire go2rtc bundlé dans HA OS = fragile/non supporté. |
| **C** | **App Android envoie l'AAC DIRECTEMENT (= objectif final + meilleure qualité)** | micro Android 16k → **MediaCodec AAC-LC 16k mono 32k** → RTP UDP 54323 + contrôle TCP 54324. **Zéro go2rtc, zéro WebRTC, zéro G.711.** | optimale = identique à l'app Aqara officielle. | **RECOMMANDÉ.** C'est ce que `aqara_lan_talk.py` fait déjà (banc de test prouvé). Port direct en Kotlin/Java. |

**RECOMMANDATION : arrêter de tuner le chemin go2rtc (archi A, plafond G.711 8 kHz) et implémenter
le talk-back DIRECTEMENT dans l'app Android (archi C)** — qui est l'objectif final de toute façon.
Android `AudioRecord` (16 kHz) + `MediaCodec` (AAC-LC) + sockets TCP/UDP. Tout le protocole est déjà
écrit et lisible dans `aqara_lan_talk.py` + `custom_components/aqara_doorbell/protocol.py` (CRC16-KERMIT,
build_packet, build_rtp_header, ADTS) → traduction directe.

**Le test décisif (Mac→sonnette direct, voix AAC propre) valide justement l'archi C** : si la voix
sort claire en direct (sans go2rtc/WebRTC/G.711), c'est la preuve que C donnera une qualité propre, et
qu'on peut abandonner A. Fichier prêt : `scratchpad/test_opt.aac` ; lanceur : `scratchpad/run_talk.py`
(wrapper Python 3.9) → `python3 run_talk.py 192.168.1.38 --audio-file test_opt.aac` (embed FERMÉ d'abord,
sinon canal voix occupé → START_VOICE rejeté).

### ✅ RÉSULTAT DU TEST DÉCISIF (2026-06-26) = CLAIR → archi C confirmée
Envoi direct OK (START_VOICE→ACK, 117 trames / 7,5 s, STOP→ACK, 0 erreur) ET **voix claire à
l'oreille**. Donc le bruit/hachage du live = **le micro du portable + le G.711 8 kHz de go2rtc**,
PAS la sonnette ni l'AAC. On abandonne l'archi A (go2rtc). On construit l'archi C dans l'app.

### Plan d'implémentation talk-back dans l'app Android (archi C)
Tout le protocole est déjà écrit côté Python (`aqara_lan_talk.py` + `protocol.py`) → port direct Kotlin :
1. **Capture micro FILTRÉE** : `AudioRecord` avec `MediaRecorder.AudioSource.VOICE_COMMUNICATION`
   (applique automatiquement suppression de bruit + AGC + anti-écho — c'est CE qu'il fallait pour
   « filtrer le micro du portable »). En complément possible : `NoiseSuppressor`, `AutomaticGainControl`,
   `AcousticEchoCanceler` (`android.media.audiofx`) sur le `audioSessionId`. Format capture : PCM 16 bits, **16 kHz mono**.
2. **Encodage** : `MediaCodec` AAC-LC (`audio/mp4a-latm`, profil AACObjectLC), **16 kHz mono, 32 kbps**,
   en sortie **ADTS** (ajouter l'en-tête ADTS 7 octets à chaque frame — MediaCodec sort du raw AAC).
3. **Session de contrôle** : socket TCP vers `IP:54324` → envoyer `build_packet(START_VOICE, session_ts)`
   (MAGIC `\xFE\xEF`, CRC-16/KERMIT), attendre ACK ; heartbeat toutes les 5 s ; `STOP_VOICE` à la fin.
4. **Envoi audio** : socket UDP vers `IP:54323` → pour chaque frame AAC : en-tête RTP 12 octets
   (`0x80`, PT=**97**, seq++, timestamp += **1024**, ssrc aléatoire) + frame ADTS. **PAS de pacing
   artificiel** (le micro temps réel cadence ; le timestamp RTP fait la lecture).
5. Réf. exacte des octets : `protocol.py` (`crc16_kermit`, `build_packet`, `build_rtp_header`) et
   `aqara_lan_talk.py` (`AqaraLanTalk.connect/send_audio/stop`, `send_aac_file`, `stream_microphone`).

### ✅ IMPLÉMENTÉ dans l'app (2026-06-26) — compile (BUILD SUCCESSFUL)
Fichiers Kotlin (`app/src/main/java/com/philhome/sonnettevideo/`) :
- **`AqaraTalkProtocol.kt`** : port fidèle du protocole (CRC-16/X-25 bit-à-bit, `buildPacket`,
  `parsePacket`, `rtpHeader`, `adtsHeader`). Constantes 54324/54323, PT 97, 16 kHz, 1024 samples/frame.
- **`VoiceFilter.kt`** : filtre « uniquement la voix » = passe-haut 250 Hz (coupe vent/frottements) +
  passe-bas 3800 Hz (coupe souffle) + porte de bruit douce (coupe le fond entre les mots, rampe lissée
  = pas de hachage). **Réglages tout en haut du fichier** (`HIGHPASS_HZ`, `GATE_OPEN_LEVEL`, etc.).
- **`DoorbellTalk.kt`** : capture `AudioRecord` source **`VOICE_COMMUNICATION`** (NS/AGC/AEC natifs) +
  `NoiseSuppressor`/`AutomaticGainControl`/`AcousticEchoCanceler` + `VoiceFilter` + encodage `MediaCodec`
  AAC-LC 16k mono 32k → ADTS → RTP/UDP 54323, contrôle TCP 54324 + heartbeat 5 s. `start()`/`stop()`.
- **`Config.kt`** : `DOORBELL_IP = "192.168.1.38"`.
- **`IncomingCallActivity.kt`** : `startTalk()` dans `showAnswered()` (bouton « Répondre »),
  `stopTalk()` dans `hangUp()`/`onDestroy()`.

**Contrainte** : talk-back direct = LAN uniquement (téléphone sur le MÊME WiFi que la sonnette → OK
pour la personne au domicile). En 5G/extérieur, l'IP LAN 192.168.1.38 est injoignable (autre sujet).

**À TESTER demain près de la sonnette** : installer l'APK, déclencher un appel, « Répondre », parler.
Écouter : voix claire ? vent/frottements coupés ? Puis **ajuster les constantes de `VoiceFilter.kt`**
(monter `HIGHPASS_HZ` à 300 si encore du vent ; ajuster `GATE_OPEN_LEVEL`/`GATE_FLOOR` si la porte
coupe trop ou pas assez). Build : `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`
puis `./gradlew assembleDebug` (ou installDebug avec le téléphone branché).

---

## 12. Qualité audio talk-back — travail du 2026-06-26

> **OBJECTIF FINAL = l'app Android `Sonnettevideo`**, PAS le navigateur. L'embed WebRTC HTTPS
> n'est qu'un **banc de test** pour valider le pipeline audio. Une fois la qualité OK, porter le
> talk-back DANS l'app (c'est l'app qui servira la maman 82 ans en fauteuil ; priorité = **voix
> fiable** > qualité vidéo).

**Constat (1er test auditif réussi le 2026-06-26) :** la voix SORT bien de la sonnette, mais au
départ « son faible + ~3 s de délai + voix robot hachée + bruit parasite ».

### Corrections déjà appliquées (fichiers Mac `~/Downloads/aqara-doorbell-main 2/.../`)
- **`bridge.py`** : suppression du **pacing artificiel** (`_send_paced`/`time.sleep` dans la boucle
  de lecture stdin → bloquait la lecture → pipe go2rtc plein → échantillons jetés → hachée). Envoi
  immédiat de chaque trame (`session.send_audio_frame(frame)`) ; l'**horodatage RTP** suffit à
  cadencer la lecture côté sonnette. `STDIN_CHUNK_SIZE` **2048 → 512** (64 ms, moins de latence).
- **`encoder.py`** : le `+10dB` brut **SATURAIT** (= bruit parasite). Remplacé par une chaîne voix
  anti-clip dans `FFMPEG_CMD_ALAW` :
  ```
  -af highpass=f=200,lowpass=f=3500,afftdn=nr=12:nf=-35,speechnorm=e=6.25:r=0.0005:l=1,alimiter=limit=0.9
  ```
  (passe-bande téléphonique, débruiteur, normalisation voix sans clip, limiteur final).
- **Vidéo** basculée **ch1 → ch3 (640×480)** dans go2rtc.yaml (libère le radio WiFi de la sonnette).

### Diagnostic restant (délai ~3 s + hachage résiduel)
La capture `/api/log` montre **0 drop / 0 erreur côté pont** → le problème n'est PAS notre encodage.
Suspect principal = le **chemin LIVE** : navigateur → WebRTC (Opus) → go2rtc (transcode **G.711 8 kHz**)
→ stdin. Le délai/hachage vient de là (jitter buffer + bande étroite 8 kHz imposée par le backchannel
exec de go2rtc), pas du codec AAC. À traiter séparément.

### GOTCHA double-micro (peut causer bruit/brouillage)
Ne PAS laisser l'embed ouvert avec `microphone` sur **plusieurs appareils** en même temps (ex. Mac
**et** portable) : deux micros poussent vers la sonnette simultanément (le Mac capte le bruit ambiant)
→ bruit parasite + audio brouillé. **Tester le talk sur UN SEUL appareil** (le portable près de la
sonnette), onglet Mac fermé (ou vidéo seule, sans `microphone`).

### Adresse de référence (favori) + permanence
URL stable à mettre en favori (ne change jamais) :
```
https://philhomeassist.duckdns.org/webrtc/embed?url=doorbell_talk&media=video+audio+microphone&mode=webrtc
```
Le lien `doorbell_talk` (AlexxIT, en mémoire) est rendu **permanent** par une **automatisation HA**
(✅ créée le 2026-06-26) : trigger `homeassistant start` → action `webrtc.create_link`
(`link_id: doorbell_talk`, `url: aqara_doorbell_192_168_1_38`, ttl 0, open_limit 0). Donc le favori
marche même après reboot HA.

### DÉPLOIEMENT Mac → HA (méthode qui marche ; `core-ssh` a `wget`, pas scp)
1. Sur le Mac, servir le dossier : `cd ~/Downloads/aqara-doorbell-main\ 2/custom_components/aqara_doorbell && python3 -m http.server 8765 --bind 0.0.0.0` (Mac = **192.168.1.30**).
2. Dans le terminal HA :
   ```bash
   cd /config/custom_components/aqara_doorbell
   cp bridge.py bridge.py.bak ; cp encoder.py encoder.py.bak
   wget -O bridge.py  http://192.168.1.30:8765/bridge.py
   wget -O encoder.py http://192.168.1.30:8765/encoder.py
   grep -n afftdn encoder.py ; grep -n STDIN_CHUNK_SIZE bridge.py   # vérif
   ```
3. Redémarrer go2rtc depuis le Mac : `curl -s -X POST http://192.168.1.76:1984/api/restart`
   (PAS besoin de redémarrer HA → lien `doorbell_talk` conservé). Sauvegardes `.bak` sur HA.

### Pistes si la qualité reste insuffisante
- **Délai/hachage (chemin live)** : pas réglable dans le codec. Pistes = réduire le jitter buffer
  go2rtc/WebRTC, ou (mieux) **implémenter le talk-back directement dans l'app Android** en envoyant
  l'AAC/RTP à la sonnette (port 54323 + contrôle 54324) sans passer par go2rtc/WebRTC — voir
  `aqara_lan_talk.py` (banc de test §8) qui fait exactement ça en direct.
- **Volume** : ajuster `speechnorm` (`e=`) ou viser un niveau cible ; éviter de revenir au gain brut.
- **Bande étroite « téléphone »** résiduelle = limite du G.711 8 kHz du backchannel go2rtc. Pour du
  16 kHz pleine bande, contourner go2rtc (envoi direct depuis l'app).
