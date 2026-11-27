/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.lidesheng.hyperlyric.lyric.view.line

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StableTextRunBatchTest {
    @Test
    fun adjacentUnitsExtendTheRunAndKeepTheFullInkBounds() {
        val run = StableTextRun()
        run.append(index = 2, x = 24f, nextAdvanceEnd = 36f, left = 23f, right = 35f)
        assertTrue(run.canAppend(index = 3, x = 36f))

        run.append(index = 3, x = 36f, nextAdvanceEnd = 48f, left = 35f, right = 49f)

        assertEquals(2, run.start)
        assertEquals(4, run.end)
        assertEquals(24f, run.drawX, 0f)
        assertEquals(23f, run.inkStart, 0f)
        assertEquals(49f, run.inkEnd, 0f)
    }

    @Test
    fun aSourceOrPositionGapRequiresANewRun() {
        val run = StableTextRun()
        run.append(index = 2, x = 24f, nextAdvanceEnd = 36f, left = 23f, right = 35f)
        assertFalse(run.canAppend(index = 4, x = 36f))
        assertFalse(run.canAppend(index = 3, x = 37f))

        run.clear()

        assertTrue(run.canAppend(index = 4, x = 37f))
    }

    @Test
    fun visibleHanUnitsWithZeroLiftCanShareOneRun() {
        assertTrue(isStableTextRunUnit('\u4E2D', 0f, 10f, 20f, 0f, 30f))
        assertTrue(isStableTextRunUnit('\u56FD', 0f, 20f, 30f, 0f, 30f))
    }

    @Test
    fun transformedUnitsStayOnThePerUnitPath() {
        assertFalse(isStableTextRunUnit('\u4E2D', 0.01f, 10f, 20f, 0f, 30f))
    }

    @Test
    fun partialClippingStaysOnThePerUnitPath() {
        assertFalse(isStableTextRunUnit('\u4E2D', 0f, 10f, 20f, 11f, 30f))
        assertFalse(isStableTextRunUnit('\u4E2D', 0f, 10f, 20f, 0f, 19f))
    }

    @Test
    fun shapingSensitiveCharactersStayOnThePerUnitPath() {
        assertFalse(isStableTextRunUnit('f', 0f, 10f, 20f, 0f, 30f))
        assertFalse(isStableTextRunUnit('\u0300', 0f, 10f, 20f, 0f, 30f))
        assertFalse(isStableTextRunUnit('\uD840', 0f, 10f, 20f, 0f, 30f))
    }
}
