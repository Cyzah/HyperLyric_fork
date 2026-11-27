package com.lidesheng.hyperlyric.root.utils

import android.content.SharedPreferences
import android.graphics.Color
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.common.LyricTextColorStylePolicy
import com.lidesheng.hyperlyric.common.SyllablePreferencePolicy
import com.lidesheng.hyperlyric.common.WordHighlightPreferencePolicy
import com.lidesheng.hyperlyric.common.getFloatCompat
import com.lidesheng.hyperlyric.lyric.view.Highlight
import com.lidesheng.hyperlyric.lyric.view.LyricViewStyle
import com.lidesheng.hyperlyric.lyric.view.Marquee
import com.lidesheng.hyperlyric.lyric.view.TextLook
import com.lidesheng.hyperlyric.lyric.view.TitleSlot
import com.lidesheng.hyperlyric.lyric.view.WordHighlight
import com.lidesheng.hyperlyric.lyric.view.WordMotion
import com.lidesheng.hyperlyric.lyric.view.line.WordRevealAnimation

/**
 * 歌词样式构建助手
 * 负责根据用户配置和歌曲信息（如封面）生成 RichLyricLineView 所需的样式对象
 */
object LyricStyleHelper {
    private const val COVER_BACKGROUND_ALPHA = 165

    internal data class Colors(val primary: IntArray, val highlight: Highlight)

