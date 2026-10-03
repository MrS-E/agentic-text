package dev.agentictext

import dev.agentictext.data.WritingProfileRepository
import dev.agentictext.domain.ClaimVerification
import dev.agentictext.domain.CriterionEvaluation
import dev.agentictext.domain.Critique
import dev.agentictext.domain.DraftCandidate
import dev.agentictext.domain.EvaluationEvidence
import dev.agentictext.domain.IssueSeverity
import dev.agentictext.domain.ProfileId
import dev.agentictext.domain.QualityCriterion
import dev.agentictext.domain.QualityEvaluation
import dev.agentictext.domain.QualityRubric
import dev.agentictext.domain.RevisionIssue
import dev.agentictext.domain.RubricCriterion
import dev.agentictext.domain.SkillDefinition
import dev.agentictext.domain.VerificationProfile
import dev.agentictext.domain.WritingOutcome
import dev.agentictext.domain.WritingProfile
import dev.agentictext.domain.WritingRequirements
import dev.agentictext.domain.WritingTask
import dev.agentictext.pipeline.DraftCritic
import dev.agentictext.pipeline.DraftEvaluator
import dev.agentictext.pipeline.WritingAgent
import dev.agentictext.pipeline.WritingPipeline
import dev.agentictext.verification.ClaimVerifier
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WritingPipelineTest {
    @Test
    fun `given several passing candidates when the pipeline runs then the top candidate is reverified and accepted`() = runTest {
        val profile = profile(maxRevisionCycles = 1)
        val writer = FixedWritingAgent(listOf("draft-weak", "draft-best", "draft-middle"))
        val evaluationCalls = mutableMapOf<String, Int>()
        val evaluator = DraftEvaluator { _, _, candidate, _ ->
            evaluationCalls[candidate.text] = evaluationCalls.getOrDefault(candidate.text, 0) + 1
            evaluation(
                score = if (candidate.text == "draft-best") 5 else 4,
                confidence = 0.92,
            )
        }
        var critiqueCalls = 0
        val result = pipeline(
            profile = profile,
            writer = writer,
            evaluator = evaluator,
            critic = DraftCritic { _, _, _ ->
                critiqueCalls++
                Critique(emptyList())
            },
        ).run(task(profile))

        val accepted = assertIs<WritingOutcome.Accepted>(result)
        assertEquals("draft-best", accepted.text)
        assertEquals(0, accepted.revisionCount)
        assertEquals(3, writer.requestedCandidateCount)
        assertEquals(2, evaluationCalls["draft-best"])
        assertEquals(0, critiqueCalls)
    }

    @Test
    fun `given a deterministic terminology failure when candidates are filtered then expensive evaluation is skipped`() = runTest {
        val profile = profile(maxRevisionCycles = 0)
        val writer = FixedWritingAgent(listOf("login credential is used."))
        val evaluator = mockk<DraftEvaluator>()
        val request = task(
            profile,
            WritingRequirements(
                requiredTerms = setOf("authentication token"),
                forbiddenAlternatives = mapOf("authentication token" to setOf("login credential")),
            ),
        )

        val result = pipeline(profile, writer, evaluator).run(request)

        val rejected = assertIs<WritingOutcome.Rejected>(result)
        assertTrue(rejected.violations.any { it.rule == "forbidden_alternative" })
        coVerify(exactly = 0) { evaluator.evaluate(any(), any(), any(), any()) }
    }

    @Test
    fun `given a correctable hard failure when one revision is allowed then the revised draft is accepted`() = runTest {
        val profile = profile(maxRevisionCycles = 1)
        val writer = FixedWritingAgent(
            candidates = listOf("login credential is used."),
            revisedText = "The authentication token is used.",
        )
        val critique = Critique(
            listOf(
                RevisionIssue(
                    location = "paragraph 1",
                    problem = "Approved terminology was replaced with a synonym.",
                    severity = IssueSeverity.MAJOR,
                    suggestedFix = "Use authentication token exactly.",
                ),
            ),
        )
        val result = pipeline(
            profile,
            writer,
            evaluator = DraftEvaluator { _, _, _, _ -> evaluation(score = 5, confidence = 0.91) },
            critic = DraftCritic { _, _, _ -> critique },
        ).run(
            task(
                profile,
                WritingRequirements(
                    requiredTerms = setOf("authentication token"),
                    forbiddenAlternatives = mapOf("authentication token" to setOf("login credential")),
                ),
            ),
        )

        val accepted = assertIs<WritingOutcome.Accepted>(result)
        assertEquals("The authentication token is used.", accepted.text)
        assertEquals(1, accepted.revisionCount)
        assertEquals(1, writer.revisionCalls)
    }

    @Test
    fun `given an unchanged hard failure when the revision budget is exhausted then the pipeline rejects it`() = runTest {
        val profile = profile(maxRevisionCycles = 1)
        val writer = FixedWritingAgent(
            candidates = listOf("login credential is used."),
            revisedText = "login credential is still used.",
        )
        val result = pipeline(
            profile = profile,
            writer = writer,
            evaluator = DraftEvaluator { _, _, _, _ -> error("Hard-failed drafts must not be evaluated") },
            critic = DraftCritic { _, _, _ ->
                Critique(listOf(RevisionIssue("paragraph 1", "Wrong term", IssueSeverity.MAJOR, "Fix the term")))
            },
        ).run(
            task(
                profile,
                WritingRequirements(requiredTerms = setOf("authentication token")),
            ),
        )

        assertIs<WritingOutcome.Rejected>(result)
        assertEquals(1, writer.revisionCalls)
    }

    @Test
    fun `given an evaluator reports a passing score with low confidence when the result is checked then human review is required`() = runTest {
        val profile = profile(maxRevisionCycles = 1, escalationConfidence = 0.55)
        val writer = FixedWritingAgent(listOf("uncertain draft"))
        var critiqueCalls = 0
        val result = pipeline(
            profile = profile,
            writer = writer,
            evaluator = DraftEvaluator { _, _, _, _ ->
                evaluation(score = 5, confidence = 0.4, reportedPass = true)
            },
            critic = DraftCritic { _, _, _ ->
                critiqueCalls++
                Critique(emptyList())
            },
        ).run(task(profile))

        val escalated = assertIs<WritingOutcome.Escalated>(result)
        assertTrue(escalated.reason.contains("confidence"))
        assertEquals(0, critiqueCalls)
    }

    @Test
    fun `given the evaluator reports pass but its criterion score fails when the pipeline runs then the draft is not accepted`() = runTest {
        val profile = profile(maxRevisionCycles = 0)
        val result = pipeline(
            profile = profile,
            writer = FixedWritingAgent(listOf("low score")),
            evaluator = DraftEvaluator { _, _, _, _ ->
                evaluation(score = 2, confidence = 0.96, reportedPass = true)
            },
        ).run(task(profile))

        assertIs<WritingOutcome.Escalated>(result)
    }

    @Test
    fun `given the rubric passes but confidence is below acceptance when revisions are exhausted then escalation explains confidence`() = runTest {
        val profile = profile(maxRevisionCycles = 0, escalationConfidence = 0.55)
        val result = pipeline(
            profile = profile,
            writer = FixedWritingAgent(listOf("strong but uncertain draft")),
            evaluator = DraftEvaluator { _, _, _, _ -> evaluation(score = 5, confidence = 0.7) },
        ).run(task(profile))

        val escalated = assertIs<WritingOutcome.Escalated>(result)
        assertTrue(escalated.reason.contains("acceptance threshold"))
    }

    @Test
    fun `given final verification detects a quality regression when the pipeline runs then it escalates the actual draft`() = runTest {
        val profile = profile(maxRevisionCycles = 0)
        var evaluationCalls = 0
        val result = pipeline(
            profile = profile,
            writer = FixedWritingAgent(listOf("candidate text")),
            evaluator = DraftEvaluator { _, _, _, _ ->
                evaluationCalls++
                if (evaluationCalls == 1) {
                    evaluation(score = 5, confidence = 0.95)
                } else {
                    evaluation(score = 2, confidence = 0.95)
                }
            },
        ).run(task(profile))

        val escalated = assertIs<WritingOutcome.Escalated>(result)
        assertEquals(2, evaluationCalls)
        assertEquals("candidate text", escalated.review.candidate.text)
    }

    private fun pipeline(
        profile: WritingProfile,
        writer: WritingAgent,
        evaluator: DraftEvaluator,
        critic: DraftCritic = DraftCritic { _, _, _ -> Critique(emptyList()) },
    ) = WritingPipeline(
        profileRepository = object : WritingProfileRepository {
            override suspend fun findById(id: ProfileId): WritingProfile? =
                profile.takeIf { candidate -> candidate.skill.id == id }
        },
        writer = writer,
        claimVerifier = emptyClaimVerifier(),
        evaluator = evaluator,
        critic = critic,
    )

    private fun emptyClaimVerifier() = object : ClaimVerifier {
        override suspend fun verify(
            draft: String,
            sources: List<dev.agentictext.domain.SourceDocument>,
        ): List<ClaimVerification> = emptyList()
    }

    private fun profile(
        maxRevisionCycles: Int,
        escalationConfidence: Double = 0.55,
    ): WritingProfile {
        val skill = SkillDefinition(
            id = ProfileId("test-profile"),
            name = "Test",
            description = "Test profile",
            instructions = "Write clearly.",
        )
        return WritingProfile(
            skill = skill,
            verification = VerificationProfile(
                id = skill.id,
                rubric = QualityRubric(
                    criteria = listOf(
                        RubricCriterion(QualityCriterion.TASK_FULFILLMENT, weight = 1.0, minimumScore = 4),
                    ),
                    minimumWeightedScore = 4.0,
                ),
                acceptanceConfidence = 0.85,
                escalationConfidence = escalationConfidence,
                maxRevisionCycles = maxRevisionCycles,
            ),
        )
    }

    private fun task(
        profile: WritingProfile,
        requirements: WritingRequirements = WritingRequirements(),
    ) = WritingTask(
        prompt = "Write a short test draft.",
        profileId = profile.skill.id,
        requirements = requirements,
    )

    private fun evaluation(
        score: Int,
        confidence: Double,
        reportedPass: Boolean? = null,
    ) = QualityEvaluation(
        criterionScores = listOf(
            CriterionEvaluation(
                criterion = QualityCriterion.TASK_FULFILLMENT,
                score = score,
                rationale = "The draft addresses the request.",
                evidence = listOf(EvaluationEvidence(text = "A relevant sentence from the draft.")),
            ),
        ),
        missingRequirements = emptyList(),
        unsupportedClaims = emptyList(),
        confidence = confidence,
        evaluatorReportedPass = reportedPass,
    )

    private class FixedWritingAgent(
        private val candidates: List<String>,
        private val revisedText: String = "revised text",
    ) : WritingAgent {
        var requestedCandidateCount: Int = 0
            private set
        var revisionCalls: Int = 0
            private set

        override suspend fun generateCandidates(
            task: WritingTask,
            profile: WritingProfile,
            candidateCount: Int,
        ): List<String> {
            requestedCandidateCount = candidateCount
            return candidates
        }

        override suspend fun revise(
            task: WritingTask,
            profile: WritingProfile,
            candidate: DraftCandidate,
            critique: Critique,
        ): String {
            revisionCalls++
            return revisedText
        }
    }
}