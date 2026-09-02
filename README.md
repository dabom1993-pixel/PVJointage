# PV Jointage — Application Android

Application Android native (Kotlin) reprenant le fichier **PV_Jointage_-_HA.xlsm**,
destinée à une utilisation sur tablette pour le contrôle terrain des joints/brides.

## 🚀 Obtenir l'APK SANS RIEN INSTALLER (recommandé)

Ce projet contient un fichier `.github/workflows/build-apk.yml` qui compile
automatiquement l'application dans le cloud (via GitHub Actions, gratuit).
Vous n'avez besoin que d'un navigateur web.

**Voir les étapes détaillées plus bas dans la conversation, ou dans la section
"Compilation automatique via GitHub" de ce document.**

Résumé :
1. Créer un compte GitHub gratuit (github.com)
2. Créer un nouveau dépôt et y déposer (glisser-déposer) le contenu de ce dossier
3. Onglet "Actions" → le build se lance automatiquement
4. Télécharger l'APK généré dans les "Artifacts" une fois le build terminé (~5-10 min)
5. Transférer l'APK sur la tablette et l'installer

---

## 📱 Mettre à jour la tablette par QR code (un seul scan, toujours la dernière version)

Le workflow `.github/workflows/build-apk.yml` publie désormais l'APK compilé
sur une **release GitHub à tag fixe `tablette-latest`**, qui est remplacée à
chaque nouveau run. L'URL de téléchargement ne change donc jamais :

```
https://github.com/dabom1993-pixel/PVJointage/releases/download/tablette-latest/PVJointage.apk
```

Le QR code [`docs/qr-tablette.png`](docs/qr-tablette.png) encode exactement
cette URL :

![QR code de mise à jour tablette](docs/qr-tablette.png)

**Usage :**
1. Onglet **Actions** du dépôt GitHub → workflow **Build APK** → **Run workflow**.
2. Attendre la fin du build (~5-10 min).
3. Sur la tablette, scanner **une seule fois pour toutes les mises à jour**
   le QR code ci-dessus (imprimé ou collé sur la tablette) : le navigateur
   télécharge directement `PVJointage.apk`, qui contient toujours la dernière
   version compilée. Ouvrir le fichier téléchargé pour l'installer
   (autoriser "Sources inconnues" au premier scan).

Comme la clé de signature debug est stable (voir plus bas), chaque nouvelle
installation remplace proprement la précédente sans perte de données, sans
avoir à désinstaller l'app ni à régénérer le QR code.

**Alternative une fois l'app installée : le bouton logo.** Sur l'écran
principal, toucher le **logo Groupe ADF** (en haut à gauche) déclenche une
recherche de mise à jour directement depuis l'app :
- Aucune mise à jour disponible → message "Pas de mise à jour nécessaire".
- Une mise à jour est disponible → téléchargement en arrière-plan (barre de
  progression), puis installation. Aucune connexion à un compte n'est
  demandée. Seule la confirmation système d'installation reste incontournable
  (protection Android : impossible à supprimer par du code) — au tout premier
  usage, il faut aussi autoriser une fois "Installer des applications
  inconnues" pour PV Jointage.

Les données locales (base de données, photos, schémas) ne sont **jamais
effacées** par cette mise à jour : tant que le package et la clé de
signature restent identiques (clé de debug stable, voir plus bas), Android
traite l'opération comme une simple mise à jour de l'app existante, pas
comme une désinstallation/réinstallation. Une copie de sécurité de la base
est en plus faite automatiquement juste avant chaque installation.

---

## Ce qui a été repris de l'Excel

| Onglet Excel | Équivalent dans l'app |
|---|---|
| `1-Exemple`  | **Écran principal** (seul écran visible au démarrage) : en-tête Client/Lieu/Date/Fait par, sélection Unité → Type d'équipement → ITEM, tableau des brides avec statuts Etiquette/Joint/Boulonnerie/Assemblage/Conforme |
| `B-Champ`    | **Écran de contrôle terrain**, ouvert en appuyant sur une ligne du tableau. Formulaire fidèle aux cases O/N/A de l'Excel, calcul de conformité identique aux formules d'origine, prise de photo |
| `1-Plan`     | **Galerie photo par ITEM** (bouton "Photos item"). Les photos prises depuis B-Champ ou depuis cet écran sont stockées et indexées par le nom de l'ITEM, comme dans l'onglet 1-Plan |
| `1-Trame` (Tableau1) | Catalogue des 708 brides de référence, importé depuis `assets/brides.csv` |
| `DATA`       | Listes de valeurs (DN, PN, matières...) extraites, disponibles dans `assets/` pour évolutions futures |

