package com.lidesheng.hyperlyric.lyric.view

import android.app.Activity
import android.os.Looper
import com.lidesheng.hyperlyric.lyric.model.LyricLine
import com.lidesheng.hyperlyric.lyric.model.RichLyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class LyricWidthPreflightTest {
    @Test
    fun richViewReusesTheMeasuredModelAfterDeferringForLayout() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = RichLyricLineView(activity)
        activity.setContentView(view)
        assertTrue(view.isAttachedToWindow)
        val line = RichLyricLine(text = "Candidate")
        view.setLineWithCallbacks(line, onMainLineWillApply = { true })
        val candidate = ReflectionHelpers.getField<PreparedRichLyricLine>(view, "preparedCandidate")
        val measured = checkNotNull(candidate.mainFor(LyricLine(text = "Candidate")))
        assertEquals("", view.main.model.text)
        runNextFrame()
        assertSame(measured.model, view.main.model)
    }

    @Test
    fun spaceGateViewReassemblesChangedInputsBeforeDeferredCommit() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = SpaceGateRichLyricLineView(activity)
        activity.setContentView(view)
        assertTrue(view.isAttachedToWindow)
        val line = RichLyricLine(text = "Original", translation = "Translation")
        view.setLineWithCallbacks(line, onMainLineWillApply = { true })
        assertEquals("", view.main.model.text)
        line.text = "Updated"
        view.displayTranslation = false
        runNextFrame()
        assertEquals("Updated", view.main.model.text)
        assertEquals("", view.secondary.model.text)
    }

    private fun runNextFrame() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40))
    }
}
