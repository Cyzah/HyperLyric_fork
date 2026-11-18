package com.lidesheng.hyperlyric.root.mediacard.island

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Outline
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.root.mediacard.MediaCardRuntimeConfig
import miui.systemui.dynamicisland.view.DynamicIslandExpandedView
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, qualifiers = "mdpi")
class IslandExpandedMediaCornerRadiusHookerTest {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences("expanded-media-corner-radius", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        MediaCardRuntimeConfig.load(prefs)
        IslandExpandedMediaCornerRadiusHooker.bindPreferences(prefs)
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
        MediaCardRuntimeConfig.load(prefs)
    }

    @Test
    fun expandedMediaContentIsEligible() {
        assertTrue(IslandExpandedMediaCornerRadiusHooker.isExpandedMediaCard(content()))
    }

    @Test
    fun bigIslandMediaContentIsExcluded() {
        assertFalse(
            IslandExpandedMediaCornerRadiusHooker.isExpandedMediaCard(content(state = BigIsland()))
        )
    }

    @Test
    fun expandedAppAndMiniWindowStatesAreExcludedForRealAndFakeContent() {
        for (state in listOf(AppExpanded(), MiniWindowExpanded(), SubAppExpanded(), SubMiniWindowExpanded())) {
            val owner = content(state = state)
            assertFalse(
                state.javaClass.simpleName,
                IslandExpandedMediaCornerRadiusHooker.isExpandedMediaCard(owner)
            )
            assertFalse(
                "Fake ${state.javaClass.simpleName}",
                IslandExpandedMediaCornerRadiusHooker.isExpandedMediaCard(FakeContent(context, owner))
            )
        }

        assertTrue(
            IslandExpandedMediaCornerRadiusHooker.isExpandedMediaCard(FakeContent(context, content()))
        )
    }

    @Test
    fun expandedNonMediaContentIsExcluded() {
        assertFalse(
            IslandExpandedMediaCornerRadiusHooker.isExpandedMediaCard(content(media = false))
        )
    }

    @Test
    fun reusedContentStopsBeingEligibleAfterItsStateChanges() {
        val content = content()
        assertTrue(IslandExpandedMediaCornerRadiusHooker.isExpandedMediaCard(content))

        content.currentState = BigIsland()

        assertFalse(IslandExpandedMediaCornerRadiusHooker.isExpandedMediaCard(content))
    }

    @Test
    fun reusedContentStopsBeingEligibleAfterMediaDataIsReplaced() {
        val content = content()
        assertTrue(IslandExpandedMediaCornerRadiusHooker.isExpandedMediaCard(content))

        content.currentData = data(media = false)

        assertFalse(IslandExpandedMediaCornerRadiusHooker.isExpandedMediaCard(content))
    }

    @Test
    fun fakeContentUsesItsRealOwnerAndTracksTheOwnersState() {
        val owner = content()
        val fake = FakeContent(context, owner)
        assertTrue(IslandExpandedMediaCornerRadiusHooker.isExpandedMediaCard(fake))

        owner.currentState = BigIsland()

        assertFalse(IslandExpandedMediaCornerRadiusHooker.isExpandedMediaCard(fake))
    }

    @Test
    fun outerBackgroundFindsItsDirectChildContentOwner() {
        val background = FrameLayout(context)
        val owner = content()
        background.addView(owner)
        assertTrue(IslandExpandedMediaCornerRadiusHooker.isExpandedMediaCard(background))

        owner.currentState = BigIsland()

        assertFalse(IslandExpandedMediaCornerRadiusHooker.isExpandedMediaCard(background))
    }

