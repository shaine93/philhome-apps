# Optimiseur solaire Anker Solarbank E1600 — automatisation Home Assistant

> **But** : piloter automatiquement la batterie **Anker Solarbank E1600** (1600 Wh, micro-onduleur
> MI80 ~800 W max) pour **maximiser les économies EDF** (ROI) sans aucune manipulation manuelle, et
> **préserver les cellules** (LFP). Remplace le geste manuel « je bascule en décharge / je recharge
> avant la nuit ». Déployé et vérifié sur HA le **2026-07-11**.

- **HA** : `https://philhomeassist.duckdns.org` — intégration `thomluther/ha-anker-solix`.
- **Automatisation** : `automation.anker_optimiseur_solaire_ecojoko_forecast`
  (id interne `anker_optimiseur_solaire`, alias « Anker – Optimiseur solaire (Ecojoko + Forecast) »).
- **Device** : `6a2148d493646510faef6d45e761062b` — SN `AZV6Y60D33400050` — firmware v2.1.5.

> ⚠️ **LOGIQUE ACTUELLE = v8 (jour = charge / nuit = décharge), voir §15.** Les §1–§6 décrivent
> l'évolution v1→v4, §11 la v7 (**périmée, elle vidait la batterie en plein soleil** — voir §15).
> Contexte : installation complète (§12), vrai tarif (§13), debug 24/24 (§14).
> (Le lave-vaisselle « au soleil » est un chantier distinct : voir `LAVE_VAISSELLE_SOLEIL.md`.
> Mes erreurs sur ce projet : `AUDIT_ERREURS.md`.)

---

## 1. Cause racine : pourquoi les anciennes automatisations ne marchaient PAS

L'ancienne `automation.delestage_anker_cycle_batterie_100_30` (créée par « AssistantIA ») écrivait :
- l'entité **`number.solarbank_e1600_limite_de_priorite_de_charge`** (limite de charge), et
- le switch **`switch.solarbank_e1600_decharge_prioritaire`** (`priority_discharge`).

**Ces leviers ne rallument jamais la sortie.** Or le planning avait le créneau **08:00–20:00 avec
`turn_on: false`** → **sortie physiquement coupée toute la journée**. Résultat : batterie pleine à
midi = 0 W, rien ne se passe. Prouvé par un test contrôlé (écriture 800→300→800 W sur le `number`
« puissance de sortie » : la lecture réelle est restée à **0 W** pendant 60 s).

**Le vrai levier** = `allow_export` (= le `turn_on` du créneau), exposé par le switch
**`switch.solarbank_e1600_autoriser_l_exportation`**. Preuve en direct (2026-07-11 20:45) :
basculer ce switch OFF a fait passer le planning `turn_on: True → False` et la décharge **198 W → 0 W**.
`delestage_100_30` est désormais **désactivée** (turn_off) pour éviter tout conflit.

---

## 2. Leviers & capteurs utilisés

| Rôle | Entité | Note |
|---|---|---|
| **Levier ON/OFF sortie** | `switch.solarbank_e1600_autoriser_l_exportation` | = `allow_export`/`turn_on` du créneau actif |
| **Levier puissance** | `number.solarbank_e1600_reglage_puissance_de_sortie_du_systeme` | = `appliance_load` (0–800 W utile) |
| Relecture puissance | `sensor.solarbank_e1600_reglage_puissance_de_sortie` | pour vérifier que l'écriture a atteint la batterie |
| Charge batterie (SOC) | `sensor.solarbank_e1600_etat_de_charge` | % |
| Conso maison **réelle** | `sensor.ecojoko_consommation_temps_reel` | W, temps réel (Ecojoko) |
| Production solaire prévue | `sensor.power_production_now` | W (Forecast.Solar — **surestime**, cf. §6) |
| Élévation soleil | `sun.sun` attr `elevation` | pilote jour/nuit (seuil 8°) |
| Lever du soleil | `sensor.sun_next_rising` | dosage décharge nuit |
| Tarif courant | `input_select.tarif_courant` | options **`hp`** / **`hc`** |
| Réserve matérielle | `select.solarbank_e1600_reserve_soc` | = 10 % (plancher device) |

