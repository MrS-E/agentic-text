package dev.agentictext

import dev.agentictext.domain.DraftCandidate
import dev.agentictext.domain.ProfileId
import dev.agentictext.domain.QualityCriterion
import dev.agentictext.domain.QualityRubric
import dev.agentictext.domain.SkillDefinition
import dev.agentictext.domain.VerificationProfile
import dev.agentictext.domain.WritingProfile
import dev.agentictext.domain.WritingTask
import dev.agentictext.llm.InvalidAgentResponseException
import dev.agentictext.llm.ModelPrompt
import dev.agentictext.llm.PromptedDraftEvaluator
import dev.agentictext.llm.TextGenerationClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PromptedDraftEvaluatorTest {
    @Test
    fun `given strict JSON with evaluator pass true but a failing criterion when decoded then the rubric rejects it`() = runTest {
        val rubric = QualityRubric.standard()
        val criteria = rubric.criteria.joinToString(separator = ",", prefix = "[", postfix = "]") { criterion ->
            val score = if (criterion.criterion == QualityCriterion.TASK_FULFILLMENT) 1 else 5
            """{"criterion":"${criterion.criterion.name.lowercase()}","score":$score,"rationale":"Evidence-backed score","evidence":[{"text":"A draft excerpt","source_id":"docs","location":"paragraph 1"}]}"""
        }
        val jsonResponse = """{"criteria":$criteria,"missing_requirements":[],"unsupported_claims":[],"pass":true,"confidence":0.98}"""
        var capturedPrompt: ModelPrompt? = null
        val client = TextGenerationClient { prompt ->
            capturedPrompt = prompt
            jsonResponse
        }
        val skill = SkillDefinition(ProfileId("test-profile"), "Test", "Test profile", "Use a concise style.")
        val profile = WritingProfile(skill, VerificationProfile(id = skill.id, rubric = rubric))
        val task = WritingTask("Write a supported report.", skill.id)

        val evaluation = PromptedDraftEvaluator(client).evaluate(
            task = task,
            profile = profile,
            candidate = DraftCandidate(0, "A draft excerpt"),
            claimVerifications = emptyList(),
        )

        assertEquals(true, evaluation.evaluatorReportedPass)
        assertFalse(evaluation.passes(rubric))
        assertEquals(rubric.criteria.size, evaluation.criterionScores.size)
        val prompt = assertNotNull(capturedPrompt)
        assertEquals(
            "Use a concise style.",
            prompt.systemMessage.substringAfter("Profile instructions:\n").substringBefore("\nRubric:"),
        )
    }

    @Test
    fun `given a model returns a score outside the rubric range when parsed then a stage-specific response error is raised`() = runTest {
        val jsonResponse = """{"criteria":[{"criterion":"task_fulfillment","score":6,"rationale":"Invalid score","evidence":[{"text":"quote"}]}],"confidence":0.9}"""
        val client = TextGenerationClient { jsonResponse }
        val skill = SkillDefinition(ProfileId("test-profile"), "Test", "Test profile", "Use a concise style.")
        val profile = WritingProfile(skill, VerificationProfile(id = skill.id))
        var invalidResponseRaised = false

        try {
            PromptedDraftEvaluator(client).evaluate(
                task = WritingTask("Write a supported report.", skill.id),
                profile = profile,
                candidate = DraftCandidate(0, "A draft excerpt"),
                claimVerifications = emptyList(),
            )
        } catch (_: InvalidAgentResponseException) {
            invalidResponseRaised = true
        }

        assertTrue(invalidResponseRaised)
    }
}