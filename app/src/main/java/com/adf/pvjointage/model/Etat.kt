package com.adf.pvjointage.model

/**
 * Etat d'une case à cocher du PV (reproduit les valeurs O / N / A du fichier Excel).
 * VIDE = pas encore renseigné.
 */
enum class Etat(val code: String) {
    VIDE(""),
    OUI("O"),
    NON("N"),
    AUTRE("A");

    companion object {
        fun fromCode(code: String?): Etat = when (code) {
            "O" -> OUI
            "N" -> NON
            "A" -> AUTRE
            else -> VIDE
        }
    }
}

/** Statut de conformité affiché à l'écran (calculé à partir des Etat saisis). */
enum class Conformite { CONFORME, NON_CONFORME, EN_ATTENTE }

/**
 * Reproduit exactement la logique des formules Excel de l'onglet B-Champ :
 *   - ETIQUETTE (cellule W15)
 *   - JOINT (cellule W20)
 *   - BOULONNERIE (cellule W29)
 *   - ASSEMBLAGE (cellule W38)
 */
object ConformiteCalculator {

    private fun ouiOuAutre(e: Etat) = e == Etat.OUI || e == Etat.AUTRE

    fun etiquette(miseSerree: Etat, nomDateLisible: Etat): Conformite {
        if (miseSerree == Etat.VIDE || nomDateLisible == Etat.VIDE) return Conformite.EN_ATTENTE
        return if (miseSerree == Etat.OUI && nomDateLisible == Etat.OUI) Conformite.CONFORME else Conformite.NON_CONFORME
    }

    fun joint(matiereConforme: Etat, dimensionCentrage: Etat, aspectNeuf: Etat): Conformite {
        if (matiereConforme == Etat.VIDE || dimensionCentrage == Etat.VIDE || aspectNeuf == Etat.VIDE) return Conformite.EN_ATTENTE
        val ok = ouiOuAutre(matiereConforme) && ouiOuAutre(dimensionCentrage) && aspectNeuf == Etat.OUI
        return if (ok) Conformite.CONFORME else Conformite.NON_CONFORME
    }

    fun boulonnerie(
        neuves: Etat,
        rondelles: Etat,
        equilibrage: Etat,
        graissage: Etat,
        longueurDiametre: Etat,
        matiere: Etat
    ): Conformite {
        if (listOf(neuves, rondelles, equilibrage, graissage, longueurDiametre, matiere).any { it == Etat.VIDE }) {
            return Conformite.EN_ATTENTE
        }
        val ok = ouiOuAutre(neuves) && ouiOuAutre(rondelles) &&
            equilibrage == Etat.OUI && graissage == Etat.OUI &&
            longueurDiametre == Etat.OUI && ouiOuAutre(matiere)
        return if (ok) Conformite.CONFORME else Conformite.NON_CONFORME
    }

    fun assemblage(parallelisme: Etat, excentration: Etat): Conformite {
        if (parallelisme == Etat.VIDE || excentration == Etat.VIDE) return Conformite.EN_ATTENTE
        return if (parallelisme == Etat.OUI && excentration == Etat.OUI) Conformite.CONFORME else Conformite.NON_CONFORME
    }

    fun global(vararg sections: Conformite): Conformite {
        if (sections.any { it == Conformite.EN_ATTENTE }) return Conformite.EN_ATTENTE
        return if (sections.all { it == Conformite.CONFORME }) Conformite.CONFORME else Conformite.NON_CONFORME
    }
}
