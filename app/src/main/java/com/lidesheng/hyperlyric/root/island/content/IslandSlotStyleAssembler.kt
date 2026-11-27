package com.lidesheng.hyperlyric.root.island.content

import android.content.SharedPreferences
import android.util.TypedValue
import android.view.View
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.common.media.MediaMetadataHelper
import com.lidesheng.hyperlyric.lyric.view.RichLyricLineView
import com.lidesheng.hyperlyric.lyric.view.SpaceGateRichLyricLineView
import com.lidesheng.hyperlyric.lyric.view.UpdatableColor
import com.lidesheng.hyperlyric.root.island.config.IslandSlotRuntimeConfig
import com.lidesheng.hyperlyric.root.island.effects.color.StatusBarTextColorHooker
import com.lidesheng.hyperlyric.root.utils.CoverColorHelper
import com.lidesheng.hyperlyric.root.utils.FontHelper
import com.lidesheng.hyperlyric.root.utils.LyricStyleHelper
import java.util.Collections
import java.util.WeakHashMap

internal object IslandSlotStyleAssembler {
    private data class StyleSignature(
        val preferences: String,
        val mode: Int,
        val isLeftSplitSide: Boolean,
        val primarySizePx: Float,
        val customFont: FontHelper.FontFileState?,
    )

    private data class ColorSignature(
        val textColorStyle: Int,
        val darkBackground: Boolean,
        val statusBarTextColor: Int?,
        val paletteRevision: Long?,
    )

    private data class AppliedStyle(
        val style: StyleSignature,
        val color: ColorSignature,
        val colors: LyricStyleHelper.Colors,
    )

    private val appliedStyles = Collections.synchronizedMap(WeakHashMap<View, AppliedStyle>())

    fun primaryTextColor(view: View): Int? = appliedStyles[view]?.colors?.primary?.firstOrNull()

    fun invalidate(view: View? = null) {
        if (view == null) appliedStyles.clear() else appliedStyles.remove(view)
    }

    /** Returns whether the text metrics or other full style properties were applied. */
    fun configureView(
        view: View,
        prefs: SharedPreferences,
        config: IslandSlotRuntimeConfig,
        mode: Int,
        mediaInfo: MediaMetadataHelper.MediaInfo,
        force: Boolean,
        coverPaletteBackgroundIsDark: Boolean
    ): Boolean {
        val lyricContentDisplay = config.lyricContentDisplay
        val colorSession = if (config.extractCoverTextColor) {
            CoverColorHelper.ensureArtworkColors(mediaInfo)
            CoverColorHelper.currentSession(mediaInfo)
        } else {
            null
        }
        val statusBarTextColor = if (config.followStatusBarTextColor) {
            StatusBarTextColorHooker.currentTextColor()
        } else {
            null
        }
        val primarySizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            config.textSizeSp.toFloat(),
            view.resources.displayMetrics,
        )
        val isLyricMode = mode == RootConstants.ISLAND_CONTENT_MODE_LYRIC
        val isLeftSplitSide = view is SpaceGateRichLyricLineView &&
                config.isLeftTag(view.tag as? String ?: "") && isLyricMode
        val styleSignature = StyleSignature(
            config.styleSignature,
            mode,
            isLeftSplitSide,
            primarySizePx,
            config.customFontPath.takeIf { it.isNotBlank() }?.let(FontHelper.FontFileState::read),
        )
        val colorSignature = ColorSignature(
            textColorStyle = config.textColorStyle,
            darkBackground = config.extractCoverTextColor && coverPaletteBackgroundIsDark,
            statusBarTextColor = statusBarTextColor,
            paletteRevision = colorSession?.let(CoverColorHelper::cachedColorsRevision),
        )

        val previous = appliedStyles[view]
        val styleChanged = force || previous?.style != styleSignature
        val colorChanged = force || previous?.color != colorSignature
        if (!styleChanged && !colorChanged) return false

        val colors = previous?.colors?.takeUnless { colorChanged } ?: LyricStyleHelper.resolveColors(
            textColorStyle = config.textColorStyle,
            colorSession = colorSession,
            textColorOverride = statusBarTextColor,
            coverPaletteBackgroundIsDark = coverPaletteBackgroundIsDark,
        )
        if (styleChanged) {
            val style = LyricStyleHelper.buildStyle(
                prefs = prefs,
                primarySizePx = primarySizePx,
                mode = mode,
                colors = colors,
                lyricAlignmentOverride = config.lyricAlignment.takeIf { isLyricMode },
            )
            when (view) {
                is RichLyricLineView -> {
                    view.displayTranslation = lyricContentDisplay.showTranslation
                    view.displayRoma = lyricContentDisplay.showRoma
                    view.displayBackgroundVocal = lyricContentDisplay.showBackgroundVocal
                    view.secondaryContentOrder = lyricContentDisplay.order
                    view.setStyle(style)
                }
                is SpaceGateRichLyricLineView -> {
                    view.displayTranslation = lyricContentDisplay.showTranslation
                    view.displayRoma = lyricContentDisplay.showRoma
                    view.displayBackgroundVocal = lyricContentDisplay.showBackgroundVocal
                    view.secondaryContentOrder = lyricContentDisplay.order
                    view.setStyle(
                        style,
                        isLeftSplitSide = isLeftSplitSide,
                        useSplitAlignment = isLyricMode,
                    )
                }
            }
        } else {
            (view as? UpdatableColor)?.updateColor(
                colors.primary,
                colors.highlight.background,
                colors.highlight.foreground,
            )
        }
        appliedStyles[view] = AppliedStyle(styleSignature, colorSignature, colors)
        return styleChanged
    }
}
