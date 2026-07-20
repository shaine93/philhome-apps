# Organisation des apps & workflow

_Dernière mise à jour : 2026-07-20_

Ce document décrit l'organisation des 3 applications Android, leurs dépôts,
et le workflow pour reprendre le travail (Claude Code) et le partager (Claude web).

## Les 3 apps

| App | Rôle | Emplacement | Dépôt GitHub |
|-----|------|-------------|--------------|
| **Sonnette Vidéo** | Sonnette connectée (intègre Home Assistant) | `philhome-apps/apps/sonnette-video` | `shaine93/philhome-apps` (monorepo) |
| **Trading Claude God** | App de trading | `philhome-apps/apps/trading-claude-god` | `shaine93/philhome-apps` (monorepo) |
| **BMW Sena Watcher** | Bascule audio BMW / Bluetooth Sena | `AndroidStudioProjects/BMWSenaWatcher` | `shaine93/BMWSenaWatcher` (séparé) |

- `philhome-apps` est un **monorepo conteneur** (pas de Gradle racine) : chaque app
  sous `apps/` est un projet Android indépendant.
- **BMW reste un dépôt séparé** pour préserver son historique (jusqu'à v2.7).

## Reprendre le travail avec Claude Code

Claude Code retrouve l'historique des conversations **par chemin de dossier**.

```bash
# Sonnette Vidéo
cd ~/AndroidStudioProjects/philhome-apps/apps/sonnette-video && claude --resume

# BMW Sena
cd ~/AndroidStudioProjects/BMWSenaWatcher && claude --resume

# Trading (pas d'historique existant : nouvelle session)
cd ~/AndroidStudioProjects/philhome-apps/apps/trading-claude-god && claude
```

- `--resume` (`-r`) : choisir parmi les sessions passées.
- `--continue` (`-c`) : reprendre directement la dernière.

## Workflow git & partage avec Claude web

Claude web (claude.ai) lit la **branche par défaut** de chaque dépôt sur GitHub.

- **Monorepo `philhome-apps`** : travail directement sur `main` → un simple
  `git push` suffit.
- **BMW `BMWSenaWatcher`** : travail sur la branche `dev`, la branche par défaut
  est `main`. Après une session, aligner `main` sur `dev` pour que Claude web voie
  la version à jour.

### Raccourci `git syncmain`

Un alias git global pousse la branche courante vers `main` (fast-forward sûr,
jamais de `--force`) :

```bash
git config --global alias.syncmain '!git push origin HEAD:main'
```

Usage (depuis `dev` sur BMW, par ex.) :

```bash
git syncmain
```

## Secrets (JAMAIS dans git)

Les secrets sont lus au build depuis le home de l'utilisateur, **hors dépôt** :

- Token Home Assistant : `~/.ha_token`
- Clé API Anthropic : `~/.claude_api_key`

Le `.gitignore` exclut par ailleurs : `google-services.json`,
`fcm-service-account.json`, `*.keystore`, `*.jks`, `*.env`, `**/.claude/`,
ainsi que les binaires `*.apk` / `*.aab` (livrés via GitHub Releases / OTA).

> ⚠️ Ne jamais coller de valeur de token/clé dans un fichier versionné (y compris
> les `.md`). Documenter uniquement l'emplacement des secrets, jamais leur contenu.
