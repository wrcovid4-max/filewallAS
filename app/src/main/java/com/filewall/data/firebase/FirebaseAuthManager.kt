package com.filewall.data.firebase

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await
import java.io.IOException

/**
 * Google Sign-In resolved through Firebase Auth.
 *
 * The whole point of the multi-platform design: the same Google account yields the same
 * Firebase `uid` on every target, which is the key to the existing data partition. Requires
 * [FirebaseGate.init] to have succeeded and a non-blank web client id.
 */
class FirebaseAuthManager(private val context: Context) {

    private val webClientId = FirebaseGate.webClientId(context)

    private val client: GoogleSignInClient by lazy {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)   // the ID token is what Firebase Auth consumes
            .requestEmail()
            .build()
        GoogleSignIn.getClient(context, options)
    }

    /** Hand this to an Activity result launcher to start Google Sign-In. */
    val signInIntent: Intent get() = client.signInIntent

    /** The current Firebase uid, or null when signed out. */
    val uid: String? get() = FirebaseAuth.getInstance().currentUser?.uid

    /** Turns the sign-in Activity result into a Firebase session; returns the resolved uid. */
    suspend fun completeSignIn(data: Intent?): String {
        val account = try {
            GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
        } catch (error: ApiException) {
            throw IOException("Google sign-in failed (${error.statusCode})", error)
        }
        val idToken = account?.idToken
            ?: throw IOException("No ID token — set firebase_web_client_id to the project's Web client ID")
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val result = FirebaseAuth.getInstance().signInWithCredential(credential).await()
        return result.user?.uid ?: throw IOException("Firebase returned no user")
    }

    suspend fun signOut() {
        runCatching { client.signOut().await() }
        FirebaseAuth.getInstance().signOut()
    }
}
