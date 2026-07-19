package com.philhome.sonnettevideo

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import java.util.concurrent.TimeUnit

/**
 * Ré-inscrit PÉRIODIQUEMENT le token FCM auprès de HA, **même app fermée** (WorkManager).
 *
 * Pourquoi c'est vital pour la fiabilité : un token FCM peut « tourner » (réinstallation,
 * rafraîchissement Firebase ~mensuel). L'app se ré-inscrit à l'ouverture / au boot / à chaque
 * push, MAIS il reste une fenêtre : si le token tourne pendant que l'app dort, le prochain appui
 * échoue sur l'ancien token, HA le retire, et le téléphone **cesse de sonner** jusqu'à la prochaine
 * ouverture de l'app. Cette tâche (toutes les 12 h) ferme cette fenêtre → le token ne peut plus
 * jamais rester périmé.
 */
class TokenRefreshWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        return try {
            DebugLog.init(applicationContext)
            val token = Tasks.await(FirebaseMessaging.getInstance().token, 20, TimeUnit.SECONDS)
            DebugLog.log("TokenRefresh", "ré-inscription périodique du token …${token.takeLast(8)}")
            TokenRegistrar.send(applicationContext, token)
            Result.success()
        } catch (e: Exception) {
            DebugLog.log("TokenRefresh", "échec (${e.javaClass.simpleName}) → retry")
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE = "token-refresh"

        /** Programme la ré-inscription toutes les 15 min (plancher WorkManager). v0.3.6 : 12 h → 15 min. */
        fun schedule(ctx: Context) {
            // 15 min = PLANCHER Android pour un travail périodique (WorkManager refuse moins sans
            // service permanent en avant-plan = batterie + risque MIUI = surcharge inutile).
            // Le vrai déclencheur reste onNewToken (instantané) ; ceci n'est QUE le filet de sécurité.
            val req = PeriodicWorkRequestBuilder<TokenRefreshWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
