# Lave-vaisselle Bosch « au soleil » — décalage de charge Home Assistant

> **But** : lancer le lave-vaisselle **Bosch (Home Connect)** au moment le moins cher — **surplus solaire**
> d'abord, sinon **heures creuses** la nuit — au lieu de démarrer en heures pleines. On auto-consomme le
> solaire (économise 0,17–0,225 €/kWh) au lieu de le revendre à 0,13 €/kWh.
> Déployé sur HA (`https://philhomeassist.duckdns.org`) le **2026-07-13**.

Contexte énergie complet (2 systèmes solaires, tarif, ROI) : voir `ANKER_OPTIMISEUR.md` §12–§13.

---

## 1. Pourquoi le lave-vaisselle (et pas les autres appareils)

Seul appareil du foyer **vraiment pilotable proprement** :
- **Lave-vaisselle Bosch** : sur **Home Connect** → vrai démarrage à distance (sûr, pas de bidouille prise).
- Machine à laver / sèche-linge : sur prises Zigbee2MQTT (détection de puissance) mais **démarrage manuel
  obligatoire** (couper/rallumer la prise ne relance pas le cycle) → au mieux une notification.
- **Ballon thermodynamique** : **pas dans HA** (non pilotable). La `climate.pompe_a_chaleur_air_eau` =
  chauffage maison (hiver seulement, inutile l'été). Pas de piscine.

**Gain** : lave-vaisselle ~60 kWh/an → **~5-8 €/an**. Petit, mais c'est le **modèle réplicable** (même
schéma sur le ballon le jour où un relais connecté ~30 € le rend pilotable — gros gisement).

---

## 2. Le garde-fou « jamais de machine vide / sans pastille »

- **HA ne peut lancer QUE si l'utilisateur a armé le départ à distance** sur la machine
  (`binary_sensor.bosch_402110522136012064_bsh_common_status_remotecontrolstartallowed` = `on`, exigence
  Bosch : chargé + programme choisi + départ à distance activé). → **impossible de lancer une machine non armée**.
- **Risque humain restant** : armer en ayant oublié la **pastille** → lavage à l'eau claire. Couvert par
  une automatisation de **rappel** (§4.2).

---

## 3. Entités Home Connect (device `bosch_402110522136012064`)

| Rôle | Entité | Valeur cible |
|---|---|---|
| **Armé (départ à distance autorisé)** | `binary_sensor..._bsh_common_status_remotecontrolstartallowed` | `on` |
| Porte | `binary_sensor..._bsh_common_status_doorstate` | `off` (fermée) |
| État machine | `sensor..._bsh_common_status_operationstate` | `BSH.Common.EnumType.OperationState.Ready` |
| **Démarrage** | `button..._start_pause` | (bouton — press) |
| Programme sélectionné | `select..._programs` / `sensor..._selected_program` | Auto + **SpeedPerfect+** (VarioSpeed Plus) |
| Départ différé | `select..._bsh_common_option_startinrelative` | (non utilisé) |
| Signal surplus (net foyer) | `sensor.ecojoko_consommation_temps_reel` | < −1000 W = surplus |
| Tarif | `input_select.tarif_courant` | hp / hc |

⚠️ `button..._start_pause` est un **toggle** (start/pause) → ne l'actionner **que** si `Ready` + armé
(sinon on mettrait en pause un cycle en cours). La condition `remotecontrolstartallowed = on` protège
(Bosch le repasse `off` dès que le cycle tourne).

---

## 4. Automatisations déployées (script `deploy_lave_vaisselle.py`)

### 4.1 `automation.lave_vaisselle_au_soleil_surplus_solaire_hc`
- **Déclencheurs** : `ecojoko_net < −1000 W` pendant **10 min** (vrai surplus, id `solaire`) ; **22:00**
  (filet HC, id `hc`).
- **Conditions** (toutes) : armé `on` + porte `off` + operationstate `Ready`.
- **Actions** : `button.press` sur `start_pause` → notifie « démarré au SOLEIL 🌞 » ou « en HC 🌙 » selon
  le déclencheur → logbook.
- Anti-double-run : si le solaire l'a lancé, à 22 h l'état n'est plus `Ready` → la 2ᵉ condition échoue.

### 4.2 `automation.lave_vaisselle_rappel_pastille`
- **Déclencheur** : `remotecontrolstartallowed` **off→on** (l'utilisateur vient d'armer).
- **Action** : push `notify.notify` **« PASTILLE mise ? »** + notification persistante.

---

## 5. Mode d'emploi (fiche imprimable pour le foyer)

Artifact publié (à imprimer et coller sur le lave-vaisselle) :
**`https://claude.ai/code/artifact/7e83daef-4817-4ac0-bbc7-39c5398eb5fe`**

Résumé des 4 étapes : **1)** ranger la vaisselle · **2) METTRE LA PASTILLE** (mis en rouge) · **3)** fermer
+ programme **Auto + SpeedPerfect+** · **4)** activer **« Départ à distance »** sur la machine. Ensuite HA
le lance au surplus solaire, sinon à 22 h en HC, et notifie. Pour lancer tout de suite : appuyer sur Départ
normalement. Pour annuler l'attente : ouvrir la porte (désarme).

---

## 6. Réglages ajustables

- **Seuil surplus** : `−1000 W` (le lave-vaisselle chauffe par pics ~2000 W ; en canicule le surplus est
  rare → c'est souvent le filet HC de 22 h qui joue).
- **Heure du filet HC** : `22:00` (adapter aux vraies heures creuses « Zen Week-End Plus »).
- Programme piloté = celui que **l'utilisateur** sélectionne sur la machine (Auto + SpeedPerfect+).
