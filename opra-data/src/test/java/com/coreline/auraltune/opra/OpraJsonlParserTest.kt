package com.coreline.auraltune.opra

import com.coreline.auraltune.opra.model.OpraFilterType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpraJsonlParserTest {

    private val parser = OpraJsonlParser()
    private fun parse(vararg lines: String) = parser.parse(lines.asSequence())

    private val vendor = """{"type":"vendor","id":"pud","data":{"name":"Pud"}}"""
    private val product =
        """{"type":"product","id":"pud::vogue","data":{"name":"Vogue","type":"headphones","subtype":"over_the_ear","vendor_id":"pud"}}"""

    /** fixture 1: a normal product + eq become a catalog entry and a supported profile. */
    @Test
    fun fixture1_normalProductEq_becomesEntryAndProfile() {
        val eq =
            """{"type":"eq","id":"pud:vogue::ora","data":{"author":"oratory1990","details":"Harman Target","link":"https://x","type":"parametric_eq","parameters":{"gain_db":-7,"bands":[{"type":"peak_dip","frequency":200,"gain_db":-7,"q":6},{"type":"high_shelf","frequency":7000,"gain_db":7,"q":0.6}]},"product_id":"pud::vogue"}}"""
        val r = parse(vendor, product, eq)

        assertEquals(1, r.vendors.size)
        assertEquals(1, r.products.size)
        assertEquals(1, r.profiles.size)
        assertEquals(1, r.catalogEntries.size)
        assertEquals(0, r.malformedLines)
        assertEquals(0, r.orphanProfiles)

        val p = r.profiles[0]
        assertTrue(p.isSupported)
        assertEquals(2, p.filters.size)
        assertEquals(OpraFilterType.PEAKING, p.filters[0].type)
        assertEquals(OpraFilterType.HIGH_SHELF, p.filters[1].type)
        assertEquals(-7f, p.preampDb, 0.001f)

        val e = r.catalogEntries[0]
        assertEquals("Vogue", e.productName)
        assertEquals("Pud", e.vendorName)
        assertTrue(e.isSupported)
    }

    /** fixture 2: a band the engine cannot realize (low_pass) excludes the whole profile. */
    @Test
    fun fixture2_unsupportedFilterType_excludesProfile() {
        val eq =
            """{"type":"eq","id":"pud:vogue::lp","data":{"author":"A","type":"parametric_eq","parameters":{"gain_db":0,"bands":[{"type":"peak_dip","frequency":1000,"gain_db":3,"q":1},{"type":"low_pass","frequency":12000,"slope":12}]},"product_id":"pud::vogue"}}"""
        val r = parse(vendor, product, eq)

        val p = r.profiles[0]
        assertFalse("low_pass band must make the profile unsupported", p.isSupported)
        assertEquals("unsupported filter type", p.unsupportedReason)
        assertFalse(r.catalogEntries[0].isSupported)
    }

    /** fixture 3: more bands than the engine's section count are EXCLUDED (not truncated). */
    @Test
    fun fixture3_tooManyBands_excludedNotTruncated() {
        val bands = (1..11).joinToString(",") {
            """{"type":"peak_dip","frequency":${100 * it},"gain_db":1,"q":1}"""
        }
        val eq =
            """{"type":"eq","id":"pud:vogue::big","data":{"author":"A","type":"parametric_eq","parameters":{"gain_db":0,"bands":[$bands]},"product_id":"pud::vogue"}}"""
        val r = parse(vendor, product, eq)

        val p = r.profiles[0]
        assertEquals(11, p.filters.size) // raw bands preserved (not silently dropped)
        assertFalse(p.isSupported)
        assertNotNull(p.unsupportedReason)
        assertTrue(p.unsupportedReason!!.contains("too many"))
    }

    /** fixture 4: author / details / link attribution is preserved end-to-end. */
    @Test
    fun fixture4_attributionPreserved() {
        val eq =
            """{"type":"eq","id":"pud:vogue::ora","data":{"author":"oratory1990","details":"Harman Target","link":"https://example.org/eq.pdf","type":"parametric_eq","parameters":{"gain_db":-5,"bands":[{"type":"peak_dip","frequency":1000,"gain_db":2,"q":1}]},"product_id":"pud::vogue"}}"""
        val r = parse(vendor, product, eq)

        val p = r.profiles[0]
        assertEquals("oratory1990", p.author)
        assertEquals("Harman Target", p.details)
        assertEquals("https://example.org/eq.pdf", p.link)
        assertEquals("CC BY-SA 4.0", p.license)
        assertEquals("oratory1990", r.catalogEntries[0].author)
        assertEquals("CC BY-SA 4.0", r.catalogEntries[0].license)
    }

    /** fixture 5: a broken JSONL row is counted and skipped — it does not abort the whole import. */
    @Test
    fun fixture5_brokenRow_isCountedNotFatal() {
        val broken = """{"type":"eq","id":"x", BROKEN not json"""
        val eq =
            """{"type":"eq","id":"pud:vogue::ora","data":{"author":"A","type":"parametric_eq","parameters":{"gain_db":0,"bands":[{"type":"peak_dip","frequency":1000,"gain_db":2,"q":1}]},"product_id":"pud::vogue"}}"""
        val r = parse(vendor, broken, product, eq, "")

        assertEquals(1, r.malformedLines)
        assertEquals("valid lines after a broken one are still parsed", 1, r.profiles.size)
        assertEquals(1, r.vendors.size)
        assertEquals(1, r.products.size)
    }

    /** orphan eq (product_id not present) is surfaced best-effort and counted. */
    @Test
    fun orphanProfile_isCountedAndSurfaced() {
        val eq =
            """{"type":"eq","id":"ghost:none::a","data":{"author":"A","type":"parametric_eq","parameters":{"gain_db":0,"bands":[{"type":"peak_dip","frequency":1000,"gain_db":2,"q":1}]},"product_id":"ghost::none"}}"""
        val r = parse(eq)

        assertEquals(1, r.orphanProfiles)
        assertEquals(1, r.catalogEntries.size)
        assertEquals(OpraJsonlParser.UNKNOWN_PRODUCT, r.catalogEntries[0].productName)
    }
}
