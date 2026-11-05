package local.filemanager

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class ComicBook(val handle: ArchiveHandle, val pages: List<ZipReader.Entry>)

private sealed interface ComicLoad {
    data object Busy : ComicLoad
    data class Ok(val book: ComicBook) : ComicLoad
    data class Fail(val message: String) : ComicLoad
}

private const val COMIC_PAGE_MAX_BYTES = 64L * 1024 * 1024

private fun openComic(ctx: android.content.Context, fs: Fs, item: FileItem): ComicLoad = try {
    val handle = ArchiveOpener.open(fs, item, ctx.cacheDir)
    val pages = ComicPages.select(handle.source.entries)
    if (pages.isEmpty()) {
        handle.close()
        ComicLoad.Fail(Txt(R.string.comic_ui_001))
    } else {
        ComicLoad.Ok(ComicBook(handle, pages))
    }
} catch (e: OpException) {
    ComicLoad.Fail(e.message ?: Txt(R.string.comic_ui_002))
} catch (e: Exception) {
    ComicLoad.Fail(e.message ?: Txt(R.string.comic_ui_003))
} catch (e: OutOfMemoryError) {
    ComicLoad.Fail(Txt(R.string.comic_ui_004))
}

/** 1 ページ分を読む。書庫(7z・RAR・tar)は同時に 2 か所を読めないので、順番に読む。 */
private fun loadPage(book: ComicBook, index: Int): ImageLoad {
    val e = book.pages[index]
    if (e.size > COMIC_PAGE_MAX_BYTES) return ImageLoad.Fail(Txt(R.string.comic_ui_005))
    val bytes = try {
        synchronized(book.handle) { book.handle.source.open(e).use { it.readBytes() } }
    } catch (ex: Exception) {
        return ImageLoad.Fail(ex.message ?: Txt(R.string.comic_ui_006))
    }
    return ImageDecode.decodeBytes(bytes, 3072)?.let { ImageLoad.Ok(it.asImageBitmap()) } ?: ImageLoad.Fail(Txt(R.string.comic_ui_007))
}

/**
 * マンガ・コミックの書庫(.cbz / .cbr / .cb7 / .cbt)を、1 ページずつ、左右にめくって読む。拡大もできる。
 * rtl が true のときは、右から左へめくる(日本のマンガ)。
 */
@Composable
internal fun ComicPane(fs: Fs, item: FileItem, rtl: Boolean, bars: Boolean, onTap: () -> Unit) {
    val ctx = LocalContext.current
    val load = produceState<ComicLoad>(ComicLoad.Busy, item.path) {
        value = withContext(Dispatchers.IO) { openComic(ctx, fs, item) }
    }.value
    when (load) {
        ComicLoad.Busy -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
        is ComicLoad.Fail -> Box(Modifier.fillMaxSize()) {
            Text(load.message, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(32.dp))
        }
        is ComicLoad.Ok -> {
            DisposableEffect(load.book) { onDispose { load.book.handle.close() } }
            ComicReader(load.book, rtl, bars, onTap)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComicReader(book: ComicBook, rtl: Boolean, bars: Boolean, onTap: () -> Unit) {
    val n = book.pages.size
    val state = rememberPagerState { n }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        HorizontalPager(state = state, modifier = Modifier.fillMaxSize(), reverseLayout = rtl, beyondBoundsPageCount = 1, key = { it }) { page ->
            ZoomableLoaded(page, { loadPage(book, page) }, active = state.currentPage == page, onTap = onTap)
        }
        if (bars) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xCC000000)).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(
                    "${state.currentPage + 1} / $n  " + (if (rtl) Txt(R.string.comic_ui_008) else Txt(R.string.comic_ui_009)),
                    color = Color.White, fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                if (n > 1) {
                    Row(Modifier.fillMaxWidth()) {
                        val f = state.currentPage.toFloat() / (n - 1)
                        Slider(
                            value = if (rtl) 1f - f else f,
                            onValueChange = { v ->
                                val frac = if (rtl) 1f - v else v
                                scope.launch { state.scrollToPage((frac * (n - 1)).toInt().coerceIn(0, n - 1)) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color(0xFF9FA8DA), inactiveTrackColor = Color(0x66FFFFFF)),
                        )
                    }
                }
            }
        }
    }
}
