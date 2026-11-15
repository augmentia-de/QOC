package de.augmentia.oc.engine.acp;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * ndjson transport over stdin/stdout for ACP (JSON-RPC 2.0).
 * Reads one JSON object per line from stdin, writes one per line to stdout.
 */
public final class AcpTransport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BufferedReader reader;
    private final BufferedWriter writer;

    public AcpTransport() {
        this.reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        this.writer = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
    }

    /** Read next JSON-RPC message (one line). Returns null on EOF. */
    public Map<String, Object> readMessage() throws IOException {
        String line = reader.readLine();
        if (line == null) return null;
        line = line.trim();
        if (line.isEmpty()) return null;
        @SuppressWarnings("unchecked")
        Map<String, Object> msg = MAPPER.readValue(line, Map.class);
        return msg;
    }

    /** Write a JSON-RPC message as one line to stdout. */
    public synchronized void writeMessage(Map<String, Object> msg) throws IOException {
        String json = MAPPER.writeValueAsString(msg);
        writer.write(json);
        writer.write('\n');
        writer.flush();
    }

    public void close() {
        try { writer.close(); } catch (IOException ignored) {}
        try { reader.close(); } catch (IOException ignored) {}
    }
}