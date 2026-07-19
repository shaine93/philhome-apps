# Détection livreur / présence — documentation détaillée

> **État : DÉPLOYÉ et opérationnel** (première mise en service 2026-07-03, affiné 2026-07-04).
> Fonctionnalité : alerter (notification **douce avec un « dong »** + photo) quand un **livreur
> s'arrête devant la porte SANS sonner**. Ne touche pas au chemin d'appel (sonnerie) qui, lui, reste
> inchangé. Cette doc décrit l'architecture réelle, les composants, les réglages et le débogage.

---

## 1. Objectif & cadrage

- **But** : prévenir en douceur (pas d'écran d'appel, pas de sonnerie forte) que quelqu'un dépose un
  colis / se présente à la porte **sans utiliser la sonnette**. Pensé pour maman (82 ans, en fauteuil) :
  elle *voit* qui est là sans être agressée par une sonnerie d'appel.
- **Cadrage physique** : la caméra de la sonnette Aqara G400 vise **la rue** (carrefour, trottoir,
  voitures garées) au LOIN, et le **pas de porte + la boîte aux lettres** tout PRÈS (bas du cadre).
  ⚠️ La **boîte aux lettres est SOUS la sonnette** → un livreur qui dépose un colis apparaît **grand,
  en bas/centre de l'image, de face**. Un passant apparaît **petit, de profil, sur le trottoir/la rue**.
- **Conséquence** : ~461 mouvements / 48 h (chaque piéton/voiture du carrefour) → **le filtre IA est
  obligatoire**, et il doit distinguer *livreur arrêté* de *passant qui marche*.

---

## 2. Chaîne complète (bout à bout)

```
binary_sensor.doorbell_repeater_74a8_motion_sensor  ->  "on"   (mouvement détecté)
      │
      ├─ CONDITION anti-spam : dernière exécution de l'auto > 3 min
      ├─ CONDITION "pas un vrai appel" : sonnette non pressée depuis > 20 s
      ▼
  📸  camera.snapshot  ->  /config/www/sonnette/presence.jpg      (photo pour la notif, servie en /local)
      ▼
  🤖  ai_task.generate_data (ai_task.openai_ai_task, image en pièce jointe)
        -> { categorie, confiance, description }
      ▼
  SI categorie == "livreur_arrete" :
        ├─ persistent_notification.create           (trace dans l'UI HA)
        └─ shell_command.sonnette_alert  ->  /config/fcm_alert.py  ->  push FCM data type=motion (2 tél)
      SINON (passant / autre / rien) : rien (silencieux)
      ▼
  📱  App (SonnetteMessagingService) : type=motion  ->  DeliveryAlertNotifier
        -> notification DOUCE avec SON « dong » (canal sonnette_presence_v2) + grande photo (BigPicture)
        -> PAS d'écran d'appel, PAS de machinerie de sonnerie
      ▼
  👆  Tap sur la notif  ->  LiveViewActivity : vidéo live door_hi (WebRTC) + SON (on entend la rue)
        -> PAS de bouton « Parler » (on regarde/écoute un livreur, on ne lui parle pas)
```

---

## 3. Côté HOME ASSISTANT

### 3.1 Automatisation `sonnette_detection_livreur`
- Entité : `automation.sonnette_detection_livreur_presence`, alias « Sonnette - Détection livreur / présence ».
- Modifiable via l'API : `POST /api/config/automation/config/sonnette_detection_livreur`.
- **Déclencheur** : `binary_sensor.doorbell_repeater_74a8_motion_sensor` passe à `on`.
- **Conditions** (les 2 doivent être vraies) :
  - anti-spam : `{{ this.attributes.last_triggered is none or (now() - this.attributes.last_triggered) > timedelta(minutes=3) }}`
  - pas un appel : `{{ (now() - states.event.doorbell_repeater_74a8_video_doorbell.last_changed) > timedelta(seconds=20) }}`
- **Actions** :
  1. `camera.snapshot` (`continue_on_error`) de `camera.doorbell_repeater_74a8` → `/config/www/sonnette/presence.jpg`.
  2. `ai_task.generate_data` (voir §3.2), résultat dans `response_variable: ia`.
  3. `if categorie == 'livreur_arrete'` → `persistent_notification.create` + `shell_command.sonnette_alert`
     (`label: "Livreur a la porte"`, `desc: description IA`).

### 3.2 Le cerveau IA (`ai_task.openai_ai_task`, OpenAI)
- Entité `ai_task.openai_ai_task`. Coût ~0,0002 €/image. **⚠️ C'est l'API OpenAI (`platform.openai.com`),
  PAS ChatGPT** : si « Insufficient funds », recharger le solde API (rechargée le 2026-07-03).
