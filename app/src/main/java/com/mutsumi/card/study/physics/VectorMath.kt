package com.mutsumi.card.study.physics

import kotlin.math.sqrt

data class Vec2(
    val x: Float,
    val y: Float,
) {
    init {
        require(x.isFinite() && y.isFinite()) { "Vec2 分量必须是有限数" }
    }

    val length: Float
        get() = sqrt(x * x + y * y)

    operator fun plus(other: Vec2): Vec2 = Vec2(x + other.x, y + other.y)

    operator fun minus(other: Vec2): Vec2 = Vec2(x - other.x, y - other.y)

    operator fun times(scale: Float): Vec2 = Vec2(x * scale, y * scale)

    companion object {
        val Zero = Vec2(0f, 0f)
    }
}

data class Vec3(
    val x: Float,
    val y: Float,
    val z: Float,
) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "Vec3 分量必须是有限数" }
    }

    val length: Float
        get() = sqrt(x * x + y * y + z * z)

    fun normalized(): Vec3 {
        val magnitude = length
        require(magnitude > VECTOR_EPSILON) { "零向量没有方向" }
        return this * (1f / magnitude)
    }

    fun dot(other: Vec3): Float = x * other.x + y * other.y + z * other.z

    fun cross(other: Vec3): Vec3 = Vec3(
        x = y * other.z - z * other.y,
        y = z * other.x - x * other.z,
        z = x * other.y - y * other.x,
    )

    operator fun plus(other: Vec3): Vec3 = Vec3(x + other.x, y + other.y, z + other.z)

    operator fun times(scale: Float): Vec3 = Vec3(x * scale, y * scale, z * scale)

    companion object {
        val UnitX = Vec3(1f, 0f, 0f)
        val UnitY = Vec3(0f, 1f, 0f)
        val UnitZ = Vec3(0f, 0f, 1f)
    }
}

data class Size2(
    val width: Float,
    val height: Float,
) {
    init {
        require(width.isFinite() && width > 0f) { "视口宽度必须大于零" }
        require(height.isFinite() && height > 0f) { "视口高度必须大于零" }
    }
}

internal fun lerp(from: Float, to: Float, fraction: Float): Float =
    from + (to - from) * fraction

internal fun lerp(from: Vec2, to: Vec2, fraction: Float): Vec2 = Vec2(
    x = lerp(from.x, to.x, fraction),
    y = lerp(from.y, to.y, fraction),
)

internal fun smootherStep(fraction: Float): Float {
    val t = fraction.coerceIn(0f, 1f)
    return t * t * t * (t * (t * 6f - 15f) + 10f)
}

private const val VECTOR_EPSILON = 1e-7f
