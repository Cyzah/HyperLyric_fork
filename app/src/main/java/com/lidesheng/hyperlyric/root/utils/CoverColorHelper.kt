package com.lidesheng.hyperlyric.root.utils

import android.graphics.Bitmap
import android.media.session.MediaSession
import android.os.Handler
import android.os.Looper
import com.lidesheng.hyperlyric.common.color.ColorExtractor
import com.lidesheng.hyperlyric.common.media.MediaMetadataHelper
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.abs

object CoverColorHelper {
    private const val TAG = "CoverColorHelper"

    /** A source-owned lease authorizing artwork from one exact MediaSession. */
    class ColorSession internal constructor(
        val revision: Long,
        internal val packageName: String,
        internal val sessionToken: MediaSession.Token
    ) {
        /** Stable diagnostic token for consumers that need to invalidate animations. */
        val mediaKey: String
            get() = "$packageName\u001Fsession\u001F${sessionToken.hashCode()}"
    }

    class ArtworkRequest internal constructor(
        val colorSession: ColorSession,
        val revision: Long
    )

    private class ArtworkInput(
        val colorSession: ColorSession,
        val cacheKey: ArtworkCacheKey,
        val mediaInfo: MediaMetadataHelper.MediaInfo,
        val bitmap: Bitmap,
        val generation: Int
    ) {
        fun matches(other: ArtworkInput): Boolean =
            colorSession === other.colorSession && cacheKey == other.cacheKey &&
                bitmap === other.bitmap && generation == other.generation

        fun hasCurrentPixels(): Boolean = !bitmap.isRecycled && bitmap.generationId == generation

        fun canReusePendingPalette(previous: ArtworkInput): Boolean =
            colorSession === previous.colorSession && cacheKey == previous.cacheKey &&
                (cacheKey.songId != null || cacheKey.mediaId != null) &&
                bitmap !== previous.bitmap && hasCurrentPixels() && previous.hasCurrentPixels()
    }

    private class PublishedArtwork(
        val input: ArtworkInput,
        val palette: CacheEntry,
        val colorRevision: Long
    )

    private data class CacheEntry(
        val artworkFingerprint: ArtworkFingerprint,
        val colors: Pair<IntArray, IntArray>
    )

    private data class ArtworkCacheKey(
        val packageName: String,
        val sessionToken: MediaSession.Token,
        val songId: String?,
        val mediaId: String?
    )

    internal class ArtworkFingerprint(
        private val pixels: IntArray
    ) {
        fun hasSamePixels(other: ArtworkFingerprint): Boolean = pixels.contentEquals(other.pixels)

        fun isSimilarTo(other: ArtworkFingerprint): Boolean {
            if (pixels.size != other.pixels.size || pixels.isEmpty()) return false
            var totalDelta = 0L
            pixels.indices.forEach { index ->
                val first = pixels[index]
                val second = other.pixels[index]
                totalDelta += abs((first ushr 16 and 0xFF) - (second ushr 16 and 0xFF))
                totalDelta += abs((first ushr 8 and 0xFF) - (second ushr 8 and 0xFF))
                totalDelta += abs((first and 0xFF) - (second and 0xFF))
            }
            return totalDelta <= pixels.size * RGB_CHANNEL_COUNT * MAX_AVERAGE_CHANNEL_DELTA
        }
    }

    private var sessionRevision = 0L
    private var artworkRevision = 0L
    private var activeSession: ColorSession? = null
    private var selectedArtwork: ArtworkInput? = null
    private var artworkInFlight = false
    private var activeArtworkRequest: ArtworkRequest? = null
    private var lastPublishedArtwork: PublishedArtwork? = null
    private val keyedCache = LinkedHashMap<ArtworkCacheKey, CacheEntry>()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    // An idle worker must release its module generation after a SystemUI hot reload.
    private val artworkExecutor = ThreadPoolExecutor(
        0, 1, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(1)
    ) { work ->
        Thread(work, "HyperLyric-CoverColors").apply { isDaemon = true }
    }

