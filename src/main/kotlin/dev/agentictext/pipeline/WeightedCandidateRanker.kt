package dev.agentictext.pipeline

import dev.agentictext.domain.ClaimVerdict
import dev.agentictext.domain.DraftReview
import dev.agentictext.domain.QualityRubric

class WeightedCandidateRanker : CandidateRanker {
    override fun rank(reviews: List<DraftReview>, rubric: QualityRubric): DraftReview? =
        reviews.maxWithOrNull(
            compareBy<DraftReview> { review -> rankScore(review, rubric) }
                .thenBy { review -> -review.candidate.id },
        )

    private fun rankScore(review: DraftReview, rubric: QualityRubric): Double {
        val qualityScore = review.evaluation?.let(rubric::weightedScore) ?: 0.0
        val hardPenalty = review.hardViolations.size * HARD_VIOLATION_PENALTY
        val factualPenalty = review.claimVerifications.sumOf { verification ->
            when (verification.verdict) {
                ClaimVerdict.SUPPORTED -> 0.0
                ClaimVerdict.UNVERIFIABLE -> UNVERIFIABLE_CLAIM_PENALTY
                ClaimVerdict.CONTRADICTED -> CONTRADICTED_CLAIM_PENALTY
            }
        }
        return qualityScore + review.confidence * CONFIDENCE_WEIGHT - hardPenalty - factualPenalty
    }

    private companion object {
        const val HARD_VIOLATION_PENALTY = 10.0
        const val UNVERIFIABLE_CLAIM_PENALTY = 1.0
        const val CONTRADICTED_CLAIM_PENALTY = 2.0
        const val CONFIDENCE_WEIGHT = 0.25
    }
}