package de.augmentia.oc.engine.runtime.provider.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Server-Sent-Events reader. The whole body is read; a frame is dispatched on
 * an empty line or at end of file.
 */
public final class SseReader {

    public record SseFrame(String event, String data) {
        public SseFrame {
            if (event == null) event = "message";
            if (data == null) data = "";
        }
    }

    public List<SseFrame> read(InputStream input) {
        if (input == null) throw new IllegalArgumentException("input must not be null");
        List<SseFrame> frames = new ArrayList<>();
        String event = null;
        StringBuilder data = null;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (data != null) {
                        frames.add(new SseFrame(event, data.toString()));
                        event = null;
                        data = null;
                    }
                    continue;
                }
                if (line.startsWith(":")) continue;
                if (line.startsWith("event:")) {
                    event = value(line, "event:");
                    continue;
                }
                if (line.startsWith("data:")) {
                    String value = value(line, "data:");
                    if (data == null) data = new StringBuilder(value);
                    else data.append('\n').append(value);
                }
                // id: and retry: are irrelevant for the engine.
            }
            if (data != null) {
                frames.add(new SseFrame(event, data.toString()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read event stream", e);
        }
        return List.copyOf(frames);
    }

    private static String value(String line, String prefix) {
        String value = line.substring(prefix.length());
        return value.startsWith(" ") ? value.substring(1) : value;
    }
}
