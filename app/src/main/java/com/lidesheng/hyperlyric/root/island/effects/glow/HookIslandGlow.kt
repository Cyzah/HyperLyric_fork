package com.lidesheng.hyperlyric.root.island.effects.glow

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.media.session.MediaSession
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.common.LyricTextColorStylePolicy
import com.lidesheng.hyperlyric.common.media.MediaIdentity
import com.lidesheng.hyperlyric.common.media.MediaMetadataHelper
import com.lidesheng.hyperlyric.root.HookEntry
import com.lidesheng.hyperlyric.root.managedHook
import com.lidesheng.hyperlyric.root.island.host.IslandProbeUtils
import com.lidesheng.hyperlyric.root.island.policy.IslandModificationTargetPolicy
import com.lidesheng.hyperlyric.root.media.CurrentMediaInfoResolver
import com.lidesheng.hyperlyric.root.utils.CoverColorHelper
import com.lidesheng.hyperlyric.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@SuppressLint("DiscouragedPrivateApi", "PrivateApi")
object HookIslandGlow {
    private const val TAG = "HookIslandGlow"
    private const val BASE_CONTENT_VIEW_CLASS =
        "miui.systemui.dynamicisland.window.content.DynamicIslandBaseContentView"
    private const val DATA_CLASS =
        "com.android.systemui.plugins.miui.dynamicisland.DynamicIslandData"

    private var module: XposedModule? = null
    private val hookedClassLoaders = java.util.Collections.synchronizedSet(
        java.util.Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )
    private val lastGlowEnabledByView = WeakHashMap<View, Boolean>()
    private var refreshingArtwork: MediaMetadataHelper.MediaInfo? = null
    private val nativeArtworkByView = WeakHashMap<View, NativeArtworkState>()
    private var nativeArtworkInFlight = false
    private var nativeReplay: NativeReplay? = null
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val nativeArtworkExecutor = ThreadPoolExecutor(
        0, 1, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(1)
    ) { work ->
        Thread(work, "HyperLyric-NativeGlowColors").apply { isDaemon = true }
    }

    private data class NativeTrack(
        val islandToken: MediaSession.Token?,
        val mediaIdentity: MediaIdentity,
        val title: String?,
        val artist: String?,
        val album: String?,
    )

    private class NativeArtwork(
        val islandData: Any,
        val bitmap: Bitmap,
        val generation: Int,
    ) {
        fun hasSameBitmap(other: NativeArtwork): Boolean =
            bitmap === other.bitmap && generation == other.generation

        fun hasCurrentPixels(): Boolean = !bitmap.isRecycled && bitmap.generationId == generation
    }

    private class NativePalette(
        val fingerprint: CoverColorHelper.ArtworkFingerprint,
        val color: String?,
    )

    private class NativeArtworkState(val track: NativeTrack, var latest: NativeArtwork) {
        var queued = true
        var resolved: NativeArtwork? = null
        var palette: NativePalette? = null

        fun currentColor(): String? = palette?.color?.takeIf {
            latest.hasCurrentPixels() && resolved?.hasCurrentPixels() == true
        }
    }

    private class NativeReplay(val view: View, val islandData: Any, val color: String)

    private val prefs: SharedPreferences?
        get() = (module as? HookEntry)?.prefs

    fun initialize(xposedModule: XposedModule) {
        module = xposedModule
    }

    fun cleanupForHotReload() {
        synchronized(nativeArtworkByView) { nativeArtworkByView.clear() }
        synchronized(lastGlowEnabledByView) {
            lastGlowEnabledByView.keys.toList().forEach(::clearViewHighlightColor)
            lastGlowEnabledByView.clear()
        }
        hookedClassLoaders.clear()
        module = null
    }

