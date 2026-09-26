package com.mutsumi.card.focus

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FocusReporterControllerTest {
    private val catalog = FocusCatalog.parse("""{"subjects":[{"id":1,"name":"408"},{"id":2,"name":"数学"}],"focus_items":[{"id":9,"subject_id":1,"name":"强化","sort_order":1},{"id":6,"subject_id":2,"name":"模拟","sort_order":2}]}""")

    @Test fun `只在前台学习时开始并每二十秒续报，离开立即 idle`() = runTest {
        val gateway = FakeGateway(catalog)
        val storage = FakeStorage(FocusConfiguration(true, 1, 9))
        val reporter = FocusReporterController(storage, gateway, backgroundScope, StandardTestDispatcher(testScheduler))
        reporter.setForeground(true)
        reporter.setStudying(true)
        runCurrent()
        assertThat(gateway.frames).containsExactly(FocusProtocol.focusFrame(catalog, 1, 9))
        advanceTimeBy(20_000)
        runCurrent()
        assertThat(gateway.frames).hasSize(2)
        reporter.setStudying(false)
        runCurrent()
        assertThat(gateway.frames.last()).isEqualTo(FocusProtocol.idleFrame())
        advanceTimeBy(60_000)
        runCurrent()
        assertThat(gateway.frames).hasSize(3)
    }

    @Test fun `目录不匹配时绝不发送 focus，409 后暂停重试`() = runTest {
        val invalid = FakeGateway(catalog)
        val mismatched = FocusReporterController(FakeStorage(FocusConfiguration(true, 1, 6)), invalid, backgroundScope, StandardTestDispatcher(testScheduler))
        mismatched.setForeground(true); mismatched.setStudying(true)
        runCurrent()
        assertThat(invalid.frames).isEmpty()

        val conflict = FakeGateway(catalog, focusStatus = 409)
        val reporter = FocusReporterController(FakeStorage(FocusConfiguration(true, 1, 9)), conflict, backgroundScope, StandardTestDispatcher(testScheduler))
        reporter.setForeground(true); reporter.setStudying(true)
        runCurrent()
        assertThat(conflict.frames).hasSize(1)
        assertThat(reporter.state.value.message).contains("409")
        advanceTimeBy(60_000)
        runCurrent()
        assertThat(conflict.frames).hasSize(1)
    }

    @Test fun `续报冲突后离开学习页仍发送 idle`() = runTest {
        val gateway = FakeGateway(catalog, focusStatuses = mutableListOf(200, 409))
        val reporter = FocusReporterController(FakeStorage(FocusConfiguration(true, 1, 9)), gateway, backgroundScope, StandardTestDispatcher(testScheduler))
        reporter.setForeground(true); reporter.setStudying(true)
        runCurrent()
        advanceTimeBy(20_000)
        runCurrent()
        assertThat(reporter.state.value.message).contains("409")
        reporter.setStudying(false)
        runCurrent()
        assertThat(gateway.frames.last()).isEqualTo(FocusProtocol.idleFrame())
    }

    private class FakeStorage(private var configuration: FocusConfiguration) : FocusSettingsStorage {
        private var key = "1." + "a".repeat(64)
        override suspend fun loadConfiguration() = configuration
        override suspend fun saveConfiguration(configuration: FocusConfiguration) { this.configuration = configuration }
        override fun loadKey(): String = key
        override fun saveKey(key: String) { this.key = key }
        override fun clearKey() { key = "" }
    }

    private class FakeGateway(
        private val catalogValue: FocusCatalog,
        private val focusStatus: Int = 200,
        private val focusStatuses: MutableList<Int> = mutableListOf(),
    ) : FocusReporterGateway {
        val frames = mutableListOf<String>()
        override suspend fun catalog(key: String) = catalogValue
        override suspend fun frame(key: String, body: String): Int {
            frames += body
            return if (body.contains("\"focus\"")) {
                if (focusStatuses.isEmpty()) focusStatus else focusStatuses.removeAt(0)
            } else 200
        }
    }
}
