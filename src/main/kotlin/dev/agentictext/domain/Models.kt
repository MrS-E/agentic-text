package dev.agentictext.domain

@JvmInline
value class ProfileId(val value: String) {
    init {
        require(value.isNotBlank()) { "Profile id must not be blank" }
    }
}

data class SkillDefinition(
    val id: ProfileId,
    val name: String,
    val description: String,
    val instructions: String,
)

data class WritingProfile(
    val skill: SkillDefinition,
    val verification: VerificationProfile,
) {
    init {
        require(skill.id == verification.id) { "Skill and verification profile ids must match" }
    }
}

data class VerificationProfile(
    val id: ProfileId,
    val rules: DeterministicRules = DeterministicRules(),
    val rubric: QualityRubric = QualityRubric.standard(),
    val acceptanceConfidence: Double = 0.85,
    val escalationConfidence: Double = 0.55,
    val candidateCount: Int = 3,
    val maxRevisionCycles: Int = 1,
) {
    init {
        require(acceptanceConfidence in 0.0..1.0) { "Acceptance confidence must be between 0 and 1" }
        require(escalationConfidence in 0.0..acceptanceConfidence) {
            "Escalation confidence must be between 0 and acceptance confidence"
        }
        require(candidateCount in 1..8) { "Candidate count must be between 1 and 8" }
        require(maxRevisionCycles >= 0) { "Revision count must not be negative" }
    }
}

data class DeterministicRules(
    val requiredSections: List<String> = emptyList(),
    val requiredTerms: Set<String> = emptySet(),
    val exactTerms: Set<String> = emptySet(),
    val forbiddenTerms: Set<String> = emptySet(),
    val forbiddenAlternatives: Map<String, Set<String>> = emptyMap(),
    val exactPhrases: Set<String> = emptySet(),
    val bannedPhrases: Set<String> = emptySet(),
    val forbiddenCharacters: Set<Char> = emptySet(),
    val minWords: Int? = null,
    val maxWords: Int? = null,
    val preserveNumbersFromSources: Boolean = false,
    val preserveCodeFromSources: Boolean = false,
    val preserveUrlsFromSources: Boolean = false,
    val preserveModalVerbsFromSources: Boolean = false,
    val rejectDuplicateSentences: Boolean = true,
) {
    init {
        require(minWords == null || minWords >= 0) { "Minimum word count must not be negative" }
        require(maxWords == null || maxWords >= 0) { "Maximum word count must not be negative" }
        require(minWords == null || maxWords == null || minWords <= maxWords) {
            "Minimum word count must not exceed maximum word count"
        }
    }
}

data class WritingTask(
    val prompt: String,
    val profileId: ProfileId,
    val requirements: WritingRequirements = WritingRequirements(),
    val sources: List<SourceDocument> = emptyList(),
    val candidateCount: Int? = null,
) {
    init {
        require(prompt.isNotBlank()) { "Writing prompt must not be blank" }
        require(candidateCount == null || candidateCount in 1..8) { "Candidate count must be between 1 and 8" }
    }
}

data class WritingRequirements(
    val requiredSections: List<String> = emptyList(),
    val requiredTerms: Set<String> = emptySet(),
    val exactTerms: Set<String> = emptySet(),
    val forbiddenTerms: Set<String> = emptySet(),
    val forbiddenAlternatives: Map<String, Set<String>> = emptyMap(),
    val exactPhrases: Set<String> = emptySet(),
    val minWords: Int? = null,
    val maxWords: Int? = null,
    val preserveNumbers: Boolean = false,
    val preserveCode: Boolean = false,
    val preserveUrls: Boolean = false,
    val preserveModalVerbs: Boolean = false,
    val requireValidJson: Boolean = false,
) {
    init {
        require(minWords == null || minWords >= 0) { "Minimum word count must not be negative" }
        require(maxWords == null || maxWords >= 0) { "Maximum word count must not be negative" }
        require(minWords == null || maxWords == null || minWords <= maxWords) {
            "Minimum word count must not exceed maximum word count"
        }
    }
}

data class SourceDocument(
    val id: String,
    val title: String,
    val content: String,
    val uri: String? = null,
)

data class DraftCandidate(
    val id: Int,
    val text: String,
    val revision: Int = 0,
)

data class HardViolation(
    val rule: String,
    val message: String,
    val expected: String? = null,
    val found: String? = null,
)

data class Evidence(
    val sourceId: String,
    val excerpt: String,
    val location: String? = null,
)

data class FactualClaim(
    val id: String,
    val text: String,
    val startIndex: Int? = null,
    val endIndex: Int? = null,
)

enum class ClaimVerdict {
    SUPPORTED,
    CONTRADICTED,
    UNVERIFIABLE,
}

data class ClaimVerification(
    val claim: FactualClaim,
    val verdict: ClaimVerdict,
    val evidence: List<Evidence>,
    val confidence: Double,
    val explanation: String,
) {
    init {
        require(confidence in 0.0..1.0) { "Claim confidence must be between 0 and 1" }
    }
}

enum class QualityCriterion {
    TASK_FULFILLMENT,
    FACTUAL_ACCURACY,
    COMPLETENESS,
    STYLE,
    SAFETY,
    ORIGINALITY,
    USEFULNESS,
    SOURCE_FIDELITY,
}

