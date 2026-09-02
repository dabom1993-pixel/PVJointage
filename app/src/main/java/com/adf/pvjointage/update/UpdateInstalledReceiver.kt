package com.adf.pvjointage.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Diffusion système reçue par l'app juste après avoir été remplacée par une mise à jour
 * (déclenchée depuis le bouton logo de l'écran principal). Sert uniquement à marquer que
 * la mise à jour a bien été installée, pour que MainActivity affiche la confirmation
 * "Programme mis à jour, vous disposez de la dernière version" au redémarrage.
 */
class UpdateInstalledReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            UpdateManager.onPackageReplaced(context)
        }
    }
}