    @Test
    fun savingRadiusUpdatesAnExistingOutlineWithoutReloadingTheMediaSnapshot() {
        configureRadius(12)
        val view = content()
        val provider = IslandExpandedMediaCornerRadiusHooker.CornerOutlineProvider(
            object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, 300, 180, 18f)
                }
            }
        )
        provider.getOutline(view, Outline())
        prefs.edit().putInt(RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_CORNER_RADIUS_DP, 28).commit()
        shadowOf(Looper.getMainLooper()).idle()

        val outline = Outline()
        provider.getOutline(view, outline)
        assertEquals(28f, outline.radius, 0f)
        assertEquals(12, MediaCardRuntimeConfig.current.islandExpanded.cornerRadiusDp)
    }

    @Test
    fun preferenceChangeRefreshesExistingViewsAndTheirProgressRadius() {
        configureRadius(12)
        val background = InvalidatedBackground(context).apply { addView(content()) }
        IslandExpandedMediaCornerRadiusHooker.recordBackgroundRadius(background)
        background.invalidations = 0

        prefs.edit().putInt(RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_CORNER_RADIUS_DP, 28).commit()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(background.invalidations > 0)
        assertEquals(28f, IslandExpandedMediaCornerRadiusHooker.resolveRadiusPx(background, 30f), 0f)
        prefs.edit().putInt(RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_CORNER_RADIUS_DP, -1).commit()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(30f, IslandExpandedMediaCornerRadiusHooker.resolveRadiusPx(background, 30f), 0f)
    }

    @Test
    fun hotReloadAdoptsExistingProvidersAndReadsTheNewPreferences() {
        configureRadius(12)
        val background = FrameLayout(context)
        val native = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, 300, 180, 18f)
                outline.alpha = 0.4f
            }
        }
        background.outlineProvider = IslandExpandedMediaCornerRadiusHooker.wrapMediaBackground(native)
        background.outlineProvider.getOutline(background, Outline())
        val views = IslandExpandedMediaCornerRadiusHooker.prepareForHotReload()
        val nextPrefs = context.getSharedPreferences("next-corner-radius-generation", Context.MODE_PRIVATE)
        nextPrefs.edit().clear().putInt(RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_CORNER_RADIUS_DP, 28).commit()
        IslandExpandedMediaCornerRadiusHooker.bindPreferences(nextPrefs)
        IslandExpandedMediaCornerRadiusHooker.adoptViews(views)

        // Pending notifications from the old RemotePreferences must not refresh the new binding.
        prefs.edit().putInt(RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_CORNER_RADIUS_DP, 3).commit()
        shadowOf(Looper.getMainLooper()).idle()
        val outline = Outline()
        background.outlineProvider.getOutline(background, outline)
        assertEquals(28f, outline.radius, 0f)
        assertEquals(0.4f, outline.alpha, 0f)
        nextPrefs.edit().putInt(RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_CORNER_RADIUS_DP, -1).commit()
        shadowOf(Looper.getMainLooper()).idle()
        background.outlineProvider.getOutline(background, outline)
        assertEquals(18f, outline.radius, 0f)
    }

    @Test
    fun glowShaderReceivesTheCustomRadiusWhileNativeCoordinatesStayIntact() {
        configureRadius(12)
        val glow = DynamicIslandExpandedView(context)
        content().addView(glow)
        val geometry = arrayOf<Any?>(4f, 6f, 284f, 166f, 30f)

        val actual = IslandExpandedMediaCornerRadiusHooker.updateGlowArguments(glow, geometry)!!

        assertArrayEquals(arrayOf<Any?>(4f, 6f, 284f, 166f, 12f), actual)
        assertEquals(30f, geometry[4])
        prefs.edit().putInt(RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_CORNER_RADIUS_DP, 0).commit()
        assertEquals(0f, IslandExpandedMediaCornerRadiusHooker.updateGlowArguments(glow, geometry)!![4])
        prefs.edit().putInt(RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_CORNER_RADIUS_DP, -1).commit()
        assertNull(IslandExpandedMediaCornerRadiusHooker.updateGlowArguments(glow, geometry))
    }

    @Test
    fun closingAnimationKeepsProgressAndBothGlowSlotsOnTheAnimatedRadius() {
        configureRadius(12)
        val owner = content()
        val background = FrameLayout(context).apply { addView(owner) }
        val expandedGlow = DynamicIslandExpandedView(context).also(owner::addView)
        val compactGlow = miui.systemui.dynamicisland.view.DynamicIslandBigIslandView(context).also(owner::addView)
        assertEquals(12f, IslandExpandedMediaCornerRadiusHooker.recordBackgroundRadius(background)!!, 0f)

        // SystemUI commits the destination state before the first shrinking frame.
        owner.previousState = Expanded()
        owner.currentState = BigIsland()
        assertEquals(12f, IslandExpandedMediaCornerRadiusHooker.recordBackgroundRadius(background)!!, 0f)
        owner.animation.animating = true
        assertFalse(IslandExpandedMediaCornerRadiusHooker.isExpandedMediaCard(owner))
        for ((height, expectedRadius) in listOf(180f to 12f, 110f to 21f, 40f to 20f)) {
            owner.animation.clipHeight = height
            assertEquals(expectedRadius, IslandExpandedMediaCornerRadiusHooker.recordBackgroundRadius(background)!!, 0.001f)
            assertEquals(expectedRadius, IslandExpandedMediaCornerRadiusHooker.resolveRadiusPx(background, 30f), 0.001f)
            val args = arrayOf<Any?>(0f, 0f, 300f, height, minOf(30f, height / 2f))
            for (glow in listOf(expandedGlow, compactGlow)) {
                assertEquals(expectedRadius, IslandExpandedMediaCornerRadiusHooker.updateGlowArguments(glow, args)!![4] as Float, 0.001f)
            }
        }
        owner.animation.animating = false
        assertNull(IslandExpandedMediaCornerRadiusHooker.recordBackgroundRadius(background))
        assertEquals(30f, IslandExpandedMediaCornerRadiusHooker.resolveRadiusPx(background, 30f), 0f)
        assertNull(IslandExpandedMediaCornerRadiusHooker.updateGlowArguments(compactGlow, arrayOf(0f, 0f, 300f, 40f, 20f)))
    }

    @Test
    fun fakeSmallIslandTransitionUsesRealGeometryAndDropsOverridesWhenMediaChanges() {
        configureRadius(12)
        val owner = content(state = SmallIsland()).apply {
            previousState = Expanded()
            animation.animating = true
            animation.clipHeight = 110f
        }
        val fake = FakeContent(context, owner)
        assertEquals(21f, IslandExpandedMediaCornerRadiusHooker.recordBackgroundRadius(fake)!!, 0f)
        assertEquals(21f, IslandExpandedMediaCornerRadiusHooker.resolveRadiusPx(fake, 30f), 0f)

        owner.currentData = data(media = false)
        assertNull(IslandExpandedMediaCornerRadiusHooker.recordBackgroundRadius(fake))
        assertEquals(30f, IslandExpandedMediaCornerRadiusHooker.resolveRadiusPx(fake, 30f), 0f)
    }

    @Test
    fun collapseUsesNativeAnimationEndpointsInsteadOfTheMeasuredCardHeight() {
        configureRadius(12)
        val owner = content(state = BigIsland()).apply {
            previousState = Expanded()
            animation.animating = true
            animation.expandedHeight = 160f
            animation.clipHeight = 160f
        }
        assertEquals(12f, IslandExpandedMediaCornerRadiusHooker.recordBackgroundRadius(owner)!!, 0.001f)
        owner.animation.clipHeight = 100f
        assertEquals(21f, IslandExpandedMediaCornerRadiusHooker.recordBackgroundRadius(owner)!!, 0.001f)
    }

    @Test
    fun compactBounceDoesNotReactivateTheCompletedCollapse() {
        configureRadius(12)
        val owner = content(state = BigIsland()).apply {
            previousState = Expanded()
            animation.animating = true
            animation.clipHeight = 180f
        }
        assertEquals(12f, IslandExpandedMediaCornerRadiusHooker.recordBackgroundRadius(owner)!!, 0f)
        owner.animation.clipHeight = 40f
        IslandExpandedMediaCornerRadiusHooker.recordBackgroundRadius(owner)
        owner.animation.animating = false
        assertNull(IslandExpandedMediaCornerRadiusHooker.recordBackgroundRadius(owner))

        owner.animation.animating = true
        owner.animation.clipHeight = 44f
        assertNull(IslandExpandedMediaCornerRadiusHooker.recordBackgroundRadius(owner))

        owner.currentState = Expanded()
        owner.animation.clipHeight = 180f
        assertEquals(12f, IslandExpandedMediaCornerRadiusHooker.recordBackgroundRadius(owner)!!, 0f)
        owner.previousState = owner.currentState
        owner.currentState = BigIsland()
        assertEquals(12f, IslandExpandedMediaCornerRadiusHooker.recordBackgroundRadius(owner)!!, 0f)
    }

    @Test
    fun repeatedBigIslandStateUpdatesCannotDropTheVisibleClosingCardRadius() {
        configureRadius(50)
        val owner = content()
        assertEquals(50f, IslandExpandedMediaCornerRadiusHooker.recordBackgroundRadius(owner)!!, 0f)
        owner.currentState = BigIsland()
        owner.previousState = BigIsland() // Observed on HyperOS 4 during the closing animation.
        owner.animation.animating = true
        assertEquals(50f, IslandExpandedMediaCornerRadiusHooker.recordBackgroundRadius(owner)!!, 0f)
        owner.animation.clipHeight = 110f
        assertEquals(40f, IslandExpandedMediaCornerRadiusHooker.recordBackgroundRadius(owner)!!, 0f)
        owner.animation.clipHeight = 40f
        owner.animation.animating = false
        assertNull(IslandExpandedMediaCornerRadiusHooker.recordBackgroundRadius(owner))
        owner.currentState = BigIsland()
        owner.animation.animating = true
        owner.animation.clipHeight = 44f
        assertNull(IslandExpandedMediaCornerRadiusHooker.recordBackgroundRadius(owner))
    }

    @Test
    fun nativeRendererGeometryOverridesStaleDelegateFieldsWithoutBlendingGlowTwice() {
        configureRadius(50)
        val owner = content()
        val glow = DynamicIslandExpandedView(context).also(owner::addView)
        IslandExpandedMediaCornerRadiusHooker.recordBackgroundRadius(owner)
        owner.currentState = BigIsland()
        owner.previousState = BigIsland()
        owner.animation.animating = true
        owner.animation.clipHeight = 40f // The delegate can lag the render payload on HyperOS 4.

        assertEquals(50f, IslandExpandedMediaCornerRadiusHooker.resolveNativeRadius(owner, 180f, 30f)!!, 0f)
        assertEquals(50f, IslandExpandedMediaCornerRadiusHooker.recordBackgroundRadius(owner)!!, 0f)
        val middleRadius = IslandExpandedMediaCornerRadiusHooker.resolveNativeRadius(owner, 110f, 30f)!!
        assertEquals(40f, middleRadius, 0f)
        assertEquals(40f, IslandExpandedMediaCornerRadiusHooker.updateGlowArguments(glow, arrayOf(0f, 0f, 300f, 110f, middleRadius))!![4] as Float, 0f)

        prefs.edit().putInt(RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_CORNER_RADIUS_DP, -1).commit()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(30f, glow.lastNativeGeometry!![4], 0f)
    }

    @Test
    fun liveRadiusChangeRefreshesTheNativeForegroundOncePerOwner() {
        configureRadius(12)
        val owner = content()
        val background = FrameLayout(context).apply { addView(owner) }
        val glow = DynamicIslandExpandedView(context).also(owner::addView)
        IslandExpandedMediaCornerRadiusHooker.recordBackgroundRadius(background)
        IslandExpandedMediaCornerRadiusHooker.updateGlowArguments(glow, arrayOf(0f, 0f, 300f, 180f, 30f))
        shadowOf(Looper.getMainLooper()).idle()
        owner.animation.containerUpdates = 0

        prefs.edit().putInt(RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_CORNER_RADIUS_DP, 28).commit()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, owner.animation.containerUpdates)
    }

    @Test
    fun glowRadiusClampsToAnimationBoundsAndExcludesOtherIslandStates() {
        configureRadius(50)
        val owner = content()
        val glow = DynamicIslandExpandedView(context)
        owner.addView(glow)
        val geometry = arrayOf<Any?>(4f, 6f, 44f, 86f, 30f)
        assertEquals(20f, IslandExpandedMediaCornerRadiusHooker.updateGlowArguments(glow, geometry)!![4])
        owner.currentState = BigIsland()
        owner.animation.clipHeight = 40f
        assertNull(IslandExpandedMediaCornerRadiusHooker.updateGlowArguments(glow, geometry))
        owner.currentState = Expanded()
        owner.animation.clipHeight = 180f
        owner.currentData = data(media = false)
        assertNull(IslandExpandedMediaCornerRadiusHooker.updateGlowArguments(glow, geometry))
        assertNull(IslandExpandedMediaCornerRadiusHooker.updateGlowArguments(glow, arrayOf(0f, 0f, Float.NaN, 20f, 30f)))
        assertNull(IslandExpandedMediaCornerRadiusHooker.updateGlowArguments(glow, arrayOf(40f, 0f, 10f, 20f, 30f)))
    }

    @Test
    fun liveRefreshReplaysTheNativeGlowRadiusSoFollowSystemCanRestoreIt() {
        configureRadius(12)
        val glow = DynamicIslandExpandedView(context)
        content().addView(glow)
        IslandExpandedMediaCornerRadiusHooker.updateGlowArguments(glow, arrayOf(4f, 6f, 284f, 166f, 30f))

        prefs.edit().putInt(RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_CORNER_RADIUS_DP, -1).commit()
        shadowOf(Looper.getMainLooper()).idle()

        assertArrayEquals(floatArrayOf(4f, 6f, 284f, 166f, 30f), glow.lastNativeGeometry, 0f)
    }

    @Test
    fun progressRadiusMatchesTheRecordedDrawAfterConfigurationReload() {
        val owner = content()
        val background = FrameLayout(context).apply { addView(owner) }
        val radiusPx = 12f * context.resources.displayMetrics.density
        configureRadius(12)
        assertEquals(
            radiusPx,
            IslandExpandedMediaCornerRadiusHooker.recordBackgroundRadius(background)!!,
            0f
        )

        configureRadius(-1)

        assertEquals(
            radiusPx,
            IslandExpandedMediaCornerRadiusHooker.resolveRadiusPx(background, 30f),
            0f
        )

        owner.currentState = BigIsland()
        assertNull(IslandExpandedMediaCornerRadiusHooker.recordBackgroundRadius(background))
        assertEquals(
            30f,
            IslandExpandedMediaCornerRadiusHooker.resolveRadiusPx(background, 30f),
            0f
        )
    }

    @Test
    fun recordedSystemRadiusPreventsPendingPreferenceFromChangingTheCurrentDraw() {
        val background = FrameLayout(context).apply { addView(content()) }
        assertNull(IslandExpandedMediaCornerRadiusHooker.recordBackgroundRadius(background))

        configureRadius(20)

        assertEquals(
            30f,
            IslandExpandedMediaCornerRadiusHooker.resolveRadiusPx(background, 30f),
            0f
        )

        val radiusPx = 20f * context.resources.displayMetrics.density
        assertEquals(
            radiusPx,
            IslandExpandedMediaCornerRadiusHooker.recordBackgroundRadius(background)!!,
            0f
        )
        assertEquals(
            radiusPx,
            IslandExpandedMediaCornerRadiusHooker.resolveRadiusPx(background, 30f),
            0f
        )
    }

    @Test
    fun customOutlinePreservesNativeBoundsAndAlpha() {
        configureRadius(12)
        val outline = outlineFor(content())

        assertNativeBounds(outline)
        assertEquals(12f * context.resources.displayMetrics.density, outline.radius, 0.001f)
        assertEquals(0.4f, outline.alpha, 0.001f)
    }

    @Test
    fun followSystemPreservesNativeRadius() {
        configureRadius(-1)
        val outline = outlineFor(content())

        assertNativeBounds(outline)
        assertEquals(18f, outline.radius, 0f)
        assertEquals(0.4f, outline.alpha, 0f)
    }

    @Test
    fun zeroRadiusProducesSquareCorners() {
        configureRadius(0)
        val outline = outlineFor(content())

        assertNativeBounds(outline)
        assertEquals(0f, outline.radius, 0f)
    }

    @Test
    fun persistedRadiusAboveMaximumIsClamped() {
        configureRadius(500)
        val outline = outlineFor(content())

        assertNativeBounds(outline)
        assertEquals(50f * context.resources.displayMetrics.density, outline.radius, 0.001f)
    }

    @Test
    fun radiusIsLimitedByTheCurrentNativeOutlineBounds() {
        configureRadius(50)
        val original = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(10, 20, 90, 60, 6f)
                outline.alpha = 0.6f
            }
        }
        val outline = Outline()

        IslandExpandedMediaCornerRadiusHooker.CornerOutlineProvider(original)
            .getOutline(content(), outline)

        val rect = Rect()
        assertTrue(outline.getRect(rect))
        assertEquals(Rect(10, 20, 90, 60), rect)
        assertEquals(20f, outline.radius, 0f)
        assertEquals(0.6f, outline.alpha, 0f)
    }

    @Test
    fun compactAndNonMediaCardsKeepTheirNativeOutline() {
        configureRadius(12)

        for (content in listOf(content(state = BigIsland()), content(media = false))) {
            val outline = outlineFor(content)
            assertNativeBounds(outline)
            assertEquals(18f, outline.radius, 0f)
            assertEquals(0.4f, outline.alpha, 0f)
        }
    }

    @Test
    fun descendantBackgroundUsesItsExpandedOwner() {
        configureRadius(12)
        val owner = content()
        val background = FrameLayout(context)
        owner.addView(background)

        val outline = outlineFor(background)

        assertNativeBounds(outline)
        assertEquals(12f * context.resources.displayMetrics.density, outline.radius, 0.001f)
    }

    @Test
    fun detachedKnownMediaBackgroundCanApplyRadius() {
        configureRadius(12)

        val outline = outlineFor(FrameLayout(context), mediaBackground = true)

        assertNativeBounds(outline)
        assertEquals(12f * context.resources.displayMetrics.density, outline.radius, 0.001f)
    }

    @Test
    fun nativeProviderSideEffectsRunOnceBeforeRadiusIsApplied() {
        configureRadius(12)
        var nativeCalls = 0
        val original = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                nativeCalls++
                view.translationX = 37f
                outline.setRoundRect(4, 6, 284, 166, 18f)
            }
        }
        val view = content()
        val outline = Outline()

        IslandExpandedMediaCornerRadiusHooker.CornerOutlineProvider(original)
            .getOutline(view, outline)

        assertEquals(1, nativeCalls)
        assertEquals(37f, view.translationX, 0f)
        assertEquals(12f * context.resources.displayMetrics.density, outline.radius, 0.001f)
    }

    @Test
    fun nonRectangularNativeOutlineIsPreserved() {
        configureRadius(12)
        val provider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setPath(Path().apply {
                    moveTo(0f, 0f)
                    lineTo(100f, 0f)
                    lineTo(50f, 60f)
                    close()
                })
                outline.alpha = 0.25f
            }
        }
        val outline = Outline()

        IslandExpandedMediaCornerRadiusHooker.CornerOutlineProvider(provider)
            .getOutline(content(), outline)

        assertFalse(outline.isEmpty)
        assertFalse(outline.getRect(Rect()))
        assertEquals(0.25f, outline.alpha, 0f)
    }

    @Test
    fun temporaryRadiusRestoresUniformCornersWithoutSchedulingAnotherRedraw() {
        val drawable = GradientDrawable().apply { cornerRadius = 6f }
        val callback = RecordingDrawableCallback()
        drawable.callback = callback
        val result = Any()

        val actualResult = IslandExpandedMediaCornerRadiusHooker.drawWithRadius(drawable, 24f) {
            assertEquals(24f, drawable.cornerRadius, 0f)
            assertSame(callback, drawable.callback)
            result
        }

        assertSame(result, actualResult)
        assertEquals(6f, drawable.cornerRadius, 0f)
        assertSame(callback, drawable.callback)
        assertEquals(0, callback.invalidations)
        assertEquals(0, callback.schedules)
    }

    @Test
    fun temporaryRadiusRestoresIndividualCornersAndKeepsNativeDrawingCallbacks() {
        val radii = floatArrayOf(2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f)
        val drawable = GradientDrawable().apply { cornerRadii = radii }
        val callback = RecordingDrawableCallback()
        drawable.callback = callback

        IslandExpandedMediaCornerRadiusHooker.drawWithRadius(drawable, 24f) {
            assertEquals(24f, drawable.cornerRadius, 0f)
            assertSame(callback, drawable.callback)
            drawable.invalidateSelf()
        }

        assertArrayEquals(radii, drawable.cornerRadii, 0f)
        assertEquals(0f, drawable.cornerRadius, 0f)
        assertSame(callback, drawable.callback)
        assertEquals(1, callback.invalidations)
        assertEquals(0, callback.schedules)
    }

    @Test
    fun failingDrawRestoresIndividualCornersAndCallbackWithoutAnotherRedraw() {
        val radii = floatArrayOf(2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f)
        val drawable = GradientDrawable().apply { cornerRadii = radii }
        val callback = RecordingDrawableCallback()
        drawable.callback = callback
        val failure = IllegalStateException("native draw failed")

        val actualFailure = assertThrows(IllegalStateException::class.java) {
            IslandExpandedMediaCornerRadiusHooker.drawWithRadius(drawable, 24f) {
                assertEquals(24f, drawable.cornerRadius, 0f)
                assertSame(callback, drawable.callback)
                throw failure
            }
        }

        assertSame(failure, actualFailure)
        assertArrayEquals(radii, drawable.cornerRadii, 0f)
        assertEquals(0f, drawable.cornerRadius, 0f)
        assertSame(callback, drawable.callback)
        assertEquals(0, callback.invalidations)
        assertEquals(0, callback.schedules)
    }

    private fun configureRadius(radiusDp: Int) {
        prefs.edit()
            .putInt(RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_CORNER_RADIUS_DP, radiusDp)
            .commit()
        MediaCardRuntimeConfig.load(prefs)
    }

    private fun outlineFor(view: View, mediaBackground: Boolean = false): Outline {
        val original = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(4, 6, 284, 166, 18f)
                outline.alpha = 0.4f
            }
        }
        return Outline().also {
            IslandExpandedMediaCornerRadiusHooker.CornerOutlineProvider(original, mediaBackground)
                .getOutline(view, it)
        }
    }

    private fun assertNativeBounds(outline: Outline) {
        val rect = Rect()
        assertTrue(outline.getRect(rect))
        assertEquals(Rect(4, 6, 284, 166), rect)
    }

    private fun content(state: Any = Expanded(), media: Boolean = true) =
        Content(context, state, data(media)).apply { layout(0, 0, 300, 180) }

    private fun data(media: Boolean) = FakeData(Bundle().apply {
        if (media) {
            putParcelable(
                "miui.pending.intent",
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent("test.expanded.media"),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
        }
    })

    class Content(
        context: Context,
        var currentState: Any,
        var currentData: FakeData
    ) : FrameLayout(context) {
        var previousState: Any? = null
        val animation = AnimationGeometry()
        fun getCurrentIslandData(): FakeData = currentData
        fun getState(): Any = currentState
        fun getLastState(): Any? = previousState
        fun getAnimatorDelegate(): AnimationGeometry = animation
        fun getExpandedViewHeight(): Int = 180
        fun getIslandViewHeight(): Int = 40
    }

    class AnimationGeometry {
        var animating = false
        var containerUpdates = 0
        var expandedHeight = 180f
        private val containerClipTopProgress = 0f
        private var containerClipBottomProgress = 180f
        var clipHeight: Float
            get() = containerClipBottomProgress
            set(value) { containerClipBottomProgress = value }
        fun isAnimating(): Boolean = animating
        fun getExpandedTop(): Float = 0f
        fun getExpandedBottom(): Float = expandedHeight
        fun getBigIslandTop(): Float = 0f
        fun getBigIslandBottom(): Float = 40f
        fun getSmallIslandTop(): Float = 0f
        fun getSmallIslandBottom(): Float = 40f
        fun containerScheduleUpdate() { containerUpdates++ }
    }

    class FakeContent(context: Context, private val real: View) : FrameLayout(context) {
        fun getRealView(): View = real
    }

    class FakeData(private val bundle: Bundle) {
        fun getExtras(): Bundle = bundle
    }

    class Expanded
    class BigIsland
    class SmallIsland
    class AppExpanded
    class MiniWindowExpanded
    class SubAppExpanded
    class SubMiniWindowExpanded

    class InvalidatedBackground(context: Context) : FrameLayout(context) {
        var invalidations = 0
        override fun invalidate() {
            invalidations++
            super.invalidate()
        }
    }

    private class RecordingDrawableCallback : Drawable.Callback {
        var invalidations = 0
        var schedules = 0

        override fun invalidateDrawable(who: Drawable) {
            invalidations++
        }

        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
            schedules++
        }

        override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
    }
}
