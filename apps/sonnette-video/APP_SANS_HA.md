# APP SANS HOME ASSISTANT — état, architecture, reprise

> **But du document** : tout consigner pour **reprendre** le chantier « rendre l'app Sonnette
> autonome, sans Home Assistant ». État au **2026-07-05**.
> Docs liées : `ETAT_TALKBACK_SONNETTE.md` (interphone actuel), `DETECTION_LIVREUR.md`.

---

## 0. Pourquoi (le problème)

Home Assistant (HA Green, chez maman) est aujourd'hui le **point de panne unique** de la sonnette :
sonnerie, vidéo, son, talk-back passent tous par lui. **Quand HA tombe, plus rien ne fonctionne.**

**Objectif** : une APK qui parle **en direct à la sonnette Aqara G400** (192.168.1.38), sans HA dans
le chemin critique — et **meilleure** qu'Aqara là où il est grossier (audio non filtré = vent, latence).

Accessibilité = priorité (maman, 82 ans, fauteuil) : voir + entendre + parler à la porte, fiable.

---

## 1. Comment Aqara fait (analyse de son APK, 2026-07-05)

APK `com.lumiunited.aqarahome.play` v6.3.5 tirée du tél de test (`adb pull` base.apk + split arm64).

⚠️ **Décompilation statique BLOQUÉE** : app packée **SecNeo** (`com.secneo.apkwrapper` + `libDexHelper.so`).
Le vrai code est chiffré/déchiffré au runtime → jadx ne voit que le stub (137 classes). Pour aller plus
loin il faudrait un **dump runtime (Frida, tél rooté)** — non fait. MAIS les **libs natives + ressources
(non protégées) révèlent toute l'archi** :

| Fonction Aqara | Techno (preuve = lib native / ressource) |
|---|---|
| **Contrôle + événements (dont APPUI)** | **Matter** — `libCHIPController.so`, res `com.google.home.matter`, OTA `aqara.matter.4447_8195`. **Local, sur le WiFi.** |
| **Vidéo à distance (5G)** | **TUTK/ThroughTek Kalay P2P** — `libPPCS_API.so` (propriétaire, licencié, brokeré par le cloud → la latence/lourdeur en 5G) |
| **Vidéo LAN** | **RTSP** — `liblive555.so`, `librtsp-lib.so` (= la « LAN Preview », ce qu'on exploite) |
| **Lecteur** | ijkplayer (`libijkplayer/ijkffmpeg/ijksdl`) — ffmpeg |
| **Découverte** | mDNS — `libjdns_sd.so` |
| **Push (appui quand absent)** | **Cloud Alibaba** — `libtnet-3.1.14.so`, `assets/tae_sdk_plugins/cloudpush.properties` (AGOO) + FCM |

**Conclusion « faire mieux »** :
- **En local** : Aqara route même du local via cloud/P2P → nous = **RTSP direct + talk direct** = plus
  basse latence, hors cloud, hors HA.
- **L'appui** : c'est un **événement Matter local** ; Aqara le notifie via **cloud Alibaba** (latence +
  dépendance). Nous = capter l'event Matter en local + **notre propre push FCM** → mieux.
- **Vidéo 5G** : Aqara = TUTK (non réutilisable). Nous = **relais léger** (RTSP→WebRTC) au lieu du P2P lourd.

---

## 2. Ce qui dépend de HA aujourd'hui vs ce que la sonnette fait EN DIRECT

**Dépend de HA (à remplacer)** :
1. **Vidéo** = `WebrtcVideo.kt` (WebView + go2rtc/embed AlexxIT via HA) + snapshot `camera_proxy`.
2. **Sonnerie** = HA détecte l'appui via le **répéteur Aqara exposé en HomeKit**
   (`event.doorbell_repeater_74a8_video_doorbell`) et envoie le FCM. ⚠️ Le protocole LAN Aqara
   reverse-engineeré **n'a AUCUN event « bouton pressé »** (`protocol.py` = START/STOP_VOICE/ACK/HEARTBEAT
   seulement). → **le vrai inconnu du projet.** Piste = Matter (cf §1).
