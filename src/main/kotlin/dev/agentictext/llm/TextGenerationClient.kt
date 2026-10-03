package dev.agentictext.llm

data class ModelPrompt(
    val systemMessage: String,
    val userMessage: String,
)

fun interface TextGenerationClient {
    suspend fun generate(prompt: ModelPrompt): String
}

class InvalidAgentResponseException(
    stage: String,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException("$stage returned an invalid response: $message", cause)