    fun init(xposedModule: XposedModule, cl: ClassLoader) {
        initialize(xposedModule)
        if (!hookedClassLoaders.add(cl)) return

        try {
            val baseContentViewClass = cl.loadClass(BASE_CONTENT_VIEW_CLASS)
            val dataClass = baseContentViewClass.classLoader?.loadClass(DATA_CLASS) ?: return
            val updateTemplateMethod = baseContentViewClass.declaredMethods.find {
                it.name == "updateTemplate" &&
                        it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == dataClass
            }

            if (updateTemplateMethod != null) {
                updateTemplateMethod.isAccessible = true
                xposedModule.managedHook(
                    executable = updateTemplateMethod,
                    capability = "island.glow.update_template",
                    hooker = UpdateTemplateHook(),
                )
                HookLogger.d(TAG, "媒体岛光效 Hook 已初始化: method=updateTemplate")
            } else {
                HookLogger.w(TAG, "未找到 updateTemplate，跳过媒体岛光效 Hook")
            }
        } catch (e: ClassNotFoundException) {
            HookLogger.w(TAG, "跳过不支持的媒体岛光效 Hook: ${e.message}")
        } catch (e: Exception) {
            HookLogger.e(TAG, "初始化媒体岛光效 Hook 失败", e)
        }
    }