    @Volatile
    private var onArtworkColorsReady: ((ArtworkRequest, MediaMetadataHelper.MediaInfo) -> Unit)? = null

    internal fun setOnArtworkColorsReady(
        listener: ((ArtworkRequest, MediaMetadataHelper.MediaInfo) -> Unit)?
    ) {
        onArtworkColorsReady = listener
    }

    /** Only the lyric-source binding coordinator can advance this lease. */
    @Synchronized
    internal fun activateSession(
        packageName: String,
        sessionToken: MediaSession.Token
    ): ColorSession {
        val normalizedPackage = packageName.trim()
        require(normalizedPackage.isNotEmpty())
        val current = activeSession
        if (current?.packageName == normalizedPackage && current.sessionToken == sessionToken) {
            return current
        }

        return ColorSession(
            revision = ++sessionRevision,
            packageName = normalizedPackage,
            sessionToken = sessionToken
        ).also {
            activeSession = it
            clearArtworkLocked()
        }
    }

    @Synchronized
    fun endSession(): Boolean {
        if (activeSession == null) return false
        val revision = activeSession?.revision
        activeSession = null
        clearArtworkLocked()
        HookLogger.dState(
            stateId = "CoverColorHelper.session",
            tag = TAG,
            state = "ended|$revision"
        ) {
            "颜色会话结束: previousRevision=$revision"
        }
        return true
    }

    @Synchronized
    fun currentSession(packageName: String? = null): ColorSession? {
        val current = activeSession ?: return null
        if (packageName == null) return current
        return current.takeIf {
            it.packageName == packageName.trim()
        }
    }

    @Synchronized
    fun isCurrentSession(session: ColorSession): Boolean {
        return isCurrentSessionLocked(session)
    }

    @Synchronized
    fun currentSession(mediaInfo: MediaMetadataHelper.MediaInfo): ColorSession? =
        activeSession?.takeIf { matchesSessionLocked(it, mediaInfo) }

    /**
     * Requests the current MediaSession artwork. A request becomes visible only after its shared
     * palette is ready. While extraction is pending, consumers use their usual fallback colors.
     */
    @Synchronized
    fun ensureArtworkColors(mediaInfo: MediaMetadataHelper.MediaInfo): ArtworkRequest? {
        val current = activeSession ?: return null
        val previous = selectedArtwork
        if (previous != null && previous.mediaInfo === mediaInfo && previous.hasCurrentPixels()) {
            return activeArtworkRequest
        }
        val incomingIdentity = mediaInfo.identity.normalized()
        if (incomingIdentity.packageName != current.packageName ||
            incomingIdentity.sessionToken != current.sessionToken
        ) return null

        val bitmap = mediaInfo.albumArt?.takeUnless { it.isRecycled } ?: run {
            clearArtworkLocked()
            return null
        }
        val input = ArtworkInput(
            colorSession = current,
            cacheKey = ArtworkCacheKey(
                packageName = current.packageName,
                sessionToken = current.sessionToken,
                songId = incomingIdentity.songId,
                mediaId = incomingIdentity.mediaId
            ),
            mediaInfo = mediaInfo,
            bitmap = bitmap,
            generation = bitmap.generationId
        )
        val sameInput = previous?.matches(input) == true
        selectedArtwork = input
        if (sameInput) return currentArtworkRequest()

        activeArtworkRequest = null
        startArtworkLocked()
        return null
    }

    private fun startArtworkLocked() {
        if (artworkInFlight) return
        val input = selectedArtwork ?: return
        val lastPalette = lastPublishedArtwork?.palette
        val keyedPalette = keyedCache[input.cacheKey]
        artworkInFlight = true
        try {
            artworkExecutor.execute {
                val palette = try {
                    resolvePalette(input, lastPalette, keyedPalette)
                } catch (error: RuntimeException) {
                    HookLogger.w(TAG, "封面调色板提取失败", error)
                    null
                }
                if (!mainHandler.post { completeArtwork(input, palette) }) {
                    synchronized(this) { artworkInFlight = false }
                }
            }
        } catch (error: RejectedExecutionException) {
            artworkInFlight = false
            HookLogger.w(TAG, "封面调色板任务调度失败", error)
        }
    }

