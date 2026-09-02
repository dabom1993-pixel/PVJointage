package com.adf.pvjointage.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Catalogue des ITEM (équivalent de l'onglet "1-Plan" : Unité / Famille / Item).
 * Données de référence, importées une seule fois depuis assets/items.csv
 */
@Entity(tableName = "item_catalog", indices = [Index(value = ["unite", "famille", "item"], unique = true)])
data class ItemCatalog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val unite: String,
    val famille: String,
    val item: String
)

/**
 * Catalogue des brides / repères par ITEM (équivalent de Tableau1 dans l'onglet "1-Trame").
 * Données de référence, importées une seule fois depuis assets/brides.csv
 */
@Entity(
    tableName = "bride_catalog",
    indices = [Index(value = ["unite", "famille", "item", "rep"], unique = true)]
)
data class BrideCatalog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val unite: String,
    val famille: String,
    val item: String,
    val rep: String,
    val designation: String,
    val dn: String,
    val pn: String,
    val matiereJoint: String,
    val rondelle: String,
    val matiereBoulon: String,
    // Colonnes "LgB" / "DiamB" (longueur et diamètre de boulon de référence), ajoutées à
    // l'onglet Excel après le lancement du projet — vides pour un import qui ne les a pas.
    val longueurBoulon: String = "",
    val diametreBoulon: String = "",
    // Colonne "NeufB" (boulonnerie neuve de référence : "Oui"/"Non"), ajoutée après coup —
    // vide pour un import qui ne l'a pas.
    val neufBoulon: String = ""
)

/**
 * En-tête du PV (équivalent des cellules Client / Lieu / Date de "1-Exemple").
 * Un seul enregistrement actif à la fois (id fixe = 1).
 */
@Entity(tableName = "pv_header")
data class PvHeader(
    @PrimaryKey val id: Long = 1,
    val client: String = "",
    val lieu: String = "",
    val date: String = "",
    // Conservé pour compatibilité de schéma avec les bases déjà installées sur les tablettes
    // (retirer la colonne nécessiterait une migration Room) ; le champ "Fait par" n'existe
    // plus dans l'interface et cette valeur n'est plus utilisée.
    val faitPar: String = "",
    val uniteSelectionnee: String = "",
    val familleSelectionnee: String = "",
    val itemSelectionne: String = ""
)

/**
 * Résultat de contrôle terrain (onglet "B-Champ") pour une bride donnée.
 * Clé métier = unite + famille + item + rep (une ligne par bride contrôlée).
 */
@Entity(
    tableName = "inspection_result",
    indices = [Index(value = ["unite", "famille", "item", "rep"], unique = true)]
)
data class InspectionResult(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val unite: String,
    val famille: String,
    val item: String,
    val rep: String,

    // ETIQUETTE
    val etiMiseSerree: String = "",
    val etiNomDateLisible: String = "",

    // JOINT
    val jointMatiereConforme: String = "",
    val jointDimensionCentrage: String = "",
    val jointAspectNeuf: String = "",

    // BOULONNERIE
    val boulonNeuves: String = "",
    val boulonRondelles: String = "",
    val boulonEquilibrage: String = "",
    val boulonGraissage: String = "",
    val boulonLongueurDiametre: String = "",
    val boulonMatiere: String = "",

    // ASSEMBLAGE
    val assemblageParallelisme: String = "",
    val assemblageExcentration: String = "",

    // Texte libre, saisi entre les sections ASSEMBLAGE et PHOTO (5 lignes maximum).
    val remarque: String = "",

    val dateModification: Long = System.currentTimeMillis()
)

/**
 * Photo prise sur le terrain, rattachée à un ITEM (équivalent de l'onglet "1-Plan",
 * colonnes "Photo 1".."Photo N", indexées par le nom de l'item).
 */
@Entity(tableName = "photo")
data class Photo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val unite: String,
    val famille: String,
    val item: String,
    val rep: String = "",
    val filePath: String,
    val dateAjout: Long = System.currentTimeMillis()
)

/**
 * Schéma / plan de l'équipement associé à un ITEM, affiché sur la moitié droite de
 * l'écran "1-Exemple" (équivalent de l'image "SCHEMA / PLAN DE L'EQUIPEMENT" présente
 * dans l'Excel d'origine, colonne R de l'onglet 1-Exemple).
 */