3. **Filtre anti-vent** = dans go2rtc (preset `opuswind`) → déplacé DANS l'app (§4).
4. **Portail** (`GateController` → webhook HA), **cancel multi-tél** (`CallCoordinator` → webhook HA).
5. **Canal de MAJ de l'app** (`Config.APK_URL` = HA `/local`) — Phase 4.

**La G400 (192.168.1.38) sait faire EN DIRECT, sans HA** :
- **RTSP** (vidéo H264 + audio AAC) : `rtsp://697:363@192.168.1.38:8554/ch1` (ch2=960p, ch3=480p).
  Auth **digest** (serveur live555). Identifiants saisis dans l'app Aqara (« RTSP LAN Preview »).
- **Talk-back LAN direct** : TCP **54324** (contrôle) + UDP **54323** (RTP, AAC-LC 16k). Code déjà en
  place : `AqaraTalkProtocol.kt` + `DoorbellTalk.kt` `DirectTransport`.

**Réseau ≠ serveur** : la 5G n'exige rien de spécial — le tél reçoit FCM nativement (comme
Snapchat/Teams). Le seul besoin « serveur » pour la sonnerie = un **mini-expéditeur « appui → FCM »**,
minuscule, hébergé hors HA. Ce n'est PAS « zéro serveur » (impossible pour réveiller un tél endormi en
5G), c'est « pas le HA fragile ».

---

## 3. Architecture cible (par phases)

```
   [À LA MAISON = LAN]                          [À DISTANCE = 5G]
   app ── RTSP direct ──▶ sonnette G400         app ◀── FCM (appui) ── mini-expéditeur (hors HA)
   app ── talk direct ──▶ (TCP54324/UDP54323)   app ── talk relais ──▶ (mini-relais, pas HA)
   app ◀─ event Matter ── (appui, local)        app ── vidéo relais ─▶ (RTSP→WebRTC léger)
```

- **Phase 1 (FAIT)** : mode LAN-direct dans l'app (vidéo RTSP + talk direct + filtre vent), sous
  interrupteur de transition. → §4.
- **Phase 2** : la **sonnerie sans HA** — capter l'appui (event **Matter** local) + **notre expéditeur
  FCM** (réutiliser `fcm_send_ha.py` hors HA). → §7.
- **Phase 3** : **vidéo/talk à distance (5G)** hors HA — mini-relais RTSP→WebRTC + repointer le talk relais.
- **Phase 4** : couper le cordon — MAJ de l'app hors HA, retrait du secours HA.

---

## 4. PHASE 1 — FAIT + VALIDÉ EN RÉEL (2026-07-05)

**But atteint** : *HA en panne + à la maison → on ouvre l'app → on voit, entend (filtré) et parle à la
porte, en direct.* Prouvé sur le tél de test (c614e0cc / 22081212UG) : capture = image live de la rue +
« PARLEZ MAINTENANT » vert ; log = `chemin: RTSP DIRECT`, `PLAYING (Vout)` ~0,9-2 s,
`DirectTransport … 600 trames AAC envoyées`.

### 4.1 Vidéo directe = **libVLC** (PAS ExoPlayer)
⚠️ **ExoPlayer/Media3 NE MARCHE PAS avec le G400.** Diagnostiqué via logcat `RtspClient` : l'auth digest
passe (200 OK), la SDP arrive, mais la piste vidéo est :
```
m=video 0 RTP/AVP 97
a=rtpmap:97 H264/90000
a=control:track2          ← PAS de ligne "a=fmtp:97 ..." (SPS/PPS envoyés in-band)
```
ExoPlayer **exige** le `fmtp` et jette `IllegalArgumentException: missing attribute fmtp`. ffmpeg/VLC
(comme l'ijkplayer d'Aqara) tolèrent. → décodeur = **libVLC** (`org.videolan.android:libvlc-all:3.6.0`).

