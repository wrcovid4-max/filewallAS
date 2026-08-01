package com.filewall

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.filewall.data.settings.VaultSettings
import com.filewall.data.wear.HandoffNotifier
import com.filewall.ui.FileWallRoot
import com.filewall.ui.theme.FileWallTheme
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
                FileWallRoot(container = container)
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
