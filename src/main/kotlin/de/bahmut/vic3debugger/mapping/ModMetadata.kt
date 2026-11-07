package de.bahmut.vic3debugger.mapping

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** What the plugin needs from a mod's `.metadata/metadata.json`. */
data class ModMetadata(val name: String, val replacePaths: List<String>) {
    companion object {
        const val METADATA_DIR = ".metadata"
        const val METADATA_FILE = "metadata.json"

        private val lenientJson = Json { isLenient = true; ignoreUnknownKeys = true }
        private val NAME = Regex("\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

        fun metadataFile(modRoot: Path): Path = modRoot.resolve(METADATA_DIR).resolve(METADATA_FILE)

        fun read(modRoot: Path): ModMetadata? {
            val file = metadataFile(modRoot)
            if (!Files.isRegularFile(file)) return null
            return parse(Files.readAllBytes(file))
        }

        /**
         * JSON is UTF-8 by spec, but metadata.json may still start with a UTF-8 BOM, which RFC 8259 forbids
         * and strict parsers reject, and some files in the wild are not valid JSON at all. So strip the BOM,
         * parse leniently, and fall back to pulling out the name by pattern (docs/SCRIPT_DEBUGGER.md §9).
         */
        fun parse(bytes: ByteArray): ModMetadata? {
            val text = String(bytes, StandardCharsets.UTF_8).removePrefix("﻿")
            val parsed = runCatching { lenientJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
            if (parsed != null) {
                val name = (parsed["name"] as? JsonPrimitive)?.contentOrNull ?: return null
                val custom = parsed["game_custom_data"] as? JsonObject
                val replace = (custom?.get("replace_paths") as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .orEmpty()
                return ModMetadata(name, replace)
            }
            val name = NAME.find(text)?.groupValues?.get(1) ?: return null
            val unescaped = runCatching { lenientJson.decodeFromString<String>("\"$name\"") }.getOrDefault(name)
            return ModMetadata(unescaped, emptyList())
        }
    }
}
