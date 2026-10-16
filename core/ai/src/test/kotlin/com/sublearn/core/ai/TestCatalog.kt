package com.sublearn.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * `docs/catalog/catalog-v2.json` as the tests read it.
 *
 * The sizes in the Kotlin catalog shipped without a source of truth once: they were "a reasonable
 * size" per model, and the publisher's real files are what the settings screen promises, so the
 * tests compare against the file the repository actually ships (`tools/refresh_model_catalog.py
 * --check` keeps it in step with the publishers). Reading it here means a model swap cannot leave a
 * passing test behind.
 */
object TestCatalog {

    /** Entries in the order the file lists them. */
    private val entries: List<JsonObject> by lazy {
        val file = File(CATALOG_PATH)
        check(file.isFile) { "catalog not found at ${file.absolutePath}; run the tests from the module directory" }
        Json.parseToJsonElement(file.readText(Charsets.UTF_8))
            .jsonObject.getValue("entries")
            .jsonArray
            .map { it.jsonObject }
    }

    private fun entryFor(id: String): JsonObject =
        entries.firstOrNull { it.getValue("id").jsonPrimitive.content == id }
            ?: error("no catalog entry for $id")

    /** The published size of a model or pack. */
    fun sizeOf(id: String): Long = entryFor(id).getValue("sizeBytes").jsonPrimitive.content.toLong()

    /** The profile the catalog lists an entry under. */
    fun profileOf(id: String): String = entryFor(id).getValue("profile").jsonPrimitive.content

    private const val CATALOG_PATH = "../../docs/catalog/catalog-v2.json"
}