`RtspVideo.kt` : `LibVLC` + `MediaPlayer` + `VLCVideoLayout`, options clés :
- `--rtsp-tcp` (RTP interleaved sur TCP, fiable derrière NAT local),
- `--rtsp-frame-buffer-size=2000000` (sinon les I-frames 1200p dépassent le buffer par défaut 250 Ko →
  `frame size exceeds buffer, bytes dropped` = artefacts),
- `--network-caching=250` / `--live-caching=250` (latence basse), HW decode (`setHWDecoderEnabled`).
- Callback `onPlaying` = event `MediaPlayer.Event.Vout` (1ʳᵉ sortie vidéo).

### 4.2 Filtre anti-vent IN-APP = **égaliseur VLC**
`RtspVideo.applyWindEqualizer()` : `MediaPlayer.Equalizer`, bandes <100 Hz à **-20 dB**, <300 Hz à
**-12 dB** (le « BRRRUUT » du vent vit sous ~300 Hz). Équivalent client-side du preset go2rtc `opuswind`
(qui, lui, reste déployé pour le chemin HA). NB : le `Biquad` RBJ a été extrait de `VoiceFilter.kt` vers
`Biquad.kt` (réutilisable) ; le `WindAudioProcessor` ExoPlayer initial a été supprimé (VLC ≠ ExoPlayer).

### 4.3 Talk-back direct
`DoorbellTalk(Config.DOORBELL_IP, useRelay = false)` → `DirectTransport` (existait déjà, cf
`ETAT_TALKBACK_SONNETTE.md`). Sélectionné quand on est en LAN, sinon relais HA.

### 4.4 Sélecteur réseau
`Lan.isDoorbellOnLan()` (`Lan.kt`) = sonde TCP courte (600 ms) sur `192.168.1.38:54324` (port contrôle
talk = le plus spécifique). Vrai → chemin direct possible ; faux → secours HA.

### 4.5 Câblage écran d'appel
`IncomingCallActivity.startVideo()` : sonde en tâche de fond → `onLan = directPref && isDoorbellOnLan()`
→ `startVideoDirect()` (RTSP libVLC + `DoorbellTalk(useRelay=false)`) sinon `startVideoHa()` (WebRTC +
snapshot HA, code historique **conservé en secours**). `VLCVideoLayout` remplace la `WebView` sur le
chemin direct. `showAnswered()` dé-mute la vidéo (`rtsp?.unmute()`).

### 4.6 Fichiers (Phase 1)
- **Nouveaux** : `Biquad.kt`, `RtspVideo.kt` (libVLC), `Lan.kt`, `Prefs.kt`.
- **Supprimé** : `WindAudioProcessor.kt` (était ExoPlayer).
- **Modifiés** : `app/build.gradle.kts` (dép libVLC, `abiFilters arm64-v8a`, version 5/0.3.0),
  `Config.kt` (`RTSP_USER=697`, `RTSP_PASS=363`, `RTSP_CONTROL_PORT=54324`), `VoiceFilter.kt` (Biquad
  extrait), `IncomingCallActivity.kt` (chemin direct + gating interrupteur), `MainActivity.kt` (interrupteur).

---

## 5. Déploiement de TRANSITION (« ne rien casser »)

**Interrupteur** `Prefs.directLan` (`Prefs.kt`, SharedPreferences `sonnette_prefs` / clé
`direct_lan_enabled`, **défaut OFF**). UI = section **« Mode (transition) »** dans `MainActivity`
(bouton « Direct LAN : activé/désactivé »). OFF = chemin HA historique (rien ne change) ; ON = direct
(bypass), avec **repli auto sur HA** si la sonnette n'est pas joignable (donc ON ne casse rien non plus).

**État déployé** :
- **v0.3.0 (versionCode 5)** installée **UNIQUEMENT sur le tél de test (c614e0cc / 22081212UG)** via adb.
  Les 2 positions de l'interrupteur validées : OFF→`WebRTC/HA`, ON→`RTSP DIRECT … PLAYING (Vout)`.
