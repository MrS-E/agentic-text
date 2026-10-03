package dev.agentictext.pipeline

import dev.agentictext.domain.Critique
import dev.agentictext.domain.ClaimVerification
import dev.agentictext.domain.DraftCandidate
import dev.agentictext.domain.DraftReview
import dev.agentictext.domain.QualityRubric
import dev.agentictext.domain.QualityEvaluation
import dev.agentictext.domain.WritingProfile
import dev.agentictext.domain.WritingTask

interface WritingAgent {
    suspend fun generateCandidates(
        task: WritingTask,
        profile: WritingProfile,
        candidateCount: Int,
    ): List<String>

    suspend fun revise(
        task: WritingTask,
        profile: WritingProfile,
        candidate: DraftCandidate,
        critique: Critique,
    ): String
}

fun interface DraftEvaluator {
    suspend fun evaluate(
        task: WritingTask,
        profile: WritingProfile,
        candidate: DraftCandidate,
        claimVerifications: List<ClaimVerification>,
    ): QualityEvaluation
}

fun interface DraftCritic {
    suspend fun critique(
        task: WritingTask,
        profile: WritingProfile,
        review: DraftReview,
    ): Critique
}

fun interface CandidateRanker {
    fun rank(reviews: List<DraftReview>, rubric: QualityRubric): DraftReview?
}