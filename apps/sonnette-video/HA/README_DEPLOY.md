# Déploiement automatisé de l'app (plus de wget manuel)

But : `bash HA/publish.sh "notes"` build l'APK **et** le déploie sur HA tout seul.
Les téléphones se mettent ensuite à jour d'un tap (bouton « Mettre à jour l'app »).

## Setup — UNE SEULE FOIS

Ajouter un `shell_command` qui fait tirer les fichiers par HA depuis le Mac.
Dans `configuration.yaml`, **sous le bloc `shell_command:` existant** (à côté de
`sonnette_register`, `sonnette_ring`…), ajouter cette ligne (même indentation) :

```yaml
  sonnette_pull_apk: "python3 -c \"import urllib.request as u; u.urlretrieve('http://192.168.1.30:8771/sonnette-video.apk','/config/www/sonnette-video.apk'); u.urlretrieve('http://192.168.1.30:8771/sonnette-version.json','/config/www/sonnette-version.json'); print('pulled')\""
```

⚠️ NE PAS créer un deuxième `shell_command:` — ajouter la ligne SOUS celui qui existe déjà.

Puis recharger (Outils de développement → YAML → « Toute la configuration YAML »,
ou redémarrer HA — sans risque, l'auto de démarrage recrée les liens vidéo).

- `192.168.1.30` = IP fixe du Mac sur le LAN. Port `8771` = serveur servi par `publish.sh`.
- On utilise `python3` (garanti dans le conteneur HA Core) plutôt que `wget` (pas garanti).

## À chaque publication (automatique)

```bash
bash HA/publish.sh "notes de version"
```

publish.sh : bump déjà fait dans `app/build.gradle.kts` (versionCode +1) → build →
copie l'APK dans `HA/` → démarre le serveur 8771 si besoin → appelle
`shell_command.sonnette_pull_apk` via l'API HA → vérifie que `/local/sonnette-version.json`
sert bien la nouvelle version. Ensuite, sur les téléphones : « Mettre à jour l'app ».