- **Tél de maman (Redmi ruby) NON touché.**
- ⚠️ **Manifeste de MAJ HA volontairement figé à `versionCode 4`** (= version de maman) pour qu'elle **ne
  soit PAS invitée à se mettre à jour**. L'APK servi en `/local/sonnette-video.apk` = la version SÛRE (à
  interrupteur, ~59,6 Mo). → build.gradle local = code 5 mais manifeste servi = code 4 (**désaccord VOULU**).
- **Installer sur un AUTRE tél à soi** (le tél de test est chez maman, près du Mac) : ouvrir dans Chrome
  `https://philhomeassist.duckdns.org/local/sonnette-video.apk` → installer. N'affecte pas le manifeste
  → maman ne voit rien. (Si erreur de signature : désinstaller l'ancienne d'abord.)
- **Rouler officiellement vers maman plus tard** : quand le direct est éprouvé, rebump le manifeste (via
  `publish.sh` qui lit build.gradle) → bouton « Mettre à jour » proposera la v0.3.0 aux 2 tél.

---

## 6. Build, déploiement, test

### Build (CLI)
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # JBR (JDK 21)
cd ~/AndroidStudioProjects/Sonnettevideo
./gradlew :app:assembleDebug            # APK = app/build/outputs/apk/debug/app-debug.apk
```
- `abiFilters += "arm64-v8a"` dans `defaultConfig` : sinon libVLC bundle TOUTES les ABI → APK ~192 Mo.
  arm64 seul ≈ **56 Mo** (les 2 tél sont arm64).

### Déploiement
- **Tél branché** : `adb -s <serial> install -r -d app/build/outputs/apk/debug/app-debug.apk`.
- **Canal MAJ intégré** (fleet) : `bash HA/publish.sh "notes"` (build + copie APK dans `HA/` + génère
  `sonnette-version.json` + HA `shell_command.sonnette_pull_apk` tire les 2 fichiers → `/local`). ⚠️ ça
  RE-sert le code de build.gradle → ne PAS lancer tant qu'on ne veut pas notifier maman (cf §5). Détails
  = `HA/README_DEPLOY.md`.

### Test & debug (méthode debug-first)
- Lancer l'écran d'appel sans sonnette : `adb -s <serial> shell am start -a ANSWER -n com.philhome.sonnettevideo/.IncomingCallActivity`.
- Log app : `bash DEBUG/pull-log.sh` (ou `adb ... pull /sdcard/Android/data/com.philhome.sonnettevideo/files/debug/sonnette-debug.log`).
  Chercher `chemin:` (direct vs HA), `RtspVideo] PLAYING (Vout)`, `DirectTransport`, `trames AAC`.
- Échange RTSP d'ExoPlayer (si on y revient) : `adb logcat | grep RtspClient` (montre DESCRIBE/SDP/401).
- Vidéo VLC : `adb logcat | grep -i VLC` (buffer, décodeur, PCR).
- Vérif visuelle : `adb exec-out screencap -p > x.png` (VLC = SurfaceView, capturable).
- Basculer l'interrupteur en test (sans l'UI) : pousser `shared_prefs/sonnette_prefs.xml`
  (`<boolean name="direct_lan_enabled" value="true"/>`) via `run-as`, app arrêtée.

---

## 7. PHASE 2 — la sonnerie sans HA (à faire, le vrai morceau)

**Rappel** : livraison du push = triviale (FCM, tout réseau). Le travail = la **source** de l'appui.

### 7.1 SOURCE DE L'APPUI IDENTIFIÉE (2026-07-06, via API HA)
L'appui n'arrive NI par le cloud NI par le protocole LAN Aqara. Il vient du **répéteur/carillon Aqara
CH-C11E**, exposé à HA en **HomeKit local** :
- entité `event.doorbell_repeater_74a8_video_doorbell`, `event_types: ['ring']`,
- device : manufacturer **Aqara**, model **CH-C11E**,
  `identifiers: {('homekit_controller:accessory-id', 'CC:DD:AF:96:35:A9:aid:1')}`.
- ⇒ **l'appui = un événement HomeKit (HAP) local**, captable sans HA ni cloud. (Distinct de la caméra
  G400 @192.168.1.38 qui fait RTSP/talk : le répéteur CH-C11E fait ring + motion + carillon + batterie.)
- Il existe `switch.doorbell_repeater_74a8_pairing_mode` → **HomeKit est multi-contrôleur** : on peut
  AJOUTER notre contrôleur à côté de HA sans dépairer HA.

### 7.2 Approche retenue = écouteur HomeKit standalone → notre FCM
Outil = **`aiohomekit`** (la lib Python que HA utilise pour homekit_controller). Un petit service
(~50-100 lignes, HORS HA) qui : (1) est appairé au répéteur CH-C11E, (2) souscrit à la caractéristique
`ring`, (3) au ring → appelle **`fcm_send_ha.py ring`** (register/ring/cancel déjà écrits) → le tél sonne
via `SonnetteMessagingService` (existant), 5G comme WiFi. Réutilise la brique éprouvée de HA, sans en
dépendre. (Env Mac de dev : venv python@3.14 + aiohomekit — scratchpad `hkenv`.)

### 7.3 HomeKit : coexistence avec HA = BLOQUÉE (essai 2026-07-06)
- Codes fournis par l'utilisateur : **HomeKit 8 chiffres = `06056963`** (→ `060-56-963`) ;
  **Matter 11 chiffres** (régénéré à chaque ouverture, cf 7.4).
- Tenté d'ajouter notre contrôleur via `aiohomekit` (Mac) : `pair -d cc:dd:af:96:35:a9 -p 060-56-963`,
  même avec `switch.doorbell_repeater_74a8_pairing_mode` = ON → **erreur TLV `0x06 [Unavailable]`** à
  l'étape M2 = le pair-setup HomeKit est refusé car l'accessoire est **déjà appairé (à HA)**. En HomeKit,
  ajouter un 2e contrôleur ne se fait PAS par un nouveau pair-setup+code, mais par « Add Pairing » réalisé
  par le contrôleur ADMIN (HA) — non exposé par HA. (pairing_mode remis OFF, entités HA vérifiées saines.)
- ⇒ **HomeKit-coexistence impossible** sans soit dépairer HA (casse la détection livreur = capteur
  mouvement du répéteur), soit **réutiliser les clés d'appairage de HA** (elles sont dans HA
  `.storage/core.config_entries` ; HAP autorise plusieurs sessions sur le MÊME pairing → HA + notre boîte
  reçoivent le ring en parallèle, rien ne casse ; nécessite de LIRE ce fichier via un add-on File Editor/
  Terminal HA).

### 7.4 Matter : INFRA MONTÉE, mais la sonnette n'expose RIEN (cul-de-sac pour le ring)
Comme la sonnette s'annonce **commissionnable en Matter** (`_matterc._udp`→192.168.1.38) et que Matter
est **additif** (n'affecte pas HA), on a tout monté :
- **Hôte = le Pi OctoPrint** `192.168.1.136` (user `Philippe` / mdp `Tarzan93320*`, SSH ouvert, Debian 12).
  Était **Raspbian 32-bit (armhf)** → contrôleur Matter (`home-assistant-chip-core`) = **wheels 64-bit
  seulement**. Basculé **noyau 64-bit** : `sudo sed -i 's/^arm_64bit=0/arm_64bit=1/' /boot/firmware/config.txt`
  + reboot → `uname -m` = **aarch64**. Installé **Docker** (`docker.io`). Lancé le serveur Matter en
  conteneur **arm64** :
  ```
  sudo docker run -d --name matter-server --restart unless-stopped --network host \
    --security-opt seccomp=unconfined -v ~/matter-data:/data --platform linux/arm64 \
    ghcr.io/home-assistant-libs/python-matter-server:stable
  ```
  ⚠️ `--security-opt seccomp=unconfined` OBLIGATOIRE (sinon exit **159/SIGSYS** : daemon armhf + binaire
  arm64 = numéros de syscalls incompatibles). Serveur WS = `ws://192.168.1.136:5580/ws` (SDK 2025.7.0,
  fabric_id 1). Côté Mac : venv `hkenv` + `python-matter-server` (client) + `paramiko` (SSH via
  `sshrun.py`, mdp dans `pi_creds.json`, helper `--sudo` qui passe le mdp par stdin).
