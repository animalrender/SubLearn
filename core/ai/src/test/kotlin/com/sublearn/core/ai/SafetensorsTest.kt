package com.sublearn.core.ai

import com.sublearn.core.common.AppResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The safetensors reader is written against the format description rather than a library, so the
 * parsing rules are pinned here with files this test writes itself.
 */
class SafetensorsTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File.createTempFile("safetensors", "").let { file ->
            file.delete()
            file.mkdirs()
            file
        }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** Write a safetensors file from a header string plus raw bytes. */
    private fun writeFile(name: String, header: String, data: ByteArray): File {
        val file = File(dir, name)
        val headerBytes = header.toByteArray(Charsets.UTF_8)
        RandomAccessFile(file, "rw").use { raf ->
            raf.writeLong(headerBytes.size.toLong())
            raf.write(headerBytes)
            raf.write(data)
        }
        return file
    }

    private fun floats(vararg values: Float): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    @Test
    fun `read the header length, dtype, shape and offsets`() {
        val file = writeFile(
            "one.safetensors",
            """{"weight":{"dtype":"F32","shape":[2,3],"data_offsets":[0,24]}}""",
            floats(1f, 2f, 3f, 4f, 5f, 6f),
        )

        val result = Safetensors.open(file)
        assertTrue(result is AppResult.Success)
        val tensors = (result as AppResult.Success).value
        val info = tensors.tensors["weight"]
        assertNotNull(info)
        assertEquals("F32", info!!.dtype)
        assertEquals(2, info.rowCount)
        assertEquals(3, info.rowLength)
        assertEquals(0L, info.start)
        assertEquals(24L, info.end)
    }

    @Test
    fun `read a whole tensor as floats`() {
        val file = writeFile(
            "read.safetensors",
            """{"w":{"dtype":"F32","shape":[2,2],"data_offsets":[0,16]}}""",
            floats(1.5f, -2.25f, 3f, 4f),
        )
        Safetensors.open(file).let { result ->
            (result as AppResult.Success).value.use { safetensors ->
                val values = safetensors.readTensor("w")
                assertNotNull(values)
                assertEquals(listOf(1.5f, -2.25f, 3f, 4f), values!!.toList())
            }
        }
    }

    @Test
    fun `read a single row of a matrix`() {
        val file = writeFile(
            "rows.safetensors",
            """{"w":{"dtype":"F32","shape":[3,2],"data_offsets":[0,24]}}""",
            floats(1f, 2f, 3f, 4f, 5f, 6f),
        )
        (Safetensors.open(file) as AppResult.Success).value.use { safetensors ->
            assertEquals(listOf(3f, 4f), safetensors.readRow("w", 1)!!.toList())
            assertNull("an out-of-range row is null, not an exception", safetensors.readRow("w", 9))
            assertNull("an unknown tensor is null", safetensors.readRow("nope", 0))
        }
    }

    @Test
    fun `several tensors keep their own offsets`() {
        val file = writeFile(
            "multi.safetensors",
            """{"a":{"dtype":"F32","shape":[1,2],"data_offsets":[0,8]},""" +
                """"b":{"dtype":"F32","shape":[1,2],"data_offsets":[8,16]}}""",
            floats(9f, 8f, 7f, 6f),
        )
        (Safetensors.open(file) as AppResult.Success).value.use { safetensors ->
            assertEquals(listOf(9f, 8f), safetensors.readTensor("a")!!.toList())
            assertEquals(listOf(7f, 6f), safetensors.readTensor("b")!!.toList())
        }
    }

    @Test
    fun `metadata is skipped rather than treated as a tensor`() {
        val file = writeFile(
            "meta.safetensors",
            """{"__metadata__":{"format":"pt"},"w":{"dtype":"F32","shape":[1,1],"data_offsets":[0,4]}}""",
            floats(42f),
        )
        (Safetensors.open(file) as AppResult.Success).value.use { safetensors ->
            assertNull(safetensors.tensors["__metadata__"])
            assertEquals(setOf("w"), safetensors.tensors.keys)
            assertEquals(42f, safetensors.readTensor("w")!![0], 0f)
        }
    }

    @Test
    fun `an empty shape tensor reads as a single scalar`() {
        val file = writeFile(
            "scalar.safetensors",
            """{"s":{"dtype":"F32","shape":[],"data_offsets":[0,4]}}""",
            floats(7f),
        )
        (Safetensors.open(file) as AppResult.Success).value.use { safetensors ->
            assertEquals(listOf(7f), safetensors.readTensor("s")!!.toList())
        }
    }

    @Test
    fun `a truncated file is reported rather than read past the end`() {
        val file = File(dir, "short.safetensors")
        file.writeBytes(byteArrayOf(1, 2, 3))

        val result = Safetensors.open(file)
        assertTrue(result is AppResult.Failure)
    }

    @Test
    fun `a header length beyond the file is rejected`() {
        val file = File(dir, "bad-header.safetensors")
        RandomAccessFile(file, "rw").use { raf ->
            raf.writeLong(9_999_999L)
            raf.write(ByteArray(16))
        }
        assertTrue(Safetensors.open(file) is AppResult.Failure)
    }

    @Test
    fun `a missing file is reported as not found`() {
        val result = Safetensors.open(File(dir, "absent.safetensors"))
        assertTrue(result is AppResult.Failure)
        assertEquals(
            com.sublearn.core.common.SubLearnError.Kind.NotFound,
            (result as AppResult.Failure).error.kind,
        )
    }

    @Test
    fun `a header with no tensors is rejected`() {
        val file = writeFile("empty.safetensors", """{"__metadata__":{"x":"1"}}""", ByteArray(0))
        assertTrue(Safetensors.open(file) is AppResult.Failure)
    }

    @Test
    fun `half precision floats are converted`() {
        // 1.0 in IEEE 754 half precision is 0x3C00.
        assertEquals(1.0f, Safetensors.halfToFloat(0x3C00.toShort()), 1e-6f)
        // -2.0 is 0xC000.
        assertEquals(-2.0f, Safetensors.halfToFloat(0xC000.toShort()), 1e-6f)
        assertEquals(0f, Safetensors.halfToFloat(0), 0f)
    }

    @Test
    fun `bfloat16 uses the top half of a float`() {
        // 1.0f is 0x3F800000, so its bfloat16 form is 0x3F80.
        assertEquals(1.0f, Safetensors.bfloat16ToFloat(0x3F80.toShort()), 1e-6f)
        assertEquals(0f, Safetensors.bfloat16ToFloat(0), 0f)
    }

    @Test
    fun `an F16 tensor is upcast on read`() {
        val buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(0x3C00.toShort())  // 1.0
        buffer.putShort(0xC000.toShort())  // -2.0
        val file = writeFile(
            "f16.safetensors",
            """{"w":{"dtype":"F16","shape":[1,2],"data_offsets":[0,4]}}""",
            buffer.array(),
        )
        (Safetensors.open(file) as AppResult.Success).value.use { safetensors ->
            assertEquals(listOf(1.0f, -2.0f), safetensors.readTensor("w")!!.toList())
        }
    }

    @Test
    fun `an unsupported dtype reads as null instead of garbage`() {
        val file = writeFile(
            "str.safetensors",
            """{"w":{"dtype":"STRING","shape":[1,1],"data_offsets":[0,4]}}""",
            floats(0f),
        )
        (Safetensors.open(file) as AppResult.Success).value.use { safetensors ->
            assertNull(safetensors.readTensor("w"))
        }
    }

    @Test
    fun `header parsing ignores whitespace between entries`() {
        val parsed = Safetensors.parseHeader(
            """{
              "a" : { "dtype" : "F32" , "shape" : [ 1 , 2 ] , "data_offsets" : [ 0 , 8 ] }
            }""",
        )
        assertNotNull(parsed)
        assertEquals(setOf("a"), parsed!!.keys)
        assertEquals(2, parsed["a"]!!.rowLength)
    }
}