## Logique de conformité (reproduite fidèlement)

Les formules Excel de l'onglet B-Champ ont été traduites en Kotlin dans
`app/src/main/java/com/adf/pvjointage/model/Etat.kt` :

- **Etiquette** : conforme si "Mise et serrée" = O ET "Nom/Date lisible" = O
- **Joint** : conforme si Matière ∈ {O,A} ET Dimension/centrage ∈ {O,A} ET Aspect neuf = O
- **Boulonnerie** : conforme si Neuves ∈{O,A}, Rondelles ∈{O,A}, Equilibrage=O, Graissage=O, Longueur/Diamètre=O, Matière ∈{O,A}
- **Assemblage** : conforme si Parallélisme = O ET Excentration = O
- **Global** : conforme si les 4 sections sont conformes ; "en attente" si une case n'est pas encore remplie

## Stockage & export

- **Stockage local** : base de données Room (SQLite) embarquée dans l'app — fonctionne
  entièrement hors connexion. Les photos sont stockées dans le stockage privé de l'app
  (`Android/data/com.adf.pvjointage/files/photos`).
- **Export** (bouton en haut à droite de l'écran principal) :
  - **CSV** compatible Excel (structure proche de l'onglet 1-Exemple)
  - **PDF** avec le tableau des statuts + une page par photo

Les fichiers exportés sont écrits dans
`Android/data/com.adf.pvjointage/files/exports` sur la tablette.

## Ouvrir le projet

1. Installer **Android Studio** (dernière version stable, ex. Koala/Ladybug).
2. `File > Open...` puis sélectionner le dossier `PVJointage` (celui contenant `settings.gradle.kts`).
3. Laisser Android Studio synchroniser Gradle (il régénère automatiquement le
   wrapper manquant `gradle-wrapper.jar` au premier sync).
4. Brancher une tablette Android (mode développeur + débogage USB activé) ou
   utiliser un émulateur, puis cliquer sur **Run ▶**.
5. Au premier lancement, l'application importe automatiquement les données de
   référence (`assets/brides.csv` et `assets/items.csv`) dans sa base locale.

### Générer un APK à installer directement

Dans Android Studio : `Build > Build Bundle(s) / APK(s) > Build APK(s)`.
L'APK généré (`app/build/outputs/apk/debug/app-debug.apk`) peut être copié et
installé directement sur la tablette (autoriser "Sources inconnues").

## Points à personnaliser selon vos besoins

- **Icône de l'application** : un icône simple (`res/drawable/ic_launcher.xml`)
  a été mis en place à titre provisoire — remplacez-le par votre logo si besoin.
- **Listes déroulantes DATA** (Pouce, Série, etc.) : les données sont extraites
  dans `data_lists.json` (non encore intégrées à l'UI) si vous souhaitez les
  utiliser pour enrichir des formulaires de saisie de nouvelles brides.
- **Ajout de nouvelles brides/items** : actuellement les catalogues sont en
  lecture seule (import CSV). Un écran d'ajout/édition peut être ajouté si vous
  gérez des équipements qui ne figurent pas encore dans l'Excel d'origine.
- **Signature de l'APK** pour une distribution en dehors d'Android Studio
  (release signée) : à configurer dans `app/build.gradle.kts` avec votre propre
  keystore.

## Structure du projet

```
PVJointage/
├── app/src/main/java/com/adf/pvjointage/
│   ├── data/       → Room (base de données locale), import CSV, Repository
│   ├── model/      → Enum Etat + calcul de conformité (logique des formules Excel)
│   ├── ui/         → MainActivity (1-Exemple), ChampActivity (B-Champ), PhotosActivity (1-Plan)
│   └── export/     → Export CSV / PDF
├── app/src/main/assets/
│   ├── brides.csv  → catalogue des 708 brides (extrait de l'onglet 1-Trame)
│   └── items.csv   → catalogue des 75 items (extrait de l'onglet 1-Plan)
└── app/src/main/res/  → mises en page, couleurs, textes
```
