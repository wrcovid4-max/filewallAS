package com.filewall.ui.viewer

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import com.filewall.R
import com.filewall.model.VaultItem
import com.filewall.ui.common.stringResourceSafe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** How the PDF is laid out, like the View menu in Preview. */
enum class PdfLayout { SINGLE, TWO_PAGE, CONTINUOUS }

/**
 * In-app PDF viewer, with three layouts like Preview: single page (swipe to turn), two pages
 * side by side (a spread at a time), and continuous scroll.
 *
 * The blob is decrypted once, on demand, into the preview cache, and [PdfRenderer] reads
 * pages from that file descriptor. The cache is wiped on lock, on background and at launch.
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
    var layout by rememberSaveable(item.id) { mutableStateOf(PdfLayout.CONTINUOUS) }

    val rendererState by produceState<PdfState>(initialValue = PdfState.Loading, item.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = decryptToCache(item)
                val descriptor = ParcelFileDescriptor.open(
                    file,
                    ParcelFileDescriptor.MODE_READ_ONLY,
                )
                val renderer = PdfRenderer(descriptor)
                // Read pageCount now, while we own the renderer on a background thread, so
                // composition never has to call into it (and never touches it after close).
                PdfState.Ready(renderer, file, renderer.pageCount)
            }.getOrElse { PdfState.Failed }
        }
    }

    val ready = rendererState as? PdfState.Ready
    // Key on the renderer *instance*, and capture it into a local so the cleanup closes
    // exactly the renderer this effect opened with.
    androidx.compose.runtime.DisposableEffect(ready?.renderer) {
        val owned = ready?.renderer
        onDispose { owned?.let { runCatching { it.close() } } }
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

            is PdfState.Ready -> when (layout) {
                PdfLayout.CONTINUOUS -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 12.dp, horizontal = 8.dp),
                ) {
                    items(state.pageCount) { index ->
                        PdfPage(renderer = state.renderer, index = index, widthPx = targetWidthPx)
                        Spacer(Modifier.height(10.dp))
                    }
                }

                PdfLayout.SINGLE -> PagedPdf(
                    renderer = state.renderer,
                    pageCount = state.pageCount,
                    widthPx = targetWidthPx,
                    twoPage = false,
                )

                PdfLayout.TWO_PAGE -> PagedPdf(
                    renderer = state.renderer,
                    pageCount = state.pageCount,
                    widthPx = targetWidthPx,
                    twoPage = true,
                )
            }
        }

        // Layout switcher, like the View menu in Preview. Only once the document is ready.
        if (ready != null) {
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LayoutOption(Icons.Filled.Description, "Single page", layout == PdfLayout.SINGLE) {
                    layout = PdfLayout.SINGLE
                }
                LayoutOption(Icons.Filled.MenuBook, "Two pages", layout == PdfLayout.TWO_PAGE) {
                    layout = PdfLayout.TWO_PAGE
                }
                LayoutOption(Icons.Filled.ViewAgenda, "Continuous scroll", layout == PdfLayout.CONTINUOUS) {
                    layout = PdfLayout.CONTINUOUS
                }
            }
        }
    }
}

@Composable
private fun LayoutOption(
    icon: ImageVector,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
        )
    }
}

/** Single page or a two-page spread per swipe. Pages fit the screen, not the width. */
@Composable
private fun PagedPdf(
    renderer: PdfRenderer,
    pageCount: Int,
    widthPx: Int,
    twoPage: Boolean,
) {
    val spreads = if (twoPage) (pageCount + 1) / 2 else pageCount
    val pagerState = rememberPagerState(pageCount = { spreads })

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
    ) { spread ->
        if (twoPage) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val left = spread * 2
                val right = left + 1
                Box(Modifier.weight(1f).fillMaxSize()) {
                    PdfPage(renderer = renderer, index = left, widthPx = widthPx, fit = true)
                }
                Box(Modifier.weight(1f).fillMaxSize()) {
                    if (right < pageCount) {
                        PdfPage(renderer = renderer, index = right, widthPx = widthPx, fit = true)
                    }
                }
            }
        } else {
            Box(Modifier.fillMaxSize().padding(12.dp)) {
                PdfPage(renderer = renderer, index = spread, widthPx = widthPx, fit = true)
            }
        }
    }
}

@Composable
private fun PdfPage(
    renderer: PdfRenderer,
    index: Int,
    widthPx: Int,
    fit: Boolean = false,
) {
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
            contentScale = if (fit) ContentScale.Fit else ContentScale.FillWidth,
            modifier = if (fit) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
        )
    } else if (fit) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
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
    data class Ready(val renderer: PdfRenderer, val file: File, val pageCount: Int) : PdfState
}