Service de planning (levier alternatif, non utilisé en routine) :
`anker_solix.update_solarbank_schedule` — champs SB1 dans `sb1_fields` :
`allow_export` (bool), `discharge_priority` (bool), `charge_priority_limit` (0–100), `device_load` (50–800).

---

## 3. Algorithme (la stratégie ROI complète)

L'automatisation tourne **toutes les 15 min** + sur événements (SOC franchit 99/90, coucher/lever,
démarrage HA). À chaque passage elle calcule un **état voulu** `(want_on, want_power)` puis n'écrit
QUE si ça diffère de l'état courant (limite les appels cloud).

**Constantes** : `sun_floor = 8°`, `seuil solaire export = 150 W`, hystérésis export `97–99 %`,
garde température `0–50 °C`. **Cible de décharge du matin (`dawn_target`) selon la météo de DEMAIN**
(`sensor.energy_production_tomorrow`) : ≥8 kWh → **10 %** (vider, ensoleillé + santé cellules) ;
4–8 kWh → **25 %** ; <4 kWh → **40 %** (garder de la réserve pour un jour sans soleil). Le plancher
matériel reste 10 %.

**Découpage jour / nuit par l'élévation du soleil** (`elev = sun.sun.elevation`) :

| Situation | Condition | Sortie | Puissance |
|---|---|---|---|
| **JOUR – charge** | `elev>8` et batterie pas pleine | **OFF** | — (tout le solaire charge la batterie) |
| **JOUR – pleine → réseau** | `elev>8`, `solar>150 W`, SOC≥99 (hyst. 97) | **ON** | `min(solaire, 800)` — le solaire part maison/réseau au lieu d'être écrêté ; batterie reste pleine |
| **NUIT/soir – HP (cher)** | `elev≤8`, tarif `hp`, SOC>`dawn_target` | **ON** | `min(conso maison, 800)` — couvre le gros consommateur au prix fort |
| **NUIT/soir – HC (bon marché)** | `elev≤8`, tarif `hc`, SOC>`dawn_target` | **ON** | `((SOC−dawn_target)×16) ÷ heures_jusqu'au_lever`, borné **80–350 W** → vise `dawn_target` **pile au lever** |
| **Plancher / réserve météo** | SOC ≤ `dawn_target` | **OFF** | — (cellules protégées, réserve gardée si demain couvert) |
| **Sécurité température** | batterie hors 0–50 °C | **OFF** | — (protection cellules, ceinture+bretelles avec le BMS) |

**Cycle quotidien obtenu** (ce que fait la batterie sans intervention) :
1. **Matin, soleil levé** → charge au solaire (sortie OFF).
2. **Journée, une fois pleine** → sortie ouverte au niveau du solaire → on **n'écrête plus**, ça part
   maison (auto-consommé à ~0,26 €/kWh) puis réseau ; la batterie **reste ~100 %**.
3. **Soir / nuit** → décharge : **forte en HP** (économise le prix fort), **douce en HC** (on garde le
   jus pour les HP), en descendant jusqu'à **10 %** au petit matin (bon pour les cellules LFP : pas de
   flottement à 100 %).
4. **Lever du soleil** → on recharge. Boucle.

**Fiabilité d'écriture** : après avoir réglé la puissance, l'automatisation **relit**
`sensor...reglage_puissance_de_sortie` et **réessaie une fois** si l'écriture n'a pas pris (latence
cloud Anker).

---

## 4. Déclencheurs

- `time_pattern` toutes les **15 min** (réévaluation périodique).
- `numeric_state` SOC **> 99** et SOC **< 90** (hystérésis export).
- `sun` **coucher −1h30** et **lever +15 min**.
- `homeassistant` **start** (état correct après reboot).
- `mode: restart`, `max_exceeded: silent`.

---

## 5. Debug détaillé (audit du 2026-07-11, ~21:00)

### 5.1 Audit statique — tout vert
- **Entités utilisées** : les 10 disponibles, valeurs valides, aucune `unavailable`/`unknown`
  (qui casserait un template).
