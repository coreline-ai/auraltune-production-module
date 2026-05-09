package com.coreline.autoeq.parser

import com.coreline.audio.EqFilterType
import com.coreline.autoeq.model.AutoEqProfile
import com.coreline.autoeq.model.AutoEqSource
import com.coreline.autoeq.model.ParseError
import com.coreline.autoeq.model.ParseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParametricEqParserTest {

    private fun parse(text: String): ParseResult = ParametricEqParser.parse(
        text = text,
        name = "Sennheiser HD 600",
        id = "test-id",
        measuredBy = "oratory1990",
        source = AutoEqSource.FETCHED,
    )

    private fun success(text: String): AutoEqProfile {
        val res = parse(text)
        assertTrue("Expected Success but got $res", res is ParseResult.Success)
        return (res as ParseResult.Success).profile
    }

    private fun failure(text: String): ParseError {
        val res = parse(text)
        assertTrue("Expected Failure but got $res", res is ParseResult.Failure)
        return (res as ParseResult.Failure).error
    }

    @Test
    fun `parses oratory1990 hd600 sample`() {
        val text = """
            Preamp: -6.5 dB
            Filter 1: ON PK Fc 21 Hz Gain 4.0 dB Q 1.41
            Filter 2: ON PK Fc 105 Hz Gain -2.0 dB Q 0.7
            Filter 3: ON LSC Fc 105 Hz Gain 7.0 dB Q 0.71
            Filter 4: ON HSC Fc 10000 Hz Gain -1.5 dB Q 0.71
        """.trimIndent()

        val p = success(text)
        assertEquals("Sennheiser HD 600", p.name)
        assertEquals(AutoEqSource.FETCHED, p.source)
        assertEquals("oratory1990", p.measuredBy)
        assertEquals(-6.5f, p.preampDB, 0.001f)
        assertEquals(4, p.filters.size)
        assertEquals(EqFilterType.PEAKING, p.filters[0].type)
        assertEquals(21.0, p.filters[0].frequency, 0.001)
        assertEquals(1.41, p.filters[0].q, 0.001)
        assertEquals(EqFilterType.LOW_SHELF, p.filters[2].type)
        assertEquals(EqFilterType.HIGH_SHELF, p.filters[3].type)
        assertEquals(48000.0, p.optimizedSampleRate, 0.0)
    }

    @Test
    fun `strips utf8 bom and crlf line endings`() {
        val bom = '﻿'
        val text = "${bom}Preamp: -3.0 dB\r\n" +
            "Filter 1: ON PK Fc 1000 Hz Gain 1.0 dB Q 1.0\r\n"
        val p = success(text)
        assertEquals(-3.0f, p.preampDB, 0.001f)
        assertEquals(1, p.filters.size)
    }

    @Test
    fun `disabled filters are skipped`() {
        val text = """
            Preamp: 0.0 dB
            Filter 1: OFF PK Fc 100 Hz Gain 1.0 dB Q 1.0
            Filter 2: ON PK Fc 200 Hz Gain 1.0 dB Q 1.0
        """.trimIndent()
        val p = success(text)
        assertEquals(1, p.filters.size)
        assertEquals(200.0, p.filters[0].frequency, 0.0)
    }

    @Test
    fun `nan and inf are rejected per filter line`() {
        val text = """
            Preamp: 0.0 dB
            Filter 1: ON PK Fc NaN Hz Gain 1.0 dB Q 1.0
            Filter 2: ON PK Fc 1000 Hz Gain Infinity dB Q 1.0
            Filter 3: ON PK Fc 1000 Hz Gain 1.0 dB Q 1.0
        """.trimIndent()
        val p = success(text)
        assertEquals(1, p.filters.size)
        assertEquals(1000.0, p.filters[0].frequency, 0.0)
    }

    @Test
    fun `empty profile is rejected`() {
        assertEquals(ParseError.Empty, failure("   \n  \n"))
    }

    @Test
    fun `profile with only invalid filters returns NoValidFilters`() {
        val text = """
            Preamp: 0.0 dB
            Filter 1: ON PK Fc -100 Hz Gain 1.0 dB Q 1.0
            Filter 2: ON PK Fc 1000 Hz Gain 999 dB Q 1.0
        """.trimIndent()
        assertEquals(ParseError.NoValidFilters, failure(text))
    }

    @Test
    fun `eleven filters truncates to ten`() {
        val sb = StringBuilder("Preamp: 0.0 dB\n")
        repeat(11) { i ->
            sb.append("Filter ${i + 1}: ON PK Fc ${100 + i} Hz Gain 1.0 dB Q 1.0\n")
        }
        val p = success(sb.toString())
        assertEquals(AutoEqProfile.MAX_FILTERS, p.filters.size)
    }

    @Test
    fun `oversized file is rejected`() {
        val big = "x".repeat((ParametricEqParser.MAX_INPUT_BYTES + 16).toInt())
        val res = failure(big)
        assertTrue(res is ParseError.FileTooLarge)
    }

    @Test
    fun `preamp clamps to plus minus 30`() {
        val text = """
            Preamp: -99 dB
            Filter 1: ON PK Fc 1000 Hz Gain 1.0 dB Q 1.0
        """.trimIndent()
        val p = success(text)
        assertEquals(-30f, p.preampDB, 0.0001f)

        val text2 = """
            Preamp: 99 dB
            Filter 1: ON PK Fc 1000 Hz Gain 1.0 dB Q 1.0
        """.trimIndent()
        val p2 = success(text2)
        assertEquals(30f, p2.preampDB, 0.0001f)
    }

    @Test
    fun `bell legacy token is rejected with InvalidFormat`() {
        val text = """
            Preamp: 0.0 dB
            Filter 1: ON Bell Fc 1000 Hz Gain 1.0 dB Q 1.0
        """.trimIndent()
        val res = failure(text)
        assertTrue(res is ParseError.InvalidFormat)
    }

    @Test
    fun `comment styles are ignored`() {
        val text = """
            # comment with hash
            // comment with slashes
            ; comment with semi
            Preamp: -1.0 dB
            Filter 1: ON PK Fc 1000 Hz Gain 1.0 dB Q 1.0
        """.trimIndent()
        val p = success(text)
        assertEquals(1, p.filters.size)
    }

    @Test
    fun `irregular whitespace is tolerated`() {
        val text = "Preamp:  -1.5   dB\n" +
            "Filter   1:    ON   PK   Fc   1000   Hz   Gain   1.0   dB   Q   1.0\n"
        val p = success(text)
        assertEquals(-1.5f, p.preampDB, 0.001f)
        assertEquals(1, p.filters.size)
        assertNotNull(p.filters[0])
    }
}
