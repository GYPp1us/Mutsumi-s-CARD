package com.mutsumi.card.ai

/**
 * 候选 PNG 仅保留一个很小的内存队列：每组至多 8 MiB，队列至多三组。
 * 生成协程在队列满时暂停，直到用户保存当前组，避免大型批量任务累积图片字节数组。
 */
internal object AiCandidateQueue {
    const val MAX_PENDING_GROUPS = 3
    const val MAX_GROUP_PNG_BYTES = 8 * 1024 * 1024

    fun requireWithinByteLimit(group: AiCandidateGroup) {
        require(group.pngByteCount <= MAX_GROUP_PNG_BYTES.toLong()) {
            "AI 候选组图片超过 ${MAX_GROUP_PNG_BYTES / (1024 * 1024)} MiB，请缩短单张卡片内容后重试"
        }
    }

    /** 保存当前选中的候选后，整组候选被消费，其他备选不再重复出现在保存队列。 */
    fun consume(
        groups: List<AiCandidateGroup>,
        groupIndex: Int,
        cardIndex: Int,
    ): AiCandidateQueueConsumption {
        require(groupIndex in groups.indices) { "候选组索引无效" }
        require(cardIndex in groups[groupIndex].cards.indices) { "候选卡片索引无效" }
        val updatedGroups = groups.toMutableList().also { it.removeAt(groupIndex) }
        return AiCandidateQueueConsumption(
            groups = updatedGroups,
            groupIndex = groupIndex.coerceAtMost(updatedGroups.lastIndex.coerceAtLeast(0)),
            selectedCardIndex = 0,
            removedGroup = true,
        )
    }
}

internal data class AiCandidateQueueConsumption(
    val groups: List<AiCandidateGroup>,
    val groupIndex: Int,
    val selectedCardIndex: Int,
    val removedGroup: Boolean,
)

/** AI 可生成相同 key；列表身份使用组号和候选序号，不能直接复用展示文字。 */
internal fun candidatePreviewItemKey(groupIndex: Int, cardIndex: Int): String = "$groupIndex:$cardIndex"

private val AiCandidateGroup.pngByteCount: Long
    get() = cards.sumOf { card -> card.frontPng.size.toLong() + card.backPng.size.toLong() }
