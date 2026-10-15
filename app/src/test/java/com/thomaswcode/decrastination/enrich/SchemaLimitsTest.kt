package com.thomaswcode.decrastination.enrich

import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.learn.ReviewInput
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every schema the app sends holds no more than the API takes: 16 parameters with union types (a
 * nullable field is one). One more and every call with it fails (400), as the chat's did on the
 * phone once the school calendar's change added its end date (PL-16).
 */
class SchemaLimitsTest {
    /** Parameters with a union type in [node]: an `anyOf`, or a list of types, wherever they're nested. */
    private fun unions(node: Any?): Int = when (node) {
        is Map<*, *> -> (if ("anyOf" in node || node["type"] is List<*>) 1 else 0) + node.values.sumOf { unions(it) }
        is List<*> -> node.sumOf { unions(it) }
        else -> 0
    }

    @Test
    fun `every schema the app sends has at most 16 unions`() {
        val schemas = mapOf(
            "chat" to ChatPrompts.SCHEMA,
            "instruction" to InstructionPrompts.SCHEMA,
            "weekly review" to ReviewInput.schema(),
            "photo check" to PhotoChecker.SCHEMA,
            "memory tidy" to MemoryTidy.SCHEMA,
            "Felix" to FelixPrompts.SCHEMA,
        ) + Enrichments.Job.entries.associate { "enrichment ${it.name}" to Prompts.schema(it) }
        for ((name, schema) in schemas) {
            val count = unions(schema)
            assertTrue(count <= 16, "the $name schema has $count unions, more than the API's 16")
        }
    }
}
