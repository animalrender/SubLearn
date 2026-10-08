package com.sublearn.core.settings

import com.sublearn.core.common.AppResult
import com.sublearn.core.common.SubLearnError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Where the settings document lives. Keeping it behind a tiny string-keyed interface means the
 * typed repository is pure Kotlin and therefore unit-testable without Android (ENGINEERING
 * REQUIREMENTS: typed, versioned settings).
 */
interface SettingsStorage {
    suspend fun read(key: String): String?

    suspend fun write(key: String, value: String)

    suspend fun clear(key: String)
}

/** Used by tests, Compose previews and the "no persistence" fallback. */
class InMemorySettingsStorage(initial: Map<String, String> = emptyMap()) : SettingsStorage {
    private val values = MutableStateFlow(initial)

    override suspend fun read(key: String): String? = values.value[key]

    override suspend fun write(key: String, value: String) {
        values.value = values.value + (key to value)
    }

    override suspend fun clear(key: String) {
        values.value = values.value - key
    }
}

interface SettingsRepository {
    val settings: StateFlow<AppSettings>

    suspend fun current(): AppSettings

    suspend fun update(transform: (AppSettings) -> AppSettings)

    suspend fun replace(value: AppSettings)

    suspend fun exportJson(): String

    suspend fun importJson(text: String, mode: ImportMode = ImportMode.REPLACE): AppResult<ImportReport>

    suspend fun reset()
}

enum class ImportMode { REPLACE, MERGE }

data class ImportReport(
    val appliedSections: List<String>,
    val warnings: List<String>,
    val droppedUnknownSections: List<String>,
)

/** JSON codec shared by persistence and export/import so both always agree. */
object SettingsJson {
    val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    fun encode(settings: AppSettings): String = json.encodeToString(AppSettings.serializer(), settings)

    fun decode(text: String): AppResult<AppSettings> {
        val migrated: AppResult<String> = SettingsMigrator.migrate(text)
        val raw = migrated.getOrNull()
            ?: return AppResult.failure(migrated.errorOrNull() ?: UNKNOWN_SETTINGS_ERROR)
        return try {
            AppResult.success(json.decodeFromString(AppSettings.serializer(), raw))
        } catch (t: Throwable) {
            AppResult.failure(SubLearnError(SubLearnError.Kind.MalformedInput, "settings document is not readable: ${t.message}", t))
        }
    }

    private val UNKNOWN_SETTINGS_ERROR = SubLearnError(SubLearnError.Kind.MalformedInput, "settings document is not readable")
}

/**
 * Version migrations. A document from a newer app is rejected with a clear error rather than being
 * silently truncated, because a truncated settings file looks like data loss to the user.
 */
object SettingsMigrator {
    fun migrate(text: String): AppResult<String> {
        val root = runCatching { Json.parseToJsonElement(text) }.getOrElse {
            return AppResult.failure(SubLearnError(SubLearnError.Kind.MalformedInput, "settings file is not JSON"))
        }
        val obj = root as? JsonObject
            ?: return AppResult.failure(SubLearnError(SubLearnError.Kind.MalformedInput, "settings file must be a JSON object"))
        val declared = obj["schemaVersion"]?.jsonPrimitive?.intOrNull ?: 0
        return when {
            declared > AppSettings.SCHEMA_VERSION -> AppResult.failure(
                SubLearnError(
                    SubLearnError.Kind.UnsupportedFormat,
                    "settings file was written by a newer SubLearn (v$declared > v${AppSettings.SCHEMA_VERSION})",
                ),
            )

            declared == AppSettings.SCHEMA_VERSION -> AppResult.success(text)
            else -> AppResult.success(applySteps(obj, declared).toString())
        }
    }

    /**
     * One entry per target version, applied in ascending order. A step that has not been needed
     * yet is present as the identity transform so adding a real step cannot skip versions.
     */
    private val steps: List<Pair<Int, (JsonObject) -> JsonObject>> = listOf(
        2 to { it },
        3 to { it },
    )

    fun pendingSteps(from: Int): List<Int> = steps.filter { it.first > from }.map { it.first }

    private fun applySteps(obj: JsonObject, from: Int): JsonObject {
        var current = obj
        steps.filter { it.first > from }.forEach { (_, transform) -> current = transform(current) }
        val patched = current + ("schemaVersion" to JsonPrimitive(AppSettings.SCHEMA_VERSION))
        return JsonObject(patched)
    }
}

/**
 * The typed settings entry point. Reads are cached in a [StateFlow] so Compose recomposes only on
 * change, and every write is a serialised read-modify-write (no lost updates when the player and
 * the settings screen change at the same time).
 */