- **Tarif** : `input_select.tarif_courant` options `['hp','hc']` → le test `is_hp` est correct
  (pas de faute de casse qui l'aurait rendu toujours faux).
- **Conflits** : scan de **toutes** les automatisations actives → **aucune autre** n'écrit sur la
  batterie. Ancienne `delestage_100_30` bien **OFF**.
- **Intégration** : cloud `online`, wifi `on`, MQTT frais (horodatage à la minute).
- **Erreurs** : **aucune** notification persistante. (`/api/error_log` renvoie 404 = endpoint
  désactivé sur cette install → contourné par : templates rendus sans erreur + zéro notif + entités OK.)

### 5.2 Preuve que le contrôle atteint la batterie
- Bascule switch OFF → décharge **198 W → 0 W**, planning créneau actif `turn_on: True → False`.
- À 21:00, l'automatisation a ouvert la sortie à **146 W** en soir HC ; `number` = 150 et **relecture
  `reglage_puissance_de_sortie` = 150** → écriture confirmée sur le matériel.

### 5.3 Simulation de CHAQUE branche (moteur Jinja réel de HA, aucune erreur)

| Cas simulé (SOC / élévation / solaire / tarif) | Décision | Sortie | Puissance |
|---|---|---|---|
| MIDI plein + fort soleil (100 / 50° / 1500 W) | pleine→réseau | ON | **800 W** (= min(1500,800)) |
| MIDI plein, faible soleil (100 / 50° / 200 W) | pleine→réseau | ON | **200 W** (passe le solaire dispo) |
| MATIN batterie basse (30 / 20° / 600 W) | JOUR charge | OFF | — (tout le solaire charge) |
| SOIR bas + HC (92 / 5° / — / hc) | décharge HC doux | ON | **146 W** |
| SOIR bas + HP (92 / 5° / — / hp) | décharge HP fort | ON | **800 W** |
| NUIT HP (80 / −15° / hp) | décharge HP fort | ON | **800 W** |
| NUIT HC (80 / −15° / hc) | décharge HC doux | ON | **125 W** (dosé) |
| NUIT plancher atteint (10 / −15°) | plancher | **OFF** | — (cellules protégées) |

### 5.4 Comment surveiller en réel
- **Logbook** : chaque cycle écrit une ligne « Anker Optimiseur » lisible, ex. :
  `DECHARGE HC doux | SOC 92% | tarif hc | elevation 6.5deg | solaire 0W - conso 3670W = surplus -3670W | consigne 146W`.
- **Coûts** : `sensor.cout_jour_hp` / `sensor.cout_jour_hc` / `sensor.cout_mois_*` (suivi ROI).
- **À l'œil** : `switch.solarbank_e1600_autoriser_l_exportation` (ON/OFF) et
  `sensor.solarbank_e1600_puissance_de_decharge` (W réels).
- **Preuve intention = réalité** (2026-07-11 21:07) : sortie ON demandée, consigne 150 W relue 150 W,
  **décharge réelle 143 W**, batterie −143 W, mode `discharge`, SOC 92→91 % → confirmé sur le matériel.

### 5.5 Chien de garde (« savoir si ça ne marche pas »)
2ᵉ automatisation **`automation.anker_chien_de_garde_alerte_si_panne`** (id `anker_watchdog`) qui
**alerte par push (`notify.notify`) + notification HA persistante + logbook** dès qu'un problème
**persiste** (déclencheurs avec `for:` → pas de spam). 4 cas surveillés :
1. **Cloud Anker hors ligne** (`sensor.solarbank_e1600_etat_du_cloud` ≠ online) > 15 min.
2. **Données MQTT figées** (> 25 min sans mise à jour) → plus de remontée.
3. **Optimiseur désactivé** (passe à `off`) ou **ne tourne plus** (`last_triggered` > 40 min).
4. **Incohérence intention/réalité** : en période décharge (élévation ≤ 8°), sortie ON + SOC > 12 %
   mais **décharge réelle < 20 W** pendant 20 min → la commande n'atteint pas la batterie.

Le message d'alerte liste précisément le(s) problème(s). Vérifié : rendu **vide** quand tout va bien
(aucune fausse alerte), pas d'erreur de template.

---

## 6. Garde-fous & limites connues

- **Forecast.Solar surestime** (~24 kWh/jour affiché, irréaliste pour un MI80 ~800 W). On ne s'y fie
  donc PAS pour l'énergie : le jour/nuit est piloté par l'**élévation** (déterministe) ; l'export est
  borné par l'**hystérésis 97 %** (si l'ouverture de sortie ponctionnait la batterie, elle se coupe à
  97 % et recharge → **pas de vidage** en journée).