    /**
     * 构建歌词样式对象
     */
    internal fun buildStyle(
        prefs: SharedPreferences,
        primarySizePx: Float,
        mode: Int,
        colors: Colors,
        lyricAlignmentOverride: Int? = null,
    ): LyricViewStyle {
        val syllableSettings = SyllablePreferencePolicy.read(prefs)
        val font = FontHelper.loadFont(prefs)

        val textSizeRatio = getFloatCompat(
            prefs,
            RootConstants.KEY_HOOK_TEXT_SIZE_RATIO,
            RootConstants.DEFAULT_HOOK_TEXT_SIZE_RATIO
        )
        val multiLineScale = getFloatCompat(
            prefs,
            RootConstants.KEY_HOOK_MULTI_LINE_SCALE,
            RootConstants.DEFAULT_HOOK_MULTI_LINE_SCALE
        )

        // 内容类型、可见性和优先级由 LyricContentDisplayPolicy 解析后交给 View；无内容时
        // assembler 返回 alwaysShow=false → secondary GONE。
        val secondarySlotVisible = mode == RootConstants.ISLAND_CONTENT_MODE_LYRIC ||
                mode == RootConstants.ISLAND_CONTENT_MODE_CUSTOM_MUSIC_INFO

        val isLyricMode = mode == RootConstants.ISLAND_CONTENT_MODE_LYRIC
        val contentAlignment = RootConstants.normalizeHookContentAlignment(
            if (isLyricMode) {
                lyricAlignmentOverride ?: prefs.getInt(
                    RootConstants.KEY_HOOK_LYRIC_ALIGNMENT,
                    RootConstants.DEFAULT_HOOK_LYRIC_ALIGNMENT
                )
            } else {
                prefs.getInt(
                    RootConstants.KEY_HOOK_MUSIC_INFO_ALIGNMENT,
                    RootConstants.DEFAULT_HOOK_MUSIC_INFO_ALIGNMENT
                )
            }
        )
        val centerIfPossible = contentAlignment == RootConstants.CONTENT_ALIGNMENT_CENTER
        val rightIfPossible = contentAlignment == RootConstants.CONTENT_ALIGNMENT_RIGHT
        val isMarqueeEnabled = if (isLyricMode) {
            prefs.getBoolean(
                RootConstants.KEY_HOOK_MARQUEE_MODE,
                RootConstants.DEFAULT_HOOK_MARQUEE_MODE
            )
        } else {
            prefs.getBoolean(
                RootConstants.KEY_HOOK_MARQUEE_METADATA_MODE,
                RootConstants.DEFAULT_HOOK_MARQUEE_METADATA_MODE
            )
        }
        val marqueeSpeed = if (isLyricMode) {
            prefs.getInt(
                RootConstants.KEY_HOOK_MARQUEE_SPEED,
                RootConstants.DEFAULT_HOOK_MARQUEE_SPEED
            )
        } else {
            prefs.getInt(
                RootConstants.KEY_HOOK_MARQUEE_METADATA_SPEED,
                RootConstants.DEFAULT_HOOK_MARQUEE_METADATA_SPEED
            )
        }
        val marqueeDelay = if (isLyricMode) {
            prefs.getInt(
                RootConstants.KEY_HOOK_MARQUEE_DELAY,
                RootConstants.DEFAULT_HOOK_MARQUEE_DELAY
            )
        } else {
            prefs.getInt(
                RootConstants.KEY_HOOK_MARQUEE_METADATA_DELAY,
                RootConstants.DEFAULT_HOOK_MARQUEE_METADATA_DELAY
            )
        }
        val marqueeLoopDelay = if (isLyricMode) {
            prefs.getInt(
                RootConstants.KEY_HOOK_MARQUEE_LOOP_DELAY,
                RootConstants.DEFAULT_HOOK_MARQUEE_LOOP_DELAY
            )
        } else {
            prefs.getInt(
                RootConstants.KEY_HOOK_MARQUEE_METADATA_LOOP_DELAY,
                RootConstants.DEFAULT_HOOK_MARQUEE_METADATA_LOOP_DELAY
            )
        }
        val infinite = if (isLyricMode) {
            prefs.getBoolean(
                RootConstants.KEY_HOOK_MARQUEE_INFINITE,
                RootConstants.DEFAULT_HOOK_MARQUEE_INFINITE
            )
        } else {
            prefs.getBoolean(
                RootConstants.KEY_HOOK_MARQUEE_METADATA_INFINITE,
                RootConstants.DEFAULT_HOOK_MARQUEE_METADATA_INFINITE
            )
        }
        val stopAtEnd = if (isLyricMode) {
            prefs.getBoolean(
                RootConstants.KEY_HOOK_MARQUEE_STOP_END,
                RootConstants.DEFAULT_HOOK_MARQUEE_STOP_END
            )
        } else {
            true
        }

        return LyricViewStyle(
            primary = TextLook(
                color = colors.primary,
                size = primarySizePx,
                typeface = font.typeface,
                fontVariationSettings = font.variationSettings,
                relativeProgress = syllableSettings.relativeProgress,
                relativeHighlight = syllableSettings.relativeHighlight,
            ),
            secondary = TextLook(
                color = if (secondarySlotVisible) colors.primary else intArrayOf(Color.TRANSPARENT),
                size = if (secondarySlotVisible) primarySizePx * textSizeRatio else 0f,
                typeface = font.typeface,
                fontVariationSettings = font.variationSettings,
            ),
            highlight = colors.highlight,
            marquee = Marquee(
                speed = if (isMarqueeEnabled) marqueeSpeed.toFloat() else 0f,
                initialDelay = marqueeDelay,
                loopDelay = marqueeLoopDelay,
                repeatCount = if (!isMarqueeEnabled) 0 else if (infinite) -1 else 1,
                stopAtEnd = stopAtEnd,
            ),
            gradient = prefs.getBoolean(
                RootConstants.KEY_HOOK_GRADIENT_PROGRESS,
                RootConstants.DEFAULT_HOOK_GRADIENT_PROGRESS
            ),
            wordReveal = isLyricMode && prefs.getInt(
                RootConstants.KEY_HOOK_WORD_EFFECT,
                RootConstants.DEFAULT_HOOK_WORD_EFFECT
            ) == RootConstants.WORD_EFFECT_REVEAL,
            wordRevealAnimation = WordRevealAnimation.entries[prefs.getInt(
                RootConstants.KEY_HOOK_WORD_REVEAL_ANIMATION,
                RootConstants.DEFAULT_HOOK_WORD_REVEAL_ANIMATION
            )],
            wordRevealSpeedRate = RootConstants.normalizeHookWordRevealSpeedRate(
                prefs.getInt(
                    RootConstants.KEY_HOOK_WORD_REVEAL_SPEED_RATE,
                    RootConstants.DEFAULT_HOOK_WORD_REVEAL_SPEED_RATE
                )
            ),
            scaleMultiLine = multiLineScale,
            lineDisplay = syllableSettings.lineDisplay,
            fadingEdge = prefs.getInt(
                RootConstants.KEY_HOOK_FADING_EDGE_LENGTH,
                RootConstants.DEFAULT_HOOK_FADING_EDGE_LENGTH
            ),
            wordMotion = WordMotion(
                enabled = prefs.getBoolean(
                    RootConstants.KEY_HOOK_WORD_MOTION_ENABLED,
                    RootConstants.DEFAULT_HOOK_WORD_MOTION_ENABLED
                ),
                cjkLiftFactor = getFloatCompat(
                    prefs,
                    RootConstants.KEY_HOOK_WORD_MOTION_CJK_LIFT,
                    RootConstants.DEFAULT_HOOK_WORD_MOTION_CJK_LIFT
                ),
                cjkWaveFactor = getFloatCompat(
                    prefs,
                    RootConstants.KEY_HOOK_WORD_MOTION_CJK_WAVE,
                    RootConstants.DEFAULT_HOOK_WORD_MOTION_CJK_WAVE
                ),
                latinByCharacter = prefs.getBoolean(
                    RootConstants.KEY_HOOK_WORD_MOTION_LATIN_BY_CHARACTER,
                    RootConstants.DEFAULT_HOOK_WORD_MOTION_LATIN_BY_CHARACTER
                ),
                latinLiftFactor = getFloatCompat(
                    prefs,
                    RootConstants.KEY_HOOK_WORD_MOTION_LATIN_LIFT,
                    RootConstants.DEFAULT_HOOK_WORD_MOTION_LATIN_LIFT
                ),
                latinWaveFactor = getFloatCompat(
                    prefs,
                    RootConstants.KEY_HOOK_WORD_MOTION_LATIN_WAVE,
                    RootConstants.DEFAULT_HOOK_WORD_MOTION_LATIN_WAVE
                ),
            ),
            // 歌词不透明度（未演唱/已演唱）：先按偏好归一化，再交给 View 层渲染。
            wordHighlight = WordHighlight(
                unsungOpacity = WordHighlightPreferencePolicy.normalizeUnsungOpacity(
                    getFloatCompat(
                        prefs,
                        RootConstants.KEY_HOOK_WORD_HIGHLIGHT_UNSUNG_OPACITY,
                        RootConstants.DEFAULT_HOOK_WORD_HIGHLIGHT_UNSUNG_OPACITY
                    )
                ),
                sungOpacity = WordHighlightPreferencePolicy.normalizeSungOpacity(
                    getFloatCompat(
                        prefs,
                        RootConstants.KEY_HOOK_WORD_HIGHLIGHT_SUNG_OPACITY,
                        RootConstants.DEFAULT_HOOK_WORD_HIGHLIGHT_SUNG_OPACITY
                    )
                )
            ),
            placeholder = TitleSlot.NONE,
            centerIfPossible = centerIfPossible,
            rightIfPossible = rightIfPossible,
        )
    }

