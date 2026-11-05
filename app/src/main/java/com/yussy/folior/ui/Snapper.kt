package com.yussy.folior.ui

import kotlin.math.abs

/**
 * 吸附类型。按「用户意图强度」排序：指示线是用户亲手拉的，最优先；
 * 页面边/中线是结构性的；等距与整厘米网格是辅助性的。
 */
enum class SnapKind { GUIDE, ELEMENT, PAGE, EQUAL, GRID }

/**
 * 同级距离时的优先级罚分（乘容差）。数值小 = 更优先。
 * 缩放吸附要按同一套优先级挑目标，所以放在外面共用。
 */
fun snapPenalty(k: SnapKind): Float = when (k) {
    SnapKind.GUIDE -> 0f
    SnapKind.ELEMENT -> 0.02f
    SnapKind.PAGE -> 0.05f
    SnapKind.EQUAL -> 0.12f
    SnapKind.GRID -> 0.2f
}

/**
 * 单轴吸附器。
 *
 * 做法与桌面排版软件一致：把候选目标摊成一维轴值，与拖动元素的
 * 起点 / 中心 / 终点三个锚点逐一比对，取「距离 + 类型罚分」最小者。
 * 罚分让同距离时优先级更高的目标胜出，而不是简单按类型硬分层
 * （硬分层会导致页面中线抢走明明更近的元素边）。
 */
class AxisSnapper {

    data class Target(val value: Float, val kind: SnapKind)
    data class Hit(val value: Float, val kind: SnapKind, val delta: Float, val anchor: Int)

    private val list = ArrayList<Target>()

    fun clear() = list.clear()

    fun add(value: Float, kind: SnapKind) {
        if (!value.isFinite()) return
        list.add(Target(value, kind))
    }

    val size: Int get() = list.size

    /** 所有目标（缩放吸附要按「目标 → 尺寸」反算，不能只要最近的那个）。 */
    val all: List<Target> get() = list

    /**
     * @param start 元素（或多选整体框）在该轴上的起点，归一化值
     * @param size  该轴上的尺寸
     * @param tol   容差，归一化值（由 8dp 屏幕距离换算而来）
     * @return 命中的目标；未命中返回 null
     */
    fun hit(start: Float, size: Float, tol: Float): Hit? {
        if (list.isEmpty() || tol <= 0f) return null
        val mid = start + size / 2f
        val end = start + size
        var best: Hit? = null
        var bestScore = Float.MAX_VALUE
        for (t in list) {
            val penalty = penaltyOf(t.kind) * tol
            val d0 = t.value - start
            val d1 = t.value - mid
            val d2 = t.value - end
            val a0 = abs(d0); val a1 = abs(d1); val a2 = abs(d2)
            val ad: Float; val dd: Float; val anchor: Int
            if (a0 <= a1 && a0 <= a2) { ad = a0; dd = d0; anchor = 0 }
            else if (a1 <= a2) { ad = a1; dd = d1; anchor = 1 }
            else { ad = a2; dd = d2; anchor = 2 }
            if (ad > tol) continue
            val score = ad + penalty
            if (score < bestScore) {
                bestScore = score
                best = Hit(t.value, t.kind, dd, anchor)
            }
        }
        return best
    }

    private fun penaltyOf(k: SnapKind): Float = snapPenalty(k)
}

/**
 * 指示线允许吸附的角度（度）。线是无向的，所以只需 0..90 与其镜像：
 * 150° 与 -30° 是同一条线，不必重复列出。
 */
private val GUIDE_SNAP_ANGLES = floatArrayOf(
    0f, 30f, 45f, 60f, 90f, -30f, -45f, -60f
)

/** 指示线角度归一化到 (-90, 90]：线是无向的，180° 与 0° 等价。 */
fun normalizeGuideAngle(deg: Float): Float {
    var a = deg % 180f
    if (a > 90f) a -= 180f
    if (a <= -90f) a += 180f
    return a
}

/**
 * 线角度吸附：靠近 0/±30/±45/±60/90 时对齐，阈值 3°。
 * 与元素的 90° 步进旋转分开——指示线角度是连续的。
 */
fun snapGuideAngle(deg: Float): Float {
    val a = normalizeGuideAngle(deg)
    var best = a
    var bestD = Float.MAX_VALUE
    for (t in GUIDE_SNAP_ANGLES) {
        val d = abs(a - t)
        if (d < bestD) { bestD = d; best = t }
    }
    return if (bestD <= 3f) best else a
}
