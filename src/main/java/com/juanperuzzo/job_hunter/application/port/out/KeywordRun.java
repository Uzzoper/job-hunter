package com.juanperuzzo.job_hunter.application.port.out;

/**
 * Outcome of a single search-keyword run inside a source fetch (issue #116,
 * linkedin-async-fetch spec). Emitted by the scraper's per-keyword loop so
 * callers can see which terms succeeded, timed out or failed — no keyword is
 * skipped silently anymore.
 *
 * @param term      the search keyword as configured (trimmed, never blank)
 * @param status    {@link Status#OK} when the keyword produced jobs,
 *                  {@link Status#TIMEOUT} on a socket/read timeout,
 *                  {@link Status#ERROR} on any other failure
 * @param jobsFound raw jobs returned by this keyword's search (0 on failure)
 * @param durationMs wall-clock duration of the keyword's search call
 */
public record KeywordRun(String term, Status status, int jobsFound, long durationMs) {

    public enum Status {
        OK, TIMEOUT, ERROR;

        /** Lowercase wire format ("ok" | "timeout" | "error") per the status payload contract. */
        public String code() {
            return name().toLowerCase();
        }
    }

    public static KeywordRun ok(String term, int jobsFound, long durationMs) {
        return new KeywordRun(term, Status.OK, jobsFound, durationMs);
    }

    public static KeywordRun timeout(String term, long durationMs) {
        return new KeywordRun(term, Status.TIMEOUT, 0, durationMs);
    }

    public static KeywordRun error(String term, long durationMs) {
        return new KeywordRun(term, Status.ERROR, 0, durationMs);
    }
}