    internal fun resolveColors(
        textColorStyle: Int,
        colorSession: CoverColorHelper.ColorSession?,
        textColorOverride: Int?,
        coverPaletteBackgroundIsDark: Boolean,
    ): Colors {
        if (textColorOverride != null) return solidColors(textColorOverride)
        if (!LyricTextColorStylePolicy.usesCoverColor(textColorStyle)) {
            return solidColors(Color.WHITE)
        }

        val useGradient = LyricTextColorStylePolicy.usesCoverGradient(textColorStyle)
        val palette = colorSession?.let { CoverColorHelper.getCachedColors(useGradient, it) }
        val coverColors = if (coverPaletteBackgroundIsDark) palette?.second else palette?.first
        if (coverColors == null || coverColors.isEmpty()) {
            return solidColors(if (coverPaletteBackgroundIsDark) Color.WHITE else Color.BLACK)
        }
        return Colors(
            primary = coverColors,
            highlight = Highlight(
                background = IntArray(coverColors.size) { index ->
                    withAlpha(coverColors[index], COVER_BACKGROUND_ALPHA)
                },
                foreground = coverColors,
            ),
        )
    }

    private fun solidColors(color: Int): Colors {
        val foreground = intArrayOf(color)
        return Colors(foreground, Highlight(intArrayOf(withAlpha(color, 128)), foreground))
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}

