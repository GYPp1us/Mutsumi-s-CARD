package com.mutsumi.card.focus

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class FocusProtocolTest {
    private val catalogJson = """{"subjects":[{"id":1,"name":"408"},{"id":2,"name":"数学"}],"focus_items":[{"id":9,"subject_id":1,"name":"强化","sort_order":1},{"id":1,"subject_id":1,"name":"二轮","sort_order":2},{"id":6,"subject_id":2,"name":"模拟","sort_order":3},{"id":5,"subject_id":1,"name":"模拟","sort_order":4}]}"""

    @Test fun `目录科目和事项依数字 ID 配对`() {
        val catalog = FocusCatalog.parse(catalogJson)
        assertThat(catalog.subjects.first().id).isEqualTo(1)
        assertThat(catalog.itemsForSubject(1).map { it.id }).containsExactly(9, 1, 5).inOrder()
        assertThat(catalog.containsSelection(1, 9)).isTrue()
        assertThat(catalog.containsSelection(1, 6)).isFalse()
    }

    @Test fun `上报只含固定来源状态与有效数字 ID`() {
        val catalog = FocusCatalog.parse(catalogJson)
        assertThat(FocusProtocol.focusFrame(catalog, 1, 9)).isEqualTo("""{"source":"mutsumi_card","state":"focus","subject_id":1,"focus_item_id":9}""")
        assertThat(FocusProtocol.idleFrame()).isEqualTo("""{"source":"mutsumi_card","state":"idle"}""")
        assertThrows(IllegalArgumentException::class.java) { FocusProtocol.focusFrame(catalog, 1, 6) }
    }

    @Test fun `只接受指定 HTTPS 主机的目录地址或密钥`() {
        val key = "1." + "a".repeat(64)
        assertThat(FocusProtocol.parseKey(key)).isEqualTo(key)
        assertThat(FocusProtocol.parseKey("https://platform.arcol.site/api/focus-reporter/$key/catalog")).isEqualTo(key)
        assertThrows(IllegalArgumentException::class.java) { FocusProtocol.parseKey("https://evil.test/api/focus-reporter/$key/catalog") }
        assertThrows(IllegalArgumentException::class.java) { FocusProtocol.parseKey("http://platform.arcol.site/api/focus-reporter/$key/catalog") }
    }
}