data class RubricCriterion(
    val criterion: QualityCriterion,
    val weight: Double,
    val minimumScore: Int = 3,
) {
    init {
        require(weight >= 0.0) { "Rubric weight must not be negative" }
        require(minimumScore in 0..5) { "Minimum criterion score must be between 0 and 5" }
    }
}

data class QualityRubric(
    val criteria: List<RubricCriterion>,
    val minimumWeightedScore: Double = 4.0,
) {
    init {
        require(criteria.isNotEmpty()) { "A rubric must contain at least one criterion" }
        require(criteria.map { it.criterion }.distinct().size == criteria.size) {
            "Rubric criteria must be unique"
        }
        require(criteria.sumOf { it.weight } > 0.0) { "Rubric weights must have a positive sum" }
        require(minimumWeightedScore in 0.0..5.0) { "Weighted score threshold must be between 0 and 5" }
    }

    fun weightedScore(evaluation: QualityEvaluation): Double {
        val scores = evaluation.criterionScores.associateBy { it.criterion }
        val totalWeight = criteria.sumOf { it.weight }
        return criteria.sumOf { criterion ->
            criterion.weight * (scores[criterion.criterion]?.score ?: 0)
        } / totalWeight
    }

    companion object {
        fun standard(): QualityRubric = QualityRubric(
            criteria = listOf(
                RubricCriterion(QualityCriterion.TASK_FULFILLMENT, weight = 0.18, minimumScore = 4),
                RubricCriterion(QualityCriterion.FACTUAL_ACCURACY, weight = 0.22, minimumScore = 4),
                RubricCriterion(QualityCriterion.COMPLETENESS, weight = 0.15, minimumScore = 3),
                RubricCriterion(QualityCriterion.STYLE, weight = 0.15, minimumScore = 3),
                RubricCriterion(QualityCriterion.SAFETY, weight = 0.10, minimumScore = 4),
                RubricCriterion(QualityCriterion.ORIGINALITY, weight = 0.05, minimumScore = 3),
                RubricCriterion(QualityCriterion.USEFULNESS, weight = 0.08, minimumScore = 3),
                RubricCriterion(QualityCriterion.SOURCE_FIDELITY, weight = 0.07, minimumScore = 4),
            ),
        )
    }
}

data class EvaluationEvidence(
    val text: String,
    val sourceId: String? = null,
    val location: String? = null,
)

data class CriterionEvaluation(
    val criterion: QualityCriterion,
    val score: Int,
    val rationale: String,
    val evidence: List<EvaluationEvidence>,
) {
    init {
        require(score in 0..5) { "Criterion score must be between 0 and 5" }
    }
}

data class QualityEvaluation(
    val criterionScores: List<CriterionEvaluation>,
    val missingRequirements: List<String>,
    val unsupportedClaims: List<String>,
    val confidence: Double,
    val evaluatorReportedPass: Boolean? = null,
) {
    init {
        require(confidence in 0.0..1.0) { "Evaluation confidence must be between 0 and 1" }
        require(criterionScores.map { it.criterion }.distinct().size == criterionScores.size) {
            "Evaluation criteria must be unique"
        }
    }

    fun passes(rubric: QualityRubric): Boolean {
        if (missingRequirements.isNotEmpty() || unsupportedClaims.isNotEmpty()) return false

        val scores = criterionScores.associateBy { it.criterion }
        if (!rubric.criteria.all { criterion ->
                val result = scores[criterion.criterion]
                result != null && result.score >= criterion.minimumScore &&
                    result.rationale.isNotBlank() && result.evidence.any { it.text.isNotBlank() }
            }
        ) {
            return false
        }

        return rubric.weightedScore(this) >= rubric.minimumWeightedScore
    }
}

data class DraftReview(
    val candidate: DraftCandidate,
    val hardViolations: List<HardViolation>,
    val claimVerifications: List<ClaimVerification>,
    val evaluation: QualityEvaluation?,
) {
    val confidence: Double
        get() {
            val evaluationConfidence = evaluation?.confidence ?: return 0.0
            val claimConfidence = claimVerifications.minOfOrNull { it.confidence } ?: 1.0
            return minOf(evaluationConfidence, claimConfidence)
        }

    val hasFactualIssues: Boolean
        get() = claimVerifications.any {
            it.verdict != ClaimVerdict.SUPPORTED || it.evidence.isEmpty()
        } || evaluation?.unsupportedClaims.orEmpty().isNotEmpty()
}

enum class IssueSeverity {
    MINOR,
    MAJOR,
    CRITICAL,
}

data class RevisionIssue(
    val location: String,
    val problem: String,
    val severity: IssueSeverity,
    val suggestedFix: String,
)

data class Critique(val issues: List<RevisionIssue>)

sealed interface WritingOutcome {
    data class Accepted(
        val text: String,
        val profileId: ProfileId,
        val evaluation: QualityEvaluation,
        val claimVerifications: List<ClaimVerification>,
        val revisionCount: Int,
    ) : WritingOutcome

    data class Rejected(
        val reason: String,
        val violations: List<HardViolation>,
    ) : WritingOutcome

    data class Escalated(
        val review: DraftReview,
        val reason: String,
        val revisionCount: Int,
    ) : WritingOutcome
}