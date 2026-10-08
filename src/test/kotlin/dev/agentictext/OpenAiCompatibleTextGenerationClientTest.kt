package dev.agentictext

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.agentictext.llm.InvalidAgentResponseException
import dev.agentictext.llm.ModelPrompt
import dev.agentictext.llm.OpenAiCompatibleTextGenerationClient
import dev.agentictext.llm.TextGenerationException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenAiCompatibleTextGenerationClientTest {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private var lastRequestBody: String? = null
    private var lastAuthorizationHeader: String? = null

    @AfterTest
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun `given a valid chat completion when generated then the first choice content is returned and the request is well formed`() = runTest {
        respondWith(
            status = 200,
            body = """{"choices":[{"message":{"role":"assistant","content":"  generated text  "}}]}""",
        )
        val client = client()

        val result = client.generate(ModelPrompt(systemMessage = "be terse", userMessage = "hello"))

        assertEquals("  generated text  ", result)
        assertEquals("Bearer secret-key", lastAuthorizationHeader)
        val request = Json.parseToJsonElement(requireNotNull(lastRequestBody)).jsonObject
        assertEquals("test-model", request.getValue("model").jsonPrimitive.content)
        val messages = request.getValue("messages").jsonArray
        assertEquals("system", messages[0].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("be terse", messages[0].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("user", messages[1].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("hello", messages[1].jsonObject.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun `given a legacy text completion shape when generated then the text field is used`() = runTest {
        respondWith(status = 200, body = """{"choices":[{"text":"legacy completion"}]}""")

        val result = client().generate(ModelPrompt("system", "user"))

        assertEquals("legacy completion", result)
    }

    @Test
    fun `given an error status when generated then a text generation exception carries the provider message`() = runTest {
        respondWith(status = 429, body = """{"error":{"message":"rate limited"}}""")

        val exception = assertFailsWith<TextGenerationException> {
            client().generate(ModelPrompt("system", "user"))
        }

        assertTrue(exception.message!!.contains("429"))
        assertTrue(exception.message!!.contains("rate limited"))
    }

    @Test
    fun `given a malformed response body when generated then an invalid agent response is raised`() = runTest {
        respondWith(status = 200, body = "not json")

        assertFailsWith<InvalidAgentResponseException> {
            client().generate(ModelPrompt("system", "user"))
        }
    }

    @Test
    fun `given a response without choices when generated then an invalid agent response is raised`() = runTest {
        respondWith(status = 200, body = """{"choices":[]}""")

        assertFailsWith<InvalidAgentResponseException> {
            client().generate(ModelPrompt("system", "user"))
        }
    }

    @Test
    fun `given no api key when generated then no authorization header is sent`() = runTest {
        respondWith(status = 200, body = """{"choices":[{"message":{"content":"ok"}}]}""")

        client(apiKey = null).generate(ModelPrompt("system", "user"))

        assertEquals(null, lastAuthorizationHeader)
    }

    private fun client(apiKey: String? = "secret-key") = OpenAiCompatibleTextGenerationClient(
        baseUrl = "http://127.0.0.1:${server.address.port}/v1",
        model = "test-model",
        apiKey = apiKey,
    )

    private fun respondWith(status: Int, body: String) {
        server.createContext("/v1/chat/completions") { exchange ->
            lastRequestBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            lastAuthorizationHeader = exchange.requestHeaders.getFirst("Authorization")
            exchange.respond(status, body)
        }
        server.start()
    }

    private fun HttpExchange.respond(status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
