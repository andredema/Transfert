# Transfert Wi-Fi — téléphone ➜ PC, puis suppression vérifiée

Usage personnel. Deux pièces :

| Dossier | Rôle |
|---|---|
| `pc-serveur/` | Petit serveur Python qui reçoit les fichiers sur l'ordinateur |
| `android/` | Projet de l'application Android (Kotlin, aucune bibliothèque externe) |

## Fonctionnement

1. Le téléphone envoie chaque fichier au PC par le Wi-Fi local.
2. Le PC l'écrit sur le disque, le **relit** et renvoie son empreinte SHA-256.
3. Le téléphone compare avec l'empreinte calculée pendant l'envoi.
4. **Seulement si elles sont identiques**, le fichier est supprimé du téléphone.
   En cas d'erreur, de coupure ou d'arrêt, le fichier reste sur le téléphone.

Sur le PC, les fichiers sont rangés ainsi :
`Transferts/<Marque Modèle>/<Dossier>/<sous-dossiers>/fichier.jpg`
(la date d'origine est conservée ; un fichier identique déjà présent n'est pas dupliqué ;
un fichier différent portant le même nom est renommé `nom (1).jpg`).

---

## 1. Côté PC

Prérequis : Python 3.8 ou plus récent (https://www.python.org — cocher « Add to PATH »).

- Windows : double-clic sur `pc-serveur/Lancer-serveur.bat`
- Ou en ligne de commande :
  ```
  python serveur.py
  python serveur.py --dossier "D:\Sauvegarde telephone" --port 8765
  ```

Le serveur affiche **l'adresse** (ex. `192.168.1.20:8765`) et un **jeton** de 6 caractères.
Le jeton est mémorisé dans `serveur_config.json` : il reste le même d'une fois à l'autre.

**Pare-feu Windows** : au premier lancement, accepter « Autoriser l'accès » pour les
**réseaux privés**. Si la fenêtre n'apparaît pas et que le téléphone ne se connecte pas :
Paramètres ▸ Confidentialité et sécurité ▸ Sécurité Windows ▸ Pare-feu ▸
Autoriser une application ▸ cocher Python (Privé).

## 2. Construire l'APK

### Option A — Android Studio (le plus simple sur ton PC)
1. Installer Android Studio, puis *File ▸ Open* ▸ choisir le dossier `android/`.
2. Laisser la synchronisation Gradle se terminer (accepter les mises à jour proposées si besoin).
3. *Build ▸ Build App Bundle(s) / APK(s) ▸ Build APK(s)*.
4. L'APK se trouve dans `android/app/build/outputs/apk/debug/app-debug.apk`.
   Ou brancher le téléphone en USB et cliquer ▶ Run.

### Option B — GitHub, sans rien installer
1. Créer un dépôt **privé** sur GitHub et y pousser tout le dossier `TransfertWifi`.
2. L'onglet *Actions* lance automatiquement « Construire l'APK » (~5 min).
3. Télécharger l'artefact `TransfertWifi-apk` (un .zip contenant l'APK).

### Installer sur le téléphone
Copier l'APK sur le téléphone, l'ouvrir, autoriser « Installer des applis inconnues »
pour le gestionnaire de fichiers. Play Protect peut afficher un avertissement
(appli non publiée) : choisir *Installer quand même*.

## 3. Utilisation

1. PC et téléphone sur le **même Wi-Fi**. Lancer le serveur.
2. Dans l'appli : *Autoriser l'accès aux fichiers* (une seule fois) → activer
   « Autoriser la gestion de tous les fichiers ».
3. Saisir l'adresse et le jeton → *Tester la connexion*.
4. Choisir un dossier (liste rapide ou chemin), éventuellement « plus de N jours »
   pour ne vider que les anciens fichiers.
5. *Démarrer* → une confirmation affiche le nombre de fichiers et le volume.

Conseils :
- Garder l'appli ouverte pendant le transfert (l'écran reste allumé automatiquement).
- Pour un premier essai, décocher « Supprimer » ou choisir un petit dossier.
- Les fichiers et dossiers cachés (`.thumbnails`, `.trashed`…) sont ignorés.
- Après 3 erreurs réseau de suite, le transfert s'arrête de lui-même.

## Sécurité

- Le trafic n'est pas chiffré (HTTP) : à utiliser uniquement sur ton réseau domestique,
  pas sur un Wi-Fi public.
- Le jeton empêche un autre appareil du réseau d'envoyer des fichiers sur ton PC.
- Le serveur refuse tout chemin qui sortirait du dossier de réception.
