package miui.systemui.dynamicisland.view

import android.content.Context
import android.widget.FrameLayout

/** Native slot fixture; all eligibility and geometry decisions run in the production hook. */
class DynamicIslandExpandedView(context: Context) : FrameLayout(context) {
    var lastNativeGeometry: FloatArray? = null

    fun setGlowEffectPosition(left: Float, top: Float, right: Float, bottom: Float, radius: Float) {
        lastNativeGeometry = floatArrayOf(left, top, right, bottom, radius)
    }
}