- **Micro-onduleur 800 W max** : la batterie ne peut jamais dépasser 800 W. Avec une conso maison
  souvent > 800 W, la sortie **réduit l'achat réseau** mais **n'injecte au réseau** que si la conso
  passe sous 800 W. Le ROI vient surtout de l'**auto-consommation** (éviter d'acheter à ~0,26 €/kWh).
- **Réserve 10 %** = plancher matériel ; la décharge s'y arrête pour préserver les cellules LFP.
- **Relecture/retry** actuellement sur la **puissance** uniquement (le switch ON/OFF a marché du
  premier coup en test) — durcissement possible (cf. §8).

---

## 7. Fichiers de déploiement (scratchpad, volatile — la vérité est dans HA)

- `deploy_anker_opt.py` — construit et POST l'automatisation (`/api/config/automation/config/…`),
  désactive l'ancienne, reload.
- `debug_anker.py` — audit statique (entités, tarif, conflits, intégration, erreurs).
- `eval_logic.py` — évalue la logique de décision via `/api/template` et **simule chaque branche**.

Accès : token HA longue durée (fourni par l'utilisateur, exp. 2036), TLS `cafile=/etc/ssl/cert.pem`.

---

## 8. Améliorations possibles (non bloquantes)

1. **Durcir le switch** : relecture + réessai sur `autoriser_l_exportation` comme sur la puissance
   (viser le « 300 % robuste »).
2. **Production solaire réelle** : préférer `sensor.solarbank_e1600_puissance_solaire` à
   `power_production_now` pour l'export, quand la mesure est fiable (évite toute dépendance à
   Forecast.Solar).
