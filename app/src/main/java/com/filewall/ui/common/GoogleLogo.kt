package com.filewall.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.filewall.R

/**
 * Google's official multi-colour "G" mark for Sign-in buttons — the plain Person icon that
 * was there before isn't Google's own branding, which is what "the Google sign-in logo" means.
 * Same path data as the official "Sign in with Google" button assets (18dp square, four brand
 * colours), so both the Cloud Sync and Drive Backup sign-in buttons show the real mark.
 */
@Composable
fun GoogleLogo(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_google_logo),
        contentDescription = null,
        modifier = modifier.size(18.dp),
    )
}
