/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.lidesheng.hyperlyric.lyric.view.line

internal class StableTextRun {
    var start = 0
        private set
    var end = 0
        private set
    var drawX = 0f
        private set
    var inkStart = 0f
        private set
    var inkEnd = 0f
        private set
    private var advanceEnd = 0f

    val isEmpty: Boolean get() = end == 0

    fun canAppend(index: Int, x: Float): Boolean = isEmpty || (index == end && x == advanceEnd)

    fun append(index: Int, x: Float, nextAdvanceEnd: Float, left: Float, right: Float) {
        if (isEmpty) {
            start = index
            drawX = x
            inkStart = left
            inkEnd = right
        } else {
            inkStart = minOf(inkStart, left)
            inkEnd = maxOf(inkEnd, right)
        }
        end = index + 1
        advanceEnd = nextAdvanceEnd
    }

    fun clear() {
        end = 0
    }
}

/** A stable unit can join a run when the system font draws it independently of its neighbors. */
internal fun isStableTextRunUnit(
    character: Char,
    liftY: Float,
    inkStart: Float,
    inkEnd: Float,
    clipStart: Float,
    clipEnd: Float
): Boolean = liftY == 0f && inkStart >= clipStart && inkEnd <= clipEnd &&
        (character in '\u3400'..'\u4DBF' || character in '\u4E00'..'\u9FFF')
