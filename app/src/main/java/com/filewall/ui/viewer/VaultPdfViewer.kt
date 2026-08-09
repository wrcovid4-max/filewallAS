package com.filewall.ui.viewer

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.filewall.R
import com.filewall.model.VaultItem
import com.filewall.ui.common.stringResourceSafe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * In-app PDF viewer.
 *
 * The blob is decrypted once, on demand, into the preview cache — the same place external
 * "Open with" would put it — and [PdfRenderer] reads pages from that file descriptor. The
 * cache is wiped on lock, on background and at launch, so the plaintext copy is as
 * short-lived as any other preview.
 *
 * Pages render lazily at the current screen width, so a hundred-page PDF costs one bitmap
 * per visible page rather than a hundred up front.
 */
@Composable
fun VaultPdfViewer(
    item: VaultItem,
    decryptToCache: suspend (VaultItem) -> File,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    // Render at ~2x the displayed width so text stays crisp when the user pinches in.
    val targetWidthPx = with(density) { 1080 }

    val rendererState by produceState<PdfState>(initialValue = PdfState.Loading, item.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = decryptToCache(item)
                val descriptor = ParcelFileDescriptor.open(
                    file,
                    ParcelFileDescriptor.MODE_READ_ONLY,
                )
                PdfState.Ready(PdfRenderer(descriptor), file)
            }.getOrElse { PdfState.Failed }
        }
    }

    // Close the renderer (and its descriptor) when we leave the screen or switch document.
    androidx.compose.runtime.DisposableEffect(rendererState) {
        onDispose {
            (rendererState as? PdfState.Ready)?.renderer?.let { runCatching { it.close() } }
        }
    }

    Box(modifier.fillMaxSize().background(Color(0xFF202020)), contentAlignment = Alignment.Center) {
        when (val state = rendererState) {
            PdfState.Loading -> CircularProgressIndicator(color = Color.White)

            PdfState.Failed -> Text(
                text = stringResourceSafe(R.string.pdf_failed),
                color = Color.White,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(32.dp),
            )

            is PdfState.Ready -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 12.dp, horizontal = 8.dp),
            ) {
                items((0 until state.renderer.pageCount).toList()) { index ->
                    PdfPage(renderer = state.renderer, index = index, widthPx = targetWidthPx)
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun PdfPage(renderer: PdfRenderer, index: Int, widthPx: Int) {
    // PdfRenderer forbids two pages open at once, so rendering is serialised on a shared lock.
    val bitmap by produceState<Bitmap?>(initialValue = null, index) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                synchronized(renderer) {
                    renderer.openPage(index).use { page ->
                        val ratio = page.height.toFloat() / page.width.toFloat()
                        val height = (widthPx * ratio).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
                        // White backing so pages with transparency don't render on black.
                        bmp.eraseColor(AndroidColor.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }
            }.getOrNull()
        }
    }

    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = "Page ${index + 1}",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        // Hold the row's height with an A4-ish placeholder so the scrollbar doesn't jump.
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.707f)
                .background(Color(0xFF2A2A2A)),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = Color.White)
        }
    }
}

private sealed interface PdfState {
    data object Loading : PdfState
    data object Failed : PdfState
    data class Ready(val renderer: PdfRenderer, val file: File) : PdfState
}
