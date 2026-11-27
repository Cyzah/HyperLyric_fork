package com.lidesheng.hyperlyric.root.utils

import android.graphics.Bitmap
import android.graphics.Color
import android.media.session.MediaSession
import android.os.Looper
import com.lidesheng.hyperlyric.common.media.MediaIdentity
import com.lidesheng.hyperlyric.common.media.MediaMetadataHelper
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverColorHelperTest {
    private val context = RuntimeEnvironment.getApplication()
    private val player = MediaSession(context, "cover-colors")
    private val session = CoverColorHelper.activateSession(context.packageName, player.sessionToken)

    @After
    fun tearDown() {
        CoverColorHelper.setOnArtworkColorsReady(null)
        CoverColorHelper.clearCache()
        player.release()
    }

    @Test
    fun exactFingerprintMatchesAnEquivalentWrapperButDetectsSmallPixelChanges() {
        val original = artwork(Color.RED)
        val duplicate = artwork(Color.RED)
        val fingerprint = requireNotNull(CoverColorHelper.bitmapFingerprint(original))

        assertTrue(fingerprint.hasSamePixels(requireNotNull(CoverColorHelper.bitmapFingerprint(duplicate))))
        duplicate.eraseColor(Color.rgb(254, 0, 0))
        val changed = requireNotNull(CoverColorHelper.bitmapFingerprint(duplicate))
        assertTrue(fingerprint.isSimilarTo(changed))
        assertTrue(!fingerprint.hasSamePixels(changed))
    }

    @Test
    fun repeatedInputReturnsReadyRequestAfterBackgroundExtraction() {
        val bitmap = artwork(Color.RED)
        val mediaInfo = media(bitmap, "song-a")

        assertNull(CoverColorHelper.ensureArtworkColors(mediaInfo))
        assertNull(CoverColorHelper.ensureArtworkColors(mediaInfo))
        assertNull(CoverColorHelper.getCachedColors(true, session))

        val ready = awaitArtwork()
        assertSame(ready, CoverColorHelper.ensureArtworkColors(mediaInfo))
        assertSame(ready, CoverColorHelper.ensureArtworkColors(mediaInfo))
        val cached = requireNotNull(CoverColorHelper.getCachedColors(true, ready))
        val direct = requireNotNull(CoverColorHelper.extractPalette(bitmap))
        assertTrue(cached.first.isNotEmpty())
        assertArrayEquals(direct.first, cached.first)
        assertArrayEquals(direct.second, cached.second)
    }

    @Test
    fun supersededArtworkCannotPublishAcrossARapidReturnToTheFirstCover() {
        val first = media(artwork(Color.RED), "song-a")
        val second = media(artwork(Color.BLUE), "song-b")
        val latest = first.copy(title = "Updated title")
        val published = mutableListOf<MediaMetadataHelper.MediaInfo>()
        CoverColorHelper.setOnArtworkColorsReady { _, mediaInfo ->
            assertSame(Looper.getMainLooper(), Looper.myLooper())
            published += mediaInfo
        }

        assertNull(CoverColorHelper.ensureArtworkColors(first))
        assertNull(CoverColorHelper.ensureArtworkColors(second))
        assertNull(CoverColorHelper.ensureArtworkColors(latest))

        val ready = awaitArtwork()
        assertSame(ready, CoverColorHelper.ensureArtworkColors(first))
        assertEquals(listOf(latest), published)
        val color = requireNotNull(CoverColorHelper.getCachedColors(false, ready)).second.single()
        assertTrue(Color.red(color) > Color.blue(color))
    }

    @Test
    fun replacementBitmapForSameItemCanReuseThePaletteWhilePending() {
        val original = media(artwork(Color.RED), "song-a")
        assertNull(CoverColorHelper.ensureArtworkColors(original))
        val ready = awaitArtwork()
        val colorRevision = requireNotNull(CoverColorHelper.cachedColorsRevision(session))
        val previousColors = requireNotNull(CoverColorHelper.getCachedColors(true, ready))

        val replacement = original.copy(albumArt = artwork(Color.RED))
        assertNull(CoverColorHelper.ensureArtworkColors(replacement))
        assertNull(CoverColorHelper.currentArtworkRequest())
        assertNull(CoverColorHelper.getCachedColors(true, ready))
        assertEquals(colorRevision, CoverColorHelper.cachedColorsRevision(session))
        val interimColors = requireNotNull(CoverColorHelper.getCachedColors(true, session))
        assertArrayEquals(previousColors.first, interimColors.first)
        assertArrayEquals(previousColors.second, interimColors.second)

        assertNotEquals(ready.revision, awaitArtwork().revision)
        assertEquals(colorRevision, CoverColorHelper.cachedColorsRevision(session))

        val anotherWrapper = replacement.copy(albumArt = artwork(Color.RED))
        assertNull(CoverColorHelper.ensureArtworkColors(anotherWrapper))
        assertEquals(colorRevision, CoverColorHelper.cachedColorsRevision(session))
        assertNull(CoverColorHelper.ensureArtworkColors(media(artwork(Color.BLUE), "song-b")))
        assertNull(CoverColorHelper.cachedColorsRevision(session))
        assertNull(CoverColorHelper.getCachedColors(true, session))
        awaitArtwork()
        assertNotEquals(colorRevision, CoverColorHelper.cachedColorsRevision(session))
    }

    @Test
    fun mutatedBitmapRequiresANewArtworkRevision() {
        val bitmap = artwork(Color.RED)
        val mediaInfo = media(bitmap, "song-a")
        assertNull(CoverColorHelper.ensureArtworkColors(mediaInfo))
        val original = awaitArtwork()

        bitmap.eraseColor(Color.BLUE)
        assertNull(CoverColorHelper.ensureArtworkColors(mediaInfo))
        assertNull(CoverColorHelper.getCachedColors(true, original))
        assertNull(CoverColorHelper.getCachedColors(true, session))

        val replacement = awaitArtwork()
        assertNotEquals(original.revision, replacement.revision)
        val color = requireNotNull(CoverColorHelper.getCachedColors(false, replacement)).second.single()
        assertTrue(Color.blue(color) > Color.red(color))
    }

    @Test
    fun newSessionAndRecycledArtworkRejectThePendingPalette() {
        val bitmap = artwork(Color.RED)
        assertNull(CoverColorHelper.ensureArtworkColors(media(bitmap, "song-a")))
        bitmap.recycle()
        assertNull(CoverColorHelper.ensureArtworkColors(media(bitmap, "song-a")))
        val nextPlayer = MediaSession(context, "next-cover-colors")
        try {
            val nextSession = CoverColorHelper.activateSession(context.packageName, nextPlayer.sessionToken)
            val nextInfo = media(artwork(Color.BLUE), "song-b", nextPlayer.sessionToken)
            assertNull(CoverColorHelper.ensureArtworkColors(nextInfo))

            val ready = awaitArtwork()
            assertEquals(nextSession.revision, ready.colorSession.revision)
            assertNull(CoverColorHelper.getCachedColors(true, session))
        } finally {
            nextPlayer.release()
        }
    }

    private fun awaitArtwork(): CoverColorHelper.ArtworkRequest {
        val deadline = System.nanoTime() + 5_000_000_000L
        do {
            shadowOf(Looper.getMainLooper()).idle()
            CoverColorHelper.currentArtworkRequest()?.let { return it }
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        error("Artwork colors were not published")
    }

    private fun artwork(color: Int): Bitmap =
        Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun media(
        bitmap: Bitmap,
        songId: String,
        token: MediaSession.Token = player.sessionToken
    ): MediaMetadataHelper.MediaInfo = MediaMetadataHelper.MediaInfo(
        albumArt = bitmap,
        identity = MediaIdentity(packageName = context.packageName, sessionToken = token, songId = songId)
    )
}
