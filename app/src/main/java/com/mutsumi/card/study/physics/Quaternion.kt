package com.mutsumi.card.study.physics

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Quaternion(
    val w: Float,
    val x: Float,
    val y: Float,
    val z: Float,
) {
    init {
        require(w.isFinite() && x.isFinite() && y.isFinite() && z.isFinite()) {
            "四元数分量必须是有限数"
        }
    }

    val magnitude: Float
        get() = sqrt(w * w + x * x + y * y + z * z)

    fun normalized(): Quaternion {
        val norm = magnitude
        require(norm > QUATERNION_EPSILON) { "零四元数不能表示方向" }
        return Quaternion(w / norm, x / norm, y / norm, z / norm)
    }

    fun inverse(): Quaternion {
        val squaredNorm = w * w + x * x + y * y + z * z
        require(squaredNorm > QUATERNION_EPSILON) { "零四元数没有逆" }
        return Quaternion(w / squaredNorm, -x / squaredNorm, -y / squaredNorm, -z / squaredNorm)
    }

    fun dot(other: Quaternion): Float = w * other.w + x * other.x + y * other.y + z * other.z

    operator fun unaryMinus(): Quaternion = Quaternion(-w, -x, -y, -z)

    operator fun plus(other: Quaternion): Quaternion = Quaternion(
        w + other.w,
        x + other.x,
        y + other.y,
        z + other.z,
    )

    operator fun times(scale: Float): Quaternion = Quaternion(w * scale, x * scale, y * scale, z * scale)

    operator fun times(other: Quaternion): Quaternion = Quaternion(
        w = w * other.w - x * other.x - y * other.y - z * other.z,
        x = w * other.x + x * other.w + y * other.z - z * other.y,
        y = w * other.y - x * other.z + y * other.w + z * other.x,
        z = w * other.z + x * other.y - y * other.x + z * other.w,
    )

    fun rotate(vector: Vec3): Vec3 {
        val direction = normalized()
        val vectorQuaternion = Quaternion(0f, vector.x, vector.y, vector.z)
        val result = direction * vectorQuaternion * direction.inverse()
        return Vec3(result.x, result.y, result.z)
    }

    fun isEquivalentTo(other: Quaternion, tolerance: Float = 1e-5f): Boolean =
        abs(normalized().dot(other.normalized())) >= 1f - tolerance

    companion object {
        val Identity = Quaternion(1f, 0f, 0f, 0f)

        fun fromAxisAngle(axis: Vec3, degrees: Float): Quaternion {
            require(degrees.isFinite()) { "旋转角必须是有限数" }
            if (degrees == 0f) return Identity
            val normalizedAxis = axis.normalized()
            val halfRadians = Math.toRadians(degrees.toDouble()).toFloat() / 2f
            val sine = sin(halfRadians)
            return Quaternion(
                w = cos(halfRadians),
                x = normalizedAxis.x * sine,
                y = normalizedAxis.y * sine,
                z = normalizedAxis.z * sine,
            ).normalized()
        }

        fun slerp(from: Quaternion, to: Quaternion, fraction: Float): Quaternion {
            require(fraction.isFinite()) { "插值比例必须是有限数" }
            val t = fraction.coerceIn(0f, 1f)
            if (t == 0f) return from
            if (t == 1f) return to

            val start = from.normalized()
            var end = to.normalized()
            var cosine = start.dot(end)
            if (cosine < 0f) {
                end = -end
                cosine = -cosine
            }
            cosine = cosine.coerceIn(-1f, 1f)

            if (cosine > LINEAR_SLERP_THRESHOLD) {
                return (start * (1f - t) + end * t).normalized()
            }

            val angle = acos(cosine)
            val sine = sin(angle)
            val startWeight = sin((1f - t) * angle) / sine
            val endWeight = sin(t * angle) / sine
            return (start * startWeight + end * endWeight).normalized()
        }

        fun angularDistanceDegrees(first: Quaternion, second: Quaternion): Float {
            val cosine = abs(first.normalized().dot(second.normalized())).coerceIn(0f, 1f)
            return Math.toDegrees((2f * acos(cosine)).toDouble()).toFloat()
        }
    }
}

private const val QUATERNION_EPSILON = 1e-7f
private const val LINEAR_SLERP_THRESHOLD = 0.9995f
