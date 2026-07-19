package com.philhome.sonnettevideo

import android.content.Context

/**
 * Réglages persistants de l'app (SharedPreferences).
 *
 * `directLan` = interrupteur de TRANSITION : quand ACTIVÉ, l'écran d'appel tente la vidéo + le
 * talk-back EN DIRECT vers la sonnette (RTSP + LAN, SANS Home Assistant) si elle est joignable ;
 * sinon secours HA. **Par défaut DÉSACTIVÉ** → comportement historique (via HA) inchangé, donc on
 * ne casse rien. On l'active sur UN téléphone (celui de test) pour valider le direct = « bypass ».
 */
object Prefs {
    private const val FILE = "sonnette_prefs"
    private const val KEY_DIRECT_LAN = "direct_lan_enabled"
    private const val KEY_MEETING = "meeting_mode_enabled"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun directLan(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_DIRECT_LAN, false)

    fun setDirectLan(ctx: Context, enabled: Boolean) =
        sp(ctx).edit().putBoolean(KEY_DIRECT_LAN, enabled).apply()

    /**
     * Mode réunion : la sonnette VIBRE mais ne joue AUCUN son (discret en réunion).
     * Lu par [RingPlayer] au moment de sonner. Par défaut DÉSACTIVÉ (sonnerie normale).
     */
    fun meetingMode(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_MEETING, false)

    fun setMeetingMode(ctx: Context, enabled: Boolean) =
        sp(ctx).edit().putBoolean(KEY_MEETING, enabled).apply()
}
