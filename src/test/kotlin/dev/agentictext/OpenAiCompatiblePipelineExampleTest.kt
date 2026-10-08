package dev.agentictext

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.agentictext.data.JsonSkillRepository
import dev.agentictext.data.SkillBackedWritingProfileRepository
import dev.agentictext.domain.ProfileId
import dev.agentictext.domain.QualityCriterion
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
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * End-to-end example of the full [WritingPipeline] driven through [OpenAiCompatibleTextGenerationClient].
 * A local HTTP server emulates an OpenAI-compatible chat completions endpoint and answers each prompt
 * adapter (writer, claim extractor, claim judge, evaluator, critic) with a canned response, so the wiring
 * from HTTP request to accepted outcome is exercised without a real provider or API key.
 */
class OpenAiCompatiblePipelineExampleTest {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val stageCalls = ConcurrentHashMap<String, Int>()
    private val authorizationHeaders = ConcurrentHashMap.newKeySet<String>()

    @AfterTest
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun `given a fake openai-compatible endpoint when the pipeline runs then the passing candidate is accepted`() = runTest {
        startFakeEndpoint()
        val outcome = pipeline().run(task(candidateCount = 2))

        val accepted = assertIs<WritingOutcome.Accepted>(outcome)
        assertEquals(GOOD_DRAFT, accepted.text)
        assertEquals(0, accepted.revisionCount)
        assertEquals(1, accepted.claimVerifications.size)
        assertEquals(
            mapOf(
                "writer" to 2,
                "claim extractor" to 3,
                "claim verifier" to 3,
                "quality evaluator" to 3,
            ),
            stageCalls.toMap(),
        )
        assertEquals(setOf("Bearer test-key"), authorizationHeaders.toSet())
    }

    @Test
    fun `given a draft failing the rubric when the pipeline runs then the critic and revision flow through the endpoint`() = runTest {
        startFakeEndpoint()
        val outcome = pipeline().run(task(candidateCount = 1))

        val accepted = assertIs<WritingOutcome.Accepted>(outcome)
        assertEquals(GOOD_DRAFT, accepted.text)
        assertEquals(1, accepted.revisionCount)
        assertEquals(
            mapOf(
                "writer" to 2,
                "claim extractor" to 3,
                "claim verifier" to 3,
                "quality evaluator" to 3,
                "critic" to 1,
            ),
            stageCalls.toMap(),
        )
    }

    private fun pipeline(): WritingPipeline {
        val skillRepository = JsonSkillRepository(Paths.get(System.getProperty("user.dir")))
        val profileRepository = SkillBackedWritingProfileRepository(
            skillRepository = skillRepository,
            profileCatalog = DefaultVerificationProfileCatalog(),
        )
        val writingModel: TextGenerationClient = client()
        val evaluationModel: TextGenerationClient = client()
        return WritingPipeline(
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
    }

    private fun client(): TextGenerationClient = OpenAiCompatibleTextGenerationClient(
        baseUrl = "http://127.0.0.1:${server.address.port}/v1",
        model = "example-model",
        apiKey = "test-key",
    )

    private fun task(candidateCount: Int) = WritingTask(
        prompt = "Write a short Lab Bench post about the rate limiter benchmark and what the numbers mean.",
        profileId = ProfileId("lab-bench-blog"),
        sources = listOf(SourceDocument(id = "benchmark-notes", title = "Benchmark notes", content = SOURCE)),
        requirements = WritingRequirements(
            requiredSections = listOf("Findings"),
            exactTerms = setOf("480 requests per second"),
            preserveNumbers = true,
        ),
        candidateCount = candidateCount,
    )

    private fun startFakeEndpoint() {
        server.createContext("/v1/chat/completions") { exchange ->
            val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            exchange.requestHeaders.getFirst("Authorization")?.let(authorizationHeaders::add)
            val request = Json.parseToJsonElement(body).jsonObject
            assertEquals("example-model", request.getValue("model").jsonPrimitive.content)
            val messages = request.getValue("messages").jsonArray
            val system = messages[0].jsonObject.getValue("content").jsonPrimitive.content
            val user = messages[1].jsonObject.getValue("content").jsonPrimitive.content
            exchange.respond(200, completion(stageAndResponse(system, user)))
        }
        server.start()
    }

    private fun stageAndResponse(system: String, user: String): String = when {
        system.startsWith("You are a careful text-writing agent") -> {
            record("writer")
            if ("Current draft:" in user) {
                GOOD_DRAFT
            } else {
                val candidateIndex = "Generate candidate (\\d) of".toRegex().find(user)?.groupValues?.get(1)?.toInt()
                if (candidateIndex == 1) WEAK_DRAFT else GOOD_DRAFT
            }
        }

        system.startsWith("Extract checkable factual claims") -> {
            record("claim extractor")
            """{"claims":[{"id":"c1","text":"Sustained throughput reached 480 requests per second on a single core."}]}"""
        }

        system.startsWith("Verify one factual claim") -> {
            record("claim verifier")
            """{"verdict":"supported","confidence":0.9,"explanation":"The excerpt states the throughput figure."}"""
        }

        system.startsWith("You are an independent quality evaluator") -> {
            record("quality evaluator")
            evaluationJson(taskScore = if (WEAK_MARKER in user) 2 else 5)
        }

        system.startsWith("Critique this draft") -> {
            record("critic")
            """{"issues":[{"location":"paragraph 1","problem":"Benchmark detail is missing.",""" +
                """"severity":"major","suggested_fix":"Include the throughput, client count, and latency figures."}]}"""
        }

        else -> error("Unexpected system prompt: ${system.take(120)}")
    }

    private fun record(stage: String) {
        stageCalls.merge(stage, 1, Int::plus)
    }

    private fun evaluationJson(taskScore: Int): String {
        val criteria = QualityCriterion.entries.joinToString(separator = ",", prefix = "[", postfix = "]") { criterion ->
            val score = if (criterion == QualityCriterion.TASK_FULFILLMENT) taskScore else 5
            """{"criterion":"${criterion.name.lowercase()}","score":$score,""" +
                """"rationale":"The draft reports the benchmark numbers from the source.",""" +
                """"evidence":[{"text":"Sustained throughput reached 480 requests per second","source_id":"benchmark-notes"}]}"""
        }
        return """{"criteria":$criteria,"missing_requirements":[],"unsupported_claims":[],""" +
            """"pass":true,"confidence":0.9}"""
    }

    private fun completion(content: String): String {
        val escaped = content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return """{"choices":[{"message":{"role":"assistant","content":"$escaped"}}]}"""
    }

    private fun HttpExchange.respond(status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private companion object {
        const val SOURCE =
            "The rate limiter was tested with 1000 concurrent clients. Sustained throughput reached " +
                "480 requests per second on a single core, and the p95 latency stayed below 42 milliseconds."
        const val GOOD_DRAFT =
            "## Findings\n\nThe rate limiter handled 1000 concurrent clients during the benchmark. " +
                "Sustained throughput reached 480 requests per second on a single core, and the p95 " +
                "latency stayed below 42 milliseconds."
        const val WEAK_MARKER = "Preliminary numbers"
        val WEAK_DRAFT =
            "## Findings\n\nPreliminary numbers only: 1000 concurrent clients, 480 requests per second, " +
                "and 42 milliseconds p95 latency."
    }
}
