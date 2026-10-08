package dev.agentictext.examples

import dev.agentictext.data.JsonSkillRepository
import dev.agentictext.data.SkillBackedWritingProfileRepository
import dev.agentictext.domain.ProfileId
import dev.agentictext.domain.SourceDocument
import dev.agentictext.domain.WritingOutcome
import dev.agentictext.domain.WritingRequirements
import dev.agentictext.domain.WritingTask
import dev.agentictext.llm.OpenAiCompatibleTextGenerationClient
import dev.agentictext.llm.PromptedClaimExtractor
import dev.agentictext.llm.PromptedClaimJudge
import dev.agentictext.llm.PromptedDraftCritic
import dev.agentictext.llm.PromptedDraftEvaluator
import dev.agentictext.llm.PromptedWritingAgent
import dev.agentictext.llm.TextGenerationClient
import dev.agentictext.pipeline.WritingPipeline
import dev.agentictext.profiles.DefaultVerificationProfileCatalog
import dev.agentictext.verification.EvidenceBackedClaimVerifier
import dev.agentictext.verification.KeywordEvidenceRetriever
import java.nio.file.Paths
import kotlinx.coroutines.runBlocking

/**
 * Runnable end-to-end example wiring the full [WritingPipeline] to an OpenAI-compatible endpoint
 * via [OpenAiCompatibleTextGenerationClient]. No secret is hardcoded: the clients are built from
 * environment variables.
 *
 * Configure `OPENAI_MODEL` (required), `OPENAI_BASE_URL` (defaults to https://api.openai.com/v1),
 * and `OPENAI_API_KEY` (optional for local endpoints such as Ollama or LM Studio), then run:
 *
 * ```shell
 * ./gradlew runOpenAiCompatibleExample
 * ```
 */
fun main(): Unit = runBlocking {
    val skillRepository = JsonSkillRepository(Paths.get(System.getProperty("user.dir")))
    val profileRepository = SkillBackedWritingProfileRepository(
        skillRepository = skillRepository,
        profileCatalog = DefaultVerificationProfileCatalog(),
    )

    val writingModel: TextGenerationClient = OpenAiCompatibleTextGenerationClient.fromEnvironment(
        temperature = 0.7,
    )
    // Prefer a separate model for evaluation when one is available; this example reuses the
    // configured endpoint because a second model was not specified.
    val evaluationModel: TextGenerationClient = OpenAiCompatibleTextGenerationClient.fromEnvironment()

    val pipeline = WritingPipeline(
        profileRepository = profileRepository,
        writer = PromptedWritingAgent(writingModel),
        claimVerifier = EvidenceBackedClaimVerifier(
            claimExtractor = PromptedClaimExtractor(evaluationModel),
            evidenceRetriever = KeywordEvidenceRetriever(),
            claimJudge = PromptedClaimJudge(evaluationModel),
        ),
        evaluator = PromptedDraftEvaluator(evaluationModel),
        critic = PromptedDraftCritic(evaluationModel),
    )

    val source = SourceDocument(
        id = "benchmark-notes",
        title = "Benchmark notes",
        content =
            "The rate limiter was tested with 1000 concurrent clients. " +
                "Sustained throughput reached 480 requests per second on a single core, " +
                "and the p95 latency stayed below 42 milliseconds.",
    )
    val task = WritingTask(
        prompt = "Write a short Lab Bench post about the rate limiter benchmark and what the numbers mean.",
        profileId = ProfileId("lab-bench-blog"),
        sources = listOf(source),
        requirements = WritingRequirements(
            requiredSections = listOf("Findings"),
            exactTerms = setOf("480 requests per second"),
            preserveNumbers = true,
        ),
        candidateCount = 2,
    )

    when (val outcome = pipeline.run(task)) {
        is WritingOutcome.Accepted -> {
            println("ACCEPTED after ${outcome.revisionCount} revision(s):")
            println(outcome.text)
            println("Verifier confidence: ${outcome.evaluation.confidence}")
            println("Verified claims: ${outcome.claimVerifications.size}")
        }

        is WritingOutcome.Rejected -> {
            println("REJECTED: ${outcome.reason}")
            outcome.violations.forEach { violation -> println("- [${violation.rule}] ${violation.message}") }
        }

        is WritingOutcome.Escalated -> {
            println("ESCALATED after ${outcome.revisionCount} revision(s): ${outcome.reason}")
            println("Draft:\n${outcome.review.candidate.text}")
        }
    }
}
