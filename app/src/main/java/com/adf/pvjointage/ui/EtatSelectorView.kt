package com.adf.pvjointage.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.adf.pvjointage.R
import com.adf.pvjointage.model.Etat

/**
 * Ligne de contrôle réutilisable : libellé + 3 boutons O / N / A (comme les cases à cocher
 * de l'onglet B-Champ). Le bouton "A" (Autre / à surveiller) peut être masqué pour les
 * contrôles qui n'acceptent que O/N dans le fichier Excel d'origine.
 */
class EtatSelectorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val tvLabel: TextView
    private val btnOui: Button
    private val btnNon: Button
    private val btnAutre: Button

    var etat: Etat = Etat.VIDE
        private set

    var onEtatChanged: ((Etat) -> Unit)? = null

    init {
        inflate(context, R.layout.view_etat_selector, this)
        tvLabel = findViewById(R.id.tvLabel)
        btnOui = findViewById(R.id.btnOui)
        btnNon = findViewById(R.id.btnNon)
        btnAutre = findViewById(R.id.btnAutre)

        btnOui.setOnClickListener { setEtat(Etat.OUI) }
        btnNon.setOnClickListener { setEtat(Etat.NON) }
        btnAutre.setOnClickListener { setEtat(Etat.AUTRE) }
        refreshColors()
    }

    fun setLabel(text: String) {
        tvLabel.text = text
    }

    fun setAutreVisible(visible: Boolean) {
        btnAutre.visibility = if (visible) VISIBLE else GONE
    }

    fun setEtat(e: Etat, notify: Boolean = true) {
        etat = e
        refreshColors()
        if (notify) onEtatChanged?.invoke(etat)
    }

    private fun refreshColors() {
        val selectedColor = ContextCompat.getColor(context, R.color.primary)
        val whiteColor = ContextCompat.getColor(context, R.color.white)
        val blackColor = ContextCompat.getColor(context, R.color.black)

        btnOui.setBackgroundColor(if (etat == Etat.OUI) ContextCompat.getColor(context, R.color.conforme) else whiteColor)
        btnOui.setTextColor(if (etat == Etat.OUI) whiteColor else blackColor)

        btnNon.setBackgroundColor(if (etat == Etat.NON) ContextCompat.getColor(context, R.color.non_conforme) else whiteColor)
        btnNon.setTextColor(if (etat == Etat.NON) whiteColor else blackColor)

        btnAutre.setBackgroundColor(if (etat == Etat.AUTRE) selectedColor else whiteColor)
        btnAutre.setTextColor(if (etat == Etat.AUTRE) whiteColor else blackColor)
    }
}
