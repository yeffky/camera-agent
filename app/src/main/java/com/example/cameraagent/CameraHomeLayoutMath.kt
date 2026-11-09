package com.example.cameraagent

/**
 * 取景页排版计算规则。抽成纯函数，便于用 JVM 单测固定顶栏、建议卡与调控面板的尺寸约束，
 * 避免在设备密度、手势条与刘海屏变化时出现错位或遮挡。
 */
object CameraHomeLayoutMath {
    /** 横向外边距：固定 16dp，并让出刘海/挖孔与手势区域。 */
    fun horizontalInset(baseMargin: Int, leftInset: Int, rightInset: Int): Int =
        baseMargin.coerceAtLeast(leftInset).coerceAtLeast(rightInset)

    /** 顶栏上边距：固定间距加状态栏高度。 */
    fun topInset(baseGap: Int, statusBarInset: Int): Int = baseGap + statusBarInset

    /** 场景状态卡宽度：画布宽度减去两侧按钮及其间距，并保证最小可读宽度。 */
    fun statusCardWidth(contentWidth: Int, buttonSize: Int, itemGap: Int, minWidth: Int): Int =
        (contentWidth - (buttonSize + itemGap) * 2).coerceAtLeast(minWidth)

    /** AI 建议卡紧贴顶栏下方出现。 */
    fun advicePanelTop(topInset: Int, barHeight: Int, stackGap: Int): Int =
        topInset + barHeight + stackGap

    /** 建议卡正文可用高度：顶栏与底部快门行之间，最小保留一段摘要高度。 */
    fun adviceTextHeight(viewportHeight: Int, panelTop: Int, bottomGap: Int, chromeHeight: Int, minHeight: Int): Int =
        (viewportHeight - panelTop - bottomGap - chromeHeight).coerceAtLeast(minHeight)

    /** 调控面板内容区高度：顶栏到导航栏之间，超出后滚动。 */
    fun panelScrollHeight(viewportHeight: Int, panelTop: Int, bottomGap: Int, chromeHeight: Int, minHeight: Int): Int =
        (viewportHeight - panelTop - bottomGap - chromeHeight).coerceAtLeast(minHeight)

    /** 可用高度能容纳的正文行数，用于截断长建议而不是把按钮挤出屏幕。 */
    fun maxLinesForHeight(available: Int, lineHeight: Int, fallback: Int): Int =
        if (lineHeight <= 0) fallback else (available / lineHeight).coerceAtLeast(fallback)
}
