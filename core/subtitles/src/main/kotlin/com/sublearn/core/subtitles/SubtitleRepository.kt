package com.sublearn.core.subtitles

import com.sublearn.core.common.AppResult
import java.io.File

/** A subtitle file the user (or the auto-matcher) picked. [key] is opaque to this module. */
data class SubtitleFile(
    val key: String,
    val name: String,
    val sizeBytes: Long = -1L,
)

/**
 * Where subtitle bytes come from. The Android implementation uses SAF so any folder the user
 * grants is reachable; tests and the folder-scan path use [DirectorySubtitleSource].
 */
interface SubtitleFileSource {
    /** Files in the same folder as [videoKey] that look like subtitles. */
    suspend fun candidatesFor(videoKey: String): List<SubtitleFile> = emptyList()

    suspend fun read(file: SubtitleFile): ByteArray

    companion object {
        const val MAX_FILE_BYTES = 8L * 1024 * 1024
    }
}

/** Reads subtitles from a directory on the file system (used by tests, the desktop-style import and folder playlists). */
class DirectorySubtitleSource(private val directory: File) : SubtitleFileSource {
    override suspend fun candidatesFor(videoKey: String): List<SubtitleFile> {
        if (!directory.isDirectory) return emptyList()
        return directory.listFiles()
            .orEmpty()
            .filter { it.isFile && SubtitleFormat.fromFileName(it.name) != SubtitleFormat.UNKNOWN }
            .sortedBy { it.name.lowercase() }
            .map { SubtitleFile(key = it.absolutePath, name = it.name, sizeBytes = it.length()) }
    }

    override suspend fun read(file: SubtitleFile): ByteArray {
        val handle = File(file.key)
        require(handle.length() <= SubtitleFileSource.MAX_FILE_BYTES) { "subtitle file too large" }
        return handle.readBytes()
    }
}

/** Fixed set of in-memory files; the fake used by unit tests and previews. */
class InMemorySubtitleSource(files: Map<String, String>) : SubtitleFileSource {
    private val contents: Map<String, String> = files

    override suspend fun candidatesFor(videoKey: String): List<SubtitleFile> =
        contents.keys.filter { SubtitleFormat.fromFileName(it) != SubtitleFormat.UNKNOWN }
            .sorted()
            .map { SubtitleFile(key = it, name = it, sizeBytes = contents.getValue(it).toByteArray().size.toLong()) }

    override suspend fun read(file: SubtitleFile): ByteArray =
        contents[file.key] ?: throw java.io.FileNotFoundException(file.key)
}

/** Result of loading one track: everything the player needs to render and index it. */
data class LoadedTrack(
    val track: SubtitleTrack,
    val document: SubtitleDocument,
    val format: SubtitleFormat,
    val charsetName: String,
    val warnings: List<String> = emptyList(),
)

interface SubtitleRepository {
    suspend fun load(file: SubtitleFile, role: TrackRole, config: NormalizerConfig = NormalizerConfig.DEFAULT): AppResult<LoadedTrack>

    /** Builds a track from text the caller already has (embedded Media3 tracks, clipboard, tests). */
    fun parse(name: String, bytes: ByteArray, role: TrackRole, config: NormalizerConfig = NormalizerConfig.DEFAULT): AppResult<LoadedTrack>

    /** External files in the video's folder that share its base name (SUB-7 auto-loading). */
    suspend fun autoMatch(videoKey: String, videoName: String): List<SubtitleCandidate>
}

/** An auto-discovered file plus the layer role inferred from its name. */
data class SubtitleCandidate(val file: SubtitleFile, val suggestedRole: TrackRole, val languageTag: String?)

/**
 * Default repository: decode bytes (charset sniff), pick a parser, clean cues, build blocks.
 *
 * Parsing is synchronous by contract; callers move it off the main thread (the player view model
 * does it on Dispatchers.IO) so this class stays trivially testable.
 */
