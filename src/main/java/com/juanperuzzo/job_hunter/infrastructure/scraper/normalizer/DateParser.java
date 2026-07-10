package com.juanperuzzo.job_hunter.infrastructure.scraper.normalizer;

import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

public class DateParser {

    private static final Pattern DAYS_AGO = Pattern.compile(".*(?:ha|a)\\s+(\\d+)\\s+dias?.*");
    private static final Pattern DAY_MONTH = Pattern.compile(".*?(\\d{1,2})\\s+(?:de\\s+)?([a-z]{3,}).*");
    private static final DateTimeFormatter ISO_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final Clock clock;

    public DateParser(Clock clock) {
        this.clock = clock;
    }

    public DateParser() {
        this(Clock.systemDefaultZone());
    }

    public Optional<LocalDate> parse(String rawDate) {
        if (rawDate == null || rawDate.isBlank()) {
            return Optional.empty();
        }

        var date = normalize(rawDate);
        var today = LocalDate.now(clock);

        if (date.contains("nova") || date.contains("hoje")) {
            return Optional.of(today);
        }
        if (date.contains("ontem")) {
            return Optional.of(today.minusDays(1));
        }

        var daysAgoMatcher = DAYS_AGO.matcher(date);
        if (daysAgoMatcher.matches()) {
            return Optional.of(today.minusDays(Long.parseLong(daysAgoMatcher.group(1))));
        }

        var dayMonthMatcher = DAY_MONTH.matcher(date);
        if (dayMonthMatcher.matches()) {
            var day = Integer.parseInt(dayMonthMatcher.group(1));
            var month = parseMonth(dayMonthMatcher.group(2));
            if (month.isPresent()) {
                var parsedDate = LocalDate.of(today.getYear(), month.get(), day);
                if (parsedDate.isAfter(today)) {
                    parsedDate = parsedDate.minusYears(1);
                }
                return Optional.of(parsedDate);
            }
        }

        try {
            return Optional.of(LocalDate.parse(date.substring(0, Math.min(10, date.length())), ISO_FORMAT));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static Optional<Month> parseMonth(String rawMonth) {
        var shortened = rawMonth.substring(0, Math.min(3, rawMonth.length()));
        return switch (shortened) {
            case "jan" -> Optional.of(Month.JANUARY);
            case "fev" -> Optional.of(Month.FEBRUARY);
            case "mar" -> Optional.of(Month.MARCH);
            case "abr" -> Optional.of(Month.APRIL);
            case "mai" -> Optional.of(Month.MAY);
            case "jun" -> Optional.of(Month.JUNE);
            case "jul" -> Optional.of(Month.JULY);
            case "ago" -> Optional.of(Month.AUGUST);
            case "set" -> Optional.of(Month.SEPTEMBER);
            case "out" -> Optional.of(Month.OCTOBER);
            case "nov" -> Optional.of(Month.NOVEMBER);
            case "dez" -> Optional.of(Month.DECEMBER);
            default -> Optional.empty();
        };
    }

    private static String normalize(String value) {
        if (value == null) return "";
        var cleaned = value.trim().toLowerCase(Locale.ROOT);
        var normalized = Normalizer.normalize(cleaned, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return normalized.replaceAll("\\bjr\\b", "junior");
    }
}
