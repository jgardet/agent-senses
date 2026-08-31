package com.nyooran.agent.senses

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.File

/**
 * S0-03: Architectural dependency checks for agent-senses:core.
 *
 * Verifies that the generic core module does not import any
 * device-specific or framework-specific types. Core must remain
 * pure Kotlin with no Android, Halo, BLE, HSD, HRP, Lua, Gemma,
 * Ktor, Node, or Compose dependencies.
 */
class CoreDependencyCheckTest {

    /**
     * Forbidden import prefixes in core source files.
     * Core must be device-neutral and framework-agnostic.
     */
    private val forbiddenImports = listOf(
        "android.",
        "halo.engine",
        "halo.engine.",
        "com.nyooran.agent.senses.halo",
        "com.nyooran.agent.senses.android",
        "com.nyooran.agent.senses.simulator",
        "io.ktor",
        "kotlinx.serialization.json.Json", // JsonElement is ok, Json parser is not
        "com.nyooran.dshandroid",
        "gemma",
        "node",
    )

    private val coreSourceDir = File("src/main/kotlin")

    @Test
    fun coreHasNoForbiddenImports() {
        val violations = mutableListOf<String>()
        walkKotlinFiles(coreSourceDir).forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("import ")) {
                    val importPath = trimmed.removePrefix("import ").substringBefore(" as ").trim()
                    forbiddenImports.forEach { forbidden ->
                        if (importPath.startsWith(forbidden)) {
                            violations += "${file.relativeTo(coreSourceDir)}:${index + 1}: $importPath"
                        }
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
            "Forbidden imports found in core:\n${violations.joinToString("\n")}")
    }

    private fun walkKotlinFiles(dir: File): List<File> {
        if (!dir.exists()) return emptyList()
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}
