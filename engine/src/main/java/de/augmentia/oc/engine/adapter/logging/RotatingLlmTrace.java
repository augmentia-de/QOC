package de.augmentia.oc.engine.adapter.logging;

import de.augmentia.oc.engine.core.contract.provider.ModelEvent;
import de.augmentia.oc.engine.core.contract.provider.LlmTrace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Appends the raw LLM exchange to a dedicated size-rotating file. The default
 * deployment uses 2 MiB per file and 20 files ({@code llm.log.0} ...
 * {@code llm.log.19}).
 *
 * <p>The trace is best-effort: a broken log file must never abort a model
 * call, so write failures are logged to the main log and otherwise ignored.
 */
public final class RotatingLlmTrace implements LlmTrace, AutoCloseable {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final Logger logger;
    private final FileHandler handler;

    private RotatingLlmTrace(Logger logger, FileHandler handler) {
        this.logger = logger;
        this.handler = handler;
    }

    public static RotatingLlmTrace open(Path file, long limitBytes, int count) {
        if (file == null) throw new IllegalArgumentException("file must not be null");
        if (limitBytes <= 0) throw new IllegalArgumentException("limitBytes must be positive");
        if (count <= 0) throw new IllegalArgumentException("count must be positive");
        Path absolute = file.toAbsolutePath().normalize();
        try {
            Path parent = absolute.getParent();
            if (parent != null) Files.createDirectories(parent);
            FileHandler handler = new FileHandler(absolute.toString(), limitBytes, count, true);
            handler.setFormatter(new PlainFormatter());
            handler.setEncoding("UTF-8");
            Logger logger = Logger.getLogger("qoc.llm." + SEQUENCE.incrementAndGet());
            logger.setUseParentHandlers(false);
            logger.addHandler(handler);
            logger.setLevel(java.util.logging.Level.INFO);
            return new RotatingLlmTrace(logger, handler);
        } catch (IOException e) {
            throw new IllegalStateException("cannot open LLM trace " + absolute, e);
        }
    }

    @Override
    public void request(String endpoint, String body) {
        StringBuilder entry = new StringBuilder();
        entry.append("===== ").append(Instant.now()).append(" REQUEST =====\n");
        if (endpoint != null) entry.append("POST ").append(endpoint).append('\n');
        entry.append(body == null ? "" : body).append('\n');
        write(entry.toString());
    }

    @Override
    public void response(int status, List<ModelEvent> events) {
        StringBuilder entry = new StringBuilder();
        entry.append("===== ").append(Instant.now()).append(" RESPONSE status=")
                .append(status).append(" =====\n");

        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        StringBuilder tools = new StringBuilder();
        String finish = null;
        String usage = null;
        String failure = null;

        if (events != null) {
            for (ModelEvent event : events) {
                if (event instanceof ModelEvent.TextDelta delta) {
                    text.append(delta.text());
                } else if (event instanceof ModelEvent.ReasoningDelta delta) {
                    reasoning.append(delta.text());
                } else if (event instanceof ModelEvent.ToolCallRequested call) {
                    tools.append("[tool] ").append(call.name().value()).append(' ')
                            .append(call.input()).append('\n');
                } else if (event instanceof ModelEvent.ToolFailed failed) {
                    tools.append("[tool failed] ").append(failed.message()).append('\n');
                } else if (event instanceof ModelEvent.Finished done) {
                    finish = done.reason().name();
                    usage = "input=" + done.usage().input() + " output=" + done.usage().output();
                } else if (event instanceof ModelEvent.ProviderFailed providerFailure) {
                    failure = providerFailure.kind() + " " + providerFailure.message()
                            + (providerFailure.status().isPresent()
                            ? " status=" + providerFailure.status().orElseThrow() : "");
                }
            }
        }

        if (!reasoning.isEmpty()) entry.append("[reasoning]\n").append(reasoning).append('\n');
        if (!text.isEmpty()) entry.append("[text]\n").append(text).append('\n');
        if (!tools.isEmpty()) entry.append(tools);
        if (finish != null) entry.append("[finish] ").append(finish).append(" ").append(usage).append('\n');
        if (failure != null) entry.append("[failure] ").append(failure).append('\n');
        write(entry.toString());
    }

    @Override
    public void transportFailure(String message) {
        write("===== " + Instant.now() + " TRANSPORT FAILURE =====\n"
                + (message == null ? "" : message) + '\n');
    }

    @Override
    public void error(int status, String body) {
        write("===== " + Instant.now() + " ERROR status=" + status + " =====\n"
                + (body == null ? "" : body) + '\n');
    }

    private void write(String entry) {
        try {
            logger.info(entry);
        } catch (RuntimeException e) {
            // Best-effort: never break the model call because of logging.
        }
    }

    @Override
    public void close() {
        logger.removeHandler(handler);
        handler.close();
    }

    private static final class PlainFormatter extends Formatter {
        @Override
        public String format(LogRecord record) {
            String message = record.getMessage();
            return message == null ? "" : message;
        }
    }
}
