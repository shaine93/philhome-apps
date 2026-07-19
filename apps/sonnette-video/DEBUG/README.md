# DEBUG — diagnostic sans deviner

L'app écrit un **journal de debug persistant** sur le téléphone. Après un test (même en 5G,
sans câble), on rebranche en USB et on rapatrie le log → on lit ce qui s'est *vraiment* passé,
et on corrige directement (pas de recherche de cause à l'aveugle).

## Où est le log
- Sur le téléphone : `/sdcard/Android/data/com.philhome.sonnettevideo/files/debug/sonnette-debug.log`
- Dans l'app : MainActivity → **« 📋 Voir le log debug »** (affiche les 80 dernières lignes) /
  **« 🗑 Effacer le log debug »**.

## Récupérer le log (Mac)
```bash
bash DEBUG/pull-log.sh
```
→ copie le log dans `DEBUG/sonnette-debug.log` et l'affiche.

## Ce qui est tracé
- **Talk-back** (`DoorbellTalk`) : mode (RELAIS/DIRECT), ouverture transport, effets micro
  (NS/AGC/AEC), nb de trames AAC envoyées, erreurs.
- **Relais 5G** (`RelayTransport`) : URL WS, onOpen/onFailure (code HTTP), fermeture.
- **Écran d'appel** (`IncomingCall`) : onCreate, états du talk (connecting/active/error/stopped).
- **FCM** : push reçu (type + data), nouveau token, résultat d'enregistrement côté HA.

## Workflow type d'un test 5G
1. (optionnel) dans l'app : « 🗑 Effacer le log debug ».
2. Débrancher, WiFi off (5G), aller près de la sonnette.
3. App → « 🎤 Tester le talk-back (direct) » → parler → écouter → « Raccrocher ».
4. Revenir, rebrancher l'USB.
5. `bash DEBUG/pull-log.sh` → on lit, on corrige.