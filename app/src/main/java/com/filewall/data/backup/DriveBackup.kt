package com.filewall.data.backup

import android.accounts.Account
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Backs the "Cloud Backup & Sync" card with Drive's `appDataFolder`.
 *
 * What travels is exactly the same passphrase-encrypted `.fwvault` blob that
 * [VaultArchive] writes locally — Google stores an opaque file it cannot read, and a
 * restore on a new phone needs the passphrase regardless of who is signed in.
 */
class DriveBackup(private val context: Context) {

    /** Sign-in has to be handed off to an Activity; this is what it needs. */
    val signInIntent: Intent get() = client.signInIntent

    private val client: GoogleSignInClient by lazy {
        GoogleSignIn.getClient(
            context,
            GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(Scope(SCOPE_APPDATA))
                .build(),
        )
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .build()
    }

    // ----------------------------------------------------------------- account

    data class AccountInfo(
        val displayName: String,
        val email: String,
        val photoUrl: String?,
    )

    fun currentAccount(): AccountInfo? =
        GoogleSignIn.getLastSignedInAccount(context)?.takeIf { it.email != null }?.toInfo()

    /**
     * Turns the result of [signInIntent] into an account.
     *
     * A [DriveNotConfiguredException] here means the build's package name and signing
     * certificate have no matching OAuth client in Google Cloud — see README.md.
     */
    fun completeSignIn(data: Intent?): AccountInfo {
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        val account = try {
            task.getResult(ApiException::class.java)
        } catch (error: ApiException) {
            if (error.statusCode == DEVELOPER_ERROR) throw DriveNotConfiguredException()
            throw IOException("Google sign-in failed (${error.statusCode})", error)
        }
        return account?.toInfo() ?: throw IOException("Google sign-in returned no account")
    }

    suspend fun signOut() {
        runCatching { client.signOut().await() }
    }

    private fun GoogleSignInAccount.toInfo() = AccountInfo(
        displayName = displayName ?: email.orEmpty().substringBefore('@'),
        email = email.orEmpty(),
        photoUrl = photoUrl?.toString(),
    )

    class DriveNotConfiguredException : IOException(
        "This build has no Google OAuth client. Register the app's package name and signing " +
            "certificate SHA-1 in Google Cloud Console, then sign in again.",
    )

    class NeedsUserConsentException(val recovery: Intent) : IOException(
        "Google needs you to approve Drive access.",
    )

    // ------------------------------------------------------------------ upload

    /** Creates or replaces `filewall-backup.fwvault` in the private app data folder. */
    suspend fun upload(archive: File): Unit = withContext(Dispatchers.IO) {
        val token = accessToken()
        val existingId = findBackupId(token)
        val body = archive.asRequestBody(OCTET_STREAM.toMediaType())

        val request = if (existingId == null) {
            val metadata = JSONObject().apply {
                put("name", VaultArchive.DEFAULT_FILE_NAME)
                put("parents", org.json.JSONArray().put(APP_DATA_FOLDER))
            }
            Request.Builder()
                .url("$UPLOAD_BASE?uploadType=multipart&fields=id")
                .post(
                    MultipartBody.Builder()
                        .setType("multipart/related".toMediaType())
                        .addPart(metadata.toString().toRequestBody(JSON.toMediaType()))
                        .addPart(body)
                        .build(),
                )
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            Request.Builder()
                .url("$UPLOAD_BASE/$existingId?uploadType=media&fields=id")
                .patch(body)
                .header("Authorization", "Bearer $token")
                .build()
        }

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw driveError("Upload", response.code, response.body?.string())
        }
    }

    // ---------------------------------------------------------------- download

    /** Pulls the stored archive into [target]. Returns false when no backup exists yet. */
    suspend fun download(target: File): Boolean = withContext(Dispatchers.IO) {
        val token = accessToken()
        val id = findBackupId(token) ?: return@withContext false

        val request = Request.Builder()
            .url("$DRIVE_BASE/$id?alt=media")
            .header("Authorization", "Bearer $token")
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw driveError("Download", response.code, response.body?.string())
            val stream = response.body?.byteStream() ?: throw IOException("Drive returned an empty body")
            target.outputStream().use { out -> stream.copyTo(out) }
        }
        true
    }

    /** Timestamp of the stored backup, or null when there is none. */
    suspend fun lastBackupAt(): String? = withContext(Dispatchers.IO) {
        val token = accessToken()
        val request = Request.Builder()
            .url("$DRIVE_BASE?spaces=$APP_DATA_FOLDER&fields=files(id,name,modifiedTime)&pageSize=10")
            .header("Authorization", "Bearer $token")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val files = JSONObject(response.body?.string().orEmpty()).optJSONArray("files") ?: return@withContext null
            for (index in 0 until files.length()) {
                val file = files.getJSONObject(index)
                if (file.optString("name") == VaultArchive.DEFAULT_FILE_NAME) {
                    return@withContext file.optString("modifiedTime").takeIf { it.isNotBlank() }
                }
            }
            null
        }
    }

    // ---------------------------------------------------------------- internals

    private fun findBackupId(token: String): String? {
        val request = Request.Builder()
            .url("$DRIVE_BASE?spaces=$APP_DATA_FOLDER&fields=files(id,name)&pageSize=100")
            .header("Authorization", "Bearer $token")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw driveError("Lookup", response.code, response.body?.string())
            val files = JSONObject(response.body?.string().orEmpty()).optJSONArray("files") ?: return null
            for (index in 0 until files.length()) {
                val file = files.getJSONObject(index)
                if (file.optString("name") == VaultArchive.DEFAULT_FILE_NAME) return file.optString("id")
            }
        }
        return null
    }

    private fun accessToken(): String {
        val signedIn = GoogleSignIn.getLastSignedInAccount(context)
            ?: throw IOException("Sign in with Google first")
        val account: Account = signedIn.account ?: throw IOException("Sign in with Google first")
        return try {
            // Fetched fresh each call; GoogleAuthUtil caches and refreshes behind the scenes.
            GoogleAuthUtil.getToken(context, account, "oauth2:$SCOPE_APPDATA")
        } catch (recoverable: UserRecoverableAuthException) {
            throw NeedsUserConsentException(recoverable.intent ?: client.signInIntent)
        }
    }

    private fun driveError(stage: String, code: Int, body: String?): IOException {
        val detail = body?.let { runCatching { JSONObject(it).optJSONObject("error")?.optString("message") }.getOrNull() }
        return IOException("$stage failed (HTTP $code)${detail?.let { ": $it" }.orEmpty()}")
    }

    private companion object {
        const val SCOPE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
        const val APP_DATA_FOLDER = "appDataFolder"
        const val DRIVE_BASE = "https://www.googleapis.com/drive/v3/files"
        const val UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3/files"
        const val OCTET_STREAM = "application/octet-stream"
        const val JSON = "application/json; charset=UTF-8"
        const val DEVELOPER_ERROR = 10
    }
}