- **Codes Matter DYNAMIQUES** : régénérés à chaque ouverture de fenêtre. La fenêtre ferme après ~15 min
  (`_matterc._udp` disparaît → « Discovery timed out »). Le bon code final = **`1240-273-2722`**.
- **COMMISSIONNÉ ✓ node_id=2** (`client.commission_with_code(code, network_only=True)`). BasicInformation =
  **Aqara Smart Video Doorbell G410**, vendor Aqara, productID 2050.
- **MAIS interview complète = COQUILLE VIDE** : ep0 Root + **ep1 Aggregator (device_type 0x0E) avec
  PartsList VIDE**, clusters ep1 = juste Identify+Descriptor. **AUCUN cluster Switch / bouton / ring
  exposé en Matter.** ⇒ **Matter ne peut PAS fournir l'appui sur ce modèle** (support Matter = placeholder).
- Scripts (scratchpad `hkenv`) : `commission.py`, `auto_commission.py` (guette `_matterc` + commissionne),
  `introspect.py`, `dump2.py`.
- ⚠️ **Le commissioning Matter DÉCLENCHE des alertes « association » côté Aqara** : le fabric
  `python-matter-server` apparaît dans l'app Aqara comme **« testVendor »** (il utilise le vendor-id de
  TEST 0xFFF1). Pour retirer proprement l'association, il faut la RETIRER **côté sonnette** (elle stocke
  le fabric), PAS juste effacer le Pi (sinon fabric fantôme). 2 voies : (a) app Aqara → sonnette → Matter/
  contrôleurs → supprimer « testVendor » ; (b) contrôleur : `client.remove_node(2)`.
