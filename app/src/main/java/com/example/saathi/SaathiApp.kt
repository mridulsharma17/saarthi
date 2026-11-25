package com.example.saathi

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.saathi.worker.InactivityWorker
import com.google.firebase.database.FirebaseDatabase
import java.util.concurrent.TimeUnit

class SaathiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Enable Firebase Realtime Database persistence
        val database = FirebaseDatabase.getInstance("https://saathi-5f51d-default-rtdb.firebaseio.com/")
        try {
            database.setPersistenceEnabled(true)
        } catch (e: Exception) {
            // Persistence must be enabled before any other usage of the database
        }

        scheduleInactivityCheckWorker()
    }

    private fun scheduleInactivityCheckWorker() {
        try {
            val periodicWorkRequest = PeriodicWorkRequestBuilder<InactivityWorker>(1, TimeUnit.HOURS).build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "RahiPeriodicInactivityCheck",
                ExistingPeriodicWorkPolicy.KEEP,
                periodicWorkRequest
            )
        } catch (e: Exception) {
            // Ignore error
        }
    }
}
