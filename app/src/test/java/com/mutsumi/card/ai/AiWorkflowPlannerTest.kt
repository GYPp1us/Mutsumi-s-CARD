package com.mutsumi.card.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AiWorkflowPlannerTest {
    @Test
    fun `heading splits can be combined into adjacent material bundles`() {
        val plan = AiWorkflowPlanner.plan(
            sources = listOf(
                ImportedAiFile(
                    id = "biology",
                    name = "biology.md",
                    content = "# 细胞\n细胞是生命的基本单位。\n\n# 代谢\n代谢维持生命活动。",
                ),
                ImportedAiFile(
                    id = "chemistry",
                    name = "chemistry.md",
                    content = "# 原子\n原子由原子核和电子构成。",
                ),
            ),
            definition = AiWorkflowDefinition(
                selectedSourceIds = setOf("biology", "chemistry"),
                splitMode = AiSplitMode.MarkdownHeading,
                combineMode = AiCombineMode.Adjacent,
                combineSize = 2,
            ),
        )

        assertThat(plan.segments).hasSize(3)
        assertThat(plan.bundles).hasSize(2)
        assertThat(plan.bundles.first().content).contains("# 细胞")
        assertThat(plan.bundles.first().content).contains("# 代谢")
        assertThat(plan.bundles.last().content).contains("# 原子")
    }

    @Test
    fun `source selection excludes unconnected notes from the request plan`() {
        val plan = AiWorkflowPlanner.plan(
            sources = listOf(
                ImportedAiFile(id = "keep", name = "保留.md", content = "保留内容"),
                ImportedAiFile(id = "skip", name = "跳过.md", content = "不应发送"),
            ),
            definition = AiWorkflowDefinition(
                selectedSourceIds = setOf("keep"),
                splitMode = AiSplitMode.WholeDocument,
                combineMode = AiCombineMode.Separate,
            ),
        )

        assertThat(plan.selectedSources.map { it.name }).containsExactly("保留.md")
        assertThat(plan.bundles.single().content).contains("保留内容")
        assertThat(plan.bundles.single().content).doesNotContain("不应发送")
    }

    @Test
    fun `context keeps the required section order while retaining generation parameters`() {
        val plan = AiWorkflowPlanner.plan(
            sources = listOf(ImportedAiFile(id = "note", name = "note.md", content = "# 标题\n内容")),
            definition = AiWorkflowDefinition(
                selectedSourceIds = setOf("note"),
                splitMode = AiSplitMode.WholeDocument,
                combineMode = AiCombineMode.Separate,
            ),
        )

        val context = AiWorkflowContextComposer.compose(
            plan = plan,
            definition = AiWorkflowDefinition(
                selectedSourceIds = setOf("note"),
                splitMode = AiSplitMode.WholeDocument,
                combineMode = AiCombineMode.Separate,
            ),
            parameters = AiGenerationParameters(
                groupCountRange = AiGroupCountRange.FiveToTen,
                candidatesPerGroup = 2,
                targetDeckId = 7L,
            ),
        )

        val systemPosition = context.text.indexOf("系统提示：")
        val listPosition = context.text.indexOf("文件列表：")
        val contentPosition = context.text.indexOf("文件内容：")
        val parameterPosition = context.text.indexOf("生成参数：")
        assertThat(systemPosition).isAtLeast(0)
        assertThat(listPosition).isGreaterThan(systemPosition)
        assertThat(contentPosition).isGreaterThan(listPosition)
        assertThat(parameterPosition).isGreaterThan(contentPosition)
        assertThat(context.text).contains("目标卡组=7")
        assertThat(context.text).contains("卡组数量范围=5-10")
    }

    @Test
    fun `context truncates material content but never drops the ending parameters`() {
        val plan = AiWorkflowPlanner.plan(
            sources = listOf(
                ImportedAiFile(
                    id = "large",
                    name = "large.md",
                    content = "知识".repeat(70_000),
                ),
            ),
            definition = AiWorkflowDefinition(
                selectedSourceIds = setOf("large"),
                splitMode = AiSplitMode.WholeDocument,
                combineMode = AiCombineMode.Separate,
            ),
        )

        val context = AiWorkflowContextComposer.compose(
            plan = plan,
            definition = AiWorkflowDefinition(selectedSourceIds = setOf("large")),
            parameters = AiGenerationParameters(),
        )

        assertThat(context.wasTruncated).isTrue()
        assertThat(context.text.length).isAtMost(AiWorkflowContextComposer.MAX_CONTEXT_CHARACTERS)
        assertThat(context.text).contains("生成参数：")
        assertThat(context.text).contains("内容因 100K 上限被截断")
    }
}
