package com.coreline.autoeq.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexMdParserTest {

    @Test
    fun `standard line parses name path and source`() {
        val line = "- [Sennheiser HD 600](./oratory1990/over-ear/Sennheiser%20HD%20600) by oratory1990"
        val entry = IndexMdParser.parseLine(line)
        assertNotNull(entry)
        assertEquals("Sennheiser HD 600", entry!!.name)
        assertEquals("oratory1990/over-ear/Sennheiser HD 600", entry.relativePath)
        assertEquals("oratory1990", entry.measuredBy)
        assertTrue("id should be hex", entry.id.matches(Regex("[0-9a-f]{24}")))
    }

    @Test
    fun `name with parentheses survives parsing`() {
        val line = "- [64 Audio A12t (m15 Apex module)](./crinacle/iem/64%20Audio%20A12t%20(m15%20Apex%20module)) by crinacle"
        val entry = IndexMdParser.parseLine(line)
        assertNotNull(entry)
        assertEquals("64 Audio A12t (m15 Apex module)", entry!!.name)
        assertEquals("crinacle/iem/64 Audio A12t (m15 Apex module)", entry.relativePath)
        assertEquals("crinacle", entry.measuredBy)
    }

    @Test
    fun `line with on Rig drops rig from measuredBy`() {
        val line = "- [Beyerdynamic DT 880](./Rtings/over-ear/Beyerdynamic%20DT%20880) by Rtings on HMS"
        val entry = IndexMdParser.parseLine(line)
        assertNotNull(entry)
        assertEquals("Beyerdynamic DT 880", entry!!.name)
        assertEquals("Rtings", entry.measuredBy)
        assertEquals("Rtings/over-ear/Beyerdynamic DT 880", entry.relativePath)
    }

    @Test
    fun `non-list line returns null`() {
        assertNull(IndexMdParser.parseLine(""))
        assertNull(IndexMdParser.parseLine("# Heading"))
        assertNull(IndexMdParser.parseLine("Some prose paragraph."))
        assertNull(IndexMdParser.parseLine("- not a link"))
    }

    @Test
    fun `parse dedupes by name keeping highest priority source`() {
        val text = """
            # Headphones

            - [HiFiMan Sundara](./crinacle/over-ear/HiFiMan%20Sundara) by crinacle
            - [HiFiMan Sundara](./oratory1990/over-ear/HiFiMan%20Sundara) by oratory1990
            - [HiFiMan Sundara](./Rtings/over-ear/HiFiMan%20Sundara) by Rtings on HMS
            - [Sony WH-1000XM4](./oratory1990/over-ear/Sony%20WH-1000XM4) by oratory1990
        """.trimIndent()

        val entries = IndexMdParser.parse(text)
        assertEquals(2, entries.size)
        val sundara = entries.first { it.name == "HiFiMan Sundara" }
        assertEquals("oratory1990", sundara.measuredBy)
    }

    @Test
    fun `parse sorts results case-insensitively by name`() {
        val text = """
            - [Zenith Foo](./oratory1990/over-ear/Zenith%20Foo) by oratory1990
            - [apple Bar](./crinacle/iem/apple%20Bar) by crinacle
            - [Beta](./oratory1990/over-ear/Beta) by oratory1990
        """.trimIndent()
        val entries = IndexMdParser.parse(text)
        assertEquals(listOf("apple Bar", "Beta", "Zenith Foo"), entries.map { it.name })
    }

    @Test
    fun `id is stable for same source path name`() {
        val a = IndexMdParser.parseLine(
            "- [Sennheiser HD 600](./oratory1990/over-ear/Sennheiser%20HD%20600) by oratory1990",
        )
        val b = IndexMdParser.parseLine(
            "- [Sennheiser HD 600](./oratory1990/over-ear/Sennheiser%20HD%20600) by oratory1990",
        )
        assertEquals(a!!.id, b!!.id)
    }

    @Test
    fun `id differs across sources for the same name`() {
        val a = IndexMdParser.parseLine(
            "- [Sony WH-1000XM4](./oratory1990/over-ear/Sony%20WH-1000XM4) by oratory1990",
        )
        val b = IndexMdParser.parseLine(
            "- [Sony WH-1000XM4](./crinacle/over-ear/Sony%20WH-1000XM4) by crinacle",
        )
        assertNotEquals(a!!.id, b!!.id)
    }
}
