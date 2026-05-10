package io.dscope.camel.agent.testing;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

public final class TestLogCaptureSupport implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender;
    private final Level previousLevel;

    private TestLogCaptureSupport(Logger logger, Level level) {
        this.logger = logger;
        this.appender = new ListAppender<>();
        this.appender.start();
        this.previousLevel = logger.getLevel();
        logger.addAppender(appender);
        logger.setLevel(level);
    }

    public static TestLogCaptureSupport capture(Class<?> loggerType, Level level) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerType);
        return new TestLogCaptureSupport(logger, level == null ? Level.INFO : level);
    }

    public List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    public List<String> matchingMessages(String... tokens) {
        if (tokens == null || tokens.length == 0) {
            return messages();
        }
        List<String> normalized = List.of(tokens).stream()
            .filter(token -> token != null && !token.isBlank())
            .map(token -> token.toLowerCase(Locale.ROOT))
            .toList();
        return messages().stream()
            .filter(message -> {
                String lower = message.toLowerCase(Locale.ROOT);
                return normalized.stream().anyMatch(lower::contains);
            })
            .collect(Collectors.toList());
    }

    public Path writeMessages(TestArtifactSupport.ArtifactBundle bundle,
                              String fileName,
                              String note,
                              String... tokens) throws IOException {
        return bundle.writeLines(fileName, matchingMessages(tokens), note);
    }

    public Path writeMessageGroup(TestArtifactSupport.ArtifactBundle bundle,
                                  String groupName,
                                  String note,
                                  String... tokens) throws IOException {
        String fileName = (groupName == null || groupName.isBlank()) ? "captured.log" : groupName + ".log";
        return writeMessages(bundle, fileName, note, tokens);
    }

    @Override
    public void close() {
        logger.setLevel(previousLevel);
        logger.detachAppender(appender);
    }
}