class DefaultSubtitleRepository(private val source: SubtitleFileSource) : SubtitleRepository {
    override suspend fun load(
        file: SubtitleFile,
        role: TrackRole,
        config: NormalizerConfig,
    ): AppResult<LoadedTrack> = AppResult.of {
        val bytes = source.read(file)
        check(bytes.size.toLong() <= SubtitleFileSource.MAX_FILE_BYTES) { "subtitle file larger than 8 MiB" }
        parse(file.name, bytes, role, config).getOrThrow()
    }

    override fun parse(
        name: String,
        bytes: ByteArray,
        role: TrackRole,
        config: NormalizerConfig,
    ): AppResult<LoadedTrack> = AppResult.of {
        val decoded = CharsetSniffer.decode(bytes)
        val format = SubtitleParsers.detect(name, decoded.text)
        val parser = SubtitleParsers.forFormat(format)
        val trackId = role.name.lowercase() + ":" + name
        val raw = parser.parse(decoded.text, trackId)
        val warnings = ArrayList<String>()
        if (raw.isEmpty()) warnings += "no cues parsed"
        val cleaned = raw.map { it.copy(text = SubtitleNormalizer.cleanCueText(it.text, config)) }
            .filter { it.text.isNotBlank() }
        val cues = SubtitleNormalizer.regroup(cleaned, config).map { it.copy(trackId = trackId) }
        val track = SubtitleTrack(
            id = trackId,
            name = displayTrackName(name),
            languageTag = SubtitleLanguage.guessFromName(name),
            role = role,
            origin = SubtitleParsers.originFor(format),
            cues = cues,
        )
        val blocks = BlockBuilder(config).build(cues, trackId)
        if (blocks.isEmpty() && cues.isNotEmpty()) warnings += "all cues were dropped by the normalizer"
        LoadedTrack(
            track = track,
            document = SubtitleDocument(track, blocks),
            format = format,
            charsetName = decoded.charset.name(),
            warnings = warnings,
        )
    }

    override suspend fun autoMatch(
        videoKey: String,
        videoName: String,
    ): List<SubtitleCandidate> {
        val base = videoName.substringBeforeLast('.')
        return source.candidatesFor(videoKey)
            .mapNotNull { file ->
                if (!SubtitleAutoMatcher.matches(base, file.name)) return@mapNotNull null
                val language = SubtitleLanguage.guessFromName(file.name)
                SubtitleCandidate(
                    file = file,
                    suggestedRole = if (SubtitleLanguage.isNativeSide(language)) TrackRole.TRANSLATION else TrackRole.LEARNING,
                    languageTag = language,
                )
            }
    }

    private fun displayTrackName(fileName: String): String {
        val stem = fileName.substringBeforeLast('.')
        val language = SubtitleLanguage.guessFromName(fileName) ?: return stem
        val withDot = ".$language"
        return if (stem.endsWith(withDot, ignoreCase = true)) stem.dropLast(withDot.length).ifBlank { stem } else stem
    }
}

/** Filename-based matching used by auto-loading (SUB-7). */
object SubtitleAutoMatcher {
    /**
     * True when [subtitleFileName] belongs to [videoBaseName]: identical stem, or the stem plus a
     * language/quality suffix, e.g. `Movie.en.srt` or `Movie [1080p].srt` for `Movie.mkv`.
     */
    fun matches(videoBaseName: String, subtitleFileName: String): Boolean {
        val subtitleBase = subtitleFileName.substringBeforeLast('.')
        if (subtitleBase.isBlank() || videoBaseName.isBlank()) return false
        if (subtitleBase.equals(videoBaseName, ignoreCase = true)) return true
        if (!subtitleBase.startsWith(videoBaseName, ignoreCase = true)) return false
        val rest = subtitleBase.substring(videoBaseName.length)
        return rest.length > 1 && (rest[0] == '.' || rest[0] == '_' || rest[0] == '-' || rest[0] == ' ')
    }
}
