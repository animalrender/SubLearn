package com.sublearn.core.ai

import com.sublearn.core.common.AppResult
import com.sublearn.core.common.SubLearnError
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * Minimal reader for the `safetensors` container used by `minishlab/potion-base-8M`.
 *
 * The format is an 8-byte little-endian header length followed by a JSON header describing each
 * tensor's dtype, shape and byte range, then the raw tensor data. Only what the Balanced profile
 * needs is supported: `F32` tensors read as a whole row, and `F16`/`BF16` rows upcast on read, so
 * a quantised model works without a second copy of the weights in memory.
 */
object Safetensors {

    /** One tensor's location inside the file. */
    data class TensorInfo(
        val name: String,
        val dtype: String,
        val shape: LongArray,
        val start: Long,
        val end: Long,
    ) {
        val rowCount: Int get() = (shape.firstOrNull() ?: 0L).toInt()
        val rowLength: Int get() = if (shape.size > 1) shape[1].toInt() else 1

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is TensorInfo) return false
            return name == other.name && dtype == other.dtype && start == other.start &&
                end == other.end && shape.contentEquals(other.shape)
        }

        override fun hashCode(): Int {
            var result = name.hashCode()
            result = 31 * result + dtype.hashCode()
            result = 31 * result + start.hashCode()
            result = 31 * result + end.hashCode()
            result = 31 * result + shape.contentHashCode()
            return result
        }
    }

    /**
     * A model file plus the offsets of every tensor in it.
     *
     * The constructor is private and [create] is the door: Kotlin's `private` is scoped to the class
     * itself, not to the enclosing object, so the outer reader cannot call it directly.
     */
    class SafetensorsFile private constructor(
        private val file: File,
        val tensors: Map<String, TensorInfo>,
        val headerLength: Long,
    ) : AutoCloseable {
        private val channel = RandomAccessFile(file, "r").channel

        /** Read the whole tensor as floats. */
        fun readTensor(name: String): FloatArray? {
            val info = tensors[name] ?: return null
            val elementSize = elementSizeOf(info.dtype) ?: return null
            val count = (info.end - info.start) / elementSize
            if (count <= 0 || count > Int.MAX_VALUE / elementSize) return null

            val bytes = ByteBuffer.allocate((count * elementSize).toInt()).order(ByteOrder.LITTLE_ENDIAN)
            channel.position(headerLength + info.start)
            var read = 0
            while (read < bytes.capacity()) {
                val n = channel.read(bytes)
                if (n <= 0) break
                read += n
            }
            bytes.flip()

            return when (info.dtype.uppercase()) {
                "F32" -> FloatArray(count.toInt()) { bytes.float }
                "F16" -> FloatArray(count.toInt()) { halfToFloat(bytes.short) }
                "BF16" -> FloatArray(count.toInt()) { bfloat16ToFloat(bytes.short) }
                else -> null
            }
        }

        /** Read one row of a 2-D tensor without loading the rest of it. */
        fun readRow(name: String, row: Int): FloatArray? {
            val info = tensors[name] ?: return null
            if (row < 0 || row >= info.rowCount) return null
            val all = readTensor(name) ?: return null
            val width = info.rowLength
            val from = row * width
            if (from + width > all.size) return null
            return all.copyOfRange(from, from + width)
        }

        override fun close() {
            runCatching { channel.close() }
        }

        companion object {
            /** The only way to build one: the file is opened and the header parsed before this. */
            internal fun create(
                file: File,
                tensors: Map<String, TensorInfo>,
                headerLength: Long,
            ): SafetensorsFile = SafetensorsFile(file, tensors, headerLength)
        }
    }

    /**
     * Parse the header of [file].
     *
     * Returns a failure rather than throwing for a truncated or non-safetensors file, because the
     * download path can produce exactly that and the caller has to recover.
     */
    fun open(file: File): AppResult<SafetensorsFile> {
        if (!file.isFile || file.length() < 8) {
            return AppResult.failure(SubLearnError(SubLearnError.Kind.NotFound, "safetensors file is missing or too short"))
        }
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val headerLength = java.lang.Long.reverseBytes(raf.readLong())
                if (headerLength <= 0 || headerLength > MAX_HEADER_BYTES || 8 + headerLength > file.length()) {
                    return AppResult.failure(
                        SubLearnError(SubLearnError.Kind.MalformedInput, "safetensors header length is not usable: $headerLength"),
                    )
                }
                val headerBytes = ByteArray(headerLength.toInt())
                raf.readFully(headerBytes)
                val tensors = parseHeader(String(headerBytes, Charsets.UTF_8))
                    ?: return AppResult.failure(
                        SubLearnError(SubLearnError.Kind.MalformedInput, "safetensors header is not valid JSON"),
                    )
                val dataBytes = file.length() - 8 - headerLength
                if (tensors.values.any { !validTensor(it, dataBytes) }) {
                    return AppResult.failure(
                        SubLearnError(SubLearnError.Kind.MalformedInput, "safetensors tensor shape or byte range is invalid"),
                    )
                }
                AppResult.success(SafetensorsFile.create(file, tensors, 8 + headerLength))
            }
        } catch (t: Throwable) {
            AppResult.failure(SubLearnError.from(t))
        }
    }

    private fun validTensor(info: TensorInfo, dataBytes: Long): Boolean {
        if (info.start < 0L || info.end < info.start || info.end > dataBytes) return false
        var count = 1L
        for (dimension in info.shape) {
            if (dimension < 0L || dimension > Int.MAX_VALUE) return false
            if (dimension != 0L && count > Long.MAX_VALUE / dimension) return false
            count *= dimension
        }
        val size = elementSizeOf(info.dtype) ?: return true
        return count <= Long.MAX_VALUE / size && info.end - info.start == count * size
    }

    /**
     * Parse the header JSON. Written by hand rather than pulling in a JSON parser because the
     * format is fixed and a dependency here would be felt in every build variant.
     */
    internal fun parseHeader(json: String): Map<String, TensorInfo>? {
        val out = LinkedHashMap<String, TensorInfo>()

        var index = 0
        while (index < json.length) {
            val nameStart = json.indexOf('"', index)
            if (nameStart < 0) break
            val nameEnd = json.indexOf('"', nameStart + 1)
            if (nameEnd < 0) break
            val name = json.substring(nameStart + 1, nameEnd)

            val objectStart = json.indexOf('{', nameEnd)
            if (objectStart < 0) break
            val objectEnd = json.indexOf('}', objectStart)
            if (objectEnd < 0) break

            val body = json.substring(objectStart + 1, objectEnd)
            index = objectEnd + 1

            if (name == "__metadata__" || body.isBlank()) continue

            val dtype = stringValue(body, "dtype") ?: continue
            val shape = longArrayValue(body, "shape") ?: continue
            val offsets = longArrayValue(body, "data_offsets") ?: continue
            if (offsets.size != 2) continue

            out[name] = TensorInfo(name, dtype, shape, offsets[0], offsets[1])
        }

        return out.ifEmpty { null }
    }

    private fun stringValue(body: String, key: String): String? {
        val keyIndex = body.indexOf("\"$key\"")
        if (keyIndex < 0) return null
        val colon = body.indexOf(':', keyIndex)
        if (colon < 0) return null
        val open = body.indexOf('"', colon)
        if (open < 0) return null
        val close = body.indexOf('"', open + 1)
        if (close < 0) return null
        return body.substring(open + 1, close)
    }

    private fun longArrayValue(body: String, key: String): LongArray? {
        val keyIndex = body.indexOf("\"$key\"")
        if (keyIndex < 0) return null
        val open = body.indexOf('[', keyIndex)
        if (open < 0) return null
        val close = body.indexOf(']', open)
        if (close < 0) return null
        val content = body.substring(open + 1, close).trim()
        if (content.isEmpty()) return LongArray(0)
        return content.split(',').mapNotNull { it.trim().toLongOrNull() }.toLongArray()
    }

    private fun elementSizeOf(dtype: String): Int? = when (dtype.uppercase()) {
        "F32", "I32" -> 4
        "F16", "BF16", "I16" -> 2
        "I8", "U8", "BOOL" -> 1
        "F64", "I64" -> 8
        else -> null
    }

    /** IEEE 754 half precision to float. */
    internal fun halfToFloat(bits: Short): Float {
        val value = bits.toInt() and 0xFFFF
        val sign = value shr 15 and 0x1
        val exponent = value shr 10 and 0x1F
        val mantissa = value and 0x3FF

        val asFloat = when (exponent) {
            0 -> {
                if (mantissa == 0) 0f else mantissa.toFloat() * 2f.pow(-24)
            }
            0x1F -> if (mantissa == 0) Float.POSITIVE_INFINITY else Float.NaN
            else -> (1f + mantissa / 1024f) * 2f.pow((exponent - 15).toFloat())
        }
        return if (sign == 1) -asFloat else asFloat
    }

    /** Brain float 16 to float: the top 16 bits of an IEEE 754 single. */
    internal fun bfloat16ToFloat(bits: Short): Float =
        Float.fromBits((bits.toInt() and 0xFFFF) shl 16)

    private const val MAX_HEADER_BYTES = 100L * 1024 * 1024
}