- **Pièce jointe** : `media-source://camera/camera.doorbell_repeater_74a8` (snapshot live au moment de l'appel).
- **Structure de sortie** :
  - `categorie` : select `[livreur_arrete, passant, autre, rien]` (required)
  - `confiance` : number 0–100 (required) — *informatif seulement*, voir le piège §5.
  - `description` : text (required)
- **Consigne (prompt), en clair** :
  > Camera de sonnette placée AU-DESSUS d'une boîte aux lettres, à l'entrée d'une maison. Au LOIN : la
  > rue, un carrefour, le trottoir, des voitures garées. TOUT PRÈS, en bas du cadre : le pas de la porte
  > et la boîte aux lettres.
  > Mission : repérer un LIVREUR ARRÊTÉ devant la porte. Réponds `livreur_arrete` UNIQUEMENT si une
  > personne remplit TOUTES ces conditions : (1) tout près de la porte / boîte aux lettres (grande dans
  > l'image, bas/centre du cadre, PAS au loin), ET (2) tournée FACE à la caméra (ni profil, ni dos), ET
  > (3) tient un colis / sac de livraison, OU dépose quelque chose dans la boîte.
  > Réponds `passant` si la personne MARCHE / passe sur le trottoir ou la rue, de profil/de dos, au loin,
  > ou sans colis. `autre` = animal / vélo / véhicule. `rien` = rien d'inhabituel (rue vide, voitures
  > garées, ombres, arbres). En cas de doute entre `livreur_arrete` et `passant`, choisis `passant`.

### 3.3 Push vers les téléphones
- `shell_command.sonnette_alert` → `python3 /config/fcm_alert.py alert "<label>" "<desc>" "<image_url>"`.
- `/config/fcm_alert.py` (**additif** : ne touche PAS `fcm_send_ha.py` qui porte register/ring/cancel) :
  - JWT RS256 signé (clé `/config/fcm-service-account.json`, projet Firebase `home-assistant-305916`).
  - Lit tous les tokens de `/config/sonnette_tokens.json` (robuste à plusieurs formats).
  - Envoie un message **data** `type=motion` (`title`, `text`, `image_url`), priorité `high`, à chaque token.
- **Photo de la notif** : `https://philhomeassist.duckdns.org/local/sonnette/presence.jpg` (servie en
  `/local`, sans authentification → l'app la télécharge directement). Nécessite
  `allowlist_external_dirs: /config/www` + le dossier `/config/www/sonnette/` (déjà en place).

---

## 4. Côté APPLICATION Android

Fichiers dans `app/src/main/java/com/philhome/sonnettevideo/` :

- **`SonnetteMessagingService.kt`** — cas `type=motion` : ne démarre PAS le service d'appel ; lance
  `DeliveryAlertNotifier.show(...)` sur un thread (télécharge la photo). Payload : `type`, `title`,
  `text`, `image_url`.
- **`DeliveryAlertNotifier.kt`** — construit la notification :
  - Canal **`sonnette_presence_v2`** (`Config.MOTION_CHANNEL_ID`), `IMPORTANCE_DEFAULT`.
  - **SON « dong »** = `res/raw/dong.m4a` (généré via ffmpeg : sinus 392/784/1176 Hz + décroissance de
    cloche ~1,6 s), posé sur le canal avec `AudioAttributes(USAGE_NOTIFICATION)`. **Pas de vibration.**
  - `BigPictureStyle` = grande photo. Tap → `LiveViewActivity`.
  - ⚠️ Le son est fixé **à la création du canal** et un canal est **immuable** ensuite → pour changer le
    son il faut **incrémenter l'ID** (`_v1` silencieux → `_v2` dong ; un futur changement = `_v3`).
- **`LiveViewActivity.kt`** — vue « regarder qui est là » : vidéo live `door_hi` (WebRTC) + son (on
  ENTEND), badge « ● EN DIRECT », croix de fermeture, snapshot anti-écran-noir en fond.
  **PAS de bouton « Parler »** (retiré en v0.2.2 : on ne parle pas à un livreur/passant qui n'a pas sonné ;
  le talk-back reste réservé à l'écran d'appel réel `IncomingCallActivity`). `exported=false`.
- **`Config.kt`** — `MOTION_CHANNEL_ID = "sonnette_presence_v2"`, `MOTION_NOTIF_ID = 4201`.

---

## 5. Pièges connus (à ne pas réintroduire)

1. **Échelle de `confiance` incohérente** : l'IA renvoie tantôt `90` (0–100), tantôt `0.95` (0–1). Un
   seuil `confiance >= 70` **bloquerait toutes les alertes** quand elle répond en 0–1. → **On ne gate PAS
   sur la confiance** ; on se fie à la seule `categorie == 'livreur_arrete'` (le prompt est strict et
   « en cas de doute → passant »). La confiance reste affichée à titre indicatif.
2. **Une seule photo ⇒ pas de vraie mesure « à l'arrêt »** : l'IA ne voit pas le mouvement sur 1 frame.
   Le « arrêté » est *simulé* par les critères proche + de face + colis (un passant est de profil, loin,
   sans colis). Si insuffisant → passer à la **version 2 photos** (§7).
3. **Bug SSL du Python du Mac** (`CERTIFICATE_VERIFY_FAILED`) quand on POST vers HA en `urllib` : soit
   `ssl.create_default_context(cafile="/etc/ssl/cert.pem")`, soit générer le JSON en Python puis POST via
   `curl` (recommandé).
4. **Ne PAS réécrire `fcm_send_ha.py`** pour ajouter l'alerte : l'alerte vit dans `fcm_alert.py` séparé
   (register/ring/cancel doivent rester intacts).

---

## 6. Tester & déboguer

- **Tester le push → app** (sans attendre un vrai livreur) :
  `POST /api/services/shell_command/sonnette_alert?return_response`
  body `{"label":"Test","desc":"..."}` → attendu `tokens=2 ... HTTP 200`.
- **Tester le prompt IA** sur la vue actuelle : `POST /api/services/ai_task/generate_data?return_response`
  avec `entity_id`, `instructions`, `structure`, `attachments` → renvoie `{categorie, confiance, description}`.
- **Log de l'app** (méthode debug-first) : `bash DEBUG/pull-log.sh` (ou
  `adb -s <serial> pull /sdcard/Android/data/com.philhome.sonnettevideo/files/debug/sonnette-debug.log`).
  Chercher `FCM push type=motion` (réception) et `LiveView` (ouverture de la vue).
- **Vérif visuelle** : la notif s'ouvre sur `LiveViewActivity` (non-exportée → non lançable par `adb am
  start` ; passer par un push réel puis taper la notif, puis `adb exec-out screencap`).
- **Déploiement de l'app** : `bash HA/publish.sh "notes"` build ET déploie tout seul sur HA
  (via `shell_command.sonnette_pull_apk`, cf `HA/README_DEPLOY.md`) ; sur les téléphones = bouton
  « Mettre à jour l'app ». **Un changement de prompt/automatisation (côté HA) ne nécessite AUCUN update
  de l'app** : ça s'applique au prochain mouvement.

---

## 7. Évolution possible — version « 2 photos » (vraie détection du « à l'arrêt »)

Si la version 1 photo reste trop bavarde **ou** rate de vrais livreurs :
1. `camera.snapshot` → `presence1.jpg`
2. `delay` ~4 s
3. `camera.snapshot` → `presence2.jpg`
4. `ai_task.generate_data` avec **les 2 images** en pièces jointes + consigne : « la même personne est-elle
   présente et à peu près à la MÊME position sur les 2 images (= arrêtée) et face à la porte avec un colis ? ».
5. Alerte seulement si oui.

Point d'attention : attacher deux fichiers locaux/temporels à `ai_task` (au lieu de la caméra live) est à
valider (chemin `media-source`). Non prioritaire tant que la v1 filtre correctement.

---

## 8. Phase 2 (plus tard) — remplacer le cerveau par Frigate

HA Green ne peut pas faire tourner Frigate (pas de Coral / trop faible). Option : Frigate sur la **seedbox
OVH** (37.187.77.37) + tunnel vers le flux LAN. **Le côté app ne change pas** : Frigate publierait juste
`type=motion` via le même `shell_command`. Non prioritaire.

---

## 9. Version 2 déployée (2026-07-15) — règle « arrêté = alerte »

> Découverte du 15/07 : lors d'un test réel, l'IA a classé Philippe **« passant »** alors qu'il faisait
> le livreur, parce que l'ancienne consigne exigeait **colis + face caméra** et retombait sur « passant »
> au moindre doute (il était de profil, perçu « sur le trottoir »). **Aucune notif** → c'était ça la panne.

**Nouvelle règle (dictée par Philippe)** : le critère n'est PAS le colis, c'est **arrêté vs en marche**.
- **`personne_arretee`** = personne **arrêtée / debout devant la porte ou la boîte** (livreur **OU
  rôdeur** — dans les deux cas on alerte), **même de profil ou de dos**, avec ou sans colis → **ALERTE**.
- **`passant`** = personne qui **marche / de passage**, même si elle regarde la maison. « Un passant,
  même s'il regarde, il marche. »
- **`autre`** (animal/vélo/véhicule), **`rien`** (rue vide).
- **En cas de doute → `personne_arretee`** (mieux vaut une alerte de trop que rater un livreur/rôdeur).

**Changements techniques** (`automation.sonnette_detection_livreur`) :
- consigne IA réécrite autour de *arrêté-près-de-la-porte vs en-mouvement* ;
- catégories : `personne_arretee / passant / autre / rien` ; alerte si `personne_arretee` ;
- **cooldown 3 min → 90 s** (re-analyse plus souvent, évite de rater le livreur) ;
- **chaque verdict IA est journalisé** dans le logbook (« Detection livreur | IA verdict = … ») → debug
  en situation réelle sans deviner.

**Bonus sécurité** : cette règle détecte aussi un **rôdeur** qui stationne devant la porte — utile pour maman.

**À valider en réel : livreur attendu vendredi 2026-07-17** (voir `SONNETTE_ROBUSTESSE.md` §12).
La v1 « 2 photos » (§7) reste l'évolution suivante si la v2 rate encore des livreurs.
