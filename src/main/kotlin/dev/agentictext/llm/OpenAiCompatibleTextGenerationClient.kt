package dev.agentictext.llm

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Example [TextGenerationClient] for any OpenAI-compatible chat completions endpoint
 * (OpenAI, Azure OpenAI, Ollama, LM Studio, vLLM, llama.cpp, ...).
 *
 * The client posts to `{baseUrl}/chat/completions` and returns the first choice's message content.
 * Credentials are never hardcoded: pass them explicitly or build the client with [fromEnvironment].
 */
class OpenAiCompatibleTextGenerationClient(
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String? = null,
    private val temperature: Double? = null,
    private val maxTokens: Int? = null,
    private val requestTimeout: Duration = Duration.ofSeconds(120),
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : TextGenerationClient {

    init {
        require(baseUrl.isNotBlank()) { "Base URL must not be blank" }
        require(model.isNotBlank()) { "Model name must not be blank" }
        require(temperature == null || temperature in 0.0..2.0) { "Temperature must be between 0 and 2" }
        require(maxTokens == null || maxTokens > 0) { "Max tokens must be positive" }
    }

    override suspend fun generate(prompt: ModelPrompt): String {
        val request = HttpRequest.newBuilder()
            .uri(chatCompletionsUri())
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .apply { if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey") }
            .POST(HttpRequest.BodyPublishers.ofString(encodeRequest(prompt)))
            .build()

        val response = withContext(ioDispatcher) {
            try {
                httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (exception: IOException) {
                throw TextGenerationException("Could not reach the model endpoint at $baseUrl", exception)
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                throw TextGenerationException("Model request to $baseUrl was interrupted", exception)
            }
        }

        if (response.statusCode() !in 200..299) {
            throw TextGenerationException(
                "Model endpoint at $baseUrl returned HTTP ${response.statusCode()}: ${errorMessage(response.body())}",
            )
        }

        val completion = try {
            json.decodeFromString<ChatCompletionResponse>(response.body())
        } catch (exception: SerializationException) {
            throw InvalidAgentResponseException(
                "text generation client",
                "expected an OpenAI-compatible chat completion response",
                exception,
            )
        }

        return completion.choices.firstOrNull()
            ?.let { choice -> choice.message?.content ?: choice.text }
            ?.takeIf { content -> content.isNotBlank() }
            ?: throw InvalidAgentResponseException(
                "text generation client",
                "response contained no completion content",
            )
    }

    private fun encodeRequest(prompt: ModelPrompt): String = json.encodeToString(
        ChatCompletionRequest(
            model = model,
            messages = listOf(
                ChatMessage(role = "system", content = prompt.systemMessage),
                ChatMessage(role = "user", content = prompt.userMessage),
            ),
            temperature = temperature,
            maxTokens = maxTokens,
        ),
    )

    private fun chatCompletionsUri(): URI = URI.create("${baseUrl.trimEnd('/')}/chat/completions")

    private fun errorMessage(body: String): String {
        if (body.isBlank()) return "(empty response body)"
        return try {
            json.decodeFromString<ErrorResponse>(body).error?.message ?: body.take(500)
        } catch (_: SerializationException) {
            body.take(500)
        }
    }

    companion object {
        const val API_KEY_ENV = "OPENAI_API_KEY"
        const val BASE_URL_ENV = "OPENAI_BASE_URL"
        const val MODEL_ENV = "OPENAI_MODEL"
        private const val DEFAULT_BASE_URL = "https://api.openai.com/v1"

        /**
         * Builds a client from environment variables so no secret is committed to source control.
         * [MODEL_ENV] is required; [BASE_URL_ENV] defaults to the public OpenAI endpoint and
         * [API_KEY_ENV] may be omitted for local endpoints that do not require authentication.
         */
        fun fromEnvironment(
            temperature: Double? = null,
            maxTokens: Int? = null,
        ): OpenAiCompatibleTextGenerationClient = OpenAiCompatibleTextGenerationClient(
            baseUrl = System.getenv(BASE_URL_ENV) ?: DEFAULT_BASE_URL,
            model = requireNotNull(System.getenv(MODEL_ENV)) {
                "Set $MODEL_ENV to the model name for the OpenAI-compatible endpoint"
            },
            apiKey = System.getenv(API_KEY_ENV),
            temperature = temperature,
            maxTokens = maxTokens,
        )
    }
}

class TextGenerationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

@Serializable
private data class ChatCompletionRequest(
    @SerialName("model") val model: String,
    @SerialName("messages") val messages: List<ChatMessage>,
    @SerialName("temperature") val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
)

@Serializable
private data class ChatMessage(
    @SerialName("role") val role: String,
    @SerialName("content") val content: String,
)

@Serializable
private data class ChatCompletionResponse(
    @SerialName("choices") val choices: List<ChatChoice> = emptyList(),
)

@Serializable
private data class ChatChoice(
    @SerialName("message") val message: ChatChoiceMessage? = null,
    @SerialName("text") val text: String? = null,
)

@Serializable
private data class ChatChoiceMessage(
    @SerialName("content") val content: String? = null,
)

@Serializable
private data class ErrorResponse(
    @SerialName("error") val error: ErrorDetail? = null,
)

@Serializable
private data class ErrorDetail(
    @SerialName("message") val message: String? = null,
)
