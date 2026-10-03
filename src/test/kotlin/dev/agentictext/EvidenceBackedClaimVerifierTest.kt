package dev.agentictext

import dev.agentictext.domain.ClaimVerdict
import dev.agentictext.domain.Evidence
import dev.agentictext.domain.FactualClaim
import dev.agentictext.domain.SourceDocument
import dev.agentictext.verification.ClaimExtractor
import dev.agentictext.verification.ClaimJudge
import dev.agentictext.verification.ClaimJudgment
import dev.agentictext.verification.EvidenceBackedClaimVerifier
import dev.agentictext.verification.EvidenceRetriever
import dev.agentictext.verification.KeywordEvidenceRetriever
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EvidenceBackedClaimVerifierTest {
    @Test
    fun `given a judge says supported without matching source evidence when verified then the claim is unverifiable`() = runTest {
        val claim = FactualClaim(id = "claim-1", text = "The service supports offline mode.")
        val source = SourceDocument(id = "docs", title = "Documentation", content = "Offline mode is not documented.")
        val verifier = verifier(
            claim = claim,
            retriever = EvidenceRetriever { _, _ ->
                listOf(Evidence(sourceId = "docs", excerpt = "Fabricated source excerpt"))
            },
            judgment = ClaimJudgment(ClaimVerdict.SUPPORTED, confidence = 0.99, explanation = "Said supported"),
        )

        val result = verifier.verify(claim.text, listOf(source)).single()

        assertEquals(ClaimVerdict.UNVERIFIABLE, result.verdict)
        assertTrue(result.evidence.isEmpty())
        assertEquals(0.0, result.confidence)
    }

    @Test
    fun `given a judge says contradicted without matching source evidence when verified then the claim is unverifiable`() = runTest {
        val claim = FactualClaim(id = "claim-1", text = "The service supports offline mode.")
        val source = SourceDocument(id = "docs", title = "Documentation", content = "No matching facts are present.")
        val verifier = verifier(
            claim = claim,
            retriever = EvidenceRetriever { _, _ -> emptyList() },
            judgment = ClaimJudgment(ClaimVerdict.CONTRADICTED, confidence = 0.97, explanation = "Said contradicted"),
        )

        val result = verifier.verify(claim.text, listOf(source)).single()

        assertEquals(ClaimVerdict.UNVERIFIABLE, result.verdict)
        assertTrue(result.evidence.isEmpty())
        assertEquals(0.0, result.confidence)
    }

    @Test
    fun `given duplicate source ids when claims are verified then evidence checking fails fast`() = runTest {
        val claim = FactualClaim(id = "claim-1", text = "A checkable statement.")
        val verifier = verifier(
            claim = claim,
            retriever = EvidenceRetriever { _, _ -> emptyList() },
            judgment = ClaimJudgment(ClaimVerdict.UNVERIFIABLE, confidence = 0.2, explanation = "No evidence"),
        )
        var rejectedDuplicateIds = false

        try {
            verifier.verify(
                claim.text,
                listOf(
                    SourceDocument("duplicate", "First", "First source."),
                    SourceDocument("duplicate", "Second", "Second source."),
                ),
            )
        } catch (_: IllegalArgumentException) {
            rejectedDuplicateIds = true
        }

        assertTrue(rejectedDuplicateIds)
    }

    @Test
    fun `given matching source text when keyword retrieval verifies a supported claim then the evidence is returned`() = runTest {
        val claim = FactualClaim(id = "claim-1", text = "The service uses Kotlin for APIs.")
        val source = SourceDocument(
            id = "docs",
            title = "Service documentation",
            content = "The service uses Kotlin for APIs and runs on the JVM.",
        )
        val verifier = verifier(
            claim = claim,
            retriever = KeywordEvidenceRetriever(),
            judgment = ClaimJudgment(ClaimVerdict.SUPPORTED, confidence = 0.94, explanation = "Directly supported"),
        )

        val result = verifier.verify(claim.text, listOf(source)).single()

        assertEquals(ClaimVerdict.SUPPORTED, result.verdict)
        assertTrue(result.evidence.isNotEmpty())
        assertTrue(source.content.contains(result.evidence.single().excerpt))
    }

    private fun verifier(
        claim: FactualClaim,
        retriever: EvidenceRetriever,
        judgment: ClaimJudgment,
    ) = EvidenceBackedClaimVerifier(
        claimExtractor = object : ClaimExtractor {
            override suspend fun extract(draft: String) = listOf(claim)
        },
        evidenceRetriever = retriever,
        claimJudge = ClaimJudge { _, _ -> judgment },
    )
}