package com.rupeek.flow.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/** Test helper: captures everything logged under {@code com.rupeek.flow} at the given level (message + MDC). */
final class LogCapture implements AutoCloseable {

    private final Logger logger = (Logger) LoggerFactory.getLogger("com.rupeek.flow");
    private final Level previous = logger.getLevel();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    LogCapture(Level level) {
        logger.setLevel(level);
        appender.start();
        logger.addAppender(appender);
    }

    List<ILoggingEvent> events() {
        return List.copyOf(appender.list);
    }

    /** All captured output flattened to one string: formatted message plus MDC values plus any throwable message. */
    String text() {
        return appender.list.stream()
                .map(e -> e.getFormattedMessage() + " " + e.getMDCPropertyMap() + " "
                        + (e.getThrowableProxy() == null ? "" : e.getThrowableProxy().getMessage()))
                .collect(Collectors.joining("\n"));
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        logger.setLevel(previous);
    }
}
