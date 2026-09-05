package com.juanperuzzo.job_hunter.domain.exception;

/**
 * Thrown when the bot memory sync pipeline encounters an unrecoverable I/O or
 * parse error. Mapped to HTTP 500 by the global exception handler — the caller
 * cannot fix this (infrastructure issue).
 */
public class BotMemorySyncException extends RuntimeException {

    public BotMemorySyncException(String message) {
        super(message);
    }

    public BotMemorySyncException(String message, Throwable cause) {
        super(message, cause);
    }
}
