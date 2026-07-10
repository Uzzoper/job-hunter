package com.juanperuzzo.job_hunter.unit.infrastructure.scraper.normalizer;

import com.juanperuzzo.job_hunter.infrastructure.scraper.normalizer.DateParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DateParser tests")
class DateParserTest {

    private DateParser dateParser;

    @BeforeEach
    void setUp() {
        var fixedClock = Clock.fixed(Instant.parse("2026-07-08T12:00:00Z"), ZoneId.of("America/Sao_Paulo"));
        dateParser = new DateParser(fixedClock);
    }

    @Test
    @DisplayName("parse 'Hoje' should return today")
    void parseHoje_shouldReturnToday() {
        assertEquals(LocalDate.of(2026, 7, 8), dateParser.parse("Hoje").orElseThrow());
    }

    @Test
    @DisplayName("parse 'Nova' should return today")
    void parseNova_shouldReturnToday() {
        assertEquals(LocalDate.of(2026, 7, 8), dateParser.parse("Nova").orElseThrow());
    }

    @Test
    @DisplayName("parse 'Ontem' should return yesterday")
    void parseOntem_shouldReturnYesterday() {
        assertEquals(LocalDate.of(2026, 7, 7), dateParser.parse("Ontem").orElseThrow());
    }

    @Test
    @DisplayName("parse 'há 3 dias' should return 3 days ago")
    void parseHa3Dias_shouldReturn3DaysAgo() {
        assertEquals(LocalDate.of(2026, 7, 5), dateParser.parse("há 3 dias").orElseThrow());
    }

    @Test
    @DisplayName("parse 'a 5 dias' should return 5 days ago")
    void parseA5Dias_shouldReturn5DaysAgo() {
        assertEquals(LocalDate.of(2026, 7, 3), dateParser.parse("a 5 dias").orElseThrow());
    }

    @Test
    @DisplayName("parse '28 abr' should return April 28")
    void parse28Abr_shouldReturnApril28() {
        assertEquals(LocalDate.of(2026, 4, 28), dateParser.parse("28 abr").orElseThrow());
    }

    @Test
    @DisplayName("parse '15 de março' should return March 15")
    void parse15DeMarco_shouldReturnMarch15() {
        assertEquals(LocalDate.of(2026, 3, 15), dateParser.parse("15 de março").orElseThrow());
    }

    @Test
    @DisplayName("parse ISO '2026-05-25' should return May 25")
    void parseIsoDate_shouldReturnCorrectDate() {
        assertEquals(LocalDate.of(2026, 5, 25), dateParser.parse("2026-05-25").orElseThrow());
    }

    @Test
    @DisplayName("parse '2026-05-25T14:00:00.000Z' should extract date portion")
    void parseIsoWithTime_shouldExtractDate() {
        assertEquals(LocalDate.of(2026, 5, 25), dateParser.parse("2026-05-25T14:00:00.000Z").orElseThrow());
    }

    @Test
    @DisplayName("parse empty string should return empty")
    void parseEmpty_shouldReturnEmpty() {
        assertTrue(dateParser.parse("").isEmpty());
    }

    @Test
    @DisplayName("parse null should return empty")
    void parseNull_shouldReturnEmpty() {
        assertTrue(dateParser.parse(null).isEmpty());
    }

    @Test
    @DisplayName("parse unknown text should return empty")
    void parseUnknown_shouldReturnEmpty() {
        assertTrue(dateParser.parse("qualquer coisa").isEmpty());
    }

    @Test
    @DisplayName("parse '28 dez' with today in July should adjust year back to previous December")
    void parseFutureMonth_shouldAdjustYear() {
        assertEquals(LocalDate.of(2025, 12, 28), dateParser.parse("28 dez").orElseThrow());
    }
}
