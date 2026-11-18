package com.lidesheng.hyperlyric.root.mediacard.island

import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import com.lidesheng.hyperlyric.common.IslandExpandedMediaCornerRadiusPolicy
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.root.island.host.IslandProbeUtils
import com.lidesheng.hyperlyric.root.managedHook
import com.lidesheng.hyperlyric.root.mediacard.MediaCardRuntimeConfig
import com.lidesheng.hyperlyric.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/** Adapts HyperIsland's delegating outline approach to expanded media cards only. */
internal object IslandExpandedMediaCornerRadiusHooker {
    private const val TAG = "IslandMediaCornerRadius"
    private const val ISLAND_PACKAGE = "miui.systemui.dynamicisland."
    private const val BACKGROUND_CLASS = "${ISLAND_PACKAGE}DynamicIslandBackgroundView"
    private const val APPLIED_RADIUS_TAG = 0x7e485952
    private const val GLOW_GEOMETRY_TAG = 0x7e485953
    private const val SETTLED_COLLAPSE_TAG = 0x7e485954
    private const val VISIBLE_EXPANSION_TAG = 0x7e485955
    private const val RENDERED_GEOMETRY_TAG = 0x7e485956
    private const val PROPERTY_UPDATER_CLASS = "${ISLAND_PACKAGE}anim.ui.animator.IslandPropertyUpdater"
    private const val GLOW_VIEW_CLASS = "${ISLAND_PACKAGE}view.DynamicGlowEffectView"
    private const val EXPANDED_VIEW_CLASS = "${ISLAND_PACKAGE}view.DynamicIslandExpandedView"
    private const val BIG_VIEW_CLASS = "${ISLAND_PACKAGE}view.DynamicIslandBigIslandView"
    const val CAPABILITY_PREFIX = "media.island.corner_radius."
    private var outlineHookInstalled = false
    private var preferences: SharedPreferences? = null
    private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var retired = false
    private val observedViews = WeakHashMap<View, Boolean>()
    private val hookedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )

    // Weak values prevent Method -> declaring Class from retaining a plugin class loader.
    private val accessorCache = WeakHashMap<Class<*>, WeakReference<Accessors>>()

    fun hook(module: XposedModule, prefs: SharedPreferences) {
        bindPreferences(prefs)
        if (outlineHookInstalled) return
        runCatching {
            module.managedHook(
                View::class.java.getDeclaredMethod("setOutlineProvider", ViewOutlineProvider::class.java),
                "media.island.corner_radius.outline",
                object : Hooker {
                    override fun intercept(chain: Chain): Any? {
                        val view = chain.thisObject as? View ?: return chain.proceed()
                        val provider = chain.args.firstOrNull() as? ViewOutlineProvider
                            ?: return chain.proceed()
                        if (provider is CornerOutlineProvider) {
                            observe(view, provider.mediaBackground)
                            return chain.proceed()
                        }
                        val previous = unwrapProvider(provider)
                        if (!previous.mediaBackground && !isOutlineCandidate(view)) {
                            return chain.proceed()
                        }
                        observe(view, previous.mediaBackground)
                        return chain.proceed(arrayOf(CornerOutlineProvider(previous.original, previous.mediaBackground)))
                    }
                }
            )
            outlineHookInstalled = true
        }.onFailure { HookLogger.e(TAG, "安装媒体卡片圆角 Outline Hook 失败", it) }
    }

    internal fun bindPreferences(prefs: SharedPreferences) {
        preferenceListener?.let { listener -> preferences?.unregisterOnSharedPreferenceChangeListener(listener) }
        preferences = prefs
        retired = false
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_CORNER_RADIUS_DP) {
                Handler(Looper.getMainLooper()).post {
                    if (!retired && preferences === prefs) refreshObservedViews()
                }
            }
        }
        preferenceListener = listener
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    /** Transfer only framework Views; providers and listeners belong to their hook generation. */
    fun prepareForHotReload(): List<View> {
        retired = true
        preferenceListener?.let { listener -> preferences?.unregisterOnSharedPreferenceChangeListener(listener) }
        preferenceListener = null
        preferences = null
        outlineHookInstalled = false
        hookedClassLoaders.clear()
        synchronized(accessorCache) { accessorCache.clear() }
        return synchronized(observedViews) { observedViews.keys.toList().also { observedViews.clear() } }
    }

    fun adoptViews(views: Iterable<View>) {
        val visited = Collections.newSetFromMap(java.util.IdentityHashMap<View, Boolean>())
        fun adopt(view: View) {
            if (!visited.add(view)) return
            val provider = view.outlineProvider
            if (provider != null) {
                val previous = unwrapProvider(provider)
                if (previous.mediaBackground || isOutlineCandidate(view)) {
                    view.outlineProvider = CornerOutlineProvider(previous.original, previous.mediaBackground)
                    observe(view, previous.mediaBackground)
                }
            }
            if (view.getTag(GLOW_GEOMETRY_TAG) is FloatArray || view.getTag(APPLIED_RADIUS_TAG) is Float) {
                observe(view, mediaBackground = false)
            }
            (view as? ViewGroup)?.let { group ->
                for (index in 0 until group.childCount) adopt(group.getChildAt(index))
            }
        }
        views.forEach { view -> adopt(view.rootView); adopt(view) }
        refreshObservedViews()
    }

    private fun observe(view: View, mediaBackground: Boolean) {
        synchronized(observedViews) {
            observedViews[view] = mediaBackground || observedViews[view] == true
        }
    }

    private fun refreshObservedViews() {
        val views = synchronized(observedViews) { observedViews.keys.toList() }
        val owners = Collections.newSetFromMap(java.util.IdentityHashMap<View, Boolean>())
        views.forEach { view ->
            findContentOwner(view)?.let(owners::add)
            view.setTag(APPLIED_RADIUS_TAG, null)
            view.invalidateOutline()
            val geometry = view.getTag(GLOW_GEOMETRY_TAG) as? FloatArray
            if (geometry != null) {
                runCatching {
                    val args = geometry.map { it as Any? }.toTypedArray()
                    // Replay native geometry through the hook, retaining the native radius so
                    // switching back to Follow system restores it rather than our last override.
                    accessors(view.javaClass).glowPosition?.invoke(view, *args)
                }.onFailure { HookLogger.w(TAG, "刷新媒体卡片描边圆角失败", it) }
            }
            view.invalidate()
        }
        owners.forEach { owner ->
            if (IslandProbeUtils.isMediaIsland(accessors(owner.javaClass).data.read(owner))) {
                runCatching {
                    val animator = accessors(owner.javaClass).animator.read(owner) ?: return@runCatching
                    // OS4's foreground glow receives its geometry in updateContainer, outside
                    // the Outline callback. Refresh it once per owner even on a stationary card.
                    accessors(animator.javaClass).containerUpdate?.invoke(animator)
                }.onFailure { HookLogger.w(TAG, "刷新原生媒体卡片轮廓失败", it) }
            }
        }
    }

    private fun unwrapProvider(provider: ViewOutlineProvider): ProviderParts {
        var original = provider
        var mediaBackground = false
        // Retained media binders can still construct providers from the previous module loader.
        // Unwrap only our own exact class name; keep native and other modules' providers intact.
        repeat(16) {
            if (original.javaClass.name != CornerOutlineProvider::class.java.name) {
                return ProviderParts(original, mediaBackground)
            }
            val wrapper = original
            val next = runCatching {
                val type = wrapper.javaClass
                mediaBackground = mediaBackground || type.getDeclaredField("mediaBackground")
                    .apply { isAccessible = true }.getBoolean(wrapper)
                type.getDeclaredField("original").apply { isAccessible = true }.get(wrapper) as? ViewOutlineProvider
            }.getOrNull() ?: return ProviderParts(original, mediaBackground)
            original = next
        }
        return ProviderParts(original, mediaBackground)
    }

    private data class ProviderParts(val original: ViewOutlineProvider, val mediaBackground: Boolean)

    fun hookPlugin(module: XposedModule, classLoader: ClassLoader) {
        if (!hookedClassLoaders.add(classLoader)) return
        installNativeRadiusHook(module, classLoader)
        installGlowHook(module, classLoader)
        runCatching {
            val backgroundClass = classLoader.loadClass(BACKGROUND_CLASS)
            val draw = backgroundClass.getDeclaredMethod("onDraw", Canvas::class.java)
                .apply { isAccessible = true }
            module.managedHook(draw, "media.island.corner_radius.background", object : Hooker {
                override fun intercept(chain: Chain): Any? {
                    val view = chain.thisObject as? View ?: return chain.proceed()
                    val radius = recordBackgroundRadius(view) ?: return chain.proceed()
                    val drawable = accessors(view.javaClass).drawable.read(view)
                        as? GradientDrawable ?: return chain.proceed()
                    if (drawable.shape != GradientDrawable.RECTANGLE) return chain.proceed()
                    // The outer drawable is reused by collapsed islands and other notifications.
                    // Keep its animated bounds, colors and alpha, and restore its shape after draw.
                    return drawWithRadius(drawable, radius) { chain.proceed() }
                }
            })
        }.onFailure {
            hookedClassLoaders.remove(classLoader)
            HookLogger.w(TAG, "此超级岛版本不支持媒体卡片背景圆角", it)
        }
    }

    private fun installNativeRadiusHook(module: XposedModule, classLoader: ClassLoader) {
        runCatching {
            // HyperOS 4 funnels Outline, native foreground glow and both outer glow slots
            // through this function. Its arguments are the current render payload, not a
            // delegate field which can still describe the previous frame.
            val updater = classLoader.loadClass(PROPERTY_UPDATER_CLASS)
            val radius = updater.getDeclaredMethod(
                "containerClipRadius", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType
            ).apply { isAccessible = true }
            val ownerField = updater.getDeclaredField("view").apply { isAccessible = true }
            module.managedHook(radius, "${CAPABILITY_PREFIX}native_radius", object : Hooker {
                override fun intercept(chain: Chain): Any? {
                    val result = chain.proceed()
                    val nativeRadius = result as? Float ?: return result
                    val owner = ownerField.get(chain.thisObject) as? View ?: return nativeRadius
                    val top = (chain.args.getOrNull(0) as? Number)?.toFloat() ?: return nativeRadius
                    val bottom = (chain.args.getOrNull(1) as? Number)?.toFloat() ?: return nativeRadius
                    return resolveNativeRadius(owner, bottom - top, nativeRadius) ?: nativeRadius
                }
            })
        }.onFailure { error ->
            // Older plugins have no property updater; Outline and glow setters remain supported.
            if (error !is ClassNotFoundException) HookLogger.w(TAG, "安装原生圆角共享入口失败", error)
        }
    }

    private fun installGlowHook(module: XposedModule, classLoader: ClassLoader) {
        runCatching {
            val glowClass = classLoader.loadClass(GLOW_VIEW_CLASS)
            val setter = glowClass.declaredMethods.single {
                it.name.substringBefore('$') == "setGlowEffectPosition" && it.parameterCount == 5 &&
                    it.parameterTypes.all { type -> type == Float::class.javaPrimitiveType }
            }.apply { isAccessible = true }
            module.managedHook(setter, "${CAPABILITY_PREFIX}glow", object : Hooker {
                override fun intercept(chain: Chain): Any? {
                    val view = chain.thisObject as? View ?: return chain.proceed()
                    val args = updateGlowArguments(view, chain.args.toTypedArray())
                    return if (args == null) chain.proceed() else chain.proceed(args)
                }
            })
        }.onFailure { HookLogger.w(TAG, "此超级岛版本不支持描边圆角同步", it) }
    }

    internal fun updateGlowArguments(view: View, args: Array<Any?>): Array<Any?>? {
        if (generateSequence(view.javaClass as Class<*>?) { it.superclass }
                .none { it.name == EXPANDED_VIEW_CLASS || it.name == BIG_VIEW_CLASS }) return null
        if (args.size != 5) return null
        val geometry = args.map { (it as? Number)?.toFloat() ?: return null }.toFloatArray()
        if (geometry.any { !it.isFinite() }) return null
        val width = geometry[2] - geometry[0]
        val height = geometry[3] - geometry[1]
        if (width < 0f || height < 0f) return null
        val owner = findContentOwner(view)
        val rendered = owner?.getTag(RENDERED_GEOMETRY_TAG) as? FloatArray
        if (rendered != null && kotlin.math.abs(rendered[0] - height) <= 0.5f) {
            // The native radius hook already changed args[4]. Keep the upstream native value
            // for Follow system and avoid interpolating the override for a second time.
            geometry[4] = rendered[1]
        }
        view.setTag(GLOW_GEOMETRY_TAG, geometry)
        observe(view, mediaBackground = false)
        val radius = configuredRadiusPx(view, systemRadiusPx = geometry[4]) ?: return null
        return args.copyOf().also { it[4] = radius.coerceAtMost(minOf(width, height) / 2f) }
    }

    fun resolveRadiusPx(view: View, systemRadiusPx: Float): Float {
        if (radiusTarget(view) == null) return systemRadiusPx
        // Use the background's decision for this draw; preference refresh and hot-reload
        // adoption invalidate the tag before the next frame.
        val appliedRadius = view.getTag(APPLIED_RADIUS_TAG) as? Float
        if (appliedRadius != null) return if (appliedRadius >= 0f) appliedRadius else systemRadiusPx
        return configuredRadiusPx(view, systemRadiusPx = systemRadiusPx) ?: systemRadiusPx
    }

    internal fun recordBackgroundRadius(view: View): Float? {
        observe(view, mediaBackground = false)
        val radius = configuredRadiusPx(view)
        view.setTag(APPLIED_RADIUS_TAG, radius ?: -1f)
        return radius
    }

    internal fun resolveNativeRadius(view: View, height: Float, nativeRadius: Float): Float? {
        if (!height.isFinite() || height <= 0f || !nativeRadius.isFinite()) return null
        val owner = findContentOwner(view) ?: return null
        owner.setTag(RENDERED_GEOMETRY_TAG, floatArrayOf(height, nativeRadius))
        return configuredRadiusPx(owner, systemRadiusPx = nativeRadius, renderedHeight = height)
    }

    internal fun <T> drawWithRadius(drawable: GradientDrawable, radius: Float, draw: () -> T): T {
        val originalRadius = drawable.cornerRadius
        val originalRadii = drawable.cornerRadii
        // Both setters invalidate the drawable. Suppress only these temporary mutations so
        // restoring the native radius does not schedule an endless sequence of redraws.
        val callback = drawable.callback
        try {
            drawable.callback = null
            drawable.cornerRadius = radius
        } finally {
            drawable.callback = callback
        }
        return try {
            draw()
        } finally {
            val currentCallback = drawable.callback
            try {
                drawable.callback = null
                drawable.cornerRadius = originalRadius
                if (originalRadii != null) drawable.cornerRadii = originalRadii
            } finally {
                drawable.callback = currentCallback
            }
        }
    }

    /** Used only for holder-owned real/dummy media backgrounds, including detached holders. */
    fun wrapMediaBackground(provider: ViewOutlineProvider?): ViewOutlineProvider? = provider?.let {
        if (it is CornerOutlineProvider && it.mediaBackground) it
        else CornerOutlineProvider(it, mediaBackground = true)
    }

    private fun configuredRadiusPx(
        view: View,
        mediaBackground: Boolean = false,
        systemRadiusPx: Float? = null,
        renderedHeight: Float? = null
    ): Float? {
        if (retired) return null
        val config = MediaCardRuntimeConfig.current
        val dp = preferences?.getInt(
            RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_CORNER_RADIUS_DP,
            RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_CORNER_RADIUS_DP
        ) ?: config.islandExpanded.cornerRadiusDp
        if (!config.enabled || dp < 0) return null
        val target = if (mediaBackground) null else radiusTarget(view, renderedHeight) ?: return null
        val customRadius = IslandExpandedMediaCornerRadiusPolicy.resolveRadiusPx(
            isExpandedMediaCard = true,
            configuredDp = dp,
            density = view.resources.displayMetrics.density,
            systemRadiusPx = 0f
        )
        val geometry = target?.geometry ?: return customRadius
        val rendered = target.owner.getTag(RENDERED_GEOMETRY_TAG) as? FloatArray
        val nativeRadius = rendered?.takeIf { kotlin.math.abs(it[0] - geometry.height) <= 0.5f }?.get(1)
            ?: systemRadiusPx ?: runCatching {
            val owner = target.owner
            val id = accessors(owner.javaClass).nativeRadiusId
            if (id != null && id != 0) owner.resources.getDimension(id) else null
        }.getOrNull() ?: (30f * view.resources.displayMetrics.density)
        return IslandExpandedMediaCornerRadiusPolicy.resolveAnimatedRadiusPx(
            customRadius, nativeRadius, geometry.height, geometry.expandedHeight, geometry.collapsedHeight
        )
    }

    private data class AnimationGeometry(val height: Float, val expandedHeight: Float, val collapsedHeight: Float)
    private data class RadiusTarget(val owner: View, val geometry: AnimationGeometry?)

    /** Logical state changes before the old card finishes shrinking. Use its actual clip height. */
    private fun radiusTarget(view: View, renderedHeight: Float? = null): RadiusTarget? {
        val owner = findContentOwner(view) ?: return null
        val access = accessors(owner.javaClass)
        if (!IslandProbeUtils.isMediaIsland(access.data.read(owner))) {
            owner.setTag(VISIBLE_EXPANSION_TAG, null)
            return null
        }
        val stateObject = access.state.read(owner) ?: return null
        val state = stateObject.javaClass.simpleName
        val animator = access.animator.read(owner)
        val animationAccess = animator?.let { accessors(it.javaClass) }
        val top = animationAccess?.clipTop.readFloat(animator)
        val bottom = animationAccess?.clipBottom.readFloat(animator)
        val coords = animationAccess?.coords.read(animator) ?: animator
        val coordAccess = coords?.let { accessors(it.javaClass) }
        val expandedTop = coordAccess?.expandedTop.read(coords) as? Number
        val expandedBottom = coordAccess?.expandedBottom.read(coords) as? Number
        val expandedHeight = if (expandedTop != null && expandedBottom != null) {
            expandedBottom.toFloat() - expandedTop.toFloat()
        } else (access.expandedHeight.read(owner) as? Number)?.toFloat()
        val collapsedHeight = (access.collapsedHeight.read(owner) as? Number)?.toFloat()
        val frameHeight = renderedHeight
            ?: (owner.getTag(RENDERED_GEOMETRY_TAG) as? FloatArray)?.getOrNull(0)
            ?: if (top != null && bottom != null) bottom - top else null
        val geometry = if (frameHeight != null && expandedHeight != null && collapsedHeight != null &&
            frameHeight.isFinite() && expandedHeight.isFinite() && collapsedHeight.isFinite() &&
            expandedHeight > collapsedHeight && collapsedHeight > 0f && frameHeight > 0f
        ) AnimationGeometry(frameHeight, expandedHeight, collapsedHeight) else null
        if (state == "Expanded") {
            owner.setTag(SETTLED_COLLAPSE_TAG, null)
            owner.setTag(VISIBLE_EXPANSION_TAG, true)
            return RadiusTarget(owner, geometry)
        }
        when (state) {
            "BigIsland", "ShowOnceBigIsland", "SmallIsland" -> Unit
            else -> {
                owner.setTag(VISIBLE_EXPANSION_TAG, null)
                return null
            }
        }
        if ((owner.getTag(VISIBLE_EXPANSION_TAG) != true &&
                access.lastState.read(owner)?.javaClass?.simpleName != "Expanded") || geometry == null) return null
        val isAnimating = animationAccess?.animating.read(animator) == true
        val atCompactEndpoint = geometry.height <= geometry.collapsedHeight + 0.5f
        val settledState = (owner.getTag(SETTLED_COLLAPSE_TAG) as? WeakReference<*>)?.get()
        // lastState remains Expanded after completion. Do not mistake a later compact bounce
        // for the original closing animation; the token is specific to this native state object.
        if (!atCompactEndpoint && settledState === stateObject) return null
        if (atCompactEndpoint) {
            owner.setTag(SETTLED_COLLAPSE_TAG, WeakReference(stateObject))
            owner.setTag(VISIBLE_EXPANSION_TAG, null)
            if (!isAnimating) return null
        }
        return RadiusTarget(owner, geometry)
    }

    private fun findContentOwner(view: View): View? {
        fun owner(candidate: View): View? {
            val access = accessors(candidate.javaClass)
            val real = access.realView.read(candidate) as? View ?: candidate
            return real.takeIf { accessors(it.javaClass).data != null }
        }
        (view as? ViewGroup)?.let { group ->
            for (index in 0 until group.childCount) owner(group.getChildAt(index))?.let { return it }
        }
        var current: View? = view
        repeat(32) {
            val candidate = current ?: return null
            owner(candidate)?.let { return it }
            current = candidate.parent as? View
        }
        return null
    }

    internal class CornerOutlineProvider(
        private val original: ViewOutlineProvider,
        val mediaBackground: Boolean = false
    ) : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            observe(view, mediaBackground)
            // Native fake providers also update RenderNode positions; always call them first.
            original.getOutline(view, outline)
            val radius = configuredRadiusPx(view, mediaBackground) ?: return
            val rect = Rect()
            if (!outline.getRect(rect)) return // Preserve smooth/custom path outlines.
            val alpha = outline.alpha
            outline.setRoundRect(rect, radius.coerceAtMost(minOf(rect.width(), rect.height()) / 2f))
            outline.alpha = alpha
        }
    }

    internal fun isExpandedMediaCard(view: View): Boolean {
        var current: View? = view
        // The outer background owns the content as a child; other targets are descendants.
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                val child = view.getChildAt(index)
                resolveContentTarget(child)?.let { return it }
            }
        }
        repeat(32) {
            val candidate = current ?: return false
            resolveContentTarget(candidate)?.let { return it }
            current = candidate.parent as? View
        }
        return false
    }

    /** Null means this view is not a content owner; false is an authoritative non-target. */
    private fun resolveContentTarget(view: View): Boolean? {
        val access = accessors(view.javaClass)
        val realView = access.realView.read(view) as? View
        val owner = realView ?: view
        val ownerAccess = accessors(owner.javaClass)
        if (ownerAccess.data == null) return null
        val data = ownerAccess.data.read(owner)
        if (!IslandProbeUtils.isMediaIsland(data)) return false
        val state = access.state.read(view) ?: ownerAccess.state.read(owner) ?: return false
        return state.javaClass.simpleName == "Expanded"
    }

    private fun isOutlineCandidate(view: View): Boolean {
        if (view.javaClass.name.startsWith(ISLAND_PACKAGE)) return true
        val id = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
        return id == "fake_expanded_view" || id == "expanded_view" || id == "media_bg"
    }

    private fun getter(type: Class<*>, name: String): Method? = runCatching {
        type.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
            ?.apply { isAccessible = true }
    }.getOrNull()

    private fun accessors(type: Class<*>): Accessors = synchronized(accessorCache) {
        accessorCache[type]?.get() ?: Accessors(
            getter(type, "getCurrentIslandData"),
            getter(type, "getState"),
            getter(type, "getRealView"),
            getter(type, "getDrawable"),
            getter(type, "getLastState"),
            getter(type, "getAnimatorDelegate"),
            getter(type, "containerScheduleUpdate"),
            getter(type, "getCoords"),
            getter(type, "getExpandedViewHeight"),
            getter(type, "getIslandViewHeight"),
            getter(type, "getExpandedTop"),
            getter(type, "getExpandedBottom"),
            getter(type, "isAnimating"),
            field(type, "containerClipTopProgress"),
            field(type, "containerClipBottomProgress"),
            type.methods.firstOrNull { it.name.substringBefore('$') == "setGlowEffectPosition" &&
                it.parameterCount == 5 && it.parameterTypes.all { parameter -> parameter == Float::class.javaPrimitiveType } }
                ?.apply { isAccessible = true },
            runCatching {
                type.classLoader?.loadClass("${ISLAND_PACKAGE}R\$dimen")
                    ?.getDeclaredField("island_radius")?.getInt(null)
            }.getOrNull()
        ).also {
            if (accessorCache.size >= 128) accessorCache.clear()
            accessorCache[type] = WeakReference(it)
        }
    }

    private fun Method?.read(receiver: Any?): Any? =
        if (this == null || receiver == null) null else runCatching { invoke(receiver) }.getOrNull()

    private fun field(type: Class<*>, name: String): Field? =
        generateSequence(type as Class<*>?) { it.superclass }.firstNotNullOfOrNull { owner ->
            runCatching { owner.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
        }

    private fun Field?.readFloat(receiver: Any?): Float? =
        if (this == null || receiver == null) null else runCatching { getFloat(receiver) }.getOrNull()

    private data class Accessors(
        val data: Method?,
        val state: Method?,
        val realView: Method?,
        val drawable: Method?,
        val lastState: Method?,
        val animator: Method?,
        val containerUpdate: Method?,
        val coords: Method?,
        val expandedHeight: Method?,
        val collapsedHeight: Method?,
        val expandedTop: Method?,
        val expandedBottom: Method?,
        val animating: Method?,
        val clipTop: Field?,
        val clipBottom: Field?,
        val glowPosition: Method?,
        val nativeRadiusId: Int?
    )
}