3. **Décharge HP en journée** : si un créneau HP tombe en pleine journée avec déficit solaire, couvrir
   la conso avec la batterie (aujourd'hui la journée est réservée à la charge).
4. **Helper de suivi** : template sensor « mode optimiseur » + carte Lovelace dédiée.

---

## 9. Preuve terrain — cycle réel de la nuit 2026-07-11 → 12 (v3.1)

Cycle complet mesuré sur le matériel (aucune intervention) :
```
SOC : 92% ─décharge douce ~145W─► 10% (04:10, au lever) ─recharge solaire─► 92% (12:26)
             48% à 23h42                  14% à 07h15 · 47% à 10h00
```
- Descente régulière jusqu'à **10 % pile au lever**, puis **recharge solaire complète** le matin.
- **~1,35 kWh** délivrés par la batterie (auto-consommés).
- **Chien de garde : 0 alerte** sur 140 cycles.
- Journée **100 % HC** (`cout_jour_hp = 0 €`) → la décharge douce HC a fait le travail ; conso maison
  vue **négative** (−397 W à 07:15) = réinjection solaire déjà en cours.

## 10. Historique des versions

- **v1** : bascule via entités switch/number, jour = charge, nuit = décharge douce. Preuve de contrôle OK.
- **v2** : ajout **HP/HC** (`input_select.tarif_courant`) → décharge forte en HP, douce en HC.
- **v3** : jour/nuit sur **élévation** (supprime la « retenue avant coucher » indésirable) ; décharge
  jusqu'à **10 %** (santé cellules) ; recharge dès le soleil.
- **v3.1** : correction du levier d'export — **passe le solaire réel** (`min(production,800)`) quand la
  batterie est pleine, au lieu d'un « surplus » qui ne se déclenchait jamais (conso maison élevée).
- **v4 (300 %)** : **cible de décharge selon la météo de demain** (`dawn_target` 10/25/40 %) → garde de la
  réserve avant un jour couvert ; **garde température** 0–50 °C ; **auto-guérison de l'interrupteur**
  (relecture + réessai, comme la puissance). Validée par simulation de toutes les branches.
- **v5 (canicule)** : correction du **yoyo 99-100 %** (l'export basé sur Forecast surestimé vidait la
  batterie). Fenêtre midi (elev>35) décharge pour les clims avec **hystérésis large 40-99 %** (plus de flap).
- **v6** : bascule sur le **capteur solaire RÉEL** `sensor.solarbank_e1600_puissance_solaire` (fini
  Forecast) ; passthrough = solaire réel quand pleine (anti-bridage) ; nuit = décharge à fond des clims.
- **v7 (EPsystem-aware, ACTUELLE)** : découverte du 2ᵉ système (§12) → signal maître = **import réseau
  Ecojoko**. Voir §11. C'est la version en production.

---

## 11. Logique v7 en production (EPsystem-aware)

Depuis qu'on sait que le foyer a **deux** systèmes solaires (§12), l'Anker (1,6 kWh) a **un seul rôle
utile** : **raboter l'achat au réseau** quand l'EPsystem 5 kWc ne suffit pas (matin, soir, nuit, grosse
clim). Le signal maître n'est plus l'élévation mais l'**import réseau réel** (Ecojoko).

**Variables clés** :
- `gross_import = ecojoko_net + system_anker_alimentation_domestique_sb` → l'import qu'on aurait **sans**
  l'apport actuel de l'Anker (empêche le flap de feedback : l'Anker ne « poursuit » pas sa propre baisse d'import).
- `dawn_target` (plancher) : 10/25/40 % selon `energy_production_tomorrow` (comme v4).

**Décisions** :

| Situation | Condition | Sortie / Puissance |
|---|---|---|
| **Décharge** (rabote l'import) | `SOC>dawn_target` ET `gross_import > 300 W (HP) / 600 W (HC)` | ON, `min(gross_import, 800)` — couvre l'import RÉEL, **jamais d'overshoot vers l'export** |
| **Passthrough** panneaux Anker | pleine (SOC≥99) + `pv>150` + pas de décharge | ON, `min(pv_Anker, 800)` (anti-bridage de ses 2 panneaux) |
| **Charge / tenue** | sinon (pas pleine, ou EPsystem couvre déjà) | OFF (le solaire Anker remplit la batterie) |
| **Plancher / température** | SOC≤`dawn_target` ou temp hors 0–50 °C | OFF |

Seuil **plus bas en HP (300 W) qu'en HC (600 W)** → la batterie se dépense en priorité quand l'électricité
est chère. Quand l'EPsystem **exporte** (surplus midi), `gross_import` négatif → l'Anker ne décharge pas,
il se charge sur ses panneaux. **Vérifié** (6 branches simulées, moteur Jinja réel) le 2026-07-13.
Débit réel ~1,3 kWh/j, **plus de yoyo** (6 bascules sur 20 h).

**Capteurs v7** (en plus des §2) : `sensor.solarbank_e1600_puissance_solaire` (PV Anker réel),
`sensor.system_anker_alimentation_domestique_sb` (apport Anker à la maison), `sensor.ecojoko_consommation_temps_reel`
(net réseau, + import / − export).

---

## 12. L'écosystème énergétique complet (découvert le 2026-07-13)

Le foyer a **DEUX systèmes solaires** — c'est essentiel :

1. **EPsystem — APsystems 5 kWc** : 12 panneaux × 420 W, **6 micro-onduleurs**, **sans batterie**. Gros
   producteur, feed direct maison/réseau. **Visible dans HA** via la passerelle **ECU** :
   - `sensor.ecu_current_power` (production instantanée W), `sensor.ecu_today_energy` (kWh/j),
     `sensor.ecu_lifetime_energy`, `sensor.ecu_inverters` = 6, + `sensor.inverter_704000XXXXXX_*`
     (tension/temp/signal par onduleur).
2. **Anker Solarbank E1600** : batterie 1,6 kWh + 2 petits panneaux (~760 W crête réel) + micro-onduleur
   MI80. C'est l'objet de cet optimiseur.

**Ecojoko** (`sensor.ecojoko_consommation_temps_reel`) mesure le **net de TOUT le foyer** (les deux
systèmes). C'est pour ça qu'on voyait de l'export alors que l'Anker produisait peu : c'était l'EPsystem.

**Contrainte matérielle** : l'Anker ne charge **que sur ses 2 panneaux** (pas d'entrée AC) → il ne peut
**pas** stocker le surplus de l'EPsystem. Les deux systèmes restent indépendants.

**Ordres de grandeur (audit)** : PV Anker pic réel **566–758 W** (jamais >800 = pas d'écrêtage onduleur) ;
EPsystem ~20 kWh/j en été ; conso maison énorme en canicule (2 clims, ~33 kWh/j, import ~41 kWh/30 h) ;
la batterie Anker = **~3 % de la conso** → ROI structurellement petit (~0,30 €/j).

---

## 13. Tarif & stratégie ROI (confirmé le 2026-07-13)

- **Contrat : EDF « Zen Week-End Plus »** — week-ends (sam+dim) **HC toute la journée** ; semaine HP le
  jour / HC la nuit. Signal : `input_select.tarif_courant` (hp/hc), basculé par
  `automation.zwep_bascule_tarif_hc_hp_selon_jour`.
- **Prix réels** (calculés depuis l'historique `cout_annee_*`) : **HC ≈ 0,17 €/kWh · HP ≈ 0,225 €/kWh**.
- **Revente surplus** : **0,13 €/kWh** (relevé réel : 366,37 € pour 2815 kWh sur 03/07/2025→02/07/2026).
  ⚠️ Le « 0,27 » évoqué au départ était une **erreur**.
- **Conséquence ROI** : revente 0,13 € < prix d'achat 0,17–0,225 € → **auto-consommer vaut mieux que
  vendre**. Donc capter plus du surplus pour soi = le vrai levier (théorique ~0,07 €/kWh basculé).
- HA chiffre déjà le gisement : `sensor.economie_possible_mois` ≈ **10,62 €/mois** (potentiel HP→HC).
- **Limite honnête** : en **été**, aucun puits pilotable pour absorber le surplus (le **ballon thermo
  n'est PAS sur HA** ; la `climate.pompe_a_chaleur_air_eau` = **chauffage maison, hiver seulement** ;
  pas de piscine). Le seul appareil vraiment pilotable = le **lave-vaisselle Bosch** (§15).
- **Piste hiver** : la pompe à chaleur devient un excellent puits → automatiser « pré-chauffer sur le
  surplus » à la saison de chauffe. **Piste matériel** : un relais connecté (~30 €) rendrait le ballon
  pilotable → gros gisement.

---

## 14. Debug 24/24 & audit 7 jours

- **Logger DEBUG** : `automation.anker_debug_decharge_24_24_heure_prevision` (id `anker_debug_decharge`,
  script `deploy_anker_debug.py`). **1 ligne logbook « Anker DEBUG » toutes les 15 min** + à chaque saut
  de puissance batterie > 100 W : heure, batterie (charge/décharge/neutre), PV réel, conso, sortie,
  mode, tarif, élévation, **prévision jour restant + demain**, nébulosité. **Flague le capteur SOC HS**
  (l'intégration Anker cloud passe parfois `unavailable`).
- **Audit 7 j** (`audit7j.py`, via history recorder — penser à `end_time` sinon 1 seul jour renvoyé) :
  bilan par jour (PV pic/kWh, SOC min/max, décharge kWh, conso nuit) + flux **heure par heure**
  (PV / batterie / réseau / SOC / tarif) + ROI chiffré.
- **Constats clés** : batterie cycle **100→10 % chaque jour** (~1,3 kWh livrés) ; **plus de yoyo** en v7 ;
  découverte du **mauvais timing tarifaire** (déchargeait la nuit HC pas chère, à sec le matin HP cher) →
  corrigé par le seuil HP plus bas ; le « crash PV » d'après-midi = **bridage batterie-pleine**, corrigé
  par le passthrough (v6).
- **Watchdog téléphones sonnette** (hors Anker mais même session) : `automation.sonnette_veille_...` alerte
  SMS Free si un téléphone se fige — cf. mémoire `sonnette-securite-fiabilite`.

> **Lave-vaisselle Bosch « au soleil »** : documenté à part dans **`LAVE_VAISSELLE_SOLEIL.md`** (décalage
> de charge, chantier distinct de la batterie Anker). ⚠️ **Automatisations DÉSACTIVÉES** depuis
> l'incident du 2026-07-13 (démarrage non consenti) — voir `AUDIT_ERREURS.md`.

---

## 15. v8 — « jour = charge / nuit = décharge » (2026-07-14, EN PRODUCTION)

### 15.1 Le bug de v7 (constaté en direct le 14/07 à 14:50)

Philippe : « *il est 14h50 et la batterie ne charge pas, plein soleil* ». Relevé de la journée :

| Heure | SoC | Comportement v7 |
|---|---|---|
| 11:45 | 38 % | charge ✅ |
| 12:45 | **50 %** | charge ✅ |
| 13:00 → 14:45 | **50 % → 10 %** | **décharge ~750 W en plein soleil** ❌ |
| 14:50 | 12 % | recharge à 339 W |

- **19 bascules** du switch `allow_export` sur la journée, charge/décharge alternées **toutes les 15 min**.
- Cause : v7 déchargeait dès `soc > 10 %` **et** import réseau > 300 W en HP. En **canicule (2 clims)**, la
  maison importe **en permanence** → la batterie ne pouvait **jamais** accumuler.
- Viole la consigne explicite : *« la décharge doit pouvoir être à 100 % la nuit jusqu'au lever du soleil »*.
- Gain de cet arbitrage tarifaire (HP 0,225 vs HC 0,17) : **~0,09 €/jour**. Prix payé : **usure cellules**.

### 15.2 Deux faits structurels (à ne jamais réoublier)

1. **L'Anker E1600 ne se recharge QUE par ses 2 propres panneaux** (max mesuré **613 W** le 14/07 à 12:17).
   Il **ne peut PAS absorber le surplus de l'EPsystem** (qui sort 3–4,4 kW à côté). C'est le **plafond
   physique** de tout le système.
2. **Quand le switch `allow_export` est ON (décharge), `sensor.solarbank_e1600_puissance_solaire` tombe à
   0 W** — vérifié sur **toutes** les lignes de la journée. Décharger en journée **annule aussi la récolte
   solaire**. v7 était donc doublement perdante.

### 15.3 La règle v8

```jinja
discharge = soc > reserve(10)
            and (house + anker_home) > 150          # la maison importe vraiment
            and ( (not producing)                   # NUIT  -> décharge libre jusqu'à 10 %
                  or soc >= 98                      # JOUR  -> seulement si (quasi) pleine
                  or (exporting_now and soc > 80) ) # JOUR  -> hystérésis 98 → 80 %, pas de yoyo
```

- **JOUR** (`elev > 8°`) = **PRIORITÉ CHARGE**. La batterie ne se décharge que si elle est **quasi pleine**
  (≥ 98 %), et alors elle descend jusqu'à **80 %** avant de recharger → **alimente les clims avec le
  trop-plein**, en **cycles courts et peu profonds**, sans flapping (le switch lui-même sert de mémoire
  d'hystérésis).
- **NUIT** (soleil couché) = **DÉCHARGE libre jusqu'à la réserve 10 %** → tient jusqu'au lever du soleil.
- `passthrough` (PV direct vers la maison quand SoC ≥ 99 %) : inchangé.

### 15.4 Vérification du 1ᵉʳ déclenchement (2026-07-14 15:02, en direct)

| Avant (v7) | Après (v8) |
|---|---|
| switch `on`, **décharge 457 W**, PV **0 W** | switch **`off`**, **décharge 0 W**, **charge 44 W**, PV **46 W** |

→ v8 a bien arrêté la décharge et relancé la charge. **Observé, pas supposé.**

### 15.5 Ce que v8 coûte / rapporte, honnêtement

- **Renonce** à ~**0,09 €/jour** d'arbitrage tarifaire (décharger en HP plutôt qu'en HC).
- **Gagne** : fin du yoyo (19 bascules/j → quelques-unes), **récolte solaire non annulée** (v7 mettait le PV
  à 0 pendant les décharges — le vrai gain caché de v8), **durée de vie des cellules**, et **le comportement
  attendu** (batterie pleine le soir, tient la nuit).
