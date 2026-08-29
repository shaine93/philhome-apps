package com.philhome.sonnettevideo

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Nouveau point d'entrée (lanceur). Aiguille selon [Prefs.uiModeSimple] :
 *  - simple  → [SimpleHomeActivity] (image de la porte, rien d'autre)
 *  - avancé  → [MainActivity] (comportement historique complet, INCHANGÉ)
 *
 * Toujours `finish()` immédiatement après avoir lancé la bonne cible : cette activité n'est
 * qu'un aiguillage transparent, jamais un écran visible ni une entrée dans la pile de retour.
 */
class HomeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = if (Prefs.uiModeSimple(this)) SimpleHomeActivity::class.java else MainActivity::class.java
        startActivity(Intent(this, target))
        finish()
    }
}
