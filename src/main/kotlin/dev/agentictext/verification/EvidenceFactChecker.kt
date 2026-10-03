package dev.agentictext.verification

import dev.agentictext.domain.ClaimVerdict
import dev.agentictext.domain.ClaimVerification
import dev.agentictext.domain.Evidence
import dev.agentictext.domain.FactualClaim
import dev.agentictext.domain.SourceDocument
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

interface ClaimExtractor {
    suspend fun extract(draft: String): List<FactualClaim>
}

fun interface EvidenceRetriever {
    suspend fun retrieve(claim: FactualClaim, sources: List<SourceDocument>): List<Evidence>
}

fun interface ClaimJudge {
    suspend fun judge(claim: FactualClaim, evidence: List<Evidence>): ClaimJudgment
}

data class ClaimJudgment(
    val verdict: ClaimVerdict,
    val confidence: Double,
    val explanation: String,
) {
    init {
        require(confidence in 0.0..1.0) { "Claim judgment confidence must be between 0 and 1" }
    }
}

interface ClaimVerifier {
    suspend fun verify(draft: String, sources: List<SourceDocument>): List<ClaimVerification>
}

class EvidenceBackedClaimVerifier(
    private val claimExtractor: ClaimExtractor,
    private val evidenceRetriever: EvidenceRetriever,
    private val claimJudge: ClaimJudge,
) : ClaimVerifier {
    override suspend fun verify(draft: String, sources: List<SourceDocument>): List<ClaimVerification> {
        require(sources.map { source -> source.id }.distinct().size == sources.size) {
            "Source document ids must be unique for evidence verification"
        }
        val claims = claimExtractor.extract(draft)
        val documentsById = sources.associateBy { source -> source.id }
        return claims.map { claim ->
            val evidence = evidenceRetriever.retrieve(claim, sources)
                .filter { item ->
                    item.excerpt.isNotBlank() &&
                        documentsById[item.sourceId]?.content?.contains(item.excerpt) == true
                }
                .distinctBy { item -> Triple(item.sourceId, item.excerpt, item.location) }
            val judgment = claimJudge.judge(claim, evidence)
            val unsupportedWithoutEvidence = judgment.verdict != ClaimVerdict.UNVERIFIABLE && evidence.isEmpty()

            ClaimVerification(
                claim = claim,
                verdict = if (unsupportedWithoutEvidence) ClaimVerdict.UNVERIFIABLE else judgment.verdict,
                evidence = evidence,
                confidence = if (unsupportedWithoutEvidence) 0.0 else judgment.confidence,
                explanation = if (unsupportedWithoutEvidence) {
                    "Verifier returned a conclusive verdict without a matching source excerpt; evidence is required."
                } else {
                    judgment.explanation
                },
            )
        }
    }
}

class KeywordEvidenceRetriever(
    private val maxResults: Int = 3,
    private val excerptLength: Int = 240,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : EvidenceRetriever {
    init {
        require(maxResults > 0) { "Maximum evidence result count must be positive" }
        require(excerptLength > 0) { "Evidence excerpt length must be positive" }
    }

    override suspend fun retrieve(
        claim: FactualClaim,
        sources: List<SourceDocument>,
    ): List<Evidence> = withContext(dispatcher) {
        val claimTerms = tokenize(claim.text)
        if (claimTerms.isEmpty()) return@withContext emptyList()

        sources.mapNotNull { source ->
            val matches = tokenPattern.findAll(source.content)
                .filter { match -> match.value.lowercase(Locale.ROOT) in claimTerms }
                .toList()
            if (matches.isEmpty()) {
                null
            } else {
                val overlap = matches.map { match -> match.value.lowercase(Locale.ROOT) }.distinct().size
                val firstMatch = matches.first()
                val start = (firstMatch.range.first - excerptLength / 2).coerceAtLeast(0)
                val end = (start + excerptLength).coerceAtMost(source.content.length)
                val excerpt = source.content.substring(start, end).trim()
                RankedEvidence(
                    evidence = Evidence(
                        sourceId = source.id,
                        excerpt = excerpt,
                        location = "${source.title}:chars $start-$end",
                    ),
                    overlap = overlap,
                )
            }
        }
            .sortedByDescending(RankedEvidence::overlap)
            .take(maxResults)
            .map(RankedEvidence::evidence)
    }

    private fun tokenize(text: String): Set<String> = tokenPattern.findAll(text)
        .map { match -> match.value.lowercase(Locale.ROOT) }
        .filter { token -> token.length >= 3 }
        .toSet()

    private data class RankedEvidence(
        val evidence: Evidence,
        val overlap: Int,
    )

    private companion object {
        val tokenPattern = Regex("[\\p{L}\\p{N}]{3,}")
    }
}