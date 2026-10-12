package com.sublearn.core.translate

import com.sublearn.core.common.AppResult
import com.sublearn.core.common.SubLearnError
import com.sublearn.core.settings.TranslationSettings
import com.sublearn.core.settings.TranslationSourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class HttpFetchResult(
    val code: Int,
    val body: String,
)

fun interface HttpFetcher {
    suspend fun get(url: String, timeoutMs: Long): HttpFetchResult
}

class OkHttpFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3_500, TimeUnit.MILLISECONDS)
        .readTimeout(3_500, TimeUnit.MILLISECONDS)
        .build(),
) : HttpFetcher {
    override suspend fun get(url: String, timeoutMs: Long): HttpFetchResult = withContext(Dispatchers.IO) {
        val c = client.newBuilder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .get()
            .build()
        c.newCall(req).execute().use { resp ->
            HttpFetchResult(
                code = resp.code,
                body = resp.body?.string().orEmpty(),
            )
        }
    }
}

/**
 * Optional online translation source modeled on ProudVocab (`D-59`).
 *
 * Privacy invariants:
 * - Disabled by default (`ONLINE_GOOGLE` not in `DEFAULT_ENABLED_SOURCES`).
 * - Blocked whenever `TranslationSettings.offlineOnly == true` or `!onlinePrivacyAccepted`.
 * - Sends ONLY the normalized target word/phrase (`<= 240` chars), never subtitle lines or metadata.
 */
