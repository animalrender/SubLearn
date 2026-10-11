package com.sublearn.core.nlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternCompilerTest {

    @Test
    fun `literal words compile to Literal elements`() {
        val elements = PatternCompiler.compile("get off")
        assertEquals(2, elements.size)
        assertTrue(elements[0] is PatternElement.Literal)
        assertTrue(elements[1] is PatternElement.Literal)
        assertEquals("get", (elements[0] as PatternElement.Literal).word)
        assertEquals("off", (elements[1] as PatternElement.Literal).word)
    }

    @Test
    fun `POSS token compiles to Possessive`() {
        val elements = PatternCompiler.compile("POSS mind")
        assertEquals(2, elements.size)
        assertTrue(elements[0] is PatternElement.Possessive)
        assertTrue(elements[1] is PatternElement.Literal)
    }

    @Test
    fun `REFL token compiles to Reflexive`() {
        val elements = PatternCompiler.compile("enjoy REFL")
        assertEquals(2, elements.size)
        assertTrue(elements[1] is PatternElement.Reflexive)
    }

    @Test
    fun `be forms compile to BeVerb`() {
        val elements = PatternCompiler.compile("be fed up")
        assertEquals(3, elements.size)
        assertTrue(elements[0] is PatternElement.BeVerb)
    }

    @Test
    fun `NP slot compiles to NounPhrase`() {
        val elements = PatternCompiler.compile("give {NP} up")
        assertEquals(3, elements.size)
        assertTrue(elements[1] is PatternElement.NounPhrase)
    }

    @Test
    fun `optional parts are removed by converter before compilation`() {
        // The converter in build_lexicon.py strips (or X) and (all) before storing
        // So the pattern stored in the DB would already be clean
        val elements = PatternCompiler.compile("get the goods on")
        assertEquals(4, elements.size)
    }

    @Test
    fun `complex pattern compiles correctly`() {
        val elements = PatternCompiler.compile("POSS mind BE made up")
        assertEquals(5, elements.size)
        assertTrue(elements[0] is PatternElement.Possessive)
        assertTrue(elements[1] is PatternElement.Literal)
        assertTrue(elements[2] is PatternElement.BeVerb)
        assertTrue(elements[3] is PatternElement.Literal)
        assertTrue(elements[4] is PatternElement.Literal)
    }
}
