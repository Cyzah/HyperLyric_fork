package com.lidesheng.hyperlyric.common

/** Radius shared by the expanded card, its background and the edge progress track. */
internal object IslandExpandedMediaCornerRadiusPolicy {
    const val FOLLOW_SYSTEM = -1
    const val MAX_DP = 50

    fun resolveAnimatedRadiusPx(
        customRadiusPx: Float,
        systemRadiusPx: Float,
        currentHeightPx: Float,
        expandedHeightPx: Float,
        collapsedHeightPx: Float
    ): Float {
        if (!currentHeightPx.isFinite() || !expandedHeightPx.isFinite() ||
            !collapsedHeightPx.isFinite() || expandedHeightPx <= collapsedHeightPx ||
            currentHeightPx <= 0f) return systemRadiusPx
        val expandedFraction = ((currentHeightPx - collapsedHeightPx) /
            (expandedHeightPx - collapsedHeightPx)).coerceIn(0f, 1f)
        val nativeRadius = systemRadiusPx.coerceIn(0f, currentHeightPx / 2f)
        return (nativeRadius + (customRadiusPx - nativeRadius) * expandedFraction)
            .coerceIn(0f, currentHeightPx / 2f)
    }

    fun resolveRadiusPx(
        isExpandedMediaCard: Boolean,
        configuredDp: Int,
        density: Float,
        systemRadiusPx: Float
    ): Float {
        if (!isExpandedMediaCard || configuredDp < 0) return systemRadiusPx
        return configuredDp.coerceAtMost(MAX_DP) * density
    }
}
