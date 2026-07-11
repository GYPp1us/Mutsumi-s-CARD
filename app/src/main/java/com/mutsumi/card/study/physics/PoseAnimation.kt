package com.mutsumi.card.study.physics

data class PoseAnimation(
    val startPose: CardPose,
    val targetPose: CardPose,
) {
    fun sample(fraction: Float): CardPose {
        require(fraction.isFinite()) { "动画进度必须是有限数" }
        val progress = fraction.coerceIn(0f, 1f)
        if (progress == 0f) return startPose
        if (progress == 1f) return targetPose

        val easedProgress = 1f - (1f - progress) * (1f - progress) * (1f - progress)
        return CardPose(
            center = lerp(startPose.center, targetPose.center, easedProgress),
            orientation = Quaternion.slerp(
                from = startPose.orientation,
                to = targetPose.orientation,
                fraction = progress,
            ),
        )
    }

    fun interruptAt(fraction: Float, newTargetPose: CardPose): PoseAnimation = PoseAnimation(
        startPose = sample(fraction),
        targetPose = newTargetPose,
    )
}