    private fun resolvePalette(
        input: ArtworkInput,
        lastPalette: CacheEntry?,
        keyedPalette: CacheEntry?
    ): CacheEntry? {
        if (!input.hasCurrentPixels()) return null
        val fingerprint = bitmapFingerprint(input.bitmap) ?: return null
        if (lastPalette?.artworkFingerprint?.isSimilarTo(fingerprint) == true) return lastPalette
        if (keyedPalette?.artworkFingerprint?.isSimilarTo(fingerprint) == true) return keyedPalette
        if (!input.hasCurrentPixels()) return null
        val colors = extractPalette(input.bitmap) ?: return null
        if (colors.first.isEmpty() || colors.second.isEmpty()) return null
        return CacheEntry(fingerprint, colors)
    }

    private fun completeArtwork(input: ArtworkInput, palette: CacheEntry?) {
        val (request, mediaInfo) = synchronized(this) {
            artworkInFlight = false
            // A -> B -> A can reuse the original work if A still refers to the same pixels.
            val selected = selectedArtwork
            if (selected == null || !selected.matches(input)) {
                startArtworkLocked()
                return
            }
            if (palette == null || !input.hasCurrentPixels()) return

            keyedCache[input.cacheKey] = palette
            trimCache()
            val ready = ArtworkRequest(input.colorSession, ++artworkRevision)
            val previous = lastPublishedArtwork
            val colorRevision = previous?.takeIf { it.palette === palette }?.colorRevision
                ?: ready.revision
            lastPublishedArtwork = PublishedArtwork(selected, palette, colorRevision)
            activeArtworkRequest = ready
            ready to selected.mediaInfo
        }
        HookLogger.dState(
            stateId = "CoverColorHelper.palette",
            tag = TAG,
            state = "ready|${request.revision}"
        ) {
            "封面调色板结果: sessionRevision=${request.colorSession.revision}, " +
                    "artworkRevision=${request.revision}"
        }
        if (isCurrentArtwork(request)) {
            try {
                onArtworkColorsReady?.invoke(request, mediaInfo)
            } catch (error: RuntimeException) {
                HookLogger.w(TAG, "封面调色板更新失败", error)
            }
        }
    }

