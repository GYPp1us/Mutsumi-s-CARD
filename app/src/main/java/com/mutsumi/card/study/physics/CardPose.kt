package com.mutsumi.card.study.physics

import com.mutsumi.card.study.CardSide

/**
 * 刚体姿态。orientation 始终是绝对方向，不会因当前显示面自动补转 180 度。
 * 正反面是同一刚体上的固定表面，外法线分别为局部 +Z 和 -Z。
 */
data class CardPose(
    val center: Vec2,
    val orientation: Quaternion,
) {
    fun normalFor(side: CardSide): Vec3 = orientation.rotate(
        when (side) {
            CardSide.Front -> Vec3.UnitZ
            CardSide.Back -> Vec3.UnitZ * -1f
        },
    )
}