class OnlineGoogleTranslationEngine(
    private val settingsProvider: () -> TranslationSettings = { TranslationSettings() },
    private val fetcher: HttpFetcher = OkHttpFetcher(),
) : TranslationEngine {

    override val id: String = "ONLINE_GOOGLE"
    override val kind: TranslationSourceKind = TranslationSourceKind.ONLINE_GOOGLE
    override val isOffline: Boolean = false

    override suspend fun isAvailable(sourceLanguage: String, targetLanguage: String): Boolean {
        val s = settingsProvider()
        return !s.offlineOnly &&
            s.onlinePrivacyAccepted &&
            TranslationSourceKind.ONLINE_GOOGLE in s.enabledSources
    }

    override suspend fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
    ): AppResult<TranslationEngineHit> {
        val s = settingsProvider()
        if (s.offlineOnly) {
            return AppResult.failure(
                SubLearnError(SubLearnError.Kind.PermissionDenied, "Offline-only mode is enabled"),
            )
        }
        if (!s.onlinePrivacyAccepted) {
            return AppResult.failure(
                SubLearnError(SubLearnError.Kind.PermissionDenied, "Online translation privacy notice has not been accepted"),
            )
        }

        val sanitized = sanitizeQuery(text)
        if (sanitized.isEmpty()) {
            return AppResult.failure(SubLearnError(SubLearnError.Kind.MalformedInput, "Empty query"))
        }

        val startMs = System.currentTimeMillis()
        val host = s.onlineCustomHost.trim().trimEnd('/').ifEmpty { TranslationSettings.DEFAULT_ONLINE_HOST }
        val primaryUrl = buildUrl(s.onlinePrimaryUrlTemplate, host, sourceLanguage, targetLanguage, sanitized)
        val fallbackUrl = buildUrl(s.onlineFallbackUrlTemplate, host, sourceLanguage, targetLanguage, sanitized)

        val primaryAttempt = runCatching { fetcher.get(primaryUrl, s.onlineTimeoutMs) }.getOrNull()
        if (primaryAttempt != null && primaryAttempt.code in 200..299) {
            val parsed = parseResponse(primaryAttempt.body)
            if (parsed != null) {
                return AppResult.success(
                    parsed.copy(latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(0L)),
                )
            }
        }

        val fallbackAttempt = runCatching { fetcher.get(fallbackUrl, s.onlineTimeoutMs) }.getOrElse { t ->
            return AppResult.failure(
                SubLearnError(SubLearnError.Kind.NetworkUnavailable, "Online translation failed: ${t.message}", t),
            )
        }
        if (fallbackAttempt.code !in 200..299) {
            return AppResult.failure(
                SubLearnError(SubLearnError.Kind.NetworkUnavailable, "HTTP ${fallbackAttempt.code} from online translation"),
            )
        }
        val parsedFallback = parseResponse(fallbackAttempt.body)
            ?: return AppResult.failure(
                SubLearnError(SubLearnError.Kind.MalformedInput, "Unrecognized online translation payload"),
            )

        return AppResult.success(
            parsedFallback.copy(latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(0L)),
        )
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Ensures only the tapped word or short phrase is transmitted (`<= 240` chars, single line).
         */
        fun sanitizeQuery(raw: String): String =
            raw.lineSequence().firstOrNull().orEmpty().trim().replace(Regex("\\s+"), " ").take(240)

        fun buildUrl(
            template: String,
            host: String,
            sourceLanguage: String,
            targetLanguage: String,
            query: String,
        ): String {
            val encodedQ = URLEncoder.encode(query, Charsets.UTF_8.name())
            val encodedSl = URLEncoder.encode(sourceLanguage, Charsets.UTF_8.name())
            val encodedTl = URLEncoder.encode(targetLanguage, Charsets.UTF_8.name())
            return template
                .replace("{host}", host)
                .replace("{sl}", encodedSl)
                .replace("{tl}", encodedTl)
                .replace("{q}", encodedQ)
        }

        fun parseResponse(body: String): TranslationEngineHit? {
            if (body.isBlank()) return null
            val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return null
            return when (root) {
                is JsonObject -> parseDj1Object(root)
                is JsonArray -> parseLegacyArray(root)
                else -> null
            }
        }

        private fun parseDj1Object(obj: JsonObject): TranslationEngineHit? {
            val sentences = obj["sentences"] as? JsonArray
            val transBuilder = StringBuilder()
            var phonetic: String? = null

            if (sentences != null) {
                for (el in sentences) {
                    val sObj = el as? JsonObject ?: continue
                    val trans = sObj["trans"]?.jsonPrimitive?.contentOrNull?.trim()
                    if (!trans.isNullOrEmpty()) {
                        if (transBuilder.isNotEmpty()) transBuilder.append(' ')
                        transBuilder.append(trans)
                    }
                    val srcTranslit = sObj["src_translit"]?.jsonPrimitive?.contentOrNull?.trim()
                    if (!srcTranslit.isNullOrEmpty() && phonetic == null) {
                        phonetic = srcTranslit
                    }
                }
            }

            val posGlosses = ArrayList<Pair<String?, List<String>>>()
            val dict = obj["dict"] as? JsonArray
            if (dict != null) {
                for (dEl in dict) {
                    val dObj = dEl as? JsonObject ?: continue
                    val pos = dObj["pos"]?.jsonPrimitive?.contentOrNull?.trim()?.uppercase()
                    val termsArr = dObj["terms"] as? JsonArray ?: continue
                    val terms = termsArr.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                    if (terms.isNotEmpty()) {
                        posGlosses += pos to terms
                    }
                }
            }

            val primaryText = transBuilder.toString().trim().ifEmpty {
                posGlosses.flatMap { it.second }.firstOrNull().orEmpty()
            }
            if (primaryText.isEmpty()) return null

            return TranslationEngineHit(
                text = primaryText,
                posGlosses = posGlosses,
                phonetic = phonetic,
                sourceKind = TranslationSourceKind.ONLINE_GOOGLE,
            )
        }

        private fun parseLegacyArray(arr: JsonArray): TranslationEngineHit? {
            val sentences = arr.getOrNull(0) as? JsonArray ?: return null
            val sb = StringBuilder()
            for (item in sentences) {
                val seg = item as? JsonArray ?: continue
                val t = seg.getOrNull(0)?.let { (it as? JsonElement)?.jsonPrimitive?.contentOrNull }?.trim()
                if (!t.isNullOrEmpty()) {
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(t)
                }
            }
            val text = sb.toString().trim()
            if (text.isEmpty()) return null
            return TranslationEngineHit(
                text = text,
                sourceKind = TranslationSourceKind.ONLINE_GOOGLE,
            )
        }
    }
}
