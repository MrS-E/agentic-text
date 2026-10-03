package dev.agentictext.pipeline

import dev.agentictext.data.WritingProfileRepository
import dev.agentictext.domain.ClaimVerdict
import dev.agentictext.domain.DraftCandidate
import dev.agentictext.domain.DraftReview
import dev.agentictext.domain.ProfileId
import dev.agentictext.domain.WritingOutcome
import dev.agentictext.domain.WritingProfile
import dev.agentictext.domain.WritingTask
import dev.agentictext.verification.ClaimVerifier
import dev.agentictext.verification.DeterministicValidator
import dev.agentictext.verification.StandardDeterministicValidator

class UnknownWritingProfileException(profileId: ProfileId) :
    IllegalArgumentException("No writing profile found for id '${profileId.value}'")

class WritingPipeline(
    private val profileRepository: WritingProfileRepository,
    private val writer: WritingAgent,
    private val claimVerifier: ClaimVerifier,
    private val evaluator: DraftEvaluator,
    private val critic: DraftCritic,
    private val deterministicValidator: DeterministicValidator = StandardDeterministicValidator(),
    private val candidateRanker: CandidateRanker = WeightedCandidateRanker(),
) {
    suspend fun run(task: WritingTask): WritingOutcome {
        val profile = profileRepository.findById(task.profileId)
            ?: throw UnknownWritingProfileException(task.profileId)
        val requestedCount = task.candidateCount ?: profile.verification.candidateCount
        val generatedDrafts = writer.generateCandidates(task, profile, requestedCount).take(requestedCount)
        if (generatedDrafts.isEmpty()) {
            return WritingOutcome.Rejected(
                reason = "Writer returned no candidates",
                violations = emptyList(),
            )
        }

        val initialReviews = generatedDrafts.mapIndexed { index, text ->
            reviewCandidate(
                candidate = DraftCandidate(id = index, text = text),
                task = task,
                profile = profile,
            )
        }
        val candidatesPassingHardChecks = initialReviews.filter { review -> review.hardViolations.isEmpty() }
        val candidatesToRank = candidatesPassingHardChecks.ifEmpty { initialReviews }
        var currentReview = selectCandidate(candidatesToRank, profile)
            ?: return WritingOutcome.Rejected(
                reason = "No candidates were available for selection",
                violations = emptyList(),
            )
        var revisionCount = 0

        while (true) {
            if (isAcceptable(currentReview, profile)) {
                val finalReview = reviewCandidate(
                    candidate = currentReview.candidate,
                    task = task,
                    profile = profile,
                )
                if (isAcceptable(finalReview, profile)) {
                    val evaluation = requireNotNull(finalReview.evaluation) {
                        "An accepted draft must have a final evaluation"
                    }
                    return WritingOutcome.Accepted(
                        text = finalReview.candidate.text,
                        profileId = profile.skill.id,
                        evaluation = evaluation,
                        claimVerifications = finalReview.claimVerifications,
                        revisionCount = revisionCount,
                    )
                }
                currentReview = finalReview
            }

            if (
                currentReview.confidence < profile.verification.escalationConfidence &&
                currentReview.hardViolations.isEmpty() &&
                !currentReview.hasFactualIssues &&
                currentReview.evaluation?.passes(profile.verification.rubric) == true
            ) {
                return WritingOutcome.Escalated(
                    review = currentReview,
                    reason = "Verifier confidence is below the escalation threshold",
                    revisionCount = revisionCount,
                )
            }

            if (revisionCount >= profile.verification.maxRevisionCycles) {
                return terminalOutcome(currentReview, revisionCount, profile)
            }

            val critique = critic.critique(task, profile, currentReview)
            if (critique.issues.isEmpty()) {
                return terminalOutcome(
                    review = currentReview,
                    revisionCount = revisionCount,
                    profile = profile,
                    fallbackReason = "The critic could not identify a concrete, actionable correction",
                )
            }

            val revisedText = writer.revise(task, profile, currentReview.candidate, critique)
            val revisedCandidate = currentReview.candidate.copy(
                text = revisedText,
                revision = currentReview.candidate.revision + 1,
            )
            currentReview = reviewCandidate(revisedCandidate, task, profile)
            revisionCount++
        }
    }

    private suspend fun reviewCandidate(
        candidate: DraftCandidate,
        task: WritingTask,
        profile: WritingProfile,
    ): DraftReview {
        val hardViolations = deterministicValidator.validate(candidate.text, task, profile)
        if (hardViolations.isNotEmpty()) {
            return DraftReview(
                candidate = candidate,
                hardViolations = hardViolations,
                claimVerifications = emptyList(),
                evaluation = null,
            )
        }

        val claimVerifications = claimVerifier.verify(candidate.text, task.sources)
        val evaluation = evaluator.evaluate(task, profile, candidate, claimVerifications)
        return DraftReview(
            candidate = candidate,
            hardViolations = emptyList(),
            claimVerifications = claimVerifications,
            evaluation = evaluation,
        )
    }

    private fun selectCandidate(reviews: List<DraftReview>, profile: WritingProfile): DraftReview? {
        val passingReviews = reviews.filter { review -> isAcceptable(review, profile) }
        return candidateRanker.rank(passingReviews.ifEmpty { reviews }, profile.verification.rubric)
    }

    private fun isAcceptable(review: DraftReview, profile: WritingProfile): Boolean {
        val evaluation = review.evaluation ?: return false
        return review.hardViolations.isEmpty() &&
            review.claimVerifications.all { verification ->
                verification.verdict == ClaimVerdict.SUPPORTED && verification.evidence.isNotEmpty()
            } &&
            evaluation.passes(profile.verification.rubric) &&
            review.confidence >= profile.verification.acceptanceConfidence
    }

    private fun terminalOutcome(
        review: DraftReview,
        revisionCount: Int,
        profile: WritingProfile,
        fallbackReason: String? = null,
    ): WritingOutcome = if (review.hardViolations.isNotEmpty()) {
        WritingOutcome.Rejected(
            reason = fallbackReason ?: "Draft still fails deterministic verification after revision",
            violations = review.hardViolations,
        )
    } else {
        WritingOutcome.Escalated(
            review = review,
            reason = fallbackReason ?: escalationReason(review, profile),
            revisionCount = revisionCount,
        )
    }

    private fun escalationReason(review: DraftReview, profile: WritingProfile): String = when {
        review.hasFactualIssues -> "Factual claims remain unsupported, contradicted, or uncertain"
        review.confidence < profile.verification.escalationConfidence ->
            "Verifier confidence is below the escalation threshold"
        review.evaluation?.passes(profile.verification.rubric) == true &&
            review.confidence < profile.verification.acceptanceConfidence ->
            "Verifier confidence is below the acceptance threshold"
        else -> "Draft did not satisfy the quality rubric after the allowed revision cycles"
    }
}