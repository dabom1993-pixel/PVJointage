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
    val matiereBoulon: String
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