class DefaultSettingsRepository(
    private val storage: SettingsStorage,
    private val key: String = KEY,
) : SettingsRepository {
    private val mutex = Mutex()
    private val state = MutableStateFlow(AppSettings.DEFAULT)
    override val settings: StateFlow<AppSettings> = state.asStateFlow()
    private var loaded = false

    override suspend fun current(): AppSettings {
        ensureLoaded()
        return state.value
    }

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        mutex.withLock {
            ensureLoadedLocked()
            val next = transform(state.value).copy(schemaVersion = AppSettings.SCHEMA_VERSION)
            persist(next)
        }
    }

    override suspend fun replace(value: AppSettings) {
        mutex.withLock {
            persist(value.copy(schemaVersion = AppSettings.SCHEMA_VERSION))
        }
    }

    override suspend fun exportJson(): String {
        ensureLoaded()
        return SettingsJson.encode(state.value)
    }

    override suspend fun importJson(text: String, mode: ImportMode): AppResult<ImportReport> = mutex.withLock {
        ensureLoadedLocked()
        val decoded = SettingsJson.decode(text)
        val imported = decoded.getOrNull() ?: return@withLock AppResult.failure(decoded.errorOrNull()!!)
        val present = presentSections(text)
        val merged = if (mode == ImportMode.REPLACE) imported else mergeSections(state.value, imported, present)
        val known = importedSections(imported)
        val dropped = Sections.ALL.filterNot { known.contains(it) }
        persist(merged)
        AppResult.success(
            ImportReport(
                appliedSections = known,
                warnings = if (imported.schemaVersion < AppSettings.SCHEMA_VERSION) {
                    listOf("settings migrated from v${imported.schemaVersion} to v${AppSettings.SCHEMA_VERSION}")
                } else {
                    emptyList()
                },
                droppedUnknownSections = dropped,
            ),
        )
    }

    override suspend fun reset() {
        mutex.withLock {
            storage.clear(key)
            state.value = AppSettings.DEFAULT
            loaded = true
        }
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock { ensureLoadedLocked() }
    }

    private suspend fun ensureLoadedLocked() {
        if (loaded) return
        val raw = storage.read(key)
        state.value = if (raw.isNullOrBlank()) {
            AppSettings.DEFAULT
        } else {
            SettingsJson.decode(raw).getOrNull() ?: AppSettings.DEFAULT
        }
        loaded = true
    }

    private suspend fun persist(value: AppSettings) {
        state.value = value
        storage.write(key, SettingsJson.encode(value))
    }

    private fun importedSections(value: AppSettings): List<String> = buildList {
        add(Sections.PLAYER)
        add(Sections.SUBTITLES)
        add(Sections.FONTS)
        add(Sections.APPEARANCE)
        if (value.quickActions.specs.isNotEmpty()) add(Sections.QUICK_ACTIONS)
        if (value.gestures.mapping.isNotEmpty()) add(Sections.GESTURES)
        add(Sections.SHADOWING)
        add(Sections.LEARNING)
        add(Sections.AI)
    }

    companion object {
        const val KEY = "app_settings"
    }
}

/** Section names used by the searchable settings screen and by JSON export reporting. */
object Sections {
    const val APPEARANCE = "appearance"
    const val PLAYER = "player"
    const val SUBTITLES = "subtitles"
    const val FONTS = "fonts"
    const val QUICK_ACTIONS = "quickActions"
    const val GESTURES = "gestures"
    const val SHADOWING = "shadowing"
    const val LEARNING = "learning"
    const val AI = "ai"
    const val DICTIONARY = "dictionary"
    const val LEVEL = "level"
    const val TRANSLATION = "translation"
    const val WORD_STYLES = "wordStyles"
    const val LANGUAGES = "languages"
    const val ABOUT = "about"
    val ALL = listOf(
        APPEARANCE, LANGUAGES, PLAYER, SUBTITLES, FONTS, QUICK_ACTIONS, GESTURES, SHADOWING,
        LEARNING, WORD_STYLES, AI, TRANSLATION, DICTIONARY, LEVEL, ABOUT,
    )
}

/** Section keys a document actually carries; used by [ImportMode.MERGE] so partial imports work. */
private fun presentSections(text: String): Set<String> {
    val obj = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
    return obj?.keys.orEmpty()
}

/**
 * Merge import: only sections the incoming document really contains replace the current values,
 * so a file exported by an older app or a hand-written partial file cannot reset everything else.
 */
private fun mergeSections(current: AppSettings, incoming: AppSettings, present: Set<String>): AppSettings {
    fun <T> pick(key: String, incomingValue: T, currentValue: T): T = if (key in present) incomingValue else currentValue

    return current.copy(
        schemaVersion = AppSettings.SCHEMA_VERSION,
        appearance = pick(Sections.APPEARANCE, incoming.appearance, current.appearance),
        languages = pick(Sections.LANGUAGES, incoming.languages, current.languages),
        player = pick(Sections.PLAYER, incoming.player, current.player),
        subtitles = pick(Sections.SUBTITLES, incoming.subtitles, current.subtitles),
        fonts = current.fonts + incoming.fonts,
        quickActions = pick(Sections.QUICK_ACTIONS, incoming.quickActions, current.quickActions),
        gestures = pick(Sections.GESTURES, incoming.gestures, current.gestures),
        shadowing = pick(Sections.SHADOWING, incoming.shadowing, current.shadowing),
        learning = pick(Sections.LEARNING, incoming.learning, current.learning),
        wordStyles = pick(Sections.WORD_STYLES, incoming.wordStyles, current.wordStyles),
        ai = pick(Sections.AI, incoming.ai, current.ai),
        translation = pick(Sections.TRANSLATION, incoming.translation, current.translation),
        dictionary = pick(Sections.DICTIONARY, incoming.dictionary, current.dictionary),
        level = pick(Sections.LEVEL, incoming.level, current.level),
    )
}

