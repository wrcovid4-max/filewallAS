package com.filewall

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.filewall.data.settings.VaultSettings
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

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
