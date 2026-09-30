package com.juanperuzzo.job_hunter.unit.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.juanperuzzo.job_hunter.web.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("GlobalExceptionHandler tests")
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private Logger handlerLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        appender = new ListAppender<>();
        appender.start();
        handlerLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        handlerLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("generic handler should log the exception with a stack trace and hide details from the client")
    void handleGenericException_whenUnexpectedError_shouldLogStackTraceAndHideDetails() {
        var cause = new IllegalStateException("transient gateway failure");

        var response = handler.handleGenericException(cause);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        var body = response.getBody();
        assertNotNull(body, "an error body must always be returned");
        assertEquals("An unexpected error occurred", body.get("message"));
        assertFalse(String.valueOf(body).contains("transient gateway failure"),
                "the exception message must never leak to the client");
        assertFalse(String.valueOf(body).contains("IllegalStateException"),
                "the stack trace must never leak to the client");

        var events = appender.list;
        assertEquals(1, events.size(), "the generic handler must log exactly one event");
        var event = events.get(0);
        assertEquals(Level.ERROR, event.getLevel(), "an unexpected 500 must be logged at ERROR level");
        assertNotNull(event.getThrowableProxy(), "the stack trace must be attached to the log event");
        var stackTrace = event.getThrowableProxy().getStackTraceElementProxyArray();
        assertNotNull(stackTrace);
        assertTrue(stackTrace.length > 0, "the logged throwable must carry its stack trace");
    }
}