    class UpdateTemplateHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val view = chain.thisObject as? View
            val data = chain.args.getOrNull(0)
            val color = prepareHighlightColor(view, data)
            if (color != null) {
                val result = injectTickerDataHighlightColor(data, color)
                HookLogger.dState(
                    stateId = "HookIslandGlow.apply:${System.identityHashCode(view)}",
                    tag = TAG,
                    state = "$color|$result"
                ) {
                    "媒体岛光效颜色注入: color=$color, result=$result, " +
                            "view=${System.identityHashCode(view)}"
                }
            }
            return chain.proceed()
        }
    }

    private fun prepareHighlightColor(view: View?, islandData: Any?): String? {
        return runCatching {
            val sharedPrefs = prefs ?: return@runCatching null
            if (!sharedPrefs.getBoolean(
                    RootConstants.KEY_HOOK_ENABLE,
                    RootConstants.DEFAULT_HOOK_ENABLE
                )
            ) {
                HookLogger.dState(
                    stateId = "HookIslandGlow.prepare",
                    tag = TAG,
                    state = "super_island_disabled"
                ) {
                    "媒体岛光效颜色未准备: reason=super_island_disabled"
                }
                return@runCatching null
            }
            if (!sharedPrefs.getBoolean(
                    RootConstants.KEY_HOOK_ISLAND_GLOW_EXTRACT_COLOR,
                    RootConstants.DEFAULT_HOOK_ISLAND_GLOW_EXTRACT_COLOR
                )
            ) {
                HookLogger.dState(
                    stateId = "HookIslandGlow.prepare",
                    tag = TAG,
                    state = "extract_disabled"
                ) {
                    "媒体岛光效颜色未准备: reason=color_extraction_disabled"
                }
                return@runCatching null
            }

            val target = IslandModificationTargetPolicy.resolve(
                data = islandData,
                hostRoot = view as? ViewGroup
            )
            val mediaInfoFromIsland = target.mediaInfo
                ?: run {
                    HookLogger.dState(
                        stateId = "HookIslandGlow.prepare",
                        tag = TAG,
                        state = "not_media_island"
                    ) {
                        "媒体岛光效颜色未准备: reason=not_media_island"
                    }
                    return@runCatching null
                }
            val pkgName = mediaInfoFromIsland.packageName
            val scope = IslandModificationTargetPolicy.currentScope(sharedPrefs)
            if (pkgName.isEmpty() || !IslandModificationTargetPolicy.allows(target, scope)) {
                HookLogger.dState(
                    stateId = "HookIslandGlow.prepare",
                    tag = TAG,
                    state = "scope_not_allowed|$scope|$pkgName"
                ) {
                    "媒体岛光效颜色未准备: reason=scope_not_allowed, scope=$scope, " +
                            "mediaPackage=$pkgName"
                }
                return@runCatching null
            }

            val contentView = view ?: run {
                HookLogger.dState(
                    stateId = "HookIslandGlow.prepare",
                    tag = TAG,
                    state = "view_context_missing"
                ) {
                    "媒体岛光效颜色未准备: reason=view_context_missing"
                }
                return@runCatching null
            }
            if (scope == IslandModificationTargetPolicy.Scope.ALL_MEDIA) {
                return@runCatching prepareNativeHighlightColor(
                    contentView, islandData ?: return@runCatching null, pkgName
                )
            }

            val mediaInfo = refreshingArtwork?.takeIf { it.identity.packageName == pkgName }
                ?: CurrentMediaInfoResolver.getMediaInfo(contentView.context, pkgName, HookLogger)
            val colorSession = CoverColorHelper.currentSession(mediaInfo) ?: run {
                HookLogger.dState(
                    stateId = "HookIslandGlow.prepare",
                    tag = TAG,
                    state = "no_color_session"
                ) {
                    "媒体岛光效颜色未准备: reason=no_matching_color_session"
                }
                return@runCatching null
            }
            val artworkRequest = CoverColorHelper.ensureArtworkColors(mediaInfo)
            val useGradient = LyricTextColorStylePolicy.usesCoverGradient(
                LyricTextColorStylePolicy.read(sharedPrefs)
            )
            val matchingArtworkRequest = artworkRequest?.takeIf {
                it.colorSession.revision == colorSession.revision
            }
            val palette = if (matchingArtworkRequest != null) {
                CoverColorHelper.getCachedColors(useGradient, matchingArtworkRequest)
            } else {
                CoverColorHelper.getCachedColors(useGradient, colorSession)
            }
            val color = palette
                ?.second
                ?.firstOrNull()
                ?: run {
                    HookLogger.dState(
                        stateId = "HookIslandGlow.prepare",
                        tag = TAG,
                        state = "no_palette|${colorSession.revision}"
                    ) {
                        "媒体岛光效颜色未准备: reason=no_cached_cover_palette, " +
                                "sessionRevision=${colorSession.revision}"
                    }
                    return@runCatching null
                }

            val colorString = String.format("#%08X", color)
            HookLogger.dState(
                stateId = "HookIslandGlow.prepare",
                tag = TAG,
                state = "ready|${colorSession.revision}|${artworkRequest?.revision}|$colorString"
            ) {
                "媒体岛光效颜色已准备: color=$colorString, sessionRevision=${colorSession.revision}, " +
                        "artworkRevision=${artworkRequest?.revision}, source=cover"
            }
            colorString
        }.onFailure { e ->
            HookLogger.e(TAG, "解析媒体岛光效颜色失败", e)
        }.getOrNull()
    }

    private fun prepareNativeHighlightColor(view: View, islandData: Any, packageName: String): String? {
        val replay = nativeReplay
        if (replay?.view === view && replay.islandData === islandData) return replay.color

        // Native islands pin their own MediaSession when the notification carries a token.
        val islandToken = IslandProbeUtils.extractMediaSessionToken(islandData)
        val mediaInfo = MediaMetadataHelper.getMediaInfo(
            context = view.context,
            packageName = packageName,
            logger = HookLogger,
            preferredSessionToken = islandToken,
        )
        val bitmap = mediaInfo.albumArt?.takeUnless { it.isRecycled }
        val identity = mediaInfo.identity.normalized()
        return synchronized(nativeArtworkByView) {
            if (bitmap == null || identity.sessionToken == null) {
                nativeArtworkByView.remove(view)
                return@synchronized null
            }
            val hasItemId = identity.songId != null || identity.mediaId != null
            val track = NativeTrack(
                islandToken,
                identity,
                mediaInfo.title.takeUnless { hasItemId },
                mediaInfo.artist.takeUnless { hasItemId },
                mediaInfo.album.takeUnless { hasItemId },
            )
            val request = NativeArtwork(islandData, bitmap, bitmap.generationId)
            val previous = nativeArtworkByView[view]
            val state = if (previous?.track == track) {
                if (!previous.latest.hasSameBitmap(request)) previous.queued = true
                previous.latest = request
                previous
            } else {
                NativeArtworkState(track, request).also { nativeArtworkByView[view] = it }
            }
            startNativeArtworkLocked()
            state.currentColor()
        }
    }

    private fun startNativeArtworkLocked() {
        if (nativeArtworkInFlight) return
        val next = nativeArtworkByView.entries.firstOrNull { it.value.queued } ?: return
        val view = WeakReference(next.key)
        val state = next.value
        val first = state.latest
        val cached = state.palette
        state.queued = false
        nativeArtworkInFlight = true
        try {
            nativeArtworkExecutor.execute {
                val firstPalette = readNativePalette(first, cached)
                val newer = synchronized(nativeArtworkByView) {
                    val current = view.get()?.let(nativeArtworkByView::get)
                    current?.latest?.takeIf { current === state && !it.hasSameBitmap(first) }
                }
                val selected = newer ?: first
                val palette = if (newer != null) readNativePalette(newer, firstPalette ?: cached) else firstPalette
                if (!mainHandler.post { completeNativeArtwork(view.get(), state, selected, palette) }) {
                    synchronized(nativeArtworkByView) { nativeArtworkInFlight = false }
                }
            }
        } catch (error: RejectedExecutionException) {
            nativeArtworkInFlight = false
            HookLogger.w(TAG, "媒体岛封面调色板任务调度失败", error)
        }
    }

    private fun readNativePalette(artwork: NativeArtwork, cached: NativePalette?): NativePalette? {
        return try {
            if (!artwork.hasCurrentPixels()) return null
            val fingerprint = CoverColorHelper.bitmapFingerprint(artwork.bitmap) ?: return null
            if (cached?.fingerprint?.hasSamePixels(fingerprint) == true) return cached

            // Keep the fingerprint when no color is available so equivalent wrappers can reuse it.
            val color = CoverColorHelper.extractPalette(artwork.bitmap)?.second?.firstOrNull()
            NativePalette(fingerprint, color?.let { String.format("#%08X", it) })
        } catch (error: RuntimeException) {
            HookLogger.w(TAG, "媒体岛封面调色板提取失败", error)
            null
        }
    }

    private fun completeNativeArtwork(
        view: View?,
        state: NativeArtworkState,
        request: NativeArtwork,
        palette: NativePalette?,
    ) {
        val replay = synchronized(nativeArtworkByView) {
            nativeArtworkInFlight = false
            val current = view?.let(nativeArtworkByView::get)
            val replay = if (view != null && current === state) {
                val latest = state.latest
                val bitmapChangedInPlace = latest.bitmap === request.bitmap && !latest.hasSameBitmap(request)
                if (latest.hasSameBitmap(request)) state.queued = false

                // Parcel clones may arrive faster than extraction. Publish the newest verified
                // colors for this item and leave a newer wrapper queued for comparison.
                if (request.hasCurrentPixels() && latest.hasCurrentPixels() && !bitmapChangedInPlace) {
                    val previousColor = state.currentColor()
                    state.resolved = request
                    state.palette = palette
                    palette?.color?.takeIf { it != previousColor }?.let {
                        NativeReplay(view, latest.islandData, it)
                    }
                } else null
            } else null
            startNativeArtworkLocked()
            replay
        } ?: return

        publishNativeArtwork(replay, state.track)
    }

    private fun publishNativeArtwork(replay: NativeReplay, track: NativeTrack) {
        val sharedPrefs = prefs ?: return
        if (!sharedPrefs.getBoolean(RootConstants.KEY_HOOK_ENABLE, RootConstants.DEFAULT_HOOK_ENABLE) ||
            !sharedPrefs.getBoolean(
                RootConstants.KEY_HOOK_ISLAND_GLOW_EXTRACT_COLOR,
                RootConstants.DEFAULT_HOOK_ISLAND_GLOW_EXTRACT_COLOR,
            ) || IslandModificationTargetPolicy.currentScope(sharedPrefs) !=
                IslandModificationTargetPolicy.Scope.ALL_MEDIA
        ) return
        val currentData = IslandProbeUtils.getCurrentIslandData(replay.view)
        if (currentData !== replay.islandData ||
            IslandProbeUtils.extractMediaSessionToken(currentData) != track.islandToken ||
            IslandProbeUtils.extractMediaIslandInfo(currentData)?.packageName != track.mediaIdentity.packageName
        ) return

        val previous = nativeReplay
        nativeReplay = replay
        try {
            refreshTemplateForCurrentIsland(replay.view)
        } finally {
            nativeReplay = previous
        }
    }

    private fun injectTickerDataHighlightColor(islandData: Any?, color: String): String {
        return runCatching {
            val receiver = islandData ?: return@runCatching "island_data_null"
            val getTickerData = receiver.javaClass.methods.find {
                it.name == "getTickerData" && it.parameterTypes.isEmpty()
            } ?: return@runCatching "getter_missing"
            val setTickerData = receiver.javaClass.methods.find {
                it.name == "setTickerData" &&
                        it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == String::class.java
            } ?: return@runCatching "setter_missing"

            val tickerData = getTickerData.invoke(receiver) as? String
                ?: return@runCatching "ticker_data_null"
            if (tickerData.isBlank()) return@runCatching "ticker_data_blank"

            val json = JSONObject(tickerData)
            json.put("highlightColor", color)
            setTickerData.invoke(receiver, json.toString())
            "applied"
        }.onFailure { e ->
            HookLogger.e(TAG, "向 tickerData 注入 highlightColor 失败", e)
        }.getOrDefault("exception")
    }

    internal fun refreshArtworkGlow(
        view: View,
        mediaInfo: MediaMetadataHelper.MediaInfo,
        prefs: SharedPreferences
    ) {
        if (IslandModificationTargetPolicy.currentScope(prefs) !=
            IslandModificationTargetPolicy.Scope.INJECTED_LYRIC ||
            !prefs.getBoolean(
                RootConstants.KEY_HOOK_ISLAND_GLOW_EXTRACT_COLOR,
                RootConstants.DEFAULT_HOOK_ISLAND_GLOW_EXTRACT_COLOR
            )
        ) return
        val previous = refreshingArtwork
        refreshingArtwork = mediaInfo
        try {
            updateMusicGlow(view, prefs, forceRefresh = true)
        } finally {
            refreshingArtwork = previous
        }
    }

    fun updateMusicGlow(
        contentView: View?,
        sharedPrefs: SharedPreferences,
        forceRefresh: Boolean = false
    ) {
        val enabled = sharedPrefs.getBoolean(
            RootConstants.KEY_HOOK_ISLAND_GLOW_EXTRACT_COLOR,
            RootConstants.DEFAULT_HOOK_ISLAND_GLOW_EXTRACT_COLOR
        )
        if (!enabled) {
            if (contentView != null) synchronized(nativeArtworkByView) {
                nativeArtworkByView.remove(contentView)
            }
            HookLogger.dState(
                stateId = "HookIslandGlow.config",
                tag = TAG,
                state = "disabled"
            ) {
                "媒体岛光效未生效: reason=color_extraction_disabled"
            }
            clearViewHighlightColor(contentView)
            rememberGlowEnabled(contentView, false)
            if (forceRefresh) {
                contentView?.let(::refreshTemplateForCurrentIsland)
            }
            return
        }
        if (contentView != null) {
            val wasEnabled = rememberGlowEnabled(contentView, true)
            if (forceRefresh || wasEnabled != true) {
                refreshTemplateForCurrentIsland(contentView)
            }
        }
    }

    private fun rememberGlowEnabled(view: View?, enabled: Boolean): Boolean? {
        view ?: return null
        return synchronized(lastGlowEnabledByView) {
            val previous = lastGlowEnabledByView[view]
            lastGlowEnabledByView[view] = enabled
            previous
        }
    }

    private fun refreshTemplateForCurrentIsland(view: View) {
        runCatching {
            val islandData = IslandProbeUtils.getCurrentIslandData(view) ?: return
            val updateTemplate = view.javaClass.methods.find {
                it.name == "updateTemplate" && it.parameterTypes.size == 1
            } ?: return
            updateTemplate.invoke(view, islandData)
        }.onFailure { e ->
            HookLogger.e(TAG, "刷新媒体岛光效模板失败", e)
        }
    }

    private fun clearViewHighlightColor(view: View?) {
        runCatching {
            view ?: return
            val template = findFieldInHierarchy(view.javaClass, "template")?.get(view)
            template?.javaClass?.methods
                ?.find {
                    it.name == "setHighlightColor" &&
                            it.parameterTypes.size == 1 &&
                            it.parameterTypes[0] == String::class.java
                }
                ?.invoke(template, null)

            val highlightState = findFieldInHierarchy(view.javaClass, "_highlightColor")?.get(view)
            highlightState?.javaClass?.methods
                ?.find { it.name == "setValue" && it.parameterTypes.size == 1 }
                ?.invoke(highlightState, null)
        }.onFailure { e ->
            HookLogger.e(TAG, "清除视图 highlightColor 失败", e)
        }
    }

    private fun findFieldInHierarchy(clazz: Class<*>, fieldName: String): java.lang.reflect.Field? {
        var current: Class<*>? = clazz
        while (current != null && current != View::class.java) {
            try {
                val field = current.getDeclaredField(fieldName)
                field.isAccessible = true
                return field
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }
}
