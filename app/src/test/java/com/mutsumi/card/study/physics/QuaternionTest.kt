package com.mutsumi.card.study.physics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class QuaternionTest {
    @Test
    fun axisAngleRotatesVectorInRightHandedDirection() {
        val rotation = Quaternion.fromAxisAngle(Vec3.UnitY, 90f)

        val rotated = rotation.rotate(Vec3.UnitZ)

        assertThat(rotated.x).isWithin(EPSILON).of(1f)
        assertThat(rotated.y).isWithin(EPSILON).of(0f)
        assertThat(rotated.z).isWithin(EPSILON).of(0f)
    }

    @Test
    fun slerpUsesShortestArcAcrossQuaternionSignBoundary() {
        val from = Quaternion.fromAxisAngle(Vec3.UnitY, 350f)
        val to = Quaternion.Identity

        val middle = Quaternion.slerp(from, to, 0.5f)

        assertThat(Quaternion.angularDistanceDegrees(from, middle)).isWithin(0.01f).of(5f)
        assertThat(Quaternion.angularDistanceDegrees(middle, to)).isWithin(0.01f).of(5f)
        assertThat(middle.rotate(Vec3.UnitZ).x).isLessThan(0f)
    }

    @Test
    fun slerpTreatsNegatedQuaternionAsTheSameDirection() {
        val pose = Quaternion.fromAxisAngle(Vec3.UnitX, 73f)

        val middle = Quaternion.slerp(pose, -pose, 0.5f)

        assertThat(Quaternion.angularDistanceDegrees(pose, middle)).isWithin(EPSILON).of(0f)
    }

    @Test
    fun multiplicationAppliesLocalRotationAfterStartingDirection() {
        val back = Quaternion.fromAxisAngle(Vec3.UnitY, 180f)
        val localTurn = Quaternion.fromAxisAngle(Vec3.UnitY, 30f)

        val orientation = back * localTurn

        assertThat(Quaternion.angularDistanceDegrees(back, orientation)).isWithin(0.01f).of(30f)
        assertThat(orientation.isEquivalentTo(Quaternion.fromAxisAngle(Vec3.UnitY, 210f))).isTrue()
    }

    private companion object {
        const val EPSILON = 0.0001f
    }
}