@Entity(tableName = "item_schema", indices = [Index(value = ["unite", "famille", "item"], unique = true)])
data class ItemSchema(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val unite: String,
    val famille: String,
    val item: String,
    val filePath: String
)

/**
 * Traçabilité des révisions d'un ITEM après export PDF : dès qu'un export PDF a été fait pour
 * cet item, toute modification ultérieure (contrôle ou photo, sur n'importe quelle bride de
 * l'item) le fait passer en révision. La révision n'avance qu'une seule fois par cycle : elle
 * reste affichée telle quelle tant qu'aucun nouvel export ne vient la figer comme référence.
 */
@Entity(tableName = "item_revision", indices = [Index(value = ["unite", "famille", "item"], unique = true)])
data class ItemRevision(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val unite: String,
    val famille: String,
    val item: String,
    // Révision courante de l'item (0 = jamais modifié après un export).
    val revision: Int = 0,
    // Révision figée lors du dernier export PDF (-1 = jamais exporté : pas de traçabilité à afficher).
    val exportedRevision: Int = -1,
    val lastModified: Long = System.currentTimeMillis()
)

/**
 * Instantané des contrôles d'une bride (hors photos, non historisées) tel qu'il était lors du
 * dernier export PDF de son item. Sert à savoir, à l'export suivant, quelles brides ont changé
 * depuis (ExportManager) pour dupliquer leur page détail "de base" + "révisée". Remplacé en
 * totalité (par item) à chaque export réussi — voir Repository.markItemExported.
 */
@Entity(tableName = "inspection_baseline", indices = [Index(value = ["unite", "famille", "item", "rep"], unique = true)])
data class InspectionBaseline(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val unite: String,
    val famille: String,
    val item: String,
    val rep: String,

    val etiMiseSerree: String = "",
    val etiNomDateLisible: String = "",

    val jointMatiereConforme: String = "",
    val jointDimensionCentrage: String = "",
    val jointAspectNeuf: String = "",

    val boulonNeuves: String = "",
    val boulonRondelles: String = "",
    val boulonEquilibrage: String = "",
    val boulonGraissage: String = "",
    val boulonLongueurDiametre: String = "",
    val boulonMatiere: String = "",

    val assemblageParallelisme: String = "",
    val assemblageExcentration: String = "",

    val remarque: String = ""
)

/** Copie les champs de contrôle (hors id/dateModification) vers un instantané "base". */
fun InspectionResult.toBaseline(): InspectionBaseline = InspectionBaseline(
    unite = unite, famille = famille, item = item, rep = rep,
    etiMiseSerree = etiMiseSerree, etiNomDateLisible = etiNomDateLisible,
    jointMatiereConforme = jointMatiereConforme, jointDimensionCentrage = jointDimensionCentrage, jointAspectNeuf = jointAspectNeuf,
    boulonNeuves = boulonNeuves, boulonRondelles = boulonRondelles, boulonEquilibrage = boulonEquilibrage,
    boulonGraissage = boulonGraissage, boulonLongueurDiametre = boulonLongueurDiametre, boulonMatiere = boulonMatiere,
    assemblageParallelisme = assemblageParallelisme, assemblageExcentration = assemblageExcentration,
    remarque = remarque
)

/** Reconstruit un [InspectionResult] "vitrine" (id/dateModification arbitraires) à partir d'un instantané, pour réutiliser telles quelles les fonctions de dessin du PDF. */
fun InspectionBaseline.toInspectionResult(): InspectionResult = InspectionResult(
    unite = unite, famille = famille, item = item, rep = rep,
    etiMiseSerree = etiMiseSerree, etiNomDateLisible = etiNomDateLisible,
    jointMatiereConforme = jointMatiereConforme, jointDimensionCentrage = jointDimensionCentrage, jointAspectNeuf = jointAspectNeuf,
    boulonNeuves = boulonNeuves, boulonRondelles = boulonRondelles, boulonEquilibrage = boulonEquilibrage,
    boulonGraissage = boulonGraissage, boulonLongueurDiametre = boulonLongueurDiametre, boulonMatiere = boulonMatiere,
    assemblageParallelisme = assemblageParallelisme, assemblageExcentration = assemblageExcentration,
    remarque = remarque
)
