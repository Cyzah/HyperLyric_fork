package com.lidesheng.hyperlyric.common

import org.junit.Assert.assertEquals
import org.junit.Test

class IslandExpandedMediaCornerRadiusPolicyTest {
    @Test
    fun closingRadiusFollowsTheCurrentGeometryInsteadOfTheDestinationState() {
        assertEquals(12f, IslandExpandedMediaCornerRadiusPolicy.resolveAnimatedRadiusPx(12f, 30f, 180f, 180f, 40f), 0f)
        assertEquals(21f, IslandExpandedMediaCornerRadiusPolicy.resolveAnimatedRadiusPx(12f, 30f, 110f, 180f, 40f), 0f)
        assertEquals(20f, IslandExpandedMediaCornerRadiusPolicy.resolveAnimatedRadiusPx(12f, 30f, 40f, 180f, 40f), 0f)
    }

    @Test
    fun animatedSquareCornersAndOvershootStayWithinTheCurrentOutline() {
        assertEquals(0f, IslandExpandedMediaCornerRadiusPolicy.resolveAnimatedRadiusPx(0f, 30f, 180f, 180f, 40f), 0f)
        assertEquals(15f, IslandExpandedMediaCornerRadiusPolicy.resolveAnimatedRadiusPx(0f, 30f, 110f, 180f, 40f), 0f)
        assertEquals(20f, IslandExpandedMediaCornerRadiusPolicy.resolveAnimatedRadiusPx(0f, 30f, 40f, 180f, 40f), 0f)
        assertEquals(15f, IslandExpandedMediaCornerRadiusPolicy.resolveAnimatedRadiusPx(50f, 30f, 30f, 180f, 40f), 0f)
        assertEquals(50f, IslandExpandedMediaCornerRadiusPolicy.resolveAnimatedRadiusPx(50f, 30f, 190f, 180f, 40f), 0f)
    }

    @Test
    fun invalidAnimationGeometryKeepsTheNativeRadius() {
        assertEquals(30f, IslandExpandedMediaCornerRadiusPolicy.resolveAnimatedRadiusPx(12f, 30f, 100f, 40f, 40f), 0f)
        assertEquals(30f, IslandExpandedMediaCornerRadiusPolicy.resolveAnimatedRadiusPx(12f, 30f, Float.NaN, 180f, 40f), 0f)
    }

    @Test
    fun systemRadiusIsKeptOutsideExpandedMediaCards() {
        assertEquals(
            30f,
            IslandExpandedMediaCornerRadiusPolicy.resolveRadiusPx(
                isExpandedMediaCard = false,
                configuredDp = 12,
                density = 2f,
                systemRadiusPx = 30f
            )
        )
    }

    @Test
    fun configuredRadiusUsesDpOnlyForExpandedMediaCards() {
        assertEquals(
            24f,
            IslandExpandedMediaCornerRadiusPolicy.resolveRadiusPx(
                isExpandedMediaCard = true,
                configuredDp = 12,
                density = 2f,
                systemRadiusPx = 30f
            )
        )
    }

    @Test
    fun zeroRadiusIsCustomAndOutOfRangeValuesAreClamped() {
        assertEquals(
            0f,
            IslandExpandedMediaCornerRadiusPolicy.resolveRadiusPx(
                isExpandedMediaCard = true,
                configuredDp = 0,
                density = 2f,
                systemRadiusPx = 30f
            )
        )
        assertEquals(
            100f,
            IslandExpandedMediaCornerRadiusPolicy.resolveRadiusPx(
                isExpandedMediaCard = true,
                configuredDp = 80,
                density = 2f,
                systemRadiusPx = 30f
            )
        )
    }

    @Test
    fun systemSentinelKeepsNativeRadius() {
        assertEquals(
            30f,
            IslandExpandedMediaCornerRadiusPolicy.resolveRadiusPx(
                isExpandedMediaCard = true,
                configuredDp = IslandExpandedMediaCornerRadiusPolicy.FOLLOW_SYSTEM,
                density = 2f,
                systemRadiusPx = 30f
            )
        )
    }
}
