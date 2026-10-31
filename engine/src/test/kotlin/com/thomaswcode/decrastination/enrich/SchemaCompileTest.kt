package com.thomaswcode.decrastination.enrich

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.BadRequestException
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every schema the app sends, compiled by the API as the app's calls have it (docs/development.md):
 * the API refuses a schema whose grammar is too large, which no test can tell from the schema alone
 * ([SchemaLimitsTest]). One call for each schema, with one token out: about a cent each (one refused
 * costs nothing), so it runs only when asked, `-PcheckSchemas=true`, with the key in the environment:
 * after any change to a schema, before it's released.
 */
class SchemaCompileTest {
    @Test
    fun `the API compiles every schema the app sends`() {
        assumeTrue("Not asked for: -PcheckSchemas=true", System.getProperty("checkSchemas") == "true")
        val key = System.getenv(EvalRunTest.KEY)
        assumeTrue("No ${EvalRunTest.KEY} in the environment", !key.isNullOrBlank())
        val client = AnthropicOkHttpClient.builder().apiKey(key).maxRetries(2).build()
        val refused = SchemaLimitsTest.SCHEMAS.mapNotNull { (name, schema) ->
            val format = JsonOutputFormat.Schema.builder().apply { schema.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) } }.build()
            val params = MessageCreateParams.builder()
                .model(ClaudeEnricher.MODEL)
                .maxTokens(1)
                .system("Answer with the JSON the schema asks for.")
                .addUserMessage("Say hello.")
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).format(JsonOutputFormat.builder().schema(format).build()).build())
                .putAdditionalHeader("anthropic-beta", ClaudeEnricher.FALLBACK_BETA)
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build()
            try {
                client.messages().create(params)
                println("Compiles: $name")
                null
            } catch (e: BadRequestException) {
                "$name: ${e.message.orEmpty().take(200)}"
            }
        }
        assertTrue(refused.isEmpty(), "Refused by the API:\n" + refused.joinToString("\n"))
    }
}
