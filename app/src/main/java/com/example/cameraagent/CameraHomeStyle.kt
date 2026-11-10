package com.example.cameraagent

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.widget.TextView

/** 圆角半径（dp），对应 docs/CAMERA_UI_VISUAL_DESIGN.md 的控件 / 卡片 / 主按钮层级。 */
object CameraRadius {
    const val CONTROL = 12
    const val CARD = 18
    const val PRIMARY = 24
}

/**
 * 取景页的视觉令牌与通用样式：颜色、间距、圆角与按下反馈。
 *
 * 取景页按 4 / 8 / 12 / 16 / 24dp 的间距体系、12 / 18 / 24dp 的圆角层级组织浮层控件；
 * 所有可点击控件共用同一套按下反馈，避免控件各自使用不同的透明度与描边而显得杂乱。
 */
object CameraHomeStyle {
    const val CANVAS = 0xFF0A0D12.toInt()
    const val SURFACE = 0xFF141923.toInt()
    const val SURFACE_SELECTED = 0xFF273143.toInt()
    const val OUTLINE = 0x33FFFFFF
    const val TEXT_PRIMARY = 0xFFF4F5F7.toInt()
    const val TEXT_SECONDARY = 0xFFA4ABB7.toInt()
    const val TEXT_TERTIARY = 0xFF8A93A3.toInt()
    const val FOCUS_ACCENT = 0xFFD5E4FF.toInt()
    const val AI_ACCENT = 0xFFCCBEFF.toInt()
    /** AI 入口底色：中性深色，避免彩色渐变抢夺快门的视觉中心。 */
    const val AI_TILE = 0xFF1B2130.toInt()

    const val SPACING_4 = 4
    const val SPACING_8 = 8
    const val SPACING_12 = 12
    const val SPACING_16 = 16
    const val SPACING_24 = 24

    /** 触控区域下限，对应视觉规范中的 48dp。 */
    const val MIN_TOUCH_TARGET = 48
    const val CONTROL_HEIGHT = 48

    /** 浮层玻璃卡片：半透明深色底、细描边与卡片圆角。 */
    fun glassCard(context: Context, radiusDp: Int = CameraRadius.CARD): Drawable =
        roundedRect(0xE0121621.toInt(), OUTLINE, 1, radiusDp, context)

    /** 浮层上的图标按钮或文字按钮，带按下态。 */
    fun glassControl(context: Context, radiusDp: Int = CameraRadius.PRIMARY): Drawable = stateful(
        roundedRect(0x8C0C1018.toInt(), OUTLINE, 1, radiusDp, context),
        roundedRect(0xB31F2937.toInt(), OUTLINE, 1, radiusDp, context),
    )

    /** 只保留按下反馈、不显示底色的图标按钮。 */
    fun touchFeedback(context: Context, radiusDp: Int = CameraRadius.CONTROL): Drawable = stateful(
        roundedRect(Color.TRANSPARENT, Color.TRANSPARENT, 1, radiusDp, context),
        roundedRect(0x40FFFFFF, Color.TRANSPARENT, 1, radiusDp, context),
    )

    /** 预设胶囊：选中项用风格色描边并稍亮底色，未选项降低对比度但仍可读。 */
    fun presetPill(context: Context, selected: Boolean, accentColor: Int, pressed: Boolean): Drawable {
        val fill = when {
            selected -> 0xCC273143.toInt()
            pressed -> 0xCC1F2937.toInt()
            else -> 0x8C0C1018.toInt()
        }
        val stroke = if (selected) withAlpha(accentColor, 0x99) else 0x40FFFFFF
        return roundedRect(fill, stroke, 1, CameraRadius.PRIMARY, context)
    }

    fun presetPillStateful(context: Context, selected: Boolean, accentColor: Int): Drawable = stateful(
        presetPill(context, selected, accentColor, pressed = false),
        presetPill(context, selected, accentColor, pressed = true),
    )

    /** 底部取景区渐变遮罩：底部深色，向上过渡到透明。 */
    fun bottomScrim(): Drawable = GradientDrawable().apply {
        orientation = GradientDrawable.Orientation.BOTTOM_TOP
        colors = intArrayOf(0xF2000000.toInt(), 0xA6000000.toInt(), 0x00000000)
    }

    /** 一级导航选中态：圆角胶囊底色。 */
    fun navPill(selected: Boolean): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 18f
        setColor(if (selected) SURFACE_SELECTED else Color.TRANSPARENT)
    }

    /** 圆形按钮底：完全不透明，避免预览画面透出干扰图标识别。 */
    fun circle(fill: Int, stroke: Int, strokeWidthDp: Int, context: Context): Drawable =
        roundedRect(fill, stroke, strokeWidthDp, CameraRadius.PRIMARY, context).apply {
            cornerRadius = -1f
            shape = GradientDrawable.OVAL
        }

    fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    fun roundedRect(
        fill: Int,
        stroke: Int,
        strokeWidthDp: Int,
        radiusDp: Int,
        context: Context,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        val density = context.resources.displayMetrics.density
        cornerRadius = radiusDp * density
        if (Color.alpha(stroke) > 0) {
            setStroke((strokeWidthDp * density).toInt().coerceAtLeast(1), stroke)
        }
    }

    /** 当前文本行高（px）：优先取已排版的 layout，否则按字号与行距估算。 */
    fun lineHeightOf(text: TextView): Int {
        val layout = text.layout
        if (layout != null && layout.lineCount > 0) {
            return (layout.getLineBottom(0) - layout.getLineTop(0)).coerceAtLeast(1)
        }
        val metrics = text.paint.fontMetrics
        return (metrics.descent - metrics.ascent).toInt().coerceAtLeast(1)
    }

    private fun stateful(normal: Drawable, pressed: Drawable): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), pressed)
        addState(intArrayOf(), normal)
    }
}