    /**
     * Extracts the same background-adapted palette used by the source-owned artwork cache.
     * This does not create or advance a color session and is therefore suitable for native media
     * surfaces that provide their current artwork directly.
     */
    internal fun extractPalette(bitmap: Bitmap): Pair<IntArray, IntArray>? {
        if (bitmap.isRecycled) return null
        val readableBitmap = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: run {
                HookLogger.dState(
                    stateId = "CoverColorHelper.extract",
                    tag = TAG,
                    state = "hardware_copy_failed"
                ) {
                    "封面调色板提取跳过: reason=hardware_bitmap_copy_failed"
                }
                return null
            }
        } else {
            bitmap
        }
        val result = try {
            ColorExtractor.extractThemePalette(readableBitmap, MAX_PALETTE_COLORS)
        } finally {
            if (readableBitmap !== bitmap) readableBitmap.recycle()
        }
        return Pair(
            result.onWhiteBackground.toIntArray(),
            result.onBlackBackground.toIntArray()
        )
    }

    private fun clearArtworkLocked() {
        selectedArtwork = null
        activeArtworkRequest = null
        lastPublishedArtwork = null
    }

    @Synchronized
    fun isCurrentArtwork(request: ArtworkRequest): Boolean {
        return isCurrentArtworkLocked(request)
    }

    @Synchronized
    fun currentArtworkRequest(): ArtworkRequest? {
        return activeArtworkRequest?.takeIf(::isCurrentArtworkLocked)
    }

    @Synchronized
    fun getCachedColors(
        useGradient: Boolean,
        session: ColorSession
    ): Pair<IntArray, IntArray>? {
        return getPublishedArtworkLocked(session)?.palette?.colors?.forGradient(useGradient)
    }

    /** Tracks the usable palette through equivalent bitmap replacements and pending fallback. */
    @Synchronized
    internal fun cachedColorsRevision(session: ColorSession): Long? =
        getPublishedArtworkLocked(session)?.colorRevision

    private fun getPublishedArtworkLocked(session: ColorSession): PublishedArtwork? {
        if (!isCurrentSessionLocked(session)) return null
        val published = lastPublishedArtwork ?: return null
        if (currentArtworkRequest() != null) return published
        return published.takeIf {
            artworkInFlight && selectedArtwork?.canReusePendingPalette(it.input) == true
        }
    }

    @Synchronized
    fun getCachedColors(
        useGradient: Boolean,
        request: ArtworkRequest
    ): Pair<IntArray, IntArray>? {
        if (!isCurrentArtworkLocked(request)) return null
        return lastPublishedArtwork?.palette?.colors?.forGradient(useGradient)
    }

    @Synchronized
    fun clearCache() {
        activeSession = null
        clearArtworkLocked()
        keyedCache.clear()
    }

    private fun isCurrentSessionLocked(session: ColorSession): Boolean {
        val current = activeSession ?: return false
        return current.revision == session.revision &&
                current.packageName == session.packageName &&
                current.sessionToken == session.sessionToken
    }

    private fun matchesSessionLocked(
        session: ColorSession,
        mediaInfo: MediaMetadataHelper.MediaInfo
    ): Boolean {
        val incomingIdentity = mediaInfo.identity.normalized()
        return incomingIdentity.packageName == session.packageName &&
                incomingIdentity.sessionToken == session.sessionToken
    }

    private fun isCurrentArtworkLocked(request: ArtworkRequest): Boolean {
        return activeArtworkRequest === request && selectedArtwork?.hasCurrentPixels() == true
    }

    private fun Pair<IntArray, IntArray>.forGradient(
        useGradient: Boolean
    ): Pair<IntArray, IntArray> {
        if (useGradient) return copyColors()
        val light = first.firstOrNull() ?: return copyColors()
        val dark = second.firstOrNull() ?: return copyColors()
        return Pair(
            intArrayOf(light),
            intArrayOf(dark)
        )
    }

    private fun Pair<IntArray, IntArray>.copyColors(): Pair<IntArray, IntArray> {
        return Pair(first.copyOf(), second.copyOf())
    }

    /**
     * 将封面缩放成固定网格后比较平均像素差，忽略同一封面在尺寸、压缩上的轻微变化。
     */
    internal fun bitmapFingerprint(bitmap: Bitmap): ArtworkFingerprint? {
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            FINGERPRINT_GRID_SIZE,
            FINGERPRINT_GRID_SIZE,
            true
        )
        var readable = scaled
        return try {
            if (scaled.config == Bitmap.Config.HARDWARE) {
                readable = scaled.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            }
            val pixels = IntArray(FINGERPRINT_GRID_SIZE * FINGERPRINT_GRID_SIZE)
            readable.getPixels(
                pixels,
                0,
                FINGERPRINT_GRID_SIZE,
                0,
                0,
                FINGERPRINT_GRID_SIZE,
                FINGERPRINT_GRID_SIZE
            )
            ArtworkFingerprint(pixels)
        } finally {
            if (readable !== scaled && readable !== bitmap) readable.recycle()
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun trimCache() {
        while (keyedCache.size > MAX_CACHED_ARTWORKS) {
            val firstKey = keyedCache.keys.firstOrNull() ?: return
            keyedCache.remove(firstKey)
        }
    }

    private const val MAX_PALETTE_COLORS = 4
    private const val MAX_CACHED_ARTWORKS = 16
    private const val FINGERPRINT_GRID_SIZE = 8
    private const val RGB_CHANNEL_COUNT = 3
    private const val MAX_AVERAGE_CHANNEL_DELTA = 12L
}
