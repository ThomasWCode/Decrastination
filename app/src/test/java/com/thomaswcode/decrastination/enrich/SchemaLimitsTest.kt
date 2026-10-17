package com.thomaswcode.decrastination.enrich

import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.learn.ReviewInput
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every schema the app sends holds no more than the API takes. The API compiles a schema into a
 * grammar that holds the answer to it as it's written, and refuses a schema whose grammar is too
 * large (400). Its stated limit: 16 parameters with union types (a nullable field is one); one
 * more and every call with it fails, as the chat's did once the school calendar's change added its
 * end date (PL-16). Beyond that the grammar's size is the API's own: unions inside a list's items
 * weigh most, and the chat's 15 there made it too large to compile. Whether each schema compiles
 * is only known from the API ([SchemaCompileTest], asked for by hand).
 */
class SchemaLimitsTest {
    /** Parameters with a union type in [node]: an `anyOf`, or a list of types, wherever they're nested. */
    private fun unions(node: Any?): Int = when (node) {
        is Map<*, *> -> (if ("anyOf" in node || node["type"] is List<*>) 1 else 0) + node.values.sumOf { unions(it) }
        is List<*> -> node.sumOf { unions(it) }
        else -> 0
    }

    /** Of [node]'s unions, those inside a list's items: each item can hold every one of them. */
    private fun unionsInLists(node: Any?): Int = when (node) {
        is Map<*, *> -> node.entries.sumOf { (key, value) -> if (key == "items") unions(value) else unionsInLists(value) }
        is List<*> -> node.sumOf { unionsInLists(it) }
        else -> 0
    }

    @Test
    fun `every schema the app sends has at most 16 unions`() {
        for ((name, schema) in SCHEMAS) {
            val count = unions(schema)
            assertTrue(count <= 16, "the $name schema has $count unions, more than the API's 16")
        }
    }

    @Test
    fun `no schema has more than two unions inside a list`() {
        // The readings' two (a step's dates) compile; the chat's fifteen didn't.
        for ((name, schema) in SCHEMAS) {
            val count = unionsInLists(schema)
            assertTrue(count <= MAX_UNIONS_IN_LISTS, "the $name schema has $count unions inside a list: make them plain fields, empty for none")
        }
    }

    companion object {
        private const val MAX_UNIONS_IN_LISTS = 2

        /** Every schema the app sends, by what it's for. */
        val SCHEMAS: Map<String, Map<String, Any>> = mapOf(
            "chat" to ChatPrompts.SCHEMA,
            "instruction" to InstructionPrompts.SCHEMA,
            "weekly review" to ReviewInput.schema(),
            "photo check" to PhotoChecker.SCHEMA,
            "memory tidy" to MemoryTidy.SCHEMA,
            "Felix" to FelixPrompts.SCHEMA,
            "micro-plan" to MicroPlans.SCHEMA,
            "photo to tasks" to PhotoTasks.SCHEMA,
        ) + Enrichments.Job.entries.associate { "enrichment ${it.name}" to Prompts.schema(it) }
    }
}
