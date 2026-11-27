package com.lidesheng.hyperlyric.root.island.content

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.common.media.MediaMetadataHelper
import com.lidesheng.hyperlyric.lyric.view.RichLyricLineView
import com.lidesheng.hyperlyric.root.island.config.IslandSlotRuntimeConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class IslandSlotStyleAssemblerTest {
    private val context = RuntimeEnvironment.getApplication()
    private val prefs = FontTrackingPreferences(
        context.getSharedPreferences("island_style", Context.MODE_PRIVATE)
    )
    private val view = RichLyricLineView(context)
    private val media = MediaMetadataHelper.MediaInfo(title = "First", artist = "Artist", album = "Album")

    @Test
    fun newSongMetadataReusesTheAppliedStyle() {
        val config = IslandSlotRuntimeConfig.from(prefs)
        configure(config)
        val reads = prefs.fontPathReads

        configure(config, media.copy(title = "Second", artist = "Other artist", album = "Other album"))

        assertEquals(reads, prefs.fontPathReads)
    }

    @Test
    fun paletteContrastChangesColorWithoutResolvingTheFontAgain() {
        prefs.edit()
            .putInt(RootConstants.KEY_HOOK_TEXT_COLOR_STYLE, RootConstants.TEXT_COLOR_STYLE_COVER_COLOR)
            .commit()
        val config = IslandSlotRuntimeConfig.from(prefs)
        configure(config)
        assertEquals(Color.WHITE, IslandSlotStyleAssembler.primaryTextColor(view))
        val reads = prefs.fontPathReads

        configure(config, darkBackground = false)

        assertEquals(Color.BLACK, IslandSlotStyleAssembler.primaryTextColor(view))
        assertEquals(reads, prefs.fontPathReads)
    }

    @Test
    fun fontSizePreferenceChangesTheAppliedStyle() {
        configure(IslandSlotRuntimeConfig.from(prefs))
        val initialTextSize = view.main.textSize
        prefs.edit().putInt(RootConstants.KEY_HOOK_TEXT_SIZE, 32).commit()

        configure(IslandSlotRuntimeConfig.from(prefs))

        assertTrue(view.main.textSize > initialTextSize)
    }

    private fun configure(
        config: IslandSlotRuntimeConfig,
        mediaInfo: MediaMetadataHelper.MediaInfo = media,
        darkBackground: Boolean = true
    ) {
        IslandSlotStyleAssembler.configureView(
            view = view,
            prefs = prefs,
            config = config,
            mode = RootConstants.ISLAND_CONTENT_MODE_LYRIC,
            mediaInfo = mediaInfo,
            force = false,
            coverPaletteBackgroundIsDark = darkBackground
        )
    }

    private class FontTrackingPreferences(
        private val preferences: SharedPreferences
    ) : SharedPreferences by preferences {
        var fontPathReads = 0
            private set

        override fun getString(key: String?, defValue: String?): String? {
            if (key == RootConstants.KEY_HOOK_CUSTOM_FONT_PATH) fontPathReads++
            return preferences.getString(key, defValue)
        }
    }
}
