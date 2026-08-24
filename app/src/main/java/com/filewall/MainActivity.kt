package com.filewall

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.filewall.data.settings.VaultSettings
import com.filewall.data.wear.HandoffNotifier
import com.filewall.ui.FileWallRoot
import com.filewall.ui.SplashScreen
import com.filewall.ui.theme.FileWallTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Single activity for the whole app.
 *
 * [FragmentActivity] rather than ComponentActivity because BiometricPrompt needs a fragment
 * host, and the vault's unlock path leans on it.
 */
class MainActivity : FragmentActivity() {

    private val container by lazy { (application as FileWallApp).container }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        consumeHandoff(intent)
        consumeShare(intent)
        requestNotificationPermissionIfNeeded()

        // FLAG_SECURE is applied before the first frame and re-applied whenever the
        // "Allow Screenshots" switch moves, so vault contents stay out of the recents
        // thumbnail as well as out of screenshots.
        lifecycleScope.launch {
            container.settings.settings
                .map { it.allowScreenshots }
                .distinctUntilChanged()
                .collect { allowed -> applySecureFlag(allowed) }
        }

        setContent {
            val settings by container.settings.settings
                .collectAsStateWithLifecycle(initialValue = VaultSettings())

            FileWallTheme(themeMode = settings.theme) {
                var showSplash by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) {
                    delay(1500)
                    showSplash = false
                }
                if (showSplash) {
                    SplashScreen()
                } else {
                    FileWallRoot(container = container)
                }
            }
        }
    }

    /**
     * The watch's "open on phone" handoff arrives as a notification, which is silently
     * dropped on 13+ without this grant. Asked for once, and never blocking: everything
     * else in the app works without it.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeHandoff(intent)
        consumeShare(intent)
    }

    /**
     * Files sent to FileWall from another app's Share sheet land here. They're encrypted into
     * the unlocked vault; the vault list is observing the database, so it refreshes on its own.
     */
    private fun consumeShare(intent: Intent?) {
        if (intent == null) return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.parcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE ->
                intent.parcelableArrayList<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> return
        }
        if (uris.isEmpty()) return
        // Consume it, so a recreation (rotation, process restart) can't re-import the same files.
        intent.action = null

        lifecycleScope.launch {
            val result = container.repository.import(uris, hidden = false, folderId = null)
            val message = when {
                result.added > 0 -> getString(R.string.imported_from_share, result.added)
                else -> result.failures.firstOrNull() ?: getString(R.string.import_failed)
            }
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    private inline fun <reified T : Parcelable> Intent.parcelableExtra(key: String): T? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(key, T::class.java)
        } else {
            @Suppress("DEPRECATION") getParcelableExtra(key) as? T
        }

    private inline fun <reified T : Parcelable> Intent.parcelableArrayList(key: String): ArrayList<T>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(key, T::class.java)
        } else {
            @Suppress("DEPRECATION") getParcelableArrayListExtra(key)
        }

    /** Picks up an item id posted by the watch-handoff notification. */
    private fun consumeHandoff(intent: Intent?) {
        if (intent?.action != HandoffNotifier.ACTION_OPEN_ITEM) return
        intent.getStringExtra(HandoffNotifier.EXTRA_ITEM_ID)
            ?.takeIf { it.isNotBlank() }
            ?.let { container.pendingOpen.value = it }
    }

    override fun onPause() {
        super.onPause()
        // Nothing decrypted should survive the app leaving the foreground.
        container.repository.clearPreviewCache()
    }

    private fun applySecureFlag(allowScreenshots: Boolean) {
        if (allowScreenshots) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        }
    }
}
