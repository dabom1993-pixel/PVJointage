package com.adf.pvjointage.data

import android.content.Context

/** Petites préférences persistées liées au fichier Excel importé. */
class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("pv_jointage_prefs", Context.MODE_PRIVATE)

    /** Nom de l'onglet choisi lors du dernier import (nécessaire pour l'export natif). */
    var lastImportSheetName: String?
        get() = prefs.getString(KEY_SHEET, null)
        set(value) = prefs.edit().putString(KEY_SHEET, value).apply()

    companion object {
        private const val KEY_SHEET = "last_import_sheet_name"
    }
}