- ✅ **NETTOYAGE FAIT (2026-07-06)** : « testVendor » supprimé dans l'app Aqara (association retirée) ;
  sur le Pi OctoPrint : conteneur + `~/matter-data` supprimés, **Docker désinstallé** (`apt purge docker.io`),
  `arm_64bit` remis à **0** (32-bit au prochain reboot). Le Pi OctoPrint est **redevenu propre**.

### 7.5 CONCLUSION conceptuelle — pourquoi une « boîte » est INCONTOURNABLE
Aqara **n'est pas autonome** : la sonnette **téléphone au cloud Aqara** (le `libtnet`/Alibaba de l'APK),
et **c'est le cloud Aqara qui pousse la notif** au téléphone. La « boîte au milieu » = leur cloud.
**Vérité de fond** : un téléphone qui DORT ne se réveille que par une notif envoyée par UN serveur
toujours-allumé. Il y a donc **toujours** un intermédiaire : cloud du fabricant, OU HA, OU un Pi. **Zéro
serveur = impossible** (même en local, la notif finale passe par Google/FCM ; le Pi/HA ne fait que
DÉTECTER l'appui en local et DÉCLENCHER la notif). L'utilisateur veut du **100 % local (pas de cloud)** →
ça IMPOSE une boîte locale. Le vrai but « sans HA » = **remplacer le gros HA fragile par un petit relais
increvable**, pas « zéro serveur ».
- **Le Pi ne DÉCODE jamais la vidéo** (c'est le téléphone). Au pire (5G) il ne fait que **relayer** les
  octets (façon go2rtc, recopie H264 sans transcoder). Talk-back relayé = quelques Ko d'audio = négligeable.
- À LA MAISON : vidéo+talk **en direct tél↔sonnette** (Phase 1, déjà fait) → **le Pi n'est pas impliqué**.

### 7.6 PLAN DE REPRISE — Pi 3B+ DÉDIÉ (👈 ON REPREND ICI)
L'utilisateur **dédie un 2e Pi 3B+** (en cours de préparation). **Matter étant mort-né, PLUS besoin de
Docker ni de 64-bit** : la sonnerie = **HomeKit via `aiohomekit`** (pur Python, marche en 32-bit).

**Ce que l'utilisateur prépare** : Raspberry Pi OS **Lite** (via Raspberry Pi Imager), **SSH activé** +
identifiant/mot de passe réglés dans l'imager, **RJ45** branché. Puis il donne **IP + login**.

**Ce que j'installe sur le Pi dédié** (via SSH/paramiko) :
1. venv Python + **`aiohomekit`** (+ cryptography).
2. **`fcm_send_ha.py`** (réutilisé, register/ring/cancel) + la clé service-account Firebase.
3. **Script écouteur** : `aiohomekit` s'abonne à l'event **`ring`** du device `cc:dd:af:96:35:a9` →
   sur ring → `fcm_send_ha.py ring` → le tél sonne (via `SonnetteMessagingService`, 5G comme WiFi).
4. **Service systemd** (auto-restart, start au boot) = increvable.
5. (Phase 3 plus tard) **go2rtc** sur ce même Pi pour le relais vidéo 5G.

**DÉCISION EN ATTENTE = méthode d'appairage HomeKit** (question posée à l'utilisateur : a-t-il un add-on
HA File Editor / Studio Code Server / Samba / Terminal ?) :
- **B (si accès fichiers HA)** : réutiliser l'appairage HomeKit de HA (lire `.storage/core.config_entries`,
  extraire le `data` de l'entrée `homekit_controller` de la sonnette) → le donner à `aiohomekit -f`.
  HA + Pi reçoivent le ring, **rien ne casse**. ⭐ préféré.
- **A (sinon)** : dépairer la sonnette de HA → pairer le Pi (`aiohomekit pair`, pair-setup redevient dispo).
  Le Pi devient le « cerveau » de la sonnette. HA perd le device → **renvoyer le mouvement à HA par
  webhook** pour garder la détection livreur. Plus de travail mais c'est l'endgame « sans HA » propre.

**Répartition finale visée** : HomeKit = appui ; RTSP = vidéo (direct) ; LAN Aqara = talk (direct) ;
Pi = relais 5G ; FCM = réveil du tél.

---

## 8. Gotchas à ne pas réintroduire

1. **ExoPlayer ≠ G400** : SDP sans `fmtp` H264 → `missing attribute fmtp`. Utiliser **libVLC** (§4.1).
2. **libVLC APK** : mettre `abiFilters arm64-v8a` (sinon ~192 Mo). Buffer RTSP à 2 Mo (I-frames 1200p).
3. **App Aqara packée SecNeo** : statique inutile → dump runtime Frida si on veut le Java.
4. **Manifeste MAJ figé code 4** (§5) : `publish.sh` le RE-bump → ne pas lancer sans le vouloir.
5. **Ne PAS toucher le tél de maman** tant que le direct n'est pas éprouvé ; interrupteur défaut OFF.
6. **`go2rtc.yaml` / chemin HA** : reste le secours (interrupteur OFF), ne pas casser. Preset `opuswind`
   anti-vent reste utile côté HA. Ne PAS remettre `webrtc.ice_servers` (casse le WebRTC HA).
7. **Le tél branché au Mac (c614e0cc) est chez MAMAN** (le Mac + HA Green sont chez elle). Le tél de
   l'utilisateur est un AUTRE appareil, à distance → l'installer via l'URL `/local` (§5), pas en adb.

---

## 9. Références rapides

- Sonnette G400 : IP **192.168.1.38**. RTSP `rtsp://697:363@192.168.1.38:8554/ch1|ch2|ch3` (digest, live555).
  Talk : TCP **54324** contrôle + UDP **54323** RTP (AAC-LC 16k mono). Matter (WiFi).
- go2rtc (secours HA) : **192.168.1.76:1984**. HA : `https://philhomeassist.duckdns.org`.
- Mac (sert les MAJ) : **192.168.1.30**, port 8771. `JAVA_HOME` = JBR Android Studio.
- Tél test : c614e0cc / 22081212UG (arm64, chez maman). Tél maman : Redmi Note 12 Pro « ruby » / 22101316G.
- Firebase : projet `home-assistant-305916`. Push custom : `fcm_send_ha.py` (register/ring/cancel).
