package com.lidesheng.hyperlyric.lyric.view.line

import android.graphics.Typeface
import android.text.TextPaint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], manifest = Config.NONE)
class TextPaintXTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun repeatingAnAppliedFontKeepsTheDerivedTypeface() {
        val paint = TextPaintX()
        val base = variableTypeface()
        val axes = "'wght' 550"

        paint.applyFont(base, axes)
        val derived = paint.typeface
        assertNotSame(base, derived)
        assertEquals(axes, paint.fontVariationSettings)

        paint.applyFont(base, axes)

        assertSame(derived, paint.typeface)
    }

    @Test
    fun changingAxesOrBaseReplacesTheAppliedFont() {
        val paint = TextPaintX()
        val base = variableTypeface()
        paint.applyFont(base, "'wght' 550")
        val first = paint.typeface

        paint.applyFont(base, "'wght' 650")

        assertNotSame(first, paint.typeface)
        assertEquals("'wght' 650", paint.fontVariationSettings)

        paint.applyFont(Typeface.SERIF, null)

        assertSame(Typeface.SERIF, paint.typeface)
        assertNull(paint.fontVariationSettings)
    }

    @Test
    fun replacingOrResettingPaintRestoresTheRequestedFontOnNextApplication() {
        val paint = TextPaintX()
        val base = variableTypeface()
        val axes = "'wght' 550"
        paint.applyFont(base, axes)

        paint.typeface = Typeface.SERIF
        paint.applyFont(base, axes)

        assertNotSame(Typeface.SERIF, paint.typeface)
        assertEquals(axes, paint.fontVariationSettings)

        val copiedTypeface = paint.typeface
        paint.set(TextPaint().apply { typeface = copiedTypeface })
        assertSame(copiedTypeface, paint.typeface)
        assertNull(paint.fontVariationSettings)

        paint.applyFont(base, axes)

        assertEquals(axes, paint.fontVariationSettings)

        paint.reset()
        paint.applyFont(base, axes)

        assertEquals(axes, paint.fontVariationSettings)
    }

    private fun variableTypeface(): Typeface {
        val font = temp.newFile("NotoSansKhmer-VF.ttf")
        // Use Robolectric's bundled variable font so the weight axis is available on every host.
        requireNotNull(javaClass.getResourceAsStream("/fonts/NotoSansKhmer-VF.ttf")).use { input ->
            font.outputStream().use { output -> input.copyTo(output) }
        }
        return Typeface.Builder(font).build()
    }
}
