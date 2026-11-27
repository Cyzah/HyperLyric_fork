/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.lidesheng.hyperlyric.lyric.view.line

import android.graphics.Typeface
import android.text.TextPaint

internal class TextPaintX : TextPaint(ANTI_ALIAS_FLAG) {
    private var appliedFont: AppliedFont? = null

    fun applyFont(baseTypeface: Typeface?, variationSettings: String?) {
        val previous = appliedFont
        if (previous != null &&
            previous.baseTypeface === baseTypeface &&
            previous.requestedVariations == variationSettings &&
            previous.effectiveTypeface === typeface &&
            previous.effectiveVariations == fontVariationSettings
        ) return

        // Paint 会基于当前 Typeface 派生 variation Typeface；先清理旧轴，
        // 再替换基础字体并重新应用，避免切换设置后继续持有上一条回退链。
        setFontVariationSettings(null)
        typeface = baseTypeface
        variationSettings?.let(::setFontVariationSettings)
        appliedFont = AppliedFont(baseTypeface, variationSettings, typeface, fontVariationSettings)
    }

    private class AppliedFont(
        val baseTypeface: Typeface?,
        val requestedVariations: String?,
        val effectiveTypeface: Typeface?,
        val effectiveVariations: String?
    )
}
