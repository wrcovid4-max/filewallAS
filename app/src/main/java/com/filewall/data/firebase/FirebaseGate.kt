package com.filewall.data.firebase

import android.content.Context
import com.filewall.R
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

/**
 * Manual Firebase initialisation from `res/values/firebase.xml`.
 *
 * Doing it by hand means the app needs no `google-services` plugin (which fails the *build*
 * when `google-services.json` is absent) and no auto-init: while the required three values are
 * blank, [init] returns false and Firebase never comes up — the app behaves exactly as before.
 */
object FirebaseGate {

    @Volatile
    private var configured = false

    val isConfigured: Boolean get() = configured

    /** Call once at startup. Returns true once Firebase is available for use. */
    fun init(context: Context): Boolean {
        if (configured) return true
        val appId = context.getString(R.string.firebase_app_id)
        val apiKey = context.getString(R.string.firebase_api_key)
        val projectId = context.getString(R.string.firebase_project_id)
        if (appId.isBlank() || apiKey.isBlank() || projectId.isBlank()) return false

        if (FirebaseApp.getApps(context).isEmpty()) {
            val bucket = context.getString(R.string.firebase_storage_bucket)
                .ifBlank { "$projectId.appspot.com" }
            val options = FirebaseOptions.Builder()
                .setApplicationId(appId)
                .setApiKey(apiKey)
                .setProjectId(projectId)
                .setStorageBucket(bucket)
                .build()
            FirebaseApp.initializeApp(context, options)
        }
        configured = true
        return true
    }

    fun webClientId(context: Context): String = context.getString(R.string.firebase_web_client_id)
}
