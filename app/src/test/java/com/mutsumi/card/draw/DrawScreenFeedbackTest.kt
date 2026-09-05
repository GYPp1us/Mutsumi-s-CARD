package com.mutsumi.card.draw

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DrawScreenFeedbackTest {
    @Test
    fun 空正面明确提示会降级为文字key() {
        assertThat(frontSaveFeedback(frontFallsBackToKey = true))
            .isEqualTo("正面将以文字 key 显示")
    }

    @Test
    fun 有正面内容明确提示会保存为图片() {
        assertThat(frontSaveFeedback(frontFallsBackToKey = false))
            .isEqualTo("正面将保存为图片")
    }
}
